package com.vadila.downloader

/** Video/audio quality choices. A key is "best", "low", "mp3", "m4a" or a height such as "720". */
object Quality {
    val fixed: LinkedHashMap<String, String> = linkedMapOf(
        "best" to "أفضل جودة متاحة",
        "1080" to "1080p – عالية",
        "720" to "720p",
        "480" to "480p",
        "360" to "360p – منخفضة",
        "240" to "240p",
        "low" to "أقل جودة (أصغر حجم)",
        "mp3" to "صوت فقط – MP3",
        "m4a" to "صوت فقط – M4A (أسرع)",
    )

    fun label(key: String): String = fixed[key] ?: "${key}p"

    fun shortLabel(key: String): String = when (key) {
        "best" -> "أفضل جودة"
        "low" -> "أقل جودة"
        "mp3" -> "MP3"
        "m4a" -> "M4A"
        else -> "${key}p"
    }

    fun isAudio(key: String) = key == "mp3" || key == "m4a"

    /** yt-dlp options for a quality key. */
    fun ytdlpOptions(key: String): List<Pair<String, String?>> = when {
        key == "mp3" -> listOf(
            "-f" to "ba/b", "-x" to null, "--audio-format" to "mp3", "--audio-quality" to "0"
        )
        key == "m4a" -> listOf(
            "-f" to "ba[ext=m4a]/ba/b", "-x" to null, "--audio-format" to "m4a"
        )
        key == "low" -> listOf(
            "-f" to "bv*+ba/b", "-S" to "+res,ext:mp4:m4a", "--merge-output-format" to "mp4"
        )
        key.toIntOrNull() != null -> listOf(
            "-f" to "bv*+ba/b", "-S" to "res:$key,ext:mp4:m4a", "--merge-output-format" to "mp4"
        )
        else -> listOf(
            "-f" to "bv*+ba/b", "-S" to "res,ext:mp4:m4a", "--merge-output-format" to "mp4"
        )
    }

    /** One entry in the quality picker. */
    class Option(val key: String, val label: String)

    class Available(val title: String, val options: List<Option>)

    fun sizeText(bytes: Long) = if (bytes > 0) " • ~${Engine.fmtBytes(bytes)}" else ""

    fun heightTag(h: Int) = when {
        h >= 2160 -> " (4K)"
        h >= 1440 -> " (2K)"
        h >= 1080 -> " (عالية)"
        h <= 360 -> " (منخفضة)"
        else -> ""
    }
}
