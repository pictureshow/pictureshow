/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.sync

private const val TICKS_PER_MILLISECOND = 10_000L

/** UTC milliseconds from one GetUtcTime exchange, including the server's processing interval. */
data class SyncClockSample(val t0Millis: Long, val t1Millis: Long, val t2Millis: Long, val t3Millis: Long) {
    /** Invalid timestamps/overflow are unavailable calibration, never a wrapped clock. */
    fun measurement(): SyncClockMeasurement? {
        val localElapsed = subtract(t3Millis, t0Millis)?.takeIf { it >= 0 } ?: return null
        val processing = subtract(t2Millis, t1Millis)?.takeIf { it >= 0 } ?: return null
        val rtt = subtract(localElapsed, processing)?.takeIf { it in 0..1_000 } ?: return null
        val outward = subtract(t1Millis, t0Millis) ?: return null
        val inward = subtract(t2Millis, t3Millis) ?: return null
        // Multiply by half a millisecond in ticks, retaining an odd sum's exact 0.5 ms.
        val offset = add(outward, inward)?.let { multiply(it, TICKS_PER_MILLISECOND / 2) } ?: return null
        return SyncClockMeasurement(rtt, offset, rtt * (TICKS_PER_MILLISECOND / 2))
    }
}

data class SyncClockMeasurement(
    val networkRttMillis: Long,
    val offsetTicks: Long,
    /** Half the network RTT; asymmetric delay can leave this much clock uncertainty. */
    val uncertaintyTicks: Long
)

/** A rolling window of the latest five valid samples; an invalid refresh cannot evict a valid sample. */
class SyncClockCalibration(samples: List<SyncClockSample> = emptyList()) {
    val samples: List<SyncClockSample> = samples.filter { it.measurement() != null }.takeLast(5)
    val best: SyncClockMeasurement?
        get() = samples.mapNotNull { it.measurement() }.minByOrNull { it.networkRttMillis }

    fun add(sample: SyncClockSample): SyncClockCalibration =
        if (sample.measurement() == null) this else SyncClockCalibration(samples + sample)

    fun anchor(wallMillis: Long, monotonicMillis: Long): SyncClockAnchor? {
        if (monotonicMillis < 0) return null
        val measured = best ?: return null
        val serverTicks = millisecondsToSyncTicks(wallMillis)?.let { add(it, measured.offsetTicks) } ?: return null
        return SyncClockAnchor(serverTicks, wallMillis, monotonicMillis, measured.uncertaintyTicks)
    }
}

/** An immutable mapping to a monotonic clock. Recalibrate instead of using an invalidated mapping. */
data class SyncClockAnchor(
    val serverTicksAtAnchor: Long,
    val localWallMillis: Long,
    val localMonotonicMillis: Long,
    val uncertaintyTicks: Long
) {
    fun serverNowTicks(wallMillis: Long, monotonicMillis: Long): Long? {
        val elapsed = subtract(monotonicMillis, localMonotonicMillis)?.takeIf { it >= 0 } ?: return null
        val wallElapsed = subtract(wallMillis, localWallMillis) ?: return null
        val clockJump = subtract(wallElapsed, elapsed) ?: return null
        if (clockJump < -100 || clockJump > 100) return null
        return millisecondsToSyncTicks(elapsed)?.let { add(serverTicksAtAnchor, it) }
    }

    fun needsRefresh(monotonicMillis: Long): Boolean =
        subtract(monotonicMillis, localMonotonicMillis)?.let { it < 0 || it >= 30_000 } ?: true
}

fun millisecondsToSyncTicks(milliseconds: Long): Long? = multiply(milliseconds, TICKS_PER_MILLISECOND)

data class SyncTarget(val positionTicks: Long, val delayMillis: Long)

/**
 * Future commands wait (rounding up to the next local millisecond), then the caller recalculates
 * on execution. Only playing commands advance after When. A bounded addition cannot wrap int64.
 */
fun syncTarget(positionTicks: Long, whenUtcMillis: Long, serverNowTicks: Long, playing: Boolean, durationTicks: Long): SyncTarget? {
    if (durationTicks < 0) return null
    val whenTicks = millisecondsToSyncTicks(whenUtcMillis) ?: return null
    val base = positionTicks.coerceIn(0, durationTicks)
    if (whenTicks > serverNowTicks) {
        val waitTicks = subtract(whenTicks, serverNowTicks) ?: return null
        val waitMillis = waitTicks / TICKS_PER_MILLISECOND + if (waitTicks % TICKS_PER_MILLISECOND == 0L) 0 else 1
        return SyncTarget(base, waitMillis)
    }
    if (!playing) return SyncTarget(base, 0)
    val elapsed = subtract(serverNowTicks, whenTicks) ?: return null
    return SyncTarget(if (elapsed >= durationTicks - base) durationTicks else base + elapsed, 0)
}

/** Skip a decoder seek only for an Unpause already within the playing tolerance at execution. */
fun syncScheduledPosition(command: String, currentTicks: Long, targetTicks: Long): Long? =
    if (command == "Unpause" && currentTicks >= 0 && targetTicks >= 0 && currentTicks - targetTicks in -800_000L..800_000L) {
        null
    } else {
        targetTicks
    }

