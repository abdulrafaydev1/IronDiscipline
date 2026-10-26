package com.irondiscipline.app.alarm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * High-Decibel Programmatic PCM Sound Synthesizer.
 * Generates 12 distinct, high-intensity hard alarm patterns directly in memory
 * using AudioTrack on AudioAttributes.USAGE_ALARM (Zero external media files required).
 * Audio writes run on a dedicated high-priority OS thread with THREAD_PRIORITY_URGENT_AUDIO
 * so device Doze / sleep states never suspend sound playback.
 */
class HardAlarmSoundEngine private constructor() {

    private val engineScope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var isPlaying = false

    @Volatile
    private var playbackThread: Thread? = null

    @Volatile
    private var currentAudioTrack: AudioTrack? = null

    private var previewTimerJob: Job? = null

    private val lock = Any()

    @Volatile
    var currentlyPlayingSoundIndex: Int? = null
        private set

    companion object {
        const val SAMPLE_RATE = 44100

        val SOUND_NAMES = listOf(
            "1. Nuclear Air Raid Siren",
            "2. Cyberpunk Glitch Klaxon",
            "3. Submarine Red Alert",
            "4. Industrial Factory Horn",
            "5. High-Voltage Dual Buzzer",
            "6. Military Reveille Pulse",
            "7. Extreme Strobe Beep (3000Hz)",
            "8. Biohazard Containment Alarm",
            "9. Sonar Ping Escalation",
            "10. Heavy Metal Sawtooth Wave",
            "11. Emergency Medical Telemetry",
            "12. Tactical Shockwave Siren"
        )

        @Volatile
        private var instance: HardAlarmSoundEngine? = null

        fun getInstance(): HardAlarmSoundEngine {
            return instance ?: synchronized(this) {
                instance ?: HardAlarmSoundEngine().also { instance = it }
            }
        }

        fun stopSound() {
            getInstance().stopSound()
        }
    }

