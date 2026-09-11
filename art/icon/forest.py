# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Generates the launcher icon: ranks of spruce against a posterised glow.

Run from the repository root:

    python art/icon/forest.py

Written as a generator rather than hand-drawn paths because a dozen trees with
two tiers each is a hundred coordinates, and a hundred hand-typed coordinates is
a hundred chances to be a pixel out. Change the ranks below and rerun; the
three vector drawables are outputs and are not edited by hand.
"""
import io
import os

RES = r"G:\CODING\zales-VPN\app\src\main\res\drawable"

HEADER = '<?xml version="1.0" encoding="utf-8"?>\n{comment}\n' \
         '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n' \
         '    android:width="108dp"\n    android:height="108dp"\n' \
         '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'


def spruce(centre: float, tip: float, half: float, foot: float = 112.0) -> str:
    """One spruce: a two-tiered silhouette, tip at the top, skirt at the foot."""
    height = foot - tip
    p = [
        (centre, tip),
        (centre + 0.40 * half, tip + 0.34 * height),
        (centre + 0.24 * half, tip + 0.37 * height),
        (centre + 0.70 * half, tip + 0.68 * height),
        (centre + 0.50 * half, tip + 0.71 * height),
        (centre + half, foot),
        (centre - half, foot),
        (centre - 0.50 * half, tip + 0.71 * height),
        (centre - 0.70 * half, tip + 0.68 * height),
        (centre - 0.24 * half, tip + 0.37 * height),
        (centre - 0.40 * half, tip + 0.34 * height),
    ]
    body = " ".join(f"L{x:.1f},{y:.1f}" for x, y in p[1:])
    return f"M{p[0][0]:.1f},{p[0][1]:.1f} {body} Z"


def rank(trees) -> str:
    return "\n                          ".join(spruce(*t) for t in trees)


def ellipse(cx, cy, rx, ry) -> str:
    return (f"M{cx - rx:.1f},{cy:.1f} a{rx:.1f},{ry:.1f} 0 1,0 {2 * rx:.1f},0 "
            f"a{rx:.1f},{ry:.1f} 0 1,0 {-2 * rx:.1f},0 Z")


# Rank furthest back: tall, thin, barely above the ground it stands on.
FAR = [(2, 30, 13), (24, 20, 14), (46, 28, 12), (68, 17, 15), (90, 27, 13), (108, 22, 13)]
# Middle rank.
MID = [(-6, 44, 17), (18, 36, 18), (44, 42, 16), (70, 34, 18), (96, 41, 17), (116, 37, 16)]
# Nearest rank: heavy, dark, and spaced so the light shows between the trunks.
NEAR = [(-2, 62, 21), (32, 54, 22), (68, 58, 21), (104, 52, 22)]

background = HEADER.format(comment="""<!--
    The ground of the forest icon: night, a fire's worth of light low in it, and
    the furthest rank of spruce standing in front of that light.

    The glow is three flat bands rather than a gradient. Zales has no gradients
    anywhere — the screen quantises everything to two tones through an ordered
    dither — and a soft radial bloom in the icon would promise an app that does
    not exist. Three steps read as light at a glance and as posterisation up
    close, which is exactly what the app looks like.

    Full bleed past 108 on every side: each launcher crops its own way, and a
    bare corner is the one mistake that cannot be fixed from outside.
-->""") + f"""
    <path
        android:fillColor="@color/icon_night"
        android:pathData="M0,0 h108 v108 h-108 z" />

    <!-- Lamplight beyond the trees, in three steps. -->
    <path
        android:fillColor="@color/icon_glow_far"
        android:pathData="{ellipse(54, 70, 40, 24)}" />
    <path
        android:fillColor="@color/icon_glow_mid"
        android:pathData="{ellipse(54, 72, 25, 15)}" />
    <path
        android:fillColor="@color/icon_glow_core"
        android:pathData="{ellipse(54, 74, 13, 8)}" />

    <!-- The farthest rank, standing in the light. -->
    <path
        android:fillColor="@color/icon_far_wood"
        android:pathData="{rank(FAR)}" />
</vector>
"""

foreground = HEADER.format(comment="""<!--
    The wood itself: two ranks of spruce, the nearer one heavier and darker, both
    reaching past the bottom edge.

    Dense on purpose — this is a thicket and not a clearing with three trees in
    it — but spaced so that the light behind still finds its way between the
    trunks. That gap is the whole picture: a way through, seen from inside the
    wood.

    Meant to be cropped at the sides. A wall of trees that stops short of the
    edge is a hedge.
-->""") + f"""
    <!-- Middle rank. -->
    <path
        android:fillColor="@color/icon_mid_wood"
        android:pathData="{rank(MID)}" />

    <!-- Nearest rank, almost the colour of the night itself. -->
    <path
        android:fillColor="@color/icon_near_wood"
        android:pathData="{rank(NEAR)}" />
</vector>
"""

monochrome = HEADER.format(comment="""<!--
    The themed layer. A single tint cannot hold three ranks apart, so the depth
    is dropped and what is left is the one shape that still says forest at any
    size: a jagged line of crowns rising from the bottom edge.
-->""") + f"""
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="{rank(MID)}" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="{rank(NEAR)}" />
</vector>
"""

for name, body in (("ic_launcher_background", background),
                   ("ic_launcher_foreground", foreground),
                   ("ic_launcher_monochrome", monochrome)):
    io.open(os.path.join(RES, name + ".xml"), "w", encoding="utf-8", newline="\n").write(body)

print("forest written")
