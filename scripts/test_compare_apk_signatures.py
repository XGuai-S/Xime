import io
import json
import subprocess
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock

from compare_apk_signatures import PROBE, main

A = "a" * 64
B = "b" * 64
C = "c" * 64


def probe_output(stdout="", returncode=0, stderr=""):
    return subprocess.CompletedProcess([], returncode, stdout, stderr)


class CompareApkSignaturesTest(unittest.TestCase):
    def compare(self, candidate, reference):
        runner = Mock(side_effect=[candidate, reference])
        output = io.StringIO()
        with redirect_stdout(output):
            code = main([
                "candidate dir/app.apk", "reference dir/app.apk",
                "--java", "/jdk path/java", "--apksigner-jar", "/sdk path/apksigner.jar",
            ], runner=runner)
        for call, apk in zip(runner.call_args_list, ("candidate dir/app.apk", "reference dir/app.apk")):
            self.assertEqual(call.args[0], [
                "/jdk path/java", "-cp", "/sdk path/apksigner.jar", str(PROBE), apk,
            ])
            self.assertFalse(call.kwargs.get("shell", False))
        self.assertEqual(runner.call_count, 2)
        return code, json.loads(output.getvalue())

    def test_same_set_ignores_order_case_and_duplicates(self):
        code, report = self.compare(probe_output(f"{B}\n{A.upper()}\n{A}\n"), probe_output(f"{A}\n{B}\n"))
        self.assertEqual((code, report["status"]), (0, "match"))
        for role in ("candidate", "reference"):
            self.assertEqual(report[role]["cert_sha256"], [A, B])

    def test_partial_overlap_is_not_a_match(self):
        code, report = self.compare(probe_output(f"{A}\n{B}\n"), probe_output(f"{A}\n{C}\n"))
        self.assertEqual((code, report["status"]), (1, "mismatch"))
        self.assertIn("Cannot overwrite the reference APK", report["message"])

    def test_extra_signer_is_not_a_match(self):
        code, report = self.compare(probe_output(f"{A}\n{B}\n"), probe_output(f"{A}\n"))
        self.assertEqual((code, report["status"]), (1, "mismatch"))

    def test_failed_verification_ignores_stdout(self):
        invalid = probe_output(A, returncode=1, stderr="APK signature verification failed")
        valid = probe_output(A)
        for role, inputs in (("candidate", (invalid, valid)), ("reference", (valid, invalid))):
            with self.subTest(role=role):
                code, report = self.compare(*inputs)
                self.assertEqual((code, report["status"]), (2, "error"))
                self.assertIn("signature verification failed", report[role]["error"])
                self.assertNotIn("cert_sha256", report[role])

    def test_empty_or_malformed_output_is_an_error(self):
        for stdout in ("", f"{A}\nnot a fingerprint\n"):
            with self.subTest(stdout=stdout):
                code, report = self.compare(probe_output(A), probe_output(stdout))
                self.assertEqual((code, report["status"]), (2, "error"))
                self.assertIn("error", report["reference"])

    def test_runner_failure_is_an_error(self):
        for failure in (FileNotFoundError("java not found"), subprocess.TimeoutExpired("java", 120)):
            with self.subTest(failure=failure):
                code, report = self.compare(failure, probe_output(A))
                self.assertEqual((code, report["status"]), (2, "error"))
                self.assertIn("error", report["candidate"])


if __name__ == "__main__":
    unittest.main()
