package univzy.torikomi.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import univzy.torikomi.R
import univzy.torikomi.Torikomi
import univzy.torikomi.data.model.DownloadHistory
import univzy.torikomi.data.model.libraryHistory
import univzy.torikomi.data.model.portableId
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(modifier: Modifier = Modifier, onUseLink: (String) -> Unit = {}) {
    val context = LocalContext.current
    val factory = AppViewModelFactory(context.applicationContext as Torikomi)
    val vm: HistoryViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsState()
    val deletionFailed by vm.deletionFailed.collectAsState()
    val store = (context.applicationContext as Torikomi).crossDeviceStore
    val library by store.state.collectAsState()
    var playingItem by remember { mutableStateOf<DownloadHistory?>(null) }
    var folderFilter by remember { mutableStateOf("all") }
    var folderItem by remember { mutableStateOf<DownloadHistory?>(null) }
    var editFolder by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf("") }
    var folderError by remember { mutableStateOf(false) }
    var removeRemote by remember { mutableStateOf<DownloadHistory?>(null) }
    fun folderAction(action: () -> Unit) { runCatching(action).onFailure { folderError = true } }
    val visibleHistory = libraryHistory(state.history, library, store.deviceId).filter { item ->
        (item.folderUri != "remote" || folderFilter != "all") &&
            (state.typeFilter == "all" || item.downloadType == state.typeFilter) &&
            (state.platformFilter == "all" || item.platform.lowercase() == state.platformFilter) &&
            (folderFilter == "all" || library.memberships.any { it.folderId == folderFilter &&
            it.entryId == item.portableId(store.deviceId) && it.deletedAt == 0L })
    }

    var showClearDialog by remember { mutableStateOf(false) }
    var deletePendingItem by remember { mutableStateOf<DownloadHistory?>(null) }

    Column(modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                FilterRow(
                    label  = stringResource(R.string.history_filter_type),
                    chips  = listOf(
                        "all" to stringResource(R.string.history_filter_all),
                        "video" to stringResource(R.string.history_filter_video),
                        "audio" to stringResource(R.string.history_filter_audio),
                        "image" to stringResource(R.string.history_filter_image),
                    ),
                    selected = state.typeFilter,
                    onSelect = vm::setTypeFilter,
                )
                Spacer(Modifier.height(8.dp))
                FilterRow(
                    label  = stringResource(R.string.history_filter_platform),
                    chips  = listOf("all" to stringResource(R.string.history_filter_all)) + state.availablePlatforms.map { id ->
                        id to historyPlatformDisplayName(id)
                    },
                    selected = state.platformFilter,
                    onSelect = vm::setPlatformFilter,
                )
                if (!state.isLoading) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.history_items_found, visibleHistory.size),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface.copy(.5f)
                        ),
                    )
                }
                Spacer(Modifier.height(8.dp))
                FilterRow(stringResource(R.string.media_back_folders),
                    listOf("all" to stringResource(R.string.history_filter_all), "favorites" to stringResource(R.string.media_favorites)) +
                        library.folders.filter { it.deletedAt == 0L }.map { it.id to it.name },
                    folderFilter, { folderFilter = it })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { editFolder = ""; folderName = "" }) { Icon(Icons.Rounded.CreateNewFolder, stringResource(R.string.media_new_folder)) }
                    if (folderFilter !in listOf("all", "favorites")) {
                        IconButton(onClick = { editFolder = folderFilter; folderName = library.folders.firstOrNull { it.id == folderFilter }?.name.orEmpty() }) { Icon(Icons.Rounded.Edit, stringResource(R.string.media_rename)) }
                        IconButton(onClick = { folderAction { store.deleteFolder(folderFilter); folderFilter = "all" } }) { Icon(Icons.Rounded.FolderDelete, stringResource(R.string.media_delete_folder)) }
                    }
                }
            }
        }

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            visibleHistory.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.History, null, Modifier.size(72.dp), tint = Color.LightGray)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.history_empty_title), color = Color.Gray, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.history_empty_subtitle), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
                        Text(stringResource(R.string.history_clear_all))
                    }
                }
                LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    items(
                        count = visibleHistory.size,
                        key   = { visibleHistory[it].id },
                    ) { index ->
                        HistoryItem(
                            context  = context,
                            item     = visibleHistory[index],
                            onPlay = { val item = visibleHistory[index]; if (item.folderUri == "remote") onUseLink(item.url) else playingItem = item },
                            favorite = library.memberships.any { it.folderId == "favorites" && it.entryId == visibleHistory[index].portableId(store.deviceId) && it.deletedAt == 0L },
                            onFavorite = { folderAction {
                                val item = visibleHistory[index]
                                val current = library.memberships.any { it.folderId == "favorites" && it.entryId == item.portableId(store.deviceId) && it.deletedAt == 0L }
                                store.setAudioMembership(item, "favorites", !current)
                            } },
                            onFolder = { folderItem = visibleHistory[index] },
                            onShare  = { shareDownloadedMedia(context, visibleHistory[index]) },
                            onDelete = { val item = visibleHistory[index]; if (item.folderUri == "remote") removeRemote = item else deletePendingItem = item },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }

    playingItem?.let { MediaPlayerScreen(it, visibleHistory.filter { entry -> entry.downloadType == "audio" && entry.folderUri != "remote" }, { playingItem = null }) }
    editFolder?.let { id ->
        AlertDialog(onDismissRequest = { editFolder = null }, title = { Text(stringResource(if (id.isEmpty()) R.string.media_new_folder else R.string.media_rename)) },
            text = { OutlinedTextField(folderName, { folderName = it }, label = { Text(stringResource(R.string.media_folder_name)) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { folderAction { if (id.isEmpty()) store.createFolder(folderName) else store.renameFolder(id, folderName); editFolder = null } }, enabled = folderName.isNotBlank()) { Text(stringResource(R.string.media_save)) } },
            dismissButton = { TextButton(onClick = { editFolder = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    folderItem?.let { item ->
        AlertDialog(onDismissRequest = { folderItem = null }, title = { Text(stringResource(R.string.media_add_folder)) }, text = {
            LazyColumn {
                items(library.folders.count { it.deletedAt == 0L }) { index ->
                    val folder = library.folders.filter { it.deletedAt == 0L }[index]
                    val checked = library.memberships.any { it.folderId == folder.id && it.entryId == item.portableId(store.deviceId) && it.deletedAt == 0L }
                    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = { include -> folderAction { store.setAudioMembership(item, folder.id, include) } }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, null)
                        Text(folder.name, Modifier.weight(1f))
                    }
                }
                item { TextButton(onClick = { folderItem = null; editFolder = ""; folderName = "" }) { Text(stringResource(R.string.media_new_folder)) } }
            }
        }, confirmButton = { TextButton(onClick = { folderItem = null }) { Text(stringResource(R.string.media_close)) } })
    }
    if (folderError) AlertDialog(onDismissRequest = { folderError = false }, text = { Text(stringResource(R.string.media_folder_error)) }, confirmButton = {
        TextButton(onClick = { folderError = false }) { Text(stringResource(R.string.media_close)) }
    })
    if (deletionFailed) AlertDialog(onDismissRequest = { vm.deletionFailed.value = false }, text = { Text(stringResource(R.string.media_delete_error)) }, confirmButton = {
        TextButton(onClick = { vm.deletionFailed.value = false }) { Text(stringResource(R.string.media_close)) }
    })
    removeRemote?.let { item -> AlertDialog(onDismissRequest = { removeRemote = null }, text = { Text(stringResource(R.string.sync_remove_description)) },
        confirmButton = { TextButton(onClick = { folderAction { store.removeLink(item.libraryId); removeRemote = null } }) { Text(stringResource(R.string.sync_remove)) } },
        dismissButton = { TextButton(onClick = { removeRemote = null }) { Text(stringResource(R.string.action_cancel)) } }) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title   = { Text(stringResource(R.string.history_clear_dialog_title)) },
            text    = { Text(stringResource(R.string.history_clear_dialog_text)) },
            confirmButton = {
                TextButton(onClick = { vm.clearAll(); showClearDialog = false }) {
                    Text(stringResource(R.string.history_clear_dialog_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    deletePendingItem?.let { pending ->
        AlertDialog(
            onDismissRequest = { deletePendingItem = null },
            title   = { Text(stringResource(R.string.history_delete_dialog_title)) },
            text    = { Text(stringResource(R.string.history_delete_dialog_text, pending.title.take(60))) },
            confirmButton = {
                TextButton(onClick = { vm.deleteItem(pending); deletePendingItem = null }) {
                    Text(stringResource(R.string.history_delete_dialog_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletePendingItem = null }) { Text(stringResource(R.string.action_cancel)) }
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
private fun HistoryItem(context: android.content.Context, item: DownloadHistory, onPlay: () -> Unit, favorite: Boolean, onFavorite: () -> Unit, onFolder: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Card(
        elevation = CardDefaults.cardElevation(0.dp),
        border    = CardDefaults.outlinedCardBorder(),
        modifier = Modifier.clickable(onClick = onPlay)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail. We always attempt to render the URL via Coil and
            // gracefully fall back to a type icon when the load fails (e.g.
            // expired CDN URLs from Instagram/SnapSave responses). This is
            // platform-agnostic — no platform allowlist required.
            Box(Modifier.size(56.dp)) {
                val fallbackIcon: ImageVector = when (item.downloadType) {
                    "video" -> Icons.Rounded.Videocam
                    "audio" -> Icons.Rounded.MusicNote
                    else -> Icons.Rounded.Image
                }

                if (item.thumbnailUrl.isBlank()) {
                    ThumbnailFallbackIcon(fallbackIcon)
                } else {
                    var loadFailed by remember(item.thumbnailUrl) { mutableStateOf(false) }
                    if (loadFailed) {
                        ThumbnailFallbackIcon(fallbackIcon)
                    } else {
                        AsyncImage(
                            model = item.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            onError = { loadFailed = true },
                        )
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
                Text(formatDate(context, item.downloadDate), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }

            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.media_actions)) }
                DropdownMenu(menu, { menu = false }) {
                    if (item.downloadType == "audio") {
                        DropdownMenuItem(text = { Text(stringResource(if (favorite) R.string.media_unfavorite else R.string.media_favorite)) }, leadingIcon = { Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null) }, onClick = { menu = false; onFavorite() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.media_add_folder)) }, leadingIcon = { Icon(Icons.Rounded.Folder, null) }, onClick = { menu = false; onFolder() })
                    }
                    if (item.folderUri != "remote") DropdownMenuItem(text = { Text(stringResource(R.string.media_share)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text(stringResource(if (item.folderUri == "remote") R.string.sync_remove else R.string.history_delete_dialog_confirm)) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private fun formatDate(context: android.content.Context, epochMillis: Long): String {
    val now  = System.currentTimeMillis()
    val diff = now - epochMillis
    val days = diff / (1000 * 60 * 60 * 24)
    val date = Date(epochMillis)
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    return when {
        days == 0L -> context.getString(R.string.history_today, time)
        days == 1L -> context.getString(R.string.history_yesterday, time)
        days < 7L  -> context.getString(R.string.history_days_ago, days)
        else       -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date)
    }
}

internal fun findMediaUri(context: android.content.Context, item: DownloadHistory): Pair<android.net.Uri, String>? {
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

    if (item.mediaUri.startsWith("content://")) return runCatching {
        val uri = Uri.parse(item.mediaUri)
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { uri to mimeType }
    }.getOrNull()

    if (item.folderUri == "default" || item.folderUri.isEmpty()) {
        // DownloadManager saves to /Download/Torikomi/<MediaTypeFolder>/<file>,
        // so the lookup root must be DIRECTORY_DOWNLOADS — not the external
        // storage root. Using the wrong root here was the cause of the
        // "File not found" toast even though the file existed on disk.
        val storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val exactFile = java.io.File(storageDir, "Torikomi/$mediaTypeFolder/$fileName")
            val file = exactFile.takeIf { it.isFile } ?: return null
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
            var foundUri: android.net.Uri? = null
            cursor?.use {
                while (it.moveToNext()) {
                    val displayName = it.getString(1) ?: continue
                    val nameLower = displayName.lowercase()
                    val isMatch = nameLower == fileName.lowercase()
                        || nameLower == (mediaTypeFolder + "_" + fileName).lowercase()
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

internal fun openDownloadedMedia(context: android.content.Context, item: DownloadHistory) {
    val (uri, mimeType) = findMediaUri(context, item) ?: run {
        android.widget.Toast.makeText(context, context.getString(R.string.history_file_not_found), android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, context.getString(R.string.history_no_app_open), android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun shareDownloadedMedia(context: android.content.Context, item: DownloadHistory) {
    val (uri, mimeType) = findMediaUri(context, item) ?: run {
        android.widget.Toast.makeText(context, context.getString(R.string.history_file_not_found), android.widget.Toast.LENGTH_SHORT).show()
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
        android.widget.Toast.makeText(context, context.getString(R.string.history_no_app_share), android.widget.Toast.LENGTH_SHORT).show()
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


@Composable
private fun ThumbnailFallbackIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        }
    }
}
