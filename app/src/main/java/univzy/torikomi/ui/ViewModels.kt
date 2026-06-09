package univzy.torikomi.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import univzy.torikomi.R
import univzy.torikomi.Torikomi
import univzy.torikomi.data.model.DownloadHistory
import univzy.torikomi.data.model.MediaInfo
import univzy.torikomi.data.model.QualityChoice
import univzy.torikomi.data.model.DownloadSpec
import univzy.torikomi.data.model.QueuedDownload
import univzy.torikomi.data.repository.DownloadQueueRepository
import univzy.torikomi.data.repository.HistoryRepository
import univzy.torikomi.data.repository.SettingsRepository
import univzy.torikomi.data.repository.AppReleaseInfo
import univzy.torikomi.engine.YtDlpEngine
import univzy.torikomi.engine.QualityPresets
import univzy.torikomi.engine.ActiveDownload
import univzy.torikomi.engine.DownloadController
import univzy.torikomi.service.DownloadService
import univzy.torikomi.util.UrlDetectorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private fun compareVersionName(latest: String, current: String): Int {
    fun normalize(input: String): List<Int> {
        val cleaned = input.trim().removePrefix("v").removePrefix("V")
        return cleaned.split(".").map { it.toIntOrNull() ?: 0 }
    }
    val a = normalize(latest)
    val b = normalize(current)
    val max = maxOf(a.size, b.size)
    for (i in 0 until max) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}


private val Context.themeDataStore by preferencesDataStore("theme_prefs")
private val THEME_KEY = stringPreferencesKey("theme_mode")

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

class ThemeViewModel(private val context: Context) : ViewModel() {

    private val _themeMode = MutableStateFlow(AppThemeMode.SYSTEM)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = context.themeDataStore.data.first()[THEME_KEY]
            _themeMode.value = when (saved) {
                "dark"  -> AppThemeMode.DARK
                "light" -> AppThemeMode.LIGHT
                else    -> AppThemeMode.SYSTEM
            }
        }
    }

    fun toggleTheme(isDark: Boolean) {
        val next = if (isDark) AppThemeMode.LIGHT else AppThemeMode.DARK
        _themeMode.value = next
        viewModelScope.launch {
            context.themeDataStore.edit { it[THEME_KEY] = next.name.lowercase() }
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        viewModelScope.launch {
            context.themeDataStore.edit { it[THEME_KEY] = mode.name.lowercase() }
        }
    }
}


data class DownloadUiState(
    val url: String = "",
    val isFetching: Boolean = false,
    val info: MediaInfo? = null,
    val choices: List<QualityChoice> = emptyList(),
    val error: String? = null,
    val saveDirectoryLabel: String = "Torikomi",
)

