package univzy.torikomi.ui.screens

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import univzy.torikomi.R
import univzy.torikomi.Torikomi
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.ThemeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ThemeViewModel = viewModel(factory = AppViewModelFactory(context.applicationContext as Torikomi))
    val prefs by vm.preferences.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.updatePreferences { it.copy(notifications = granted) }
    }
    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = { TopAppBar(title = { Text(stringResource(R.string.appearance_title)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.personalize_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ThemeSelector(prefs) { selection -> vm.updatePreferences { it.copy(themeMode = selection.themeMode, themeColor = selection.themeColor) } }
            LanguageSelector(prefs.language) { value -> vm.updatePreferences { it.copy(language = value) } }
            PreferencePicker(stringResource(R.string.pref_font), prefs.fontFamily, listOf(
                "system" to stringResource(R.string.appearance_theme_system), "sans" to "Sans serif", "serif" to "Serif", "mono" to "Monospace",
            )) { value -> vm.updatePreferences { it.copy(fontFamily = value) } }
            Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                var scale by remember(prefs.fontScale) { mutableFloatStateOf(prefs.fontScale) }
                Text(stringResource(R.string.pref_font_size, (scale * 100).toInt()), style = MaterialTheme.typography.titleMedium)
                Slider(value = scale, onValueChange = { scale = it }, valueRange = 1f..1.5f, steps = 9,
                    onValueChangeFinished = { vm.updatePreferences { it.copy(fontScale = scale) } })
                Text(stringResource(R.string.pref_font_preview), style = MaterialTheme.typography.bodyLarge)
            } }
            PreferencePicker(stringResource(R.string.pref_layout), prefs.layout, listOf(
                "comfortable" to stringResource(R.string.pref_comfortable), "compact" to stringResource(R.string.pref_compact),
            )) { value -> vm.updatePreferences { it.copy(layout = value) } }
            Card { Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.pref_notifications), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = prefs.notifications, onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else vm.updatePreferences { it.copy(notifications = enabled) }
                    })
                }
                Text(stringResource(R.string.pref_notifications_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
}

@Composable
private fun PreferencePicker(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Column(Modifier.fillMaxWidth().padding(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(options.firstOrNull { it.first == value }?.second ?: value, style = MaterialTheme.typography.bodyLarge)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option.second) }, onClick = { expanded = false; onSelect(option.first) }) }
        }
    }
}
