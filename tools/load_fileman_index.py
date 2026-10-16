#!/usr/bin/env python3
"""Build a SQLite + FTS5 index over a FileMan / VistA M routine corpus.

Companion to jvmumps#32 (FileMan Tier A). Derived, disposable index —
does not touch MeshGraph (YottaDB) or tsx (Comdb2).

Env:
  FILEMAN_CORPUS  corpus root (default $HOME/_fileman)
  FILEMAN_INDEX   sqlite file  (default $FILEMAN_CORPUS/fileman-index.sqlite)

Read-only for the corpus. Idempotent: DROP + CREATE on each run.

Usage:
  tools/load_fileman_index.py [--limit N] [--stats]
"""
from __future__ import annotations

import argparse
import os
import re
import sqlite3
import sys
from pathlib import Path

# ---- line shapes (ZSAVE .m) -------------------------------------------

# Tag at column 0: NAME, then space / ( / EOL. Not a command word.
RE_TAG = re.compile(r"^([A-Za-z%][A-Za-z0-9%]*)(\(([^)]*)\))?(?=[ \t]|$)")
# Commands we never treat as tags
CMD_WORDS = {
    "SET", "IF", "ELSE", "FOR", "DO", "GOTO", "QUIT", "WRITE", "READ", "KILL",
    "NEW", "MERGE", "HALT", "HANG", "LOCK", "UNLOCK", "OPEN", "CLOSE", "USE",
    "VIEW", "XECUTE", "JOB", "TSTART", "TCOMMIT", "TROLLBACK", "BREAK",
}
# DO / GOTO targets: tag, tag^RTN, ^RTN, $$tag, $$tag^RTN
RE_DO = re.compile(r"\bD(?:O)?\s+(\$\$)?([A-Za-z%][A-Za-z0-9%]*)?(\^([A-Za-z%][A-Za-z0-9%]*))?")
RE_GOTO = re.compile(r"\bG(?:OTO)?\s+([A-Za-z%][A-Za-z0-9%]*)?(\^([A-Za-z%][A-Za-z0-9%]*))?")
RE_EXTRINSIC = re.compile(r"\$\$([A-Za-z%][A-Za-z0-9%]*)(\^([A-Za-z%][A-Za-z0-9%]*))?")
RE_INTRINSIC = re.compile(r"\$([A-Za-z%][A-Za-z0-9%]*)")
# Known-gap markers
RE_ZSTAR = re.compile(r"\$Z[A-Z0-9%]*")
RE_XCALL = re.compile(r"\$&[A-Za-z]|X\^|XECUTE\s+@?")


def package_of(rel: str) -> str:
    top = rel.split("/", 1)[0]
    return top if "/" in rel else "root"


def parse_routine(text: str):
    """Return (labels, calls, intrinsics, gaps). Each label is (name, line, formals)."""
    labels, calls, intrinsics, gaps = [], [], [], []
    for n, raw in enumerate(text.splitlines(), 1):
        if not raw:
            continue
        # tag at col 0
        m = RE_TAG.match(raw)
        if m and m.group(1).upper() not in CMD_WORDS:
            formals = m.group(3).strip() if m.group(3) else None
            labels.append((m.group(1), n, formals))
        body = raw
        for mm in RE_EXTRINSIC.finditer(body):
            calls.append((n, "EXTRINSIC", mm.group(0), mm.group(1), mm.group(3)))
        for mm in RE_DO.finditer(body):
            if mm.group(1):  # $$ handled above
                continue
            tag, ext = mm.group(2), mm.group(4)
            if tag or ext:
                tgt = (tag or "") + ("^" + ext if ext else "")
                calls.append((n, "DO", tgt, tag, ext))
        for mm in RE_GOTO.finditer(body):
            tag, ext = mm.group(1), mm.group(3)
            if tag or ext:
                tgt = (tag or "") + ("^" + ext if ext else "")
                calls.append((n, "GOTO", tgt, tag, ext))
        for mm in RE_INTRINSIC.finditer(body):
            name = mm.group(1).upper()
            if name.startswith("Z") or name in {"H", "I", "J", "K", "L", "N", "P", "Q", "R", "S", "T", "X"}:
                intrinsics.append((n, name))
            elif name.isalpha() or any(c.isdigit() for c in name):
                intrinsics.append((n, name))
        for mm in RE_ZSTAR.finditer(body):
            gaps.append((n, "ZSTAR", mm.group(0)))
        for mm in RE_XCALL.finditer(body):
            gaps.append((n, "XCALL", mm.group(0)[:40]))
    return labels, calls, intrinsics, gaps


