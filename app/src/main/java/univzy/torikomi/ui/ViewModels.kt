package univzy.torikomi.ui

import android.app.DownloadManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import univzy.torikomi.Torikomi
import univzy.torikomi.data.model.DownloadItem
import univzy.torikomi.data.model.DownloadHistory
import univzy.torikomi.data.model.ExtensionInfo
import univzy.torikomi.data.model.ExtensionStatus
import univzy.torikomi.data.model.ScrapeResult
import univzy.torikomi.data.repository.ExtensionRepository
import univzy.torikomi.data.repository.HistoryRepository
import univzy.torikomi.data.repository.SettingsRepository
import univzy.torikomi.data.repository.AppReleaseInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

private fun compareVersionName(latest: String, current: String): Int {
    fun normalize(input: String): List<Int> {
        val cleaned = input.trim().removePrefix("v").removePrefix("V")
        return cleaned.split(".").map { it.toIntOrNull() ?: 0 }
    }
    val a = normalize(latest)
    val b = normalize(current)
    val max = maxOf(a.size, b.size)
    for (i in 0 until max) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}


private val Context.themeDataStore by preferencesDataStore("theme_prefs")
private val THEME_KEY = stringPreferencesKey("theme_mode")

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

class ThemeViewModel(private val context: Context) : ViewModel() {

    private val _themeMode = MutableStateFlow(AppThemeMode.SYSTEM)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = context.themeDataStore.data.first()[THEME_KEY]
            _themeMode.value = when (saved) {
                "dark"  -> AppThemeMode.DARK
                "light" -> AppThemeMode.LIGHT
                else    -> AppThemeMode.SYSTEM
            }
        }
    }

    fun toggleTheme(isDark: Boolean) {
        val next = if (isDark) AppThemeMode.LIGHT else AppThemeMode.DARK
        _themeMode.value = next
        viewModelScope.launch {
            context.themeDataStore.edit { it[THEME_KEY] = next.name.lowercase() }
        }
    }
}


data class ExtensionsUiState(
    val catalog: List<ExtensionInfo>  = emptyList(),
    val isLoading: Boolean            = false,
    val error: String?                = null,
)

