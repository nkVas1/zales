#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Builds the sayings corpus from docs/VOICE.md.

The document is the source of truth: the lines are written and reviewed there,
in context, next to the rules that govern them. This turns that prose into the
asset the app ships, so the two can never drift apart.

    python tools/build_corpus.py
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VOICE = ROOT / "docs" / "VOICE.md"
OUT = ROOT / "core" / "voice" / "src" / "main" / "assets" / "sayings.json"

CORPUS_HEADING = "## 5. Стартовый корпус"
SECTION = re.compile(r"^###\s+`([a-z_]+)`\s*(.*)$")
FENCE = "```"

MAX_LENGTH = 64
EXCLAMATION_ALLOWED = {"Прошли!"}


def fail(message: str) -> "NoReturn":  # type: ignore[name-defined]
    print("error: " + message, file=sys.stderr)
    raise SystemExit(1)


def parse() -> list[dict]:
    lines = VOICE.read_text(encoding="utf-8").splitlines()
    try:
        start = next(i for i, line in enumerate(lines) if line.strip() == CORPUS_HEADING)
    except StopIteration:
        fail(f"{VOICE.name} has no section titled {CORPUS_HEADING!r}")

    sayings: list[dict] = []
    seen: set[str] = set()
    context: str | None = None
    register = "HUT"
    inside = False

    for line in lines[start:]:
        heading = SECTION.match(line)
        if heading:
            context = heading.group(1).upper()
            # The register is written in the heading: "регистр **чащи**".
            register = "THICKET" if "чащ" in heading.group(2).lower() else "HUT"
            continue
        if line.startswith(FENCE):
            inside = not inside
            continue
        if not inside or context is None:
            continue
        text = line.strip()
        if not text:
            continue
        if text in seen:
            fail(f"duplicate line: {text!r}")
        seen.add(text)
        sayings.append(
            {
                "id": context.lower() + "_" + hashlib.sha1(text.encode("utf-8")).hexdigest()[:8],
                "text": text,
                "context": context,
                "register": register,
                "tier": "RARE" if context == "RARE" else "COMMON",
            }
        )
    if not sayings:
        fail("no sayings found")
    return sayings


def validate(sayings: list[dict]) -> None:
    problems: list[str] = []
    for saying in sayings:
        text = saying["text"]
        if len(text) > MAX_LENGTH:
            problems.append(f"longer than {MAX_LENGTH} characters: {text!r}")
        if "!" in text and text not in EXCLAMATION_ALLOWED:
            problems.append(f"exclamation mark outside the allow list: {text!r}")
        if any(ord(character) > 0x2100 for character in text):
            problems.append(f"symbol or emoji: {text!r}")
    if problems:
        fail("corpus problems:\n  " + "\n  ".join(problems))


def main() -> None:
    sayings = parse()
    validate(sayings)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps({"version": 1, "sayings": sayings}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    counts: dict[str, int] = {}
    for saying in sayings:
        counts[saying["context"]] = counts.get(saying["context"], 0) + 1
    print(f"{len(sayings)} sayings -> {OUT.relative_to(ROOT)}")
    for context in sorted(counts):
        print(f"  {context.lower():<16} {counts[context]}")


if __name__ == "__main__":
    main()
