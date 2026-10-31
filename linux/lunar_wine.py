#!/usr/bin/env python3
# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Run a Lunar Fabric profile's Java command using Windows Java in Wine."""

import argparse
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile


def host_path(value, mappings):
    for guest, host in mappings:
        if value == guest or value.startswith(guest + "/"):
            return host + value[len(guest):]
    return value


def windows_path(value, mappings):
    return "Z:" + host_path(value, mappings) if value.startswith("/") else value


def path_list(value, mappings):
    return ";".join(windows_path(part, mappings) for part in value.split(":"))


def convert_arguments(arguments, config):
    mappings = config["path_mappings"]
    expanded = []
    for value in arguments:
        if value.startswith("@") and not value.startswith("@@"):
            source = Path(host_path(value[1:], mappings))
            expanded.extend(shlex.split(source.read_text(), comments=True))
        else:
            expanded.append(value)
    result = []
    classpath_next = False
    for value in expanded:
        if classpath_next:
            result.append(path_list(value, mappings))
            classpath_next = False
        elif value in ("-cp", "-classpath", "--class-path", "-p", "--module-path"):
            result.append(value)
            classpath_next = True
        elif value.startswith(("-Djava.library.path=", "-Dorg.lwjgl.librarypath=")):
            # One consistent Windows native directory replaces Linux libraries.
            continue
        elif value.startswith("-D") and "=" in value:
            key, content = value.split("=", 1)
            result.append(key + "=" + (path_list(content, mappings) if content.startswith("/") else content))
        elif value.startswith("--class-path="):
            result.append("--class-path=" + path_list(value.split("=", 1)[1], mappings))
        else:
            result.append(windows_path(value, mappings))
    natives = windows_path(config["natives"], mappings)
    # JVM properties must precede the main class. Insertion at the beginning
    # also works when the original command uses an argument file.
    return ["-Djava.library.path=" + natives, "-Dorg.lwjgl.librarypath=" + natives,
            "-Duser.home=" + windows_path(config["game_home"], mappings), *result]


def quote_java_argument(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\r", "\\r") + '"'


def launch(config, command):
    if not command:
        raise ValueError("Lunar must pass its Java executable and arguments to the wrapper")
    arguments = convert_arguments(command[1:], config)
    runtime = Path(config["runtime"])
    runtime.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    for key in ("LD_LIBRARY_PATH", "LD_PRELOAD", "VK_DRIVER_FILES", "VK_ICD_FILENAMES", "FLATPAK_ID"):
        env.pop(key, None)
    env["PATH"] = "/usr/local/bin:/usr/bin:/bin"
    env["WINEPREFIX"] = config["prefix"]
    env["WINEARCH"] = "win64"
    env["WINEDEBUG"] = "-all"
    env["WINEDLLOVERRIDES"] = "d3d9=n;mscoree,mshtml="
    env["RTXREMIX_EXTRA_RTXCONF"] = config.get("remix_options", "")
    # The host handoff starts in a host-visible directory; the game still
    # needs its own directory for runtime configs, caches, and relative assets.
    cwd = Path(config["game_directory"])
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=runtime,
                                     prefix="java-", suffix=".args", delete=False) as output:
        argument_file = Path(output.name)
        for argument in arguments:
            output.write(quote_java_argument(argument) + "\n")
    child = None
    try:
        print("[RTX Remix] Starting Windows Java through Wine with Windows graphics libraries.", flush=True)
        child = subprocess.Popen([config.get("wine", "/usr/bin/wine"), config["java"],
                                  "@" + windows_path(str(argument_file), [])],
                                 cwd=cwd, env=env, start_new_session=True,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 text=True, errors="replace")

        def stop(signum, frame):
            if child.poll() is None:
                os.killpg(child.pid, signum)

        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        with (runtime / "last-game.log").open("w", encoding="utf-8") as log:
            for line in child.stdout:
                log.write(line)
                log.flush()
                print(line, end="", flush=True)
        return child.wait()
    finally:
        argument_file.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    try:
        return launch(json.loads(args.config.read_text()), command)
    except (OSError, ValueError) as error:
        print("[RTX Remix] Wine launch failed: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
