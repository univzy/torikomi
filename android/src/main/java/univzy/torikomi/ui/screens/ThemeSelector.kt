package univzy.torikomi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import univzy.torikomi.R
import univzy.torikomi.data.model.AppCatalog
import univzy.torikomi.data.model.AppPreferences
import univzy.torikomi.data.model.ThemePreset
import univzy.torikomi.ui.theme.TorikomiDownloaderTheme

@Composable
fun ThemeSelector(preferences: AppPreferences, onSelect: (AppPreferences) -> Unit) {
    var preview by remember { mutableStateOf<AppPreferences?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.appearance_theme), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = { preview = preferences }) { Text(stringResource(R.string.theme_preview)) }
        }
        ThemeChoices(preferences, onSelect)
    }
    preview?.let { draft ->
        val systemDark = isSystemInDarkTheme()
        Dialog(onDismissRequest = { preview = null }) {
            Box(Modifier.clip(RoundedCornerShape(24.dp))) {
            TorikomiDownloaderTheme(darkTheme = draft.themeMode == "dark" || (draft.themeMode == "system" && systemDark), preferences = draft.copy(fontScale = 1f)) {
                Surface(shape = RoundedCornerShape(24.dp), color = Color.Transparent) {
                    Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.theme_preview_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.theme_preview_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThemeChoices(draft) { preview = it }
                        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Torikomi", style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.pref_font_preview))
                            Button(onClick = {}) { Text(stringResource(R.string.tab_downloads)) }
                        } }
                        TextButton(onClick = { onSelect(draft); preview = null }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.theme_apply)) }
                        TextButton(onClick = { preview = null }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun ThemeChoices(preferences: AppPreferences, onSelect: (AppPreferences) -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val followLabel = stringResource(R.string.theme_follow_device)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.theme_follow_device), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.theme_follow_device_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = preferences.themeMode == "system", onCheckedChange = { enabled -> onSelect(preferences.copy(themeMode = if (enabled) "system" else if (systemDark) "dark" else "light")) },
                modifier = Modifier.semantics { contentDescription = followLabel })
        }
        Text(stringResource(R.string.theme_default_themes), style = MaterialTheme.typography.titleSmall)
        ThemeSwatches(AppCatalog.themes.filter { it.group == "default" }, preferences, onSelect)
        Text(stringResource(R.string.theme_color_themes), style = MaterialTheme.typography.titleSmall)
        ThemeSwatches(AppCatalog.themes.filter { it.group == "color" }, preferences, onSelect)
    }
}

@Composable
private fun ThemeSwatches(themes: List<ThemePreset>, preferences: AppPreferences, onSelect: (AppPreferences) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 60.dp).toInt().coerceIn(1, 10)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            themes.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { theme ->
                        val selected = preferences.themeColor == theme.id
                        val label = when (theme.id) {
                            "neutral" -> stringResource(R.string.theme_light)
                            "ash" -> stringResource(R.string.theme_ash)
                            "slate" -> stringResource(R.string.theme_dark)
                            "onyx" -> stringResource(R.string.theme_onyx)
                            else -> theme.name
                        }
                        val selectedText = stringResource(R.string.theme_selected)
                        Box(Modifier.size(50.dp)
                            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(preferences.copy(themeColor = theme.id, themeMode = if (theme.group == "default") theme.mode else preferences.themeMode)) })
                            .semantics { contentDescription = label; if (selected) stateDescription = selectedText }
                            .padding(4.dp).background(Brush.linearGradient(theme.swatch.map { Color(it) }), RoundedCornerShape(7.dp)), contentAlignment = Alignment.TopEnd) {
                            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)).padding(3.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LanguageSelector(value: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val system = stringResource(R.string.appearance_lang_system)
    val normalized = if (value == "in") "id" else value
    OutlinedButton(onClick = { query = ""; open = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.appearance_language), style = MaterialTheme.typography.labelMedium)
            Text(AppCatalog.languages.firstOrNull { it.code == normalized }?.name ?: system)
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.theme_choose_language)) }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.theme_search_language)) }, singleLine = true)
            val languages = (listOf("system" to system) + AppCatalog.languages.map { it.code to it.name }).filter { it.first.contains(query, true) || it.second.contains(query, true) }
            LazyColumn(Modifier.heightIn(max = 360.dp).selectableGroup()) {
                if (languages.isEmpty()) item { Text(stringResource(R.string.theme_no_languages), Modifier.padding(16.dp)) }
                items(languages, key = { it.first }) { language ->
                    Row(Modifier.fillMaxWidth().selectable(normalized == language.first, role = Role.RadioButton, onClick = { onSelect(language.first); open = false }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = normalized == language.first, onClick = null)
                        Text(language.second, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_close)) } })
}
