package com.vadila.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/** Speech typing through the phone's recognizer (Google). */
class Dictation(
    private val ctx: Context,
    private val onText: (String, Boolean) -> Unit,
    private val onState: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var sr: SpeechRecognizer? = null
    var active = false
        private set

    fun start(continuous: Boolean = false) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onError("التعرّف على الكلام غير متاح على هذا الهاتف (ثبّت تطبيق Google).")
            return
        }
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                active = true
                onState(true)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                active = false
                onState(false)
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onError("")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onError("اسمح للتطبيق باستعمال الميكروفون.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> onError("التعرّف على الكلام يحتاج اتصالًا بالإنترنت.")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> onError("لغة الإملاء غير مدعومة — غيّرها من الإعدادات.")
                    else -> onError("تعذّر الإملاء الصوتي (رمز $error).")
                }
            }

            override fun onResults(results: Bundle?) {
                active = false
                onState(false)
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                onText(t, true)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                onText(t, false)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        val lang = Prefs.get("speech_lang", "")
        if (lang.isNotBlank()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        if (continuous) i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        r.startListening(i)
    }

    fun stop() {
        try {
            sr?.stopListening()
            sr?.destroy()
        } catch (e: Exception) {
        }
        sr = null
        if (active) {
            active = false
            onState(false)
        }
    }
}

/** Plays 16-bit mono PCM as it arrives. */
class PcmPlayer(rate: Int, voiceCall: Boolean = false) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val track: AudioTrack
    @Volatile
    private var running = true
    @Volatile
    var level = 0f
        private set
    @Volatile
    var pending = 0
        private set
    private val thread: Thread

    init {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (voiceCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min * 4, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        thread = Thread {
            while (running) {
                val b = try {
                    queue.take()
                } catch (e: InterruptedException) {
                    break
                }
                if (b.isEmpty()) continue
                var sum = 0.0
                var k = 0
                while (k + 1 < b.size) {
                    val v = ((b[k + 1].toInt() shl 8) or (b[k].toInt() and 0xFF)).toShort().toInt()
                    sum += v.toDouble() * v
                    k += 2
                }
                level = (Math.sqrt(sum / maxOf(1, b.size / 2)) / 6000.0).toFloat().coerceIn(0f, 1f)
                try {
                    track.write(b, 0, b.size)
                } catch (e: Exception) {
                    break
                }
                pending = queue.size
                if (queue.isEmpty()) level = 0f
            }
        }.apply { start() }
    }

    fun write(b: ByteArray) {
        queue.offer(b)
        pending = queue.size
    }

    /** Drops everything not yet played (the person interrupted). */
    fun flush() {
        queue.clear()
        try {
            track.pause()
            track.flush()
            track.play()
        } catch (e: Exception) {
        }
        level = 0f
    }

    val idle get() = queue.isEmpty()

    fun release() {
        running = false
        queue.offer(ByteArray(0))
        thread.interrupt()
        try {
            track.stop()
            track.release()
        } catch (e: Exception) {
        }
    }
}

