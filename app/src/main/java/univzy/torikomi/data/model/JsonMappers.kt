package univzy.torikomi.data.model

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
    put("fileSize", fileSize)
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
    fileSize     = json.optLong("fileSize", 0L),
)

fun QueuedDownload.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("sourceUrl", sourceUrl)
    put("title", title)
    put("thumbnail", thumbnail)
    put("detectedSite", detectedSite)
    put("choiceLabel", choiceLabel)
    put("choiceType", choiceType)
    put("choiceSelector", choiceSelector)
    put("choiceAudioExtract", choiceAudioExtract)
    put("addedAt", addedAt)
    put("sizeBytes", sizeBytes ?: -1L)
}

fun queuedDownloadFromJson(json: JSONObject) = QueuedDownload(
    id = json.optString("id"),
    sourceUrl = json.optString("sourceUrl"),
    title = json.optString("title"),
    thumbnail = json.optString("thumbnail"),
    detectedSite = json.optString("detectedSite"),
    choiceLabel = json.optString("choiceLabel"),
    choiceType = json.optString("choiceType", "video"),
    choiceSelector = json.optString("choiceSelector"),
    choiceAudioExtract = json.optBoolean("choiceAudioExtract", false),
    addedAt = json.optLong("addedAt", 0L),
    sizeBytes = json.optLong("sizeBytes", -1L).takeIf { it >= 0 },
)
