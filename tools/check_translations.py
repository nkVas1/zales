# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Fails when a string exists in one language and not the other.

The failure this guards against is silent: a string added to values/ and not to
values-en/ falls back to Russian on an English phone, and nothing anywhere says
so. It is caught here instead of by the person reading it.

    python tools/check_translations.py
"""

from __future__ import annotations

import glob
import io
import os
import re
import sys

NAME = re.compile(r'<string name="([^"]+)"')


def names(path: str) -> set[str]:
    return set(NAME.findall(io.open(path, encoding="utf-8").read()))


def main() -> int:
    sources = sorted(
        glob.glob("*/src/main/res/values/strings.xml")
        + glob.glob("*/*/src/main/res/values/strings.xml")
    )
    if not sources:
        print("no string resources found - wrong working directory?")
        return 1

    complaints: list[str] = []
    for source in sources:
        english = source.replace(os.sep + "values" + os.sep, os.sep + "values-en" + os.sep)
        english = english.replace("/values/", "/values-en/")
        if not os.path.exists(english):
            complaints.append(f"{source}: no English at all")
            continue
        here, there = names(source), names(english)
        for missing in sorted(here - there):
            complaints.append(f"{english}: missing {missing}")
        for extra in sorted(there - here):
            complaints.append(f"{english}: {extra} exists only in English")

    for line in complaints:
        print(line)
    total = sum(len(names(s)) for s in sources)
    print(f"{total} strings across {len(sources)} modules")
    return 1 if complaints else 0


if __name__ == "__main__":
    sys.exit(main())
