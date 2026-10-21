package com.natan.aevo

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AEVO 0.3 beta.
 *
 * Faixa do tipo "Instrumentos virtuais (SF2)": cada faixa carrega um SF2, tem preset, volume e
 * uma entrada MIDI. Ao armar a faixa (botão gravar), as notas do controlador escolhido tocam
 * direto no motor nativo de baixa latência (monitoramento).
 */
class MainActivity : AppCompatActivity(), MidiListener {

    /** Estado de uma faixa. Lido pela thread do MIDI, por isso os campos são @Volatile. */
    private class TrackState(val id: Int, val name: String, val presetCount: Int) {
        @Volatile var armed = false
        @Volatile var midiInput = ANY   // NONE, ANY ou o id de um dispositivo
        var presetIndex = 0
        var volume = 0.8f
    }

    private val tracks = CopyOnWriteArrayList<TrackState>()
    private var selectedTrack = -1

    private val keyNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B", "C")
    private val firstKey = 60 // C4

    private lateinit var trackList: LinearLayout
    private lateinit var audioInfo: TextView
    private lateinit var midiInfo: TextView
    private lateinit var midi: MidiInput

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
        val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".sf2")
            ?: "Faixa ${tracks.size + 1}"
        tracks.add(TrackState(id, name, Engine.nativePresetCount(id)))
        if (selectedTrack < 0) selectedTrack = id
        refreshTracks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        midi = MidiInput(this, this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        root.addView(TextView(this).apply {
            text = "AEVO 0.3 beta"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        audioInfo = smallText("Áudio: iniciando…")
        midiInfo = smallText("MIDI: nenhum controlador")
        root.addView(audioInfo)
        root.addView(midiInfo)

        root.addView(Button(this).apply {
            text = "+ Adicionar faixa"
            setOnClickListener { showAddTrackDialog() }
        })

        trackList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply { addView(trackList) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(buildKeyboard())
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!Engine.nativeStartAudio()) toast("Não consegui abrir a saída de áudio")
        audioInfo.text = "Áudio: ${Engine.nativeAudioInfo()}"
        midi.start()
        updateMidiInfo()
    }

    override fun onPause() {
        for (t in tracks) Engine.nativeAllNotesOff(t.id)
        midi.stop()
        Engine.nativeStopAudio()
        super.onPause()
    }

    override fun onDestroy() {
        tracks.forEach { Engine.nativeRemoveTrack(it.id) }
        tracks.clear()
        super.onDestroy()
    }

    // ---- MIDI: roda na thread do MIDI, vai direto ao motor (sem passar pela interface) ----

    private inline fun forArmed(deviceId: Int, action: (TrackState) -> Unit) {
        for (t in tracks) {
            if (t.armed && (t.midiInput == ANY || t.midiInput == deviceId)) action(t)
        }
    }

    override fun onNoteOn(deviceId: Int, key: Int, velocity: Int) =
        forArmed(deviceId) { Engine.nativeNoteOn(it.id, key, velocity) }

    override fun onNoteOff(deviceId: Int, key: Int) =
        forArmed(deviceId) { Engine.nativeNoteOff(it.id, key) }

    override fun onControl(deviceId: Int, controller: Int, value: Int) =
        forArmed(deviceId) { Engine.nativeControl(it.id, controller, value) }

    override fun onPitchBend(deviceId: Int, value: Int) =
        forArmed(deviceId) { Engine.nativePitchBend(it.id, value) }

    override fun onDevicesChanged() {
        updateMidiInfo()
        refreshTracks()
    }

    private fun updateMidiInfo() {
        val list = midi.connectedDevices()
        midiInfo.text = when {
            !midi.available -> "MIDI: este aparelho não tem suporte"
            list.isEmpty() -> "MIDI: nenhum controlador (ligue um por USB/OTG)"
            else -> "MIDI: " + list.joinToString(", ") { it.name }
        }
    }

    // ---- Faixas ----

    private fun showAddTrackDialog() {
        val options = arrayOf("Instrumentos virtuais (SF2)", "Faixa de áudio (em breve)")
        AlertDialog.Builder(this)
            .setTitle("Adicionar faixa")
            .setItems(options) { _, which ->
                if (which == 0) pickSf2.launch(arrayOf("*/*")) else toast("Faixa de áudio ainda não existe")
            }
            .show()
    }

    private fun removeTrack(t: TrackState) {
        t.armed = false
        Engine.nativeRemoveTrack(t.id)
        tracks.remove(t)
        if (selectedTrack == t.id) selectedTrack = tracks.firstOrNull()?.id ?: -1
        refreshTracks()
    }

    private fun showPresetDialog(t: TrackState) {
        val names = Array(t.presetCount) { i -> "${i + 1}. ${Engine.nativePresetName(t.id, i)}" }
        AlertDialog.Builder(this)
            .setTitle("Instrumento")
            .setSingleChoiceItems(names, t.presetIndex) { dialog, which ->
                t.presetIndex = which
                Engine.nativeSetPreset(t.id, which)
                dialog.dismiss()
                refreshTracks()
            }
            .show()
    }

    private fun showMidiDialog(t: TrackState) {
        val devices = midi.connectedDevices()
        val labels = mutableListOf("Nenhum", "Qualquer controlador")
        val ids = mutableListOf(NONE, ANY)
        for (d in devices) {
            labels.add(d.name)
            ids.add(d.id)
        }
        val current = ids.indexOf(t.midiInput).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Entrada MIDI da faixa")
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                t.midiInput = ids[which]
                if (t.midiInput == NONE && t.armed) disarm(t)
                dialog.dismiss()
                refreshTracks()
            }
            .show()
    }

    private fun disarm(t: TrackState) {
        t.armed = false
        Engine.nativeAllNotesOff(t.id)
    }

    private fun toggleArm(t: TrackState) {
        if (t.armed) {
            disarm(t)
        } else if (t.midiInput == NONE) {
            toast("Escolha uma entrada MIDI para esta faixa")
        } else {
            t.armed = true
            if (midi.connectedDevices().isEmpty()) toast("Faixa armada, mas nenhum controlador conectado")
        }
        refreshTracks()
    }

    private fun midiLabel(t: TrackState) = when (t.midiInput) {
        NONE -> "Nenhum"
        ANY -> "Qualquer controlador"
        else -> midi.nameOf(t.midiInput) ?: "Controlador desconectado"
    }

    private fun refreshTracks() {
        runOnUiThread {
            trackList.removeAllViews()
            if (tracks.isEmpty()) {
                trackList.addView(smallText("Nenhuma faixa. Toque em \"Adicionar faixa\" e escolha Instrumentos virtuais (SF2)."))
            }
            for (t in tracks) trackList.addView(buildTrackCard(t))
        }
    }

    private fun buildTrackCard(t: TrackState): View {
        val selected = t.id == selectedTrack
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (selected) CARD_SELECTED else CARD)
                if (t.armed) setStroke(dp(2), ARM_RED)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = (if (selected) "▶ " else "") + t.name
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { selectedTrack = t.id; refreshTracks() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "✕"
            setOnClickListener { removeTrack(t) }
        })
        card.addView(header)

        val presetName = if (t.presetCount > 0) Engine.nativePresetName(t.id, t.presetIndex) else "—"
        card.addView(Button(this).apply {
            text = "Instrumento: $presetName"
            transformationMethod = null
            setOnClickListener { showPresetDialog(t) }
        })
        card.addView(Button(this).apply {
            text = "MIDI: ${midiLabel(t)}"
            transformationMethod = null
            setOnClickListener { showMidiDialog(t) }
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(Button(this).apply {
            text = if (t.armed) "● ARMADA" else "● GRAVAR"
            setTextColor(if (t.armed) Color.WHITE else ARM_RED)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (t.armed) ARM_RED else CARD_BUTTON
            )
            setOnClickListener { toggleArm(t) }
        })
        controls.addView(SeekBar(this).apply {
            max = 100
            progress = (t.volume * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    t.volume = value / 100f
                    Engine.nativeSetVolume(t.id, t.volume)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })
        card.addView(controls)
        return card
    }

    // ---- Teclado na tela: toca a faixa selecionada ----

    private fun buildKeyboard(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        for (i in keyNames.indices) {
            val key = firstKey + i
            row.addView(Button(this).apply {
                text = keyNames[i]
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(96), 1f)
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN ->
                            if (selectedTrack >= 0) Engine.nativeNoteOn(selectedTrack, key, 100)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            if (selectedTrack >= 0) Engine.nativeNoteOff(selectedTrack, key)
                    }
                    true
                }
            })
        }
        return row
    }

    // ---- Utilidades ----

    private fun smallText(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFFB0B0B0.toInt())
        textSize = 13f
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val NONE = -2
        const val ANY = -1

        private const val BG = 0xFF121212.toInt()
        private const val CARD = 0xFF1E1E1E.toInt()
        private const val CARD_SELECTED = 0xFF2A2F3A.toInt()
        private const val CARD_BUTTON = 0xFF333333.toInt()
        private const val ARM_RED = 0xFFE53935.toInt()
    }
}
