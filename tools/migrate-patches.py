#!/usr/bin/env python3
"""MultiPaper patch-layout migration: old paperweight 1.x patches/ -> paperweight 2.x layout.

Old layout (paperweight 1.5.7):
    patches/server/0001-Name.patch     (mixed io.papermc + net.minecraft hunks, paths: src/main/java/...)
    patches/api/*.patch                (Spigot/Bukkit API tree)
    patches/removed/*.patch            (reverse patches reverting upstream Purpur changes)

New layout (paperweight 2.0.0-beta.21):
    multipaper-server/paper-patches/features/      (paths: src/main/java/...)
    multipaper-server/minecraft-patches/features/  (paths: net/minecraft/... — 'src/main/java/' stripped)
    multipaper-api/paper-patches/features/         (paths: src/main/java/...)

Rules:
  - Each patch file is split into file-segments at 'diff --git' boundaries.
  - Segment target tree: path.startswith('src/main/java/net/minecraft/') -> NMS tree
    ('src/main/java/' prefix stripped); everything else -> paper tree; api group -> api tree.
  - Mixed patches produce one output file per tree; the NMS part gets the '-NMS' suffix
    only when the paper part also exists.
  - Segments touching root build files are detached to tools/build-file-segments/
    (applied by hand to the new build scripts).
  - Original format-patch headers (From/Date/Subject) preserved verbatim.
  - Emits tools/multipaper-patch-map.md (old -> new mapping).
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PATCHES = ROOT / "patches"
BUILD_SEGMENTS = ROOT / "tools" / "build-file-segments"
MAP_FILE = ROOT / "tools" / "multipaper-patch-map.md"

OUT = {
    "server-paper": ROOT / "multipaper-server" / "paper-patches" / "features",
    "server-nms": ROOT / "multipaper-server" / "minecraft-patches" / "features",
    "api": ROOT / "multipaper-api" / "paper-patches" / "features",
}

DIFF_RE = __import__("re").compile(r"^diff --git a/(.+?) b/")
NMS_PREFIX = "src/main/java/net/minecraft/"
BUILD_FILE_RE = __import__("re").compile(r"^(?:src/main/java/)?(?:build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradle/wrapper/.*)$")


def split_segments(text: str):
    """Return (header_text, [(path, segment_text), ...]) preserving order."""
    lines = text.splitlines(keepends=True)
    starts = [i for i, ln in enumerate(lines) if ln.startswith("diff --git ")]
    if not starts:
        return "".join(lines), []
    header = "".join(lines[: starts[0]])
    bounds = starts + [len(lines)]
    segs = []
    for i in range(len(starts)):
        blob = "".join(lines[bounds[i] : bounds[i + 1]])
        m = DIFF_RE.match(blob)
        segs.append((m.group(1) if m else "", blob))
    return header, segs


def classify(path: str):
    """Return (kind, rewritten_path). kind in {'nms','paper','build'}."""
    if path.startswith(NMS_PREFIX):
        return "nms", path[len("src/main/java/") :]
    if BUILD_FILE_RE.match(path):
        return "build", path
    return "paper", path


def main() -> None:
    if "--clean" in sys.argv:
        for d in OUT.values():
            for f in d.glob("*.patch"):
                f.unlink()
        for f in BUILD_SEGMENTS.glob("*.patch"):
            f.unlink()

    groups = {
        "server": (PATCHES / "server", ("server-paper", "server-nms")),
        "api": (PATCHES / "api", ("api",)),
        "removed": (PATCHES / "removed", ("server-paper", "server-nms")),
    }
    rows = []
    for group, (src_dir, out_keys) in groups.items():
        for pf in sorted(src_dir.glob("*.patch")):
            header, segs = split_segments(pf.read_text(encoding="utf-8", errors="replace"))
            if not segs:
                print(f"SKIP {group}/{pf.name}: no diff segments")
                continue
            buckets = {"paper": [], "nms": [], "build": []}
            for path, seg in segs:
                kind, _ = classify(path)
                buckets[kind].append(seg)

            basename = pf.stem
            nms_suffix = "-NMS" if buckets["nms"] and buckets["paper"] else ""
            for kind, outkey in (("paper", out_keys[0]), ("nms", out_keys[-1] if len(out_keys) > 1 else None)):
                if not buckets[kind] or outkey is None:
                    continue
                body = buckets[kind]
                if kind == "nms":
                    body = [s.replace(NMS_PREFIX, "net/minecraft/") for s in body]
                name = f"{basename}{'-NMS' if kind == 'nms' and buckets['paper'] else ''}.patch"
                target = OUT[outkey] / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(header + "".join(body), encoding="utf-8", newline="\n")
                rows.append((f"{group}/{pf.name}", outkey, name, len(bucket := buckets[kind])))

            if buckets["build"]:
                bf = BUILD_SEGMENTS / pf.name
                bf.parent.mkdir(parents=True, exist_ok=True)
                bf.write_text(header + "".join(buckets["build"]), encoding="utf-8", newline="\n")
                rows.append((f"{group}/{pf.name}", "build-file-segments", bf.name, len(buckets["build"])))

    MAP_FILE.parent.mkdir(parents=True, exist_ok=True)
    with MAP_FILE.open("w", encoding="utf-8") as fh:
        fh.write("# MultiPaper patch migration map\n\n| old | new root | new file | segments |\n|---|---|---|---|\n")
        for old, root, new, n in rows:
            fh.write(f"| {old} | {root} | {new} | {n} |\n")

    # sanity: counts per root
    from collections import Counter
    c = Counter(r[1] for r in rows)
    print("entries:", len(rows), dict(c))
    for key, d in OUT.items():
        n = len(list(d.glob("*.patch")))
        print(f"{d.relative_to(ROOT)}: {n} files")
    print(f"map -> {MAP_FILE.relative_to(ROOT)}")


if __name__ == "__main__":
    main()