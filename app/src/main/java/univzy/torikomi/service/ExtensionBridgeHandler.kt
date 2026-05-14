package univzy.torikomi.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import univzy.torikomi.data.model.InstalledExtensionInfo
import java.io.File

class ExtensionBridgeHandler(private val context: Context) {

    companion object {
        private const val EXT_META_KEY = "torikomi.extension"
        private const val TAG = "ExtensionBridge"
    }

    @Suppress("DEPRECATION")
    fun getInstalledExtensions(): List<InstalledExtensionInfo> {
        val pm = context.packageManager
        val packages = runCatching {
            pm.getInstalledPackages(PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS)
        }.getOrElse { err ->
            Log.w(TAG, "getInstalledPackages failed: ${err.message}. Falling back to prefix scan.")
            return scanExtensionsByPrefix(pm)
        }
        val result = mutableListOf<InstalledExtensionInfo>()

        for (pkg in packages) {
            val meta = pkg.applicationInfo?.metaData ?: continue
            val isExtension = try {
                when (val v = meta.get(EXT_META_KEY)) {
                    is String  -> v == "true"
                    is Boolean -> v
                    else       -> false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading meta for ${pkg.packageName}: ${e.message}")
                false
            }
            if (!isExtension) continue

            val extId = meta.getString("torikomi.extension.id") ?: ""
            val authority = pkg.providers
                ?.firstOrNull { it.authority?.startsWith("torikomi.extension.") == true }
                ?.authority
                ?: "torikomi.extension.$extId"
            result.add(
                InstalledExtensionInfo(
                    id              = extId,
                    platform        = meta.getString("torikomi.extension.platform") ?: "",
                    platformName    = meta.getString("torikomi.extension.platformName"),
                    version         = meta.getString("torikomi.extension.version") ?: "1.0.0",
                    authority       = authority,
                    packageName     = pkg.packageName,
                    color           = meta.getString("torikomi.extension.color"),
                    fontAwesomeIcon = meta.getString("torikomi.extension.icon"),
                    downloaderName  = meta.getString("torikomi.extension.downloader"),
                    description     = meta.getString("torikomi.extension.description"),
                    urlPlaceholder  = meta.getString("torikomi.extension.urlPlaceholder"),
                )
            )
        }

        return result
    }

    @Suppress("DEPRECATION")
    private fun scanExtensionsByPrefix(pm: PackageManager): List<InstalledExtensionInfo> {
        val appInfos = runCatching { pm.getInstalledApplications(0) }.getOrElse {
            Log.e(TAG, "Fallback scan failed: ${it.message}")
            return emptyList()
        }

        val result = mutableListOf<InstalledExtensionInfo>()
        appInfos.asSequence()
            .map { it.packageName }
            .filter { it.startsWith("com.torikomi.extension") }
            .forEach { packageName ->
                runCatching {
                    val pkg = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS)
                    val meta = pkg.applicationInfo?.metaData ?: return@runCatching
                    val extId = meta.getString("torikomi.extension.id") ?: return@runCatching
                    val authority = pkg.providers
                        ?.firstOrNull { it.authority?.startsWith("torikomi.extension.") == true }
                        ?.authority
                        ?: "torikomi.extension.$extId"
                    result.add(
                        InstalledExtensionInfo(
                            id              = extId,
                            platform        = meta.getString("torikomi.extension.platform") ?: "",
                            platformName    = meta.getString("torikomi.extension.platformName"),
                            version         = meta.getString("torikomi.extension.version") ?: "1.0.0",
                            authority       = authority,
                            packageName     = pkg.packageName,
                            color           = meta.getString("torikomi.extension.color"),
                            fontAwesomeIcon = meta.getString("torikomi.extension.icon"),
                            downloaderName  = meta.getString("torikomi.extension.downloader"),
                            description     = meta.getString("torikomi.extension.description"),
                            urlPlaceholder  = meta.getString("torikomi.extension.urlPlaceholder"),
                        )
                    )
                }.onFailure {
                    Log.w(TAG, "Failed to parse extension package $packageName: ${it.message}")
                }
            }

        return result
    }

    /**
     * Scrapes a URL using the matching extension's ContentProvider.
     * Resolves extensionId by id first, then by platform field, so aliases
     * like "youtube" route correctly to an extension whose id is "ytdown".
     */
    fun scrapeWithExtension(extensionId: String, url: String, cfCookies: String? = null): String {
        val authority = resolveAuthority(extensionId)
        val uri = Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath("scrape")
            .appendQueryParameter("url", url)
            .apply { if (!cfCookies.isNullOrBlank()) appendQueryParameter("cfCookies", cfCookies) }
            .build()

        val cursor = context.contentResolver.query(uri, null, null, null, null)
            ?: return """{"error":"Extension '$extensionId' is not responding or not installed"}"""

        return cursor.use { c ->
            if (!c.moveToFirst()) return@use """{"error":"Extension '$extensionId' returned an empty result"}"""

            val errorCol   = c.getColumnIndex("error")
            val resultCol  = c.getColumnIndex("result")
            val error      = if (errorCol  >= 0) c.getString(errorCol)  else null
            val jsonResult = if (resultCol >= 0) c.getString(resultCol) else null

            when {
                !error.isNullOrBlank()      -> """{"error":"$error"}"""
                !jsonResult.isNullOrBlank() -> jsonResult
                else -> """{"error":"Extension '$extensionId' returned no data"}"""
            }
        }
    }

    fun installApk(filePath: String) {
        val apkFile = File(filePath)
        require(apkFile.exists()) { "APK not found at $filePath" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            error("Allow Torikomi Downloader to install apps from this source, then retry the extension installation.")
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }

    fun uninstallPackage(packageName: String): Boolean {
        val targetPkg = packageName.trim()
        require(targetPkg.isNotBlank()) { "Package name is blank" }

        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$targetPkg"))
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "uninstallPackage failed for $targetPkg: ${it.message}")
            false
        }
    }

    /** Resolves an extensionId to a ContentProvider authority.
     *  Priority: exact id match → platform match → direct fallback. */
    private fun resolveAuthority(extensionId: String): String {
        val id = extensionId.lowercase().trim()
        val installed = runCatching { getInstalledExtensions() }.getOrElse { return "torikomi.extension.$id" }
        installed.find { it.id.lowercase() == id }?.let { return it.authority }
        installed.find { it.platform.lowercase() == id }?.let { return it.authority }
        return "torikomi.extension.$id"
    }
}
