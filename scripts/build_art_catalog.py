"""Build the art family index from tracked files and existing source registries."""
import argparse
from bisect import bisect_left
from collections import defaultdict
from fnmatch import fnmatchcase
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
ASSETS = "src/main/resources/assets/mcyukkuri/"
LIBRARY = "standards/yukkuri-library/"
OUTPUT = ROOT / "art/CATALOG.md"
RUNTIME_DIRS = ("textures/", "models/", "yukkuri_props/", "yukkuri_presentation/",
                "yukkuri/", "yukkuri_wounds/", "sounds/", "sounds.json")
PATH = re.compile(r"(?:src/main/resources/|standards/yukkuri-library/|art/|\.local/previews/)[\w./{}*,-]+")
STATUS_KEYS = {"status", "standard_status", "shape_status", "game_acceptance", "runtime_acceptance"}


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for key, item in value.items():
            yield key
            yield from strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from strings(item)


def select(data, key):
    for part in key.split("."):
        data = data[part]
    return data


def resource_id(path):
    name = PurePosixPath(path).name
    return name.split(".", 1)[0]


def link(path, label=None):
    return f"[{label or path}]({quote('../' + path, safe='/#')})"


def cell(value):
    return str(value).replace("|", "&#124;").replace("\n", " ")


def paths_in(value):
    return {match.rstrip(".,") for text in strings(value) for match in PATH.findall(text)}


