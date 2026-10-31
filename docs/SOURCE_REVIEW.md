# Radeon source review

Review target: `quartz1216/rtx-remix-minecraft-radeon`, branch `main`, commit
`162b31f` as fetched for this integration. This is a source review, not a malware
scan or a guarantee about prebuilt binaries.

The fork was compared to its common ancestor with this 26.2 repository.
The added DDS writer, texture-cache code, texture upload changes, tests,
lava-light changes, packaging changes, and metadata were read. Relevant
runtime/build paths were compared separately.

## Findings

- No malicious behavior was found in the reviewed Radeon additions. The
  imported texture code builds mipmaps, encodes DDS files, loads those files
  through the existing Remix API, and maintains its own texture cache.
- The texture additions contain no network requests, process spawning,
  credential access, encoded executable payloads, or persistence hooks.
- The fork does not change the JNI bridge, Remix C header, Packman runtime
  pins, Gradle wrapper, CI workflows, or dependency repositories relative to
  the common ancestor. Both repositories pin
  `minecraft-rtx2-71a581da` for the native runtime.
- Its packaging change removes DLSS DLLs; it does not introduce a new binary
  download. This integration makes that removal specific to `-Pgpu=radeon` so
  the NVIDIA package retains its existing features.
- Only the texture workaround and its tests were imported. The unrelated
  lava-light feature and fork-specific launcher assets were not imported.

The imported commits are `b7cc831`, `ce2f852`, `a603796`, and `fc068a6`.
Integration changes adapt Minecraft names, preserve 26.2 atlas padding, and
enable the workaround automatically on AMD rather than changing NVIDIA's
default texture path. Original copyright and license notices are retained.
The integration also validates DDS input dimensions, retains odd texture edges
in generated mips, and separates cached files by texture format and purpose.

## Limits

No release JAR from the Radeon fork was downloaded or executed. Bundled
NVIDIA runtime DLLs are separate binary dependencies and were not audited or
reproducibly rebuilt here. Their matching source branch is not public.
Local installable packages reuse NVIDIA's official v0.1.0 native payload, whose
release archive SHA-256 is
`0a4b84fc6a1eb13294daba7bb015431a13ad660febd6cb201ee30ba91b1301fa`.
The repository's existing staging helper verified this checksum and its pinned
bridge/header source fingerprints before extracting the payload. This checks
provenance and compatibility; it does not inspect the binary implementation.
The upstream runtime's documented telemetry and crash reporting are separate
from the reviewed texture changes; consult [Crash Reports and Privacy](PLAYING.md)
and the native runtime's permissions settings. There is no claim that an
arbitrary downloaded release is malware-free.
