package univzy.torikomi.engine

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File

/**
 * Bridges Android WebView's cookie jar to yt-dlp's `--cookies` file.
 * The user logs into any site in a WebView; we dump that site's cookies to a Netscape
 * cookies.txt (one per registrable domain) that the engine passes to yt-dlp for every
 * link on that domain — so it works for every yt-dlp extractor, not a fixed list.
 */
object CookieStore {

    /** Quick-pick login pages (name -> url). Any other site can be logged into by URL. */
    val LOGIN_URLS: Map<String, String> = mapOf(
        "Instagram" to "https://www.instagram.com/accounts/login/",
        "TikTok"    to "https://www.tiktok.com/login",
        "Facebook"  to "https://www.facebook.com/login/",
        "Twitter"   to "https://x.com/login",
        "Threads"   to "https://www.threads.net/login",
        "Pinterest" to "https://www.pinterest.com/login/",
    )

    /** Cookie present only when actually logged in (a bare csrftoken doesn't count). Unknown sites: any file counts. */
    private val SESSION_COOKIE = mapOf(
        "instagram.com" to "sessionid", "threads.net" to "sessionid", "tiktok.com" to "sessionid",
        "facebook.com" to "c_user", "x.com" to "auth_token", "pinterest.com" to "_auth",
    )

    private val ALIASES = mapOf("twitter.com" to "x.com", "fb.watch" to "facebook.com", "fb.com" to "facebook.com")

    private fun dir(context: Context) = File(context.filesDir, "cookies").apply { mkdirs() }

    /** Registrable domain, e.g. vm.tiktok.com -> tiktok.com, www.bbc.co.uk -> bbc.co.uk. */
    fun siteKey(url: String): String? = runCatching {
        val parts = Uri.parse(if ("://" in url) url else "https://$url").host!!.lowercase().split(".")
        // heuristic for 2-letter ccTLD + short SLD (co.uk, com.br); no public-suffix list.
        val n = if (parts.size >= 3 && parts.last().length == 2 && parts[parts.size - 2].length <= 3) 3 else 2
        parts.takeLast(n).joinToString(".").let { ALIASES[it] ?: it }
    }.getOrNull()?.takeIf { "." in it }

    private fun file(context: Context, key: String) = File(dir(context), "$key.txt")

    /** The saved Netscape cookie file for [url]'s site, or null if none. */
    fun cookieFileFor(context: Context, url: String): File? =
        siteKey(url)?.let { file(context, it) }?.takeIf { it.exists() && it.length() > 0 }

    /** Where to send the user to log in for a link from [sourceUrl]: known login page, else the site's home. */
    fun loginUrlFor(sourceUrl: String): String? {
        val key = siteKey(sourceUrl) ?: return null
        return LOGIN_URLS.values.firstOrNull { siteKey(it) == key } ?: "https://www.$key/"
    }

    fun isLoggedIn(context: Context, key: String): Boolean {
        val f = file(context, key).takeIf { it.exists() && it.length() > 0 } ?: return false
        val marker = SESSION_COOKIE[key] ?: return true
        return f.readLines().any { it.split("\t").getOrNull(5) == marker }
    }

    /** Sites with a saved session that aren't in the quick-pick list. */
    fun otherSites(context: Context): List<String> {
        val known = LOGIN_URLS.values.mapNotNull { siteKey(it) }.toSet()
        return dir(context).listFiles().orEmpty().map { it.nameWithoutExtension }.filter { it !in known }.sorted()
    }

    /** Dumps the WebView cookies visible to [url] into a Netscape cookies.txt for that site. */
    fun save(context: Context, url: String) {
        val key = siteKey(url) ?: return
        CookieManager.getInstance().flush()
        val raw = CookieManager.getInstance().getCookie(url) ?: return
        // Far-future expiry so yt-dlp doesn't treat session cookies (expiry 0) as expired.
        val expiry = System.currentTimeMillis() / 1000 + 31_536_000L
        val lines = buildString {
            appendLine("# Netscape HTTP Cookie File")
            raw.split(";").forEach { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) return@forEach
                val name = pair.substring(0, i).trim()
                val value = pair.substring(i + 1).trim()
                // domain  includeSubdomains  path  secure  expiry  name  value
                appendLine(".$key\tTRUE\t/\tTRUE\t$expiry\t$name\t$value")
            }
        }
        file(context, key).writeText(lines)
    }

    /** Forgets the saved session for [key]. WebView is only used for logins, so wiping its jar is safe. */
    fun logout(context: Context, key: String) {
        file(context, key).delete()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /** Heuristic: does this yt-dlp error mean the site wants a logged-in session? */
    fun needsLogin(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return "login" in m || "log in" in m || "sign in" in m || "cookies" in m ||
            "rate-limit" in m || "rate limit" in m || "only available" in m ||
            "private" in m || "authentication" in m || "confirm your age" in m ||
            ("not available" in m && "content" in m)
    }
}
