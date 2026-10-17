package univzy.torikomi.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import univzy.torikomi.R
import univzy.torikomi.Torikomi
import univzy.torikomi.data.repository.HistoryRepository
import univzy.torikomi.data.model.SyncDocument

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit, onUseLink: (String) -> Unit) {
    val context = LocalContext.current
    val store = (context.applicationContext as Torikomi).crossDeviceStore
    val document by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<SyncDocument?>(null) }
    var removeId by remember { mutableStateOf<String?>(null) }
    val exported = stringResource(R.string.backup_exported)
    val imported = stringResource(R.string.backup_imported)
    val importError = stringResource(R.string.backup_error)
    val saved = stringResource(R.string.sync_saved)
    fun run(message: String, onSuccess: () -> Unit = {}, task: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; status = ""; failure = false
            try { withContext(Dispatchers.IO) { task() }; onSuccess(); status = message }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { status = "$importError ${e.message.orEmpty()}"; failure = true }
            finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run(exported) {
            val bytes = store.exportBackup(HistoryRepository(context).getHistory())
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { importError }.use { it.write(bytes) }
        }
    }
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run("", task = {
            val backup = checkNotNull(context.contentResolver.openInputStream(uri)) { importError }.use { SyncDocument.readBackup(it) }
            withContext(Dispatchers.Main) { preview = backup }
        })
    }
    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = { TopAppBar(title = { Text(stringResource(R.string.sync_title)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.sync_description), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = !busy, onClick = { export.launch("torikomi-backup.json") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.backup_export))
                        }
                        OutlinedButton(enabled = !busy, onClick = { pickImport.launch(arrayOf("application/json")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.backup_import))
                        }
                    }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (status.isNotBlank()) item { Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            item { HorizontalDivider(); Text(stringResource(R.string.sync_library), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 20.dp)) }
            item { OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.sync_link)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedButton(enabled = !busy && url.isNotBlank(), onClick = { val value = url.trim(); run(saved) { store.addLink(value, value) } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sync_save_link)) } }
            val entries = document.entries.filter { it.deletedAt == 0L }.sortedByDescending { it.createdAt }
            if (entries.isEmpty()) item { Text(stringResource(R.string.sync_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(entries, key = { it.id }) { entry -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(if (document.preferences.layout == "compact") 12.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                    Text(entry.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { onUseLink(entry.url) }) { Text(stringResource(R.string.tab_downloads)) }
                    TextButton(onClick = { removeId = entry.id }, enabled = !busy) { Text(stringResource(R.string.sync_remove)) }
                }
            } }
        }
    }
    preview?.let { backup ->
        AlertDialog(onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.backup_preview_title)) },
            text = { Text(stringResource(R.string.backup_preview_body,
                backup.entries.count { it.deletedAt == 0L }, backup.entries.count { it.deletedAt != 0L })) },
            confirmButton = { TextButton(onClick = {
                preview = null
                run(imported) { store.importBackup(backup) }
            }) { Text(stringResource(R.string.backup_apply)) } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
    removeId?.let { id -> AlertDialog(onDismissRequest = { removeId = null }, title = { Text(stringResource(R.string.sync_remove)) },
        text = { Text(stringResource(R.string.sync_remove_description)) }, confirmButton = {
            TextButton(onClick = { removeId = null; run(saved) { store.removeLink(id) } }) { Text(stringResource(R.string.sync_remove)) }
        }, dismissButton = { TextButton(onClick = { removeId = null }) { Text(stringResource(android.R.string.cancel)) } }) }
}
