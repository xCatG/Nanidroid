#!/usr/bin/env python3
"""Report the pinned modernization development environment."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path


def version(command: list[str]) -> dict[str, object]:
    executable = shutil.which(command[0])
    if executable is None:
        return {"available": False}
    try:
        result = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError as error:
        return {"available": False, "executable": executable, "error": str(error)}
    output = (result.stdout or result.stderr).strip().splitlines()
    return {
        "available": result.returncode == 0,
        "executable": executable,
        "version": output[0] if output else "",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--require-android-tools",
        action="store_true",
        help="fail when sdkmanager is unavailable",
    )
    args = parser.parse_args()

    report = {
        "java": version(["java", "-version"]),
        "python": version([sys.executable, "--version"]),
        "cmake": version(["cmake", "--version"]),
        "ninja": version(["ninja", "--version"]),
        "git": version(["git", "--version"]),
        "sdkmanager": version(["sdkmanager", "--version"]),
        "androidHome": os.environ.get("ANDROID_HOME"),
        "androidSdkRoot": os.environ.get("ANDROID_SDK_ROOT"),
        "workspace": str(Path.cwd()),
    }
    print(json.dumps(report, indent=2))

    required = ["java", "python", "git"]
    if args.require_android_tools:
        required.append("sdkmanager")
    missing = [name for name in required if not report[name]["available"]]
    if missing:
        print(f"Missing required tools: {', '.join(missing)}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