class DownloadViewModel(
    private val engine: YtDlpEngine,
    private val settingsRepo: SettingsRepository,
    private val queueRepo: DownloadQueueRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    val activeDownloads: StateFlow<List<ActiveDownload>> = DownloadController.active

    val queued: StateFlow<List<QueuedDownload>> =
        queueRepo.queueFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { refreshSavePathLabel() }

    fun updateUrl(url: String) = _uiState.update { it.copy(url = url) }

    fun clearResult() = _uiState.update { it.copy(info = null, choices = emptyList(), error = null) }

    fun dismissDownload(id: String) = DownloadController.remove(id)

    fun refreshSavePathLabel() {
        viewModelScope.launch {
            val path = settingsRepo.getDownloadPathSettings()
            val label = if (path.folderUri.isNotBlank()) "Custom: ${path.folderName}" else "Torikomi"
            _uiState.update { it.copy(saveDirectoryLabel = label) }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun fetchInfo() {
        val url = _uiState.value.url.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(error = appContext.getString(R.string.error_paste_link_first)) }
            return
        }
        if (!UrlDetectorService.isValidMediaUrl(url)) {
            _uiState.update { it.copy(error = appContext.getString(R.string.error_invalid_link)) }
            return
        }
        if (!isNetworkAvailable()) {
            _uiState.update { it.copy(error = appContext.getString(R.string.error_no_internet)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isFetching = true, error = null, info = null, choices = emptyList()) }
            runCatching { engine.fetchInfo(url) }
                .onSuccess { info ->
                    _uiState.update {
                        it.copy(isFetching = false, info = info, choices = QualityPresets.from(info.formats))
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isFetching = false, error = humanizeError(e.message)) }
                }
        }
    }

    fun startDownload(choice: QualityChoice) {
        val info = _uiState.value.info ?: return
        DownloadService.enqueue(
            appContext,
            DownloadSpec(
                sourceUrl = info.sourceUrl,
                title = info.title,
                thumbnail = info.thumbnail,
                detectedSite = info.detectedSite,
                choice = choice,
            ),
        )
        android.widget.Toast.makeText(appContext, appContext.getString(R.string.toast_download_started), android.widget.Toast.LENGTH_SHORT).show()
    }

    fun downloadLater(choice: QualityChoice) {
        val info = _uiState.value.info ?: return
        viewModelScope.launch {
            queueRepo.add(
                QueuedDownload(
                    id = java.util.UUID.randomUUID().toString(),
                    sourceUrl = info.sourceUrl,
                    title = info.title,
                    thumbnail = info.thumbnail,
                    detectedSite = info.detectedSite,
                    choiceLabel = choice.label,
                    choiceType = choice.type,
                    choiceSelector = choice.formatSelector,
                    choiceAudioExtract = choice.audioExtract,
                    addedAt = System.currentTimeMillis(),
                    sizeBytes = choice.sizeBytes,
                )
            )
            android.widget.Toast.makeText(appContext, appContext.getString(R.string.toast_saved_for_later), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun downloadQueuedNow(item: QueuedDownload) {
        DownloadService.enqueue(appContext, item.toSpec())
        viewModelScope.launch { queueRepo.remove(item.id) }
        android.widget.Toast.makeText(appContext, appContext.getString(R.string.toast_download_started), android.widget.Toast.LENGTH_SHORT).show()
    }

    fun removeQueued(id: String) {
        viewModelScope.launch { queueRepo.remove(id) }
    }

    private fun humanizeError(raw: String?): String {
        val lower = raw?.lowercase().orEmpty()
        return when {
            lower.isBlank() -> appContext.getString(R.string.error_fetch_generic)
            "unsupported url" in lower || "no video" in lower ->
                appContext.getString(R.string.error_unsupported_link)
            "unable to" in lower && "host" in lower ->
                appContext.getString(R.string.error_unreachable)
            "timed out" in lower || "timeout" in lower ->
                appContext.getString(R.string.error_timeout)
            "http error 403" in lower || "private" in lower ->
                appContext.getString(R.string.error_access_blocked)
            "http error 404" in lower -> appContext.getString(R.string.error_not_found)
            else -> raw!!.take(200)
        }
    }
}


data class HistoryUiState(
    val history: List<DownloadHistory>   = emptyList(),
    val isLoading: Boolean               = false,
    val typeFilter: String               = "all",
    val platformFilter: String           = "all",
    val availablePlatforms: List<String> = emptyList(),
)

class HistoryViewModel(private val repo: HistoryRepository) : ViewModel() {

    private val _typeFilter     = MutableStateFlow("all")
    private val _platformFilter = MutableStateFlow("all")

    val uiState: StateFlow<HistoryUiState> = combine(
        repo.historyFlow,
        _typeFilter,
        _platformFilter,
    ) { allHistory, typeFilter, platformFilter ->
        val availablePlatforms = allHistory
            .map { it.platform.lowercase() }
            .distinct()
            .sorted()
        var history = if (typeFilter != "all") allHistory.filter { it.downloadType == typeFilter } else allHistory
        if (platformFilter != "all") history = history.filter { it.platform.lowercase() == platformFilter }
        HistoryUiState(
            history            = history,
            isLoading          = false,
            typeFilter         = typeFilter,
            platformFilter     = platformFilter,
            availablePlatforms = availablePlatforms,
        )
    }.stateIn(
        scope         = viewModelScope,
        started       = SharingStarted.WhileSubscribed(5_000),
        initialValue  = HistoryUiState(isLoading = true),
    )

    fun setTypeFilter(filter: String)     { _typeFilter.value     = filter }
    fun setPlatformFilter(filter: String) { _platformFilter.value = filter }

    fun deleteItem(item: DownloadHistory) {
        viewModelScope.launch { repo.deleteHistoryAndFile(item) }
    }

    fun clearAll() {
        viewModelScope.launch { repo.clearAllHistoryAndFiles() }
    }
}


data class StatsUiState(
    val isLoading: Boolean = true,
    val total: Int = 0,
    val totalBytes: Long = 0L,
    val videoCount: Int = 0,
    val audioCount: Int = 0,
    val imageCount: Int = 0,
    val last7Days: Int = 0,
    val topPlatforms: List<Pair<String, Int>> = emptyList(),
)

class StatisticsViewModel(repo: HistoryRepository) : ViewModel() {
    val uiState: StateFlow<StatsUiState> = repo.historyFlow
        .map { history ->
            val now = System.currentTimeMillis()
            val weekAgo = now - 7L * 24 * 60 * 60 * 1000
            StatsUiState(
                isLoading = false,
                total = history.size,
                totalBytes = history.sumOf { it.fileSize },
                videoCount = history.count { it.downloadType == "video" },
                audioCount = history.count { it.downloadType == "audio" },
                imageCount = history.count { it.downloadType == "image" },
                last7Days = history.count { it.downloadDate >= weekAgo },
                topPlatforms = history.groupingBy { it.platform.lowercase() }.eachCount()
                    .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState(isLoading = true))
}


data class StartupUpdateUiState(
    val currentVersion: String = "-",
    val updateRelease: AppReleaseInfo? = null,
)

class StartupUpdateViewModel(
    private val repo: SettingsRepository,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StartupUpdateUiState())
    val uiState: StateFlow<StartupUpdateUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(currentVersion = appVersionName()) }
        checkForStartupUpdate()
    }

    fun dismissUpdatePopup() {
        _uiState.update { it.copy(updateRelease = null) }
    }

    private fun checkForStartupUpdate() {
        viewModelScope.launch {
            val currentVersion = _uiState.value.currentVersion
            runCatching { repo.fetchLatestRelease() }
                .onSuccess { release ->
                    val hasUpdate = compareVersionName(release.tagName, currentVersion) > 0
                    if (!hasUpdate) return@onSuccess

                    _uiState.update { it.copy(updateRelease = release) }

                    if (repo.shouldNotifyFor(release.tagName)) {
                        notifyUpdate(release)
                        repo.markUpdateNotified(release.tagName)
                    }
                }
        }
    }

    private fun notifyUpdate(release: AppReleaseInfo) {
        if (!hasNotificationPermission()) return
        val openReleaseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            991,
            openReleaseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (release.tagName.isNotBlank()) {
            "Update available: ${release.tagName}"
        } else {
            "Torikomi update available"
        }

        val body = release.name.ifBlank { "Tap to view changelog and update details" }

        val notification = NotificationCompat.Builder(context, Torikomi.UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(991, notification)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun appVersionName(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "-"
    }.getOrDefault("-")
}


data class SettingsUiState(
    val currentVersion: String = "-",
    val downloadFolderUri: String = "",
    val downloadFolderName: String = "Select Folder",
    val isCheckingUpdate: Boolean = false,
    val latestRelease: AppReleaseInfo? = null,
    val hasUpdate: Boolean = false,
    val updateError: String? = null,
    val ytDlpVersion: String = "",
    val isUpdatingYtDlp: Boolean = false,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val engine: YtDlpEngine,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(currentVersion = appVersionName()) }
        loadDownloadSettings()
        viewModelScope.launch {
            _uiState.update { it.copy(ytDlpVersion = repo.getYtDlpVersion()) }
        }
    }

    fun updateYtDlp() {
        if (_uiState.value.isUpdatingYtDlp) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingYtDlp = true) }
            runCatching { engine.updateYtDlp() }
                .onSuccess { v ->
                    repo.setYtDlpReady(true, v)
                    _uiState.update { it.copy(isUpdatingYtDlp = false, ytDlpVersion = v) }
                }
                .onFailure { _uiState.update { it.copy(isUpdatingYtDlp = false) } }
        }
    }

    fun loadDownloadSettings() {
        viewModelScope.launch {
            val settings = repo.getDownloadPathSettings()
            _uiState.update {
                it.copy(
                    downloadFolderUri = settings.folderUri,
                    downloadFolderName = settings.folderName,
                )
            }
        }
    }

    fun setDownloadFolder(folderUri: String, folderName: String) {
        viewModelScope.launch {
            repo.setDownloadFolder(folderUri, folderName)
            _uiState.update {
                it.copy(
                    downloadFolderUri = folderUri,
                    downloadFolderName = folderName,
                )
            }
        }
    }

    fun checkLatestRelease() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingUpdate = true, updateError = null) }
            runCatching { repo.fetchLatestRelease() }
                .onSuccess { release ->
                    val hasUpdate = compareVersionName(release.tagName, _uiState.value.currentVersion) > 0
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            latestRelease = release,
                            hasUpdate = hasUpdate,
                            updateError = null,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateError = e.message ?: "Failed to check updates",
                        )
                    }
                }
        }
    }

    private fun appVersionName(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "-"
    }.getOrDefault("-")
}


