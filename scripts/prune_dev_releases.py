#!/usr/bin/env python3
"""Keep the three highest numbered published Dev prereleases; preserve tags."""
import argparse
import json
import re
import subprocess


def releases_to_delete(releases):
    candidates = [
        release for release in releases
        if re.fullmatch(r"dev-[1-9][0-9]*", release.get("tag_name", ""))
        and release.get("prerelease") is True
        and release.get("draft") is False
    ]
    candidates.sort(key=lambda release: int(release["tag_name"][4:]), reverse=True)
    return [release["tag_name"] for release in candidates[3:]]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--apply", action="store_true", help="Delete old releases and assets; otherwise preview only")
    args = parser.parse_args()
    pages = json.loads(subprocess.check_output([
        "gh", "api", "--paginate", "--slurp", f"repos/{args.repo}/releases?per_page=100"
    ], text=True))
    tags = releases_to_delete([release for page in pages for release in page])
    for tag in tags:
        print(f"{'Deleting' if args.apply else 'Would delete'} release {tag} (tag retained)", flush=True)
        if args.apply:
            subprocess.run(["gh", "release", "delete", tag, "--repo", args.repo, "--yes"], check=True)
    if not tags:
        print("No old Dev releases to remove.")


if __name__ == "__main__":
    main()