class ExtensionViewModel(private val repo: ExtensionRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ExtensionsUiState(isLoading = true))
    val uiState: StateFlow<ExtensionsUiState> = _uiState.asStateFlow()

    val installedExtensions: List<ExtensionInfo>
        get() = _uiState.value.catalog.filter { it.status == ExtensionStatus.INSTALLED }

    init { fetchCatalog() }

    fun fetchCatalog() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { repo.fetchCatalog() }
                .onSuccess { catalog -> _uiState.update { it.copy(catalog = catalog, isLoading = false) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun refreshCatalog() {
        repo.invalidateCache()
        fetchCatalog()
    }

    fun install(ext: ExtensionInfo) {
        viewModelScope.launch {
            runCatching { repo.installExtension(ext) }
            repo.invalidateCache()
            fetchCatalog()
        }
    }

    fun uninstall(ext: ExtensionInfo) {
        viewModelScope.launch {
            runCatching { repo.uninstallExtension(ext) }
            repo.invalidateCache()
            fetchCatalog()
        }
    }
}


data class ActiveDownload(
    val requestId: Long,
    val fileName: String,
    val itemLabel: String,
    val progress: Int,
    val statusText: String,
)

data class DownloadUiState(
    val isLoading: Boolean = false,
    val result: ScrapeResult? = null,
    val url: String = "",
    val activeDownloads: List<ActiveDownload> = emptyList(),
    val saveDirectoryLabel: String = "Downloads/Torikomi",
    val playlistItemResults: Map<String, ScrapeResult> = emptyMap(),
    val loadingPlaylistItems: Set<String> = emptySet(),
)

class DownloadViewModel(
    private val repo: ExtensionRepository,
    private val historyRepo: HistoryRepository,
    private val settingsRepo: SettingsRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()
    private val resolverClient = OkHttpClient.Builder().build()

    private fun isNetworkAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun friendlyNetworkError(throwable: Throwable, platform: String, platformId: String): ScrapeResult {
        return ScrapeResult(
            extensionId = platformId,
            platform = platform,
            title = "",
            error = humanizeErrorMessage(throwable.message),
            downloadItems = emptyList(),
        )
    }

    /**
     * Translate technical error strings into user-friendly messages.
     */
    private fun humanizeErrorMessage(raw: String?): String {
        val original = raw?.trim().orEmpty()
        if (original.isEmpty()) return "Failed to fetch media. Please try again."

        val stripped = original.replaceFirst(
            Regex("^.*?(?:download|unduh|scrape)\\s+(?:failed|gagal)\\s*:\\s*", RegexOption.IGNORE_CASE),
            ""
        ).ifBlank { original }

        val lower = stripped.lowercase()

        return when {
            "unable to resolve host" in lower ||
            "no address associated" in lower ||
            "nodename nor servname" in lower ||
            "name or service not known" in lower ->
                "Could not reach the server. Your network may be blocking it — try switching connection (Wi-Fi/data) or using a VPN."

            "failed to connect" in lower ||
            "connection refused" in lower ||
            "network is unreachable" in lower ||
            "econnreset" in lower ||
            "connection reset" in lower ->
                "Could not connect to server. It may be temporarily unavailable, please try again later."

            "timeout" in lower ||
            "timed out" in lower ->
                "Request timed out. Check your connection and try again."

            "ssl" in lower ||
            "handshake" in lower ||
            "certificate" in lower ||
            "trust anchor" in lower ->
                "Secure connection failed. Update your device or try again on a different network."

            "is not responding or not installed" in lower ->
                "The extension is not installed or not responding. Please reinstall it from the Extensions tab."

            // Server returned non-2xx
            Regex("api returned status\\s+(\\d+)", RegexOption.IGNORE_CASE).containsMatchIn(lower) -> {
                val code = Regex("api returned status\\s+(\\d+)", RegexOption.IGNORE_CASE)
                    .find(lower)?.groupValues?.get(1) ?: ""
                when {
                    code.startsWith("5") -> "The downloader service is temporarily down (HTTP $code). Please try again later."
                    code == "403" || code == "401" -> "Access blocked by the source (HTTP $code). The link may be private or rate-limited."
                    code == "404" -> "Content not found (HTTP 404). The link may have been removed."
                    code == "429" -> "Too many requests. Please wait a moment and try again."
                    else -> "The downloader service returned an error (HTTP $code). Please try again later."
                }
            }

            else -> stripped
        }
    }

    init {
        refreshSavePathLabel()
    }

    fun updateUrl(url: String) = _uiState.update { it.copy(url = url) }

    fun refreshSavePathLabel() {
        viewModelScope.launch {
            val path = settingsRepo.getDownloadPathSettings()
            val label = if (path.folderUri.isNotBlank()) {
                "Custom: ${path.folderName}"
            } else {
                "Torikomi"
            }
            _uiState.update {
                it.copy(
                    saveDirectoryLabel = label,
                )
            }
        }
    }

    fun fetchMedia(platform: String, cfCookies: String? = null) {
        val url = _uiState.value.url.trim()
        if (url.isEmpty()) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    result = ScrapeResult(
                        extensionId = "",
                        platform = platform,
                        title = "",
                        error = "URL is empty",
                        downloadItems = emptyList(),
                    )
                )
            }
            return
        }

        val platformId = normalizeRequestedExtensionId(platform)
        if (platformId.isBlank()) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    result = ScrapeResult(
                        extensionId = "",
                        platform = platform,
                        title = "",
                        error = "Invalid platform: $platform",
                        downloadItems = emptyList(),
                    )
                )
            }
            return
        }

        if (!isUrlAllowedForPlatform(url, platformId)) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    result = ScrapeResult(
                        extensionId = platformId,
                        platform = platform,
                        title = "",
                        error = "URL does not match platform ${platform.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }}",
                        downloadItems = emptyList(),
                    )
                )
            }
            return
        }

        if (!isNetworkAvailable()) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    result = ScrapeResult(extensionId = platformId, platform = platform, title = "",
                        error = "No internet connection. Check your network and try again.", downloadItems = emptyList())
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, result = null) }
            val result = runCatching {
                repo.scrape(platformId, url, cfCookies)
            }.getOrElse {
                friendlyNetworkError(it, platform, platformId)
            }

            val finalResult = if (!result.isSuccess) {
                result.copy(error = humanizeErrorMessage(result.error))
            } else result
            _uiState.update { it.copy(result = finalResult, isLoading = false) }
        }
    }

    fun fetchPlaylistItemMedia(platform: String, itemKey: String, videoUrl: String) {
        val existing = _uiState.value.playlistItemResults[itemKey]
        if (existing != null && existing.isSuccess) return  // already loaded

        viewModelScope.launch {
            _uiState.update { it.copy(loadingPlaylistItems = it.loadingPlaylistItems + itemKey) }
            val result = runCatching {
                repo.scrape(normalizeRequestedExtensionId(platform), videoUrl)
            }.getOrElse {
                ScrapeResult(extensionId = platform, platform = platform, title = "", error = it.message, downloadItems = emptyList())
            }
            _uiState.update {
                it.copy(
                    playlistItemResults  = it.playlistItemResults + (itemKey to result),
                    loadingPlaylistItems = it.loadingPlaylistItems - itemKey,
                )
            }
        }
    }

    fun startDownload(
        platform: String,
        sourceUrl: String,
        sourceTitle: String,
        thumbnailUrl: String,
        item: DownloadItem,
        fileName: String,
        fileCount: Int = 1,
    ) {
        viewModelScope.launch {
            val resolvingEntry = ActiveDownload(
                requestId  = -System.currentTimeMillis(),
                fileName   = "Resolving link...",
                itemLabel  = item.label,
                progress   = -1,
                statusText = "Contacting server...",
            )
            _uiState.update { it.copy(activeDownloads = listOf(resolvingEntry) + it.activeDownloads) }

            val resolvedItem = runCatching { resolveDeferredDownloadItem(item) }.getOrElse { throwable ->
                _uiState.update { it.copy(activeDownloads = it.activeDownloads.filterNot { e -> e.requestId == resolvingEntry.requestId }) }
                android.widget.Toast.makeText(
                    appContext,
                    "Failed to get download link: ${throwable.message ?: "Unknown error"}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            _uiState.update { it.copy(activeDownloads = it.activeDownloads.filterNot { e -> e.requestId == resolvingEntry.requestId }) }

            if (resolvedItem.url.isBlank()) {
                android.widget.Toast.makeText(
                    appContext,
                    "Empty download link for ${resolvedItem.label}",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            var requestId: Long? = null
            try {
                val settings = settingsRepo.getDownloadPathSettings()
                val safeFileName = sanitizeFileName(fileName)

                val mediaTypeFolder = univzy.torikomi.data.repository.MEDIA_TYPE_FOLDERS[resolvedItem.type] ?: "VIDEO"
                val relativePath = "Torikomi/$mediaTypeFolder/$safeFileName"

                val request = DownloadManager.Request(Uri.parse(resolvedItem.url))
                    .setTitle(safeFileName)
                    .setDescription("Downloading media")
                    .setMimeType(resolvedItem.mimeType)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, relativePath)

                val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                requestId = dm.enqueue(request)

                _uiState.update {
                    it.copy(
                        activeDownloads = listOf(
                            ActiveDownload(
                                requestId = requestId,
                                fileName = safeFileName,
                                itemLabel = resolvedItem.label,
                                progress = 0,
                                statusText = "Pending",
                            )
                        ) + it.activeDownloads,
                    )
                }

                monitorDownload(
                    dm = dm,
                    requestId = requestId,
                    platform = platform,
                    sourceUrl = sourceUrl,
                    sourceTitle = sourceTitle,
                    thumbnailUrl = thumbnailUrl,
                    downloadType = resolvedItem.type,
                    fileCount = fileCount,
                    filePath = relativePath,
                    fileName = safeFileName,
                    folderUri = settings.folderUri,
                    quality = item.quality,
                )
            } catch (t: Throwable) {
                requestId?.let { failedId ->
                    _uiState.update {
                        it.copy(activeDownloads = it.activeDownloads.filterNot { entry -> entry.requestId == failedId })
                    }
                }
                android.widget.Toast.makeText(
                    appContext,
                    "Download failed: ${t.message ?: "Unknown error"}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private suspend fun resolveDeferredDownloadItem(item: DownloadItem): DownloadItem {
        val resolver = item.extra["resolver"]?.toString().orEmpty()
        return when {
            resolver.isEmpty() -> item
            resolver.endsWith("-polling") || resolver == "polling" -> resolvePollingItem(item)
            resolver.endsWith("-post-json") || resolver == "post-json" -> resolvePostJsonItem(item)
            else -> item
        }
    }

    /** Generic GET polling resolver. Extra keys: pollUrl (required), maxAttempts, pollInterval, statusKey, fileUrlKey, completedStatus. */
    private suspend fun resolvePollingItem(item: DownloadItem): DownloadItem {
        val resolver = item.extra["resolver"]?.toString() ?: ""
        val pollUrl = item.extra["pollUrl"]?.toString().orEmpty()
        if (pollUrl.isBlank()) throw IllegalStateException("[$resolver] extra 'pollUrl' is missing. Keys: ${item.extra.keys.toList()}")

        val maxAttempts   = item.extra["maxAttempts"]?.toString()?.toIntOrNull() ?: 60
        val delayMs       = item.extra["pollInterval"]?.toString()?.toLongOrNull() ?: 5000L
        val statusKey     = item.extra["statusKey"]?.toString()?.takeIf { it.isNotBlank() } ?: "status"
        val fileUrlKey    = item.extra["fileUrlKey"]?.toString()?.takeIf { it.isNotBlank() } ?: "fileUrl"
        val completedVal  = item.extra["completedStatus"]?.toString()?.takeIf { it.isNotBlank() } ?: "completed"

        for (attempt in 0 until maxAttempts) {
            val fileUrl = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(pollUrl)
                    .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0")
                    .header("accept", "application/json, text/plain, */*")
                    .get()
                    .build()

                resolverClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val raw = response.body?.string().orEmpty()
                    if (raw.isBlank()) return@withContext null
                    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
                    val status = json.optString(statusKey).orEmpty()
                    when {
                        status.equals(completedVal, ignoreCase = true) -> {
                            val url = json.optString(fileUrlKey).orEmpty()
                            url.takeIf { it.isNotBlank() && !it.startsWith("In Processing") }
                        }
                        status.equals("error", ignoreCase = true) || status.equals("failed", ignoreCase = true) -> {
                            throw IllegalStateException("[$resolver] render failed: ${json.optString("message").ifBlank { status }}")
                        }
                        else -> null
                    }
                }
            }
            if (fileUrl != null) return item.copy(url = fileUrl)
            if (attempt < maxAttempts - 1) delay(delayMs)
        }

        throw IllegalStateException("[$resolver] polling timed out after ${maxAttempts * delayMs / 1000}s")
    }

    /** Generic POST-JSON resolver. Extra keys: apiUrl (required), payload (JSON string), responseKey (default "url"), headers (JSON string). */
    private suspend fun resolvePostJsonItem(item: DownloadItem): DownloadItem {
        val resolver = item.extra["resolver"]?.toString() ?: ""
        val apiUrl = item.extra["apiUrl"]?.toString().orEmpty()
        if (apiUrl.isBlank()) throw IllegalStateException("[$resolver] extra 'apiUrl' is missing. Keys: ${item.extra.keys.toList()}")

        val payloadJson  = item.extra["payload"]?.toString()?.takeIf { it.isNotBlank() } ?: "{}"
        val responseKey  = item.extra["responseKey"]?.toString()?.takeIf { it.isNotBlank() } ?: "url"

        val body = payloadJson.toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0")
            .header("accept", "application/json, text/plain, */*")
            .post(body)

        val headersJson = item.extra["headers"]?.toString()
        if (!headersJson.isNullOrBlank()) {
            runCatching { JSONObject(headersJson) }.getOrNull()?.let { headers ->
                headers.keys().forEach { key -> requestBuilder.header(key, headers.optString(key)) }
            }
        }

        return withContext(Dispatchers.IO) {
            resolverClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty().take(300)
                    throw IllegalStateException("[$resolver] HTTP ${response.code}: $errorBody")
                }
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) throw IllegalStateException("[$resolver] Response is empty")
                val json = runCatching { JSONObject(raw) }.getOrElse {
                    throw IllegalStateException("[$resolver] Response is not JSON: ${raw.take(100)}")
                }
                val resolvedUrl = json.optString(responseKey)
                if (resolvedUrl.isBlank()) {
                    throw IllegalStateException("[$resolver] Key '$responseKey' not found. Response: ${raw.take(200)}")
                }
                item.copy(url = resolvedUrl)
            }
        }
    }

    private suspend fun monitorDownload(
        dm: DownloadManager,
        requestId: Long,
        platform: String,
        sourceUrl: String,
        sourceTitle: String,
        thumbnailUrl: String,
        downloadType: String,
        fileCount: Int,
        filePath: String,
        fileName: String,
        folderUri: String,
        quality: String = "",
    ) {
        val query = DownloadManager.Query().setFilterById(requestId)
        while (true) {
            val cursor = dm.query(query)
            if (!cursor.moveToFirst()) {
                cursor.close()
                removeActiveDownload(requestId)
                break
            }

            val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            cursor.close()

            val progress = if (bytesTotal > 0L) ((bytesDownloaded * 100L) / bytesTotal).toInt().coerceIn(0, 100) else 0
            val statusText = downloadStatusText(status)
            updateActiveDownloadProgress(requestId, progress, statusText)

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val downloadedFile = localUri
                        ?.takeIf { it.startsWith("file://") }
                        ?.let { uri -> java.io.File(Uri.parse(uri).path.orEmpty()) }
                        ?: java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), filePath)
                    
                    var finalFolderUri = ""
                    if (downloadedFile.exists()) {
                        if (folderUri.isNotBlank()) {
                            finalFolderUri = copyFileToCustomFolder(downloadedFile, folderUri, downloadType)
                        }
                        android.media.MediaScannerConnection.scanFile(
                            appContext,
                            arrayOf(downloadedFile.absolutePath),
                            null,
                            null
                        )
                    }
                    
                    historyRepo.saveDownload(
                        platform = platform,
                        title = sourceTitle.ifBlank { deriveFallbackTitle(platform, sourceUrl) },
                        url = sourceUrl,
                        thumbnailUrl = thumbnailUrl,
                        downloadType = downloadType,
                        fileCount = fileCount,
                        folderUri = finalFolderUri.ifBlank { "default" },
                        fileName = fileName,
                        quality = quality,
                    )
                    
                    removeActiveDownload(requestId)
                    break
                }
                DownloadManager.STATUS_FAILED -> {
                    removeActiveDownload(requestId)
                    break
                }
                else -> delay(700)
            }
        }
    }

    private fun updateActiveDownloadProgress(requestId: Long, progress: Int, statusText: String) {
        _uiState.update {
            it.copy(
                activeDownloads = it.activeDownloads.map { item ->
                    if (item.requestId == requestId) item.copy(progress = progress, statusText = statusText) else item
                }
            )
        }
    }

    private fun removeActiveDownload(requestId: Long) {
        _uiState.update {
            it.copy(activeDownloads = it.activeDownloads.filterNot { item -> item.requestId == requestId })
        }
    }

    private fun sanitizePathPart(value: String): String {
        val sanitized = value.trim().replace(Regex("[^a-zA-Z0-9 _-]"), "")
        return sanitized.ifBlank { "Torikomi" }
    }

    private fun sanitizeFileName(value: String): String =
        value.trim().replace(Regex("[\\\\/:*?\"<>|]+"), "_")

    private fun copyFileToCustomFolder(sourceFile: java.io.File, folderUri: String, downloadType: String): String {
        return try {
            val mediaTypeFolder = univzy.torikomi.data.repository.MEDIA_TYPE_FOLDERS[downloadType] ?: "VIDEO"
            val uri = android.net.Uri.parse(folderUri)
            val resolver = appContext.contentResolver
            
            // Get folder URI for creating documents
            val targetDirUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            
            // Determine MIME type based on file extension
            val mimeType = when {
                sourceFile.extension.lowercase().matches(Regex("mp3|m4a|wav|flac")) -> "audio/*"
                sourceFile.extension.lowercase().matches(Regex("mp4|mkv|avi|mov")) -> "video/*"
                sourceFile.extension.lowercase().matches(Regex("jpg|jpeg|png|gif|bmp")) -> "image/*"
                else -> "*/*"
            }
            
            // Create document in custom folder with prefix (MUSIC_filename.mp3)
            val newDocumentUri = DocumentsContract.createDocument(
                resolver,
                targetDirUri,
                mimeType,
                mediaTypeFolder + "_" + sourceFile.name
            )
            
            if (newDocumentUri != null) {
                resolver.openOutputStream(newDocumentUri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                folderUri
            } else {
                ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun downloadStatusText(status: Int): String = when (status) {
        DownloadManager.STATUS_PENDING -> "Pending"
        DownloadManager.STATUS_PAUSED -> "Paused"
        DownloadManager.STATUS_RUNNING -> "Downloading"
        DownloadManager.STATUS_SUCCESSFUL -> "Completed"
        DownloadManager.STATUS_FAILED -> "Failed"
        else -> "Unknown"
    }

    private fun directoryLabel(directoryType: String): String = when (directoryType) {
        Environment.DIRECTORY_DOWNLOADS -> "Downloads"
        Environment.DIRECTORY_MOVIES -> "Movies"
        Environment.DIRECTORY_PICTURES -> "Pictures"
        Environment.DIRECTORY_MUSIC -> "Music"
        else -> "Downloads"
    }

    /**
     * Build a fallback title for History when the extension doesn't supply one.
     *
     * Platform-agnostic by design — it never hard-codes any extension name,
     * so adding a new extension does NOT require updating this method:
     *
     *  • The platform label is derived by title-casing the platform id itself
     *    (e.g. "snapsave_instagram" → "Snapsave Instagram").
     *  • The URL identifier is heuristically extracted as the last segment
     *    that looks like an alphanumeric id (≥ 4 chars), so it works for
     *    URLs like /p/CzExAbCd/, /video/123456, /watch?v=AbCdEf, etc.
     *
     * Examples:
     *  • instagram + .../p/CzExAbCdEfG/  → "Instagram • CzExAbCdEfG"
     *  • tiktok    + .../video/12345     → "Tiktok • 12345"
     *  • newplat   + https://x.io/foo/bar → "Newplat • bar"
     */
    private fun deriveFallbackTitle(platform: String, url: String): String {
        val displayPlatform = platform
            .trim()
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
            }
            .ifBlank { "Download" }

        val id = extractIdFromUrl(url)
        return if (id != null) "$displayPlatform • $id" else "$displayPlatform Post"
    }


    private fun extractIdFromUrl(url: String): String? {
        val cleaned = url.trim()
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
        if (cleaned.isBlank()) return null

        val queryString = url.substringAfter('?', "")
        if (queryString.isNotBlank()) {
            for (param in queryString.split('&')) {
                val (rawKey, rawValue) = param.split('=', limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else return@let "" to ""
                }
                if (rawKey.lowercase() in setOf("v", "id", "videoid", "vid") &&
                    rawValue.matches(Regex("[\\w-]{4,}"))
                ) {
                    return rawValue
                }
            }
        }

        val segments = cleaned.split('/').filter { it.isNotBlank() }
  
        val genericKeywords = setOf(
            "p", "post", "posts", "video", "videos", "watch", "reel", "reels",
            "shorts", "tv", "status", "track", "album", "playlist",
        )
        for (segment in segments.asReversed()) {
            if (segment.length < 4) continue
            if (segment.lowercase() in genericKeywords) continue
            if (!segment.matches(Regex("[\\w-]+"))) continue
            return segment
        }
        return null
    }

    private fun normalizeRequestedExtensionId(platform: String): String {
        val normalized = platform
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

        return when (normalized) {
            "x" -> "twitter"
            else -> normalized
        }
    }

    private fun isUrlAllowedForPlatform(url: String, platformId: String): Boolean {
        val rules = platformUrlRules[platformId] ?: return true
        return rules.any { it.containsMatchIn(url) }
    }

    private val platformUrlRules: Map<String, List<Regex>> = mapOf(
        "tiktok" to listOf(
            Regex("(^|[./])tiktok\\.com(/|$)", RegexOption.IGNORE_CASE),
            Regex("(^|[./])vm\\.tiktok\\.com(/|$)", RegexOption.IGNORE_CASE),
            Regex("(^|[./])vt\\.tiktok\\.com(/|$)", RegexOption.IGNORE_CASE),
        ),
        "instagram" to listOf(
            Regex("(^|[./])instagram\\.com(/|$)", RegexOption.IGNORE_CASE),
            Regex("(^|[./])instagr\\.am(/|$)", RegexOption.IGNORE_CASE),
        ),
        "twitter" to listOf(
            Regex("(^|[./])twitter\\.com(/|$)", RegexOption.IGNORE_CASE),
            Regex("(^|[./])x\\.com(/|$)", RegexOption.IGNORE_CASE),
            Regex("(^|[./])t\\.co(/|$)", RegexOption.IGNORE_CASE),
        ),
        "facebook" to listOf(
            Regex("(^|[./])facebook\\.com(/|$)", RegexOption.IGNORE_CASE),
            Regex("(^|[./])m\\.facebook\\.com(/|$)", RegexOption.IGNORE_CASE),
            Regex("(^|[./])fb\\.watch(/|$)", RegexOption.IGNORE_CASE),
            Regex("(^|[./])fb\\.me(/|$)", RegexOption.IGNORE_CASE),
        ),
        "threads" to listOf(
            Regex("(^|[./])threads\\.net(/|$)", RegexOption.IGNORE_CASE),
            Regex("(^|[./])threads\\.com(/|$)", RegexOption.IGNORE_CASE),
        ),
    )
}


data class HistoryUiState(
    val history: List<DownloadHistory>   = emptyList(),
    val isLoading: Boolean               = false,
    val typeFilter: String               = "all",
    val platformFilter: String           = "all",
    val availablePlatforms: List<String> = emptyList(),
)

class HistoryViewModel(private val repo: HistoryRepository) : ViewModel() {

    private val _typeFilter     = MutableStateFlow("all")
    private val _platformFilter = MutableStateFlow("all")

    val uiState: StateFlow<HistoryUiState> = combine(
        repo.historyFlow,
        _typeFilter,
        _platformFilter,
    ) { allHistory, typeFilter, platformFilter ->
        val availablePlatforms = allHistory
            .map { it.platform.lowercase() }
            .distinct()
            .sorted()
        var history = if (typeFilter != "all") allHistory.filter { it.downloadType == typeFilter } else allHistory
        if (platformFilter != "all") history = history.filter { it.platform.lowercase() == platformFilter }
        HistoryUiState(
            history            = history,
            isLoading          = false,
            typeFilter         = typeFilter,
            platformFilter     = platformFilter,
            availablePlatforms = availablePlatforms,
        )
    }.stateIn(
        scope         = viewModelScope,
        started       = SharingStarted.WhileSubscribed(5_000),
        initialValue  = HistoryUiState(isLoading = true),
    )

    fun setTypeFilter(filter: String)     { _typeFilter.value     = filter }
    fun setPlatformFilter(filter: String) { _platformFilter.value = filter }

    fun deleteItem(item: DownloadHistory) {
        viewModelScope.launch { repo.deleteHistoryAndFile(item) }
    }

    fun clearAll() {
        viewModelScope.launch { repo.clearAllHistoryAndFiles() }
    }
}


data class StartupUpdateUiState(
    val currentVersion: String = "-",
    val updateRelease: AppReleaseInfo? = null,
)

class StartupUpdateViewModel(
    private val repo: SettingsRepository,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StartupUpdateUiState())
    val uiState: StateFlow<StartupUpdateUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(currentVersion = appVersionName()) }
        checkForStartupUpdate()
    }

    fun dismissUpdatePopup() {
        _uiState.update { it.copy(updateRelease = null) }
    }

    private fun checkForStartupUpdate() {
        viewModelScope.launch {
            val currentVersion = _uiState.value.currentVersion
            runCatching { repo.fetchLatestRelease() }
                .onSuccess { release ->
                    val hasUpdate = compareVersionName(release.tagName, currentVersion) > 0
                    if (!hasUpdate) return@onSuccess

                    _uiState.update { it.copy(updateRelease = release) }

                    if (repo.shouldNotifyFor(release.tagName)) {
                        notifyUpdate(release)
                        repo.markUpdateNotified(release.tagName)
                    }
                }
        }
    }

    private fun notifyUpdate(release: AppReleaseInfo) {
        if (!hasNotificationPermission()) return
        val openReleaseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            991,
            openReleaseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (release.tagName.isNotBlank()) {
            "Update available: ${release.tagName}"
        } else {
            "Torikomi update available"
        }

        val body = release.name.ifBlank { "Tap to view changelog and update details" }

        val notification = NotificationCompat.Builder(context, Torikomi.UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(991, notification)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun appVersionName(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "-"
    }.getOrDefault("-")
}


data class SettingsUiState(
    val currentVersion: String = "-",
    val downloadFolderUri: String = "",
    val downloadFolderName: String = "Select Folder",
    val isCheckingUpdate: Boolean = false,
    val latestRelease: AppReleaseInfo? = null,
    val hasUpdate: Boolean = false,
    val updateError: String? = null,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(currentVersion = appVersionName()) }
        loadDownloadSettings()
    }

    fun loadDownloadSettings() {
        viewModelScope.launch {
            val settings = repo.getDownloadPathSettings()
            _uiState.update {
                it.copy(
                    downloadFolderUri = settings.folderUri,
                    downloadFolderName = settings.folderName,
                )
            }
        }
    }

    fun setDownloadFolder(folderUri: String, folderName: String) {
        viewModelScope.launch {
            repo.setDownloadFolder(folderUri, folderName)
            _uiState.update {
                it.copy(
                    downloadFolderUri = folderUri,
                    downloadFolderName = folderName,
                )
            }
        }
    }

    fun checkLatestRelease() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingUpdate = true, updateError = null) }
            runCatching { repo.fetchLatestRelease() }
                .onSuccess { release ->
                    val hasUpdate = compareVersionName(release.tagName, _uiState.value.currentVersion) > 0
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            latestRelease = release,
                            hasUpdate = hasUpdate,
                            updateError = null,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateError = e.message ?: "Failed to check updates",
                        )
                    }
                }
        }
    }

    private fun appVersionName(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "-"
    }.getOrDefault("-")
}


