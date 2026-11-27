package com.vadila.ai

import java.io.File
import java.util.UUID

/** A file the person attached (copied into the app's private storage). */
class Att(
    val id: String,
    var name: String,
    var mime: String,
    var path: String,
    var size: Long,
    /** Extracted text (documents) or transcript (audio / video). */
    var text: String? = null,
    /** Gemini File API reference, valid until [gExp]. */
    var gUri: String? = null,
    var gExp: Long = 0,
) {
    val isImage get() = mime.startsWith("image/")
    val isAudio get() = mime.startsWith("audio/")
    val isVideo get() = mime.startsWith("video/")
    val isPdf get() = mime == "application/pdf"
    val file get() = File(path)

    fun toMap(): Map<String, Any?> = J.o(
        "id" to id, "name" to name, "mime" to mime, "path" to path, "size" to size,
        "text" to text, "gUri" to gUri, "gExp" to gExp,
    )

    companion object {
        fun from(m: Any?): Att = Att(
            m.str("id") ?: UUID.randomUUID().toString(), m.str("name") ?: "file", m.str("mime") ?: "application/octet-stream",
            m.str("path") ?: "", m.num("size")?.toLong() ?: 0, m.str("text"), m.str("gUri"), m.num("gExp")?.toLong() ?: 0,
        )
    }
}

/** One answer of the council (several models asked the same question). */
class Opinion(val label: String, var text: String, var ok: Boolean)

class Msg(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    var text: String = "",
    val atts: MutableList<Att> = ArrayList(),
    /** Generated pictures (absolute paths). */
    val images: MutableList<String> = ArrayList(),
    val sources: MutableList<Src> = ArrayList(),
    var model: String = "",
    var error: String? = null,
    var time: Long = System.currentTimeMillis(),
    val opinions: MutableList<Opinion> = ArrayList(),
    /** GitHub build of this answer's project: "owner/repo". */
    var repo: String? = null,
) {
    fun toMap(): Map<String, Any?> = J.o(
        "id" to id, "role" to role, "text" to text, "atts" to atts.map { it.toMap() }, "images" to images,
        "sources" to sources.map { J.o("t" to it.title, "u" to it.url) }, "model" to model, "error" to error, "time" to time,
        "opinions" to opinions.map { J.o("l" to it.label, "t" to it.text, "ok" to it.ok) }, "repo" to repo,
    )

    companion object {
        fun from(m: Any?): Msg = Msg(
            id = m.str("id") ?: UUID.randomUUID().toString(),
            role = m.str("role") ?: "user",
            text = m.str("text") ?: "",
            atts = m.list("atts").map { Att.from(it) }.toMutableList(),
            images = m.list("images").mapNotNull { it as? String }.toMutableList(),
            sources = m.list("sources").map { Src(it.str("t") ?: "", it.str("u") ?: "") }.toMutableList(),
            model = m.str("model") ?: "",
            error = m.str("error"),
            time = m.num("time")?.toLong() ?: 0,
            opinions = m.list("opinions").map { Opinion(it.str("l") ?: "", it.str("t") ?: "", it.bool("ok") ?: true) }.toMutableList(),
            repo = m.str("repo"),
        )
    }
}

class Chat(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    val msgs: MutableList<Msg> = ArrayList(),
    var updated: Long = System.currentTimeMillis(),
) {
    fun toMap(): Map<String, Any?> = J.o("id" to id, "title" to title, "updated" to updated, "msgs" to msgs.map { it.toMap() })

    companion object {
        fun from(m: Any?): Chat = Chat(
            m.str("id") ?: UUID.randomUUID().toString(), m.str("title") ?: "",
            m.list("msgs").map { Msg.from(it) }.toMutableList(), m.num("updated")?.toLong() ?: 0,
        )
    }
}

/** Conversations are stored one JSON file each. */
class Store(private val dir: File) {
    init {
        dir.mkdirs()
    }

    class Entry(val id: String, val title: String, val updated: Long)

    fun save(c: Chat) {
        val tmp = File(dir, c.id + ".tmp")
        tmp.writeText(J.write(c.toMap()))
        tmp.renameTo(File(dir, c.id + ".json"))
        File(dir, "index.json").delete() // rebuilt lazily
    }

    fun load(id: String): Chat? = try {
        Chat.from(J.parse(File(dir, "$id.json").readText()))
    } catch (e: Exception) {
        null
    }

    fun delete(id: String) {
        load(id)?.let { c ->
            for (m in c.msgs) {
                for (a in m.atts) File(a.path).delete()
                for (i in m.images) File(i).delete()
            }
        }
        File(dir, "$id.json").delete()
    }

    fun list(): List<Entry> {
        val out = ArrayList<Entry>()
        for (f in dir.listFiles() ?: emptyArray()) {
            if (!f.name.endsWith(".json") || f.name == "index.json") continue
            try {
                // titles and dates are at the start of the file, so a small read is enough
                val head = f.reader().use { r ->
                    val buf = CharArray(4096)
                    val n = r.read(buf)
                    if (n > 0) String(buf, 0, n) else ""
                }
                val title = Regex(""""title":"((?:[^"\\]|\\.)*)"""").find(head)?.groupValues?.get(1)
                    ?.let { J.parse("\"$it\"") as? String } ?: ""
                val upd = Regex(""""updated":(\d+)""").find(head)?.groupValues?.get(1)?.toLongOrNull() ?: f.lastModified()
                out.add(Entry(f.name.removeSuffix(".json"), title, upd))
            } catch (e: Exception) {
            }
        }
        return out.sortedByDescending { it.updated }
    }
}
