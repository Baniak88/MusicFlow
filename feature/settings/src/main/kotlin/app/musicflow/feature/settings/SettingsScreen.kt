package app.musicflow.feature.settings

import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.designsystem.component.messageRes
import app.musicflow.core.designsystem.navigation.LocalNavigator
import app.musicflow.core.designsystem.navigation.LocalSnackbarHost
import app.musicflow.core.designsystem.theme.LocalMfExtras
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.BufferProfile
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.ThemeMode
import app.musicflow.data.providers.registry.ProviderStatus

@Composable
fun qualityLabel(q: AudioQuality) = stringResource(
    when (q) {
        AudioQuality.DATA_SAVER -> R.string.set_quality_saver
        AudioQuality.STANDARD -> R.string.set_quality_standard
        AudioQuality.HIGH -> R.string.set_quality_high
        AudioQuality.BEST -> R.string.set_quality_best
    },
)

private data class Choice<T>(val value: T, val label: String)

@Composable
fun SettingsScreen(contentPadding: PaddingValues, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val cacheUsed by vm.cacheUsedBytes.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val host = LocalSnackbarHost.current
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::backup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::restore) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            host.showSnackbar(
                when (e) {
                    is SettingsEvent.Message -> if (e.arg != null) resources.getString(e.res, e.arg) else resources.getString(e.res)
                    is SettingsEvent.Error -> resources.getString((e.error as? ProviderError)?.messageRes() ?: app.musicflow.core.designsystem.R.string.mf_error_unknown)
                },
            )
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
        item {
            Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.set_back)) }
                Text(stringResource(R.string.set_title), style = MaterialTheme.typography.titleLarge)
            }
        }

        item { Header(R.string.set_appearance) }
        item { Clickable(stringResource(R.string.set_theme), themeLabel(s.themeMode)) { dialog = "theme" } }
        item { Toggle(stringResource(R.string.set_amoled), stringResource(R.string.set_amoled_desc), s.amoledBlack, vm::setAmoled) }
        if (Build.VERSION.SDK_INT >= 31) item { Toggle(stringResource(R.string.set_dynamic), stringResource(R.string.set_dynamic_desc), s.dynamicColor, vm::setDynamic) }
        item { Toggle(stringResource(R.string.set_artwork_colors), stringResource(R.string.set_artwork_colors_desc), s.artworkColors, vm::setArtworkColors) }
        item { Clickable(stringResource(R.string.set_language), languageLabel(s.languageTag)) { dialog = "language" } }

        item { Header(R.string.set_playback) }
        item { Clickable(stringResource(R.string.set_quality_wifi), qualityLabel(s.qualityWifi)) { dialog = "qwifi" } }
        item { Clickable(stringResource(R.string.set_quality_mobile), qualityLabel(s.qualityMobile)) { dialog = "qmobile" } }
        item { Text(stringResource(R.string.set_quality_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        item { Clickable(stringResource(R.string.set_buffer), bufferLabel(s.bufferProfile)) { dialog = "buffer" } }
        item {
            Clickable(
                stringResource(R.string.set_crossfade),
                if (s.crossfadeSeconds == 0) stringResource(R.string.set_crossfade_off) else stringResource(R.string.set_crossfade_value, s.crossfadeSeconds),
            ) { dialog = "crossfade" }
        }
        item { Toggle(stringResource(R.string.set_autoplay), stringResource(R.string.set_autoplay_desc), s.autoContinueQueue, vm::setAutoContinue) }
        item { Clickable(stringResource(R.string.set_normalization), normalizationLabel(s.normalization)) { dialog = "normalization" } }
        item { Toggle(stringResource(R.string.set_leveling), stringResource(R.string.set_leveling_desc), s.volumeLeveling, vm::setLeveling) }
        item { Toggle(stringResource(R.string.set_skip_silence), null, s.skipSilence, vm::setSkipSilence) }
        item { Toggle(stringResource(R.string.set_pause_disconnect), stringResource(R.string.set_pause_disconnect_desc), s.pauseOnDisconnect, vm::setPauseOnDisconnect) }
        item { Clickable(stringResource(R.string.set_equalizer), null, onClick = nav::openEqualizer) }

        item { Header(R.string.set_recommendations) }
        item { Toggle(stringResource(R.string.set_discovery), stringResource(R.string.set_discovery_desc), s.discoveryMode, vm::setDiscovery) }

        item { Header(R.string.set_providers) }
        providers.values.forEach { h ->
            item(key = "p" + h.providerId) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(h.status)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.displayName + if (h.experimental) " · " + stringResource(R.string.set_experimental) else "", style = MaterialTheme.typography.titleSmall)
                        Text(statusLabel(h.status) + (h.lastError?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    if (h.providerId == "yt") Switch(s.youtubeProviderEnabled, vm::setYouTube)
                    if (h.providerId == "sc") Switch(s.soundcloudProviderEnabled, vm::setSoundCloud)
                    if (h.providerId == "radio") Switch(s.radioProviderEnabled, vm::setRadio)
                    if (h.providerId == "mbr") Switch(s.musicbrainzEnabled, vm::setMusicBrainz)
                }
            }
        }
        if (s.radioProviderEnabled) item { Toggle(stringResource(R.string.set_radio_http), stringResource(R.string.set_radio_http_desc), s.radioAllowHttp, vm::setRadioHttp) }
        item { Text(stringResource(R.string.set_providers_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        item { Clickable(stringResource(R.string.set_country), s.contentCountry.ifEmpty { stringResource(R.string.set_country_auto) }) { dialog = "country" } }

        item { Header(R.string.set_storage) }
        item { Clickable(stringResource(R.string.set_cache_size), stringResource(R.string.set_cache_value, s.cacheSizeMb, Formatter.formatShortFileSize(context, cacheUsed))) { dialog = "cache" } }
        item { Clickable(stringResource(R.string.set_clear_cache), null) { dialog = "clear_cache" } }

        item { Header(R.string.set_privacy) }
        item { Toggle(stringResource(R.string.set_history), stringResource(R.string.set_history_desc), s.historyEnabled, vm::setHistory) }
        item { Clickable(stringResource(R.string.set_clear_history), null) { dialog = "clear_history" } }
        item { Clickable(stringResource(R.string.set_clear_search), null) { dialog = "clear_search" } }
        item { Toggle(stringResource(R.string.set_diagnostics), stringResource(R.string.set_diagnostics_desc), s.diagnosticsEnabled, vm::setDiagnostics) }

        item { Header(R.string.set_backup) }
        item { Clickable(stringResource(R.string.set_backup_export), stringResource(R.string.set_backup_export_desc)) { backup.launch("musicflow-backup.json") } }
        item { Clickable(stringResource(R.string.set_backup_import), stringResource(R.string.set_backup_import_desc)) { restore.launch(arrayOf("application/json", "*/*")) } }

        item { Header(R.string.set_about) }
        item { Clickable(stringResource(R.string.set_diagnostics_screen), null, onClick = nav::openDiagnostics) }
        item { Clickable(stringResource(R.string.set_licenses), stringResource(R.string.set_licenses_desc), onClick = nav::openLicenses) }
        item { Clickable(stringResource(R.string.set_version), "${vm.appInfo.versionName} (${vm.appInfo.versionCode})") {} }
        item { Toggle(stringResource(R.string.set_check_updates), stringResource(R.string.set_check_updates_desc), s.checkUpdates, vm::setCheckUpdates) }
        item {
            val checking by vm.checkingUpdates.collectAsStateWithLifecycle()
            Clickable(stringResource(R.string.set_check_now), if (checking) stringResource(R.string.set_checking) else null) { vm.checkUpdatesNow() }
        }
        item { Toggle(stringResource(R.string.set_auto_install), stringResource(R.string.set_auto_install_desc), s.autoInstallUpdates, vm::setAutoInstall) }
        item {
            val canInstall by vm.canInstall.collectAsStateWithLifecycle()
            androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { vm.refreshCanInstall(); onPauseOrDispose { } }
            if (!canInstall) Clickable(stringResource(R.string.set_allow_install), stringResource(R.string.set_allow_install_desc)) {
                runCatching { context.startActivity(vm.installPermissionIntent()) }
            }
        }
        item {
            val update by vm.availableUpdate.collectAsStateWithLifecycle()
            val install by vm.installState.collectAsStateWithLifecycle()
            update?.let { u ->
                val status = when (val st = install) {
                    is app.musicflow.data.repositories.InstallState.Downloading -> stringResource(R.string.set_update_downloading)
                    is app.musicflow.data.repositories.InstallState.Installing -> stringResource(R.string.set_update_installing)
                    is app.musicflow.data.repositories.InstallState.NeedsConfirmation -> stringResource(R.string.set_update_confirm)
                    is app.musicflow.data.repositories.InstallState.Failed -> stringResource(R.string.set_update_install_failed, st.message)
                    else -> null
                }
                Clickable(stringResource(R.string.set_install_version, u.version.toString()), status) { vm.installUpdate() }
            }
        }
        item {
            Clickable(stringResource(R.string.set_extractor), stringResource(R.string.set_extractor_value, vm.appInfo.extractorVersion.ifEmpty { "?" })) {}
        }
    }

    when (dialog) {
        "theme" -> ChoiceDialog(stringResource(R.string.set_theme), ThemeMode.entries.map { Choice(it, themeLabel(it)) }, s.themeMode, { dialog = null }, vm::setTheme)
        "language" -> ChoiceDialog(stringResource(R.string.set_language), listOf(Choice("", stringResource(R.string.set_language_system)), Choice("en", "English"), Choice("pl", "Polski")), s.languageTag, { dialog = null }, vm::setLanguage)
        "qwifi" -> ChoiceDialog(stringResource(R.string.set_quality_wifi), AudioQuality.entries.map { Choice(it, qualityLabel(it)) }, s.qualityWifi, { dialog = null }, vm::setQualityWifi)
        "qmobile" -> ChoiceDialog(stringResource(R.string.set_quality_mobile), AudioQuality.entries.map { Choice(it, qualityLabel(it)) }, s.qualityMobile, { dialog = null }, vm::setQualityMobile)
        "buffer" -> ChoiceDialog(stringResource(R.string.set_buffer), BufferProfile.entries.map { Choice(it, bufferLabel(it)) }, s.bufferProfile, { dialog = null }, vm::setBuffer)
        "cache" -> ChoiceDialog(stringResource(R.string.set_cache_size), listOf(128, 256, 512, 1024, 2048, 4096).map { Choice(it, "$it MB") }, s.cacheSizeMb, { dialog = null }, vm::setCacheSize)
        "normalization" -> ChoiceDialog(
            stringResource(R.string.set_normalization),
            app.musicflow.core.model.NormalizationLevel.entries.map { Choice(it, normalizationLabel(it)) },
            s.normalization, { dialog = null }, vm::setNormalization,
        )
        "crossfade" -> CrossfadeDialog(s.crossfadeSeconds, { dialog = null }, vm::setCrossfade)
        "country" -> CountryDialog(s.contentCountry, { dialog = null }, vm::setCountry)
        "clear_cache" -> Confirm(stringResource(R.string.set_clear_cache), stringResource(R.string.set_clear_cache_desc), { dialog = null }, vm::clearCache)
        "clear_history" -> Confirm(stringResource(R.string.set_clear_history), stringResource(R.string.set_clear_history_desc), { dialog = null }, vm::clearHistory)
        "clear_search" -> Confirm(stringResource(R.string.set_clear_search), null, { dialog = null }, vm::clearSearchHistory)
    }
}

@Composable private fun normalizationLabel(l: app.musicflow.core.model.NormalizationLevel) = stringResource(when (l) {
    app.musicflow.core.model.NormalizationLevel.OFF -> R.string.set_norm_off
    app.musicflow.core.model.NormalizationLevel.QUIET -> R.string.set_norm_quiet
    app.musicflow.core.model.NormalizationLevel.NORMAL -> R.string.set_norm_normal
    app.musicflow.core.model.NormalizationLevel.LOUD -> R.string.set_norm_loud
})
@Composable private fun themeLabel(t: ThemeMode) = stringResource(when (t) { ThemeMode.SYSTEM -> R.string.set_theme_system; ThemeMode.DARK -> R.string.set_theme_dark; ThemeMode.LIGHT -> R.string.set_theme_light })
@Composable private fun languageLabel(tag: String) = when (tag) { "en" -> "English"; "pl" -> "Polski"; else -> stringResource(R.string.set_language_system) }
@Composable private fun bufferLabel(b: BufferProfile) = stringResource(when (b) { BufferProfile.LOW -> R.string.set_buffer_low; BufferProfile.BALANCED -> R.string.set_buffer_balanced; BufferProfile.LARGE -> R.string.set_buffer_large })
@Composable fun statusLabel(s: ProviderStatus) = stringResource(when (s) {
    ProviderStatus.AVAILABLE -> R.string.set_status_available; ProviderStatus.DEGRADED -> R.string.set_status_degraded
    ProviderStatus.UNAVAILABLE -> R.string.set_status_unavailable; ProviderStatus.DISABLED -> R.string.set_status_disabled
    ProviderStatus.UNKNOWN -> R.string.set_status_unknown
})

@Composable
fun StatusDot(s: ProviderStatus) {
    val ex = LocalMfExtras.current
    val c = when (s) {
        ProviderStatus.AVAILABLE -> ex.success; ProviderStatus.DEGRADED -> ex.warning
        ProviderStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.outline
    }
    Surface(shape = CircleShape, color = c, modifier = Modifier.size(10.dp)) {}
}

@Composable
private fun Header(res: Int) = Text(stringResource(res), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 6.dp))

@Composable
private fun Toggle(title: String, desc: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!value) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(value, onChange)
    }
}

