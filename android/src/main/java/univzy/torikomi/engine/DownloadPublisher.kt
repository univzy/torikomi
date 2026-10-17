package univzy.torikomi.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File

data class PublishResult(val fileName: String, val folderUri: String, val mediaUri: String)

/** Moves a downloaded file from the app-private dir to the user's destination. */
object DownloadPublisher {

    private const val TAG = "DownloadPublisher"

    /**
     * @param customFolderUri SAF tree URI string, or blank/"" for the default Downloads/Torikomi location.
     * @param type history downloadType ("video" | "audio" | "image")
     *
     * The display name is sanitized ([DownloadPaths.sanitizeFileName]) because the source file name
     * comes from the media title and can contain characters (e.g. `|`) that are legal on the internal
     * cache but break file creation in public storage / MediaStore. The sanitized name is returned so
     * history records the name actually written (open/share/delete resolve against it).
     */
    fun publish(context: Context, source: File, type: String, customFolderUri: String): PublishResult {
        val safeName = DownloadPaths.sanitizeFileName(source.name)
        Log.i(TAG, "publish sdk=${Build.VERSION.SDK_INT} custom=${customFolderUri.isNotBlank()} name='$safeName'")
        return if (customFolderUri.isNotBlank()) {
            val uri = publishToSaf(context, source, safeName, type, customFolderUri)
            PublishResult(fileName = safeName, folderUri = customFolderUri, mediaUri = uri.toString())
        } else {
            val uri = publishToDefault(context, source, safeName, type)
            PublishResult(fileName = safeName, folderUri = "default", mediaUri = uri.toString())
        }
    }

    private fun publishToDefault(context: Context, source: File, displayName: String, type: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/${DownloadPaths.relativeDir(type)}",
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            try {
                val out = resolver.openOutputStream(uri) ?: error("MediaStore openOutputStream failed")
                out.use { o -> source.inputStream().use { it.copyTo(o) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DownloadPaths.relativeDir(type),
            )
            if (!dir.exists() && !dir.mkdirs()) {
                error("Cannot create ${dir.absolutePath} — storage permission may be missing")
            }
            val dest = File(dir, displayName)
            source.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
            return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
        }
    }

    private fun publishToSaf(context: Context, source: File, displayName: String, type: String, treeUriString: String): Uri {
        val treeUri = Uri.parse(treeUriString)
        val resolver = context.contentResolver
        val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val ext = source.extension.lowercase()
        val mime = when (ext) {
            in setOf("mp3", "m4a", "aac", "wav", "flac", "opus", "ogg") -> "audio/*"
            in setOf("mp4", "mkv", "avi", "mov", "webm") -> "video/*"
            in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp") -> "image/*"
            else -> "*/*"
        }
        val prefixedName = "${DownloadPaths.mediaTypeFolder(type)}_$displayName"
        val docUri = DocumentsContract.createDocument(resolver, parentDocUri, mime, prefixedName)
            ?: error("SAF createDocument failed")
        try {
            val out = resolver.openOutputStream(docUri) ?: error("SAF openOutputStream failed")
            out.use { o -> source.inputStream().use { it.copyTo(o) } }
            return docUri
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, docUri) }
            throw t
        }
    }
}
