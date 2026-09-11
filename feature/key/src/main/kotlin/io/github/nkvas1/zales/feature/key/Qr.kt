// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.key

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A square of black and white modules: `true` is a dark module.
 *
 * Kept as a plain grid rather than a bitmap so the drawing side can render it
 * in the app's own hand — hard squares, no anti-aliasing, no rounded corners —
 * instead of scaling someone else's image.
 */
public class QrGrid(public val size: Int, private val dark: BooleanArray) {
    public fun isDark(x: Int, y: Int): Boolean = dark[y * size + x]
}

/**
 * Making and reading the codes that carry a key between two phones.
 *
 * Three ways in, because a key arrives in three ways: pointed at another phone's
 * screen, found in a screenshot someone was sent, or typed — and only the last
 * of those is any good for the person this app is built for.
 */
public object Qr {

    /**
     * Encodes a key link.
     *
     * Correction level Q, not the usual M: this code gets photographed off a
     * glowing screen at an angle, by an unsteady hand, and a quarter of it may
     * be glare.
     */
    public fun encode(text: String): QrGrid? = runCatching {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            NOMINAL,
            NOMINAL,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 0,
            ),
        )
        val size = matrix.width
        val dark = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                dark[y * size + x] = matrix.get(x, y)
            }
        }
        QrGrid(size, dark)
    }.getOrNull()

    /** Reads a code out of a picture — a screenshot, most often. */
    public fun readImage(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return read(RGBLuminanceSource(width, height, pixels))
    }

    /** Reads a code out of one camera frame, given its luminance plane. */
    public fun readFrame(luminance: ByteArray, width: Int, height: Int): String? = read(
        PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false),
    )

    private fun read(source: com.google.zxing.LuminanceSource): String? = runCatching {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    // Worth the extra work: these are photographs of screens,
                    // not printed labels under a scanner.
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()

    /** ZXing needs a size to lay out against; the grid it returns is its own. */
    private const val NOMINAL = 512
}
