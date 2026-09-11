// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import kotlin.random.Random

/** Where a saying is allowed to appear. */
public enum class SayingContext {
    IDLE,
    KEY_INPUT,
    CONNECTING,
    CONNECTING_LONG,
    CONNECTED_FRESH,
    CONNECTED_LONG,
    RECONNECTING,
    FIRST_RUN,
    VETERAN,
    NIGHT,
    MORNING,
    RARE,
}

/**
 * The two voices of the app (docs/VOICE.md §1.1).
 *
 * [THICKET] is the cold one that speaks of the forest as of someone.
 * [HUT] is the warm one that turns from the window and talks to you.
 */
public enum class Register { THICKET, HUT }

public enum class Tier { COMMON, RARE }

public data class Saying(
    val id: String,
    val text: String,
    val context: SayingContext,
    val register: Register,
    val tier: Tier,
)

/** The corpus, generated from docs/VOICE.md by tools/build_corpus.py. */
public class Sayings(public val all: List<Saying>) {

    public fun of(context: SayingContext): List<Saying> = all.filter { it.context == context }

    public companion object {
        public const val ASSET: String = "sayings.json"

        public fun read(stream: InputStream): Sayings {
            val document = Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
            val sayings = document.getValue("sayings").jsonArray.map { element ->
                val item = element.jsonObject
                Saying(
                    id = item.getValue("id").jsonPrimitive.content,
                    text = item.getValue("text").jsonPrimitive.content,
                    context = SayingContext.valueOf(item.getValue("context").jsonPrimitive.content),
                    register = Register.valueOf(item.getValue("register").jsonPrimitive.content),
                    tier = Tier.valueOf(item.getValue("tier").jsonPrimitive.content),
                )
            }
            return Sayings(sayings)
        }
    }
}

/**
 * Chooses what the app says while a person waits.
 *
 * The first rule is the one that matters: **nothing is ever said in a difficult
 * moment.** A joke while something is broken reads as mockery, so the picker
 * refuses before it does anything else. The cold register is withheld in the
 * simplified mode as well — an elderly person alone at night does not need a
 * hint that something is watching (docs/VOICE.md §4.1).
 */
public class SayingPicker(
    private val sayings: Sayings,
    private val random: Random = Random.Default,
    private val historySize: Int = DEFAULT_HISTORY,
    private val rareChance: Float = RARE_CHANCE,
) {
    private val history = ArrayDeque<String>()

    public fun pick(mood: Mood): Saying? {
        if (mood.stressful || mood.context == null) return null

        val rare = sayings.of(SayingContext.RARE).eligible(mood)
        if (rare.isNotEmpty() && random.nextFloat() < rareChance) {
            return rare.random(random).also(::remember)
        }
        val pool = sayings.of(mood.context).eligible(mood)
        if (pool.isEmpty()) return null
        return pool.random(random).also(::remember)
    }

    private fun List<Saying>.eligible(mood: Mood): List<Saying> = filter { saying ->
        saying.id !in history && (!mood.simpleMode || saying.register == Register.HUT)
    }

    private fun remember(saying: Saying) {
        history.addLast(saying.id)
        while (history.size > historySize) history.removeFirst()
    }

    public companion object {
        public const val DEFAULT_HISTORY: Int = 25
        public const val RARE_CHANCE: Float = 0.015f
    }
}

/**
 * What the app is doing and how the person is feeling about it.
 *
 * [stressful] is set whenever something has failed or is being diagnosed. It is
 * checked before anything else, so no future context can accidentally open the
 * door to a joke at the wrong moment.
 */
public data class Mood(
    val context: SayingContext?,
    val stressful: Boolean = false,
    val simpleMode: Boolean = false,
)
