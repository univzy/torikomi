package univzy.torikomi.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import univzy.torikomi.data.model.ExtensionInfo
import univzy.torikomi.data.model.ExtensionStatus
import univzy.torikomi.data.model.InstalledExtensionInfo
import univzy.torikomi.data.model.ScrapeResult
import univzy.torikomi.data.model.extensionInfoFromJson
import univzy.torikomi.data.model.scrapeResultFromJson
import univzy.torikomi.service.ExtensionBridgeHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private val Context.extDataStore by preferencesDataStore(name = "torikomi_ext_prefs")
private val INSTALLED_KEY = stringSetPreferencesKey("installed_extensions")

private val CATALOG_URL =
    "https://raw.githubusercontent.com/univzy/torikomi-extensions/refs/heads/main/index.json"
private const val APK_BASE_URL =
    "https://raw.githubusercontent.com/univzy/torikomi-extensions/master/apk/"

class ExtensionRepository(
    private val context: Context,
    private val bridge: ExtensionBridgeHandler,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile private var catalogCache: List<ExtensionInfo>? = null

    fun invalidateCache() { catalogCache = null }


    private suspend fun loadInstalledIds(): MutableSet<String> =
        context.extDataStore.data.first()[INSTALLED_KEY]?.toMutableSet() ?: mutableSetOf()

    private suspend fun persistInstalledIds(ids: Set<String>) {
        context.extDataStore.edit { it[INSTALLED_KEY] = ids }
    }

    private fun inferPlatformId(ext: ExtensionInfo): String {
        val fromBaseUrl = ext.sources.firstOrNull()?.baseUrl?.lowercase().orEmpty()
        return when {
            fromBaseUrl.contains("instagram.com") -> "instagram"
            fromBaseUrl.contains("twitter.com") || fromBaseUrl.contains("x.com") -> "twitter"
            fromBaseUrl.contains("tiktok.com") -> "tiktok"
            fromBaseUrl.contains("youtube.com") || fromBaseUrl.contains("youtu.be") -> "youtube"
            fromBaseUrl.contains("facebook.com") || fromBaseUrl.contains("fb.watch") -> "facebook"
            fromBaseUrl.contains("threads.net") || fromBaseUrl.contains("threads.com") -> "threads"
            else -> ext.id.lowercase()
        }
    }


    suspend fun getDeviceInstalledExtensions(): List<InstalledExtensionInfo> =
        withContext(Dispatchers.IO) { bridge.getInstalledExtensions() }


    suspend fun fetchCatalog(forceRefresh: Boolean = false): List<ExtensionInfo> = withContext(Dispatchers.IO) {
        if (!forceRefresh && catalogCache != null) return@withContext catalogCache!!

        val deviceExtensions = bridge.getInstalledExtensions()

        val catalog = runCatching { downloadCatalog() }
            .getOrElse { emptyList() }
            .toMutableList()

        catalog.forEach { ext ->
            val targetPlatform = inferPlatformId(ext)
            val devExt = deviceExtensions.find {
                it.packageName.equals(ext.pkg, ignoreCase = true) ||
                    it.id.equals(ext.id, ignoreCase = true) ||
                    it.platform.equals(targetPlatform, ignoreCase = true)
            }
            ext.status = when {
                devExt == null -> ExtensionStatus.AVAILABLE
                isNewerVersion(ext.version, devExt.version) -> ExtensionStatus.UPDATE_AVAILABLE
                else -> ExtensionStatus.INSTALLED
            }
            if (devExt != null) {
                ext.dynamicPlatform = devExt.platform
                ext.dynamicPlatformName = devExt.platformName
                ext.dynamicColor = devExt.color
                ext.dynamicIcon = devExt.fontAwesomeIcon
                ext.dynamicDownloaderName = devExt.downloaderName
                ext.dynamicDescription = devExt.description
            }
        }

        val catalogPackages = catalog.map { it.pkg }.toSet()
        val catalogIds = catalog.map { it.id.lowercase() }.toSet()
        deviceExtensions.filter {
            it.packageName !in catalogPackages && it.id.lowercase() !in catalogIds
        }.forEach { dev ->
            catalog.add(
                ExtensionInfo(
                    name         = dev.platform,
                    pkg          = dev.packageName,
                    apk          = "",
                    lang         = "local",
                    version      = dev.version,
                    status       = ExtensionStatus.INSTALLED,
                    dynamicPlatform = dev.platform,
                    dynamicPlatformName = dev.platformName,
                    dynamicColor = dev.color,
                    dynamicIcon  = dev.fontAwesomeIcon,
                    dynamicDownloaderName = dev.downloaderName,
                    dynamicDescription = dev.description,
                )
            )
        }

        persistInstalledIds(deviceExtensions.map { it.id }.toSet())
        catalogCache = catalog
        catalog
    }


    suspend fun installExtension(ext: ExtensionInfo) = withContext(Dispatchers.IO) {
        val apkPath = downloadApk(ext)
        bridge.installApk(apkPath)
    }

    suspend fun uninstallExtension(ext: ExtensionInfo): Boolean {
        val installed = bridge.getInstalledExtensions()
        val target = installed.firstOrNull {
            it.packageName.equals(ext.pkg, ignoreCase = true) ||
                it.id.equals(ext.id, ignoreCase = true) ||
                (!ext.dynamicPlatform.isNullOrBlank() && it.platform.equals(ext.dynamicPlatform, ignoreCase = true))
        }

        val packageToUninstall = target?.packageName
            ?.takeIf { it.isNotBlank() }
            ?: ext.pkg.takeIf { it.isNotBlank() }
            ?: return false

        val launched = bridge.uninstallPackage(packageToUninstall)
        if (!launched) return false

        val ids = loadInstalledIds().also {
            it.remove(ext.id)
            target?.id?.let { detectedId -> it.remove(detectedId) }
        }
        persistInstalledIds(ids)
        return true
    }


    suspend fun scrape(extensionId: String, url: String, cfCookies: String? = null): ScrapeResult =
        withContext(Dispatchers.IO) {
            val json = bridge.scrapeWithExtension(extensionId, url, cfCookies)
            scrapeResultFromJson(JSONObject(json))
        }


    private fun isNewerVersion(catalogVersion: String, installedVersion: String): Boolean {
        fun parse(v: String) = v.trim().removePrefix("v").split(".")
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parse(catalogVersion)
        val b = parse(installedVersion)
        val len = maxOf(a.size, b.size)
        for (i in 0 until len) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai > bi) return true
            if (ai < bi) return false
        }
        return false
    }

    private fun downloadCatalog(): List<ExtensionInfo> {
        val response = client.newCall(Request.Builder().url(CATALOG_URL).build()).execute()
        val body = response.body?.string() ?: throw Exception("Catalog is empty")
        val arr = JSONArray(body)
        return (0 until arr.length()).map { extensionInfoFromJson(arr.getJSONObject(it)) }
    }

    private fun downloadApk(ext: ExtensionInfo): String {
        val dir = File(context.cacheDir, "extensions").also { it.mkdirs() }
        val file = File(dir, ext.apk)
        val request = Request.Builder().url("$APK_BASE_URL${ext.apk}").build()
        client.newCall(request).execute().use { response ->
            response.body?.byteStream()?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }

}