/** Reads answers aloud: natural Gemini voices when available, otherwise the phone's voice. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var player: PcmPlayer? = null
    private var cancel: Cancel? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    var speakingId: String? = null
        private set
    var onChange: (() -> Unit)? = null

    val VOICES = linkedMapOf(
        "Puck" to "Puck — رجل، مرح", "Charon" to "Charon — رجل، رصين", "Fenrir" to "Fenrir — رجل، حماسي",
        "Orus" to "Orus — رجل، ثابت", "Iapetus" to "Iapetus — رجل، واضح", "Algenib" to "Algenib — رجل، أجش",
        "Alnilam" to "Alnilam — رجل، حازم", "Schedar" to "Schedar — رجل، هادئ", "Sadaltager" to "Sadaltager — رجل، مثقف",
        "Kore" to "Kore — امرأة، واثقة", "Aoede" to "Aoede — امرأة، نسيمية", "Leda" to "Leda — امرأة، شابة",
        "Zephyr" to "Zephyr — امرأة، مشرقة", "Despina" to "Despina — امرأة، ناعمة", "Achernar" to "Achernar — امرأة، رقيقة",
        "Gacrux" to "Gacrux — امرأة، ناضجة", "Sulafat" to "Sulafat — امرأة، دافئة", "Vindemiatrix" to "Vindemiatrix — امرأة، لطيفة",
    )

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext, { st -> ready = st == TextToSpeech.SUCCESS }, "com.google.android.tts")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId?.endsWith("#last") == true) finish()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finish()
            }
        })
    }

    private fun finish() {
        speakingId = null
        main.post { onChange?.invoke() }
    }

    fun stop() {
        cancel?.stop()
        cancel = null
        player?.release()
        player = null
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        if (speakingId != null) finish()
    }

    /** Language of a text, judged from its script and a few common words. */
    fun localeOf(text: String): Locale {
        var ar = 0; var lat = 0; var cyr = 0; var cjk = 0; var kana = 0; var hangul = 0; var deva = 0; var heb = 0; var greek = 0; var thai = 0
        for (c in text) {
            when (c) {
                in '؀'..'ۿ', in 'ݐ'..'ݿ', in 'ﭐ'..'﷿', in 'ﹰ'..'﻿' -> ar++
                in 'a'..'z', in 'A'..'Z', in 'À'..'ɏ' -> lat++
                in 'Ѐ'..'ӿ' -> cyr++
                in '一'..'鿿' -> cjk++
                in '぀'..'ヿ' -> kana++
                in '가'..'힯' -> hangul++
                in 'ऀ'..'ॿ' -> deva++
                in '֐'..'׿' -> heb++
                in 'Ͱ'..'Ͽ' -> greek++
                in '฀'..'๿' -> thai++
            }
        }
        val best = listOf(ar to "ar", lat to "lat", cyr to "ru", cjk to "zh", kana to "ja", hangul to "ko", deva to "hi", heb to "he", greek to "el", thai to "th")
            .maxByOrNull { it.first }!!
        if (best.first == 0) return Locale.getDefault()
        if (kana > 0 && best.second == "zh") return Locale("ja")
        if (best.second != "lat") return Locale(best.second)
        val words = text.lowercase().split(Regex("[^a-zà-ÿ]+")).filter { it.isNotEmpty() }
        val score = mapOf(
            "fr" to setOf("le", "la", "les", "est", "et", "des", "une", "un", "pour", "que", "dans", "vous", "pas", "avec", "sur", "je"),
            "es" to setOf("el", "los", "las", "que", "es", "una", "por", "para", "con", "del", "como", "pero", "muy", "está"),
            "de" to setOf("der", "die", "das", "und", "ist", "nicht", "ich", "mit", "sie", "ein", "eine", "auf", "für"),
            "it" to setOf("il", "che", "di", "gli", "una", "per", "non", "sono", "della", "con", "anche"),
            "pt" to setOf("o", "os", "que", "não", "uma", "para", "com", "você", "está", "mais", "como", "do", "da"),
            "tr" to setOf("ve", "bir", "bu", "için", "ile", "çok", "ama", "gibi", "değil", "olarak"),
            "en" to setOf("the", "and", "is", "are", "to", "of", "in", "that", "it", "you", "for", "with", "this", "was"),
        ).mapValues { (_, set) -> words.count { it in set } }
        val top = score.maxByOrNull { it.value }!!
        return if (top.value == 0) Locale.ENGLISH else Locale(top.key)
    }

    /** Speaks [text]; [id] identifies the message (for the play/stop button). */
    fun speak(ctx: Context, id: String, text: String) {
        stop()
        init(ctx)
        val clean = Md.speakable(text).take(12_000)
        if (clean.isBlank()) return
        speakingId = id
        main.post { onChange?.invoke() }
        val natural = Prefs.get("voice_engine", "gemini") == "gemini" && Prefs.hasKey("gemini")
        if (natural) speakGemini(ctx, clean) else speakPhone(clean)
    }

    private fun chunks(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (s in text.split(Regex("(?<=[.!?؟。\n])\\s+"))) {
            if (sb.length + s.length > max && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (s.length > max) {
                for (piece in s.chunked(max)) out.add(piece)
            } else sb.append(s).append(' ')
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out
    }

    private fun speakGemini(ctx: Context, text: String) {
        val c = Cancel()
        cancel = c
        val pl = PcmPlayer(24000)
        player = pl
        val voice = Prefs.get("voice_name", "Kore")
        Thread {
            val parts = chunks(text, 900)
            for ((k, part) in parts.withIndex()) {
                if (c.stopped) return@Thread
                try {
                    pl.write(GeminiApi.speak(part, voice, c))
                } catch (e: Exception) {
                    if (c.stopped) return@Thread
                    // fall back to the phone's voice for the rest
                    main.post {
                        if (!c.stopped) {
                            speakingId = speakingId ?: "x"
                            speakPhone(parts.drop(k).joinToString(" "))
                        }
                    }
                    return@Thread
                }
            }
            while (!c.stopped && !pl.idle) Thread.sleep(150)
            Thread.sleep(400)
            if (!c.stopped) main.post { if (player === pl) { pl.release(); player = null; finish() } }
        }.start()
    }

    private fun speakPhone(text: String) {
        val t = tts ?: return finish()
        if (!ready) {
            main.postDelayed({ if (speakingId != null) speakPhone(text) }, 600)
            return
        }
        val loc = localeOf(text)
        if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) t.language = loc
        t.setSpeechRate(Prefs.get("speech_rate", "1.0").toFloatOrNull() ?: 1f)
        val parts = chunks(text, 3500)
        for ((k, part) in parts.withIndex()) {
            t.speak(part, if (k == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (k == parts.size - 1) "u$k#last" else "u$k")
        }
    }
}

/** Records the microphone into a 16 kHz WAV file (voice messages). */
class Recorder(private val out: File) {
    @Volatile
    private var running = false
    private var thread: Thread? = null
    @Volatile
    var level = 0f
        private set
    @Volatile
    var seconds = 0.0
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6400))
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        running = true
        rec.startRecording()
        thread = Thread {
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                val buf = ShortArray(1600)
                val bytes = ByteArray(3200)
                var total = 0L
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        sum += v.toDouble() * v
                        bytes[2 * i] = (v and 0xFF).toByte()
                        bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    level = (Math.sqrt(sum / n) / 4000.0).toFloat().coerceIn(0f, 1f)
                    raf.write(bytes, 0, 2 * n)
                    total += 2 * n
                    seconds = total / 32000.0
                    if (seconds > 15 * 60) running = false
                }
                Media.writeWavHeader(raf, total, rate)
            }
            try {
                rec.stop()
            } catch (e: Exception) {
            }
            rec.release()
        }.apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(2000)
    }
}
