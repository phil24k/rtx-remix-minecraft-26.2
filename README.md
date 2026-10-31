# RTX Remix for Minecraft 26.2 (Unofficial)

A Fabric port of NVIDIA's Minecraft RTX Remix beta to Minecraft Java 26.2.
It renders the game through NVIDIA's RTX Remix runtime with path tracing,
reflections, and upscaling, on Windows and Linux, with NVIDIA or AMD Radeon GPUs.

**[Download the universal JAR - Preview 4 (Windows + Linux, NVIDIA + Radeon)](https://github.com/phil24k/rtx-remix-minecraft-26.2/releases/download/v0.1.0-mc26.2-preview.4/rtx-remix-renderer-0.1.0-mc26.2-preview.4-universal.jar)**

A smaller [Radeon JAR](https://github.com/phil24k/rtx-remix-minecraft-26.2/releases/download/v0.1.0-mc26.2-preview.4/rtx-remix-renderer-0.1.0-mc26.2-preview.4-radeon.jar)
leaves out the DLSS libraries. Both run on Windows and Linux.
[Release notes and checksums](https://github.com/phil24k/rtx-remix-minecraft-26.2/releases/tag/v0.1.0-mc26.2-preview.4)

This is an unofficial community port. NVIDIA does not maintain or support it.
The original project is **[NVIDIA RTX Remix for Minecraft](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft)**.
NVIDIA developed the original mod, JNI bridge, and native rendering runtime.

## Requirements

- Minecraft Java 26.2, Fabric Loader 0.20.1 or newer, and Fabric API 0.161.0+26.2.
- Java 25 in the launcher (on Linux too: the bundled Windows Java is used only
  for the restarted game).
- Windows x64, or x86-64 Linux.
- A ray-tracing GPU:
  - NVIDIA RTX 30-series or newer; on Windows, driver 616.92 or newer.
  - AMD Radeon RX 7000-series or newer; on Linux, Mesa 26.1 or newer.

DLSS works only with NVIDIA on Windows. Elsewhere the default upscaler is
TAA-U, and XeSS can be chosen in the Remix graphics menu. The runtime has no FSR.

Do not combine this renderer with Sodium, Iris, OptiFine, or other renderer
replacements. Use a separate game instance for it.

## Install

1. Create a Minecraft 26.2 Fabric instance in your launcher (Prism, the
   official launcher with Fabric, Lunar, and so on).
2. Put the renderer JAR and Fabric API in that instance's `mods` folder.
3. Launch. The first launch unpacks the native runtime into the game folder.

On **Linux**, nothing else is needed: the JAR carries its own Wine and Windows
Java. Before Minecraft opens its window, the mod restarts the game under them
with the launcher's own command line, and the launcher keeps tracking the game
as usual. The first Linux launch also unpacks about 1 GB to
`~/.local/share/rtx-remix-minecraft/portable/` (inside a Flatpak launcher, its
app data folder) and takes noticeably longer. To start without the renderer,
add `-Drtxremix.linux.wine=false` to the instance's JVM arguments. Details are
in [Linux](docs/LINUX.md).

On **Radeon**, the mod builds texture mipmaps on the CPU to avoid a driver
problem; this happens automatically. See [NVIDIA and Radeon](docs/RADEON.md).

In game: **Options > RTX Remix Settings** for graphics settings, **Alt+X** for
Remix's own menu (including the upscaler), and `/rtx status` to check that the
renderer started. Baseline material textures are included; LabPBR resource
packs add metallic reflections and surface detail when enabled above the
Fabric resource pack.

## How it works

The JAR is a Fabric mod with NVIDIA's native RTX Remix runtime inside. It is
not a separate edition of Minecraft or a launcher. Minecraft keeps running the
game; the mod captures its geometry, textures, materials, and lights and sends
them through a JNI bridge to the runtime, which path-traces the scene with
Vulkan. Minecraft draws the interface with OpenGL into images shared with the
runtime. The mod forces Minecraft's OpenGL backend for this, even if Vulkan is
chosen in the video menu.

The runtime is Windows code. On Linux it runs under Wine, which the JAR bundles
together with Windows Java and the Windows LWJGL libraries.

This is a client-side renderer: servers do not need the mod, though server
resource packs and custom content affect what is rendered.

## Status

Verified in game:

- **Linux, AMD Radeon RX 7000-series (Mesa 26.2.2):** the released Radeon JAR,
  started from a Flatpak launcher with no Wine installed, restarted itself under
  the bundled Wine 11.18 and initialized the renderer on a multiplayer server.
  A pixel probe confirmed correct output across window resizes.
- **Windows, NVIDIA:** Preview 3 initialized the renderer through Lunar.
  Preview 4 has not been re-tested on NVIDIA.

Not yet verified: Radeon on Windows with this port (quartz1216's fork, which
this Radeon code comes from, reports Windows testing on Minecraft 1.21.1), and
NVIDIA on Linux.

Automated: 771 Java tests and 12 Linux launcher tests pass. Tests do not
establish visual correctness in a running world.

Known limitations:

- Distant animated blocks can stay on their first frame on Radeon.
- Held glass and ice materials, inherited from the 26.3 port, are not fixed.
- Standalone see-through text displays are depth-tested; they do not show
  through walls as in vanilla.
- A distant block-edge grid has been reported on some servers, including one
  with a server-provided Nexo resource pack; it has not been investigated.

## Build and test

Install JDK 25 and Git and set `JAVA_HOME`. Compiling the JNI bridge needs
Windows with the Visual Studio C++ build tools:

```powershell
.\gradlew.bat build test package -Parch=win64
```

Without the C++ toolchain, on Windows or Linux, reuse the unchanged bridge from
NVIDIA's original
[v0.1.0 release ZIP](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/releases/download/v0.1.0/rtx-remix-renderer-0.1.0-202610230634-170197a.zip)
(Python 3.12 or newer):

```bash
python3 packaging/stage_published_runtime.py PATH_TO_ORIGINAL_RELEASE_ZIP
./gradlew build test package -Parch=win64 -x compileNativeWin64
```

The staging script verifies NVIDIA's archive checksum and the exact bridge
source and header hashes, and refuses incompatible sources.

Package options:

| Option | Effect |
| --- | --- |
| `-Pgpu=nvidia` (default) | Includes the DLSS libraries. |
| `-Pgpu=radeon` | Leaves out the DLSS libraries; adds `-radeon` to the name. |
| `-Plinux=true` | Bundles Wine, Windows Java, and Windows LWJGL natives (pinned by SHA-256 in `linux/portable-runtime.properties`); adds `-linux`. Needs `-Parch=win64` and `xz`. |

The release's universal JAR is `-Pgpu=nvidia -Plinux=true`; the Radeon JAR is
`-Pgpu=radeon -Plinux=true`. The AMD texture workaround is selected at run time
in every package. Packaged JARs are written to `build/distributions/`; the
lightweight JAR in `build/libs/` has no native runtime and cannot be installed.

On Linux, `./gradlew build test` compiles and tests without the Windows
toolchain, and `python3 -m unittest discover -s linux -p 'test_*.py'` runs the
Linux launcher tests. The Java tests use Fabric Loader JUnit, so Minecraft
classes receive their real mixin transformations, including all 49 configured
mixin targets. The optional OpenGL regression needs OpenGL 4.5 and a display:

```powershell
.\gradlew.bat gpuTest -Parch=win64 -x compileNativeWin64
```

## Port changes

Compared with the community 26.3 port:

- Target Minecraft 26.2, Java 25, and matching Fabric dependencies.
- Use 26.2's Blaze3D types and GLFW window, native window handle, input, and
  presentation.
- Adapt first-person items, world render callbacks, breaking decals, sprite
  coordinates, Copper Beacon models, cloud color, and block codecs.
- Extrude atlas gutters for static and animated textures and material maps, to
  avoid black mip borders.
- Replay 26.2's name-tag pass, including named-entity holograms.
- Reattach the compositor framebuffer when the render target changes,
  including when OpenGL reuses a texture name after a resize.
- Build texture mipmaps on the CPU on AMD GPUs (from quartz1216's Radeon fork).
- Import shared images as dedicated allocations, fixing scrambled output on Radeon.
- Copy each frame to the runtime from Minecraft's frame texture, so resizing
  works under Wine.
- Restart the game under bundled Wine on Linux (`LinuxWineLauncher`).

## Provenance

NVIDIA's original mod -> the Apache-2.0 community 26.3 port by
[yeezybeezydeezy](https://github.com/yeezybeezydeezy/rtx-remix-minecraft/tree/port/26.3)
(forked at commit d0bde279ddd021ab59c5c26c40770d39422fa985) -> this 26.2 fork by
Phil24k. The Radeon texture code is ported from
[quartz1216's Radeon fork](https://github.com/quartz1216/rtx-remix-minecraft-radeon);
its review is in [docs/SOURCE_REVIEW.md](docs/SOURCE_REVIEW.md). The title-screen
splash reads **RTX REMIX FORKED BY Phil24k!**.

The repository holds the Java mod, JNI bridge source and header, tests, material
assets, licenses, documentation, and build tools. Native runtime binaries,
Wine, Java, and Minecraft dependencies are downloaded during the build;
packaged JARs are published in GitHub Releases. Inherited developer guides and
Prism tooling may still describe Minecraft 1.21.1 or the 26.3 branch; this
README describes what this build supports.

## Contributing

Follow [CONTRIBUTING.md](CONTRIBUTING.md). Contributions use the
[Developer Certificate of Origin](https://developercertificate.org/);
sign commits with `git commit -s`.

## License and attribution

The mod source is Apache-2.0. See LICENSE, NOTICE, and THIRD_PARTY_NOTICES.md.
NVIDIA's runtime, Wine (LGPL-2.1), Eclipse Temurin (GPL-2.0 with Classpath
Exception), and LWJGL (BSD-3-Clause) keep their own license terms.
This is not an official Minecraft product and is not approved by Mojang or Microsoft.
