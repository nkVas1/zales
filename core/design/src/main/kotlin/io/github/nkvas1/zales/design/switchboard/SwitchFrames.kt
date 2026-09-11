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
 * Held at two resolutions on purpose, because the two jobs are different ones.
 *
 * The handle is at rest almost all of the time, and that resting frame is the
 * hero image of the whole app: it wants every pixel it can get. The frames in
 * between exist for a third of a second while a hand is moving, and at that
 * speed nobody has ever seen a detail. Keeping the whole sequence at hero
 * resolution would cost well over a hundred megabytes of graphics memory to
 * make motion sharper than motion needs to be.
 *
 * So: a short run at working size for the throw, and the two resting positions
 * at full size for all the rest of the time.
 */
internal class SwitchFrames private constructor(
    private val motion: List<ImageBitmap>,
    private val restingClosed: ImageBitmap,
    private val restingOpen: ImageBitmap,
    /** The closed switch with current flowing, cross-faded in when the path opens. */
    val energised: ImageBitmap?,
) {
    val closed: ImageBitmap get() = restingClosed

    /** [progress] runs 0 at the closed switch to 1 at the fully open one. */
    fun at(progress: Float): ImageBitmap {
        val travel = progress.coerceIn(0f, 1f)
        if (travel <= SETTLED) return restingClosed
        if (travel >= 1f - SETTLED) return restingOpen
        val index = (travel * (motion.size - 1)).roundToInt()
        return motion[index]
    }

    fun recycle() {
        // Hardware bitmaps are freed with their last reference; software ones
        // benefit from going early, and the screen may be left at any moment.
        (motion + listOfNotNull(restingClosed, restingOpen, energised)).forEach { image ->
            runCatching {
                val bitmap = image.asAndroidBitmap()
                if (bitmap.config != Bitmap.Config.HARDWARE) bitmap.recycle()
            }
        }
    }

    companion object {
        private const val DIRECTORY = "switch"
        private const val ENERGISED = "switch/frame_00_on.webp"

        /** Within this much of either end, the handle counts as at rest. */
        private const val SETTLED = 0.02f

        /**
         * Enough for a throw lasting about a third of a second. More frames buy
         * nothing the eye can use and cost graphics memory in proportion.
         */
        private const val MOTION_FRAMES = 16

        /** Half the drawn size: these are only ever seen moving. */
        private const val MOTION_MAX_PX = 440
        private const val MOTION_MIN_PX = 200

        /** The resting frames are the hero image and get the pixels. */
        private const val RESTING_MAX_PX = 820

        /**
         * Decodes what is needed, in parallel, at the two sizes it is needed at.
         *
         * Called once when the home screen appears; everything stays resident so
         * a throw never waits on a decode mid-gesture.
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

            val restingWidth = targetWidth.coerceAtMost(RESTING_MAX_PX)
            val motionWidth = (targetWidth / 2).coerceIn(MOTION_MIN_PX, MOTION_MAX_PX)
            val wanted = spread(names, MOTION_FRAMES)

            val motion = wanted
                .map { name -> async(Dispatchers.IO) { decode(assets, "$DIRECTORY/$name", motionWidth) } }
                .awaitAll()
                .filterNotNull()
            if (motion.size != wanted.size) {
                ZalesLog.warn(ZalesLog.TAG_UI, "decoded ${motion.size} of ${wanted.size} switch frames")
            }
            if (motion.isEmpty()) return@coroutineScope null

            val closed = async(Dispatchers.IO) { decode(assets, "$DIRECTORY/${names.first()}", restingWidth) }
            val open = async(Dispatchers.IO) { decode(assets, "$DIRECTORY/${names.last()}", restingWidth) }
            val lit = async(Dispatchers.IO) { decode(assets, ENERGISED, restingWidth) }

            SwitchFrames(
                motion = motion,
                restingClosed = closed.await() ?: motion.first(),
                restingOpen = open.await() ?: motion.last(),
                energised = lit.await(),
            )
        }

        /** Picks [count] frames spread evenly across the travel, both ends included. */
        private fun spread(names: List<String>, count: Int): List<String> {
            if (names.size <= count) return names
            return (0 until count).map { step ->
                names[(step * (names.size - 1) / (count - 1.0)).roundToInt()]
            }
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
