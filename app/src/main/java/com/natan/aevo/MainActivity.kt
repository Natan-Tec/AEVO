package com.natan.aevo

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.PI
import kotlin.math.sin

/**
 * Primeiro protótipo do AEVO: 4 teclas que tocam uma nota (onda senoidal).
 * Próximo passo: trocar o gerador de senoide pelo motor de SF2 (TinySoundFont via NDK).
 */
class MainActivity : AppCompatActivity() {

    private val sampleRate = 44100
    private val noteSeconds = 1.0
    private val notes = listOf(
        "C4" to 261.63,
        "D4" to 293.66,
        "E4" to 329.63,
        "G4" to 392.00
    )

    private val tracks = mutableMapOf<String, AudioTrack>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        notes.forEach { (name, freq) ->
            val button = Button(this).apply {
                text = name
                setOnClickListener { playNote(name, freq) }
            }
            layout.addView(button)
        }

        setContentView(layout)
    }

    private fun playNote(name: String, frequency: Double) {
        val track = tracks.getOrPut(name) { createTrack(frequency) }
        track.stop()
        track.reloadStaticData()
        track.play()
    }

    private fun createTrack(frequency: Double): AudioTrack {
        val totalSamples = (sampleRate * noteSeconds).toInt()
        val buffer = ShortArray(totalSamples) { i ->
            val t = i.toDouble() / sampleRate
            val fade = if (i > totalSamples - 2000) (totalSamples - i) / 2000.0 else 1.0
            (sin(2 * PI * frequency * t) * Short.MAX_VALUE * 0.5 * fade).toInt().toShort()
        }

        return AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(buffer, 0, buffer.size) }
    }

    override fun onDestroy() {
        tracks.values.forEach { it.release() }
        tracks.clear()
        super.onDestroy()
    }
}
