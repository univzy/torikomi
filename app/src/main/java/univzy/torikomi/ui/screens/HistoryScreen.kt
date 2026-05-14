package univzy.torikomi.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import univzy.torikomi.Torikomi
import univzy.torikomi.data.model.DownloadHistory
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val factory = AppViewModelFactory(context.applicationContext as Torikomi)
    val vm: HistoryViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }
    var deletePendingItem by remember { mutableStateOf<DownloadHistory?>(null) }

    Column(modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                FilterRow(
                    label  = "TYPE",
                    chips  = listOf("all" to "All", "video" to "Video", "audio" to "Audio", "image" to "Image"),
                    selected = state.typeFilter,
                    onSelect = vm::setTypeFilter,
                )
                Spacer(Modifier.height(8.dp))
                FilterRow(
                    label  = "PLATFORM",
                    chips  = listOf("all" to "All") + state.availablePlatforms.map { id ->
                        id to historyPlatformDisplayName(id)
                    },
                    selected = state.platformFilter,
                    onSelect = vm::setPlatformFilter,
                )
                if (!state.isLoading) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${state.history.size} items found",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface.copy(.5f)
                        ),
                    )
                }
            }
        }

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.history.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.History, null, Modifier.size(72.dp), tint = Color.LightGray)
                    Spacer(Modifier.height(16.dp))
                    Text("No downloads yet", color = Color.Gray, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Downloads will appear here", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            else -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear All")
                    }
                }
                LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    items(
                        count = state.history.size,
                        key   = { state.history[it].id },
                    ) { index ->
                        HistoryItem(
                            context  = context,
                            item     = state.history[index],
                            onShare  = { shareDownloadedMedia(context, state.history[index]) },
                            onDelete = { deletePendingItem = state.history[index] },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title   = { Text("Clear all history?") },
            text    = { Text("This will remove all history entries and delete the downloaded files. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.clearAll(); showClearDialog = false }) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }

    deletePendingItem?.let { pending ->
        AlertDialog(
            onDismissRequest = { deletePendingItem = null },
            title   = { Text("Delete download?") },
            text    = { Text("\"${pending.title.take(60)}\" will be removed from history and deleted from storage.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteItem(pending); deletePendingItem = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletePendingItem = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FilterRow(
    label: String,
    chips: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                letterSpacing = 0.8.sp,
            ),
        )
        Spacer(Modifier.width(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chips.size) { i ->
                val (value, display) = chips[i]
                FilterChip(
                    selected = selected == value,
                    onClick  = { onSelect(value) },
                    label    = { Text(display, fontSize = 12.sp) },
                )
            }
        }
    }
}

@Composable
private fun HistoryItem(context: android.content.Context, item: DownloadHistory, onShare: () -> Unit, onDelete: () -> Unit) {
    Card(
        elevation = CardDefaults.cardElevation(0.dp),
        border    = CardDefaults.outlinedCardBorder(),
        modifier = Modifier.clickable {
            openDownloadedMedia(context, item)
        }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val showThumbnail = item.thumbnailUrl.isNotBlank() &&
                !item.platform.equals("instagram", ignoreCase = true) &&
                !item.platform.equals("snapsave_instagram", ignoreCase = true)

            // Thumbnail
            Box(Modifier.size(56.dp)) {
                if (showThumbnail) {
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Surface(
                        Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                when (item.downloadType) {
                                    "video" -> Icons.Rounded.Videocam
                                    "audio" -> Icons.Rounded.MusicNote
                                    else    -> Icons.Rounded.Image
                                },
                                contentDescription = null,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text(historyPlatformDisplayName(item.platform), color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 10.sp)
                    }
                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(item.downloadType, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 10.sp)
                    }
                    if (item.quality.isNotBlank()) {
                        Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text(item.quality, color = MaterialTheme.colorScheme.onTertiaryContainer, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(formatDate(item.downloadDate), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }

            IconButton(onClick = onShare) {
                Icon(Icons.Rounded.Share, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun formatDate(epochMillis: Long): String {
    val now  = System.currentTimeMillis()
    val diff = now - epochMillis
    val days = diff / (1000 * 60 * 60 * 24)
    val date = Date(epochMillis)
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    return when {
        days == 0L -> "Today $time"
        days == 1L -> "Yesterday $time"
        days < 7L  -> "${days}d ago"
        else       -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date)
    }
}

private fun findMediaUri(context: android.content.Context, item: DownloadHistory): Pair<android.net.Uri, String>? {
    val mediaTypeFolder = when (item.downloadType) {
        "audio" -> "MUSIC"
        "video" -> "VIDEO"
        "image" -> "IMAGES"
        else    -> "VIDEO"
    }
    val mimeType = when (item.downloadType) {
        "audio" -> "audio/*"
        "video" -> "video/*"
        "image" -> "image/*"
        else    -> "*/*"
    }
    val fileName = item.fileName.takeIf { it.isNotBlank() } ?: sanitizeFileName(item.title)

    if (item.folderUri == "default" || item.folderUri.isEmpty()) {
        val storageDir = Environment.getExternalStorageDirectory()
        val exactFile = java.io.File(storageDir, "Torikomi/$mediaTypeFolder/$fileName")
        val file = if (exactFile.exists()) {
            exactFile
        } else {
            val folder = java.io.File(storageDir, "Torikomi/$mediaTypeFolder")
            val allFiles = folder.listFiles()?.filter { it.isFile } ?: emptyList()
            val titleHint = sanitizeFileName(item.title).lowercase().take(30)
            val byTitle = if (titleHint.isNotBlank())
                allFiles.filter { it.name.lowercase().contains(titleHint) }
            else emptyList()
            if (byTitle.isNotEmpty()) {
                byTitle.maxByOrNull { it.lastModified() }
            } else {
                val platformHint = "torikomi_${item.platform.lowercase()}"
                val byPlatform = allFiles.filter { it.name.lowercase().startsWith(platformHint) }
                (byPlatform.ifEmpty { allFiles })
                    .minByOrNull { kotlin.math.abs(it.lastModified() - item.downloadDate) }
            }
        }
        if (file == null || !file.exists()) return null
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        return uri to mimeType
    } else {
        return try {
            val treeUri = android.net.Uri.parse(item.folderUri)
            val resolver = context.contentResolver
            val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val projection = arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            val cursor = resolver.query(childrenUri, projection, null, null, null)
            val titleHint = sanitizeFileName(item.title).lowercase().take(30)
            var foundUri: android.net.Uri? = null
            cursor?.use {
                while (it.moveToNext()) {
                    val displayName = it.getString(1) ?: continue
                    val nameLower = displayName.lowercase()
                    val isMatch = nameLower == fileName.lowercase()
                        || nameLower == (mediaTypeFolder + "_" + fileName).lowercase()
                        || (titleHint.isNotBlank() && nameLower.contains(titleHint))
                    if (isMatch) {
                        foundUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                            treeUri, it.getString(0)
                        )
                        break
                    }
                }
            }
            foundUri?.let { it to mimeType }
        } catch (e: Exception) {
            null
        }
    }
}

private fun openDownloadedMedia(context: android.content.Context, item: DownloadHistory) {
    val (uri, mimeType) = findMediaUri(context, item) ?: run {
        android.widget.Toast.makeText(context, "File not found", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "No app available to open this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun shareDownloadedMedia(context: android.content.Context, item: DownloadHistory) {
    val (uri, mimeType) = findMediaUri(context, item) ?: run {
        android.widget.Toast.makeText(context, "File not found", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, item.title))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "No app available to share this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun sanitizeFileName(title: String): String {
    return title
        .replace(Regex("[<>:\"/\\|?*]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(200)
}

private fun historyPlatformDisplayName(id: String): String = when (id.lowercase()) {
    "tiktok"              -> "TikTok"
    "youtube", "ytdown"   -> "YouTube"
    "instagram"           -> "Instagram"
    "facebook"            -> "Facebook"
    "twitter", "x"        -> "Twitter"
    "threads"             -> "Threads"
    "spotify"             -> "Spotify"
    "pinterest"           -> "Pinterest"
    "soundcloud"          -> "SoundCloud"
    "douyin"              -> "Douyin"
    "bilibili"            -> "Bilibili"
    "musicaldown"         -> "MusicalDown"
    "snapsave"            -> "SnapSave"
    else -> id.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
