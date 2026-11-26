"""Inspect publishable files without initializing the workspace Git repository.

--export PATH copies only those files to a new directory for a clean-source check.
Requires Git. Does not commit, push, copy credentials, or download media.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    cache = root / ".cache"
    cache.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="release-index-", dir=cache) as folder:
        subprocess.run(["git", "init", "--bare", "--quiet", folder], check=True)
        command = ["git", "--git-dir=" + folder, "--work-tree=" + str(root), "ls-files", "--others", "--exclude-standard", "-z"]
        files = [Path(p.decode("utf-8")) for p in subprocess.check_output(command, cwd=root).split(b"\0") if p]
    failures = []
    secrets = []
    for config in (root / ".env", root / "video-search/.env"):
        if not config.exists():
            continue
        for line in config.read_text(encoding="utf-8-sig").splitlines():
            if "=" not in line or line.startswith("#"):
                continue
            name, value = line.split("=", 1)
            value = value.strip().strip("\"'")
            if re.search(r"PASSWORD|SECRET|API_KEY|ACCESS_KEY|TOKEN", name) and len(value) >= 12:
                secrets.append(value)
    published = {p.as_posix() for p in files}
    for relative in files:
        path = root / relative
        if not path.resolve().is_relative_to(root) or path.is_symlink():
            failures.append(str(relative) + ": external/symbolic file")
            continue
        forbidden = {"tmp", ".data", ".cache", "output", "node_modules", "target", ".git", "archive", "samples"}
        if any(part in forbidden for part in relative.parts) or (relative.name.startswith(".env") and relative.name != ".env.example"):
            failures.append(str(relative) + ": private/generated file")
        try:
            text = path.read_text(encoding="utf-8-sig")
        except (UnicodeError, OSError):
            continue
        for number, line in enumerate(text.splitlines(), 1):
            if any(secret in line for secret in secrets):
                failures.append(str(relative) + ":" + str(number) + ": configured credential")
    for name in ["README.md", "video-search/README.md", "video-search/docs/evaluation/README.md"]:
        path = root / name
        for target in re.findall(r"\]\(([^)]+)\)", path.read_text(encoding="utf-8")):
            if target.startswith(("http:", "https:", "#", "mailto:")):
                continue
            target = target.split("#", 1)[0].strip("<>")
            if not target:
                continue
            destination = (path.parent / target).resolve()
            if not destination.is_relative_to(root) or destination.relative_to(root).as_posix() not in published:
                failures.append(name + ": unpublished link: " + target)
    if failures:
        print(json.dumps({"passed": False, "findings": failures}, ensure_ascii=True, indent=2))
        raise SystemExit(1)
    if args.export:
        destination = args.export.resolve()
        if destination.exists():
            raise SystemExit("Export destination must not already exist.")
        if destination == root or root.is_relative_to(destination):
            raise SystemExit("Export destination must not contain the workspace.")
        destination.mkdir(parents=True)
        for relative in files:
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(root / relative, target)
    print(json.dumps({"passed": True, "publishableFiles": len(files), "configuredSecretMatches": 0, "exported": bool(args.export)}, indent=2))


if __name__ == "__main__":
    main()
