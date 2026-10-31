# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import tempfile
from pathlib import Path
import unittest

from lunar_wine import convert_arguments, quote_java_argument


class LunarWineArgumentsTest(unittest.TestCase):
    def setUp(self):
        self.config = {
            "path_mappings": [["/home/player/.lunarclient", "/sandbox/.lunarclient"]],
            "natives": "/runtime/windows natives",
            "game_home": "/sandbox",
        }

    def test_remaps_classpath_and_profile_and_replaces_linux_native_path(self):
        args = convert_arguments([
            "-Djava.library.path=/linux/natives", "-cp",
            "/home/player/.lunarclient/a.jar:/home/player/.lunarclient/b.jar",
            "net.fabricmc.loader.impl.launch.knot.KnotClient", "--gameDir",
            "/home/player/.lunarclient/profiles/rtx", "--accessToken", "a/b+c==",
        ], self.config)
        self.assertIn("Z:/sandbox/.lunarclient/a.jar;Z:/sandbox/.lunarclient/b.jar", args)
        self.assertIn("Z:/sandbox/.lunarclient/profiles/rtx", args)
        self.assertIn("-Djava.library.path=Z:/runtime/windows natives", args)
        self.assertNotIn("-Djava.library.path=/linux/natives", args)
        self.assertEqual(args[-1], "a/b+c==")

    def test_expands_argument_files_without_losing_spaces(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "java.args"
            path.write_text('-cp "/home/player/.lunarclient/file name.jar" Main --username "A Name"')
            args = convert_arguments(["@" + str(path)], self.config)
        self.assertIn("Z:/sandbox/.lunarclient/file name.jar", args)
        self.assertEqual(args[-1], "A Name")

    def test_quotes_backslashes_quotes_and_line_breaks_for_java(self):
        self.assertEqual(quote_java_argument('a\\b"c\nd'), '"a\\\\b\\"c\\nd"')


if __name__ == "__main__":
    unittest.main()