@Composable
private fun Clickable(title: String, value: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, choices: List<Choice<T>>, selected: T, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                choices.forEach { c ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onSelect(c.value); onDismiss() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(c.value == selected, onClick = { onSelect(c.value); onDismiss() })
                        Spacer(Modifier.width(8.dp))
                        Text(c.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.set_close)) } },
    )
}

@Composable
private fun CrossfadeDialog(current: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    var value by rememberSaveable { mutableStateOf(current.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_crossfade)) },
        text = {
            Column {
                Text(
                    if (value.toInt() == 0) stringResource(R.string.set_crossfade_off) else stringResource(R.string.set_crossfade_value, value.toInt()),
                    style = MaterialTheme.typography.headlineSmall,
                )
                androidx.compose.material3.Slider(
                    value = value, onValueChange = { value = it }, valueRange = 0f..12f, steps = 11,
                    modifier = Modifier.testTag("crossfade_slider"),
                )
                Text(stringResource(R.string.set_crossfade_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSet(value.toInt()); onDismiss() }) { Text(stringResource(R.string.set_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.set_close)) } },
    )
}

@Composable
private fun CountryDialog(current: String, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_country)) },
        text = { Column { Text(stringResource(R.string.set_country_desc)); OutlinedTextField(text, { text = it.take(2).uppercase() }, singleLine = true, placeholder = { Text("PL") }) } },
        confirmButton = { TextButton(onClick = { onSet(text); onDismiss() }) { Text(stringResource(R.string.set_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.set_close)) } },
    )
}

@Composable
private fun Confirm(title: String, body: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = body?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(stringResource(R.string.set_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.set_close)) } },
    )
}

@Suppress("unused") private val unusedColor = Color.Unspecified
