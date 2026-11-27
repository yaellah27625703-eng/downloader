package com.vadila.ai

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    lateinit var sp: SharedPreferences
        private set

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("ai", Context.MODE_PRIVATE)
    }

    fun get(k: String, def: String = ""): String = sp.getString(k, def) ?: def
    fun set(k: String, v: String) = sp.edit().putString(k, v).apply()
    fun bool(k: String, def: Boolean = false): Boolean = sp.getBoolean(k, def)
    fun setBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    fun long(k: String, def: Long = 0): Long = sp.getLong(k, def)
    fun setLong(k: String, v: Long) = sp.edit().putLong(k, v).apply()
    fun remove(k: String) = sp.edit().remove(k).apply()

    fun key(provider: String): String = get("key_$provider").trim()
    fun hasKey(provider: String): Boolean = when (provider) {
        "free" -> !bool("free_off", false)
        "custom" -> get("base_custom").isNotBlank()
        "device" -> OnDevice.installedModels().isNotEmpty()
        else -> key(provider).isNotEmpty()
    }

    /** Model list fetched from a provider (most useful first). */
    fun models(provider: String): List<String> =
        J.parse(get("models_$provider", "[]").ifBlank { "[]" }).list().mapNotNull { it as? String }

    fun setModels(provider: String, ids: List<String>) = set("models_$provider", J.write(ids))

    /** Extra per-model facts gathered from model lists (e.g. "vision"). */
    fun tagged(provider: String, tag: String): Set<String> =
        J.parse(get("tag_${tag}_$provider", "[]").ifBlank { "[]" }).list().mapNotNull { it as? String }.toSet()

    fun setTagged(provider: String, tag: String, ids: Collection<String>) = set("tag_${tag}_$provider", J.write(ids.toList()))

    val DEFAULT_SYSTEM = """
أنت «Vadila AI»، مساعد ذكي متقدم ودقيق جدًا يعمل داخل تطبيق أندرويد.
- أجب بلغة المستخدم نفسها (إن كتب بالعربية فأجب بعربية فصيحة واضحة).
- الدقة أولًا: لا تخترع معلومات أو مصادر أو أرقامًا. إن لم تكن متأكدًا فقل ذلك صراحة، وفرّق بين الحقيقة والرأي.
- عند تحليل الصور والفيديو والصوت والمستندات صِف ما تراه بدقة ثم أجب عن السؤال.
- نسّق الإجابات بـ Markdown (عناوين قصيرة، قوائم، جداول عند الحاجة، كتل كود).
- عند طلب برنامج أو تطبيق: اكتب مشروعًا كاملًا جاهزًا للبناء، وقبل كل ملف اكتب سطرًا بصيغة `FILE: المسار/اسم_الملف` ثم الملف كاملًا داخل كتلة كود، بلا اختصار ولا «...».
  • أندرويد: Kotlin مع Gradle Kotlin DSL (AGP 8.7.3، Kotlin 2.0.21، compileSdk 35، minSdk 24، Java 17) دون ملفات gradle-wrapper الثنائية؛
    واكتب settings.gradle.kts كاملًا (pluginManagement وdependencyResolutionManagement مع google() وmavenCentral())، وgradle.properties فيه android.useAndroidX=true.
  • لعدة أنظمة (أندرويد وآيفون وويندوز ولينكس): Flutter؛ اكتب pubspec.yaml ومجلد lib/ فقط (مجلدات الأنظمة تُولَّد تلقائيًا عند البناء).
  • أدوات سطح المكتب: Python بملف main.py وrequirements.txt. • المواقع: index.html مع CSS وJavaScript.
- اجعل الإجابة متناسبة مع السؤال: قصيرة للأسئلة البسيطة، ومفصلة للمهام الكبيرة.
""".trim()

    fun system(): String = get("system_prompt", "").ifBlank { DEFAULT_SYSTEM }
}

/** A service the app can talk to. */
class ProviderDef(
    val id: String,
    val name: String,
    /** claude | gpt | gemini | other — used by the brain chips. */
    val family: String,
    val api: Int,
    val base: String,
    val keyUrl: String,
    val free: Boolean,
    val hint: String,
    val defaults: List<String>,
    val keyHint: String = "المفتاح",
) {
    companion object {
        const val GEMINI = 1
        const val CLAUDE = 2
        const val OAI = 3
        const val DEVICE = 4
    }
}

