package ch.fhnw.pepper_realtime.controller

import android.util.Log
import ch.fhnw.pepper_realtime.R
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.AnimateBuilder
import com.aldebaran.qi.sdk.builder.AnimationBuilder
import com.aldebaran.qi.sdk.`object`.actuation.Animate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class GestureController internal constructor(
    private val newScheduler: () -> ScheduledExecutorService,
    private val buildAction: (QiContext, Int) -> Future<Animate>
) {
    constructor() : this({ Executors.newSingleThreadScheduledExecutor() }, { context, id ->
        AnimationBuilder.with(context).withResources(id).buildAsync().andThenCompose { animation ->
            AnimateBuilder.with(context).withAnimation(animation).buildAsync()
        }
    })

    fun interface BoolSupplier { fun get(): Boolean }
    fun interface IntSupplier { fun get(): Int? }

    private class Loop(val context: QiContext, val keepRunning: BoolSupplier, val nextId: IntSupplier) {
        var pending: Future<*>? = null
        var timer: ScheduledFuture<*>? = null
        var delayMs = 350L
    }

    private var scheduler: ScheduledExecutorService? = null
    private var active: Loop? = null
    private var cacheContext: QiContext? = null
    private val actions = mutableMapOf<Int, Animate>()

    @Synchronized
    fun start(robotContext: Any?, keepRunning: BoolSupplier, nextResId: IntSupplier) {
        val context = robotContext as? QiContext ?: return
        if (!keepRunning.get()) return
        if (cacheContext !== context) {
            onRobotFocusLost()
            cacheContext = context
        }
        if (active != null) return
        if (scheduler == null) scheduler = newScheduler()
        val loop = Loop(context, keepRunning, nextResId)
        active = loop
        schedule(loop, 0)
    }

    private fun schedule(loop: Loop, delayMs: Long) {
        loop.timer = scheduler!!.schedule(Runnable { next(loop) }, delayMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun next(loop: Loop) {
        if (active !== loop) return
        loop.timer = null
        try {
            val id = if (loop.keepRunning.get()) loop.nextId.get() else null
            if (id == null) {
                active = null
                return
            }
            val cached = actions[id]
            if (cached != null) {
                play(loop, cached)
            } else {
                observe(loop, buildAction(loop.context, id)) { built ->
                    val action = checkNotNull(built.value)
                    actions[id] = action
                    play(loop, action)
                }
            }
        } catch (e: Exception) {
            retry(loop, e)
        }
    }

    private fun play(loop: Loop, action: Animate) {
        if (!loop.keepRunning.get()) {
            active = null
            return
        }
        observe(loop, action.async().run()) {
            loop.delayMs = 350L
            schedule(loop, loop.delayMs)
        }
    }

    private fun <T> observe(loop: Loop, future: Future<T>, onSuccess: (Future<T>) -> Unit) {
        loop.pending = future
        future.thenConsume { done ->
            synchronized(this) {
                // Old SDK completions must not start motion or repopulate a new focus's cache.
                if (active === loop && loop.pending === future) {
                    loop.pending = null
                    try {
                        if (done.isSuccess) onSuccess(done)
                        else retry(loop, if (done.hasError()) done.error else null)
                    } catch (e: Exception) {
                        retry(loop, e)
                    }
                }
            }
        }
    }

    private fun retry(loop: Loop, error: Throwable?) {
        if (active !== loop) return
        if (loop.delayMs < 2000L) Log.w("GestureController", "Gesture unavailable; retrying", error)
        schedule(loop, loop.delayMs)
        loop.delayMs = minOf(loop.delayMs * 2, 2000L)
    }

    @Synchronized
    fun stopNow() {
        val loop = active ?: return
        active = null
        loop.timer?.cancel(false)
        try {
            loop.pending?.requestCancellation()
        } catch (e: Exception) {
            Log.w("GestureController", "Could not cancel gesture", e)
        }
    }

    @Synchronized
    fun onRobotFocusLost() {
        stopNow()
        actions.clear()
        cacheContext = null
    }

    fun pause() = stopNow()

    fun resume() = Unit // The next speaking turn starts its own loop.

    @Synchronized
    fun shutdown() {
        onRobotFocusLost()
        scheduler?.shutdownNow()
        scheduler = null
    }

    fun getRandomExplainAnimationResId(): Int {
        val ids = intArrayOf(
            R.raw.explain_01,
            R.raw.explain_02,
            R.raw.explain_03,
            R.raw.explain_04,
            R.raw.explain_05,
            R.raw.explain_06,
            R.raw.explain_07,
            R.raw.explain_08,
            R.raw.explain_09,
            R.raw.explain_10,
            R.raw.explain_11
        )
        return ids[Random.nextInt(ids.size)]
    }
}

