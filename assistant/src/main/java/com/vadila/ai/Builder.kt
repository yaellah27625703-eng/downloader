package com.vadila.ai

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Turns an answer's code into a project: a ZIP on the phone, or a real build on GitHub Actions. */
object Builder {
    private const val API = "https://api.github.com"

    fun ghHeaders(): Map<String, String> = mapOf(
        "Authorization" to "Bearer " + Prefs.key("github"),
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
    )

    /** Files of the project: every file written so far in this chat up to [msg] (later versions win). */
    fun collect(chat: Chat, msg: Msg): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in chat.msgs) {
            if (m.role == "assistant") for ((path, code) in Md.files(m.text)) out[path] = code
            if (m === msg) break
        }
        return out
    }

    fun projectMenu(act: Activity, msg: Msg, chat: Chat, onFix: (String) -> Unit) {
        val files = collect(chat, msg)
        val items = listOf(
            "تنزيل المشروع كملف ZIP",
            "بناء على GitHub: APK / ويندوز / لينكس / آيفون / موقع",
            "عرض قائمة الملفات",
            "نسخ كل الملفات كنص",
        )
        Ui.pick(act, "المشروع (${files.size} ملف)", items) { k ->
            when (k) {
                0 -> zip(act, chat, files)
                1 -> build(act, chat, msg, files, onFix)
                2 -> Ui.showText(act, "ملفات المشروع", files.entries.joinToString("\n") { "• ${it.key}  (${it.value.lines().size} سطر)" })
                3 -> Ui.copy(act, files.entries.joinToString("\n\n") { "FILE: ${it.key}\n```\n${it.value}```" })
            }
        }
    }

    private fun slug(s: String): String {
        val ascii = s.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-')
        return ascii.ifBlank { "vadila-app" }.take(40)
    }

    private fun zip(act: Activity, chat: Chat, files: Map<String, String>) {
        val name = slug(chat.title).let { if (it == "vadila-app") "project-${System.currentTimeMillis() % 100000}" else it } + ".zip"
        val f = File(act.cacheDir, name)
        ZipOutputStream(FileOutputStream(f)).use { z ->
            for ((path, code) in files) {
                z.putNextEntry(ZipEntry(path))
                z.write(code.toByteArray())
                z.closeEntry()
            }
        }
        val uri = Media.saveToDownloads(act, f, "application/zip", name)
        Ui.toast(act, if (uri != null) "حُفظ في التنزيلات/Vadila AI/$name" else "تعذّر الحفظ")
    }

    // =============================================================================================
    // GitHub
    // =============================================================================================
    enum class Type(val label: String) {
        ANDROID("تطبيق أندرويد (APK)"),
        FLUTTER("Flutter: أندرويد + ويندوز + لينكس + آيفون"),
        PYTHON("برنامج Python: ويندوز + لينكس + ماك"),
        WEB("موقع ويب (GitHub Pages)"),
        CUSTOM("سير عمل خاص بالمشروع"),
        OTHER("رفع الكود فقط"),
    }

    fun detect(files: Map<String, String>): Type {
        val names = files.keys.map { it.lowercase() }
        return when {
            names.any { it.startsWith(".github/workflows/") } -> Type.CUSTOM
            names.any { it.endsWith("pubspec.yaml") } -> Type.FLUTTER
            names.any { it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") || it.endsWith("androidmanifest.xml") } -> Type.ANDROID
            names.any { it.endsWith(".py") } -> Type.PYTHON
            names.any { it.endsWith("index.html") } -> Type.WEB
            else -> Type.OTHER
        }
    }

    private fun gh(method: String, path: String, body: Any? = null): Any? {
        val url = if (path.startsWith("http")) path else API + path
        val b = okhttp3.Request.Builder().url(url)
        for ((k, v) in ghHeaders()) b.header(k, v)
        val rb = (if (body != null) J.write(body) else "{}").toRequestBody(Net.JSON)
        when (method) {
            "GET" -> b.get()
            "POST" -> b.post(rb)
            "PATCH" -> b.patch(rb)
            "PUT" -> b.put(rb)
        }
        val t = Net.text(b.build(), "github")
        return if (t.isBlank()) null else J.parse(t)
    }

    private fun build(act: Activity, chat: Chat, msg: Msg, files: LinkedHashMap<String, String>, onFix: (String) -> Unit) {
        if (!Prefs.hasKey("github")) {
            Ui.confirm(act, "يحتاج رمز GitHub", "لبناء التطبيق تلقائيًا أضف رمز GitHub المجاني (بصلاحيات repo وworkflow) في الإعدادات.", "فتح الإعدادات") {
                act.startActivityForResult(android.content.Intent(act, SettingsActivity::class.java).putExtra("provider", "github"), MainActivity.REQ_SETTINGS)
            }
            return
        }
        val type = detect(files)
        val existing = msg.repo ?: chat.msgs.lastOrNull { it.repo != null }?.repo
        if (existing != null) {
            BuildDialog(act, chat, msg, files, type, existing.substringAfter('/'), onFix).start()
            return
        }
        Ui.ask(act, "اسم المستودع على GitHub", "مثال: my-notes-app", slug(chat.title)) { name ->
            val n = slug(name)
            BuildDialog(act, chat, msg, files, type, n, onFix).start()
        }
    }

    private class BuildDialog(
        val act: Activity,
        val chat: Chat,
        val msg: Msg,
        val files: LinkedHashMap<String, String>,
        val type: Type,
        val repoName: String,
        val onFix: (String) -> Unit,
    ) {
        val ui = Handler(Looper.getMainLooper())
        val d = Dialog(act)
        val log = Ui.text(act, 14f, Ui.p.text)
        val buttons = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val bar = Ui.progress(act).apply { isIndeterminate = true }
        @Volatile
        var stopped = false

        fun dp(v: Int) = Ui.dp(act, v)

        fun start() {
            val p = Ui.p
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(p.surface)
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(18), dp(16), dp(18), dp(14))
            }
            box.addView(Ui.text(act, 18f, p.text, true).apply { text = "بناء: " + type.label })
            box.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8); bottomMargin = dp(8) })
            box.addView(ScrollView(act).apply { addView(log) }, Ui.lp(Ui.MATCH, dp(220)))
            box.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
            box.addView(Ui.button(act, "إغلاق", false) { stopped = true; d.dismiss() }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6) })
            d.setContentView(box)
            d.setOnDismissListener { stopped = true }
            d.window?.setLayout((act.resources.displayMetrics.widthPixels * 0.94).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            d.show()
            Thread { run() }.start()
        }

        fun say(s: String) = ui.post { log.append(s + "\n") }

        fun button(label: String, primary: Boolean, f: () -> Unit) = ui.post {
            buttons.addView(Ui.button(act, label, primary) { f() }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(6) })
        }

        fun done() = ui.post { bar.visibility = View.GONE }

        fun run() {
            try {
                val login = Prefs.get("gh_login").ifBlank {
                    (gh("GET", "/user").str("login") ?: throw ApiError(Kind.AUTH, 401, "")).also { Prefs.set("gh_login", it) }
                }
                val full = "$login/$repoName"
                say("• المستودع: github.com/$full")
                val repo = try {
                    gh("GET", "/repos/$full")
                } catch (e: ApiError) {
                    if (e.kind != Kind.GONE) throw e
                    say("• إنشاء المستودع…")
                    val r = gh("POST", "/user/repos", J.o("name" to repoName, "description" to "صُنع بواسطة Vadila AI", "private" to false, "auto_init" to true))
                    Thread.sleep(2500)
                    r
                }
                val branch = repo.str("default_branch") ?: "main"
                val all = LinkedHashMap(files)
                workflow(type)?.let { (path, yml) -> if (all.keys.none { it.startsWith(".github/workflows/") }) all[path] = yml }
                if (!all.containsKey("README.md")) all["README.md"] = "# ${chat.title.ifBlank { repoName }}\n\nصُنع بواسطة Vadila AI.\n"
                say("• رفع ${all.size} ملف…")
                var baseSha: String? = null
                for (attempt in 0 until 5) {
                    try {
                        baseSha = gh("GET", "/repos/$full/git/ref/heads/$branch").str("object", "sha")
                        break
                    } catch (e: ApiError) {
                        if (attempt == 4) throw e
                        Thread.sleep(2000)
                    }
                }
                val baseTree = gh("GET", "/repos/$full/git/commits/$baseSha").str("tree", "sha")
                val tree = ArrayList<Any?>()
                for ((path, content) in all) {
                    if (stopped) return
                    val blob = gh("POST", "/repos/$full/git/blobs", J.o("content" to Net.b64(content.toByteArray()), "encoding" to "base64")).str("sha")
                    tree.add(J.o("path" to path, "mode" to "100644", "type" to "blob", "sha" to blob))
                }
                val treeSha = gh("POST", "/repos/$full/git/trees", J.o("base_tree" to baseTree, "tree" to tree)).str("sha")
                val commit = gh("POST", "/repos/$full/git/commits", J.o("message" to "Vadila AI: ${chat.title.ifBlank { "تحديث" }}", "tree" to treeSha, "parents" to J.a(baseSha))).str("sha")
                gh("PATCH", "/repos/$full/git/refs/heads/$branch", J.o("sha" to commit))
                msg.repo = full
                say("✓ رُفع الكود.")
                button("فتح المستودع", false) { Ui.open(act, "https://github.com/$full") }
                when (type) {
                    Type.WEB -> pages(full, login, branch)
                    Type.OTHER -> {
                        say("لا يحتاج هذا المشروع بناءً؛ الكود موجود في المستودع.")
                        done()
                    }
                    else -> watch(full, commit!!)
                }
            } catch (e: Exception) {
                done()
                say("✗ " + (if (e is ApiError) (if (e.kind == Kind.AUTH) "رمز GitHub غير صالح أو ينقصه صلاحيات repo وworkflow." else Errors.friendly(e) + " " + (e.message ?: "")) else e.toString()))
            }
        }

        fun pages(full: String, login: String, branch: String) {
            try {
                gh("POST", "/repos/$full/pages", J.o("build_type" to "legacy", "source" to J.o("branch" to branch, "path" to "/")))
            } catch (e: ApiError) {
                if (e.http != 409) say("ملاحظة: " + (e.message ?: ""))
            }
            val url = "https://$login.github.io/${full.substringAfter('/')}/"
            say("✓ الموقع سيعمل خلال دقيقة أو دقيقتين على:\n$url")
            done()
            button("فتح الموقع", true) { Ui.open(act, url) }
        }

        fun watch(full: String, sha: String) {
            say("• ينتظر بدء البناء على GitHub Actions…")
            var runId: Long? = null
            val start = System.currentTimeMillis()
            var lastState = ""
            while (!stopped) {
                Thread.sleep(8000)
                val runs = try {
                    gh("GET", "/repos/$full/actions/runs?head_sha=$sha&per_page=5").list("workflow_runs")
                } catch (e: ApiError) {
                    if (e.kind == Kind.AUTH) throw e
                    continue
                }
                val run = runs.firstOrNull()
                if (run == null) {
                    if (System.currentTimeMillis() - start > 120_000) {
                        say("✗ لم يبدأ البناء. تأكد أن Actions مفعّلة في المستودع.")
                        done()
                        return
                    }
                    continue
                }
                runId = run.num("id")?.toLong()
                val status = run.str("status") ?: ""
                val mins = (System.currentTimeMillis() - start) / 60000
                val st = when (status) {
                    "queued", "waiting", "pending", "requested" -> "في الانتظار"
                    "in_progress" -> "يُبنى الآن"
                    else -> status
                }
                if (st != lastState || mins > 0 && (System.currentTimeMillis() - start) % 60000 < 8000) say("• $st… ($mins دقيقة)")
                lastState = st
                if (status == "completed") {
                    val ok = run.str("conclusion") == "success"
                    finishRun(full, runId ?: 0, run.num("run_number")?.toLong() ?: 0, ok, run.str("html_url"))
                    return
                }
                if (System.currentTimeMillis() - start > 50 * 60_000) {
                    say("ما زال البناء مستمرًا؛ تابعه من صفحة المستودع.")
                    done()
                    return
                }
            }
        }

        fun finishRun(full: String, runId: Long, runNumber: Long, ok: Boolean, htmlUrl: String?) {
            done()
            val assets = try {
                gh("GET", "/repos/$full/releases/tags/build-$runNumber").list("assets")
            } catch (e: ApiError) {
                emptyList()
            }
            if (assets.isNotEmpty()) {
                say(if (ok) "✓ اكتمل البناء! الملفات الجاهزة:" else "اكتمل جزء من البناء؛ الملفات الجاهزة:")
                for (a in assets) {
                    val name = a.str("name") ?: continue
                    val url = a.str("browser_download_url") ?: continue
                    say("  • $name")
                    button("تنزيل $name", true) { Ui.open(act, url) }
                }
                if (assets.any { (it.str("name") ?: "").endsWith(".ipa") }) {
                    say("ملاحظة: ملف الآيفون غير موقَّع؛ لتثبيته تحتاج توقيعه بحساب Apple (مثل AltStore أو Sideloadly).")
                }
            } else if (ok) {
                say("✓ اكتمل البناء.")
                htmlUrl?.let { u -> button("فتح صفحة البناء", false) { Ui.open(act, u) } }
            }
            if (!ok) {
                say("✗ فشل البناء. يقرأ سجل الأخطاء…")
                val logText = failureLog(full, runId)
                say(logText.lines().takeLast(12).joinToString("\n"))
                button("أصلحه بالذكاء الاصطناعي ثم ابنِ من جديد", true) {
                    d.dismiss()
                    onFix(
                        "فشل بناء المشروع على GitHub Actions. هذا آخر سجل الخطأ:\n```text\n$logText\n```\n" +
                            "حدّد سبب الفشل بدقة، ثم أصلحه: اكتب كل ملف يحتاج تعديلًا كاملًا من جديد، وقبل كل ملف سطر `FILE: المسار`. " +
                            "لا تكتب الملفات التي لم تتغير.",
                    )
                }
                htmlUrl?.let { u -> button("فتح صفحة البناء", false) { Ui.open(act, u) } }
            }
        }

        fun failureLog(full: String, runId: Long): String {
            return try {
                val jobs = gh("GET", "/repos/$full/actions/runs/$runId/jobs").list("jobs")
                val failed = jobs.firstOrNull { it.str("conclusion") == "failure" } ?: return "لا يوجد سجل."
                val id = failed.num("id")?.toLong()
                val req = okhttp3.Request.Builder().url("$API/repos/$full/actions/jobs/$id/logs")
                for ((k, v) in ghHeaders()) req.header(k, v)
                val raw = Net.text(req.build(), "github")
                val lines = raw.lines().map { it.replace(Regex("""^\d{4}-\d{2}-\d{2}T[\d:.]+Z\s?"""), "") }
                val important = lines.withIndex().filter { (_, l) ->
                    l.contains("error", true) || l.contains("FAILURE") || l.startsWith("e: ") || l.contains("Exception") || l.contains("What went wrong")
                }.map { it.index }
                val pick = LinkedHashSet<Int>()
                for (i in important.take(25)) for (k in (i - 2)..(i + 3)) if (k in lines.indices) pick.add(k)
                for (k in (lines.size - 40).coerceAtLeast(0) until lines.size) pick.add(k)
                val sb = StringBuilder("الخطوة الفاشلة: ").append(failed.str("name") ?: "").append('\n')
                var prev = -2
                for (k in pick.sorted()) {
                    if (k != prev + 1) sb.append("…\n")
                    sb.append(lines[k]).append('\n')
                    prev = k
                }
                sb.toString().take(9000)
            } catch (e: Exception) {
                "تعذّرت قراءة السجل: " + (e.message ?: "")
            }
        }
    }

    // ---- ready-made build recipes ----------------------------------------------------------------
    fun workflow(type: Type): Pair<String, String>? {
        val yml = when (type) {
            Type.ANDROID -> """
name: Build
on:
  push:
  workflow_dispatch:
permissions:
  contents: write
jobs:
  android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.14.3"
      - name: Build APK
        run: |
          if [ -f gradlew ] && [ -f gradle/wrapper/gradle-wrapper.jar ]; then chmod +x gradlew; ./gradlew assembleDebug --stacktrace; else gradle assembleDebug --stacktrace; fi
      - name: Collect
        run: |
          mkdir -p out
          find . -path "*/outputs/apk/*" -name "*.apk" -exec cp {} out/ \;
          ls -la out
      - uses: softprops/action-gh-release@v2
        with:
          tag_name: build-@@{{ github.run_number }}
          name: Build @@{{ github.run_number }}
          files: out/*.apk
          make_latest: true
"""
            Type.FLUTTER -> """
name: Build
on:
  push:
  workflow_dispatch:
permissions:
  contents: write
jobs:
  android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: subosito/flutter-action@v2
        with:
          channel: stable
      - run: flutter create --platforms=android . && flutter pub get && flutter build apk --release
      - run: cp build/app/outputs/flutter-apk/app-release.apk app-android.apk
      - uses: actions/upload-artifact@v4
        with:
          name: android
          path: app-android.apk
  windows:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
      - uses: subosito/flutter-action@v2
        with:
          channel: stable
      - run: flutter create --platforms=windows . ; flutter pub get ; flutter build windows --release
      - run: Compress-Archive -Path build/windows/x64/runner/Release/* -DestinationPath app-windows.zip
      - uses: actions/upload-artifact@v4
        with:
          name: windows
          path: app-windows.zip
  linux:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: sudo apt-get update && sudo apt-get install -y ninja-build libgtk-3-dev
      - uses: subosito/flutter-action@v2
        with:
          channel: stable
      - run: flutter create --platforms=linux . && flutter pub get && flutter build linux --release
      - run: tar czf app-linux.tar.gz -C build/linux/x64/release/bundle .
      - uses: actions/upload-artifact@v4
        with:
          name: linux
          path: app-linux.tar.gz
  ios:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - uses: subosito/flutter-action@v2
        with:
          channel: stable
      - run: flutter create --platforms=ios . && flutter pub get && flutter build ios --release --no-codesign
      - run: mkdir Payload && cp -r build/ios/iphoneos/Runner.app Payload/ && zip -qr app-ios-unsigned.ipa Payload
      - uses: actions/upload-artifact@v4
        with:
          name: ios
          path: app-ios-unsigned.ipa
  release:
    needs: [android, windows, linux, ios]
    if: always()
    runs-on: ubuntu-latest
    steps:
      - uses: actions/download-artifact@v4
        with:
          path: dist
          merge-multiple: true
      - run: ls -la dist || true
      - uses: softprops/action-gh-release@v2
        with:
          tag_name: build-@@{{ github.run_number }}
          name: Build @@{{ github.run_number }}
          files: dist/*
          make_latest: true
          fail_on_unmatched_files: false
"""
            Type.PYTHON -> """
name: Build
on:
  push:
  workflow_dispatch:
permissions:
  contents: write
jobs:
  build:
    strategy:
      fail-fast: false
      matrix:
        os: [windows-latest, ubuntu-latest, macos-latest]
    runs-on: @@{{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"
      - name: Build
        shell: bash
        run: |
          python -m pip install --upgrade pip pyinstaller
          if [ -f requirements.txt ]; then pip install -r requirements.txt; fi
          ENTRY=@@(ls main.py app.py 2>/dev/null | head -1 || true)
          if [ -z "@@ENTRY" ]; then ENTRY=@@(ls *.py 2>/dev/null | head -1 || true); fi
          pyinstaller --onefile --windowed --name app "@@ENTRY"
          mkdir -p out
          cd dist && for f in *; do cp -r "@@f" "../out/@@{{ runner.os }}-@@f"; done
          cd ../out && for f in *; do if [ -d "@@f" ]; then zip -qr "@@f.zip" "@@f" && rm -rf "@@f"; fi; done
      - uses: actions/upload-artifact@v4
        with:
          name: app-@@{{ matrix.os }}
          path: out/*
  release:
    needs: build
    if: always()
    runs-on: ubuntu-latest
    steps:
      - uses: actions/download-artifact@v4
        with:
          path: dist
          merge-multiple: true
      - uses: softprops/action-gh-release@v2
        with:
          tag_name: build-@@{{ github.run_number }}
          name: Build @@{{ github.run_number }}
          files: dist/*
          make_latest: true
          fail_on_unmatched_files: false
"""
            else -> return null
        }
        return ".github/workflows/build.yml" to yml.trimStart().replace("@@", "$")
    }
}
