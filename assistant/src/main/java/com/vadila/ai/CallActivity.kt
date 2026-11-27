package com.vadila.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream

/** Full-screen voice call with the assistant (Gemini Live; a simpler mode works without a Gemini key). */
class CallActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var live: LiveCall? = null
    private lateinit var orb: Orb
    private lateinit var stateTv: TextView
    private lateinit var userTv: TextView
    private lateinit var modelTv: TextView
    private lateinit var muteBtn: ImageButton
    private lateinit var speakerBtn: ImageButton
    private lateinit var camBtn: ImageButton
    private lateinit var preview: TextureView
    private var ended = false
    private val transcript = ArrayList<Pair<String, String>>()

    // simple mode (no Gemini key)
    private var classic = false
    private var dictation: Dictation? = null
    private var tts: TextToSpeech? = null
    private var classicBusy = false
    private var classicCancel: Cancel? = null

    @Suppress("DEPRECATION")
    private var camera: android.hardware.Camera? = null
    private var lastFrame = 0L
    private var camFacing = 0

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Ui.apply(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFF0B0A14.toInt()
        window.navigationBarColor = 0xFF0B0A14.toInt()
        build()
        classic = !Prefs.hasKey("gemini")
        if (classic) startClassic() else startLive()
        tick()
    }

    private fun build() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF0B0A14.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(36), dp(20), dp(28))
        }
        col.addView(TextView(this).apply {
            text = "Vadila AI"
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        stateTv = TextView(this).apply {
            setTextColor(0xFFB9B4D6.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        col.addView(stateTv)
        val mid = FrameLayout(this)
        orb = Orb(this)
        mid.addView(orb, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        preview = TextureView(this).apply { visibility = View.GONE }
        mid.addView(preview, FrameLayout.LayoutParams(dp(210), dp(280), Gravity.CENTER))
        preview.setOnClickListener { switchCamera() }
        col.addView(mid, Ui.lp(Ui.MATCH, 0, 1f))
        userTv = TextView(this).apply {
            setTextColor(0xFF9C97B8.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 3
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        modelTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            maxLines = 5
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPadding(0, dp(8), 0, dp(18))
        }
        col.addView(userTv, Ui.lp(Ui.MATCH, Ui.WRAP))
        col.addView(modelTv, Ui.lp(Ui.MATCH, Ui.WRAP))
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun round(icon: Int, bg: Int, desc: String, size: Int = 62, onClick: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = Ui.pressable(this@CallActivity, bg, size / 2)
            contentDescription = desc
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { onClick() }
        }
        muteBtn = round(R.drawable.ic_mic, 0xFF2A2740.toInt(), "كتم الميكروفون") { toggleMute() }
        speakerBtn = round(R.drawable.ic_speaker, 0xFF2A2740.toInt(), "مكبر الصوت") { toggleSpeaker() }
        camBtn = round(R.drawable.ic_video, 0xFF2A2740.toInt(), "الكاميرا: دع الذكاء يرى") { toggleCamera() }
        val endBtn = round(R.drawable.ic_call_end, 0xFFE53935.toInt(), "إنهاء المكالمة", 72) { finishCall() }
        for (b in listOf(muteBtn, speakerBtn, camBtn)) bar.addView(b, Ui.lp(dp(62), dp(62)).apply { marginStart = dp(8); marginEnd = dp(8) })
        bar.addView(endBtn, Ui.lp(dp(72), dp(72)).apply { marginStart = dp(8) })
        col.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        setContentView(root)
    }

    private fun tick() {
        if (ended) return
        val mic = live?.micLevel ?: if (dictation?.active == true) 0.25f else 0f
        val out = live?.outLevel ?: if (tts?.isSpeaking == true) 0.5f else 0f
        orb.update(mic, out)
        ui.postDelayed({ tick() }, 50)
    }

    // ---- Gemini Live -------------------------------------------------------------------------------
    private fun startLive() {
        val l = LiveCall(this, object : LiveCall.Listener {
            override fun onState(text: String, connected: Boolean) = ui.post { stateTv.text = text }.let { }
            override fun onUser(text: String, final: Boolean) = ui.post { userTv.text = text }.let { }
            override fun onModel(text: String, final: Boolean) = ui.post {
                modelTv.text = text
                if (final) userTv.text = ""
            }.let { }

            override fun onEnded(error: String?) = ui.post {
                if (error != null && !ended) {
                    if (live?.transcript?.isEmpty() != false) {
                        // live calls are not available: continue in the simple mode
                        stateTv.text = error + "\nيتحول إلى المكالمة البسيطة…"
                        live = null
                        classic = true
                        ui.postDelayed({ if (!ended) startClassic() }, 1200)
                    } else {
                        stateTv.text = error
                    }
                }
            }.let { }
        })
        live = l
        l.start()
    }

    private fun toggleMute() {
        val l = live
        if (l != null) {
            l.muted = !l.muted
            muteBtn.setImageResource(if (l.muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            stateTv.text = if (l.muted) "الميكروفون مكتوم" else "تكلّم… أنا أسمعك"
        } else {
            classicMuted = !classicMuted
            muteBtn.setImageResource(if (classicMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            if (classicMuted) dictation?.stop() else listenClassic()
        }
    }

    private fun toggleSpeaker() {
        val l = live ?: return
        l.setSpeaker(!l.speakerOn)
        speakerBtn.setImageResource(if (l.speakerOn) R.drawable.ic_speaker else R.drawable.ic_speaker_off)
        Ui.toast(this, if (l.speakerOn) "مكبر الصوت" else "سمّاعة الأذن")
    }

    // ---- camera (the model sees what you show it) ----------------------------------------------------
    private fun toggleCamera() {
        if (live == null) {
            Ui.toast(this, "الكاميرا تعمل في مكالمات Gemini (أضف مفتاح Gemini المجاني).")
            return
        }
        if (camera != null) {
            stopCamera()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 5)
            return
        }
        preview.visibility = View.VISIBLE
        orb.visibility = View.INVISIBLE
        if (preview.isAvailable) startCamera(preview.surfaceTexture!!) else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = startCamera(surface)
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleCamera()
    }

    @Suppress("DEPRECATION")
    private fun startCamera(tex: SurfaceTexture) {
        try {
            val n = android.hardware.Camera.getNumberOfCameras()
            var id = 0
            val info = android.hardware.Camera.CameraInfo()
            for (i in 0 until n) {
                android.hardware.Camera.getCameraInfo(i, info)
                if (info.facing == camFacing) {
                    id = i
                    break
                }
            }
            android.hardware.Camera.getCameraInfo(id, info)
            val cam = android.hardware.Camera.open(id)
            val params = cam.parameters
            val size = params.supportedPreviewSizes.minByOrNull { Math.abs(it.width - 800) + Math.abs(it.height - 600) }!!
            params.setPreviewSize(size.width, size.height)
            if (params.supportedFocusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.focusMode = android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            }
            cam.parameters = params
            val rotation = if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - info.orientation % 360) % 360 else info.orientation
            cam.setDisplayOrientation(rotation)
            cam.setPreviewTexture(tex)
            val w = size.width
            val h = size.height
            val frameRotation = info.orientation
            val mirror = info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT
            cam.setPreviewCallback { data, _ ->
                val now = System.currentTimeMillis()
                if (now - lastFrame < 1000 || data == null) return@setPreviewCallback
                lastFrame = now
                val copy = data.copyOf()
                Thread {
                    try {
                        val bos = ByteArrayOutputStream()
                        YuvImage(copy, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 75, bos)
                        val raw = bos.toByteArray()
                        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                        val m = Matrix().apply {
                            postRotate(frameRotation.toFloat())
                            if (mirror) postScale(-1f, 1f)
                        }
                        val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                        val out = ByteArrayOutputStream()
                        rot.compress(Bitmap.CompressFormat.JPEG, 72, out)
                        live?.sendFrame(out.toByteArray())
                        bmp.recycle()
                        if (rot != bmp) rot.recycle()
                    } catch (e: Exception) {
                    }
                }.start()
            }
            cam.startPreview()
            camera = cam
            camBtn.background = Ui.pressable(this, 0xFF5B3FD6.toInt(), 31)
            stateTv.text = "أرِه ما تريد — يرى ما تراه الكاميرا (اضغط الصورة لتبديل الكاميرا)"
        } catch (e: Exception) {
            Ui.toast(this, "تعذّر تشغيل الكاميرا")
            stopCamera()
        }
    }

    @Suppress("DEPRECATION")
    private fun stopCamera() {
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        } catch (e: Exception) {
        }
        camera = null
        preview.visibility = View.GONE
        orb.visibility = View.VISIBLE
        camBtn.background = Ui.pressable(this, 0xFF2A2740.toInt(), 31)
    }

    @Suppress("DEPRECATION")
    private fun switchCamera() {
        camFacing = if (camFacing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK) android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT
        else android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK
        val tex = preview.surfaceTexture ?: return
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        } catch (e: Exception) {
        }
        camera = null
        startCamera(tex)
    }

    // ---- simple mode: speech recognition → model → phone voice ------------------------------------
    private var classicMuted = false
    private val history = ArrayList<Turn>()

    private fun startClassic() {
        stateTv.text = "مكالمة بسيطة (لمكالمة طبيعية أضف مفتاح Gemini المجاني)"
        speakerBtn.visibility = View.GONE
        tts = TextToSpeech(applicationContext, { st ->
            if (st == TextToSpeech.SUCCESS) ui.post { listenClassic() }
        }, "com.google.android.tts")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "last") ui.post { classicBusy = false; listenClassic() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                ui.post { classicBusy = false; listenClassic() }
            }
        })
    }

    private fun listenClassic() {
        if (ended || classicBusy || classicMuted) return
        val d = dictation ?: Dictation(this, onText@{ text, final ->
            if (!final) {
                userTv.text = text
                return@onText
            }
            if (text.isBlank()) {
                ui.postDelayed({ listenClassic() }, 300)
                return@onText
            }
            userTv.text = text
            answerClassic(text)
        }, { active -> if (active) stateTv.text = "أسمعك…" }, { err ->
            if (err.isNotEmpty()) stateTv.text = err
            ui.postDelayed({ listenClassic() }, if (err.isEmpty()) 200 else 1500)
        }).also { dictation = it }
        d.start(continuous = true)
    }

    private fun answerClassic(text: String) {
        classicBusy = true
        stateTv.text = "يفكّر…"
        history.add(Turn("user", text, emptyList()))
        transcript.add("user" to text)
        val c = Cancel()
        classicCancel = c
        val sys = Prefs.system() + "\n\nأنت في مكالمة صوتية: أجب بجملتين أو ثلاث جمل قصيرة طبيعية بلغة المتصل، دون Markdown أو رموز."
        Thread {
            val reply = try {
                Router.run(Router.autoChain(), Req(history.takeLast(16), sys), c, {}, { _, _ -> }).text
            } catch (e: Exception) {
                if (c.stopped) return@Thread
                "عذرًا، " + (if (e is ApiError) Errors.friendly(e) else "حدث خطأ.")
            }
            ui.post {
                if (ended) return@post
                history.add(Turn("assistant", reply, emptyList()))
                transcript.add("assistant" to reply)
                modelTv.text = Md.speakable(reply)
                stateTv.text = "يتكلّم…"
                val t = tts
                if (t == null) {
                    classicBusy = false
                    listenClassic()
                    return@post
                }
                val clean = Md.speakable(reply)
                val loc = Speaker.localeOf(clean)
                if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) t.language = loc
                t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "last")
            }
        }.start()
    }

    // ---- ending -----------------------------------------------------------------------------------
    private fun finishCall() {
        if (ended) return
        ended = true
        stopCamera()
        live?.let { l ->
            l.stop()
            transcript.addAll(l.transcript)
        }
        dictation?.stop()
        classicCancel?.stop()
        tts?.stop()
        tts?.shutdown()
        if (transcript.isNotEmpty()) {
            val json = J.write(transcript.map { J.o("r" to it.first, "t" to it.second) })
            setResult(RESULT_OK, Intent().putExtra("transcript", json))
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = finishCall()

    override fun onDestroy() {
        super.onDestroy()
        if (!ended) finishCall()
    }

    /** The animated circle that breathes with the voices. */
    class Orb(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var mic = 0f
        private var out = 0f
        private var t = 0f

        fun update(micLevel: Float, outLevel: Float) {
            mic = mic * 0.6f + micLevel * 0.4f
            out = out * 0.6f + outLevel * 0.4f
            t += 0.05f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val base = minOf(width, height) * 0.22f
            val breathe = (Math.sin(t.toDouble()) * 0.03 + 1).toFloat()
            val r = base * breathe * (1f + out * 0.45f + mic * 0.25f)
            for (k in 3 downTo 1) {
                paint.shader = null
                paint.color = Color.argb((30 / k + out * 40).toInt().coerceIn(0, 255), 140, 110, 255)
                canvas.drawCircle(cx, cy, r * (1f + k * 0.22f * (0.6f + out + mic * 0.5f)), paint)
            }
            paint.shader = RadialGradient(cx, cy - r * 0.3f, r * 1.3f, intArrayOf(0xFFC7B8FF.toInt(), 0xFF6A4CE0.toInt(), 0xFF2B1F6B.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
}
