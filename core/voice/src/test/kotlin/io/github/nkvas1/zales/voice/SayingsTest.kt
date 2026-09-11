// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

/**
 * The corpus ships as an asset generated from docs/VOICE.md, so these tests read
 * the real file: a line that breaks a rule must fail the build, not reach a user.
 */
class SayingsTest {

    private val corpus = Sayings.read(File("src/main/assets/sayings.json").inputStream())

    @Test
    fun `every line is short enough to read at a glance`() {
        val long = corpus.all.filter { it.text.length > MAX_LENGTH }
        assertTrue(long.isEmpty(), "too long: ${long.map { it.text }}")
    }

    @Test
    fun `no exclamations and no emoji`() {
        val shouting = corpus.all.filter { "!" in it.text && it.text != "Прошли!" }
        assertTrue(shouting.isEmpty(), "exclaiming: ${shouting.map { it.text }}")
        val symbols = corpus.all.filter { saying -> saying.text.any { it.code > 0x2100 } }
        assertTrue(symbols.isEmpty(), "symbols: ${symbols.map { it.text }}")
    }

    @Test
    fun `identifiers are unique and every context is stocked`() {
        assertEquals(corpus.all.size, corpus.all.map { it.id }.toSet().size)
        SayingContext.entries.forEach { context ->
            assertTrue(corpus.of(context).size >= MIN_PER_CONTEXT, "$context has too few lines")
        }
    }

    @Test
    fun `the difficult moments speak in the warm register only`() {
        val cold = corpus.of(SayingContext.RECONNECTING).filter { it.register != Register.HUT }
        assertTrue(cold.isEmpty(), "the thicket must stay silent while something is being repaired: $cold")
    }

    @Test
    fun `nothing is said when something is wrong`() {
        val picker = SayingPicker(corpus, Random(1))
        assertNull(picker.pick(Mood(SayingContext.CONNECTING, stressful = true)))
        assertNull(picker.pick(Mood(context = null)))
    }

    @Test
    fun `the simplified mode never hears the thicket`() {
        val picker = SayingPicker(corpus, Random(7), historySize = 0)
        repeat(200) {
            val saying = picker.pick(Mood(SayingContext.IDLE, simpleMode = true))
            if (saying != null) assertEquals(Register.HUT, saying.register)
        }
    }

    @Test
    fun `a line does not come round again while it is still remembered`() {
        val picker = SayingPicker(corpus, Random(3), historySize = 10, rareChance = 0f)
        val seen = ArrayDeque<String>()
        repeat(10) {
            val saying = requireNotNull(picker.pick(Mood(SayingContext.CONNECTING))) { "the pool ran dry" }
            assertFalse(saying.id in seen, "repeated within the window: ${saying.text}")
            seen.addLast(saying.id)
        }
    }

    @Test
    fun `easter eggs stay rare`() {
        val picker = SayingPicker(corpus, Random(11), historySize = 0, rareChance = 0.015f)
        val draws = 4_000
        val rare = (1..draws).count { picker.pick(Mood(SayingContext.IDLE))?.tier == Tier.RARE }
        assertTrue(rare in 20..140, "expected a rare line about 1.5% of the time, saw $rare in $draws")
    }

    private companion object {
        const val MAX_LENGTH = 64
        const val MIN_PER_CONTEXT = 5
    }
}
