package com.vadila.ai

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URLEncoder

/**
 * A real-time voice conversation with Gemini (Live API): the microphone is streamed to the model and its
 * spoken answer is played back while it is generated. The person can interrupt it at any moment.
 */
class LiveCall(private val ctx: Context, private val listener: Listener) {
    interface Listener {
        fun onState(text: String, connected: Boolean)
        fun onUser(text: String, final: Boolean)
        fun onModel(text: String, final: Boolean)
        fun onEnded(error: String?)
    }

    val MODELS = listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025")

    private var ws: WebSocket? = null
    private var player: PcmPlayer? = null
    private var mic: Thread? = null
    @Volatile
    private var running = false
    @Volatile
    private var ready = false
    @Volatile
    var muted = false
    @Volatile
    var micLevel = 0f
        private set
    val outLevel get() = player?.level ?: 0f
    private var modelIndex = 0
    private var resumeHandle: String? = null
    private var reconnects = 0
    private val userText = StringBuilder()
    private val modelText = StringBuilder()
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var speaker = true
    @Volatile
    private var lastModelAudio = 0L

    /** Finished turns of the conversation: ("user"|"assistant", text). */
    val transcript = ArrayList<Pair<String, String>>()

    private fun models(): List<String> {
        val chosen = Prefs.get("model_live")
        val tagged = Prefs.tagged("gemini", "live").sortedBy { val i = MODELS.indexOf(it); if (i < 0) 50 else i }
        return (listOf(chosen).filter { it.isNotBlank() } + tagged + MODELS).distinct()
    }

    fun start() {
        running = true
        setupAudioRoute(true)
        connect()
    }

