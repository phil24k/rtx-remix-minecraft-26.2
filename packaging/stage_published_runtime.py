# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Stage the unmodified, matching win64 runtime and JNI bridge from NVIDIA's release."""

import argparse
import hashlib
from pathlib import Path
import shutil
import zipfile

RELEASE_SHA256 = "0a4b84fc6a1eb13294daba7bb015431a13ad660febd6cb201ee30ba91b1301fa"
RELEASE_JAR = "mods/rtx-remix-renderer-0.1.0-202610230634-170197a.jar"
SOURCE_HASHES = {
    "rtxremix-java/native/remixbridge.cpp": "2a7fa0d4eeaf8de6f1ebd3127f8f8830c277583af258cff866f3a4d001bf9054",
    "rtxremix-java/native/include/remix_c.h": "afaea2187faf8e71001de0fb4869789e405c2b00e35a5c38c16d7da7c9bee9d7",
}
SOURCE_MARKER = "source: NVIDIA Minecraft release v0.1.0 (170197a), unmodified win64 native payload\n"


def stage(archive: Path, root: Path) -> None:
    with archive.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != RELEASE_SHA256:
            raise ValueError("The archive does not match NVIDIA's v0.1.0 release checksum")
    for name, expected in SOURCE_HASHES.items():
        content = (root / name).read_text(encoding="utf-8").encode("utf-8")
        if hashlib.sha256(content).hexdigest() != expected:
            raise ValueError(f"{name} changed: rebuild the JNI bridge from source instead")

    runtime = root / "remix-runtime/win64"
    bridge = root / "build/native/win64"
    marker = runtime / ".local-sdk-source"
    if runtime.is_junction() or runtime.is_symlink():
        raise ValueError("Refusing to write into a managed runtime junction")
    if runtime.exists() and (not marker.is_file() or marker.read_text() != SOURCE_MARKER):
        raise ValueError("A different runtime is staged; use a fresh checkout")

    with zipfile.ZipFile(archive) as outer, outer.open(RELEASE_JAR) as stream, zipfile.ZipFile(stream) as jar:
        prefix = "native/remix-sdk-win64/"
        for entry in jar.infolist():
            if entry.is_dir() or not entry.filename.startswith(prefix):
                continue
            relative = Path(entry.filename.removeprefix(prefix))
            destination_root = bridge if relative.name in {"remixbridge.dll", "remixbridge.pdb"} else runtime
            destination = (destination_root / relative).resolve()
            if not destination.is_relative_to(destination_root.resolve()):
                raise ValueError(f"Archive entry escapes its destination: {entry.filename}")
            destination.parent.mkdir(parents=True, exist_ok=True)
            with jar.open(entry) as source, destination.open("wb") as output:
                shutil.copyfileobj(source, output)
    marker.write_text(SOURCE_MARKER, encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, help="NVIDIA's original v0.1.0 release ZIP")
    args = parser.parse_args()
    stage(args.archive, Path(__file__).resolve().parents[1])
    print("Staged NVIDIA's matching win64 runtime and bridge. Build with -x compileNativeWin64.")
