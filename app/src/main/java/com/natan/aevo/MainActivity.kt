package com.natan.aevo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * AEVO v0.2: importa arquivos SF2, mantém trilhas ilimitadas (cada uma com
 * instrumento e volume próprios) e toca um teclado de 2 oitavas na trilha selecionada.
 */
class MainActivity : AppCompatActivity() {

    private val sampleRate = 44100
    private val frames = 512
    private val keyNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B", "C")
    private val firstKey = 60 // C4

    private val trackIds = mutableListOf<Int>()
    private val trackNames = mutableMapOf<Int, String>()
    private var selectedTrack = -1

    private lateinit var trackList: LinearLayout
    private lateinit var selectedLabel: TextView

    private var playing = false
    private var audioThread: Thread? = null

    private val pickSf2 = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            toast("Não consegui ler o arquivo")
            return@registerForActivityResult
        }
        val id = Engine.nativeAddTrack(bytes)
        if (id < 0) {
            toast("Esse arquivo não é um SF2 válido")
            return@registerForActivityResult
        }
        Engine.nativeSetVolume(id, 0.8f)
        trackIds.add(id)
        trackNames[id] = uri.lastPathSegment?.substringAfterLast('/') ?: "Trilha ${trackIds.size}"
        if (selectedTrack < 0) selectTrack(id)
        refreshTracks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.nativeInit(sampleRate)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(Button(this).apply {
            text = "+ Adicionar trilha (SF2)"
            setOnClickListener { pickSf2.launch(arrayOf("*/*")) }
        })

        selectedLabel = TextView(this).apply {
            text = "Nenhuma trilha selecionada"
            setPadding(0, 24, 0, 24)
        }
        root.addView(selectedLabel)

        trackList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(trackList)

        root.addView(buildKeyboard())

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        startAudio()
    }

    override fun onPause() {
        stopAudio()
        super.onPause()
    }

    override fun onDestroy() {
        trackIds.forEach { Engine.nativeRemoveTrack(it) }
        super.onDestroy()
    }

    private fun selectTrack(id: Int) {
        selectedTrack = id
        selectedLabel.text = "Tocando: ${trackNames[id]}"
    }

    private fun removeTrack(id: Int) {
        Engine.nativeRemoveTrack(id)
        trackIds.remove(id)
        trackNames.remove(id)
        if (selectedTrack == id) {
            selectTrack(trackIds.firstOrNull() ?: -1)
            if (selectedTrack < 0) selectedLabel.text = "Nenhuma trilha selecionada"
        }
        refreshTracks()
    }

    private fun refreshTracks() {
        trackList.removeAllViews()
        for (id in trackIds) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = trackNames[id]
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTrack(id) }
            })
            row.addView(SeekBar(this).apply {
                max = 100
                progress = 80
                layoutParams = LinearLayout.LayoutParams(300, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                        Engine.nativeSetVolume(id, value / 100f)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            })
            row.addView(Button(this).apply {
                text = "X"
                setOnClickListener { removeTrack(id) }
            })
            trackList.addView(row)
        }
    }

    private fun buildKeyboard(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 0)
        }
        for (i in keyNames.indices) {
            val key = firstKey + i
            row.addView(Button(this).apply {
                text = keyNames[i]
                layoutParams = LinearLayout.LayoutParams(0, 200, 1f)
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN ->
                            if (selectedTrack >= 0) Engine.nativeNoteOn(selectedTrack, key, 0.8f)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            if (selectedTrack >= 0) Engine.nativeNoteOff(selectedTrack, key)
                    }
                    true
                }
            })
        }
        return row
    }

    // Thread de áudio: puxa o mix do motor nativo e envia para o AudioTrack.
    private fun startAudio() {
        if (playing) return
        playing = true
        audioThread = Thread {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, frames * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val buffer = ShortArray(frames * 2)
            track.play()
            while (playing) {
                Engine.nativeRender(buffer, frames)
                track.write(buffer, 0, buffer.size)
            }
            track.stop()
            track.release()
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun stopAudio() {
        playing = false
        audioThread?.join()
        audioThread = null
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
