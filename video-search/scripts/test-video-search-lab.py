"""Offline integration checks for timestamped frame extraction."""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
FFMPEG = ROOT / "tools/ffmpeg/win-x64/ffmpeg.exe"
SAMPLER = ROOT / "scripts/video-search-lab.py"


class SamplingIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="video-search-test-")
        cls.folder = Path(cls.directory.name)
        cls.source = cls.folder / "color-boundary.mp4"
        command = [str(FFMPEG), "-hide_banner", "-nostdin",
                   "-f", "lavfi", "-i", "color=c=red:s=160x90:r=10:d=1",
                   "-f", "lavfi", "-i", "color=c=blue:s=160x90:r=10:d=2",
                   "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]",
                   "-map", "[v]", "-c:v", "libx264", str(cls.source)]
        subprocess.run(command, check=True, capture_output=True, timeout=30)
        cls.original_hash = hashlib.sha256(cls.source.read_bytes()).hexdigest()
        result = subprocess.run(
            [sys.executable, str(SAMPLER), "--input", str(cls.source), "--id", "synthetic",
             "--start", "0.8", "--duration", "0.8", "--interval", "0.2",
             "--output", str(cls.folder / "samples")],
            capture_output=True, text=True, encoding="utf-8", timeout=30,
        )
        if result.returncode:
            raise RuntimeError(result.stderr)
        metadata = json.loads(result.stdout)
        cls.manifest = json.loads(Path(metadata["manifest"]).read_text(encoding="utf-8"))

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_timestamp_tracks_known_color_change_after_seek(self):
        frames = self.manifest["frames"]
        self.assertGreaterEqual(len(frames), 3)
        for frame in frames:
            with Image.open(frame["path"]) as picture:
                red, _, blue = picture.convert("RGB").getpixel((20, 20))
            if frame["timestampMs"] < 1000:
                self.assertGreater(red, blue + 100)
            else:
                self.assertGreater(blue, red + 100)

    def test_trim_does_not_publish_unwritten_or_outside_frames(self):
        stamps = [frame["timestampMs"] for frame in self.manifest["frames"]]
        self.assertEqual(stamps, sorted(set(stamps)))
        self.assertTrue(all(800 <= stamp < 1600 for stamp in stamps))
        self.assertTrue(all(Path(frame["path"]).is_file() for frame in self.manifest["frames"]))
        self.assertFalse(self.manifest["hasAudio"])

    def test_source_is_unchanged_and_existing_output_is_not_overwritten(self):
        self.assertEqual(self.original_hash, hashlib.sha256(self.source.read_bytes()).hexdigest())
        result = subprocess.run(
            [sys.executable, str(SAMPLER), "--input", str(self.source), "--id", "synthetic",
             "--start", "0.8", "--duration", "0.8", "--interval", "0.2",
             "--output", str(self.folder / "samples")],
            capture_output=True, timeout=30,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.original_hash, hashlib.sha256(self.source.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
