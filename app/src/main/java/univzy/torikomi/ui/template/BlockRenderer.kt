package univzy.torikomi.ui.template

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import univzy.torikomi.data.model.DownloadItem
import univzy.torikomi.data.model.ScrapeResult
import univzy.torikomi.ui.ActiveDownload
import univzy.torikomi.ui.DownloadUiState
import univzy.torikomi.ui.DownloadViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Render satu [ScreenBlock] berdasarkan state ViewModel saat ini.
 *
 * Fungsi ini adalah dispatcher utama: semua block diteruskan ke composable
 * renderer masing-masing. Cukup tambahkan `is ScreenBlock.Xxx ->` baru
 * di sini bila ingin menambah tipe blok baru.
 */
@Composable
internal fun RenderBlock(
    block: ScreenBlock,
    state: DownloadUiState,
    vm: DownloadViewModel,
    platform: String,
    context: Context,
) {
    when (block) {
        is ScreenBlock.UrlInput      -> UrlInputBlock(block, state, vm, context)
        is ScreenBlock.SearchButton  -> SearchButtonBlock(block, state, vm, platform)
        is ScreenBlock.ActiveDownloads -> ActiveDownloadsBlock(state.activeDownloads)
        is ScreenBlock.Thumbnail     -> ThumbnailBlock(block, state.result)
        is ScreenBlock.Title         -> TitleBlock(state.result)
        is ScreenBlock.Author        -> AuthorBlock(state.result)
        is ScreenBlock.Description   -> DescriptionBlock(block, state.result)
        is ScreenBlock.VideoPreview  -> VideoPreviewBlock(state.result)
        is ScreenBlock.DownloadButtons -> DownloadButtonsBlock(state, vm, platform, state.url)
        is ScreenBlock.ImageGallery  -> ImageGalleryBlock(state.result, vm, platform, state.url)
        is ScreenBlock.CustomText    -> CustomTextBlock(block)
        is ScreenBlock.CustomButton  -> CustomButtonBlock(block)
        is ScreenBlock.Divider       -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        is ScreenBlock.Spacer        -> Spacer(modifier = Modifier.height(block.heightDp.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Input / Search blocks
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun UrlInputBlock(
    block: ScreenBlock.UrlInput,
    state: DownloadUiState,
    vm: DownloadViewModel,
    context: Context,
) {
    OutlinedTextField(
        value         = state.url,
        onValueChange = vm::updateUrl,
        modifier      = Modifier.fillMaxWidth(),
        placeholder   = { Text(block.placeholder) },
        leadingIcon   = { Icon(Icons.Rounded.Link, contentDescription = null) },
        trailingIcon  = if (block.showPaste) {
            {
                IconButton(onClick = {
                    val text = runCatching {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.primaryClip?.getItemAt(0)?.text?.toString()
                    }.getOrNull() ?: return@IconButton
                    vm.updateUrl(text)
                }) { Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste") }
            }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine      = true,
    )
    if (block.showSavePath) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Save to: ${state.saveDirectoryLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
internal fun SearchButtonBlock(
    block: ScreenBlock.SearchButton,
    state: DownloadUiState,
    vm: DownloadViewModel,
    platform: String,
) {
    Button(
        onClick  = { vm.fetchMedia(platform) },
        enabled  = !state.isLoading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier    = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color       = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text(block.loadingLabel)
        } else {
            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(block.label)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Active downloads block
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ActiveDownloadsBlock(items: List<ActiveDownload>) {
    if (items.isEmpty()) return
    Card(elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Download Progress",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(8.dp))
            items.forEach { item ->
                Text(
                    item.itemLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { item.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${item.statusText} ${item.progress}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

// ─────────────────────────────────────────────────────────────────────────────
// Media info blocks
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ThumbnailBlock(block: ScreenBlock.Thumbnail, result: ScrapeResult?) {
    val url = result?.thumbnail?.takeIf { it.isNotBlank() } ?: return
    AsyncImage(
        model              = url,
        contentDescription = "Thumbnail",
        contentScale       = ContentScale.Crop,
        modifier           = Modifier
            .fillMaxWidth()
            .height(block.heightDp.dp)
            .clip(RoundedCornerShape(block.cornerDp.dp)),
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
internal fun TitleBlock(result: ScrapeResult?) {
    val title = result?.title?.takeIf { it.isNotBlank() } ?: return
    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun AuthorBlock(result: ScrapeResult?) {
    val author = result?.authorName?.takeIf { it.isNotBlank() } ?: return
    Text(author, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun DescriptionBlock(block: ScreenBlock.Description, result: ScrapeResult?) {
    val text = block.text ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}

// ─────────────────────────────────────────────────────────────────────────────
// Preview block
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun VideoPreviewBlock(result: ScrapeResult?) {
    val url = result?.downloadItems
        ?.firstOrNull { it.type == "video" && it.url.isNotBlank() }
        ?.url
        ?: return

    Text(
        "Video Preview",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
    Spacer(Modifier.height(8.dp))
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        factory = { ctx ->
            VideoView(ctx).apply {
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoURI(Uri.parse(url))
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    start()
                }
            }
        },
        update = { view ->
            if (view.tag != url) {
                view.tag = url
                view.setVideoURI(Uri.parse(url))
                view.start()
            }
        },
    )
    Spacer(Modifier.height(12.dp))
}

// ─────────────────────────────────────────────────────────────────────────────
// Download blocks
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun DownloadButtonsBlock(
    state: DownloadUiState,
    vm: DownloadViewModel,
    platform: String,
    sourceUrl: String = "",
) {
    val result = state.result ?: return
    if (!result.isSuccess) return

    val playlistItems = result.downloadItems.filter { it.type == "playlist_item" }
    val regularItems  = result.downloadItems.filter { it.type != "playlist_item" }

    // ── Regular download buttons ───────────────────────────────────────────
    regularItems.forEach { item ->
        DownloadItemRow(
            item         = item,
            vm           = vm,
            platform     = platform,
            sourceUrl    = sourceUrl,
            sourceTitle  = result.title,
            thumbnailUrl = result.thumbnail,
            fileCount    = result.images.size.coerceAtLeast(1),
        )
        Spacer(Modifier.height(6.dp))
    }

    // ── Playlist items ─────────────────────────────────────────────────────
    if (playlistItems.isNotEmpty()) {
        Text(
            "${playlistItems.size} video • Tap to view download options",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        playlistItems.forEach { item ->
            PlaylistItemCard(
                item       = item,
                itemResult = state.playlistItemResults[item.key],
                isLoading  = item.key in state.loadingPlaylistItems,
                onTap      = { vm.fetchPlaylistItemMedia(platform, item.key, item.url) },
                vm         = vm,
                platform   = platform,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PlaylistItemCard(
    item: DownloadItem,
    itemResult: ScrapeResult?,
    isLoading: Boolean,
    onTap: () -> Unit,
    vm: DownloadViewModel,
    platform: String,
) {
    val thumbnail  = item.extra["thumbnail"]?.toString().orEmpty()
    val index      = item.extra["index"]?.toString()?.toIntOrNull() ?: 0
    val isLoaded   = itemResult != null && itemResult.isSuccess
    val hasError   = itemResult?.error != null
    val clickable  = !isLoading && (!isLoaded || hasError)

    Card(elevation = CardDefaults.cardElevation(0.dp)) {
        Column {
            // ── Header row ─────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (clickable) Modifier.clickable { onTap() } else Modifier)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Index badge
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$index",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }

                // Thumbnail
                if (thumbnail.isNotBlank()) {
                    AsyncImage(
                        model              = thumbnail,
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier
                            .size(width = 80.dp, height = 50.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(width = 80.dp, height = 50.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.VideoFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.width(10.dp))

                // Title + optional error
                Column(Modifier.weight(1f)) {
                    Text(
                        item.label,
                        style    = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                    )
                    if (hasError) {
                        Text(
                            itemResult!!.error!!,
                            style  = MaterialTheme.typography.labelSmall,
                            color  = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Status icon
                when {
                    isLoading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    isLoaded  -> Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    else      -> Icon(Icons.Rounded.PlayArrow, contentDescription = "Pilih format", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }

            // ── Inline format buttons ───────────────────────────────────────
            if (isLoaded) {
                HorizontalDivider(Modifier.padding(horizontal = 8.dp))
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    itemResult!!.downloadItems.forEach { formatItem ->
                        DownloadItemRow(
                            item         = formatItem,
                            vm           = vm,
                            platform     = platform,
                            sourceUrl    = item.url,
                            sourceTitle  = item.label,
                            thumbnailUrl = thumbnail,
                            fileCount    = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImageGalleryBlock(
    result: ScrapeResult?,
    vm: DownloadViewModel,
    platform: String,
    sourceUrl: String = "",
) {
    if (result == null || !result.isSuccess || !result.isImagePost) return
    Spacer(Modifier.height(8.dp))
    Text(
        "${result.images.size} images found",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
    )
    result.images.forEachIndexed { index, imgUrl ->
        Spacer(Modifier.height(6.dp))
        AsyncImage(
            model              = imgUrl,
            contentDescription = "Image preview ${index + 1}",
            contentScale       = ContentScale.Crop,
            modifier           = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick  = {
                vm.startDownload(
                    platform     = platform,
                    sourceUrl    = sourceUrl,
                    sourceTitle  = result.title,
                    thumbnailUrl = result.thumbnail,
                    item         = DownloadItem(
                        key      = "image_${index + 1}",
                        label    = "Image ${index + 1}",
                        type     = "image",
                        url      = imgUrl,
                        mimeType = "image/jpeg",
                        quality  = "Original",
                    ),
                    fileName  = blockDefaultFileName(
                        platform = result.platform.ifBlank { "image" },
                        suffix   = "image_${index + 1}",
                        ext      = "jpg",
                    ),
                    fileCount = 1,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Download Image ${index + 1}", modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Custom / layout blocks
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun CustomTextBlock(block: ScreenBlock.CustomText) {
    val style = when (block.style) {
        CustomTextStyle.Title    -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        CustomTextStyle.Subtitle -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        CustomTextStyle.Body     -> MaterialTheme.typography.bodyMedium
        CustomTextStyle.Caption  -> MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        CustomTextStyle.Label    -> MaterialTheme.typography.labelSmall.copy(
            fontWeight    = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color         = MaterialTheme.colorScheme.secondary,
        )
    }
    Text(
        text  = block.text,
        style = style,
    )
}

@Composable
internal fun CustomButtonBlock(block: ScreenBlock.CustomButton) {
    if (block.filled) {
        Button(onClick = block.onClick, modifier = Modifier.fillMaxWidth()) {
            block.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
            Text(block.label)
        }
    } else {
        OutlinedButton(onClick = block.onClick, modifier = Modifier.fillMaxWidth()) {
            block.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
            Text(block.label)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Internal helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DownloadItemRow(
    item: DownloadItem,
    vm: DownloadViewModel,
    platform: String,
    sourceUrl: String,
    sourceTitle: String,
    thumbnailUrl: String,
    fileCount: Int,
) {
    val ext = when (item.type) {
        "audio" -> "mp3"
        "image" -> "jpg"
        else    -> "mp4"
    }
    OutlinedButton(
        onClick  = {
            vm.startDownload(
                platform     = platform,
                sourceUrl    = sourceUrl,
                sourceTitle  = sourceTitle,
                thumbnailUrl = thumbnailUrl,
                item         = item,
                fileName     = blockDefaultFileName(platform = platform, suffix = item.key.ifBlank { item.label }, ext = ext),
                fileCount    = fileCount,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        val typeIcon = when (item.type) {
            "audio" -> Icons.Rounded.AudioFile
            "image" -> Icons.Rounded.Image
            else    -> Icons.Rounded.VideoFile
        }
        Icon(typeIcon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(item.label, fontSize = MaterialTheme.typography.bodySmall.fontSize)
            if (item.quality.isNotBlank()) {
                Text(item.quality, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

internal fun blockDefaultFileName(platform: String, suffix: String, ext: String): String {
    val safePlatform = platform.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "media" }
    val safeSuffix   = suffix.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "file" }
    val ts           = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "torikomi_${safePlatform}_${safeSuffix}_$ts.$ext"
}
