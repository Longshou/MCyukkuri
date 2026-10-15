"""Maintain the wiki manga reference archive without browser/UI automation.

Python 3.10+ and Pillow. `fetch` reads the explicitly listed public article URLs,
extracts article-body image links (never comments/sidebar), caches HTML in .local,
and stores original image bytes plus provenance in wiki/art-references. No login,
certificate bypass, guessed image URLs, or runtime-resource changes. `check` is
offline/read-only; `sheets` creates review contact sheets only in .local;
`catalog` rebuilds the per-work Markdown indexes from the reviewed manifest.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import time
from http.client import IncompleteRead
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from urllib.error import URLError

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / "wiki/art-references"
CACHE = ROOT / ".local/reference/wiki-manga"
MANIFEST = LIB / "manifest.json"
IMAGE_RE = re.compile(r"\.(png|jpe?g|gif|webp)$", re.I)


class ArticleParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.depth = 0
        self.anchor = None
        self.images = []
        self.title = ""
        self.text = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "meta" and a.get("property") == "og:title":
            self.title = a.get("content", "")
        if tag == "div":
            if self.depth:
                self.depth += 1
            elif "article-body" in a.get("class", "").split():
                self.depth = 1
        if not self.depth:
            return
        if tag == "a":
            self.anchor = a.get("href")
        if tag == "img":
            candidates = [self.anchor, a.get("src")]
            for candidate in candidates:
                if (candidate and urlparse(candidate).hostname == "livedoor.blogimg.jp"
                        and IMAGE_RE.search(urlparse(candidate).path)):
                    if candidate not in [x["url"] for x in self.images]:
                        self.images.append({"url": candidate, "alt": a.get("alt", "")})
                    break

    def handle_endtag(self, tag):
        if tag == "a":
            self.anchor = None
        if tag == "div" and self.depth:
            self.depth -= 1

    def handle_data(self, data):
        if self.depth and data.strip():
            self.text.append(data.strip())


def get(url):
    req = Request(url, headers={"User-Agent": "MCyukkuri-Wiki-Reference/1.0"})
    for attempt in range(3):
        try:
            with urlopen(req, timeout=40) as response:
                return response.read(), response.url
        except (IncompleteRead, URLError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def fetch():
    sources = json.loads((LIB / "sources.json").read_text(encoding="utf-8"))["sources"]
    data = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {
        "schema_version": 1, "purpose": "作品研究与美术参考；非游戏素材授权",
        "sources": [], "images": []}
    CACHE.mkdir(parents=True, exist_ok=True)
    failures = []
    for source in sources:
        if source.get("collect") is False:
            continue
        sid = source["id"]
        try:
            cache = CACHE / f"{sid}.html"
            if cache.exists():
                raw = cache.read_bytes()
                fetched_url = source["url"]
            else:
                raw, fetched_url = get(source["url"])
                cache.write_bytes(raw)
            parser = ArticleParser()
            parser.feed(raw.decode("utf-8", errors="replace"))
            if not parser.images or not parser.title:
                raise ValueError("No titled article-body image content found")
            (CACHE / f"{sid}.txt").write_text("\n".join(parser.text), encoding="utf-8")
            match = re.search(r"【([^】]+)】", parser.title)
            author = source.get("attribution_override") or (match.group(1) if match else "转载标题未标作者")
            record = {**source, "title": parser.title, "attribution_on_page": author,
                      "attribution_status": "转载页标注；首发与作者身份未独立核验",
                      "retrieved_date": datetime.fromtimestamp(cache.stat().st_mtime, timezone.utc).date().isoformat(),
                      "retrieved_url": fetched_url,
                      "page_sha256": sha(raw), "available_images": len(parser.images),
                      "selection": f"正文前 {min(source['limit'], len(parser.images))} 张不同原图链接",
                      "license": "未发现明确开放授权；不视为可直接复用的游戏资源"}
            data["sources"] = [x for x in data["sources"] if x["id"] != sid] + [record]
            for index, item in enumerate(parser.images[:source["limit"]], 1):
                iid = f"{sid}-{index:03}"
                existing = next((x for x in data["images"] if x["id"] == iid), None)
                if existing:
                    # Never silently replace a saved edition after an article changes.
                    if existing["image_url"] != item["url"]:
                        raise ValueError(f"{iid}: source order changed; review manually")
                    if (ROOT / existing["path"]).exists():
                        continue
                try:
                    raw_img, final_url = get(item["url"])
                    with Image.open(io.BytesIO(raw_img)) as im:
                        width, height = im.size
                        fmt = im.format
                        frames = getattr(im, "n_frames", 1)
                        im.verify()
                    if min(width, height) < 100:
                        raise ValueError("Image too small for reference")
                    name = f"{index:03}-{Path(urlparse(item['url']).path).name}"
                    dest = LIB / "images" / sid / name
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(raw_img)
                    row = {"id": iid, "source_id": sid, "article_image_order": index,
                           "source_url": source["url"], "image_url": item["url"],
                           "retrieved_url": final_url, "retrieved_at": datetime.now(timezone.utc).isoformat(),
                           "path": dest.relative_to(ROOT).as_posix(), "bytes": len(raw_img),
                           "sha256": sha(raw_img), "width": width, "height": height,
                           "format": fmt, "frames": frames, "original_alt": item["alt"],
                           "tags": source["tags"], "review_status": "downloaded_not_read",
                           "review_note": "仅技术验证；下载不等于核读，不据此添加情节结论"}
                    data["images"] = [x for x in data["images"] if x["id"] != iid] + [row]
                except Exception as exc:
                    failures.append({"id": iid, "error": str(exc)})
            write_json(MANIFEST, data)
            print(sid, parser.title, "available", len(parser.images), "saved",
                  sum(x["source_id"] == sid for x in data["images"]), flush=True)
        except Exception as exc:
            failures.append({"id": sid, "error": str(exc)})
            print(sid, "FAILED", str(exc), flush=True)
    write_json(CACHE / "fetch-errors.json", failures)
    if failures:
        raise SystemExit(f"{len(failures)} failures; see {CACHE / 'fetch-errors.json'}")


def check():
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    ids, hashes, paths = set(), set(), set()
    sources = {x["id"]: x for x in data["sources"]}
    assert len(sources) == len(data["sources"]), "Duplicate source IDs"
    for sid, source in sources.items():
        count = sum(row["source_id"] == sid for row in data["images"])
        assert count == min(source["limit"], source["available_images"]), f"Incomplete source: {sid}"
    for row in data["images"]:
        assert row["id"] not in ids, f"Duplicate ID: {row['id']}"
        ids.add(row["id"])
        assert row["source_id"] in sources
        path = (ROOT / row["path"]).resolve()
        assert path.is_relative_to((LIB / "images").resolve())
        assert path not in paths, f"Duplicate path: {row['id']}"
        paths.add(path)
        raw = path.read_bytes()
        assert sha(raw) == row["sha256"], row["id"]
        assert len(raw) == row["bytes"], row["id"]
        with Image.open(io.BytesIO(raw)) as im:
            assert im.size == (row["width"], row["height"]), row["id"]
            im.verify()
        hashes.add(row["sha256"])
    actual = {x.resolve() for x in (LIB / "images").rglob("*") if x.is_file()}
    assert actual == paths, "Unregistered or missing files"
    print(json.dumps({"sources": len(sources), "images": len(ids), "unique_hashes": len(hashes),
                      "bytes": sum(x["bytes"] for x in data["images"]), "status": "PASS"}))


def sheets():
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    out = CACHE / "sheets"
    out.mkdir(parents=True, exist_ok=True)
    for source in data["sources"]:
        rows = sorted((x for x in data["images"] if x["source_id"] == source["id"]), key=lambda x: x["id"])
        for offset in range(0, len(rows), 6):
            batch = rows[offset:offset+6]
            canvas = Image.new("RGB", (1440, 1120), "#deded8")
            draw = ImageDraw.Draw(canvas)
            for n, row in enumerate(batch):
                with Image.open(ROOT / row["path"]) as im:
                    thumb = im.convert("RGB")
                    thumb.thumbnail((468, 522))
                    x, y = (n % 3) * 480, (n // 3) * 560
                    canvas.paste(thumb, (x + (480-thumb.width)//2, y + 26))
                    draw.text((x+8, y+5), row["id"], fill="black")
            dest = out / f"{source['id']}-{offset//6+1}.jpg"
            canvas.save(dest, quality=90)
    print(out)


def catalog():
    """Write only generated work indexes; keep the hand-edited reading guide."""
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    out = LIB / "works"
    out.mkdir(parents=True, exist_ok=True)
    labels = {"downloaded_not_read": "仅下载", "visual_overview": "画面总览",
              "detail_reviewed": "原图重点核对"}
    for source in sorted(data["sources"], key=lambda x: x["id"]):
        rows = sorted((x for x in data["images"] if x["source_id"] == source["id"]), key=lambda x: x["id"])
        overview_count = sum(row["review_status"] in {"visual_overview", "detail_reviewed"} for row in rows)
        detail_count = sum(row["review_status"] == "detail_reviewed" for row in rows)
        lines = [f"# {source['id']} · {source['title']}", "",
                 "[参考库](../README.md) · [按用途查图](../visual-guide.md)", "",
                 f"来源：[作品转载页]({source['url']})。署名：{source['attribution_on_page']}。",
                 f"核对边界：{source['attribution_status']}。原图抓取日期见 manifest；作品页缓存日期 {source['retrieved_date']}。", "",
                 f"正文检出 {source['available_images']} 张，保存前 {len(rows)} 张。序号指页面图片顺序，不保证等于印刷页码；未保存部分不算已读。",
                 f"检索主题：{'、'.join(source['tags'])}。", "",
                 f"已看画面总览 {overview_count} 张，其中原图重点核对 {detail_count} 张；核对限下表列明的结构、画格或署名，不表示全文翻译。原作含伤害、死亡等情节，具体页另标。",
                 "", "| 原图 | 尺寸 | 核对层级 | 可观察内容与使用边界 |", "| --- | --- | --- | --- |"]
        for row in rows:
            relative = Path(row['path']).relative_to('wiki/art-references').as_posix()
            note = row['review_note'].replace('|', '／').replace('\n', ' ')
            lines.append(f"| [{row['id']}](../{relative}) | {row['width']} × {row['height']} | {labels[row['review_status']]} | {note} |")
        lines.extend(["", "完整来源 URL、下载 URL、文件名、字节数、SHA-256 与逐图记录见 [manifest.json](../manifest.json)。",
                      "图片保留原字节与署名；研究留存不等于取得游戏贴图、宣传图或再发行授权。", "",
                      "本目录由 `scripts/wiki_reference_library.py catalog` 生成；逐图更正先改 manifest，再重新生成。", ""])
        (out / f"{source['id']}.md").write_text('\n'.join(lines), encoding='utf-8')
    print(f"Generated {len(data['sources'])} work indexes: {out}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("command", choices=["fetch", "check", "sheets", "catalog"])
    args = ap.parse_args()
    {"fetch": fetch, "check": check, "sheets": sheets, "catalog": catalog}[args.command]()