object Catalog {
    val providers = listOf(
        ProviderDef(
            "gemini", "Google Gemini", "gemini", ProviderDef.GEMINI, "https://generativelanguage.googleapis.com/v1beta",
            "https://aistudio.google.com/apikey", true,
            "مجاني بحصة يومية. يفهم الصور والفيديو والصوت وملفات PDF، ويُجري المكالمات الصوتية الطبيعية بكل اللغات.",
            listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3-flash-preview", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash", "gemini-2.5-flash-lite"),
        ),
        ProviderDef(
            "anthropic", "Claude (Anthropic)", "claude", ProviderDef.CLAUDE, "https://api.anthropic.com/v1",
            "https://console.anthropic.com/settings/keys", false,
            "مدفوع حسب الاستعمال (منفصل عن اشتراك claude.ai). الأقوى في البرمجة والكتابة والتحليل.",
            listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5"),
        ),
        ProviderDef(
            "openai", "ChatGPT (OpenAI)", "gpt", ProviderDef.OAI, "https://api.openai.com/v1",
            "https://platform.openai.com/api-keys", false,
            "مدفوع حسب الاستعمال (منفصل عن اشتراك ChatGPT Plus).",
            listOf("gpt-5", "gpt-5-mini"),
        ),
        ProviderDef(
            "github", "GitHub Models", "gpt", ProviderDef.OAI, "https://models.github.ai/inference",
            "https://github.com/settings/tokens/new?scopes=repo,workflow&description=Vadila%20AI", true,
            "مجاني بحسابك في GitHub: نماذج ChatGPT بحصة يومية صغيرة، والمفتاح نفسه يبني تطبيقاتك تلقائيًا.",
            listOf("openai/gpt-4.1", "openai/gpt-4o", "openai/gpt-4.1-mini"),
            keyHint = "رمز GitHub (ghp_…)",
        ),
        ProviderDef(
            "groq", "Groq", "other", ProviderDef.OAI, "https://api.groq.com/openai/v1",
            "https://console.groq.com/keys", true,
            "مجاني وسريع جدًا بنماذج مفتوحة المصدر، ويبحث في الويب مجانًا، ويحوّل الصوت إلى نص.",
            listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "meta-llama/llama-4-scout-17b-16e-instruct"),
        ),
        ProviderDef(
            "mistral", "Mistral", "other", ProviderDef.OAI, "https://api.mistral.ai/v1",
            "https://console.mistral.ai/api-keys", true,
            "خطة «Experiment» مجانية بحدود سخية.",
            listOf("mistral-large-latest", "mistral-medium-latest", "pixtral-large-latest"),
        ),
        ProviderDef(
            "openrouter", "OpenRouter", "other", ProviderDef.OAI, "https://openrouter.ai/api/v1",
            "https://openrouter.ai/keys", true,
            "عشرات النماذج المفتوحة المصدر المجانية (:free).",
            emptyList(),
        ),
        ProviderDef(
            "cerebras", "Cerebras", "other", ProviderDef.OAI, "https://api.cerebras.ai/v1",
            "https://cloud.cerebras.ai", true,
            "مجاني وسريع بنماذج مفتوحة المصدر.",
            listOf("gpt-oss-120b", "llama-3.3-70b"),
        ),
        ProviderDef(
            "deepseek", "DeepSeek", "other", ProviderDef.OAI, "https://api.deepseek.com/v1",
            "https://platform.deepseek.com/api_keys", false,
            "مدفوع لكنه رخيص جدًا.",
            listOf("deepseek-chat", "deepseek-reasoner"),
        ),
        ProviderDef(
            "xai", "Grok (xAI)", "other", ProviderDef.OAI, "https://api.x.ai/v1",
            "https://console.x.ai", false,
            "مدفوع.",
            listOf("grok-4"),
        ),
        ProviderDef(
            "pollinations", "Pollinations", "other", ProviderDef.OAI, "https://gen.pollinations.ai/v1",
            "https://enter.pollinations.ai", true,
            "منصة مفتوحة المصدر؛ مفتاحها المجاني يتيح نماذج إضافية.",
            listOf("openai", "mistral"),
        ),
        ProviderDef(
            "custom", "خادمك الخاص (Ollama / LM Studio)", "other", ProviderDef.OAI, "",
            "https://ollama.com/download", true,
            "شغّل نموذجًا مفتوح المصدر على حاسوبك (مع كرت الشاشة) واستعمله بلا حدود عبر شبكة البيت. اكتب العنوان مثل http://192.168.1.5:11434/v1",
            emptyList(),
            keyHint = "المفتاح (اختياري)",
        ),
        ProviderDef(
            "device", "داخل الهاتف (بلا إنترنت)", "other", ProviderDef.DEVICE, "",
            "", true,
            "نموذج Gemma 4 من Google يعمل داخل هاتفك دون إنترنت ودون مفتاح أو حساب، ويفهم الصور والصوت.",
            listOf("gemma-4-e2b"),
        ),
        ProviderDef(
            "free", "مجاني بلا مفتاح", "other", ProviderDef.OAI, "https://text.pollinations.ai/openai",
            "", true,
            "نموذج مفتوح المصدر صغير يعمل فورًا دون تسجيل (أبطأ وأضعف). يُستعمل كآخر احتياط.",
            listOf("openai-fast", "openai"),
        ),
    )

    fun byId(id: String): ProviderDef? = providers.firstOrNull { it.id == id }

    fun base(p: ProviderDef): String = if (p.id == "custom") Prefs.get("base_custom").trim().trimEnd('/') else p.base

    /** Preferred model first, then the defaults, then whatever was fetched. */
    fun modelsFor(p: ProviderDef, max: Int = 4): List<String> {
        if (p.id == "device") {
            val first = OnDevice.preferred()
            return (listOfNotNull(first) + OnDevice.installedModels()).distinct().map { it.id }.take(max)
        }
        val out = LinkedHashSet<String>()
        Prefs.get("model_${p.id}").takeIf { it.isNotBlank() }?.let { out.add(it) }
        val fetched = Prefs.models(p.id)
        if (fetched.isNotEmpty()) {
            for (d in p.defaults) if (d in fetched) out.add(d)
            for (f in fetched) out.add(f)
        } else out.addAll(p.defaults)
        return out.take(max)
    }

    // ---- capabilities -------------------------------------------------------------------
    const val VISION = 1
    const val PDF = 2
    const val AUDIO = 4
    const val VIDEO = 8
    const val SEARCH = 16

    fun caps(p: ProviderDef, model: String): Int {
        val m = model.lowercase()
        return when (p.id) {
            "gemini" -> VISION or PDF or AUDIO or VIDEO or SEARCH
            "anthropic" -> VISION or PDF or SEARCH
            "device" -> if (OnDevice.byId(model)?.multimodal == true) VISION or PDF or AUDIO or VIDEO else 0
            "openai" -> if (m.startsWith("gpt-") || m.startsWith("o") || m.contains("chat")) VISION or PDF else 0
            "github" -> if (m in Prefs.tagged("github", "vision") || m.contains("gpt-4o") || m.contains("gpt-4.1") ||
                m.contains("gpt-5") || m.contains("llama-4") || m.contains("multimodal") || m.contains("vision")) VISION else 0
            "groq" -> (if (m.contains("llama-4") || m.contains("vision") || m.contains("scout") || m.contains("maverick")) VISION else 0) or
                (if (m.contains("compound")) SEARCH else 0)
            "mistral" -> if (m.contains("pixtral") || m.contains("medium") || m.contains("small") || m.contains("large-2511") || m.contains("large-3")) VISION else 0
            "openrouter" -> if (m in Prefs.tagged("openrouter", "vision")) VISION else 0
            "xai" -> if (m.contains("grok-4") || m.contains("vision")) VISION else 0
            "pollinations" -> if (m.startsWith("openai") || m.contains("gemini") || m.contains("claude")) VISION else 0
            "custom" -> if (m.contains("llava") || m.contains("vision") || m.contains("-vl") || m.contains("vl:") || m.contains("gemma3") ||
                m.contains("gemma-3") || m.contains("gemma4") || m.contains("minicpm-v") || m.contains("qwen2.5vl") || m.contains("llama3.2-vision")) VISION else 0
            else -> 0
        }
    }

    /** Short display name of a model. */
    fun pretty(model: String): String {
        OnDevice.byId(model)?.let { return it.name + " (بلا إنترنت)" }
        var m = model.substringAfterLast('/').removeSuffix(":free")
        m = m.replace("-latest", "").replace("-preview", " (تجريبي)")
        return when {
            m.startsWith("gemini-") -> "Gemini " + m.removePrefix("gemini-").replace("-", " ").replaceFirstChar { it.uppercase() }
                .replace("flash", "Flash").replace("lite", "Lite").replace("pro", "Pro")
            m.startsWith("claude-") -> "Claude " + m.removePrefix("claude-").split('-').let { parts ->
                val name = parts.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: ""
                val ver = parts.drop(1).takeWhile { it.all(Char::isDigit) && it.length <= 2 }.joinToString(".")
                "$name $ver".trim()
            }
            m.startsWith("gpt-") -> "GPT-" + m.removePrefix("gpt-")
            m == "openai-fast" || m == "openai" -> "GPT-OSS"
            else -> m
        }
    }
}
