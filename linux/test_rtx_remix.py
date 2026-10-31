# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import contextlib
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import rtx_remix


class LinuxLauncherTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.launcher = self.root / "launcher with spaces" / "PrismLauncher.exe"
        self.launcher.parent.mkdir()
        self.write_pe(0x8664)
        self.prefix = self.root / "prefix with spaces"
        self.args = ["launch", "--launcher", str(self.launcher), "--prefix", str(self.prefix)]
        for target, value in (("platform.system", "Linux"), ("platform.machine", "x86_64"),
                              ("shutil.which", "/usr/bin/wine")):
            mock = patch("rtx_remix." + target, return_value=value)
            mock.start()
            self.addCleanup(mock.stop)

    def write_pe(self, machine):
        header = bytearray(64)
        header[:2] = b"MZ"
        struct.pack_into("<I", header, 0x3C, 64)
        self.launcher.write_bytes(header + b"PE\0\0" + struct.pack("<H", machine))

    def test_launch_preserves_spaces_and_uses_isolated_windows_profile(self):
        with patch("rtx_remix.subprocess.call", return_value=23) as call:
            result = rtx_remix.main([*self.args, "--instance", "RTX 26.2"])
        self.assertEqual(result, 23)
        command = call.call_args.args[0]
        self.assertEqual(command, ["/usr/bin/wine", str(self.launcher), "--dir",
                                   r"C:\rtx-remix-prism", "--launch", "RTX 26.2"])
        self.assertEqual(call.call_args.kwargs["cwd"], self.launcher.parent)
        env = call.call_args.kwargs["env"]
        self.assertEqual(env["WINEPREFIX"], str(self.prefix))
        self.assertEqual(env["WINEARCH"], "win64")
        self.assertTrue(self.prefix.is_dir())

    def test_dry_run_does_not_create_prefix_or_invoke_wine_or_print_secrets(self):
        with patch.dict(os.environ, {"PRIVATE_TOKEN": "do-not-print"}), \
             patch("rtx_remix.subprocess.call") as call, \
             contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(rtx_remix.main([*self.args, "--dry-run"]), 0)
        call.assert_not_called()
        self.assertFalse(self.prefix.exists())
        self.assertNotIn("do-not-print", output.getvalue())
        data = json.loads(output.getvalue())
        self.assertEqual(data["environment"]["WINEPREFIX"], str(self.prefix))

    def test_rejects_32_bit_prefix_before_launch(self):
        self.prefix.mkdir()
        (self.prefix / "system.reg").write_text("WINE REGISTRY Version 2\n#arch=win32\n")
        with patch("rtx_remix.subprocess.call") as call, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(rtx_remix.main(self.args), 1)
        call.assert_not_called()

    def test_rejects_non_x64_windows_launchers(self):
        for machine in (0x14C, 0xAA64):
            with self.subTest(machine=machine):
                self.write_pe(machine)
                with self.assertRaisesRegex(ValueError, "Windows x64"):
                    rtx_remix.validate_launcher(self.launcher)
        self.launcher.write_bytes(b"\x7fELF")
        with self.assertRaisesRegex(ValueError, "Windows PE"):
            rtx_remix.validate_launcher(self.launcher)

    def test_radeon_mode_wins_over_conflicting_inherited_options(self):
        inherited = {"RTXREMIX_EXTRA_RTXCONF": "rtx.upscalerType=1;rtx.custom=true"}
        env = rtx_remix.wine_environment(self.prefix, True, inherited)
        options = dict(item.split("=", 1) for item in env["RTXREMIX_EXTRA_RTXCONF"].split(";"))
        self.assertEqual(options["rtx.custom"], "true")
        self.assertEqual(options["rtx.upscalerType"], "0")
        self.assertEqual(options["rtx.integrateIndirectMode"], "1")
        self.assertEqual(options["rtx.enableRayReconstruction"], "false")
        self.assertNotIn("WINEPREFIX", inherited)

    def test_nvidia_mode_preserves_runtime_options(self):
        inherited = {"RTXREMIX_EXTRA_RTXCONF": "rtx.upscalerType=1"}
        env = rtx_remix.wine_environment(self.prefix, False, inherited)
        self.assertEqual(env["RTXREMIX_EXTRA_RTXCONF"], inherited["RTXREMIX_EXTRA_RTXCONF"])

    def test_native_d3d9_override_retains_other_grouped_overrides(self):
        env = rtx_remix.wine_environment(self.prefix, False, {
            "WINEDLLOVERRIDES": "dxgi,d3d9=b;nvapi64=n,b;D3D9=;winemenubuilder.exe=d"})
        self.assertEqual(env["WINEDLLOVERRIDES"], "dxgi=b;nvapi64=n,b;winemenubuilder.exe=d;d3d9=n")

    def test_doctor_reports_missing_capability_without_initializing_prefix(self):
        gl = "OpenGL core profile version string: 4.6\n" + " ".join(rtx_remix.GL_EXTENSIONS)
        vk = " ".join(rtx_remix.VK_EXTENSIONS[:-1])
        with patch("rtx_remix.capture", side_effect=[(True, "wine-test"), (True, gl), (True, vk)]), \
             contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(rtx_remix.main(["doctor"]), 1)
        self.assertIn("FAIL: VK_KHR_external_semaphore_fd", output.getvalue())
        self.assertFalse(self.prefix.exists())

    def test_doctor_success_still_reports_rendering_unverified(self):
        gl = "OpenGL core profile version string: 4.5\n" + " ".join(rtx_remix.GL_EXTENSIONS)
        vk = " ".join(rtx_remix.VK_EXTENSIONS)
        with patch("rtx_remix.capture", side_effect=[(True, "wine-test"), (True, gl), (True, vk)]), \
             contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(rtx_remix.main(["doctor"]), 0)
        self.assertIn("do not verify", output.getvalue())


if __name__ == "__main__":
    unittest.main()