    private fun connect() {
        val list = models()
        if (modelIndex >= list.size) {
            end("تعذّر الاتصال بنموذج المكالمات.")
            return
        }
        val model = list[modelIndex]
        listener.onState("يتصل…", false)
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" +
            URLEncoder.encode(Prefs.key("gemini"), "UTF-8")
        val req = Request.Builder().url(url).build()
        ws = Net.ws.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val voice = Prefs.get("call_voice", Prefs.get("voice_name", "Kore"))
                val sys = Prefs.system() + "\n\nأنت الآن في مكالمة صوتية مباشرة: تكلّم بجمل قصيرة طبيعية ودودة كأنك إنسان في مكالمة هاتفية، " +
                    "بلغة المتصل نفسها ولهجته إن أمكن، ولا تستعمل تنسيق Markdown أو رموزًا."
                val setup = J.o(
                    "model" to "models/$model",
                    "generationConfig" to J.o(
                        "responseModalities" to J.a("AUDIO"),
                        "speechConfig" to J.o("voiceConfig" to J.o("prebuiltVoiceConfig" to J.o("voiceName" to voice))),
                    ),
                    "systemInstruction" to J.o("parts" to J.a(J.o("text" to sys))),
                    "inputAudioTranscription" to J.o(),
                    "outputAudioTranscription" to J.o(),
                    "contextWindowCompression" to J.o("slidingWindow" to J.o()),
                    "sessionResumption" to (resumeHandle?.let { J.o("handle" to it) } ?: J.o()),
                )
                webSocket.send(J.write(J.o("setup" to setup)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val body = try {
                    response?.body?.string() ?: ""
                } catch (e: Exception) {
                    ""
                }
                closed(response?.code ?: -1, (t.message ?: "") + " " + body)
            }
        })
    }

    private fun closed(code: Int, reason: String) {
        if (!running) return
        val wasReady = ready
        ready = false
        val low = reason.lowercase()
        if (!wasReady) {
            // the model was refused: try the next one, unless the key itself is the problem
            if (low.contains("api key") || low.contains("permission") || code == 401 || code == 403) {
                end("مفتاح Gemini غير صالح للمكالمات.")
                return
            }
            if (low.contains("quota") || low.contains("exhausted") || code == 429) {
                Router.coolDown("gemini:" + models().getOrNull(modelIndex), ApiError(Kind.QUOTA, 429, reason, Errors.untilPacificMidnight()))
            }
            modelIndex++
            connect()
            return
        }
        // the server ended the session (time limit) — resume it
        if (reconnects < 20) {
            reconnects++
            listener.onState("يعيد الاتصال…", false)
            connect()
        } else end(null)
    }

    private fun handle(raw: String) {
        val j = try {
            J.parse(raw)
        } catch (e: Exception) {
            return
        }
        if (j.at("setupComplete") != null) {
            ready = true
            listener.onState(if (muted) "الميكروفون مكتوم" else "تكلّم… أنا أسمعك", true)
            if (player == null) player = PcmPlayer(24000, voiceCall = true)
            if (mic == null) startMic()
            return
        }
        j.str("sessionResumptionUpdate", "newHandle")?.let { if (j.bool("sessionResumptionUpdate", "resumable") != false) resumeHandle = it }
        if (j.at("goAway") != null) {
            listener.onState("يجدّد الاتصال…", true)
        }
        val sc = j.at("serverContent") ?: return
        if (sc.bool("interrupted") == true) {
            player?.flush()
            finishModel()
        }
        sc.str("inputTranscription", "text")?.let {
            userText.append(it)
            listener.onUser(userText.toString().trim(), false)
        }
        sc.str("outputTranscription", "text")?.let {
            if (userText.isNotEmpty()) finishUser()
            modelText.append(it)
            listener.onModel(modelText.toString().trim(), false)
        }
        for (part in sc.list("modelTurn", "parts")) {
            val data = part.str("inlineData", "data") ?: continue
            if (userText.isNotEmpty()) finishUser()
            lastModelAudio = System.currentTimeMillis()
            player?.write(Net.unb64(data))
        }
        if (sc.bool("turnComplete") == true) {
            finishUser()
            finishModel()
        }
    }

    private fun finishUser() {
        val t = userText.toString().trim()
        if (t.isNotEmpty()) {
            transcript.add("user" to t)
            listener.onUser(t, true)
        }
        userText.setLength(0)
    }

    private fun finishModel() {
        val t = modelText.toString().trim()
        if (t.isNotEmpty()) {
            transcript.add("assistant" to t)
            listener.onModel(t, true)
        }
        modelText.setLength(0)
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        mic = Thread {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 4, 12800))
            } catch (e: Exception) {
                end("تعذّر فتح الميكروفون.")
                return@Thread
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                end("تعذّر فتح الميكروفون — اسمح للتطبيق باستعماله.")
                return@Thread
            }
            val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null
            val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null
            rec.startRecording()
            val buf = ShortArray(640) // 40 ms
            val bytes = ByteArray(1280)
            val halfDuplex = Prefs.bool("call_half_duplex", false)
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
                micLevel = (Math.sqrt(sum / n) / 3000.0).toFloat().coerceIn(0f, 1f)
                val speaking = player?.let { !it.idle || System.currentTimeMillis() - lastModelAudio < 300 } ?: false
                if (!ready || muted || (halfDuplex && speaking)) continue
                val msg = J.o("realtimeInput" to J.o("audio" to J.o("data" to Net.b64(bytes.copyOf(2 * n)), "mimeType" to "audio/pcm;rate=16000")))
                ws?.send(J.write(msg))
            }
            try {
                rec.stop()
            } catch (e: Exception) {
            }
            aec?.release()
            ns?.release()
            rec.release()
        }.apply { start() }
    }

    /** Sends a camera frame (JPEG) so the model can see. */
    fun sendFrame(jpeg: ByteArray) {
        if (!ready) return
        ws?.send(J.write(J.o("realtimeInput" to J.o("video" to J.o("data" to Net.b64(jpeg), "mimeType" to "image/jpeg")))))
    }

    fun sendText(text: String) {
        if (!ready) return
        ws?.send(J.write(J.o("realtimeInput" to J.o("text" to text))))
    }

    fun setSpeaker(on: Boolean) {
        speaker = on
        setupAudioRoute(true)
    }

    val speakerOn get() = speaker

    private fun setupAudioRoute(active: Boolean) {
        try {
            if (active) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val want = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    val headset = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    }
                    val dev = headset ?: am.availableCommunicationDevices.firstOrNull { it.type == want }
                    if (dev != null) am.setCommunicationDevice(dev)
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = speaker
                }
            } else {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                }
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {
        }
    }

    private fun end(error: String?) {
        if (!running) return
        running = false
        ready = false
        finishUser()
        finishModel()
        try {
            ws?.close(1000, null)
        } catch (e: Exception) {
        }
        ws = null
        mic?.join(500)
        mic = null
        player?.release()
        player = null
        setupAudioRoute(false)
        listener.onEnded(error)
    }

    fun stop() = end(null)
}
