# RTX Remix for Minecraft 26.2 (Unofficial)

A Windows x64 Fabric port of NVIDIA's Minecraft RTX Remix beta to Minecraft Java 26.2.
It uses NVIDIA's RTX Remix runtime for path tracing, DLSS, and reflections.

This is an unofficial community port. NVIDIA does not maintain or support it.
The source is based on NVIDIA's 0.1.0 release and the Apache-2.0 community
26.3 port by yeezybeezydeezy, commit d0bde279ddd021ab59c5c26c40770d39422fa985:

- https://github.com/NVIDIAGameWorks/rtx-remix-minecraft
- https://github.com/yeezybeezydeezy/rtx-remix-minecraft/tree/port/26.3

## Validation

The local Windows x64 build passes all 748 automated tests with no skips.
A Windows x64 launch through Lunar was verified: the native renderer
initialized and imported its color/depth interop images.
Visual parity and a full playthrough have not been verified.

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
48 configured mixin targets, including actual class transformation.
Automated tests do not establish visual parity in a running world.

## Port changes

- Target 26.2, Java 25, and matching Fabric dependencies throughout packaging.
- Use 26.2's Blaze3D types and GLFW window, native HWND, input, and presentation.
- Adapt first-person item submission, world render callbacks, breaking decals,
  sprite-coordinate wrappers, Copper Beacon models, cloud color, and block codecs.
- Preserve vanilla render traversal while substituting Remix's draw and present.
- Update closed dynamic-texture detection for 26.2's retained image objects.
- Extrude atlas gutters for static and animated textures/material maps to address black mip borders.
- Restore the inherited test suite and verify injection targets against 26.2.

This remains experimental. The 26.3 source port's held-glass/ice material
limitation has not been addressed, and visual testing is needed for all
worlds, dimensions, resource packs, and third-party mod combinations. A distant
block-edge grid has been reported with a server-provided Nexo resource pack;
the atlas-padding patch is included, but that server-specific issue is not
confirmed resolved.

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

## License and attribution

The mod source is Apache-2.0. See LICENSE, NOTICE, and THIRD_PARTY_NOTICES.md.
NVIDIA's bundled runtime components retain their separate license terms.
This is not an official Minecraft product and is not approved by Mojang or Microsoft.
