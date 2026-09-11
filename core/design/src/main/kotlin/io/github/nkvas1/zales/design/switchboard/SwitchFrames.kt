// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design.switchboard

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.nkvas1.zales.common.ZalesLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.roundToInt

/**
 * The rendered knife switch: one path-traced frame per position of the blade.
 *
 * The frames are decoded once at the size they will actually be drawn, because
 * the source renders are far larger than any phone needs and decoding at full
 * size would cost tens of megabytes for nothing. On Android 8.1 and later they
 * are hardware bitmaps, so they live in graphics memory rather than the heap.
 */
internal class SwitchFrames private constructor(
    private val frames: List<ImageBitmap>,
    /** The closed switch with current flowing, cross-faded in when the path opens. */
    val energised: ImageBitmap?,
) {
    val closed: ImageBitmap get() = frames.first()

    /** [progress] runs 0 at the closed switch to 1 at the fully open one. */
    fun at(progress: Float): ImageBitmap {
        val index = (progress.coerceIn(0f, 1f) * (frames.size - 1)).roundToInt()
        return frames[index]
    }

    fun recycle() {
        // Hardware bitmaps are freed with their last reference; software ones
        // benefit from going early, and the screen may be left at any moment.
        (frames + listOfNotNull(energised)).forEach { image ->
            runCatching {
                val bitmap = image.asAndroidBitmap()
                if (bitmap.config != Bitmap.Config.HARDWARE) bitmap.recycle()
            }
        }
    }

    companion object {
        private const val DIRECTORY = "switch"
        private const val ENERGISED = "switch/frame_00_on.webp"

        /**
         * Decodes every frame in parallel at [targetWidth] pixels.
         *
         * Called once when the home screen appears; the whole sequence stays
         * resident so a throw never waits on a decode mid-gesture.
         */
        suspend fun load(context: Context, targetWidth: Int): SwitchFrames? = coroutineScope {
            val assets = context.assets
            val names = runCatching { assets.list(DIRECTORY).orEmpty() }.getOrDefault(emptyArray())
                .filter { it.startsWith("frame_") && it.endsWith(".webp") && !it.contains("_on") }
                .sorted()
            if (names.isEmpty()) {
                ZalesLog.error(ZalesLog.TAG_UI, "no switch frames in assets/$DIRECTORY")
                return@coroutineScope null
            }
            val decoded = names
                .map { name -> async(Dispatchers.IO) { decode(assets, "$DIRECTORY/$name", targetWidth) } }
                .awaitAll()
                .filterNotNull()
            if (decoded.size != names.size) {
                ZalesLog.warn(ZalesLog.TAG_UI, "decoded ${decoded.size} of ${names.size} switch frames")
            }
            if (decoded.isEmpty()) return@coroutineScope null
            SwitchFrames(decoded, decode(assets, ENERGISED, targetWidth))
        }

        private fun decode(assets: AssetManager, path: String, targetWidth: Int): ImageBitmap? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(assets, path)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val scale = targetWidth.toFloat() / info.size.width
                    decoder.setTargetSize(targetWidth, (info.size.height * scale).roundToInt())
                    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                    decoder.isMutableRequired = false
                }
            } else {
                assets.open(path).use { stream ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSizeFor(bounds.outWidth, targetWidth)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    assets.open(path).use { second -> BitmapFactory.decodeStream(second, null, options) }
                }
            }?.asImageBitmap()
        }.onFailure { ZalesLog.warn(ZalesLog.TAG_UI, "could not decode $path", it) }.getOrNull()

        private fun sampleSizeFor(sourceWidth: Int, targetWidth: Int): Int {
            var sample = 1
            while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
            return sample
        }
    }
}
