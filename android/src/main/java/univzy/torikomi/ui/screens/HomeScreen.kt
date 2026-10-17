package univzy.torikomi.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import univzy.torikomi.R
import univzy.torikomi.Torikomi
import univzy.torikomi.data.model.QualityChoice
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.DownloadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabTitles = listOf(
        stringResource(R.string.tab_downloads),
        stringResource(R.string.tab_whatsapp),
        stringResource(R.string.tab_history),
        stringResource(R.string.tab_settings),
    )

    // Activity-scoped download VM — drives the top-bar badge counts.
    val context = LocalContext.current
    val factory = AppViewModelFactory(context.applicationContext as Torikomi)
    val downloadVm: DownloadViewModel = viewModel(
        viewModelStoreOwner = context as androidx.lifecycle.ViewModelStoreOwner,
        factory = factory,
    )
    val active by downloadVm.activeDownloads.collectAsState()
    val queued by downloadVm.queued.collectAsState()
    val downloadCount = active.size + queued.size
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val tabs = listOf(
        stringResource(R.string.tab_downloads) to Icons.Rounded.Download,
        stringResource(R.string.tab_whatsapp) to Icons.Rounded.Chat,
        stringResource(R.string.tab_history) to Icons.Rounded.History,
        stringResource(R.string.tab_settings) to Icons.Rounded.Settings,
    )

    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(tabTitles[selectedTab], fontWeight = FontWeight.Bold) },
                actions = {
                    if (selectedTab == 0) {
                        BadgedBox(
                            badge = { if (downloadCount > 0) Badge { Text("$downloadCount") } },
                        ) {
                            IconButton(onClick = { navController.navigate("downloads_hub?tab=0") }) {
                                Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.home_my_downloads))
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!wide) {
            NavigationBar {
                listOf(
                    Triple(stringResource(R.string.tab_downloads), Icons.Rounded.Download, Icons.Rounded.DownloadDone),
                    Triple(stringResource(R.string.tab_whatsapp), Icons.Rounded.Chat, Icons.Rounded.Chat),
                    Triple(stringResource(R.string.tab_history), Icons.Rounded.History, Icons.Rounded.History),
                    Triple(stringResource(R.string.tab_settings), Icons.Rounded.Settings, Icons.Rounded.Settings),
                ).forEachIndexed { index, (label, icon, selectedIcon) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(if (selectedTab == index) selectedIcon else icon, label) },
                        label = { Text(label) },
                    )
                }
            }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide) NavigationRail {
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationRailItem(selected = selectedTab == index, onClick = { selectedTab = index },
                        icon = { Icon(icon, label) }, label = { Text(label) })
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
        when (selectedTab) {
            0 -> DownloadsTab(
                modifier = Modifier.fillMaxSize(),
                onLogin = { url -> navController.navigate("login?url=${android.net.Uri.encode(url)}") },
            )
            1 -> WhatsAppStatusContent(modifier = Modifier.fillMaxSize())
            2 -> HistoryScreen(modifier = Modifier.fillMaxSize(), onUseLink = { url ->
                downloadVm.updateUrl(url); downloadVm.fetchInfo(); selectedTab = 0
            })
            3 -> SettingsScreen(
                modifier = Modifier.fillMaxSize(),
                onOpenAbout = { navController.navigate("about") },
                onOpenQueue = { navController.navigate("downloads_hub?tab=1") },
                onOpenStatistics = { navController.navigate("statistics") },
                onOpenAppearance = { navController.navigate("appearance") },
                onOpenConverter = { navController.navigate("converter") },
                onOpenAccounts = { navController.navigate("accounts") },
                onOpenSync = { navController.navigate("sync") },
            )
        }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsTab(
    modifier: Modifier,
    onLogin: (String) -> Unit,
) {
    val context = LocalContext.current
    val factory = AppViewModelFactory(context.applicationContext as Torikomi)
    val vm: DownloadViewModel = viewModel(
        viewModelStoreOwner = context as androidx.lifecycle.ViewModelStoreOwner,
        factory = factory,
    )
    val state by vm.uiState.collectAsState()
    val personal by (context.applicationContext as Torikomi).crossDeviceStore.state.collectAsState()
    val spacing = if (personal.preferences.layout == "compact") 12.dp else 20.dp

    var selectedChoice by remember(state.info, state.choices) { mutableStateOf<QualityChoice?>(null) }

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing),
    ) {
        // Link input + fetch, wrapped in a card.
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(2.dp),
        ) {
            Column(Modifier.padding(spacing)) {
                // Share hint
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Share, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.home_share_hint),
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.secondary),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                val urlCount = vm.validUrlCount()
                val isMulti = urlCount > 1

                OutlinedTextField(
                    value = state.url,
                    onValueChange = vm::updateUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(if (isMulti) R.string.home_link_label_multi else R.string.home_link_label)) },
                    placeholder = { Text(stringResource(R.string.home_link_placeholder)) },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 5,
                    trailingIcon = {
                        if (state.url.isNotBlank()) {
                            IconButton(onClick = { vm.updateUrl(""); vm.clearResult() }) {
                                Icon(Icons.Rounded.Clear, stringResource(R.string.home_clear))
                            }
                        }
                    },
                )

                if (urlCount > 1) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.home_links_detected, urlCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (isMulti) {
                    Text(stringResource(R.string.formats_multi_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                }
                run {
                    // Single link → usual flow: fetch info, then show the quality card.
                    Button(
                        onClick = { vm.fetchInfo() },
                        enabled = !state.isFetching,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        if (state.isFetching) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.home_fetching))
                        } else {
                            Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.home_fetch))
                        }
                    }
                }
            }
        }

        state.error?.let { err ->
            Spacer(Modifier.height(12.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        state.loginUrl?.let { loginUrl ->
            val site = univzy.torikomi.engine.CookieStore.siteKey(loginUrl).orEmpty()
            Spacer(Modifier.height(10.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.home_login_required, site),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onLogin(loginUrl) }, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_login_btn, site))
                    }
                }
            }
        }

        state.info?.let { info ->
            Spacer(Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(2.dp),
            ) {
                Column(Modifier.padding(spacing)) {
                    if (info.thumbnail.isNotBlank()) {
                        AsyncImage(
                            model = info.thumbnail,
                            contentDescription = info.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(14.dp)),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(info.title, fontWeight = FontWeight.Bold, maxLines = 2)
                    if (info.uploader.isNotBlank()) {
                        Text(info.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        InfoChip(
                            text = info.detectedSite,
                            icon = Icons.Rounded.Public,
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        InfoChip(
                            text = formatDuration(info.durationSec),
                            icon = Icons.Rounded.Schedule,
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Divider(Modifier.padding(vertical = 12.dp))
                    Text(stringResource(R.string.home_choose_quality), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    QualitySelector(
                        choices = state.choices,
                        selected = selectedChoice,
                        onSelect = { selectedChoice = it },
                        formats = info.formats,
                    )
                    if (state.choices.isEmpty()) Text(stringResource(R.string.formats_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (vm.validUrlCount() > 1) TextButton(onClick = { vm.nextLink() }) {
                        Text(stringResource(R.string.formats_next_link))
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { selectedChoice?.let { c -> requireStorageThen { vm.startDownload(c) } } },
                            enabled = selectedChoice != null,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.home_download_now))
                        }
                        FilledTonalButton(
                            onClick = { selectedChoice?.let { vm.downloadLater(it) } },
                            enabled = selectedChoice != null,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.home_later))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun QualitySelector(
    choices: List<QualityChoice>,
    selected: QualityChoice?,
    onSelect: (QualityChoice) -> Unit,
    formats: List<univzy.torikomi.data.model.MediaFormat>,
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(choices.size, key = { choices[it].formatSelector }) { index ->
            val choice = choices[index]
            val isSel = choice.formatSelector == selected?.formatSelector
            val kind = formats.firstOrNull { it.formatId == choice.formatSelector }?.kind
            val isAudio = choice.type == "audio"
            val typeColor = if (isAudio) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
            Surface(
                onClick = { onSelect(choice) },
                shape = RoundedCornerShape(14.dp),
                color = if (isSel) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = if (isSel) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = typeColor.copy(alpha = 0.16f),
                        shape = CircleShape,
                    ) {
                        Icon(
                            if (isAudio) Icons.Rounded.MusicNote else Icons.Rounded.Movie,
                            null,
                            tint = typeColor,
                            modifier = Modifier.size(36.dp).padding(8.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(choice.label, fontWeight = FontWeight.Medium)
                        Text(stringResource(when (kind) {
                            univzy.torikomi.data.model.MediaKind.VIDEO -> R.string.formats_silent
                            univzy.torikomi.data.model.MediaKind.AUDIO -> R.string.formats_audio
                            univzy.torikomi.data.model.MediaKind.IMAGE -> R.string.formats_image
                            univzy.torikomi.data.model.MediaKind.SOURCE -> R.string.revision_original
                            else -> R.string.formats_muxed
                        }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (choice.sizeBytes != null && choice.sizeBytes > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                formatFileSize(choice.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    RadioButton(selected = isSel, onClick = { onSelect(choice) })
                }
            }
        }
    }
}

@Composable
private fun InfoChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    container: Color,
    content: Color,
) {
    Surface(color = container, shape = RoundedCornerShape(8.dp)) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(14.dp), tint = content)
            Spacer(Modifier.width(5.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = content,
            )
        }
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "—"
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
