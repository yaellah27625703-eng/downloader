package com.vadila.downloader

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Colors of the current theme. */
class Palette(
    val dark: Boolean,
    val bg: Int,
    val surface: Int,
    val field: Int,
    val text: Int,
    val sub: Int,
    val accent: Int,
    val onAccent: Int,
    val accentSoft: Int,
    val divider: Int,
    val danger: Int,
    val success: Int,
)

object Ui {
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_CALM = "calm"

    val themeLabels = linkedMapOf(
        THEME_SYSTEM to "تلقائي",
        THEME_LIGHT to "فاتح",
        THEME_DARK to "ليلي",
        THEME_CALM to "هادئ",
    )

    private val LIGHT = Palette(
        dark = false,
        bg = 0xFFF3F5F9.toInt(), surface = 0xFFFFFFFF.toInt(), field = 0xFFEEF1F6.toInt(),
        text = 0xFF1B1F24.toInt(), sub = 0xFF5F6773.toInt(),
        accent = 0xFF1565C0.toInt(), onAccent = 0xFFFFFFFF.toInt(), accentSoft = 0xFFE2ECFA.toInt(),
        divider = 0xFFE1E5EB.toInt(), danger = 0xFFC62828.toInt(), success = 0xFF2E7D32.toInt(),
    )
    private val DARK = Palette(
        dark = true,
        bg = 0xFF0E1115.toInt(), surface = 0xFF181D24.toInt(), field = 0xFF222932.toInt(),
        text = 0xFFE8EAED.toInt(), sub = 0xFF9AA3AE.toInt(),
        accent = 0xFF6AAEFF.toInt(), onAccent = 0xFF0E1115.toInt(), accentSoft = 0xFF1E3350.toInt(),
        divider = 0xFF2A313A.toInt(), danger = 0xFFFF7A7A.toInt(), success = 0xFF6FCF73.toInt(),
    )
    /** Warm, low-contrast colours that are easy on the eyes. */
    private val CALM = Palette(
        dark = false,
        bg = 0xFFF2ECE1.toInt(), surface = 0xFFFAF6EF.toInt(), field = 0xFFEDE5D8.toInt(),
        text = 0xFF3A3328.toInt(), sub = 0xFF7A6F5E.toInt(),
        accent = 0xFF3E7C74.toInt(), onAccent = 0xFFFFFFFF.toInt(), accentSoft = 0xFFDCE9E4.toInt(),
        divider = 0xFFE3DACB.toInt(), danger = 0xFFB0463C.toInt(), success = 0xFF4E7D3A.toInt(),
    )

    lateinit var p: Palette
        private set

    fun themeKey(ctx: Context): String =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM

    fun setThemeKey(ctx: Context, key: String) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("theme", key).apply()
    }

    /** Must be called before setContentView. */
    fun apply(activity: Activity) {
        val key = themeKey(activity)
        val systemDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        p = when (key) {
            THEME_DARK -> DARK
            THEME_LIGHT -> LIGHT
            THEME_CALM -> CALM
            else -> if (systemDark) DARK else LIGHT
        }
        activity.setTheme(
            if (p.dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar
        )
        val w = activity.window
        w.statusBarColor = p.surface
        w.navigationBarColor = p.surface
        @Suppress("DEPRECATION")
        var flags = w.decorView.systemUiVisibility
        @Suppress("DEPRECATION")
        flags = if (p.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        @Suppress("DEPRECATION")
        w.decorView.systemUiVisibility = flags
    }

    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(ctx, 1), strokeColor)
        }

    /** Background with touch ripple. */
    fun pressable(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int? = null): Drawable {
        val ripple = if (p.dark) 0x33FFFFFF else 0x22000000
        val content = rounded(ctx, color, radiusDp, strokeColor)
        val mask = rounded(ctx, Color.WHITE, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(ripple), content, mask)
    }

    fun text(ctx: Context, size: Float, color: Int, bold: Boolean = false): TextView = TextView(ctx).apply {
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun iconButton(ctx: Context, icon: Int, tint: Int, desc: String, sizeDp: Int = 40): ImageButton =
        ImageButton(ctx).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            contentDescription = desc
            background = pressable(ctx, Color.TRANSPARENT, sizeDp / 2)
            val pad = dp(ctx, 8)
            setPadding(pad, pad, pad, pad)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        }

    /** Filled (primary) or tonal button. */
    fun button(ctx: Context, label: String, primary: Boolean, icon: Int? = null, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            val fg = if (primary) p.onAccent else p.accent
            setTextColor(fg)
            background = pressable(ctx, if (primary) p.accent else p.accentSoft, 14)
            setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0)
            minHeight = dp(ctx, 48)
            if (icon != null) {
                val d = ctx.getDrawable(icon)!!.mutate()
                d.setTint(fg)
                val s = dp(ctx, 20)
                d.setBounds(0, 0, s, s)
                setCompoundDrawablesRelative(d, null, null, null)
                compoundDrawablePadding = dp(ctx, 6)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun field(ctx: Context, hint: String): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(p.sub)
        setTextColor(p.text)
        textSize = 15f
        background = rounded(ctx, p.field, 14)
        val pad = dp(ctx, 12)
        setPadding(pad, pad, pad, pad)
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, p.surface, 18)
        val pad = dp(ctx, 14)
        setPadding(pad, pad, pad, pad)
        elevation = if (p.dark) 0f else dp(ctx, 1).toFloat()
    }

    fun progress(ctx: Context): ProgressBar =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = ColorStateList.valueOf(p.divider)
            indeterminateTintList = ColorStateList.valueOf(p.accent)
        }

    fun chip(ctx: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(if (selected) p.onAccent else p.text)
        background = pressable(ctx, if (selected) p.accent else p.field, 18)
        setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
        setOnClickListener { onClick() }
    }

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight)

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun iconFor(t: Task): Int {
        val name = t.fileName.lowercase()
        val mime = t.mime.lowercase()
        return when {
            t.quality == "mp3" || t.quality == "m4a" || mime.startsWith("audio") ||
                Regex("\\.(mp3|m4a|aac|ogg|opus|wav|flac)$").containsMatchIn(name) -> R.drawable.ic_music
            t.media || mime.startsWith("video") || Regex("\\.(mp4|mkv|webm|mov|3gp|m4v)$").containsMatchIn(name) -> R.drawable.ic_movie
            mime.startsWith("image") || Regex("\\.(jpg|jpeg|png|gif|webp|bmp)$").containsMatchIn(name) -> R.drawable.ic_image
            else -> R.drawable.ic_file
        }
    }
}
