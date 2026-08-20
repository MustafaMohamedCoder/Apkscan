package com.masahhisabat.app.data

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * نغمات واهتزازات مكالمات خفيفة ومحلية. تعتمد فقط على مولد نغمات النظام
 * وتحترم وضع الرنين والصامت، ولا تضيف أي ملفات صوتية أو خدمة خارجية.
 */
object CallFeedback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeTone: ToneGenerator? = null
    private var repeatingTone: Runnable? = null

    @Synchronized
    fun startOutgoingWaiting(context: Context) {
        startRepeatingTone(context, ToneGenerator.TONE_SUP_RINGTONE, 1500L)
    }

    @Synchronized
    fun startIncomingRinging(context: Context) {
        startRepeatingTone(context, ToneGenerator.TONE_SUP_RINGTONE, 1400L)
    }

    @Synchronized
    fun stopTone() {
        repeatingTone?.let(mainHandler::removeCallbacks)
        repeatingTone = null
        activeTone?.stopTone()
        activeTone?.release()
        activeTone = null
    }

    fun vibrateAnswered(context: Context) = vibrate(context, 35L)

    fun playCallEnded(context: Context) {
        if (!isRingerAudible(context)) return
        ToneGenerator(AudioManager.STREAM_RING, TONE_VOLUME).also { tone ->
            tone.startTone(ToneGenerator.TONE_PROP_NACK, 180)
            mainHandler.postDelayed({ tone.release() }, 260L)
        }
        vibrate(context, 28L)
    }

    @Synchronized
    private fun startRepeatingTone(context: Context, toneType: Int, intervalMs: Long) {
        stopTone()
        if (!isRingerAudible(context)) return
        activeTone = ToneGenerator(AudioManager.STREAM_RING, TONE_VOLUME)
        repeatingTone = object : Runnable {
            override fun run() {
                activeTone?.startTone(toneType, 1050)
                mainHandler.postDelayed(this, intervalMs)
            }
        }.also(mainHandler::post)
    }

    private fun vibrate(context: Context, durationMs: Long) {
        if (!shouldVibrate(context)) return
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(durationMs)
            }
        } catch (_: SecurityException) {
            // لا نمنع المكالمة إن قيّد الجهاز الاهتزاز.
        }
    }

    private fun isRingerAudible(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && audio.getStreamVolume(AudioManager.STREAM_RING) > 0
    }

    private fun shouldVibrate(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.ringerMode != AudioManager.RINGER_MODE_SILENT
    }

    private const val TONE_VOLUME = 55
}
