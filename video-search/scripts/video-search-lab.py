"""Local-only media sampling for the video-search experiment. No network calls.

Requires Pillow for contact sheets. Original media is opened read-only by FFmpeg.
"""

import argparse
import hashlib
import json
import math
import re
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]


def run_ffmpeg(executable, arguments, timeout=240):
    return subprocess.run(
        [str(executable), "-hide_banner", "-nostdin", *arguments],
        capture_output=True, encoding="utf-8", errors="replace", timeout=timeout,
    )


def time_label(milliseconds):
    total = milliseconds / 1000
    return f"{int(total // 60):02d}:{total % 60:06.3f}"


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def contact_sheets(frames, target, video_id):
    paths = []
    for page, offset in enumerate(range(0, len(frames), 24), 1):
        selected = frames[offset:offset + 24]
        sheet = Image.new("RGB", (1280, 42 + math.ceil(len(selected) / 4) * 208), "#14151b")
        draw = ImageDraw.Draw(sheet)
        draw.text((12, 14), f"{video_id} | page {page} | source timestamps", fill="white")
        for index, frame in enumerate(selected):
            x, y = (index % 4) * 320, 42 + (index // 4) * 208
            with Image.open(frame["path"]) as source:
                source.thumbnail((320, 180))
                sheet.paste(source, (x, y))
            draw.text((x + 8, y + 185), time_label(frame["timestampMs"]), fill="#c5ff42")
        path = target / f"sheet-{page:02d}.jpg"
        sheet.save(path, quality=90)
        paths.append(str(path.resolve()))
    return paths


def prepare(args):
    video = args.input.resolve(strict=True)
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", args.id):
        raise ValueError("id must contain only ASCII letters, digits, underscores or hyphens")
    if args.interval <= 0 or args.start < 0 or (args.duration is not None and args.duration <= 0):
        raise ValueError("interval/duration must be positive; start cannot be negative")
    ffmpeg = args.ffmpeg.resolve(strict=True)
    probe = run_ffmpeg(ffmpeg, ["-i", str(video)])
    match = re.search(r"Duration: (\d+):(\d+):([\d.]+)", probe.stderr)
    if not match:
        raise ValueError("Cannot read media duration: " + probe.stderr[-1500:])
    duration_ms = round((int(match[1]) * 3600 + int(match[2]) * 60 + float(match[3])) * 1000)
    if args.start * 1000 >= duration_ms:
        raise ValueError("start is outside the video")
    target = args.output.resolve() / args.id / f"sample-{args.start:g}-{args.duration or 'end'}-{args.interval:g}s"
    if target.exists():
        raise FileExistsError(f"Output already exists; choose a different output root: {target}")
    if video.is_relative_to(target):
        raise ValueError("Source cannot be placed inside the output directory")
    target.mkdir(parents=True)
    # Trim before showinfo: output-level -t may log an extra frame which is never written.
    trim = f"trim=duration={args.duration}," if args.duration is not None else ""
    expression = trim + f"select=isnan(prev_selected_t)+gte(t-prev_selected_t\\,{args.interval:g}),scale=640:-2,showinfo"
    command = ["-ss", str(args.start), "-i", str(video)]
    command += ["-an", "-vf", expression, "-fps_mode", "vfr", "-q:v", "3", str(target / "frame_%05d.jpg")]
    result = run_ffmpeg(ffmpeg, command)
    (target / "ffmpeg.log").write_text(result.stderr, encoding="utf-8")
    if result.returncode:
        raise RuntimeError("Sampling failed: " + result.stderr[-2000:])
    times = [float(value) for value in re.findall(r"\bn:\s*\d+\s+pts:.*?\bpts_time:([\d.eE+-]+)", result.stderr)]
    pictures = sorted(target.glob("frame_*.jpg"))
    if not pictures or len(times) != len(pictures):
        raise RuntimeError(f"Timestamp/frame mismatch: {len(times)} timestamps, {len(pictures)} frames")
    frames = [{"path": str(path.resolve()), "timestampMs": round((args.start + stamp) * 1000)}
              for path, stamp in zip(pictures, times)]
    manifest = {
        "schemaVersion": 1, "videoId": args.id, "sourcePath": str(video),
        "sourceName": video.name, "bytes": video.stat().st_size, "sha256": digest_file(video),
        "durationMs": duration_ms, "hasAudio": "Audio:" in probe.stderr,
        "sampling": {"startMs": round(args.start * 1000),
                     "durationMs": round(args.duration * 1000) if args.duration is not None else None,
                     "minimumIntervalMs": round(args.interval * 1000), "method": "source-frame-timestamp"},
        "frames": frames, "contactSheets": contact_sheets(frames, target, args.id),
        "annotationStatus": "unreviewed", "automaticRetrievalExecuted": False,
    }
    (target / "probe.log").write_text(probe.stderr, encoding="utf-8")
    write_json(target / "manifest.json", manifest)
    print(json.dumps({"videoId": args.id, "durationMs": duration_ms, "frames": len(frames),
                      "manifest": str(target / "manifest.json"), "sheets": manifest["contactSheets"]}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--id", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "samples/beverage")
    parser.add_argument("--ffmpeg", type=Path, default=ROOT / "tools/ffmpeg/win-x64/ffmpeg.exe")
    parser.add_argument("--interval", type=float, default=5)
    parser.add_argument("--start", type=float, default=0)
    parser.add_argument("--duration", type=float)
    prepare(parser.parse_args())


if __name__ == "__main__":
    main()
