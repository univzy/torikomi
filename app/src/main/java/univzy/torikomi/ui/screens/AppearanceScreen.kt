package univzy.torikomi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import univzy.torikomi.R
import univzy.torikomi.Torikomi
import univzy.torikomi.ui.AppThemeMode
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.ThemeViewModel
import univzy.torikomi.util.LocaleHelper

private data class ThemeOption(
    val mode: AppThemeMode,
    val labelRes: Int,
    val icon: ImageVector,
)

private val THEME_OPTIONS = listOf(
    ThemeOption(AppThemeMode.SYSTEM, R.string.appearance_theme_system, Icons.Rounded.BrightnessAuto),
    ThemeOption(AppThemeMode.LIGHT,  R.string.appearance_theme_light,  Icons.Rounded.LightMode),
    ThemeOption(AppThemeMode.DARK,   R.string.appearance_theme_dark,   Icons.Rounded.DarkMode),
)

private data class LanguageOption(
    val tag: String,
    // Native display name shown in the picker. null means use [labelRes] instead.
    val label: String? = null,
    val labelRes: Int = 0,
)

private val LANGUAGE_OPTIONS = listOf(
    LanguageOption(tag = "", labelRes = R.string.appearance_lang_system),
    LanguageOption(tag = "en", labelRes = R.string.appearance_lang_english),
    LanguageOption(tag = "in", labelRes = R.string.appearance_lang_indonesian),
    LanguageOption(tag = "es", label = "Español"),
    LanguageOption(tag = "pt", label = "Português"),
    LanguageOption(tag = "fr", label = "Français"),
    LanguageOption(tag = "de", label = "Deutsch"),
    LanguageOption(tag = "it", label = "Italiano"),
    LanguageOption(tag = "ru", label = "Русский"),
    LanguageOption(tag = "tr", label = "Türkçe"),
    LanguageOption(tag = "vi", label = "Tiếng Việt"),
    LanguageOption(tag = "ar", label = "العربية"),
    LanguageOption(tag = "hi", label = "हिन्दी"),
    LanguageOption(tag = "zh-CN", label = "中文 (简体)"),
    LanguageOption(tag = "ja", label = "日本語"),
    LanguageOption(tag = "ko", label = "한국어"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = AppViewModelFactory(context.applicationContext as Torikomi)
    val vm: ThemeViewModel = viewModel(factory = factory)
    val themeMode by vm.themeMode.collectAsState()

    var currentLang by remember { mutableStateOf(LocaleHelper.getPersistedTag(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appearance_title), fontWeight = FontWeight.Bold) },
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
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Section label
            Text(
                stringResource(R.string.appearance_theme),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )

            // Theme selector card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    THEME_OPTIONS.forEachIndexed { index, option ->
                        val isSelected = themeMode == option.mode
                        val label = stringResource(option.labelRes)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    else
                                        androidx.compose.ui.graphics.Color.Transparent,
                                    shape = when (index) {
                                        0 -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                                        THEME_OPTIONS.lastIndex -> RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
                                        else -> RoundedCornerShape(0.dp)
                                    },
                                )
                                .clickable { vm.setThemeMode(option.mode) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = option.icon,
                                contentDescription = label,
                                modifier = Modifier.size(24.dp),
                                tint = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            RadioButton(
                                selected = isSelected,
                                onClick = { vm.setThemeMode(option.mode) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Language section label
            Text(
                stringResource(R.string.appearance_language),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )

            // Language selector — compact dropdown so the list doesn't run down the page.
            var langExpanded by remember { mutableStateOf(false) }
            val currentLangLabel = LANGUAGE_OPTIONS.firstOrNull { it.tag == currentLang }
                ?.let { it.label ?: stringResource(it.labelRes) }
                ?: stringResource(R.string.appearance_lang_system)
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    onClick = { langExpanded = true },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = currentLangLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DropdownMenu(
                    expanded = langExpanded,
                    onDismissRequest = { langExpanded = false },
                ) {
                    LANGUAGE_OPTIONS.forEach { option ->
                        val label = option.label ?: stringResource(option.labelRes)
                        val isSelected = currentLang == option.tag
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                langExpanded = false
                                if (currentLang != option.tag) {
                                    LocaleHelper.setLanguageTag(context, option.tag)
                                    currentLang = option.tag
                                    (context as? android.app.Activity)?.recreate()
                                }
                            },
                            trailingIcon = {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.appearance_language_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}
