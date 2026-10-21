package com.natan.aevo

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/** Mensagem MIDI já separada, entregue ao listener na thread do MIDI (sem passar pela UI). */
interface MidiListener {
    fun onNoteOn(deviceId: Int, key: Int, velocity: Int)
    fun onNoteOff(deviceId: Int, key: Int)
    fun onControl(deviceId: Int, controller: Int, value: Int)
    fun onPitchBend(deviceId: Int, value: Int)
    /** Chamado na thread principal quando um controlador é plugado ou removido. */
    fun onDevicesChanged()
}

/**
 * Abre todos os controladores MIDI USB conectados e repassa as mensagens ao [MidiListener].
 * Usa a API nativa do Android (android.media.midi), sem permissões extras.
 */
class MidiInput(context: Context, private val listener: MidiListener) {

    class Device(val id: Int, val name: String)

    private val manager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val main = Handler(Looper.getMainLooper())
    private val open = mutableMapOf<Int, MidiDevice>()
    private val ports = mutableMapOf<Int, MidiOutputPort>()

    @Volatile
    private var devices: List<Device> = emptyList()

    val available: Boolean get() = manager != null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            openDevice(info)
            refresh()
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            closeDevice(info.id)
            refresh()
        }
    }

    fun start() {
        val m = manager ?: return
        m.registerDeviceCallback(callback, main)
        for (info in m.devices) openDevice(info)
        refresh()
    }

    fun stop() {
        manager?.unregisterDeviceCallback(callback)
        for (id in open.keys.toList()) closeDevice(id)
        devices = emptyList()
    }

    fun connectedDevices(): List<Device> = devices

    fun nameOf(deviceId: Int): String? = devices.firstOrNull { it.id == deviceId }?.name

    private fun refresh() {
        devices = open.map { (id, device) -> Device(id, deviceName(device.info)) }
        listener.onDevicesChanged()
    }

    private fun deviceName(info: MidiDeviceInfo): String {
        val p = info.properties
        return p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "Controlador ${info.id}"
    }

    private fun openDevice(info: MidiDeviceInfo) {
        // "outputPortCount" = portas por onde o dispositivo MANDA dados para nós (teclas, knobs).
        if (info.outputPortCount == 0 || open.containsKey(info.id)) return
        manager?.openDevice(info, opened@{ device ->
            if (device == null) return@opened
            val port = device.openOutputPort(0)
            if (port == null) {
                device.close()
                return@opened
            }
            port.connect(Parser(info.id))
            open[info.id] = device
            ports[info.id] = port
            refresh()
        }, main)
    }

    private fun closeDevice(id: Int) {
        ports.remove(id)?.close()
        open.remove(id)?.close()
    }

    /** Lê o fluxo de bytes MIDI (suporta running status e várias mensagens por pacote). */
    private inner class Parser(private val deviceId: Int) : MidiReceiver() {
        private var status = 0
        private val data = IntArray(2)
        private var count = 0
        private var inSysex = false

        override fun onSend(msg: ByteArray, offset: Int, length: Int, timestamp: Long) {
            for (i in offset until offset + length) {
                val b = msg[i].toInt() and 0xFF
                when {
                    b >= 0xF8 -> {}                        // tempo real: ignora
                    b == 0xF0 -> { inSysex = true; status = 0 }
                    b == 0xF7 -> { inSysex = false }
                    b >= 0x80 -> { inSysex = false; status = b; count = 0 }
                    inSysex || status == 0 -> {}
                    else -> {
                        data[count++] = b
                        if (count == dataLength(status)) {
                            dispatch(status, data[0], data[1])
                            count = 0
                        }
                    }
                }
            }
        }

        private fun dataLength(s: Int) = when (s and 0xF0) {
            0xC0, 0xD0 -> 1
            else -> 2
        }

        private fun dispatch(s: Int, d1: Int, d2: Int) {
            when (s and 0xF0) {
                0x90 -> if (d2 > 0) listener.onNoteOn(deviceId, d1, d2) else listener.onNoteOff(deviceId, d1)
                0x80 -> listener.onNoteOff(deviceId, d1)
                0xB0 -> listener.onControl(deviceId, d1, d2)
                0xE0 -> listener.onPitchBend(deviceId, (d2 shl 7) or d1)
            }
        }
    }
}
