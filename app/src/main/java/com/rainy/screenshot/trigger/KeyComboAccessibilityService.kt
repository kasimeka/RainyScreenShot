package com.rainy.screenshot.trigger

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.rainy.screenshot.capture.Screenshotter
import com.rainy.screenshot.feedback.ScreenshotNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Volume up + volume down runs an adb screenshot without the volume slider in it.
 * All volume-key events are consumed here and single presses are replayed through
 * AudioManager, so each volume press is delayed by [COMBO_WINDOW_MS].
 */
class KeyComboAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyComboA11y"

        /** How long a first press waits for the other volume key before it counts as a normal press. */
        const val COMBO_WINDOW_MS = 150L

        /** A key still marked as held after this long is treated as released when another key arrives. */
        const val STALE_KEY_MS = 1000L

        /** Caps simulated auto-repeat, so a lost key-up cannot keep changing the volume. */
        const val MAX_REPEAT_MS = 15_000L

        /** Same flags the hardware keys use: slider, sound, vibration and ringer modes behave normally. */
        private const val ADJUST_FLAGS = AudioManager.FLAG_SHOW_UI or
            AudioManager.FLAG_PLAY_SOUND or
            AudioManager.FLAG_VIBRATE or
            AudioManager.FLAG_ALLOW_RINGER_MODES
    }

    private enum class Mode { IDLE, PENDING, PASSTHROUGH, COMBO }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private var mode = Mode.IDLE
    private var pendingKey = 0

    private val heldKeys = mutableMapOf<Int, Long>()

    private var repeatKey = 0

    private val resolvePending = Runnable {
        if (mode == Mode.PENDING) {
            mode = Mode.PASSTHROUGH
            adjustVolume(pendingKey)
            startRepeat(pendingKey, ViewConfiguration.getKeyRepeatTimeout().toLong() - COMBO_WINDOW_MS)
        }
    }

    private val repeatStep = object : Runnable {
        override fun run() {
            val downAt = heldKeys[repeatKey]
            val expired = downAt == null || SystemClock.uptimeMillis() - downAt > MAX_REPEAT_MS
            if (mode != Mode.PASSTHROUGH || expired) {
                if (downAt != null && expired) heldKeys -= repeatKey
                repeatKey = 0
                if (heldKeys.isEmpty()) mode = Mode.IDLE
                return
            }
            adjustVolume(repeatKey)
            handler.postDelayed(this, ViewConfiguration.getKeyRepeatDelay().toLong())
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = reset()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = reset()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        when (event.action) {

            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) onKeyDown(key, event.downTime)
            KeyEvent.ACTION_UP -> onKeyUp(key)
        }
        return true
    }

    private fun onKeyDown(key: Int, downTime: Long) {

        if (key in heldKeys) reset()
        dropStaleKeys(downTime)
        heldKeys[key] = downTime

        when (mode) {
            Mode.IDLE -> {
                mode = Mode.PENDING
                pendingKey = key
                handler.postDelayed(resolvePending, COMBO_WINDOW_MS)
            }
            Mode.PENDING -> {
                handler.removeCallbacks(resolvePending)
                mode = Mode.COMBO
                trigger()
            }
            Mode.PASSTHROUGH -> {
                adjustVolume(key)
                startRepeat(key, ViewConfiguration.getKeyRepeatTimeout().toLong())
            }
            Mode.COMBO -> Unit
        }
    }

    private fun onKeyUp(key: Int) {
        heldKeys -= key
        if (key == repeatKey) stopRepeat()
        if (mode == Mode.PENDING && key == pendingKey) {

            handler.removeCallbacks(resolvePending)
            adjustVolume(key)
            mode = Mode.PASSTHROUGH
        }
        if (heldKeys.isEmpty()) mode = Mode.IDLE
    }

    private fun dropStaleKeys(now: Long) {
        if (mode == Mode.IDLE) return
        val stale = heldKeys.filterValues { now - it > STALE_KEY_MS }.keys
        if (stale.isEmpty()) return
        heldKeys -= stale
        if (repeatKey in stale) stopRepeat()
        if (heldKeys.isEmpty()) {
            handler.removeCallbacks(resolvePending)
            mode = Mode.IDLE
        }
    }

    private fun startRepeat(key: Int, initialDelayMs: Long) {
        stopRepeat()
        repeatKey = key
        handler.postDelayed(repeatStep, initialDelayMs.coerceAtLeast(0L))
    }

    private fun stopRepeat() {
        handler.removeCallbacks(repeatStep)
        repeatKey = 0
    }

    private fun adjustVolume(key: Int) {
        val direction =
            if (key == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        runCatching {
            audioManager.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, ADJUST_FLAGS)
        }.onFailure { Log.w(TAG, "Volume adjustment failed", it) }
    }

    private fun trigger() {
        val context = applicationContext
        scope.launch {
            val shot = runCatching { Screenshotter.capture(context) }.getOrNull() ?: return@launch
            ScreenshotNotifier.show(context, shot)
        }
    }

    private fun reset() {
        handler.removeCallbacks(resolvePending)
        stopRepeat()
        heldKeys.clear()
        mode = Mode.IDLE
    }

    override fun onDestroy() {
        reset()
        runCatching { unregisterReceiver(screenOffReceiver) }
        scope.cancel()
        super.onDestroy()
    }
}