def registry_paths(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key not in {"history", "inputs", "input_model", "references", "note", "scope", "validation",
                           "checked_unchanged", "applied_before", "carried_from_base", "derived_reexports"}:
                yield from registry_paths(item)
    elif isinstance(value, list):
        for item in value:
            yield from registry_paths(item)
    elif isinstance(value, str) and PATH.fullmatch(value):
        yield value


def matching_paths(reference, files):
    pattern = re.sub(r"\{[^}]+\}", "*", reference)
    prefix = pattern.split("*", 1)[0]
    start = bisect_left(files, prefix)
    for path in files[start:]:
        if not path.startswith(prefix):
            break
        if path == reference or path.startswith(reference.rstrip("/") + "/") or fnmatchcase(path, pattern):
            yield path


def statuses(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key in STATUS_KEYS and isinstance(item, str):
                yield item
            elif isinstance(item, (dict, list)):
                yield from statuses(item)
    elif isinstance(value, list):
        for item in value:
            yield from statuses(item)


def collect(tracked, categories):
    files = [p for p in tracked if p not in {"art/catalog.json", "art/CATALOG.md"}
             and (p.startswith(("art/", LIBRARY)) or
                  p.startswith(tuple(ASSETS + d for d in RUNTIME_DIRS)))]
    registry = read("art/runtime-sources.json")
    library = read(LIBRARY + "catalog-data.json")
    families = [f for category in categories for f in category["families"]]
    owners = {}
    records = defaultdict(list)
    references = defaultdict(set)
    covered_keys = set()
    for family in families:
        ident = family["id"]
        for key in family.get("registry", []):
            record = select(registry, key)
            records[ident].append(("art/runtime-sources.json", record))
            references[ident].update(registry_paths(record))
            covered_keys.add(key.split(".")[0])
        for key in family.get("library", []):
            record = select(library, key)
            records[ident].append((LIBRARY + "catalog-data.json", record))
        for path in files:
            scores = [len(prefix) for prefix in family.get("prefixes", []) if path.startswith(prefix)]
            names = {resource_id(path)} if path.startswith(ASSETS) and not path.startswith(
                (ASSETS + "yukkuri_wounds/", ASSETS + "textures/wounds/")) else set()
            if path.startswith(ASSETS + "textures/entity/"):
                names.add(path.removeprefix(ASSETS + "textures/entity/").split("/")[0])
            if any(fnmatchcase(name, pattern) for pattern in family.get("ids", []) for name in names):
                scores.append(1000)
            if scores and (path not in owners or max(scores) > owners[path][0]):
                owners[path] = (max(scores), ident)
    # Package manifests join outputs that are not explicitly assigned above.
    for path, (_, ident) in list(owners.items()):
        if PurePosixPath(path).name in {"manifest.json", "provenance.json", "selection.json", "exports.json"}:
            record = read(path)
            records[ident].append((path, record))
            for key in ("files", "outputs", "exports"):
                if key in record:
                    references[ident].update(paths_in(record[key]))
    for family in families:
        ident = family["id"]
        for reference in sorted(references[ident]):
            for path in matching_paths(reference, files):
                if path not in owners:
                    owners[path] = (0, ident)
    # Item/block wrappers share the mesh or texture id in the export contract.
    runtime_ids = {resource_id(p): ident for p, (_, ident) in owners.items()
                   if p.startswith(ASSETS + "yukkuri_props/")}
    for path in files:
        if path not in owners and path.startswith((ASSETS + "models/", ASSETS + "textures/props/")):
            ident = runtime_ids.get(resource_id(path))
            if ident:
                owners[path] = (0, ident)
    members = defaultdict(list)
    for path in files:
        members[owners[path][1] if path in owners else "uncategorized"].append(path)
    unknown_keys = sorted(key for key, value in registry.items()
                          if isinstance(value, dict) and key not in covered_keys)
    return members, records, unknown_keys


def usage(tracked, runtime):
    aliases = defaultdict(set)
    for path in runtime:
        relative = path.removeprefix(ASSETS)
        aliases[relative].add(path)
        aliases["mcyukkuri:" + relative].add(path)
        if relative.startswith(("yukkuri/", "yukkuri_props/")) and not resource_id(path).endswith("index"):
            aliases[resource_id(path)].add(path)
        if relative.startswith("models/item/"):
            aliases["mcyukkuri:" + resource_id(path)].add(path)
        if relative.startswith(("textures/", "models/", "sounds/")):
            short = relative.split("/", 1)[1].rsplit(".", 1)[0]
            aliases[short].add(path)
            aliases["mcyukkuri:" + short].add(path)
    found = defaultdict(set)
    for source in tracked:
        if source.startswith("src/main/java/") and source.endswith(".java"):
            text = (ROOT / source).read_text(encoding="utf-8")
            text = re.sub(r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"',
                          lambda m: m[0] if m[0].startswith('"') else "", text, flags=re.S)
            tokens = re.findall(r'"([^"\n]*)"', text)
        elif source.startswith("src/main/resources/") and source.endswith(".json"):
            tokens = strings(read(source))
        else:
            continue
        for token in tokens:
            for path in aliases.get(token, ()):
                if path != source:
                    found[path].add(source)
    return found


def path_cell(paths):
    if not paths:
        return "—"
    if len(paths) <= 12:
        return "<br>".join(link(p, p.removeprefix(ASSETS)) for p in paths)
    groups = defaultdict(list)
    for path in paths:
        if path.startswith(ASSETS):
            parts = path.removeprefix(ASSETS).split("/")
            depth = 3 if parts[0] == "textures" and len(parts) > 3 else 2 if parts[0] in {"textures", "models", "sounds"} else 1
            directory = ASSETS + "/".join(parts[:depth])
        else:
            parts = path.split("/")
            depth = 4 if path.startswith(LIBRARY) and len(parts) > 3 else 3
            directory = "/".join(parts[:depth])
        groups[directory].append(path)
    links = "<br>".join(link(directory, directory.removeprefix(ASSETS)) if directory in items else
                        link(directory + "/", directory.removeprefix(ASSETS) + f"/（{len(items)} 文件）")
                        for directory, items in sorted(groups.items()))
    return f"<details><summary>{len(paths)} 文件 · {len(groups)} 目录／入口</summary>{links}</details>"


def keywords(paths, language):
    names = {resource_id(p) for p in paths if p.startswith((ASSETS + "yukkuri_props/", ASSETS + "models/item/"))
             and not resource_id(p).endswith("index")}
    labels = {ident: next((language[k] for k in ("item.mcyukkuri." + ident, "block.mcyukkuri." + ident)
                           if k in language), ident) for ident in names}
    return "、".join(f"{label} (`{ident}`)" if label != ident else f"`{ident}`" for ident, label in sorted(labels.items()))


def source_notes(paths):
    notes = set()
    for path in paths:
        if path.endswith("/README.md"):
            for name, description in re.findall(r"^\| `([^`]+\.(?:png|bbmodel|wav|ogg))` \| ([^|]+) \|",
                                                (ROOT / path).read_text(encoding="utf-8"), re.M):
                asset = (PurePosixPath(path).parent / name).as_posix()
                if asset in paths:
                    notes.add(link(asset, name) + "：" + description)
    return "<br>".join(sorted(notes))


def title(family, paths, records):
    if "label" in family:
        return family["label"]
    for path in sorted(paths, key=lambda p: (p.count("/"), p)):
        if path.endswith("/README.md"):
            return (ROOT / path).read_text(encoding="utf-8").splitlines()[0].lstrip("# ")
    labels = [value["label"] for _, record in records if isinstance(record, dict)
              for value in record.values() if isinstance(value, dict) and "label" in value]
    return "、".join(dict.fromkeys(labels)) or family["id"]


def render(categories, members, records, found, unknown_keys):
    library = read(LIBRARY + "catalog-data.json")
    language = read(ASSETS + "lang/zh_cn.json")
    runtime_files = sorted(p for paths in members.values() for p in paths if p.startswith(ASSETS))
    source_files = sorted(p for paths in members.values() for p in paths if not p.startswith(ASSETS))
    lines = ["# 美术素材目录", "", "<!-- 由 scripts/build_art_catalog.py 生成；登记源在 art/registry/，先用 scripts/export.py --registries 汇总再重建。 -->", "",
             "按游戏主题查找现有素材；目录不移动文件，也不替代各包的认可登记。文件数包含制作脚本、配方与登记；同族的网格、贴图与语音合并显示。", "",
             "来源：`git ls-files`、[运行选用登记](runtime-sources.json)、[标准库目录](../standards/yukkuri-library/catalog-data.json)及包内 manifest／provenance／selection。运行路径相对 `src/main/resources/assets/mcyukkuri/`，其他路径相对仓库根。", "",
             "“游戏引用”逐文件查 Java 字符串及运行 JSON 的资源路径／资源 ID（排除自身引用），只证明存在静态引用，不代表可达、已启用或试玩通过；动态拼接未命中时仍待核实。共享运行文件另列，不重复计入分类文件数。预览只链接当前工作树 `.local/previews/` 中存在且登记提到的文件，可为空。", "",
             "| 分类 | 素材族数 | 文件数 |", "| --- | ---: | ---: |"]
    for category in categories:
        counts = [len(members[f["id"]]) for f in category["families"]]
        lines.append(f"| [{category['label']}](#{category['id']}) | {sum(n > 0 for n in counts)} | {sum(counts)} |")
    lines.append("")
    for category in categories:
        lines += [f'<a id="{category["id"]}"></a>', f"## {category['label']}", "",
                  "| 素材族 · 内容 | 运行资源 | 选用快照／独立制作源 | 标准库制作源 | 当前预览 | 游戏引用 | 登记状态 |",
                  "| --- | --- | --- | --- | --- | --- | --- |"]
        for family in category["families"]:
            ident = family["id"]
            paths = members[ident]
            if not paths:
                continue
            runtime = [p for p in paths if p.startswith(ASSETS)]
            shared = sorted({p for _, record in records[ident] for ref in registry_paths(record)
                             if ref.startswith(ASSETS) for p in matching_paths(ref, runtime_files)} - set(runtime))
            snapshots = [p for p in paths if p.startswith("art/")]
            sources = [p for p in paths if p.startswith(LIBRARY)]
            related = {p for _, record in records[ident] for ref in registry_paths(record)
                       if ref.startswith(("art/", LIBRARY)) for p in matching_paths(ref, source_files)} - set(paths)
            shared_snapshots = sorted(p for p in related if p.startswith("art/"))
            shared_sources = sorted(p for p in related if p.startswith(LIBRARY))
            name = title(family, paths, records[ident])
            previews = sorted({p for _, record in records[ident] for p in paths_in(record)
                               if p.startswith(".local/previews/") and (ROOT / p).is_file()})
            state = sorted({s for _, record in records[ident] for s in statuses(record)})
            state_links = sorted({p for p, record in records[ident] if list(statuses(record))})
            status = "、".join(state) + ("<br>" + " · ".join(link(p, "登记") for p in state_links) if state_links else "")
            if len(state) > 3:
                status = f"<details><summary>各项登记状态 · {len(state)} 种</summary>{status}</details>"
            linked_runtime = runtime + shared
            used = [p for p in linked_runtime if p in found]
            use = f"有静态引用 {len(used)}/{len(linked_runtime)}" if used else "未发现静态引用（动态加载待核实）" if linked_runtime else "未关联运行文件"
            if used:
                evidence = min(found[used[0]])
                use += "<br>" + link(evidence, "引用示例")
            description = f"**{name}**<br>`{ident}` · {len(paths)} 文件"
            notes = source_notes(paths)
            if notes:
                description += "<br>" + notes
            names = keywords(runtime, language)
            if names:
                description += f"<details><summary>物品／网格检索</summary>{names}</details>"
            for key in family.get("library", []):
                entries = select(library, key)
                labels = [v["label"] for v in entries.values() if isinstance(v, dict) and "label" in v]
                if labels:
                    description += f"<details><summary>标准库 {key} · {len(labels)} 项</summary>" + "、".join(labels) + "</details>"
            runtime_cell = path_cell(runtime) + ("<br>共享：" + path_cell(shared) if shared else "")
            snapshot_cell = path_cell(snapshots) + ("<br>共用：" + path_cell(shared_snapshots) if shared_snapshots else "")
            source_cell = path_cell(sources) + ("<br>共用：" + path_cell(shared_sources) if shared_sources else "")
            cells = [description, runtime_cell, snapshot_cell, source_cell,
                     "<br>".join(link(p, "预览") for p in previews) or "—", use, status or "未登记状态"]
            lines.append("| " + " | ".join(cell(c) for c in cells) + " |")
        lines.append("")
    lines += ["## 未归类", ""]
    lines += [f"- {link(p)}" for p in members["uncategorized"]]
    lines += [f"- 运行登记键：`{key}`" for key in unknown_keys]
    if not members["uncategorized"] and not unknown_keys:
        lines.append("无。")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="只读检查归类覆盖与生成目录是否最新")
    args = parser.parse_args()
    tracked = sorted(subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode("utf-8").rstrip("\0").split("\0"))
    categories = read("art/catalog.json")["categories"]
    members, records, unknown_keys = collect(tracked, categories)
    runtime = [p for paths in members.values() for p in paths if p.startswith(ASSETS)]
    content = render(categories, members, records, usage(tracked, runtime), unknown_keys)
    unclaimed = len(members["uncategorized"]) + len(unknown_keys)
    if args.check:
        stale = not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != content
        print(f"Art catalog: {unclaimed} unclassified; {'stale' if stale else 'up to date'}")
        return int(bool(unclaimed or stale))
    OUTPUT.write_text(content, encoding="utf-8", newline="\n")
    print(f"Wrote art/CATALOG.md: {sum(len(p) for p in members.values())} files; {unclaimed} unclassified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
