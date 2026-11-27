package com.vadila.downloader

/** sherpa-onnx on the phone's CPU: separates voices from music/effects, and fingerprints voices. */
object Sherpa {
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        // load the dependencies first so the linker finds them on every Android version
        runCatching { System.loadLibrary("onnxruntime") }
        runCatching { System.loadLibrary("sherpa-onnx-c-api") }
        System.loadLibrary("dubsherpa")
        loaded = true
    }

    /** Spleeter (vocals + accompaniment models) or UVR (one model): pass "" for the unused ones. */
    @JvmStatic external fun sepCreate(vocals: String, accompaniment: String, uvr: String, threads: Int): Long
    @JvmStatic external fun sepFree(h: Long)
    /** Separates left/right[0 until n]: out = vocals L, vocals R, rest L, rest R; returns samples per channel or -1. */
    @JvmStatic external fun sepProcess(h: Long, left: FloatArray, right: FloatArray, n: Int, sampleRate: Int, out: Array<FloatArray>): Int

    @JvmStatic external fun embCreate(model: String, threads: Int): Long
    @JvmStatic external fun embFree(h: Long)
    /** Voice fingerprint of a 16 kHz mono clip (null if too short). */
    @JvmStatic external fun embCompute(h: Long, samples: FloatArray, sampleRate: Int): FloatArray?

    /** Stereo [offset, offset + n) → mono 16 kHz PCM16 with an anti-aliasing filter; returns samples written. */
    @JvmStatic external fun toMono16k(left: FloatArray, right: FloatArray, offset: Int, n: Int, sampleRate: Int, out: ShortArray): Int
}
