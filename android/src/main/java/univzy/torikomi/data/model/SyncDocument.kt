package univzy.torikomi.data.model

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class AppPreferences(
    val themeMode: String = "system", val themeColor: String = "violet",
    val fontScale: Float = 1f, val fontFamily: String = "system",
    val layout: String = "comfortable", val language: String = "system",
    val notifications: Boolean = true, val glass: Boolean = false,
) {
    fun toJson() = JSONObject().apply {
        put("themeMode", themeMode); put("themeColor", themeColor); put("fontScale", fontScale)
        put("fontFamily", fontFamily); put("layout", layout); put("language", language)
        put("notifications", notifications); put("glass", glass)
    }

    companion object {
        fun fromJson(json: JSONObject): AppPreferences {
            listOf("themeMode", "themeColor", "fontFamily", "layout", "language").forEach { require(json.get(it) is String) { "Invalid preference: $it" } }
            listOf("notifications", "glass").forEach { require(json.get(it) is Boolean) { "Invalid preference: $it" } }
            require(json.get("fontScale") is Number) { "Invalid font scale" }
            val value = AppPreferences(
                json.getString("themeMode"), json.getString("themeColor"), json.getDouble("fontScale").toFloat(),
                json.getString("fontFamily"), json.getString("layout"), json.getString("language"),
                json.getBoolean("notifications"), json.getBoolean("glass"),
            )
            require(value.themeMode in listOf("system", "light", "dark"))
            require(value.themeColor in AppCatalog.themeIds)
            require(value.fontScale.isFinite() && value.fontScale in 1f..1.5f)
            require(value.fontFamily in listOf("system", "sans", "serif", "mono"))
            require(value.layout in listOf("comfortable", "compact"))
            require(value.language.length <= 35) { "Language tag is too long" }
            return value
        }
    }
}

data class MediaMetadata(
    val artist: String? = null, val album: String? = null, val albumArtist: String? = null,
    val trackNumber: Long? = null, val genre: String? = null, val year: Long? = null, val duration: Double? = null,
) {
    fun toJson() = JSONObject().apply {
        put("artist", artist); put("album", album); put("albumArtist", albumArtist)
        put("trackNumber", trackNumber); put("genre", genre); put("year", year); put("duration", duration)
    }
}

data class FavoriteFolder(
    val id: String, val name: String, val createdAt: Long, val updatedAt: Long, val deviceId: String, val deletedAt: Long = 0,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("createdAt", createdAt)
        put("updatedAt", updatedAt); put("deviceId", deviceId); put("deletedAt", deletedAt)
    }
}

data class FolderMembership(
    val folderId: String, val entryId: String, val updatedAt: Long, val deviceId: String, val deletedAt: Long = 0,
) {
    fun toJson() = JSONObject().apply {
        put("folderId", folderId); put("entryId", entryId)
        put("updatedAt", updatedAt); put("deviceId", deviceId); put("deletedAt", deletedAt)
    }
}

data class LibraryEntry(
    val id: String, val url: String, val title: String, val kind: String,
    val createdAt: Long, val updatedAt: Long, val deviceId: String, val deletedAt: Long = 0,
    val metadata: MediaMetadata? = null,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("url", url); put("title", title); put("kind", kind)
        put("createdAt", createdAt); put("updatedAt", updatedAt); put("deviceId", deviceId); put("deletedAt", deletedAt)
        metadata?.let { put("metadata", it.toJson()) }
    }
}

