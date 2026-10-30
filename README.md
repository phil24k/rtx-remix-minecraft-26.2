# RTX Remix for Minecraft 26.2 (Unofficial)

A Windows x64 Fabric port of NVIDIA's Minecraft RTX Remix beta to Minecraft Java 26.2.
It uses NVIDIA's RTX Remix runtime for path tracing, DLSS, and reflections.

This is an unofficial community port. NVIDIA does not maintain or support it.
The original project is **[NVIDIA RTX Remix for Minecraft](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft)**.
NVIDIA developed the original mod, JNI bridge, and native rendering runtime.

This 26.2 fork adapts the Apache-2.0 community 26.3 port by
[yeezybeezydeezy](https://github.com/yeezybeezydeezy/rtx-remix-minecraft/tree/port/26.3),
starting at commit d0bde279ddd021ab59c5c26c40770d39422fa985. Phil24k's fork
contains the subsequent 26.2 integration and atlas-padding changes.

The provenance is NVIDIA -> community 26.3 port -> Phil24k 26.2 fork.
GitHub displays the community repository as the immediate fork parent and
identifies NVIDIA's repository as the original source of the fork network.
The local `upstream` remote points to NVIDIA; `community` points to the 26.3 port.

The title-screen splash reads **RTX REMIX FORKED BY Phil24k!**.

## Validation

The local Windows x64 build passes all 751 automated tests with no skips.
A Windows x64 launch through Lunar was verified: the native renderer
initialized and imported its color/depth interop images.
A separate real OpenGL pixel-readback regression passes for resized render
targets whose texture names are reused. Live F11 switching, server labels,
visual parity, and a full playthrough still need in-game confirmation.

## Requirements

- Minecraft Java 26.2 and Java 25.
- Fabric Loader 0.20.1 and Fabric API 0.161.0+26.2.
- Windows x64 and an NVIDIA RTX 30-series or newer GPU.
- NVIDIA driver 616.92 or newer.

Use a separate game directory. Do not combine this renderer with Sodium,
Iris, OptiFine, or other renderer replacements. The mod selects the OpenGL
backend needed for its shared-image interop with Remix's Vulkan renderer.

## What you install

The packaged `.jar` is a Fabric mod with bundled native NVIDIA runtime files.
It is not a separate edition of Minecraft or a replacement launcher.

| Part | Purpose |
| --- | --- |
| Minecraft Java 26.2 | Runs the game, world simulation, and multiplayer client. |
| Fabric Loader and Fabric API | Load the mod and provide its Minecraft integration hooks. |
| RTX Remix mod JAR | Captures Minecraft geometry, textures, materials, and lights. |
| JNI bridge and RTX Remix runtime | Native DLLs that render the captured scene with path tracing and DLSS. |
| Lunar profile / dedicated instance | Selects Minecraft, Fabric, memory, and an isolated game directory. |

At launch, the packaged JAR extracts its native runtime into the game directory.
The Java code sends the scene through a JNI bridge into NVIDIA's runtime.
Remix renders using Vulkan; Minecraft uses OpenGL to share images with it and
draw the interface. Choosing Vulkan in Minecraft's video menu does not switch
this port to Minecraft's Vulkan backend: the mod currently forces OpenGL.

This is a client renderer. A remote server does not need the mod to display
its world, but server resource packs and custom content can affect rendering.
The profile is ordinary launcher configuration; the mod and native runtime
provide the rendering features.

## Install

Place the packaged mod JAR and Fabric API in a dedicated Fabric 26.2
instance's mods folder. In Lunar, turn Lunar features off for that profile.
The JAR unpacks NVIDIA's runtime on first launch. Baseline material textures
are included; extra LabPBR resource packs can supply metallic reflections
and surface detail. Enable user packs above the Fabric resource pack.

Graphics settings: Options, RTX Remix Settings. Alt+X opens Remix's overlay.
In a world, `/rtx status` reports whether the renderer and SDK initialized.

## Build and test

Install JDK 25, Git, and Visual Studio C++ build tools. The Python staging
option below also requires Python 3.12 or newer. Set JAVA_HOME to the
JDK, then run:

```powershell
.\gradlew.bat build test package -Parch=win64
```

The packaged JAR is under build/distributions. The lightweight JAR under
build/libs does not include the native runtime and is not a standalone install.

The native bridge source and API header are unchanged from NVIDIA's v0.1.0.
To build without installing the C++ toolchain, download NVIDIA's original
[v0.1.0 release ZIP](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/releases/download/v0.1.0/rtx-remix-renderer-0.1.0-202610230634-170197a.zip),
then stage its matching published native binaries:

```powershell
python packaging/stage_published_runtime.py PATH_TO_ORIGINAL_RELEASE_ZIP
.\gradlew.bat build test package -Parch=win64 -x compileNativeWin64
```

The staging script verifies NVIDIA's archive checksum and the exact bridge
source/header hashes. It refuses incompatible native sources or managed
runtime junctions. Normal Gradle tasks acquire the separate runtime textures.

The unit suite uses Fabric Loader JUnit so Minecraft classes receive their
real mixin transformations. Tests cover chunk and material processing,
geometry capture, lighting configuration, persistence, camera paths, and the
49 configured mixin targets, including actual class transformation.
Automated tests do not establish visual parity in a running world.

To run the optional OpenGL regression on a machine with OpenGL 4.5:

```powershell
.\gradlew.bat gpuTest -Parch=win64 -x compileNativeWin64
```

This hidden-window test checks actual framebuffer pixels after a texture is
replaced under the same OpenGL name. It runs separately from the headless
suite so build servers do not require a graphics driver.

## Port changes

- Target 26.2, Java 25, and matching Fabric dependencies throughout packaging.
- Use 26.2's Blaze3D types and GLFW window, native HWND, input, and presentation.
- Adapt first-person item submission, world render callbacks, breaking decals,
  sprite-coordinate wrappers, Copper Beacon models, cloud color, and block codecs.
- Preserve vanilla render traversal while substituting Remix's draw and present.
- Update closed dynamic-texture detection for 26.2's retained image objects.
- Extrude atlas gutters for static and animated textures/material maps to address black mip borders.
- Restore the inherited test suite and verify injection targets against 26.2.
- Replay 26.2's separate name-tag feature pass, including named-entity holograms.
- Keep standalone see-through text displays visible as depth-tested scene geometry;
  they currently do not reproduce vanilla's through-wall overlay behavior.
- Reattach the compositor framebuffer when the render target object changes,
  including when OpenGL reuses its numeric texture name after a resize.

This remains experimental. The 26.3 source port's held-glass/ice material
limitation has not been addressed, and visual testing is needed for all
worlds, dimensions, resource packs, and third-party mod combinations. A distant
block-edge grid has been reported only on certain servers, including Mystic
Craft with its server-provided Nexo resource pack. The atlas-padding patch is
included; that server-specific interaction has not been investigated further.

## Repository contents

The repository contains the Java mod, JNI bridge source/header, tests, bundled
material assets, licenses, documentation, Gradle wrapper, and packaging tools.
NVIDIA runtime binaries and Minecraft dependencies are downloaded during
build/staging; packaged JARs belong in GitHub Releases rather than Git history.
Launcher profiles, accounts, tokens, saves, server packs, logs, screenshots,
local configuration, and build caches are excluded.

The 26.2 README is the reference for this port. Inherited developer guides,
agent instructions, and Prism tooling may still describe upstream Minecraft
1.21.1 or the community 26.3 branch; they do not expand the supported version
of this build.

## Contributing

Follow [CONTRIBUTING.md](CONTRIBUTING.md). Contributions use the
[Developer Certificate of Origin](https://developercertificate.org/);
sign commits with `git commit -s`.

## License and attribution

The mod source is Apache-2.0. See LICENSE, NOTICE, and THIRD_PARTY_NOTICES.md.
NVIDIA's bundled runtime components retain their separate license terms.
This is not an official Minecraft product and is not approved by Mojang or Microsoft.
