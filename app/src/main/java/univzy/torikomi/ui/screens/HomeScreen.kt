package univzy.torikomi.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    var selectedTab by remember { mutableStateOf(0) }
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

    Scaffold(
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
        },
    ) { padding ->
        when (selectedTab) {
            0 -> DownloadsTab(modifier = Modifier.padding(padding))
            1 -> WhatsAppStatusContent(modifier = Modifier.padding(padding))
            2 -> HistoryScreen(modifier = Modifier.padding(padding))
            3 -> SettingsScreen(
                modifier = Modifier.padding(padding),
                onOpenAbout = { navController.navigate("about") },
                onOpenQueue = { navController.navigate("downloads_hub?tab=1") },
                onOpenStatistics = { navController.navigate("statistics") },
                onOpenAppearance = { navController.navigate("appearance") },
            )
        }
    }
}

@Composable
private fun DownloadsTab(
    modifier: Modifier,
) {
    val context = LocalContext.current
    val factory = AppViewModelFactory(context.applicationContext as Torikomi)
    val vm: DownloadViewModel = viewModel(
        viewModelStoreOwner = context as androidx.lifecycle.ViewModelStoreOwner,
        factory = factory,
    )
    val state by vm.uiState.collectAsState()

    // Reset selection whenever a new link is fetched (keyed on the choices list).
    var selectedChoice by remember(state.choices) { mutableStateOf(state.choices.firstOrNull()) }

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
            .padding(16.dp),
    ) {
        // Link input + fetch, wrapped in a card.
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(2.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
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

                OutlinedTextField(
                    value = state.url,
                    onValueChange = vm::updateUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.home_link_label)) },
                    placeholder = { Text(stringResource(R.string.home_link_placeholder)) },
                    singleLine = true,
                    trailingIcon = {
                        if (state.url.isNotBlank()) {
                            IconButton(onClick = { vm.updateUrl(""); vm.clearResult() }) {
                                Icon(Icons.Rounded.Clear, stringResource(R.string.home_clear))
                            }
                        }
                    },
                )

                Spacer(Modifier.height(12.dp))

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

        state.info?.let { info ->
            Spacer(Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(2.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
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
                    )
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            val isSel = choice.label == selected?.label
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
                    Text(choice.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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
