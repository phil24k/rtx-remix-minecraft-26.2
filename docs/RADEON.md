# NVIDIA and Radeon

The 26.2 codebase now includes the CPU texture-mipmap workaround from
[quartz1216's Radeon fork](https://github.com/quartz1216/rtx-remix-minecraft-radeon).
That fork targets Minecraft 1.21.1 and reports Windows testing on an RX 9070 XT.
Its JAR cannot be installed into Minecraft 26.2. This integration ports the
texture changes to 26.2 and retains this fork's atlas border padding.

## GPU selection

The mod selects CPU-built mipmaps when the OpenGL vendor identifies AMD or
ATI. NVIDIA continues using the runtime's existing GPU texture path, including
DLSS when its libraries and hardware are available. The native runtime is the
same API 0.16 build already pinned by this repo; it selects its own ray tracing
and upscaling capabilities. No vendor-ID spoofing or replacement native DLLs
are used.

For diagnosis, override the texture selection with a JVM argument:

```text
-Drtxremix.texture.cpuAtlasMips=true
```

Setting it to `false` restores runtime-generated mips on AMD as well. This
property controls texture mip generation, not GPU selection or ray tracing
support.

The workaround covers block, entity, and particle texture uploads, including
normal, height, material, emissive, and subsurface maps. It writes DDS files
under the instance's `rtx-remix-cache/`. File-backed entity textures are
recreated when their pixels change, so a partial memory upload cannot leave
their mip chain stale.

## Packaging

Build a complete package on Windows with Java 25 and the normal MSVC toolchain:

```powershell
.\gradlew.bat package -Parch=win64 -Pgpu=nvidia
.\gradlew.bat package -Parch=win64 -Pgpu=radeon
```

The default is `nvidia`, preserving the existing distribution contents. The
`radeon` package has a `-radeon` filename suffix and omits `nvngx_dlss*.dll`,
following the Radeon fork's packaging policy. Package verification rejects
those DLLs in a Radeon artifact. Both packages contain the same Java code and
automatic AMD texture workaround; the option selects payload contents.
Install one renderer JAR at a time, with Fabric API for Minecraft 26.2.
Linux can also assemble these Windows/Wine packages from checksum-verified
published native files; see [Linux packaging](LINUX.md#build-and-test-on-linux).

Use a separate game directory for each GPU package. `NativeExtractor` treats
their different version suffixes as a runtime update, so a change of package
refreshes `remix-sdk/` rather than retaining removed DLSS files.

## Validation and limitations

The imported fork reports broken lower texture mips on Radeon, static distant
animation frames, and crashes when certain ray tracing modes are changed.
Keep its runtime's AMD defaults; do not select raygen/RGS variants or enable
Radeon GPU Detective. The CPU workaround repairs the initial mip chain, but
the runtime still regenerates animation patches on the GPU: distant animated
blocks can therefore remain on their initial frame.

This 26.2 integration requires an in-game test on both vendors before being
called verified GPU support. CPU DDS tests and Java compilation cannot prove
that the native renderer initializes, displays correct pixels, or survives
resource reloads. No new live Radeon or NVIDIA rendering result is claimed.

Linux uses the experimental [Wine route](LINUX.md). A low-level Radeon RX 7000-series
diagnostic passed real device startup and three shared-image frame round trips;
this is not a Minecraft gameplay test. A native Linux runtime is not supplied
by either repository.
