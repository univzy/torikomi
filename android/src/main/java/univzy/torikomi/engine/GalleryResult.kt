package univzy.torikomi.engine

import org.json.JSONArray
import univzy.torikomi.data.model.SyncDocument

data class GalleryPhoto(val url: String, val extension: String, val title: String, val site: String)

fun parseGalleryPhotos(raw: String): List<GalleryPhoto> {
    require(raw.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "Gallery response exceeds 2 MiB" }
    val rows = JSONArray(raw)
    require(rows.length() <= 500) { "Too many gallery records" }
    val photos = (0 until rows.length()).mapNotNull { index ->
        val row = rows.getJSONArray(index)
        require(row.optInt(0) != -1) { "Gallery extraction failed: ${row.optJSONObject(1)?.optString("message").orEmpty().take(300)}" }
        if (row.optInt(0) != 3 || row.length() < 3) return@mapNotNull null
        val meta = row.getJSONObject(2)
        val ext = meta.optString("extension").lowercase()
        if (ext !in ImageConverter.IMAGE_EXTS) return@mapNotNull null
        GalleryPhoto(publicMediaUrl(row.getString(1)), ext,
            meta.optString("title").ifBlank { meta.optString("description") }.take(1000),
            meta.optString("category", "Gallery").take(100))
    }.distinctBy { it.url }
    require(photos.isNotEmpty()) { "No publicly accessible photos found in this link" }
    require(photos.size <= 100) { "This gallery exceeds 100 photos" }
    return photos
}