def schema(db: sqlite3.Connection) -> None:
    db.executescript(
        """
        PRAGMA journal_mode=WAL;
        DROP TABLE IF EXISTS routine_fts;
        DROP TABLE IF EXISTS routine;
        DROP TABLE IF EXISTS label;
        DROP TABLE IF EXISTS call;
        DROP TABLE IF EXISTS intrinsic;
        DROP TABLE IF EXISTS gap;

        CREATE TABLE routine (
          id INTEGER PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL UNIQUE,
          package TEXT NOT NULL, labels INTEGER NOT NULL DEFAULT 0,
          lines INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX ix_routine_name ON routine(name);
        CREATE INDEX ix_routine_pkg  ON routine(package);

        CREATE TABLE label (
          id INTEGER PRIMARY KEY, routine INTEGER NOT NULL REFERENCES routine(id) ON DELETE CASCADE,
          name TEXT NOT NULL, line INTEGER NOT NULL, formals TEXT,
          extrinsic INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX ix_label_routine ON label(routine);
        CREATE INDEX ix_label_name    ON label(name);

        CREATE TABLE call (
          id INTEGER PRIMARY KEY, routine INTEGER NOT NULL REFERENCES routine(id) ON DELETE CASCADE,
          line INTEGER NOT NULL, kind TEXT NOT NULL, target TEXT NOT NULL,
          tag TEXT, ext TEXT
        );
        CREATE INDEX ix_call_target ON call(target);
        CREATE INDEX ix_call_ext    ON call(ext);

        CREATE TABLE intrinsic (
          id INTEGER PRIMARY KEY, routine INTEGER NOT NULL REFERENCES routine(id) ON DELETE CASCADE,
          line INTEGER NOT NULL, name TEXT NOT NULL
        );
        CREATE INDEX ix_intrinsic_name ON intrinsic(name);

        CREATE TABLE gap (
          id INTEGER PRIMARY KEY, routine INTEGER NOT NULL REFERENCES routine(id) ON DELETE CASCADE,
          line INTEGER NOT NULL, kind TEXT NOT NULL, detail TEXT
        );
        CREATE INDEX ix_gap_kind ON gap(kind);

        CREATE VIRTUAL TABLE routine_fts USING fts5(name, body);
        """
    )


def load(db: sqlite3.Connection, root: Path, limit: int) -> dict:
    counts = {"routines": 0, "labels": 0, "calls": 0, "intrinsics": 0, "gaps": 0}
    files = sorted(root.rglob("*.m"))
    if limit:
        files = files[:limit]
    for p in files:
        rel = p.relative_to(root).as_posix()
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except OSError as e:
            print(f"  READ FAIL {rel}: {e}", file=sys.stderr)
            continue
        name = p.stem.upper()
        pkg = package_of(rel)
        labels, calls, intrinsics, gaps = parse_routine(text)
        cur = db.execute(
            "INSERT INTO routine(name,path,package,labels,lines,bytes) VALUES(?,?,?,?,?,?)",
            (name, rel, pkg, len(labels), text.count("\n") + 1, len(text)),
        )
        rid = cur.lastrowid
        db.execute(
            "INSERT INTO routine_fts(rowid,name,body) VALUES(?,?,?)", (rid, name, text)
        )
        for lab, line, formals in labels:
            db.execute(
                "INSERT INTO label(routine,name,line,formals,extrinsic) VALUES(?,?,?,?,?)",
                (rid, lab, line, formals, 1 if formals else 0),
            )
        for line, kind, target, tag, ext in calls:
            db.execute(
                "INSERT INTO call(routine,line,kind,target,tag,ext) VALUES(?,?,?,?,?,?)",
                (rid, line, kind, target, tag, ext),
            )
        for line, iname in intrinsics:
            db.execute("INSERT INTO intrinsic(routine,line,name) VALUES(?,?,?)", (rid, line, iname))
        for line, kind, detail in gaps:
            db.execute("INSERT INTO gap(routine,line,kind,detail) VALUES(?,?,?,?)", (rid, line, kind, detail))
        counts["routines"] += 1
        counts["labels"] += len(labels)
        counts["calls"] += len(calls)
        counts["intrinsics"] += len(intrinsics)
        counts["gaps"] += len(gaps)
    db.commit()
    return counts


def stats(db: sqlite3.Connection) -> None:
    print("\n### package × routines")
    for pkg, n in db.execute("SELECT package, COUNT(*) FROM routine GROUP BY 1 ORDER BY 2 DESC"):
        print(f"  {pkg:<12} {n}")
    print("\n### top targets (DO/$$/GOTO)")
    for tgt, n in db.execute(
        "SELECT target, COUNT(*) FROM call GROUP BY 1 ORDER BY 2 DESC LIMIT 12"
    ):
        print(f"  {n:>5}  {tgt}")
    print("\n### top intrinsics")
    for nm, n in db.execute(
        "SELECT name, COUNT(*) FROM intrinsic GROUP BY 1 ORDER BY 2 DESC LIMIT 12"
    ):
        print(f"  {n:>5}  ${nm}")
    print("\n### gaps by kind")
    for kind, n in db.execute("SELECT kind, COUNT(*) FROM gap GROUP BY 1 ORDER BY 2 DESC"):
        print(f"  {kind:<8} {n}")
    print("\n### extrinsics (formals) sample")
    for r, lab, f in db.execute(
        "SELECT r.name, l.name, l.formals FROM label l JOIN routine r ON r.id=l.routine"
        " WHERE l.extrinsic=1 ORDER BY r.name, l.name LIMIT 12"
    ):
        print(f"  $${lab}^{r}({f})")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--stats", action="store_true")
    args = ap.parse_args()

    root = Path(os.environ.get("FILEMAN_CORPUS", Path.home() / "_fileman"))
    db_path = Path(os.environ.get("FILEMAN_INDEX", root / "fileman-index.sqlite"))
    if not root.is_dir():
        print(f"no corpus at {root}", file=sys.stderr)
        return 1

    db_path.parent.mkdir(parents=True, exist_ok=True)
    if db_path.exists():
        db_path.unlink()
    db = sqlite3.connect(db_path)
    try:
        schema(db)
        counts = load(db, root, args.limit)
        print(f"indexed {counts['routines']} routines -> {db_path}")
        for k in ("labels", "calls", "intrinsics", "gaps"):
            print(f"  {k}: {counts[k]}")
        if args.stats:
            stats(db)
    finally:
        db.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
