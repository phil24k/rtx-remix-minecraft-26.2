# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Experimental Linux entry point for the Windows renderer through Wine."""

import argparse
import json
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import sys


# These are startup overrides, consumed by RemixRenderer before startup().
# Radeon still requires compatible runtime binaries and working GL/VK interop.
RADEON_OPTIONS = {
    "rtx.upscalerType": "0",
    "rtx.enableRayReconstruction": "false",
    "rtx.dlfg.enable": "false",
    "rtx.isReflexEnabled": "false",
    "rtx.integrateIndirectMode": "1",
    "rtx.isShaderExecutionReorderingSupported": "false",
    "rtx.sparseRendering.enableSparseRendering": "false",
}
GL_EXTENSIONS = (
    "GL_EXT_memory_object",
    "GL_EXT_memory_object_fd",
    "GL_EXT_semaphore",
    "GL_EXT_semaphore_fd",
)
VK_EXTENSIONS = (
    "VK_KHR_ray_tracing_pipeline",
    "VK_KHR_acceleration_structure",
    "VK_KHR_external_memory_fd",
    "VK_KHR_external_semaphore_fd",
)


def default_prefix():
    data = Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local/share")))
    return data / "rtx-remix-minecraft" / "wineprefix"


def wine_environment(prefix, radeon, inherited):
    """Use the bundled renderer while retaining unrelated Wine overrides."""
    env = dict(inherited)
    env["WINEPREFIX"] = str(prefix)
    env["WINEARCH"] = "win64"
    overrides = []
    for entry in env.get("WINEDLLOVERRIDES", "").split(";"):
        names, separator, value = entry.partition("=")
        remaining = [n.strip() for n in names.split(",") if n.strip().lower() != "d3d9"]
        if separator and any(remaining):
            overrides.append(",".join(remaining) + "=" + value)
    env["WINEDLLOVERRIDES"] = ";".join([*overrides, "d3d9=n"])
    if radeon:
        options = env.get("RTXREMIX_EXTRA_RTXCONF", "").strip(";")
        preset = ";".join(f"{key}={value}" for key, value in RADEON_OPTIONS.items())
        # The explicit compatibility mode wins over inherited conflicting options.
        env["RTXREMIX_EXTRA_RTXCONF"] = ";".join(filter(None, [options, preset]))
    return env


def capture(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        return False, str(error)
    output = result.stdout if result.returncode == 0 else result.stderr or result.stdout
    return result.returncode == 0, output.strip()


def validate_launcher(path):
    """Reject native Linux, Windows ARM64, and 32-bit launcher binaries."""
    if not path.is_file() or path.name.lower() != "prismlauncher.exe":
        raise ValueError("--launcher must point to an existing Windows x64 PrismLauncher.exe.")
    with path.open("rb") as executable:
        header = executable.read(64)
        if len(header) != 64 or header[:2] != b"MZ":
            raise ValueError("PrismLauncher.exe is not a Windows PE executable.")
        executable.seek(struct.unpack_from("<I", header, 0x3C)[0])
        pe = executable.read(6)
    if pe != b"PE\0\0\x64\x86":
        raise ValueError("PrismLauncher.exe must be the Windows x64 build.")


def doctor(wine):
    """Inspect host prerequisites without initializing a Wine prefix."""
    failures = 0

    def report(passed, label):
        nonlocal failures
        print(f"{'OK' if passed else 'FAIL'}: {label}")
        failures += not passed

    report(platform.system() == "Linux", "Linux host")
    report(platform.machine().lower() in ("x86_64", "amd64"), "x86_64 CPU")
    ok, version = capture([wine, "--version"])
    report(ok, f"Wine: {version}")
    for command, extensions in ((["glxinfo"], GL_EXTENSIONS), (["vulkaninfo"], VK_EXTENSIONS)):
        ok, output = capture(command)
        report(ok, f"{command[0]} query" + ("" if ok else f": {output[:300]}"))
        if not ok:
            continue
        words = set(re.findall(r"[A-Za-z0-9_]+", output))
        for extension in extensions:
            report(extension in words, extension)
        if command[0] == "glxinfo":
            version = re.search(r"OpenGL core profile version string:\s*(\d+)\.(\d+)", output)
            report(version is not None and tuple(map(int, version.groups())) >= (4, 5), "OpenGL 4.5 core context")
            for line in output.splitlines():
                if "OpenGL renderer string:" in line or "OpenGL core profile version string:" in line:
                    print(line.strip())
            if "GL_NV_timeline_semaphore" not in words:
                print("WARN: GL_NV_timeline_semaphore not reported; live semaphore import must be tested.")
        else:
            print("NOTE: Vulkan extensions may belong to different GPUs; GL and Remix must use the same GPU.")
    print("Experimental Wine route. Host checks do not verify Windows GL extensions or Remix rendering.")
    print("Radeon additionally needs --radeon and a successful live runtime/interop test. See docs/LINUX.md.")
    return 1 if failures else 0


def launch(args):
    if platform.system() != "Linux" or platform.machine().lower() not in ("x86_64", "amd64"):
        raise ValueError("This Wine launcher requires x86_64 Linux.")
    launcher = args.launcher.expanduser().resolve()
    validate_launcher(launcher)
    prefix = args.prefix.expanduser().resolve()
    registry = prefix / "system.reg"
    if registry.is_file() and "#arch=win32" in registry.read_text(errors="replace"):
        raise ValueError("This is a 32-bit Wine prefix; choose a new prefix for Windows x64.")
    wine = shutil.which(args.wine)
    if wine is None:
        raise ValueError(f"Wine executable not found: {args.wine}")
    env = wine_environment(prefix, args.radeon, os.environ)
    # A Windows path inside the prefix avoids depending on Wine's optional Z: mapping.
    command = [wine, str(launcher), "--dir", r"C:\rtx-remix-prism"]
    if args.instance:
        command += ["--launch", args.instance]
    if args.dry_run:
        keys = ["WINEPREFIX", "WINEARCH", "WINEDLLOVERRIDES"]
        if args.radeon:
            keys.append("RTXREMIX_EXTRA_RTXCONF")
        print(json.dumps({"command": command, "environment": {k: env[k] for k in keys}}, indent=2))
        return 0
    print("Experimental Wine launch; rendering is unverified. See docs/LINUX.md.", flush=True)
    if args.radeon:
        print("Radeon test mode requests DLSS, RR, frame generation, Reflex, NRC, SER and sparse rendering off.", flush=True)
    prefix.mkdir(parents=True, exist_ok=True)
    return subprocess.call(command, env=env, cwd=launcher.parent)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("doctor", help="inspect host prerequisites; does not launch Wine apps")
    check.add_argument("--wine", default="wine", help="Wine executable (default: wine)")
    run = commands.add_parser("launch", help="open Windows Prism in a dedicated Wine prefix")
    run.add_argument("--launcher", type=Path, required=True, help="path to Windows x64 PrismLauncher.exe")
    run.add_argument("--prefix", type=Path, default=default_prefix(), help="isolated Wine prefix path")
    run.add_argument("--wine", default="wine", help="Wine executable (default: wine)")
    run.add_argument("--instance", help="Prism instance ID; requires signing in to Prism normally")
    run.add_argument("--radeon", action="store_true", help="experimental non-DLSS/non-NRC startup options")
    run.add_argument("--dry-run", action="store_true", help="print launch settings without writing files")
    args = parser.parse_args(argv)
    try:
        return doctor(args.wine) if args.command == "doctor" else launch(args)
    except (OSError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
