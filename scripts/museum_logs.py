"""Export playground logs by time without running the game or compiling anything.

Python 3.10+, standard library. Reads per-launch stdout/stderr and session metadata
under .local/experimental-map/play-museum; writes only .local/reports/museum-logs.
Existing logs remain untouched. Live files are bounded at export start.
"""
import argparse
from datetime import datetime, timedelta
import json
from pathlib import Path
import re
import shutil
import sys


SESSION = re.compile(r"\d{8}-\d{6}(?:-\d{3})?")
CLOCK = re.compile(r"^\[(\d{2}:\d{2}:\d{2})(?:[.,]\d+)?\]")
ROOT = Path(__file__).resolve().parent.parent


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def snapshot_lines(path, size):
    """Do not chase a growing file or split a multibyte character between reads."""
    with path.open("rb") as source:
        remaining = size
        while remaining:
            raw = source.readline(remaining)
            if not raw:
                break
            remaining -= len(raw)
            yield raw.decode("utf-8", errors="replace")


def dated_lines(path, size):
    # Filenames are local launch time, matching the console clock.
    current = datetime.strptime(path.stem[:15], "%Y%m%d-%H%M%S")
    day = current.date()
    previous = current.time()
    for line in snapshot_lines(path, size):
        match = CLOCK.match(line)
        if match:
            clock = datetime.strptime(match[1], "%H:%M:%S").time()
            # Small backward jumps can be cross-thread ordering. A large wrap is midnight.
            if datetime.combine(day, previous) - datetime.combine(day, clock) > timedelta(hours=12):
                day += timedelta(days=1)
            current = datetime.combine(day, clock)
            previous = clock
        # Continuations (including exception stacks) inherit their preceding timestamp.
        yield current, line


def local_datetime(value):
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is not None:
        raise ValueError("Use local log time without a UTC offset: YYYY-MM-DD HH:MM:SS")
    return parsed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="Checkout holding the local logs")
    parser.add_argument("--list", action="store_true", help="List saved sessions")
    parser.add_argument("--session", help="Session filename stem; default: newest log")
    window = parser.add_mutually_exclusive_group()
    window.add_argument("--minutes", type=int, help="Minutes before the last recorded timestamp (default: 15)")
    window.add_argument("--start", help="Local YYYY-MM-DD HH:MM:SS, inclusive")
    window.add_argument("--all", action="store_true", help="Entire session")
    parser.add_argument("--end", help="Local YYYY-MM-DD HH:MM:SS, inclusive; requires --start")
    parser.add_argument("--interactive", action="store_true", help="Prompt for recent minutes")
    args = parser.parse_args()
    if args.end and not args.start:
        parser.error("--end requires --start")
    root = args.root.resolve()
    log_root = root / ".local/experimental-map/play-museum"
    sessions = sorted(p for p in log_root.glob("*.log") if SESSION.fullmatch(p.stem))
    if not sessions:
        raise ValueError(f"No saved playground logs in {log_root}")
    if args.list:
        for path in sessions:
            meta = path.with_suffix(".session.json")
            config = read_json(meta).get("runtimeConfig", {}) if meta.exists() else {}
            print(f"{path.stem}  commit={config.get('commit') or 'UNKNOWN'}  CI={config.get('run') or 'UNKNOWN'}")
        return
    if args.session and not SESSION.fullmatch(args.session):
        raise ValueError("Invalid session ID; use --list")
    path = log_root / f"{args.session}.log" if args.session else sessions[-1]
    if not path.is_file():
        raise ValueError(f"Session log does not exist: {path}")
    if args.interactive and args.minutes is None and not args.start and not args.all:
        print(f"本次导出：{path.stem}（最新一次试玩）")
        answer = input("导出日志末尾多少分钟？直接回车为 15；输入 all 导出整场：").strip()
        if answer.lower() == "all":
            args.all = True
        else:
            args.minutes = int(answer or "15")
    minutes = args.minutes if args.minutes is not None else 15
    if minutes <= 0:
        raise ValueError("--minutes must be positive")
    size = path.stat().st_size
    stderr = path.with_suffix(".err.log")
    stderr_size = stderr.stat().st_size if stderr.exists() else None
    first = last = None
    for at, _ in dated_lines(path, size):
        first = min(first, at) if first else at
        last = max(last, at) if last else at
    if last is None and not args.all:
        raise ValueError("Stdout is empty. Use --all to export startup metadata and stderr.")
    start = local_datetime(args.start) if args.start else (first if args.all else last - timedelta(minutes=minutes))
    end = local_datetime(args.end) if args.end else last
    if start and end and start > end:
        raise ValueError("Start is after end")
    session_file = path.with_suffix(".session.json")
    session = read_json(session_file) if session_file.exists() else None
    config = session.get("runtimeConfig", {}) if session else {}
    # Never attribute historic logs to today's checkout or mutable runtime manifest.
    commit = config.get("commit")
    run = config.get("run")
    output = root / ".local/reports/museum-logs" / f"{path.stem}_{datetime.now():%Y%m%d-%H%M%S-%f}"
    output.mkdir(parents=True, exist_ok=False)
    selected = 0
    with (output / "stdout.log").open("w", encoding="utf-8", newline="") as target:
        for at, line in dated_lines(path, size):
            if args.all or start <= at <= end:
                target.write(line)
                selected += 1
    # stderr often has no clock; preserve it in full instead of pretending to time-filter it.
    if stderr_size is not None:
        with stderr.open("rb") as source, (output / "stderr-full.log").open("wb") as target:
            remaining = stderr_size
            while remaining:
                chunk = source.read(min(remaining, 1024 * 1024))
                if not chunk:
                    break
                target.write(chunk)
                remaining -= len(chunk)
    metadata = {
        "session": path.stem, "source": str(path), "source_bytes": size,
        "exported_at": datetime.now().astimezone().isoformat(),
        "start": str(start), "end": str(end), "lines": selected,
        "runtime_commit": commit, "ci_run": run,
        "version_status": "recorded at launch" if commit else "版本待核实",
        "launch": session,
    }
    (output / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = (
        f"试玩日志：{path.stem}\n"
        f"时间范围（启动机器本地时间）：{start} ～ {end}\n"
        f"命中行数：{selected}\n"
        f"实际运行提交：{commit or '版本待核实（旧日志没有启动版本快照）'}\n"
        f"CI run：{run or '未知'}\n"
        f"原始日志：{path}\n\n"
        "stdout.log：选定时间范围，含异常堆栈续行；stderr-full.log：整场标准错误，未按时间筛选。\n"
        "默认最近 N 分钟以日志末尾为终点，退出游戏后仍可导出。--list / --session 可选旧场次。\n"
        "游戏仍在运行时只截取导出开始时已有内容，后续事件请重新导出。\n"
        "只有时分秒的日志按启动日期及可见跨午夜推算；改系统时钟或超过一天无日志可能影响日期，请对照原文。\n"
        "日志只包含实际打点的事件。未记录不等于未发生，完整读数只在点击读数时生成。\n"
    )
    if not selected:
        summary += "本次范围无 stdout 内容，请检查日期、时间、场次或改用 --all。\n"
    (output / "说明.txt").write_text(summary, encoding="utf-8")
    archive = shutil.make_archive(str(output), "zip", output)
    print(summary)
    print(f"日志包：{archive}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, EOFError) as error:
        print(f"Log export failed: {error}", file=sys.stderr)
        sys.exit(1)
