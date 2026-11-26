"""Build reviewed, muted excerpts from the two user-provided beverage videos.

Labels are provisional visual annotations, not model predictions. No cloud calls.
Run video-search-lab.py on both full videos and review its contact sheets first.
"""

import hashlib
import html
import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "samples/beverage"
FFMPEG = ROOT / "tools/ffmpeg/win-x64/ffmpeg.exe"

# Boundaries reflect ~0.5-second source-frame sampling; uncertainty is explicit.
CASES = [
    {"clipId": "cup-pour", "videoId": "beverage-01", "range": [10000, 22000],
     "label": "pour_beverage", "event": [12400, 19900],
     "startBracket": [12133, 12667], "endBracket": [19600, 20133],
     "description": "从绿色标签汽水瓶向透明塑料杯倒饮料；没有饮用。"},
    {"clipId": "cup-hold", "videoId": "beverage-01", "range": [103000, 115000],
     "label": "handle_container_without_drinking", "event": None,
     "description": "触碰、整理杯子并等待；杯口没有接触嘴部，没有可见饮用动作。"},
    {"clipId": "cup-drink", "videoId": "beverage-01", "range": [115000, 127000],
     "label": "drink_beverage", "event": [116900, 124900],
     "startBracket": [116621, 117154], "endBracket": [124622, 125156],
     "description": "举起透明塑料杯，使杯口接触嘴部并倾斜饮用，随后移开。"},
    {"clipId": "bottle-display", "videoId": "beverage-02", "range": [84000, 94000],
     "label": "handle_container_without_drinking", "event": None,
     "description": "拿汽水瓶介绍和展示；本段没有饮用。原字幕谈论味道，不等于发生饮用。"},
    {"clipId": "bottle-sip", "videoId": "beverage-02", "range": [93000, 99000],
     "label": "drink_beverage", "event": [95300, 96900],
     "startBracket": [95046, 95569], "endBracket": [96617, 97141],
     "description": "从汽水瓶短暂饮用后放低瓶子，用于检查稀疏抽帧漏检。"},
    {"clipId": "boot-pour", "videoId": "beverage-02", "range": [120000, 132000],
     "label": "pour_beverage", "event": [122400, 129200],
     "startBracket": [122095, 122619], "endBracket": [128905, 129428],
     "description": "从汽水瓶向透明酒靴杯倒饮料，随后展示杯子；没有饮用。"},
    {"clipId": "boot-drink", "videoId": "beverage-02", "range": [328000, 354000],
     "label": "drink_beverage", "event": [333000, 348700],
     "startBracket": [332760, 333284], "endBracket": [348474, 348998],
     "description": "室外举起大型酒靴杯，持续倾斜饮用后移开；画面内计时文字不是源视频时间。"},
]


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def stamp(ms):
    return f"{ms // 60000:02d}:{ms % 60000 / 1000:04.1f}"