data class SyncDocument(
    val preferences: AppPreferences, val updatedAt: Long, val deviceId: String,
    val entries: List<LibraryEntry> = emptyList(),
    val folders: List<FavoriteFolder> = emptyList(),
    val memberships: List<FolderMembership> = emptyList(),
) {
    fun toJson() = JSONObject().apply {
        put("schemaVersion", 2)
        put("settings", JSONObject().put("value", preferences.toJson()).put("updatedAt", updatedAt).put("deviceId", deviceId))
        put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })
        put("folders", JSONArray().apply { folders.forEach { put(it.toJson()) } })
        put("memberships", JSONArray().apply { memberships.forEach { put(it.toJson()) } })
    }

    fun merge(other: SyncDocument): SyncDocument {
        parse(toJson().toString()); parse(other.toJson().toString())
        val settings = if (updatedAt > other.updatedAt || (updatedAt == other.updatedAt && deviceId >= other.deviceId)) this else other
        val merged = (entries + other.entries).groupBy { it.id }.map { (_, versions) ->
            versions.maxWith(compareBy<LibraryEntry> { it.updatedAt }.thenBy { it.deviceId })
        }.sortedBy { it.id }
        require(merged.size <= 5000) { "Library limit reached (5,000 entries)" }
        val mergedFolders = (folders + other.folders).groupBy { it.id }.map { (_, versions) ->
            versions.maxWith(compareBy<FavoriteFolder> { it.updatedAt }.thenBy { it.deviceId })
        }.sortedBy { it.id }
        val mergedMemberships = (memberships + other.memberships).groupBy { it.folderId to it.entryId }.map { (_, versions) ->
            versions.maxWith(compareBy<FolderMembership> { it.updatedAt }.thenBy { it.deviceId })
        }.sortedWith(compareBy<FolderMembership> { it.folderId }.thenBy { it.entryId })
        return parse(settings.copy(entries = merged, folders = mergedFolders, memberships = mergedMemberships).toJson().toString())
    }

    fun nextTime(now: Long): Long = maxOf(now, updatedAt + 1,
        (entries.maxOfOrNull { it.updatedAt } ?: 0) + 1,
        (folders.maxOfOrNull { it.updatedAt } ?: 0) + 1,
        (memberships.maxOfOrNull { it.updatedAt } ?: 0) + 1).also {
        require(it in 0..9_007_199_254_740_991L) { "Revision timestamp exceeds supported range" }
    }

    fun importBackup(backup: SyncDocument, localDeviceId: String, now: Long): SyncDocument {
        val merged = merge(backup)
        val time = merged.nextTime(now)
        return parse(merged.copy(preferences = backup.preferences, updatedAt = time, deviceId = localDeviceId).toJson().toString())
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        fun readBackup(input: InputStream): SyncDocument {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                if (count == -1) break
                output.write(buffer, 0, count)
                require(output.size() <= MAX_BYTES) { "Backup exceeds 2 MiB" }
            }
            val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
            return parse(raw)
        }
        fun mediaUrl(raw: String): String {
            require(raw.length <= 8192) { "URL too long" }
            val uri = URI(raw.trim())
            require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Use an HTTP(S) URL without credentials" }
            return uri.toASCIIString()
        }
        private fun timestamp(json: JSONObject, key: String): Long {
            val number = json.get(key)
            require(number is Number && number.toDouble().isFinite() && number.toDouble() == number.toLong().toDouble() && number.toLong() in 0..9_007_199_254_740_991L)
            return number.toLong()
        }
        private fun text(json: JSONObject, key: String) = (json.get(key) as? String) ?: throw IllegalArgumentException("Invalid $key")
        private fun device(json: JSONObject) = text(json, "deviceId").also { require(it.isNotEmpty() && it.length <= 100) }
        fun parse(raw: String): SyncDocument {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Sync file exceeds 2 MiB" }
            val parser = JSONTokener(raw)
            val json = parser.nextValue() as? JSONObject ?: throw IllegalArgumentException("Backup must be a JSON object")
            require(parser.nextClean() == '\u0000') { "Unexpected content after backup" }
            require(json.get("schemaVersion") is Number && json.getDouble("schemaVersion") in listOf(1.0, 2.0)) { "Unsupported sync version" }
            val version = json.getInt("schemaVersion")
            val settings = json.getJSONObject("settings")
            val items = json.getJSONArray("entries")
            require(items.length() <= 5000) { "Library limit reached" }
            val entries = (0 until items.length()).map { i ->
                val entry = items.getJSONObject(i)
                LibraryEntry(text(entry, "id").also { require(it.isNotEmpty() && it.length <= 160) },
                    mediaUrl(text(entry, "url")), text(entry, "title").also { require(it.length <= 1000) },
                    text(entry, "kind").also { require(it in listOf("video", "audio", "image", "link")) },
                    timestamp(entry, "createdAt"), timestamp(entry, "updatedAt"), device(entry), timestamp(entry, "deletedAt"),
                    if (entry.isNull("metadata")) null else metadata(entry.getJSONObject("metadata")))
            }
            require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate library IDs" }
            val folderItems = if (version == 2) json.getJSONArray("folders") else JSONArray()
            require(folderItems.length() <= 100) { "Folder limit reached" }
            val folders = (0 until folderItems.length()).map { i ->
                val folder = folderItems.getJSONObject(i)
                FavoriteFolder(text(folder, "id").also { require(it.isNotEmpty() && it.length <= 160 && it != "favorites") },
                    text(folder, "name").trim().also { require(it.isNotEmpty() && it.length <= 80 && it.none(Char::isISOControl)) },
                    timestamp(folder, "createdAt"), timestamp(folder, "updatedAt"), device(folder), timestamp(folder, "deletedAt"))
            }
            require(folders.map { it.id }.distinct().size == folders.size) { "Duplicate folder IDs" }
            val visible = folders.filter { it.deletedAt == 0L }
            require(visible.map { it.name }.distinct().size == visible.size) { "Duplicate folder names" }
            val memberItems = if (version == 2) json.getJSONArray("memberships") else JSONArray()
            require(memberItems.length() <= 20000) { "Membership limit reached" }
            val folderIds = folders.map { it.id }.toSet() + "favorites"
            val audioIds = entries.filter { it.kind == "audio" }.map { it.id }.toSet()
            val members = (0 until memberItems.length()).map { i ->
                val member = memberItems.getJSONObject(i)
                FolderMembership(text(member, "folderId").also { require(it in folderIds) },
                    text(member, "entryId").also { require(it in audioIds) },
                    timestamp(member, "updatedAt"), device(member), timestamp(member, "deletedAt"))
            }
            require(members.map { it.folderId to it.entryId }.distinct().size == members.size) { "Duplicate membership pairs" }
            return SyncDocument(AppPreferences.fromJson(settings.getJSONObject("value")), timestamp(settings, "updatedAt"), device(settings), entries, folders, members).also {
                require(it.toJson().toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Sync file exceeds 2 MiB" }
            }
        }

        private fun metadata(json: JSONObject): MediaMetadata {
            fun optionalText(key: String): String? = if (json.isNull(key)) null else text(json, key).also {
                require(it.length <= 1000 && it.none(Char::isISOControl)) { "Invalid metadata $key" }
            }
            fun integer(key: String, max: Long): Long? = if (json.isNull(key)) null else timestamp(json, key).also { require(it in 1..max) }
            val duration = if (json.isNull("duration")) null else {
                require(json.get("duration") is Number)
                json.getDouble("duration").also { require(it.isFinite() && it in 0.0..9_007_199_254_740_991.0) }
            }
            return MediaMetadata(optionalText("artist"), optionalText("album"), optionalText("albumArtist"),
                integer("trackNumber", 4_294_967_295), optionalText("genre"), integer("year", 9999), duration)
        }
    }
}
