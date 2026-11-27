package com.vadila.ai

import java.time.ZoneId
import java.time.ZonedDateTime

enum class Kind {
    /** Wrong / revoked key. */
    AUTH,
    /** Daily or monthly allowance used up (or no free allowance for this model). */
    QUOTA,
    /** Too many requests this minute. */
    RATE,
    /** The request was refused as invalid (often: this model cannot take this input). */
    BAD,
    /** Conversation or file larger than the model accepts. */
    TOO_LARGE,
    /** Model removed / not available to this key. */
    GONE,
    SERVER,
    NETWORK,
    BLOCKED,
    CANCELLED,
}

class ApiError(val kind: Kind, val http: Int, message: String, val waitMs: Long = 0) : Exception(message)

object Errors {
    private val TRY_AGAIN = Regex("""try again in (?:(\d+)h)?(?:(\d+)m(?!s))?(?:([0-9.]+)(ms|s))?""", RegexOption.IGNORE_CASE)
    private val RETRY_IN = Regex("""retry in ([0-9.]+)\s*(ms|s)""", RegexOption.IGNORE_CASE)
    private val RETRY_DELAY = Regex(""""retryDelay"\s*:\s*"([0-9.]+)s"""")
    private val WAIT_SECONDS = Regex("""wait (\d+) seconds""", RegexOption.IGNORE_CASE)

    /** Milliseconds the service asked us to wait, 0 when it did not say. */
    fun waitFrom(body: String, retryAfter: String?): Long {
        retryAfter?.trim()?.toDoubleOrNull()?.let { if (it > 0) return (it * 1000).toLong() }
        TRY_AGAIN.find(body)?.let { m ->
            val h = m.groupValues[1].toLongOrNull() ?: 0
            val min = m.groupValues[2].toLongOrNull() ?: 0
            val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
            val unitMs = if (m.groupValues[4] == "ms") 1.0 else 1000.0
            val t = h * 3_600_000 + min * 60_000 + (sec * unitMs).toLong()
            if (t > 0) return t
        }
        RETRY_IN.find(body)?.let { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
            return (v * if (m.groupValues[2] == "ms") 1.0 else 1000.0).toLong()
        }
        RETRY_DELAY.find(body)?.let { return ((it.groupValues[1].toDoubleOrNull() ?: 0.0) * 1000).toLong() }
        WAIT_SECONDS.find(body)?.let { return (it.groupValues[1].toLongOrNull() ?: 0) * 1000 }
        return 0
    }

    /** Milliseconds until the next midnight in California, when Google resets daily Gemini quotas. */
    fun untilPacificMidnight(now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("America/Los_Angeles"))): Long {
        val zone = ZoneId.of("America/Los_Angeles")
        val local = now.withZoneSameInstant(zone)
        val next = local.toLocalDate().plusDays(1).atStartOfDay(zone)
        return next.toInstant().toEpochMilli() - local.toInstant().toEpochMilli() + 60_000
    }

    fun classify(provider: String, code: Int, body: String, retryAfter: String?): ApiError {
        val low = body.lowercase()
        val msg = J.obj(body).let { it.str("error", "message") ?: (it["error"] as? String) ?: it.str("message") }
            ?: body.take(300).ifBlank { "HTTP $code" }
        val wait = waitFrom(body, retryAfter)
        val kind: Kind
        var w = wait
        when {
            code == 401 -> kind = Kind.AUTH
            code == 403 && (low.contains("quota") || low.contains("billing") || low.contains("credit")) -> kind = Kind.QUOTA
            code == 403 && (low.contains("no access to model") || low.contains("not available") || low.contains("not supported in your")) -> kind = Kind.GONE
            code == 403 -> kind = Kind.AUTH
            code == 402 -> { kind = Kind.QUOTA; if (w == 0L) w = 6 * 3_600_000L }
            code == 404 -> { kind = Kind.GONE; w = 3 * 86_400_000L }
            code == 413 || low.contains("tokens_limit_reached") || low.contains("request too large") ||
                low.contains("context_length_exceeded") || low.contains("maximum context length") ||
                low.contains("prompt is too long") || low.contains("input token count") -> kind = Kind.TOO_LARGE
            code == 400 && (low.contains("api key not valid") || low.contains("api_key_invalid") || low.contains("invalid api key")) -> kind = Kind.AUTH
            code == 400 && (low.contains("credit balance is too low") || low.contains("billing")) -> { kind = Kind.QUOTA; w = 12 * 3_600_000L }
            code == 400 && (low.contains("user location is not supported") || low.contains("not supported for")) -> { kind = Kind.GONE; w = 86_400_000L }
            code == 429 -> {
                val daily = low.contains("perday") || low.contains("per day") || low.contains("byday") ||
                    low.contains("rpd") || low.contains("daily") || low.contains("free-models-per-day")
                val noFree = Regex("""limit:\s*0\b""").containsMatchIn(low)
                val outOfMoney = low.contains("insufficient_quota") ||
                    (low.contains("exceeded your current quota, please check your plan") && !low.contains("free_tier")) ||
                    (low.contains("insufficient") && low.contains("credit"))
                when {
                    noFree -> { kind = Kind.QUOTA; w = if (provider == "gemini") untilPacificMidnight() else 86_400_000L }
                    daily -> {
                        kind = Kind.QUOTA
                        w = if (provider == "gemini") untilPacificMidnight() else maxOf(wait, 3_600_000L)
                    }
                    outOfMoney -> { kind = Kind.QUOTA; w = 6 * 3_600_000L }
                    else -> { kind = Kind.RATE; if (w == 0L) w = 30_000 }
                }
            }
            code == 529 || code >= 500 -> { kind = Kind.SERVER; if (w == 0L) w = 20_000 }
            else -> kind = Kind.BAD
        }
        return ApiError(kind, code, msg, w)
    }

    /** A short explanation in Arabic for the person using the app. */
    fun friendly(e: Throwable): String {
        if (e is ApiError) {
            return when (e.kind) {
                Kind.AUTH -> "المفتاح غير صالح أو لا يملك الصلاحية — راجعه في الإعدادات."
                Kind.QUOTA -> "انتهت الحصة المتاحة لهذا النموذج حاليًا."
                Kind.RATE -> "طلبات كثيرة في وقت قصير — سيُعاد المحاولة بعد قليل."
                Kind.TOO_LARGE -> "المحادثة أو المرفق أكبر من الحدّ الذي يقبله هذا النموذج."
                Kind.GONE -> "هذا النموذج غير متاح لمفتاحك."
                Kind.SERVER -> "الخدمة مشغولة أو متعطلة مؤقتًا."
                Kind.NETWORK -> "تعذّر الاتصال — تحقق من الإنترنت."
                Kind.BLOCKED -> e.message ?: "رُفض الطلب."
                Kind.CANCELLED -> "أُوقف."
                Kind.BAD -> "رفضت الخدمة الطلب: " + (e.message ?: "").take(200)
            }
        }
        return when (e) {
            is java.net.UnknownHostException -> "لا يوجد اتصال بالإنترنت."
            is java.net.SocketTimeoutException -> "انتهت مهلة الاتصال."
            is java.io.IOException -> "تعذّر الاتصال: " + (e.message ?: "")
            else -> e.message ?: e.toString()
        }
    }
}
