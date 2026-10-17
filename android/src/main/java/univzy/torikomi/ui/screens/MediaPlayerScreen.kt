package univzy.torikomi.ui.screens

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import univzy.torikomi.R
import univzy.torikomi.data.model.DownloadHistory
import univzy.torikomi.data.model.MediaMetadata
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun MediaPlayerScreen(initial: DownloadHistory, audioItems: List<DownloadHistory>, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.media_now_playing), Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.media_close)) }
                }
                when (initial.downloadType) {
                    "audio" -> AudioPlayer(initial, audioItems)
                    else -> PhotoVideoPlayer(initial)
                }
            }
        }
    }
}

@Composable
private fun PhotoVideoPlayer(item: DownloadHistory) {
    val context = LocalContext.current
    val media by produceState<Pair<android.net.Uri, String>?>(null, item.id) {
        value = withContext(Dispatchers.IO) { findMediaUri(context, item) }
    }
    var video by remember { mutableStateOf<VideoView?>(null) }
    var failed by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) video?.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) { onDispose { video?.stopPlayback() } }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleMedium)
        if (failed) TextButton(onClick = { openDownloadedMedia(context, item) }) { Text(stringResource(R.string.media_open_system)) }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                failed -> Text(stringResource(R.string.media_codec_error))
                media == null -> Text(stringResource(R.string.history_file_not_found))
                item.downloadType == "image" -> AsyncImage(media!!.first, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onError = { failed = true })
                else -> AndroidView(factory = { ctx ->
                    VideoView(ctx).apply {
                        video = this
                        setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                        setOnErrorListener { _, _, _ -> failed = true; true }
                        setOnPreparedListener { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start() }
                        setVideoURI(media!!.first)
                    }
                }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun AudioPlayer(initial: DownloadHistory, audioItems: List<DownloadHistory>) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("media-player", Context.MODE_PRIVATE) }
    val baseQueue = remember(initial.id) { (audioItems + initial).distinctBy { it.id } }
    var queue by remember(initial.id) { mutableStateOf(baseQueue) }
    var index by remember(initial.id) { mutableIntStateOf(baseQueue.indexOfFirst { it.id == initial.id }.coerceAtLeast(0)) }
    val item = queue[index]
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(RepeatMode.OFF) }
    var volume by remember { mutableIntStateOf(preferences.getInt("volume", 100).coerceIn(0, 100)) }
    var muted by remember { mutableStateOf(preferences.getBoolean("muted", false)) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf(false) }
    var artwork by remember(item.id) { mutableStateOf<ByteArray?>(null) }
    var tags by remember(item.id) { mutableStateOf(item.metadata) }
    val currentRepeat by rememberUpdatedState(repeat)
    val currentIndex by rememberUpdatedState(index)
    val currentQueue by rememberUpdatedState(queue)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(item.id) {
        ready = false; playing = false; error = false; position = 0; duration = 0
        val media = withContext(Dispatchers.IO) { findMediaUri(context, item) }
        if (media == null) { error = true; return@LaunchedEffect }
        val details = withContext(Dispatchers.IO) {
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(context, media.first)
                reader.embeddedPicture to MediaMetadata(artist = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), album = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            } catch (_: Exception) { null to null } finally { reader.release() }
        }
        artwork = details.first
        tags = item.metadata ?: details.second
        val created = MediaPlayer()
        player = created
        try {
            created.setDataSource(context, media.first)
            created.setOnPreparedListener {
                ready = true; duration = it.duration.coerceAtLeast(0)
                it.setVolume(playbackVolume(volume, muted), playbackVolume(volume, muted))
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { it.start(); playing = true }
            }
            created.setOnErrorListener { _, _, _ -> error = true; ready = false; playing = false; true }
            created.setOnCompletionListener { completed ->
                val next = nextPlaybackIndex(currentIndex, currentQueue.size, 1, currentRepeat, true)
                when {
                    next == null -> { playing = false; position = duration }
                    next == currentIndex -> { completed.seekTo(0); completed.start(); playing = true }
                    else -> index = next
                }
            }
            created.prepareAsync()
        } catch (_: Exception) { error = true }
    }
    DisposableEffect(item.id) {
        onDispose { player?.release(); player = null }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && ready) { player?.pause(); playing = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(volume, muted, ready) {
        if (ready) player?.setVolume(playbackVolume(volume, muted), playbackVolume(volume, muted))
        preferences.edit().putInt("volume", volume).putBoolean("muted", muted).apply()
    }
    LaunchedEffect(item.id, ready) {
        while (ready) { position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0); delay(500) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 240.dp), contentAlignment = Alignment.Center) {
                if (artwork != null || item.thumbnailUrl.isNotBlank()) AsyncImage(artwork ?: item.thumbnailUrl, null, Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Fit)
                else Icon(Icons.Rounded.Album, null, Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(16.dp))
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            tags?.artist?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            tags?.album?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (error) {
                Text(stringResource(R.string.media_codec_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { openDownloadedMedia(context, item) }) { Text(stringResource(R.string.media_open_system)) }
            }
            val seekLabel = stringResource(R.string.media_seek)
            Slider(position.toFloat().coerceAtMost(duration.toFloat()), { position = it.toInt(); player?.seekTo(position) },
                enabled = ready && duration > 0, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), modifier = Modifier.semantics { contentDescription = seekLabel })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(playbackTime(position)); Text(playbackTime(duration)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconToggleButton(shuffle, { checked ->
                    shuffle = checked
                    val current = queue[index]
                    queue = if (checked) listOf(current) + baseQueue.filterNot { it.id == current.id }.shuffled() else baseQueue
                    index = queue.indexOfFirst { it.id == current.id }
                }) { Icon(Icons.Rounded.Shuffle, stringResource(R.string.media_shuffle)) }
                IconButton(onClick = { nextPlaybackIndex(index, queue.size, -1, repeat)?.let { index = it } }, enabled = nextPlaybackIndex(index, queue.size, -1, repeat) != null) {
                    Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.media_previous))
                }
                FilledIconButton(onClick = {
                    if (playing) player?.pause() else player?.start()
                    playing = !playing
                }, enabled = ready, modifier = Modifier.size(64.dp)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (playing) R.string.media_pause else R.string.media_play))
                }
                IconButton(onClick = { nextPlaybackIndex(index, queue.size, 1, repeat)?.let { index = it } }, enabled = nextPlaybackIndex(index, queue.size, 1, repeat) != null) {
                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.media_next))
                }
                IconToggleButton(repeat != RepeatMode.OFF, { repeat = RepeatMode.entries[(repeat.ordinal + 1) % RepeatMode.entries.size] }) {
                    Icon(if (repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        stringResource(if (repeat == RepeatMode.ONE) R.string.media_repeat_one else R.string.media_repeat))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconToggleButton(muted, { muted = it }) { Icon(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, stringResource(if (muted) R.string.media_unmute else R.string.media_mute)) }
                val volumeLabel = "${stringResource(R.string.media_volume)}: $volume%"
                Slider(volume.toFloat(), { volume = it.toInt() }, valueRange = 0f..100f, modifier = Modifier.weight(1f).semantics { contentDescription = volumeLabel })
                Text("$volume%", Modifier.widthIn(min = 48.dp))
            }
            Text(stringResource(R.string.media_play_queue), style = MaterialTheme.typography.titleMedium)
        }
        items(queue, key = { it.id }) { queued ->
            val selected = queued.id == item.id
            Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().semantics { this.selected = selected }.clickable { index = queue.indexOf(queued) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (selected) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote, null, Modifier.size(24.dp))
                    Text(queued.title, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun playbackTime(millis: Int) = "%d:%02d".format(millis / 60000, (millis / 1000) % 60)