enum class SplashDestination { LOADING, ONBOARDING, HOME }

data class SplashUiState(
    val destination: SplashDestination = SplashDestination.LOADING,
    val loadingStep: String = "Starting up...",
)

class SplashViewModel(
    private val settingsRepo: SettingsRepository,
    private val extensionRepo: ExtensionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            delay(300)
            _uiState.update { it.copy(loadingStep = "Loading extension catalog...") }
            runCatching { extensionRepo.fetchCatalog() }
            _uiState.update { it.copy(loadingStep = "Discovering installed extensions...") }
            runCatching { extensionRepo.getDeviceInstalledExtensions() }
            val onboardingDone = runCatching { settingsRepo.isOnboardingDone() }.getOrDefault(false)
            _uiState.update {
                it.copy(
                    destination = if (onboardingDone) SplashDestination.HOME else SplashDestination.ONBOARDING,
                    loadingStep = if (onboardingDone) "Ready!" else "First time setup...",
                )
            }
        }
    }

    fun completeOnboarding(folderUri: String, folderName: String) {
        viewModelScope.launch {
            if (folderUri.isNotBlank()) {
                settingsRepo.setDownloadFolder(folderUri, folderName)
            }
            settingsRepo.markOnboardingDone()
            _uiState.update { it.copy(destination = SplashDestination.HOME) }
        }
    }
}


class AppViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val torikomi = app as? Torikomi ?: throw IllegalStateException("Torikomi not initialized")
        val extensionRepo = torikomi.extensionRepo
        return when {
            modelClass.isAssignableFrom(ThemeViewModel::class.java)     -> ThemeViewModel(app) as T
            modelClass.isAssignableFrom(ExtensionViewModel::class.java) ->
                ExtensionViewModel(extensionRepo) as T
            modelClass.isAssignableFrom(HistoryViewModel::class.java)   -> HistoryViewModel(HistoryRepository(app)) as T
            modelClass.isAssignableFrom(DownloadViewModel::class.java)  ->
                DownloadViewModel(
                    repo = extensionRepo,
                    historyRepo = HistoryRepository(app),
                    settingsRepo = SettingsRepository(app),
                    appContext = app,
                ) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java)   ->
                SettingsViewModel(SettingsRepository(app), app) as T
            modelClass.isAssignableFrom(StartupUpdateViewModel::class.java) ->
                StartupUpdateViewModel(SettingsRepository(app), app) as T
            modelClass.isAssignableFrom(SplashViewModel::class.java) ->
                SplashViewModel(
                    settingsRepo  = SettingsRepository(app),
                    extensionRepo = extensionRepo,
                ) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
