package univzy.torikomi.data.model

import org.json.JSONArray
import org.json.JSONObject


fun DownloadHistory.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("platform", platform)
    put("title", title)
    put("url", url)
    put("thumbnailUrl", thumbnailUrl)
    put("downloadType", downloadType)
    put("downloadDate", downloadDate)
    put("fileCount", fileCount)
    put("folderUri", folderUri)
    put("fileName", fileName)
    put("quality", quality)
}

fun downloadHistoryFromJson(json: JSONObject) = DownloadHistory(
    id           = json.optString("id"),
    platform     = json.optString("platform"),
    title        = json.optString("title"),
    url          = json.optString("url"),
    thumbnailUrl = json.optString("thumbnailUrl"),
    downloadType = json.optString("downloadType", "video"),
    downloadDate = json.optLong("downloadDate", System.currentTimeMillis()),
    fileCount    = json.optInt("fileCount", 1),
    folderUri    = json.optString("folderUri", "default"),
    fileName     = json.optString("fileName", ""),
    quality      = json.optString("quality", ""),
)


fun scrapeResultFromJson(json: JSONObject): ScrapeResult {
    val items = json.optJSONArray("downloadItems") ?: JSONArray()
    val images = json.optJSONArray("images") ?: JSONArray()
    val parsedItems = (0 until items.length()).mapNotNull { index ->
        val itemObj = items.optJSONObject(index) ?: return@mapNotNull null
        runCatching { downloadItemFromJson(itemObj) }.getOrNull()
    }

    // Backward-compat for extension payloads that still return legacy URL keys.
    val fallbackItems = mutableListOf<DownloadItem>()
    fun addLegacyItem(key: String, label: String, type: String, quality: String = "") {
        val url = json.optString(key)
        if (url.isNotBlank()) {
            fallbackItems += DownloadItem(
                key = key,
                label = label,
                type = type,
                url = url,
                mimeType = if (type == "audio") "audio/mpeg" else "video/mp4",
                quality = quality,
            )
        }
    }
    addLegacyItem("videoUrl", "Video", "video")
    addLegacyItem("videoUrlHD", "Video HD", "video", "HD")
    addLegacyItem("videoUrlWatermark", "Video Watermark", "video", "Watermark")
    addLegacyItem("music", "Audio", "audio")

    val effectiveItems = if (parsedItems.isNotEmpty()) parsedItems else fallbackItems
    val thumbnail = json.optString("thumbnail").ifBlank { json.optString("cover") }

    return ScrapeResult(
        extensionId   = json.optString("extensionId"),
        platform      = json.optString("platform"),
        title         = json.optString("title"),
        author        = json.optString("author"),
        authorName    = json.optString("authorName"),
        thumbnail     = thumbnail,
        duration      = json.optInt("duration"),
        downloadItems = effectiveItems,
        images        = (0 until images.length()).mapNotNull { idx ->
            images.optString(idx).takeIf { it.isNotBlank() }
        },
        error         = json.optString("error").takeIf { it.isNotEmpty() },
    )
}

private fun jsonObjectToMap(json: JSONObject): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = json.opt(key)
        when (value) {
            null,
            JSONObject.NULL -> Unit
            is JSONObject -> map[key] = jsonObjectToMap(value)
            is JSONArray -> {
                val items = mutableListOf<Any>()
                for (i in 0 until value.length()) {
                    val item = value.opt(i)
                    when (item) {
                        null,
                        JSONObject.NULL -> Unit
                        is JSONObject -> items += jsonObjectToMap(item)
                        else -> items += item
                    }
                }
                map[key] = items
            }
            else -> map[key] = value
        }
    }
    return map
}

fun downloadItemFromJson(json: JSONObject) = DownloadItem(
    key      = json.optString("key"),
    label    = json.optString("label"),
    type     = json.optString("type"),
    url      = json.optString("url"),
    mimeType = json.optString("mimeType", "application/octet-stream"),
    quality  = json.optString("quality"),
    fileSize = json.optIntOrNull("fileSize"),
    extra    = json.optJSONObject("extra")?.let(::jsonObjectToMap) ?: emptyMap(),
)

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null

    val raw = opt(key)
    return when (raw) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull()
        else -> null
    }
}


fun extensionInfoFromJson(json: JSONObject): ExtensionInfo {
    val sources = json.optJSONArray("sources") ?: JSONArray()
    return ExtensionInfo(
        name    = json.optString("name"),
        pkg     = json.optString("pkg"),
        icon    = json.optString("icon"),
        apk     = json.optString("apk"),
        lang    = json.optString("lang", "multi"),
        code    = json.optInt("code", 1),
        version = json.optString("version", "1.0.0"),
        nsfw    = json.optInt("nsfw", 0),
        sources = (0 until sources.length()).map { extensionSourceFromJson(sources.getJSONObject(it)) },
    )
}

fun extensionSourceFromJson(json: JSONObject) = ExtensionSource(
    name      = json.optString("name"),
    lang      = json.optString("lang", "multi"),
    id        = json.optString("id"),
    baseUrl   = json.optString("baseUrl"),
    versionId = json.optInt("versionId", 1),
)
