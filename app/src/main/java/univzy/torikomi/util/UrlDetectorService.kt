package univzy.torikomi.util

object UrlDetectorService {

    private val platformPatterns: Map<String, List<Regex>> = mapOf(
        "YouTube"    to listOf(
            Regex("youtube\\.com/watch",      RegexOption.IGNORE_CASE),
            Regex("youtube\\.com/playlist",   RegexOption.IGNORE_CASE),
            Regex("youtu\\.be/",              RegexOption.IGNORE_CASE),
            Regex("youtube\\.com/shorts/",    RegexOption.IGNORE_CASE),
            Regex("m\\.youtube\\.com",        RegexOption.IGNORE_CASE),
        ),
        "TikTok"     to listOf(
            Regex("tiktok\\.com/@",           RegexOption.IGNORE_CASE),
            Regex("tiktok\\.com/v/",          RegexOption.IGNORE_CASE),
            Regex("vm\\.tiktok\\.com",        RegexOption.IGNORE_CASE),
            Regex("vt\\.tiktok\\.com",        RegexOption.IGNORE_CASE),
        ),
        "Instagram"  to listOf(
            Regex("instagram\\.com/p/",       RegexOption.IGNORE_CASE),
            Regex("instagram\\.com/reel/",    RegexOption.IGNORE_CASE),
            Regex("instagram\\.com/reels/",   RegexOption.IGNORE_CASE),
            Regex("instagram\\.com/tv/",      RegexOption.IGNORE_CASE),
            Regex("instagr\\.am/",            RegexOption.IGNORE_CASE),
        ),
        "Facebook"   to listOf(
            Regex("facebook\\.com/.*/videos/",RegexOption.IGNORE_CASE),
            Regex("facebook\\.com/watch",     RegexOption.IGNORE_CASE),
            Regex("fb\\.watch/",              RegexOption.IGNORE_CASE),
            Regex("m\\.facebook\\.com",       RegexOption.IGNORE_CASE),
        ),
        "Twitter"    to listOf(
            Regex("twitter\\.com/.*/status/", RegexOption.IGNORE_CASE),
            Regex("x\\.com/.*/status/",       RegexOption.IGNORE_CASE),
            Regex("t\\.co/",                  RegexOption.IGNORE_CASE),
        ),
        "Threads"    to listOf(
            Regex("threads\\.net/@",          RegexOption.IGNORE_CASE),
            Regex("threads\\.net/t/",         RegexOption.IGNORE_CASE),
            Regex("threads\\.com/@.*/post/",  RegexOption.IGNORE_CASE),
        ),
        "Pinterest"  to listOf(
            Regex("pinterest\\.com/pin/",     RegexOption.IGNORE_CASE),
            Regex("pin\\.it/",                RegexOption.IGNORE_CASE),
        ),
        "Spotify"    to listOf(
            Regex("open\\.spotify\\.com/track/",    RegexOption.IGNORE_CASE),
            Regex("open\\.spotify\\.com/playlist/", RegexOption.IGNORE_CASE),
            Regex("open\\.spotify\\.com/episode/",  RegexOption.IGNORE_CASE),
            Regex("spotify\\.link/",                RegexOption.IGNORE_CASE),
        ),
        "SoundCloud" to listOf(
            Regex("soundcloud\\.com/[^/]+/[^/]+",   RegexOption.IGNORE_CASE),
            Regex("on\\.soundcloud\\.com",           RegexOption.IGNORE_CASE),
        ),
        "Douyin"     to listOf(
            Regex("v\\.douyin\\.com/",              RegexOption.IGNORE_CASE),
            Regex("(www\\.)?douyin\\.com/video/",   RegexOption.IGNORE_CASE),
            Regex("m\\.douyin\\.com",               RegexOption.IGNORE_CASE),
        ),
        "Bilibili"   to listOf(
            Regex("bilibili\\.tv/[a-z]{2}/video/",  RegexOption.IGNORE_CASE),
            Regex("bilibili\\.tv/video/",           RegexOption.IGNORE_CASE),
            Regex("b23\\.tv/",                      RegexOption.IGNORE_CASE),
        ),
    )

    fun detectPlatform(url: String): String? {
        if (url.isBlank()) return null
        val clean = url.trim()
        for ((platform, patterns) in platformPatterns) {
            if (patterns.any { it.containsMatchIn(clean) }) return platform
        }
        return null
    }

    fun isValidMediaUrl(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("http://") && !t.startsWith("https://")) return false
        return detectPlatform(t) != null
    }

    fun extractUrl(text: String): String? {
        if (text.isBlank()) return null
        val trimmed = text.trim()
        if (isValidMediaUrl(trimmed)) return trimmed
        val urlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        return urlRegex.findAll(text).mapNotNull { it.value.takeIf { u -> isValidMediaUrl(u) } }.firstOrNull()
    }

    fun getPlatformEmoji(platform: String): String = when (platform) {
        "YouTube"    -> "▶️"
        "TikTok"     -> "🎵"
        "Instagram"  -> "📸"
        "Facebook"   -> "👤"
        "Twitter"    -> "🐦"
        "Pinterest"  -> "📌"
        "Spotify"    -> "🎵"
        "SoundCloud" -> "🔊"
        "Douyin"     -> "🎬"
        "Bilibili"   -> "📺"
        else         -> "🔗"
    }

    fun shortenUrl(url: String): String = runCatching {
        val uri = android.net.Uri.parse(url)
        "${uri.host}${uri.path}".replace(Regex("\\?.*"), "")
    }.getOrElse { if (url.length > 50) "${url.take(47)}..." else url }
}
