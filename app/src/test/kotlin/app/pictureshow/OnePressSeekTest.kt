/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.OnePressSeek.Path.Key
import app.pictureshow.OnePressSeek.Path.Reader
import kotlin.test.Test
import kotlin.test.assertEquals

class OnePressSeekTest {
    private var nowMs = 1_000L
    private val gate = OnePressSeek(windowMs = 250L) { nowMs }

    private fun run(vararg steps: Pair<OnePressSeek.Path, Long>, gapMs: Long = 0): List<Boolean> = steps.map { (path, delta) ->
        gate.admit(path, delta).also { nowMs += gapMs }
    }

    @Test fun aPressThatArrivesOnBothPathsSeeksOnceInEitherOrder() {
        assertEquals(listOf(true, false), run(Key to 10_000L, Reader to 10_000L))
        nowMs += 1_000
        assertEquals(listOf(true, false), run(Reader to -10_000L, Key to -10_000L))
    }

    @Test fun eachPressOnOnePathIsItsOwnSeekHoweverFast() {
        assertEquals(List(4) { true }, run(*Array(4) { Reader to 10_000L }, gapMs = 50))
        nowMs += 1_000
        assertEquals(List(4) { true }, run(*Array(4) { Key to 10_000L }, gapMs = 50))
    }

    @Test fun pressesThatEachArriveOnBothPathsPairOneToOne() {
        // A forwarding reader at key-repeat pace: each key pairs with its own action, never with the next press.
        val steps = (1..3).flatMap { listOf(Key to 10_000L, Reader to 10_000L) }.toTypedArray()
        assertEquals(listOf(true, false, true, false, true, false), run(*steps, gapMs = 30))
    }

    @Test fun theOtherDirectionOrAnExpiredClaimIsNeverAbsorbed() {
        assertEquals(listOf(true, true), run(Key to 10_000L, Reader to -10_000L))
        nowMs += 251
        assertEquals(true, gate.admit(Key, -10_000L))
        nowMs += 251
        assertEquals(true, gate.admit(Reader, -10_000L))
    }
}