def build():
    manifests = {video_id: json.loads((OUTPUT / video_id / "sample-0-end-5s/manifest.json").read_text(encoding="utf-8"))
                 for video_id in ("beverage-01", "beverage-02")}
    target = OUTPUT / "fixture-v1"
    if target.exists():
        raise FileExistsError(f"Fixture already exists: {target}")
    clips = target / "clips"
    clips.mkdir(parents=True)
    records = []
    for case in CASES:
        start, end = case["range"]
        output = clips / (case["clipId"] + ".mp4")
        result = subprocess.run(
            [str(FFMPEG), "-hide_banner", "-nostdin", "-ss", str(start / 1000),
             "-i", manifests[case["videoId"]]["sourcePath"], "-t", str((end - start) / 1000),
             "-map", "0:v:0", "-an", "-vf", "scale=640:-2", "-c:v", "libx264",
             "-preset", "fast", "-crf", "25", "-movflags", "+faststart", str(output)],
            capture_output=True, encoding="utf-8", errors="replace", timeout=120,
        )
        if result.returncode:
            raise RuntimeError(result.stderr[-1500:])
        record = {**case, "clipPath": f"clips/{case['clipId']}.mp4", "split": "development",
                  "silent": True, "reviewStatus": "provisional_visual_review",
                  "annotationMethod": "assistant reviewed contact sheets at approximately 0.5-second intervals",
                  "sourceSha256": manifests[case["videoId"]]["sha256"],
                  "clipSha256": hashlib.sha256(output.read_bytes()).hexdigest()}
        event = case["event"]
        record["eventInClipMs"] = [event[0] - start, event[1] - start] if event else None
        records.append(record)
    write_json(target / "annotations.json", {
        "schemaVersion": 1, "completeSourceAnnotation": False, "automaticRetrievalExecuted": False,
        "purpose": "局部片段上的视觉动作区分；不用于统计完整原视频的事件召回。",
        "boundaryPrecision": "约 0.6 秒内的边界括区，未逐帧确认，不宣称帧级精度。",
        "actionDefinition": "饮用指容器口接触嘴部并呈可见饮用姿态；不能据此判断吞咽或液体成分。",
        "splitPolicy": "全部属于开发样本。源视频已被查看，不再宣称独立保留测试集。",
        "clips": records,
    })
    query_specs = [
        ("q01", "找出正在喝饮料的片段", "drink_beverage"),
        ("q02", "找出从瓶子往杯子里倒饮料的片段", "pour_beverage"),
        ("q03", "找出仅拿着或展示容器，没有饮用也没有倒液体的完整样本", "handle_container_without_drinking"),
        ("q04", "找出用透明塑料杯喝饮料的片段", "cup-drink"),
        ("q05", "找出直接对着瓶口喝的片段", "bottle-sip"),
        ("q06", "找出用酒靴杯喝饮料的片段", "boot-drink"),
        ("q07", "找出往塑料杯倒汽水的片段", "cup-pour"),
        ("q08", "找出往酒靴杯倒汽水的片段", "boot-pour"),
        ("q09", "找出拿勺子吃饭的片段", "absent"),
        ("q10", "找出拿牙刷刷牙的片段", "absent"),
        ("q11", "找出往地面泼水的片段", "absent"),
        ("q12", "找出两个人碰杯的片段", "absent"),
    ]
    queries = []
    for query_id, query, selector in query_specs:
        answers = []
        for record in records:
            if selector in (record["label"], record["clipId"]):
                local_event = record["eventInClipMs"] or [0, record["range"][1] - record["range"][0]]
                answers.append({"videoId": record["clipId"], "startMs": local_event[0], "endMs": local_event[1]})
        queries.append({"queryId": query_id, "query": query, "scope": [record["clipId"] for record in records],
                        "split": "development", "expectedEvents": answers, "reviewStatus": "provisional"})
    write_json(target / "queries.json", {"schemaVersion": 1, "queries": queries})
    cards = []
    for record in records:
        event = record["event"]
        label = f"源视频事件约 {stamp(event[0])} — {stamp(event[1])}" if event else "本样本无可见饮用动作"
        cards.append(f'''<article><h2>{html.escape(record['clipId'])}</h2>
<p>{html.escape(record['description'])}</p>
<video controls muted preload="metadata" src="{record['clipPath']}"></video>
<p>来源 {record['videoId']} · 截取 {stamp(record['range'][0])} — {stamp(record['range'][1])}</p>
<p>{label} · 暂定标注，边界约 ±0.6 秒</p></article>''')
    page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>视频检索实验样本</title>
<style>body{font:16px/1.65 system-ui,sans-serif;background:#111318;color:#e8eaf0;max-width:1100px;margin:36px auto;padding:0 20px}h1{font-size:28px}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,420px),1fr));gap:20px}article{background:#1d2028;padding:20px;border-radius:12px}h2{font-size:20px;margin-top:0}p{color:#c4c9d5}video{width:100%;background:#000}aside{padding:16px;border:1px solid #596477;margin:24px 0}</style>
<h1>视频检索实验样本</h1><p>两条视频裁出的静音开发样本，用于区分倒饮料、持有容器和饮用。</p>
<aside>这是人工视觉复核的暂定标注，不是自动检索结果。源视频没有被修改；播放器显示样本内时间，下面另列原视频时间。当前不提供准确率，也没有调用付费模型。</aside>
<main>''' + "\n".join(cards) + "</main></html>"
    (target / "review.html").write_text(page, encoding="utf-8")
    print(json.dumps({"clips": len(records), "queries": len(queries), "review": str(target / "review.html")}, ensure_ascii=False))


if __name__ == "__main__":
    build()
