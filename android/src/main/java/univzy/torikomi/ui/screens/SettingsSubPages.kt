package univzy.torikomi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import univzy.torikomi.R
import univzy.torikomi.Torikomi
import univzy.torikomi.data.repository.ConvertSettings
import univzy.torikomi.engine.CookieStore
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .background(androidx.compose.ui.graphics.Color.Transparent)
                .padding(padding),
        ) { content() }
    }
}

@Composable
fun ConverterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(factory = AppViewModelFactory(context.applicationContext as Torikomi))
    val c = vm.uiState.collectAsState().value.convert

    SubPage(stringResource(R.string.settings_section_convert), onBack) {
        Text(
            stringResource(R.string.settings_convert_desc),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormatRow(stringResource(R.string.settings_convert_video), ConvertSettings.VIDEO, c.video) { vm.setConvert(c.copy(video = it)) }
        FormatRow(stringResource(R.string.settings_convert_audio), ConvertSettings.AUDIO, c.audio) { vm.setConvert(c.copy(audio = it)) }
        FormatRow(stringResource(R.string.settings_convert_image), ConvertSettings.IMAGE, c.image) { vm.setConvert(c.copy(image = it)) }
    }
}

@Composable
private fun FormatRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { o ->
                FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(".$o") })
            }
        }
    }
}

@Composable
fun AccountsScreen(onBack: () -> Unit, onOpenLogin: (String) -> Unit) {
    val context = LocalContext.current
    var showOtherSiteDialog by remember { mutableStateOf(false) }

    SubPage(stringResource(R.string.settings_section_accounts), onBack) {
        Text(
            stringResource(R.string.settings_accounts_desc),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Re-read on each (re)entry: returning from the login screen recomposes this page.
        val loggedIn = remember { mutableStateMapOf<String, Boolean>() }
        val rows = CookieStore.LOGIN_URLS.map { (name, url) -> Triple(name, url, CookieStore.siteKey(url)!!) } +
            CookieStore.otherSites(context).map { Triple(it, "https://www.$it/", it) }
        rows.forEach { (_, _, key) -> loggedIn[key] = CookieStore.isLoggedIn(context, key) }
        rows.forEach { (name, url, key) ->
            val on = loggedIn[key] == true
            SettingsItem(
                icon = Icons.Rounded.Lock,
                title = name,
                subtitle = stringResource(if (on) R.string.settings_account_on else R.string.settings_account_off),
                trailingContent = if (on) {
                    { TextButton(onClick = { CookieStore.logout(context, key); loggedIn[key] = false }) {
                        Text(stringResource(R.string.settings_account_logout))
                    } }
                } else null,
                onClick = { onOpenLogin(url) },
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        SettingsItem(
            icon = Icons.Rounded.Add,
            title = stringResource(R.string.settings_account_other),
            subtitle = stringResource(R.string.settings_account_other_sub),
            onClick = { showOtherSiteDialog = true },
        )
    }

    if (showOtherSiteDialog) {
        var site by remember { mutableStateOf("") }
        val key = CookieStore.siteKey(site.trim())
        AlertDialog(
            onDismissRequest = { showOtherSiteDialog = false },
            title = { Text(stringResource(R.string.settings_account_other)) },
            text = {
                OutlinedTextField(
                    value = site, onValueChange = { site = it }, singleLine = true,
                    placeholder = { Text("example.com") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = key != null,
                    onClick = { showOtherSiteDialog = false; onOpenLogin("https://www.$key/") },
                ) { Text(stringResource(R.string.login_done)) }
            },
            dismissButton = {
                TextButton(onClick = { showOtherSiteDialog = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}
