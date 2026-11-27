package com.vadila.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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
    val userBubble: Int,
    val codeBg: Int,
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
        bg = 0xFFF5F6FA.toInt(), surface = 0xFFFFFFFF.toInt(), field = 0xFFECEEF4.toInt(),
        text = 0xFF1B1D24.toInt(), sub = 0xFF626774.toInt(),
        accent = 0xFF5B3FD6.toInt(), onAccent = 0xFFFFFFFF.toInt(), accentSoft = 0xFFE9E4FB.toInt(),
        divider = 0xFFE2E4EC.toInt(), danger = 0xFFC62828.toInt(), success = 0xFF2E7D32.toInt(),
        userBubble = 0xFFE9E4FB.toInt(), codeBg = 0xFF1E1F29.toInt(),
    )
    private val DARK = Palette(
        dark = true,
        bg = 0xFF0F1015.toInt(), surface = 0xFF181A22.toInt(), field = 0xFF23252F.toInt(),
        text = 0xFFE9EAF0.toInt(), sub = 0xFF9EA2B0.toInt(),
        accent = 0xFFA996FF.toInt(), onAccent = 0xFF14121F.toInt(), accentSoft = 0xFF2C2547.toInt(),
        divider = 0xFF2A2C36.toInt(), danger = 0xFFFF7A7A.toInt(), success = 0xFF6FCF73.toInt(),
        userBubble = 0xFF2C2547.toInt(), codeBg = 0xFF0B0C10.toInt(),
    )
    private val CALM = Palette(
        dark = false,
        bg = 0xFFF2ECE1.toInt(), surface = 0xFFFAF6EF.toInt(), field = 0xFFEDE5D8.toInt(),
        text = 0xFF3A3328.toInt(), sub = 0xFF7A6F5E.toInt(),
        accent = 0xFF3E7C74.toInt(), onAccent = 0xFFFFFFFF.toInt(), accentSoft = 0xFFDCE9E4.toInt(),
        divider = 0xFFE3DACB.toInt(), danger = 0xFFB0463C.toInt(), success = 0xFF4E7D3A.toInt(),
        userBubble = 0xFFDCE9E4.toInt(), codeBg = 0xFF2B2A26.toInt(),
    )

    lateinit var p: Palette
        private set

    fun apply(activity: Activity) {
        val key = Prefs.get("theme", THEME_SYSTEM)
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

    fun iconButton(ctx: Context, icon: Int, tint: Int, desc: String, sizeDp: Int = 42, onClick: (() -> Unit)? = null): ImageButton =
        ImageButton(ctx).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            contentDescription = desc
            background = pressable(ctx, Color.TRANSPARENT, sizeDp / 2)
            val pad = dp(ctx, 9)
            setPadding(pad, pad, pad, pad)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
            if (onClick != null) setOnClickListener { onClick() }
        }

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
            minHeight = dp(ctx, 46)
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

    fun field(ctx: Context, hint: String, multiLine: Boolean = false): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(p.sub)
        setTextColor(p.text)
        textSize = 15f
        background = rounded(ctx, p.field, 14)
        val pad = dp(ctx, 12)
        setPadding(pad, pad, pad, pad)
        if (multiLine) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false
            gravity = Gravity.TOP or Gravity.START
        } else {
            isSingleLine = true
        }
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, p.surface, 18)
        val pad = dp(ctx, 14)
        setPadding(pad, pad, pad, pad)
        elevation = if (p.dark) 0f else dp(ctx, 1).toFloat()
    }

    fun progress(ctx: Context, horizontal: Boolean = true): ProgressBar =
        if (horizontal) ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = ColorStateList.valueOf(p.divider)
            indeterminateTintList = ColorStateList.valueOf(p.accent)
        } else ProgressBar(ctx).apply { indeterminateTintList = ColorStateList.valueOf(p.accent) }

    fun chip(ctx: Context, label: String, selected: Boolean, icon: Int? = null, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        val fg = if (selected) p.onAccent else p.text
        setTextColor(fg)
        background = pressable(ctx, if (selected) p.accent else p.field, 18)
        setPadding(dp(ctx, 13), dp(ctx, 7), dp(ctx, 13), dp(ctx, 7))
        if (icon != null) {
            val d = ctx.getDrawable(icon)!!.mutate()
            d.setTint(if (selected) p.onAccent else p.accent)
            val s = dp(ctx, 16)
            d.setBounds(0, 0, s, s)
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = dp(ctx, 5)
        }
        setOnClickListener { onClick() }
    }

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

    fun margins(v: View, s: Int, t: Int, e: Int, b: Int) {
        val ctx = v.context
        val l = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        l.marginStart = dp(ctx, s)
        l.topMargin = dp(ctx, t)
        l.marginEnd = dp(ctx, e)
        l.bottomMargin = dp(ctx, b)
        v.layoutParams = l
    }

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun copy(ctx: Context, text: String, label: String = "نص") {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(ctx, "نُسخ")
    }

    fun open(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(ctx, "تعذّر فتح الرابط")
        }
    }

    fun shareText(ctx: Context, text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(i, "مشاركة"))
    }

    /** A simple scrollable dialog that shows selectable text. */
    fun showText(ctx: Context, title: String, body: String) {
        val tv = TextView(ctx).apply {
            text = body
            setTextColor(p.text)
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        val sv = ScrollView(ctx).apply { addView(tv) }
        AlertDialog.Builder(ctx).setTitle(title).setView(sv)
            .setPositiveButton("نسخ الكل") { _, _ -> copy(ctx, body) }
            .setNegativeButton("إغلاق", null).show()
    }

    fun confirm(ctx: Context, title: String, msg: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(ctx).setTitle(title).setMessage(msg)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("إلغاء", null).show()
    }

    /** List picker; [checked] marks the current choice. */
    fun pick(ctx: Context, title: String, items: List<String>, checked: Int = -1, onPick: (Int) -> Unit) {
        val b = AlertDialog.Builder(ctx).setTitle(title)
        if (checked >= 0) b.setSingleChoiceItems(items.toTypedArray(), checked) { d, w -> d.dismiss(); onPick(w) }
        else b.setItems(items.toTypedArray()) { _, w -> onPick(w) }
        b.setNegativeButton("إلغاء", null).show()
    }

    fun ask(ctx: Context, title: String, hint: String, initial: String = "", multiLine: Boolean = false, onOk: (String) -> Unit) {
        val f = field(ctx, hint, multiLine).apply { setText(initial) }
        if (multiLine) f.minLines = 4
        val box = LinearLayout(ctx).apply {
            setPadding(dp(ctx, 18), dp(ctx, 6), dp(ctx, 18), 0)
            addView(f, lp(MATCH, WRAP))
        }
        AlertDialog.Builder(ctx).setTitle(title).setView(box)
            .setPositiveButton("موافق") { _, _ -> onOk(f.text.toString()) }
            .setNegativeButton("إلغاء", null).show()
    }
}
