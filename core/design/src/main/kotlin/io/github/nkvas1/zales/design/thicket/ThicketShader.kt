// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design.thicket

/**
 * The thicket, in AGSL.
 *
 * Three belts of spruce silhouettes at different depths, a lamp that reaches
 * only so far, and an ordered Bayer threshold that quantises the whole frame to
 * exactly two tones — cold ink and warm light, with nothing in between. That
 * hard split is the art direction itself, not a filter laid over it
 * (docs/DESIGN.md §1 and §5).
 *
 * Cost is deliberately low: no texture fetches and a few dozen arithmetic
 * instructions per pixel, so it holds the display's frame rate on the Mali-G52
 * of a budget phone.
 */
internal const val THICKET_AGSL: String = """
uniform float2 uResolution;
uniform float  uTime;      // seconds, for the forest's slow, uneven breathing
uniform float  uOpen;      // 0 closed thicket, 1 open clearing
uniform float2 uTilt;      // parallax from the gyroscope, in pixels
uniform float  uFlicker;   // lamp brightness, 0.97..1.0
uniform float  uPulse;     // live traffic ripple along the path
uniform float  uGaze;      // 0..1, the rare pair of points in the depths
uniform float  uPixel;     // dither cell size in device pixels

layout(color) uniform half4 uVoid;   // darkness the lamp never reaches
layout(color) uniform half4 uCold;   // the forest's own tone
layout(color) uniform half4 uWarm;   // lamplight

float hash1(float n) {
    return fract(sin(n) * 43758.5453123);
}

// The classic recursive construction of the 8x8 ordered Bayer matrix: cheaper
// than a lookup and free of texture sampling.
float bayer2(float2 a) {
    float2 f = floor(a);
    return fract(f.x / 2.0 + f.y * f.y * 0.75);
}

float bayer4(float2 a) {
    return bayer2(0.5 * a) * 0.25 + bayer2(a);
}

float bayer8(float2 a) {
    return bayer4(0.5 * a) * 0.25 + bayer2(a);
}

// One belt of spruces. `tall` scales the crowns, `sway` lets them lean.
float belt(float2 uv, float scale, float seed, float tall, float sway) {
    float x = uv.x * scale + seed;
    float column = floor(x);
    float f = fract(x);
    float jitter = hash1(column * 1.37 + seed);
    f = fract(f + sway * (jitter - 0.5));
    float crown = mix(0.42, 1.0, jitter) * tall;
    float toTrunk = abs(f - 0.5) * 2.0;
    // A spruce narrows towards its top, so the silhouette is a soft triangle.
    float profile = crown * (1.0 - toTrunk * 0.78);
    return smoothstep(profile + 0.012, profile - 0.012, uv.y);
}

half4 main(float2 fragCoord) {
    // Quantise to dither cells first: every pixel in a cell shares one value,
    // which is what gives the image its printed, chunky grain.
    float2 cell = floor(fragCoord / uPixel);
    float2 uv = (cell * uPixel) / uResolution;
    // Screen space runs downwards; the forest grows upwards.
    float2 forest = float2(uv.x, 1.0 - uv.y);

    // Uneven breathing: two incommensurate periods, so it never repeats cleanly.
    float breath = 0.5 * sin(uTime * 0.21) + 0.5 * sin(uTime * 0.13 + 1.7);

    float2 far = forest + float2(uTilt.x * 0.20, 0.0) / uResolution;
    float2 mid = forest + float2(uTilt.x * 0.55, 0.0) / uResolution;
    float2 near = forest + float2(uTilt.x, 0.0) / uResolution;

    float f0 = belt(far, 26.0, 3.1, 0.30, 0.010 * breath);
    float f1 = belt(mid, 15.0, 9.7, 0.46, 0.018 * breath);
    float f2 = belt(near, 8.0, 17.3, 0.72, 0.026 * breath);

    // Depth: distant belts read thinner and paler, near ones solid.
    float density = max(max(f0 * 0.34, f1 * 0.62), f2);

    // Ground mist thickens towards the bottom of the frame.
    density = max(density, smoothstep(0.28, 0.0, forest.y) * 0.55);

    // The clearing: a corridor opens down the middle as the path opens, its
    // edges rippling only while traffic is actually moving.
    float halfWidth = uOpen * (0.30 + 0.02 * sin(uTime * 1.7) * uPulse);
    float edge = abs(uv.x - 0.5);
    float corridor = smoothstep(halfWidth, halfWidth + 0.10, edge);
    density *= corridor;

    // The lamp, upper left, falling off hard. Beyond it there is nothing to see.
    float reach = distance(uv, float2(0.26, 0.30)) / 0.46;
    float lamp = exp(-reach * reach * 2.2) * uFlicker;

    // The gaze: two faint points far back, never moving, never explained.
    float gaze = 0.0;
    if (uGaze > 0.0) {
        float d = min(distance(uv, float2(0.63, 0.34)), distance(uv, float2(0.665, 0.345)));
        gaze = uGaze * exp(-d * d * 9000.0);
    }

    float value = density * 0.85 + lamp * 0.95 + gaze * 0.5;
    float threshold = bayer8(cell) * 0.92 + 0.04;

    if (value <= threshold) {
        return uVoid;
    }
    // Two tones, chosen by what lit the pixel: the hut is warm, the forest cold.
    return (lamp * 1.4 > density && gaze <= lamp) ? uWarm : uCold;
}
"""