data class SetupUiState(
    val isDownloading: Boolean = false,
    val done: Boolean = false,
    val version: String = "",
    val error: String? = null,
)

class SetupViewModel(
    private val engine: YtDlpEngine,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun downloadYtDlp() {
        if (_uiState.value.isDownloading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, error = null) }
            runCatching { engine.updateYtDlp() }
                .onSuccess { version ->
                    settingsRepo.setYtDlpReady(true, version)
                    _uiState.update { it.copy(isDownloading = false, done = true, version = version) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isDownloading = false, error = e.message ?: "Failed to download yt-dlp")
                    }
                }
        }
    }
}


enum class SplashDestination { LOADING, ONBOARDING, SETUP, HOME }

data class SplashUiState(
    val destination: SplashDestination = SplashDestination.LOADING,
    val loadingStep: String = "Starting up...",
)

class SplashViewModel(
    private val settingsRepo: SettingsRepository,
    private val engine: YtDlpEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingStep = "Preparing engine...") }
            runCatching { engine.init() }
            val onboardingDone = runCatching { settingsRepo.isOnboardingDone() }.getOrDefault(false)
            val ytReady = runCatching { settingsRepo.isYtDlpReady() }.getOrDefault(false)
            _uiState.update {
                it.copy(
                    destination = when {
                        !onboardingDone -> SplashDestination.ONBOARDING
                        !ytReady -> SplashDestination.SETUP
                        else -> SplashDestination.HOME
                    },
                    loadingStep = "Ready!",
                )
            }
        }
    }

    /** Called by onboarding's final step; routes to the setup gate. */
    fun completeOnboarding(folderUri: String, folderName: String) {
        viewModelScope.launch {
            if (folderUri.isNotBlank()) settingsRepo.setDownloadFolder(folderUri, folderName)
            settingsRepo.markOnboardingDone()
            val ytReady = runCatching { settingsRepo.isYtDlpReady() }.getOrDefault(false)
            _uiState.update {
                it.copy(destination = if (ytReady) SplashDestination.HOME else SplashDestination.SETUP)
            }
        }
    }

    /** Called by the setup gate on success. */
    fun completeSetup() {
        _uiState.update { it.copy(destination = SplashDestination.HOME) }
    }
}


class AppViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val torikomi = app as? Torikomi ?: throw IllegalStateException("Torikomi not initialized")
        val engine = torikomi.ytDlpEngine
        return when {
            modelClass.isAssignableFrom(ThemeViewModel::class.java) -> ThemeViewModel(app) as T
            modelClass.isAssignableFrom(HistoryViewModel::class.java) -> HistoryViewModel(HistoryRepository(app)) as T
            modelClass.isAssignableFrom(StatisticsViewModel::class.java) ->
                StatisticsViewModel(HistoryRepository(app)) as T
            modelClass.isAssignableFrom(DownloadViewModel::class.java) ->
                DownloadViewModel(
                    engine = engine,
                    settingsRepo = SettingsRepository(app),
                    queueRepo = DownloadQueueRepository(app),
                    appContext = app,
                ) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(SettingsRepository(app), engine, app) as T
            modelClass.isAssignableFrom(StartupUpdateViewModel::class.java) ->
                StartupUpdateViewModel(SettingsRepository(app), app) as T
            modelClass.isAssignableFrom(SetupViewModel::class.java) ->
                SetupViewModel(engine, SettingsRepository(app)) as T
            modelClass.isAssignableFrom(SplashViewModel::class.java) ->
                SplashViewModel(SettingsRepository(app), engine) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
