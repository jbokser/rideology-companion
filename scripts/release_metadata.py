"""Validate a release tag against metadata produced by Android Gradle Plugin."""

import argparse
import json
from pathlib import Path
import re


def validate(metadata, tag):
    elements = metadata.get("elements", [])
    if len(elements) != 1:
        raise ValueError("Expected exactly one release APK.")
    element = elements[0]
    version = element["versionName"]
    match = re.fullmatch(r"\d+(?:\.\d+)*(?P<beta>b\d*|-beta(?:\.\d+)?)?", version)
    if not match:
        raise ValueError("Supported versions are numeric releases or b/-beta versions.")
    if tag != f"v{version}":
        raise ValueError(f"Tag {tag!r} must match versionName: v{version}")
    if not isinstance(element["versionCode"], int) or element["versionCode"] < 1:
        raise ValueError("versionCode must be a positive integer.")
    apk = element["outputFile"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+\.apk", apk):
        raise ValueError("Expected a local APK filename.")
    return {
        "version": version,
        "prerelease": str(bool(match.group("beta"))).lower(),
        "apk": apk,
        "asset": f"rideology-companion-{tag}.apk",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("tag")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = validate(json.loads(args.metadata.read_text()), args.tag)
        if not (args.metadata.parent / result["apk"]).is_file():
            raise ValueError("The release APK is missing.")
    except (ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Release validation failed: {error}\n")
    with args.output.open("a") as output:
        for key, value in result.items():
            output.write(f"{key}={value}\n")


if __name__ == "__main__":
    main()
