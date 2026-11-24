#!/usr/bin/env python3
"""Discover a CloudStream plugin's runtime modules so a temporary probe can call them."""
import json
import pathlib
import re
import sys
import zipfile


def main(apk: str) -> int:
    report = {}
    with zipfile.ZipFile(apk) as archive:
        dex = [name for name in archive.namelist() if name.endswith(".dex")]
        classes = set(re.findall(rb"L([\w/$]+);", b"".join(archive.read(name) for name in dex)))
    names = sorted({c.decode("utf-8", "replace") for c in classes})
    report["classes"] = names[:400]
    report["has_provider"] = [n for n in names if n.endswith("Provider")]
    pathlib.Path("modules.json").write_text(json.dumps(report, indent=1))
    print(json.dumps(report))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
