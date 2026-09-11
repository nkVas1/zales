#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Build zales-core.aar — the single Go artefact of Zales.

Runs identically on a Linux CI runner and on a Windows workstation. Every Go
dependency (libXray, Xray-core, gVisor) is compiled by ONE gomobile invocation,
because a process can host only one Go runtime (docs/adr/0003).

The AAR is also laid out as a file-based Maven repository under
native/prebuilt/maven, which the Gradle build resolves like any other library.

Usage:
    python native/build_core.py [--out native/prebuilt]
    python native/build_core.py --package-only     # republish an existing AAR

Requires: Go (toolchain per native/core/go.mod), gomobile + gobind on PATH or in
$GOPATH/bin, and an Android SDK with an NDK installed.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parent
CORE = ROOT / "core"

# Kept in lock-step with gradle/libs.versions.toml `minSdk`.
ANDROID_API = "26"
# arm64 for every current phone, arm for the long tail of 32-bit userlands.
TARGETS = "android/arm64,android/arm"
JAVA_PACKAGE = "io.github.nkvas1.zales"

# Maven coordinates the Gradle build resolves from native/prebuilt/maven.
MAVEN_GROUP = "io.github.nkvas1.zales"
MAVEN_ARTIFACT = "zales-core"

LDFLAGS = " ".join(
    [
        "-s",
        "-w",
        "-buildid=",
        # Xray-core and gVisor rely on go:linkname into the runtime.
        "-checklinkname=0",
        # 16 KB pages are mandatory on Android 15+ devices.
        "-extldflags=-Wl,-z,max-page-size=16384",
    ]
)

POM_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{group}</groupId>
  <artifactId>{artifact}</artifactId>
  <version>{version}</version>
  <packaging>aar</packaging>
</project>
"""

# CalVer pinned in go.mod, e.g. "github.com/xtls/libxray v1.260909.0".
LIBXRAY_REQUIREMENT = re.compile("github[.]com/xtls/libxray v1[.]([0-9]{2})([0-9]{2})([0-9]{2})[.][0-9]+")


def fail(message: str) -> NoReturn:
    print("error: " + message, file=sys.stderr)
    raise SystemExit(1)


def find_tool(name: str) -> str:
    exe = name + (".exe" if os.name == "nt" else "")
    found = shutil.which(exe) or shutil.which(name)
    if found:
        return found
    gopath = Path(os.environ.get("GOPATH") or Path.home() / "go")
    candidate = gopath / "bin" / exe
    if candidate.exists():
        return str(candidate)
    fail(f"{name} not found on PATH or in {gopath / 'bin'} - run: go install golang.org/x/mobile/cmd/{name}@latest")


def find_ndk() -> Path:
    for var in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        value = os.environ.get(var)
        if value and Path(value).is_dir():
            return Path(value)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        fail("ANDROID_HOME is not set")
    ndk_dir = Path(sdk) / "ndk"
    if not ndk_dir.is_dir():
        fail(f"no NDK found under {ndk_dir}")
    versions = sorted(
        (p for p in ndk_dir.iterdir() if p.is_dir()),
        key=lambda p: [int(x) for x in p.name.split(".") if x.isdigit()],
    )
    if not versions:
        fail(f"no NDK found under {ndk_dir}")
    return versions[-1]


def run(cmd: list[str], env: dict[str, str], cwd: Path, attempts: int = 1) -> None:
    """Runs a command, retrying network-bound steps with a growing pause."""
    returncode = 0
    for attempt in range(1, attempts + 1):
        suffix = f"   (attempt {attempt}/{attempts})" if attempts > 1 else ""
        print("$ " + " ".join(cmd) + suffix, flush=True)
        returncode = subprocess.run(cmd, cwd=cwd, env=env).returncode
        if returncode == 0:
            return
        if attempt < attempts:
            time.sleep(10 * attempt)
    fail(f"command failed with exit code {returncode}")


def core_version() -> str:
    """The libXray CalVer pinned in go.mod: v1.260909.0 -> 26.9.9."""
    match = LIBXRAY_REQUIREMENT.search((CORE / "go.mod").read_text("utf-8"))
    if not match:
        fail("could not read the libXray version from native/core/go.mod")
    year, month, day = (int(part) for part in match.groups())
    return f"{year}.{month}.{day}"


def publish_maven(aar: Path, out: Path) -> Path:
    """Lays the AAR out as a file-based Maven repository Gradle resolves directly."""
    version = core_version()
    directory = out / "maven" / Path(*MAVEN_GROUP.split(".")) / MAVEN_ARTIFACT / version
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)
    stem = f"{MAVEN_ARTIFACT}-{version}"
    shutil.copyfile(aar, directory / (stem + ".aar"))
    pom = POM_TEMPLATE.format(group=MAVEN_GROUP, artifact=MAVEN_ARTIFACT, version=version)
    (directory / (stem + ".pom")).write_text(pom, encoding="utf-8")
    print(f"maven    : {MAVEN_GROUP}:{MAVEN_ARTIFACT}:{version}")
    return directory


def build(out: Path) -> Path:
    go = find_tool("go")
    gomobile = find_tool("gomobile")
    gobind = find_tool("gobind")
    ndk = find_ndk()

    env = dict(os.environ)
    env["ANDROID_NDK_HOME"] = str(ndk)
    env["PATH"] = os.pathsep.join([str(Path(gobind).parent), str(Path(go).parent), env.get("PATH", "")])
    env["CGO_ENABLED"] = "1"
    env.setdefault("GOFLAGS", "-mod=mod")
    # proxy.golang.org is unreliable from some networks (notably in Russia);
    # fall back to a mirror and finally to the origin repositories.
    env.setdefault("GOPROXY", "https://proxy.golang.org,https://goproxy.io,direct")

    print("NDK      : " + str(ndk))
    print("go       : " + go)
    print("gomobile : " + gomobile)

    run([go, "mod", "download"], env, CORE, attempts=4)
    run([go, "vet", "./..."], env, CORE)
    run([go, "test", "./..."], env, CORE)

    out.mkdir(parents=True, exist_ok=True)
    aar = out / "zales-core.aar"
    for stale in (aar, out / "zales-core-sources.jar"):
        stale.unlink(missing_ok=True)

    run(
        [
            gomobile,
            "bind",
            "-target=" + TARGETS,
            "-androidapi=" + ANDROID_API,
            "-javapkg=" + JAVA_PACKAGE,
            "-trimpath",
            "-ldflags=" + LDFLAGS,
            "-o",
            str(aar),
            ".",
        ],
        env,
        CORE,
    )
    return aar


def main() -> None:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--out", type=Path, default=ROOT / "prebuilt")
    parser.add_argument("--package-only", action="store_true", help="skip the Go build and republish an existing AAR")
    args = parser.parse_args()

    if args.package_only:
        aar = args.out / "zales-core.aar"
        if not aar.exists():
            fail(f"{aar} does not exist; build it first")
    else:
        aar = build(args.out)

    publish_maven(aar, args.out)
    digest = hashlib.sha256(aar.read_bytes()).hexdigest()
    (args.out / "zales-core.aar.sha256").write_text(digest + "  zales-core.aar" + chr(10), encoding="utf-8")
    print(f"artefact : {aar} ({aar.stat().st_size / 1_048_576:.1f} MiB)")
    print("sha256   : " + digest)


if __name__ == "__main__":
    main()
