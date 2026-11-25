#!/usr/bin/env python3
"""Verify two APKs and compare their complete signer certificate sets."""

import argparse
import json
from pathlib import Path
import re
import subprocess

PROBE = Path(__file__).resolve().with_name("ApkCertificateProbe.java")
DEFAULT_JAR = Path("/opt/android-sdk/build-tools/37.0.0/lib/apksigner.jar")


def signer_set(apk, java, jar, runner):
    result = runner(
        [java, "-cp", str(jar), str(PROBE), str(apk)],
        capture_output=True, encoding="utf-8", errors="replace", timeout=120,
    )
    if result.returncode:
        detail = result.stderr.strip() or f"Java exit status {result.returncode}"
        raise ValueError(f"APK verification failed: {detail}")
    fingerprints = result.stdout.splitlines()
    if not fingerprints or any(not re.fullmatch(r"[0-9a-fA-F]{64}", value) for value in fingerprints):
        raise ValueError("Probe did not return a valid, nonempty signer certificate set")
    return sorted({value.lower() for value in fingerprints})


def main(argv=None, runner=subprocess.run):
    parser = argparse.ArgumentParser(
        description=__doc__,
        epilog="Requires JDK 17+. JSON is written to stdout. Exit codes: 0 match, "
               "1 mismatch, 2 verification or tool error.",
    )
    parser.add_argument("candidate", type=Path)
    parser.add_argument("reference", type=Path)
    parser.add_argument("--java", default="java", help="Java executable")
    parser.add_argument("--apksigner-jar", type=Path, default=DEFAULT_JAR,
                        help=f"SDK apksigner.jar (default: {DEFAULT_JAR})")
    args = parser.parse_args(argv)
    report = {role: {"path": str(getattr(args, role))} for role in ("candidate", "reference")}
    for role, entry in report.items():
        try:
            entry["cert_sha256"] = signer_set(getattr(args, role), args.java, args.apksigner_jar, runner)
        except (ValueError, OSError, subprocess.TimeoutExpired) as exc:
            entry["error"] = str(exc)

    if any("error" in entry for entry in report.values()):
        status, code, message = "error", 2, "APK verification could not be completed."
    elif report["candidate"]["cert_sha256"] != report["reference"]["cert_sha256"]:
        status, code, message = "mismatch", 1, "Signing certificates differ. Cannot overwrite the reference APK as an update."
    else:
        status, code, message = "match", 0, "The verified signer certificate sets are identical."
    print(json.dumps(dict(report, status=status, message=message), indent=2))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