data class SyncDriftState(val lastSeekMonotonicMillis: Long? = null, val rateStartedMonotonicMillis: Long? = null, val rate: Double = 1.0)

enum class SyncDriftAction { NONE, RATE, SEEK, WAIT, RESET_RATE }

/** Every decision carries the complete desired rate, including restoration to 1x on seek/wait. */
data class SyncDriftDecision(val state: SyncDriftState, val action: SyncDriftAction, val targetTicks: Long, val rate: Double = state.rate)

/**
 * Positive drift means this renderer is ahead. Rate support means the exact requested speed,
 * rather than a nearby advertised rate. At five seconds restore 1x and recheck this sample:
 * residual drift over 80 ms becomes a bounded seek, subject to the two-second seek cooldown.
 * Explicit transport commands are scheduled separately; this reducer never sends group requests.
 */
fun syncDrift(
    state: SyncDriftState,
    currentTicks: Long,
    targetTicks: Long,
    playing: Boolean,
    nowMonotonicMillis: Long,
    durationTicks: Long,
    sourceAllowsRate: Boolean,
    engineRates: Set<Double>
): SyncDriftDecision {
    val reset = state.copy(rateStartedMonotonicMillis = null, rate = 1.0)
    if (durationTicks < 0 || currentTicks < 0 || targetTicks < 0 || nowMonotonicMillis < 0) {
        return SyncDriftDecision(reset, SyncDriftAction.WAIT, 0)
    }
    val target = targetTicks.coerceAtMost(durationTicks)
    val current = currentTicks.coerceAtMost(durationTicks)
    val drift = current - target // Both values are non-negative; subtraction is representable.
    val small = drift in -800_000L..800_000L
    val wasChangingRate = state.rate != 1.0 || state.rateStartedMonotonicMillis != null
    val lastSeekElapsed = state.lastSeekMonotonicMillis?.let { subtract(nowMonotonicMillis, it) }
    val rateElapsed = state.rateStartedMonotonicMillis?.let { subtract(nowMonotonicMillis, it) }
    if ((state.lastSeekMonotonicMillis != null && (lastSeekElapsed == null || lastSeekElapsed < 0)) ||
        (state.rateStartedMonotonicMillis != null && (rateElapsed == null || rateElapsed < 0))
    ) {
        return SyncDriftDecision(SyncDriftState(), SyncDriftAction.WAIT, target)
    }
    if ((playing && small) || (!playing && drift == 0L)) {
        return SyncDriftDecision(reset, if (wasChangingRate) SyncDriftAction.RESET_RATE else SyncDriftAction.NONE, target)
    }
    val requestedRate = if (drift > 0) 0.97 else 1.03
    val rateEligible = playing && drift in -2_500_000L..2_500_000L && sourceAllowsRate && requestedRate in engineRates
    if (rateEligible && (rateElapsed == null || rateElapsed < 5_000)) {
        val next = state.copy(rateStartedMonotonicMillis = state.rateStartedMonotonicMillis ?: nowMonotonicMillis, rate = requestedRate)
        return SyncDriftDecision(next, SyncDriftAction.RATE, target)
    }
    if (lastSeekElapsed != null && lastSeekElapsed < 2_000) {
        return SyncDriftDecision(reset, SyncDriftAction.WAIT, target)
    }
    return SyncDriftDecision(reset.copy(lastSeekMonotonicMillis = nowMonotonicMillis), SyncDriftAction.SEEK, target)
}

/**
 * A scheduled transport action also seeks the renderer. Start a fresh correction cooldown
 * after that seek, including a Ready-triggered Unpause for the same entry. An aligned command
 * which does not actually seek preserves the previous deadline. Explicit future
 * group commands remain schedulable; only autonomous drift correction waits. Restore 1x.
 */
fun syncTransportApplied(state: SyncDriftState, nowMonotonicMillis: Long, positionChanged: Boolean): SyncDriftState = state.copy(
    lastSeekMonotonicMillis = if (positionChanged) nowMonotonicMillis else state.lastSeekMonotonicMillis,
    rateStartedMonotonicMillis = null,
    rate = 1.0
)

/** Stop/Leave discards the old viewing's correction deadlines and restores normal speed. */
fun stopSyncDrift(): SyncDriftState = SyncDriftState()

private fun add(a: Long, b: Long): Long? = if ((b > 0 && a > Long.MAX_VALUE - b) || (b < 0 && a < Long.MIN_VALUE - b)) null else a + b

private fun subtract(a: Long, b: Long): Long? = if ((b > 0 && a < Long.MIN_VALUE + b) || (b < 0 && a > Long.MAX_VALUE + b)) null else a - b

private fun multiply(value: Long, positiveFactor: Long): Long? =
    if (value > Long.MAX_VALUE / positiveFactor || value < Long.MIN_VALUE / positiveFactor) null else value * positiveFactor

/** A common group timeline requires finite, seekable server VOD; local copies have no shared server session. */
fun syncSourceEligible(durationTicks: Long, seekable: Boolean, live: Boolean, infinite: Boolean, offline: Boolean): Boolean =
    durationTicks > 0 && seekable && !live && !infinite && !offline
