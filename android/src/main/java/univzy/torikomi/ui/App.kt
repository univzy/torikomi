package univzy.torikomi.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import univzy.torikomi.R
import univzy.torikomi.Torikomi
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.DownloadViewModel
import univzy.torikomi.ui.screens.AboutScreen
import univzy.torikomi.ui.screens.AccountsScreen
import univzy.torikomi.ui.screens.ConverterScreen
import univzy.torikomi.ui.screens.AppearanceScreen
import univzy.torikomi.ui.screens.DownloadsHubScreen
import univzy.torikomi.ui.screens.HomeScreen
import univzy.torikomi.ui.screens.LoginWebViewScreen
import univzy.torikomi.ui.screens.OnboardingScreen
import univzy.torikomi.ui.screens.SetupScreen
import univzy.torikomi.ui.screens.SplashScreen
import univzy.torikomi.ui.screens.StatisticsScreen
import univzy.torikomi.ui.screens.SyncScreen
import univzy.torikomi.ui.theme.TorikomiDownloaderTheme
import univzy.torikomi.util.UrlDetectorService

@Composable
fun App(initialSharedUrl: String? = null) {
    val context      = LocalContext.current
    val factory      = AppViewModelFactory(context.applicationContext as Torikomi)
    val themeVm: ThemeViewModel = viewModel(factory = factory)
    val startupVm: StartupUpdateViewModel = viewModel(factory = factory)
    val splashVm: SplashViewModel = viewModel(factory = factory)
    val themeMode   by themeVm.themeMode.collectAsState()
    val preferences by themeVm.preferences.collectAsState()
    val startupState by startupVm.uiState.collectAsState()
    val splashState  by splashVm.uiState.collectAsState()
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.DARK   -> true
        AppThemeMode.LIGHT  -> false
        AppThemeMode.SYSTEM -> isSystemDark
    }

    LaunchedEffect(preferences.language) {
        val tag = preferences.language.takeUnless { it == "system" }.orEmpty()
        if (univzy.torikomi.util.LocaleHelper.getPersistedTag(context) != tag) {
            univzy.torikomi.util.LocaleHelper.setLanguageTag(context, tag)
            (context as? android.app.Activity)?.recreate()
        }
    }
    TorikomiDownloaderTheme(darkTheme = isDark, preferences = preferences) {
        val navController = rememberNavController()

        // Navigate based on splash state
        LaunchedEffect(splashState.destination) {
            when (splashState.destination) {
                SplashDestination.HOME -> navController.navigate("home") { popUpTo("splash") { inclusive = true } }
                SplashDestination.ONBOARDING -> navController.navigate("onboarding") { popUpTo("splash") { inclusive = true } }
                SplashDestination.SETUP -> navController.navigate("setup") { popUpTo("splash") { inclusive = true } }
                SplashDestination.LOADING -> Unit
            }
        }

        // Handle initial shared URL: prefill the Home URL field and auto-fetch.
        val context2 = LocalContext.current
        LaunchedEffect(initialSharedUrl, splashState.destination) {
            if (splashState.destination == SplashDestination.HOME && !initialSharedUrl.isNullOrBlank()) {
                val url = UrlDetectorService.extractUrl(initialSharedUrl)
                if (url != null) {
                    val dlFactory = AppViewModelFactory(context2.applicationContext as Torikomi)
                    val dlVm = ViewModelProvider(
                        context2 as androidx.lifecycle.ViewModelStoreOwner, dlFactory
                    )[DownloadViewModel::class.java]
                    dlVm.updateUrl(url)
                    dlVm.fetchInfo()
                }
            }
        }

        NavHost(navController = navController, startDestination = "splash") {
            composable("splash") { SplashScreen(loadingStep = splashState.loadingStep) }
            composable("onboarding") {
                OnboardingScreen(onComplete = { uri, name -> splashVm.completeOnboarding(uri, name) })
            }
            composable("setup") {
                SetupScreen(onSetupComplete = { splashVm.completeSetup() })
            }
            composable("home") {
                HomeScreen(navController = navController)
            }
            composable("about") {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable("appearance") {
                AppearanceScreen(onBack = { navController.popBackStack() })
            }
            composable("sync") {
                SyncScreen(onBack = { navController.popBackStack() }, onUseLink = { url ->
                    val dlVm = ViewModelProvider(context as androidx.lifecycle.ViewModelStoreOwner, factory)[DownloadViewModel::class.java]
                    dlVm.updateUrl(url)
                    navController.navigate("home") { popUpTo("home") { inclusive = true } }
                })
            }
            composable(
                "downloads_hub?tab={tab}",
                arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 }),
            ) { entry ->
                DownloadsHubScreen(
                    onBack = { navController.popBackStack() },
                    onLogin = { url -> navController.navigate("login?url=${android.net.Uri.encode(url)}") },
                    initialTab = entry.arguments?.getInt("tab") ?: 0,
                )
            }
            composable(
                "login?url={url}",
                arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                LoginWebViewScreen(
                    loginUrl = entry.arguments?.getString("url").orEmpty(),
                    onDone = { navController.popBackStack() },
                )
            }
            composable("converter") {
                ConverterScreen(onBack = { navController.popBackStack() })
            }
            composable("accounts") {
                AccountsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLogin = { url -> navController.navigate("login?url=${android.net.Uri.encode(url)}") },
                )
            }
            composable("statistics") {
                StatisticsScreen(onBack = { navController.popBackStack() })
            }
        }

        startupState.updateRelease?.let { release ->
            AlertDialog(
                onDismissRequest = { startupVm.dismissUpdatePopup() },
                title = { Text(stringResource(R.string.startup_update_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.startup_update_body,
                            release.tagName,
                            release.body.ifBlank { stringResource(R.string.startup_update_no_changelog) },
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
                        context.startActivity(intent)
                        startupVm.dismissUpdatePopup()
                    }) {
                        Text(stringResource(R.string.startup_update_btn))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { startupVm.dismissUpdatePopup() }) {
                        Text(stringResource(R.string.startup_update_later))
                    }
                },
            )
        }
    }
}
