"""Run build-plugins.sh against isolated Cargo/xipm substitutes."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class BuildPluginsScriptTest(unittest.TestCase):
    def run_fixture(self, cargo_exit):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            shutil.copyfile(Path(__file__).with_name("build-plugins.sh"), root / "scripts/build-plugins.sh")
            cli = root / "tools/xime-plugin/target/release/xipm"
            cli.parent.mkdir(parents=True)
            cli.write_text('#!/bin/sh\nprintf "pack\\n" >> "$TEST_LOG"\n')
            cli.chmod(0o755)
            (root / "plugins/webdav-backup").mkdir(parents=True)
            assets = root / "app/src/main/assets/plugins"
            assets.mkdir(parents=True)
            (assets / "existing.xipk").write_bytes(b"preserve")
            binary = root / "bin"
            binary.mkdir()
            cargo = binary / "cargo"
            cargo.write_text(f'#!/bin/sh\nprintf "cargo\\n" >> "$TEST_LOG"\nexit {cargo_exit}\n')
            cargo.chmod(0o755)
            log = root / "calls"
            environment = {**os.environ, "PATH": str(binary) + os.pathsep + os.environ["PATH"], "TEST_LOG": str(log)}
            result = subprocess.run(["bash", str(root / "scripts/build-plugins.sh")], env=environment, capture_output=True, text=True)
            self.assertEqual((assets / "existing.xipk").read_bytes(), b"preserve")
            return result.returncode, log.read_text().splitlines()

    def test_existing_cli_still_checks_cargo_incremental_build(self):
        status, calls = self.run_fixture(0)
        self.assertEqual(status, 0)
        self.assertEqual(calls, ["cargo", "pack"])

    def test_failed_cargo_does_not_run_stale_cli(self):
        status, calls = self.run_fixture(7)
        self.assertEqual(status, 7)
        self.assertEqual(calls, ["cargo"])


if __name__ == "__main__":
    unittest.main()
