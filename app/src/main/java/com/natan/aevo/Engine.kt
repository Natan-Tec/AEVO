package com.natan.aevo

/** Ponte com o motor nativo (aevo_engine.cpp). Todas as chamadas de nota são seguras em qualquer thread. */
object Engine {
    init {
        System.loadLibrary("aevo")
    }

    external fun nativeStartAudio(): Boolean
    external fun nativeStopAudio()
    external fun nativeAudioInfo(): String

    external fun nativeAddTrack(sf2: ByteArray): Int
    external fun nativeRemoveTrack(id: Int)
    external fun nativePresetCount(id: Int): Int
    external fun nativePresetName(id: Int, index: Int): String
    external fun nativeSetPreset(id: Int, index: Int)
    external fun nativeSetVolume(id: Int, volume: Float)

    /** velocity de 1 a 127 */
    external fun nativeNoteOn(id: Int, key: Int, velocity: Int)
    external fun nativeNoteOff(id: Int, key: Int)
    external fun nativeControl(id: Int, controller: Int, value: Int)

    /** value de 0 a 16383, centro em 8192 */
    external fun nativePitchBend(id: Int, value: Int)
    external fun nativeAllNotesOff(id: Int)
}
