package univzy.torikomi.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import univzy.torikomi.R
import univzy.torikomi.Torikomi
import univzy.torikomi.data.model.QueuedDownload
import univzy.torikomi.engine.ActiveDownload
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.DownloadViewModel

/** Hardcoded "completed" green — the only non-theme color, used in both light and dark. */
private val CompletedGreen = Color(0xFF2E7D32)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsHubScreen(onBack: () -> Unit, onLogin: (String) -> Unit = {}, initialTab: Int = 0) {
    val context = LocalContext.current
    val factory = AppViewModelFactory(context.applicationContext as Torikomi)
    val vm: DownloadViewModel = viewModel(
        viewModelStoreOwner = context as androidx.lifecycle.ViewModelStoreOwner,
        factory = factory,
    )
    val active by vm.activeDownloads.collectAsState()
    val queued by vm.queued.collectAsState()

    // On Android 9 and below, writing to public storage needs WRITE_EXTERNAL_STORAGE
    // (a runtime permission). Request it just before a real download if missing.
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted && action != null) action()
        else if (!granted) android.widget.Toast.makeText(context, context.getString(R.string.home_storage_permission_required), android.widget.Toast.LENGTH_LONG).show()
    }
    val requireStorageThen: (() -> Unit) -> Unit = { action ->
        val needsLegacyWrite = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
        if (needsLegacyWrite) { pendingAction = action; storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else action()
    }

    var tab by remember { mutableStateOf(initialTab) }

    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hub_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { TabLabel(stringResource(R.string.hub_tab_now), active.size) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { TabLabel(stringResource(R.string.hub_tab_later), queued.size) },
                )
            }

            when (tab) {
                0 -> NowTab(active = active, onDismiss = { vm.dismissDownload(it) }, onCancel = { vm.cancelDownload(it) }, onLogin = onLogin)
                else -> LaterTab(
                    queued = queued,
                    onDownloadAll = { requireStorageThen { queued.forEach { vm.downloadQueuedNow(it) } } },
                    onDownloadOne = { q -> requireStorageThen { vm.downloadQueuedNow(q) } },
                    onRemove = { id -> vm.removeQueued(id) },
                )
            }
        }
    }
}

@Composable
private fun TabLabel(label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Badge { Text("$count") }
        }
    }
}

@Composable
private fun NowTab(active: List<ActiveDownload>, onDismiss: (String) -> Unit, onCancel: (String) -> Unit, onLogin: (String) -> Unit) {
    if (active.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.Download,
            title = stringResource(R.string.hub_empty_now_title),
            subtitle = stringResource(R.string.hub_empty_now_subtitle),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(active, key = { it.id }) { d ->
            ActiveDownloadCard(d = d, onDismiss = { onDismiss(d.id) }, onCancel = { onCancel(d.id) }, onLogin = onLogin)
        }
    }
}

@Composable
private fun LaterTab(
    queued: List<QueuedDownload>,
    onDownloadAll: () -> Unit,
    onDownloadOne: (QueuedDownload) -> Unit,
    onRemove: (String) -> Unit,
) {
    if (queued.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.Schedule,
            title = stringResource(R.string.hub_empty_later_title),
            subtitle = stringResource(R.string.hub_empty_later_subtitle),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.hub_saved_for_later),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = onDownloadAll, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.hub_download_all))
                }
            }
        }
        items(queued, key = { it.id }) { q ->
            QueueCard(item = q, onNow = { onDownloadOne(q) }, onRemove = { onRemove(q.id) })
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(20.dp).size(40.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ActiveDownloadCard(d: ActiveDownload, onDismiss: () -> Unit, onCancel: () -> Unit, onLogin: (String) -> Unit) {
    // Completion is detected via the terminal progress value (100 = success),
    // which is locale-independent — the status text itself is localized.
    val isCompleted = d.terminal && d.progress == 100
    val isFailed = d.terminal && !isCompleted

    // State-reflecting accent + leading icon + subtle container tint.
    val accent = when {
        isCompleted -> CompletedGreen
        isFailed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val iconContainer = when {
        isCompleted -> CompletedGreen.copy(alpha = 0.16f)
        isFailed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val cardContainer = when {
        isCompleted -> CompletedGreen.copy(alpha = 0.06f)
        isFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val leadingIcon = when {
        isCompleted -> Icons.Rounded.CheckCircle
        isFailed -> Icons.Rounded.ErrorOutline
        else -> Icons.Rounded.Download
    }

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardContainer),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = iconContainer, shape = CircleShape) {
                    Icon(
                        leadingIcon,
                        null,
                        tint = accent,
                        modifier = Modifier.size(44.dp).padding(10.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(d.title, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                if (d.terminal) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.action_dismiss), Modifier.size(18.dp))
                    }
                } else {
                    IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.action_cancel), Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (d.terminal) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (isCompleted) CompletedGreen.copy(alpha = 0.16f) else MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (isCompleted) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                null,
                                Modifier.size(16.dp),
                                tint = accent,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isCompleted) stringResource(R.string.hub_status_completed) else stringResource(R.string.hub_status_failed),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = accent,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        d.itemLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (isFailed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        d.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // Cookie/login wall → offer an in-app login for the source site.
                    val loginUrl = univzy.torikomi.engine.CookieStore.loginUrlFor(d.sourceUrl)
                        ?.takeIf { univzy.torikomi.engine.CookieStore.needsLogin(d.statusText) }
                    if (loginUrl != null) {
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = { onLogin(loginUrl) }, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.hub_login))
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${d.itemLabel} • ${d.statusText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (d.progress in 0..100) {
                        Text(
                            "${d.progress}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (d.progress in 0..100) {
                    LinearProgressIndicator(
                        progress = { d.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueCard(item: QueuedDownload, onNow: () -> Unit, onRemove: () -> Unit) {
    val isAudio = item.choiceType == "audio"
    val accent = if (isAudio) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.thumbnail.isNotBlank()) {
                AsyncImage(
                    model = item.thumbnail,
                    contentDescription = item.title,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Surface(
                    color = if (isAudio) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        if (isAudio) Icons.Rounded.MusicNote else Icons.Rounded.Movie,
                        null,
                        tint = if (isAudio) MaterialTheme.colorScheme.onTertiaryContainer
                               else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(56.dp).padding(16.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(
                        text = item.choiceLabel,
                        container = accent.copy(alpha = 0.16f),
                        content = accent,
                    )
                    if (item.sizeBytes != null && item.sizeBytes > 0) {
                        Spacer(Modifier.width(6.dp))
                        Chip(
                            text = formatFileSize(item.sizeBytes),
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(item.addedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = onNow) {
                Icon(Icons.Rounded.Download, stringResource(R.string.hub_download_now), Modifier.size(20.dp))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, stringResource(R.string.action_remove), Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Chip(text: String, container: Color, content: Color) {
    Surface(color = container, shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