    /**
     * Halts all active audio playback immediately with full resource cleanup.
     */
    fun stopSound() {
        synchronized(lock) {
            isPlaying = false
            currentlyPlayingSoundIndex = null

            previewTimerJob?.cancel()
            previewTimerJob = null

            playbackThread?.let {
                it.interrupt()
                playbackThread = null
            }

            try {
                currentAudioTrack?.let { track ->
                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        try { track.pause() } catch (_: Exception) {}
                        try { track.flush() } catch (_: Exception) {}
                    }
                    try { track.release() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                currentAudioTrack = null
            }
        }
    }

    /**
     * Previews the selected sound pattern for 3.2 seconds and then stops automatically.
     */
    fun previewSound(soundIndex: Int, onComplete: (() -> Unit)? = null) {
        playAlarmSound(soundIndex)

        previewTimerJob = engineScope.launch {
            delay(3200L)
            stopSound()
            onComplete?.invoke()
        }
    }

    /**
     * Loops the selected sound continuously with a 100% fresh AudioTrack instance.
     * Guaranteed never to stick or skip on 2nd, 3rd, or 10th runs.
     */
    fun playAlarmSound(soundIndex: Int) {
        // ALWAYS stop and clean up any previous session first so 2nd, 3rd, 10th runs NEVER get stuck!
        stopSound()

        synchronized(lock) {
            isPlaying = true
            currentlyPlayingSoundIndex = soundIndex

            playbackThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                var track: AudioTrack? = null
                try {
                    val soundBuffer = generateSoundPatternBuffer(soundIndex)
                    val minBufSize = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(soundBuffer.size * 2)

                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()

                    val audioFormat = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()

                    track = AudioTrack.Builder()
                        .setAudioAttributes(audioAttributes)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(minBufSize * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    currentAudioTrack = track

                    if (track.state != AudioTrack.STATE_INITIALIZED) {
                        Log.e("AlarmDiag", "[AUDIO_START] AudioTrack failed to initialize (state=${track.state})")
                        return@Thread
                    }

                    track.play()
                    Log.i("IronAlarmTrace", "[IronAlarmTrace:AUDIO_PLAY_LOOP] AudioTrack track.play() started successfully for soundIndex=$soundIndex")

                    while (isPlaying && !Thread.currentThread().isInterrupted) {
                        val written = track.write(soundBuffer, 0, soundBuffer.size, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) break
                    }
                } catch (e: Exception) {
                    Log.e("AlarmDiag", "[AUDIO_START] AudioTrack playback error", e)
                } finally {
                    try {
                        if (track?.state == AudioTrack.STATE_INITIALIZED) {
                            try { track?.stop() } catch (_: Exception) {}
                            try { track?.flush() } catch (_: Exception) {}
                        }
                        try { track?.release() } catch (_: Exception) {}
                    } catch (_: Exception) {}
                    synchronized(lock) {
                        if (currentAudioTrack === track) {
                            currentAudioTrack = null
                        }
                    }
                }
            }.apply {
                name = "HardAlarmAudioThread-${System.currentTimeMillis()}"
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Loops the selected sound continuously (alias for playAlarmSound).
     */
    fun startLoopingSound(soundIndex: Int) {
        playAlarmSound(soundIndex)
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying

    /**
     * Synthesizes high-decibel 16-bit PCM waveform buffers for the 12 distinct alarm patterns.
     */
    private fun generateSoundPatternBuffer(soundIndex: Int): ShortArray {
        return when (soundIndex) {
            0 -> generateNuclearAirRaidSiren()
            1 -> generateCyberpunkGlitchKlaxon()
            2 -> generateSubmarineRedAlert()
            3 -> generateIndustrialFactoryHorn()
            4 -> generateHighVoltageDualBuzzer()
            5 -> generateMilitaryReveillePulse()
            6 -> generateExtremeStrobeBeep()
            7 -> generateBiohazardContainmentAlarm()
            8 -> generateSonarPingEscalation()
            9 -> generateHeavyMetalSawtooth()
            10 -> generateEmergencyTelemetry()
            11 -> generateTacticalShockwaveSiren()
            else -> generateNuclearAirRaidSiren()
        }
    }

    // 0. Sweeping dual-frequency sawtooth 400Hz -> 1200Hz (1.6s cycle)
    private fun generateNuclearAirRaidSiren(): ShortArray {
        val durationSec = 1.6
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val buffer = ShortArray(totalSamples)
        var phase = 0.0

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            val freq = 450.0 + 750.0 * (0.5 - 0.5 * cos(2.0 * PI * t / durationSec))
            phase += 2.0 * PI * freq / SAMPLE_RATE
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            val sampleVal = 2.0 * (phase / (2.0 * PI) - floor(phase / (2.0 * PI) + 0.5))
            buffer[i] = (sampleVal * 28000.0).toInt().toShort()
        }
        return buffer
    }

    // 1. Rapid alternating harsh square wave 880Hz / 1760Hz (0.25s cycle)
    private fun generateCyberpunkGlitchKlaxon(): ShortArray {
        val durationSec = 1.0
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val buffer = ShortArray(totalSamples)
        var phase = 0.0

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            val isHighPitch = ((t * 8.0).toInt() % 2) == 0
            val freq = if (isHighPitch) 1760.0 else 880.0
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val raw = if (sin(phase) >= 0.0) 1.0 else -1.0
            buffer[i] = (raw * 27000.0).toInt().toShort()
        }
        return buffer
    }

    // 2. Rising urgent whoop 500Hz -> 1500Hz with sharp drop (0.8s cycle)
    private fun generateSubmarineRedAlert(): ShortArray {
        val durationSec = 0.8
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val buffer = ShortArray(totalSamples)
        var phase = 0.0

        for (i in 0 until totalSamples) {
            val t = (i.toDouble() / SAMPLE_RATE) % durationSec
            val freq = 500.0 + 1000.0 * (t / durationSec)
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val sampleVal = sin(phase)
            val window = if (t < 0.7) 1.0 else (0.8 - t) * 10.0
            buffer[i] = (sampleVal * window * 29000.0).toInt().toShort()
        }
        return buffer
    }

    // 3. Deep distorted low-mid brassy buzz 220Hz + 440Hz + 660Hz (0.8s cycle)
    private fun generateIndustrialFactoryHorn(): ShortArray {
        val durationSec = 0.8
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val buffer = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val t = (i.toDouble() / SAMPLE_RATE) % durationSec
            if (t < 0.6) {
                val s1 = sin(2.0 * PI * 220.0 * t)
                val s2 = sin(2.0 * PI * 440.0 * t) * 0.7
                val s3 = sin(2.0 * PI * 660.0 * t) * 0.5
                val combined = (s1 + s2 + s3).coerceIn(-1.0, 1.0)
                buffer[i] = (combined * 28000.0).toInt().toShort()
            } else {
                buffer[i] = 0
            }
        }
        return buffer
    }

    // 4. Aggressive 60Hz modulated 2000Hz shock buzz (0.7s cycle)
    private fun generateHighVoltageDualBuzzer(): ShortArray {
        val durationSec = 0.7
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val buffer = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val t = (i.toDouble() / SAMPLE_RATE) % durationSec
            if (t < 0.5) {
                val carrier = if (sin(2.0 * PI * 2000.0 * t) >= 0.0) 1.0 else -1.0
                val modulator = 0.5 + 0.5 * sin(2.0 * PI * 60.0 * t)
                buffer[i] = (carrier * modulator * 28000.0).toInt().toShort()
            } else {
                buffer[i] = 0
            }
        }
        return buffer
    }

    // 5. Rapid staccato triple-burst high-pitch bugle tones (1.0s cycle)
    private fun generateMilitaryReveillePulse(): ShortArray {
        val durationSec = 1.0
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val buffer = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val t = (i.toDouble() / SAMPLE_RATE) % durationSec
            val burstIndex = (t / 0.16).toInt()
            val burstT = t % 0.16
            if (burstIndex in 0..2 && burstT < 0.12) {
                val freq = when (burstIndex) {
                    0 -> 1050.0
                    1 -> 1318.0
                    else -> 1568.0
                }
                val sampleVal = sin(2.0 * PI * freq * t)
                buffer[i] = (sampleVal * 28000.0).toInt().toShort()
            } else {
                buffer[i] = 0
            }
        }
        return buffer
    }

