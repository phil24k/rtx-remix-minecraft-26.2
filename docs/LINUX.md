# Linux (experimental Wine route)

The renderer uses Windows DLLs, a Win32 window, and Windows external-memory
handles. Linux Java cannot load those DLLs. This repo provides a Linux launcher
for Windows Prism, Java, Minecraft, and the renderer running together in Wine.
This is experimental compatibility tooling. A live Radeon RX 7000-series diagnostic with
Mesa 26.2.2, Wine 11.17, and Windows Java 25 passed runtime startup, Windows GL
extension checks, timeline semaphore imports, shared-image imports, and three
640x360 submit/present round trips. This diagnostic used an empty scene;
Minecraft gameplay and performance have not been verified.

## Prerequisites

- x86_64 Linux, Python 3.10 or newer, and a current 64-bit Wine build.
- Working hardware OpenGL 4.5 and Vulkan ray tracing on the same GPU.
- For NVIDIA, an RTX GPU meeting this fork's hardware requirements and a
  current Linux NVIDIA driver. The Windows driver version in the main README
  is not a Linux driver version requirement.
- For Radeon, ray tracing hardware and drivers exposing the required GL/Vulkan
  interop. This is an additional unverified combination; see [Radeon](RADEON.md).
- Windows x64 Prism Launcher, obtained by the user from the
  [official download page](https://prismlauncher.org/download/windows/), and
  Windows x64 Java 25. Sign in through Prism with an account that owns Minecraft.

The native Linux version of Prism selects Linux Java and Linux LWJGL natives,
so use the Windows build inside Wine. The helper does not download Prism,
read account credentials, or launch Minecraft directly.

## Check and launch

From the repository root, inspect the host without creating a Wine prefix:

```bash
./linux/rtx-remix doctor
```

This uses `wine --version`, `glxinfo`, and `vulkaninfo`. Install the diagnostic
utilities using your distro's packages if missing. A passing result establishes
only partial host prerequisites: it does not test Wine's Windows GL extension
translation, external-handle imports, or the renderer. Vulkan extension names
can come from different devices on a multi-GPU system.

Extract the Windows x64 portable Prism distribution, then open it with:

```bash
./linux/rtx-remix launch --launcher "$HOME/Applications/Prism-Windows/PrismLauncher.exe"
```

The helper uses a dedicated 64-bit Wine prefix at
`${XDG_DATA_HOME:-$HOME/.local/share}/rtx-remix-minecraft/wineprefix` and a Prism
profile inside its `drive_c/rtx-remix-prism/`. It leaves the host's normal Prism
profile and default Wine prefix alone. Use `--prefix /absolute/path` to select
another prefix, or `--wine /path/to/wine` to select another Wine build.

In Windows Prism:

1. Sign in normally with your Microsoft account.
2. Select Windows x64 Java 25 in Java settings; test the Java installation.
3. Create a Minecraft 26.2 instance and install Fabric Loader 0.20.1 or newer.
4. Add Fabric API `0.161.0+26.2` and the matching **packaged** renderer JAR.
   Use the Radeon package for Radeon. A lightweight `build/libs/` JAR does not
   include the native runtime. Do not combine it with Sodium, Iris, or OptiFine.
5. Start the instance from Prism. After creating it, `--instance INSTANCE_ID`
   can select it from this helper.

For a Radeon experiment, start Prism with:

```bash
./linux/rtx-remix launch --launcher /path/to/PrismLauncher.exe --radeon
```

This requests startup overrides that turn off DLSS, ray reconstruction, frame
generation, Reflex, NRC, shader execution reordering, and sparse rendering.
It does not add missing driver features or patch NVIDIA's runtime. Inspect the
effective settings and logs to confirm the runtime accepted them. Use a fresh
instance; runtime user settings may be saved by the renderer.

`--dry-run` prints the launch command and selected environment without starting
Wine or creating files. Arguments are passed as a list, preserving spaces.
The helper forces `d3d9=n` so Wine loads the packaged renderer; it preserves
unrelated DLL overrides. Do not install ordinary DXVK over the mod's `d3d9.dll`.

## Driver and Wine requirements

Wine must translate `GL_EXT_memory_object_win32` and `GL_EXT_semaphore_win32`
to the host's fd-based extensions. [Wine's OpenGL source](https://github.com/wine-mirror/wine/blob/master/dlls/opengl32/wgl.c)
contains that translation. Extension support varies by Wine release and driver;
no minimum Wine version has been established with a successful game launch.

The mod also configures timeline semaphores through `GL_NV_timeline_semaphore`.
[Mesa 26.1 added this extension to radeonsi](https://docs.mesa3d.org/relnotes/26.1.0.html),
so the `NV` name alone does not establish an NVIDIA-only hardware requirement.
The exact host driver and Wine combination still needs a live interop test.

If `doctor` cannot access the display or Vulkan devices, run it from a normal
desktop terminal. On a hybrid-GPU machine select the same discrete GPU for
OpenGL and Vulkan using your distro's normal GPU selection tools.

## Verify a game launch

Check the instance's `logs/latest.log` for successful RTX Remix initialization
and color/depth interop imports. A title screen or vanilla fallback is not proof
of path tracing. Enter a world, inspect nearby and distant terrain, entities,
particles, animation, and material normals; then test resource reload, resize,
and F11. Keep the Minecraft and native runtime logs when any step fails.

## Build and test on Linux

With a JDK 25 selected through `JAVA_HOME`:

```bash
./gradlew build test
python3 -m unittest discover -s linux -p 'test_*.py' -v
```

Linux compiles the Java mod and runs headless tests without trying to compile
or stage Windows DLLs. `build/libs/` contains a development JAR.

To produce installable Windows/Wine JARs on Linux, reuse the unchanged bridge
from NVIDIA's official v0.1.0 archive, linked in the main README. The staging
helper requires Python 3.12 or newer and verifies both the archive SHA-256 and
the bridge/header source hashes before extracting anything:

```bash
python3 packaging/stage_published_runtime.py /path/to/nvidia-original-v0.1.0.zip
./gradlew package -Parch=win64 -Pgpu=nvidia -x compileNativeWin64
./gradlew package -Parch=win64 -Pgpu=radeon -x compileNativeWin64
```

The packaged JARs are in `build/distributions/`. Gradle uses the existing POSIX
Packman bootstrap to fetch pinned runtime textures. This does not compile a
Linux renderer: the verified Windows DLLs still run through Wine. If the JNI
source or header changes, the staging helper refuses reuse; compile the bridge
on Windows instead.

The pinned Minecraft-specific API 0.16 runtime comes from an unpublished native
branch; making a native Linux build requires matching native runtime changes,
JNI loading, native window access, and fd-based interop.

## Portable Linux JAR

`./gradlew package -Parch=win64 -Pgpu=radeon -Plinux=true` (or the default
`-Pgpu=nvidia`) builds a JAR ending in `-linux.jar` that carries its own Wine,
Windows Java, and Windows LWJGL natives, pinned by SHA-256 in
`linux/portable-runtime.properties`. Drop it with Fabric API into any
launcher's Minecraft 26.2 Fabric instance; no wrapper command or system Wine
is needed, and Flatpak launchers work because nothing outside the sandbox is
used. The build needs `xz` on the build machine.

On x86-64 Linux, `LinuxWineLauncher` runs as the first pre-launch entrypoint,
extracts the payload once to `$XDG_DATA_HOME/rtx-remix-minecraft/portable/`
(about 1 GB), and restarts the game, before Minecraft opens a window, as
Windows Java under the bundled Wine with the launcher's own command line. The
Linux JVM waits as the parent and exits with the game's exit code. Set
`-Drtxremix.linux.wine=false` or `RTXREMIX_LINUX_WINE=0` to stay on Linux
Java. Inside an existing Wine launch it does nothing.

DLSS is unavailable under Wine, so the default upscaler is TAA-U. XeSS, which
the runtime bundles and which runs on AMD GPUs, can be chosen in the Remix
graphics menu; a saved choice in `user.conf` is kept. The runtime has no FSR.
