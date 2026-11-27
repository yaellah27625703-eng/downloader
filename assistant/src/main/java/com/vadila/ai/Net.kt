package com.vadila.ai

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Lets the person stop a running request. */
class Cancel {
    @Volatile
    var stopped = false
        private set

    @Volatile
    var call: Call? = null

    fun stop() {
        stopped = true
        try {
            call?.cancel()
        } catch (e: Exception) {
        }
    }

    fun check() {
        if (stopped) throw ApiError(Kind.CANCELLED, 0, "أُوقف")
    }
}

object Net {
    val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(240, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** For WebSockets (live calls). */
    val ws: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun b64(f: File): String = Base64.getEncoder().encodeToString(f.readBytes())
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s.trim())

    fun jsonPost(url: String, headers: Map<String, String>, body: Any?): Request {
        val b = Request.Builder().url(url).post(J.write(body).toRequestBody(JSON))
        for ((k, v) in headers) b.header(k, v)
        return b.build()
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Request {
        val b = Request.Builder().url(url).get()
        for ((k, v) in headers) b.header(k, v)
        return b.build()
    }

    /** Runs a request and returns the response; throws [ApiError] for HTTP errors. Caller closes. */
    fun exec(req: Request, provider: String, cancel: Cancel? = null, c: OkHttpClient = client): Response {
        cancel?.check()
        val call = c.newCall(req)
        cancel?.call = call
        val resp = try {
            call.execute()
        } catch (e: IOException) {
            if (cancel?.stopped == true) throw ApiError(Kind.CANCELLED, 0, "أُوقف")
            throw ApiError(Kind.NETWORK, 0, e.message ?: e.toString())
        }
        if (!resp.isSuccessful) {
            val body = try {
                resp.body?.string() ?: ""
            } catch (e: Exception) {
                ""
            }
            resp.close()
            throw Errors.classify(provider, resp.code, body, resp.header("retry-after"))
        }
        return resp
    }

    /** GET/POST returning the body as text. */
    fun text(req: Request, provider: String, cancel: Cancel? = null): String =
        exec(req, provider, cancel).use { it.body?.string() ?: "" }

    /** Streams a server-sent-events response: [onEvent] gets (eventName, data) for each event. */
    fun sse(req: Request, provider: String, cancel: Cancel, onEvent: (String, String) -> Unit) {
        exec(req, provider, cancel).use { r ->
            val src = r.body?.source() ?: return
            val p = SseParser()
            while (true) {
                cancel.check()
                val line = try {
                    src.readUtf8Line()
                } catch (e: IOException) {
                    if (cancel.stopped) throw ApiError(Kind.CANCELLED, 0, "أُوقف")
                    throw ApiError(Kind.NETWORK, 0, e.message ?: "انقطع الاتصال")
                } ?: break
                p.line(line)?.let { onEvent(it.first, it.second) }
            }
            p.finish()?.let { onEvent(it.first, it.second) }
        }
    }
}