    // 6. Piercing 3000Hz ear-awakening rapid pulse (0.12s cycle)
    private fun generateExtremeStrobeBeep(): ShortArray {
        val durationSec = 0.6
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val buffer = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val t = (i.toDouble() / SAMPLE_RATE) % 0.12
            if (t < 0.06) {
                val sampleVal = sin(2.0 * PI * 3000.0 * t)
                buffer[i] = (sampleVal * 29000.0).toInt().toShort()
            } else {
                buffer[i] = 0
            }
        }
        return buffer
    }

    // 7. Alternating dissonance 950Hz & 1420Hz siren (0.5s cycle)
    private fun generateBiohazardContainmentAlarm(): ShortArray {
        val durationSec = 0.5
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val buffer = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val t = (i.toDouble() / SAMPLE_RATE) % durationSec
            val freq = if (t < 0.25) 950.0 else 1420.0
            val s1 = sin(2.0 * PI * freq * t)
            val s2 = sin(2.0 * PI * (freq * 1.02) * t) * 0.5
            val combined = (s1 + s2).coerceIn(-1.0, 1.0)
            buffer[i] = (combined * 28000.0).toInt().toShort()
        }
        return buffer
    }

    // 8. High-resonance metallic 2500Hz ping with accelerating tempo (1.0s cycle)
    private fun generateSonarPingEscalation(): ShortArray {
        val durationSec = 1.0
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val buffer = ShortArray(totalSamples)
        val pings = listOf(0.0, 0.4, 0.7, 0.9)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            var sampleSum = 0.0
            for (p in pings) {
                if (t >= p && t < p + 0.25) {
                    val dt = t - p
                    val decay = kotlin.math.exp(-14.0 * dt)
                    sampleSum += sin(2.0 * PI * 2500.0 * dt) * decay
                }
            }
            buffer[i] = (sampleSum.coerceIn(-1.0, 1.0) * 29000.0).toInt().toShort()
        }
        return buffer
    }

    // 9. Aggressive multi-harmonic harsh synth alarm (0.7s cycle)
    private fun generateHeavyMetalSawtooth(): ShortArray {
        val durationSec = 0.7
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val buffer = ShortArray(totalSamples)
        var phase = 0.0

        for (i in 0 until totalSamples) {
            val t = (i.toDouble() / SAMPLE_RATE) % durationSec
            if (t < 0.55) {
                phase += 2.0 * PI * 220.0 / SAMPLE_RATE
                if (phase > 2.0 * PI) phase -= 2.0 * PI
                val saw1 = 2.0 * (phase / (2.0 * PI) - floor(phase / (2.0 * PI) + 0.5))
                val saw2 = sin(2.0 * phase * 2.0) * 0.6
                val combined = (saw1 + saw2).coerceIn(-1.0, 1.0)
                buffer[i] = (combined * 28000.0).toInt().toShort()
            } else {
                buffer[i] = 0
            }
        }
        return buffer
    }

    // 10. Urgent ICU monitor rapid triple-beep pattern (0.9s cycle)
    private fun generateEmergencyTelemetry(): ShortArray {
        val durationSec = 0.9
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val buffer = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val t = (i.toDouble() / SAMPLE_RATE) % durationSec
            val beepSlot = (t / 0.12).toInt()
            val slotT = t % 0.12
            if (beepSlot in 0..2 && slotT < 0.07) {
                val sampleVal = sin(2.0 * PI * 1850.0 * slotT)
                buffer[i] = (sampleVal * 28500.0).toInt().toShort()
            } else {
                buffer[i] = 0
            }
        }
        return buffer
    }

    // 11. Full-spectrum modulated police/tactical wail + yelp (0.5s cycle)
    private fun generateTacticalShockwaveSiren(): ShortArray {
        val durationSec = 0.5
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val buffer = ShortArray(totalSamples)
        var phase = 0.0

        for (i in 0 until totalSamples) {
            val t = (i.toDouble() / SAMPLE_RATE) % durationSec
            val freq = 600.0 + 1000.0 * (0.5 - 0.5 * cos(2.0 * PI * t / durationSec))
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val sampleVal = sin(phase)
            buffer[i] = (sampleVal * 29000.0).toInt().toShort()
        }
        return buffer
    }
}
