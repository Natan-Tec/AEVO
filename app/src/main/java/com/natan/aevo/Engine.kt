package com.natan.aevo

/** Ponte com o motor nativo (aevo_engine.cpp). */
object Engine {
    init {
        System.loadLibrary("aevo")
    }

    external fun nativeInit(sampleRate: Int)
    external fun nativeAddTrack(sf2: ByteArray): Int
    external fun nativeRemoveTrack(id: Int)
    external fun nativeSetVolume(id: Int, volume: Float)
    external fun nativeNoteOn(id: Int, key: Int, velocity: Float)
    external fun nativeNoteOff(id: Int, key: Int)
    external fun nativeRender(out: ShortArray, frames: Int)
}
