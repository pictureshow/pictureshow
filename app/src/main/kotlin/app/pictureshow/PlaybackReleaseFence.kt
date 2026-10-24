/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.os.HandlerCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.pictureshow.core.playback.PlayDecision
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Application-thread ownership, with volatile timestamps published by the retiring playback thread. */
internal object PlaybackReleaseFence {
    // Below the viewing's 10s buffering recovery deadline. No playback/application thread waits
    // for this bound: only prepare is deferred. A stuck vendor release is explicitly diagnosed.
    private const val RELEASE_BOUND_MS = 5_000L
    private var owner: WeakReference<Context>? = null
    private var state: State? = null

    fun playback(context: Context): Playback {
        check(Looper.myLooper() == Looper.getMainLooper())
        val application = context.applicationContext
        if (owner?.get() !== application) {
            // Android has one Application per process. Test hosts replace its loopers too;
            // never synchronously drain work from a previous lifecycle on the new one.
            owner = WeakReference(application)
            state = State()
        }
        val scope = requireNotNull(state)
        scope.outstanding.removeAll { !it.pending() }
        return Playback(scope, scope.previous, scope.outstanding.toList())
    }

    internal class State {
        val handler = Handler(Looper.getMainLooper())
        val releaseHandler = HandlerCompat.createAsync(Looper.getMainLooper())
        val outstanding = mutableListOf<Retirement>()
        var previous: Retirement? = null
    }

    class Playback internal constructor(
        private val scope: State,
        private val predecessor: Retirement?,
        private val dependencies: List<Retirement>
    ) {
        private val thread = HandlerThread("Pictureshow:Playback", Process.THREAD_PRIORITY_AUDIO).apply { start() }
        val looper: Looper = thread.looper
        private var waitMs = 0L
        private var boundReached = false

        fun abortConstruction() {
            thread.quit()
        }

        fun prepare(whenReady: () -> Unit) {
            val start = SystemClock.elapsedRealtime()
            fun checkReady() {
                if (dependencies.any { it.pending() }) return
                waitMs = SystemClock.elapsedRealtime() - start
                boundReached = dependencies.any { it.finishedMs == null }
                whenReady()
            }
            if (dependencies.none { it.pending() }) {
                checkReady()
            } else {
                var delivered = false
                val check = {
                    if (!delivered && dependencies.none { it.pending() }) {
                        delivered = true
                        checkReady()
                    }
                }
                dependencies.filter { it.pending() }.forEach { it.whenFinished(check) }
                check()
            }
        }

        fun videoInitFields(initializedTimestampMs: Long, initializationDurationMs: Long): String {
            val finished = predecessor?.finishedMs
            val started = initializedTimestampMs - initializationDurationMs
            return "initializedMs=$initializedTimestampMs releaseWaitMs=$waitMs releaseBoundReached=$boundReached " +
                "previousReleaseGapMs=${finished?.let { started - it } ?: if (predecessor == null) "none" else "pending"}"
        }

        fun release(player: ExoPlayer, decision: PlayDecision) {
            check(Looper.myLooper() == Looper.getMainLooper())
            val retirement = Retirement(scope, decision)
            scope.previous = retirement
            scope.outstanding += retirement
            // ListenerSet drops nested events when release happens during its own flush. Register
            // the fence now, then release in a fresh app-loop turn to observe the timeout exactly.
            // Any replacement can be built, but cannot prepare until this retirement completes.
            val action = Runnable {
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) retirement.timeout = true
                    }
                })
                val callStart = SystemClock.elapsedRealtime()
                try {
                    player.release()
                } finally {
                    retirement.returnedMs = SystemClock.elapsedRealtime()
                    retirement.blockedMs = requireNotNull(retirement.returnedMs) - callStart
                    // A separate Handler survives Media3 clearing its own handler's callbacks.
                    Handler(looper).post {
                        retirement.finished()
                        thread.quit()
                    }
                    retirement.scheduleBound()
                }
            }
            // Retire before a pending UI frame can detach the live old player's surface.
            scope.releaseHandler.postAtFrontOfQueue(action)
        }
    }

    internal class Retirement(private val scope: State, private val decision: PlayDecision) {
        val startedMs = SystemClock.elapsedRealtime()
        var blockedMs: Long? = null
        var returnedMs: Long? = null
        var timeout = false

        @Volatile var finishedMs: Long? = null
            private set
        private val reported = AtomicBoolean(false)
        private val callbacks = mutableListOf<() -> Unit>()
        private val bound = Runnable {
            report(bound = true)
            notifyWaiters()
        }

        fun pending(): Boolean {
            val age = SystemClock.elapsedRealtime() - startedMs
            return finishedMs == null && age in 0 until RELEASE_BOUND_MS
        }

        fun whenFinished(callback: () -> Unit) {
            callbacks += callback
            scheduleBound()
        }

        fun scheduleBound() {
            scope.handler.removeCallbacks(bound)
            scope.handler.postDelayed(bound, (RELEASE_BOUND_MS - (SystemClock.elapsedRealtime() - startedMs)).coerceAtLeast(0))
        }

        fun finished() {
            finishedMs = SystemClock.elapsedRealtime()
            scope.handler.post {
                scope.handler.removeCallbacks(bound)
                if (!report(bound = false)) {
                    PlaybackDiagnostics.write(
                        decision,
                        "release-finished",
                        "finishedMs=$finishedMs totalMs=${finishedMs!! - startedMs} blockedMs=$blockedMs timeout=$timeout"
                    )
                }
                notifyWaiters()
            }
        }

        private fun notifyWaiters() {
            scope.outstanding.remove(this)
            val waiting = callbacks.toList()
            callbacks.clear()
            waiting.forEach { it() }
        }

        private fun report(bound: Boolean): Boolean {
            if (!reported.compareAndSet(false, true)) return false
            val finished = finishedMs
            PlaybackDiagnostics.write(
                decision,
                "release",
                "blockedMs=${blockedMs ?: "pending"} timeout=${if (returnedMs == null) "pending" else timeout} " +
                    "startedMs=$startedMs returnedMs=${returnedMs ?: "pending"} " +
                    "finishedMs=${finished ?: "pending"} totalMs=${finished?.minus(startedMs) ?: "pending"} " +
                    "afterReturnMs=${finished?.let { end -> returnedMs?.let { end - it } } ?: "pending"} " +
                    "boundMs=$RELEASE_BOUND_MS boundReached=${bound && finished == null}"
            )
            return true
        }
    }
}
