/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.linux;

import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Makes the portable Linux JAR work from any launcher. The renderer is a Windows
 * runtime, so on Linux this pre-launch entrypoint restarts the game, before
 * Minecraft opens its window, as Windows Java inside the Wine build bundled in
 * the JAR, with the exact command line the launcher used. The Linux JVM stays
 * alive as the parent, so the launcher keeps its process, log, and exit code.
 *
 * <p>Runs only on x86-64 Linux, only when the JAR carries the payload, and never
 * inside the Wine child. {@code -Drtxremix.linux.wine=false} or
 * {@code RTXREMIX_LINUX_WINE=0} keeps the game on Linux Java, where the renderer
 * then stays off. Any failure before the restart also leaves the game on Linux
 * Java rather than blocking it from starting.
 */
public final class LinuxWineLauncher implements PreLaunchEntrypoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(LinuxWineLauncher.class);
    static final String CHILD_ENV = "RTXREMIX_WINE_CHILD";
    private static final String PAYLOAD = "linux/";

    /**
     * Options the renderer needs on Wine and non-NVIDIA GPUs, applied unless the
     * user's own RTXREMIX_EXTRA_RTXCONF or conf files set them.
     */
    static final Map<String, String> DEFAULT_OPTIONS = new LinkedHashMap<>();
    static {
        DEFAULT_OPTIONS.put("rtx.enableRayReconstruction", "false");
        DEFAULT_OPTIONS.put("rtx.dlfg.enable", "false");
        DEFAULT_OPTIONS.put("rtx.isReflexEnabled", "false");
        DEFAULT_OPTIONS.put("rtx.integrateIndirectMode", "1");
        DEFAULT_OPTIONS.put("rtx.isShaderExecutionReorderingSupported", "false");
        DEFAULT_OPTIONS.put("rtx.sparseRendering.enableSparseRendering", "false");
        // DLSS needs NVIDIA NGX, which Wine does not provide. TAA-U is the
        // fallback; XeSS can be chosen in the Remix menu and is kept once saved.
        DEFAULT_OPTIONS.put("rtx.upscalerType", "3");
    }

    @Override
    public void onPreLaunch() {
        if (!shouldRelaunch()) {
            return;
        }
        Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(RtxRemixCommonMod.MOD_ID);
        Optional<Path> pins = mod.flatMap(m -> m.findPath(PAYLOAD + "portable-runtime.properties"));
        if (pins.isEmpty()) {
            LOGGER.info("Linux detected, but this JAR has no bundled Wine; the RTX renderer needs the "
                    + "portable Linux JAR or a Wine launch. See docs/LINUX.md.");
            return;
        }
        int exitCode;
        try {
            Path runtime = prepareRuntime(mod.get(), pins.get());
            exitCode = launchUnderWine(runtime);
        } catch (Exception e) {
            LOGGER.error("Could not start the game under the bundled Wine; continuing on Linux Java "
                    + "with the RTX renderer off", e);
            return;
        }
        System.out.flush();
        System.err.flush();
        // The game ran in the Wine child. Halting skips Minecraft entirely here.
        Runtime.getRuntime().halt(exitCode);
    }

    static boolean shouldRelaunch() {
        if (System.getenv(CHILD_ENV) != null) return false;
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("linux")) return false;
        String arch = System.getProperty("os.arch", "");
        if (!arch.equals("amd64") && !arch.equals("x86_64")) return false;
        if ("false".equalsIgnoreCase(System.getProperty("rtxremix.linux.wine"))) return false;
        String env = System.getenv("RTXREMIX_LINUX_WINE");
        return env == null || !(env.equals("0") || env.equalsIgnoreCase("false"));
    }

    /** Extracts the payload once per payload version and returns its directory. */
    private static Path prepareRuntime(ModContainer mod, Path pinsFile) throws IOException {
        byte[] pins = Files.readAllBytes(pinsFile);
        String id = payloadId(mod, pins);
        Path base = dataHome().resolve("rtx-remix-minecraft").resolve("portable");
        Path runtime = base.resolve(id);
        Files.createDirectories(base);
        try (FileChannel channel = FileChannel.open(base.resolve(".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            if (Files.exists(runtime.resolve(".complete"))) {
                return runtime;
            }
            LOGGER.info("Extracting bundled Wine and Windows Java to {} (first launch only)", runtime);
            deleteRecursively(runtime);
            Path staging = base.resolve(id + ".partial");
            deleteRecursively(staging);
            Files.createDirectories(staging);
            extractTarXz(mod.findPath(PAYLOAD + "wine.tar.xz").orElseThrow(), staging.resolve("wine"));
            extractTarXz(mod.findPath(PAYLOAD + "jre.tar.xz").orElseThrow(), staging.resolve("jre"));
            Path nativesSource = mod.findPath(PAYLOAD + "natives").orElseThrow();
            Path natives = Files.createDirectories(staging.resolve("natives"));
            try (DirectoryStream<Path> dlls = Files.newDirectoryStream(nativesSource)) {
                for (Path dll : dlls) {
                    Files.copy(dll, natives.resolve(dll.getFileName().toString()));
                }
            }
            Files.write(staging.resolve(".complete"), pins);
            Files.move(staging, runtime);
            removeOtherVersions(base, id);
            return runtime;
        }
    }

    private static int launchUnderWine(Path runtime) throws IOException, InterruptedException {
        Path wine = runtime.resolve("wine/bin/wine");
        Path java = runtime.resolve("jre/bin/java.exe");
        Path gameDir = FabricLoader.getInstance().getGameDir();
        List<String> self = WineCommandLine.parseProcCmdline(Files.readAllBytes(Path.of("/proc/self/cmdline")));
        if (self.size() < 2) {
            throw new IOException("Cannot read this JVM's command line");
        }
        List<String> arguments = WineCommandLine.convert(self.subList(1, self.size()),
                runtime.resolve("natives"), System.getProperty("user.home"), List.of());

        Path argsDir = Files.createDirectories(runtime.getParent().resolve("args"));
        Path argFile = argsDir.resolve("java-" + ProcessHandle.current().pid() + ".args");
        try (BufferedWriter writer = Files.newBufferedWriter(argFile, StandardCharsets.UTF_8)) {
            for (String argument : arguments) {
                writer.write(WineCommandLine.quoteForArgFile(argument));
                writer.newLine();
            }
        }

        ProcessBuilder builder = new ProcessBuilder(wine.toString(), java.toString(),
                "@" + WineCommandLine.windowsPath(argFile.toString()));
        builder.directory(Path.of(System.getProperty("user.dir")).toFile());
        builder.inheritIO();
        Map<String, String> env = builder.environment();
        env.remove("LD_PRELOAD");
        env.remove("LD_LIBRARY_PATH");
        Path prefix = runtime.getParent().resolve("prefix");
        env.put("WINEPREFIX", prefix.toString());
        env.put("WINEARCH", "win64");
        env.putIfAbsent("WINEDEBUG", "-all");
        env.put("WINEDLLOVERRIDES", dllOverrides(env.get("WINEDLLOVERRIDES")));
        env.put("RTXREMIX_EXTRA_RTXCONF", rendererOptions(env.get("RTXREMIX_EXTRA_RTXCONF"), gameDir));
        env.put(CHILD_ENV, "1");

        LOGGER.info("Linux: restarting the game as Windows Java under the bundled Wine for the RTX renderer");
        Process child = builder.start();
        Thread stopChild = new Thread(() -> stop(child, runtime, prefix), "rtx-remix-wine-stop");
        Runtime.getRuntime().addShutdownHook(stopChild);
        try {
            return child.waitFor();
        } finally {
            Files.deleteIfExists(argFile);
        }
    }

    /** Ends the Wine child when the launcher stops the game. */
    private static void stop(Process child, Path runtime, Path prefix) {
        if (!child.isAlive()) return;
        child.destroy();
        try {
            if (!child.waitFor(10, TimeUnit.SECONDS)) {
                child.destroyForcibly();
            }
            ProcessBuilder kill = new ProcessBuilder(runtime.resolve("wine/bin/wineserver").toString(), "-k");
            kill.environment().put("WINEPREFIX", prefix.toString());
            kill.start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best effort while the JVM shuts down.
        }
    }

    /** Keeps unrelated user overrides; forces the bundled d3d9.dll and skips Mono/Gecko prompts. */
    static String dllOverrides(String inherited) {
        List<String> entries = new ArrayList<>();
        if (inherited != null) {
            for (String entry : inherited.split(";")) {
                String name = entry.split("=", 2)[0].trim().toLowerCase();
                if (!entry.isBlank() && !name.equals("d3d9") && !name.equals("mscoree,mshtml")) {
                    entries.add(entry);
                }
            }
        }
        entries.add("d3d9=n");
        entries.add("mscoree,mshtml=");
        return String.join(";", entries);
    }

    /** Adds each default the user has not set through the environment or the game's conf files. */
    static String rendererOptions(String inherited, Path gameDir) {
        StringBuilder out = new StringBuilder(inherited == null ? "" : inherited);
        String userConf = readQuietly(gameDir.resolve("user.conf")) + "\n" + readQuietly(gameDir.resolve("rtx.conf"));
        for (Map.Entry<String, String> option : DEFAULT_OPTIONS.entrySet()) {
            String key = option.getKey();
            boolean setByEnv = inherited != null && (";" + inherited).contains(";" + key + "=");
            boolean setByConf = userConf.lines().anyMatch(line -> line.strip().startsWith(key + " ")
                    || line.strip().startsWith(key + "="));
            if (!setByEnv && !setByConf) {
                if (!out.isEmpty()) out.append(';');
                out.append(key).append('=').append(option.getValue());
            }
        }
        return out.toString();
    }

    private static String readQuietly(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void extractTarXz(Path archive, Path destination) throws IOException {
        try (InputStream in = new XZInputStream(new BufferedInputStream(Files.newInputStream(archive), 1 << 16))) {
            TarExtractor.extract(new BufferedInputStream(in, 1 << 16), destination, 1);
        }
    }

    private static Path dataHome() {
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && xdg.startsWith("/")) return Path.of(xdg);
        return Path.of(System.getProperty("user.home"), ".local", "share");
    }

    private static void removeOtherVersions(Path base, String keep) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(base)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry) && name.matches("[0-9a-f]{12}(\\.partial)?") && !name.equals(keep)) {
                    deleteRecursively(entry);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not remove old bundled Wine versions from {}", base, e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Names the extraction directory after the payload actually in the JAR, not
     * only its pins: a repacked payload with unchanged pins (for example, a
     * slimmer Wine) must not reuse an older extraction.
     */
    static String payloadId(ModContainer mod, byte[] pins) throws IOException {
        StringBuilder key = new StringBuilder(new String(pins, StandardCharsets.UTF_8));
        for (String name : new String[] {"wine.tar.xz", "jre.tar.xz"}) {
            Path entry = mod.findPath(PAYLOAD + name).orElseThrow(() -> new IOException("Missing " + name));
            key.append('\n').append(name).append('=').append(Files.size(entry));
        }
        return sha256(key.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
