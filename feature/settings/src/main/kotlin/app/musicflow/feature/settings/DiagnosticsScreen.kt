package app.musicflow.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.common.DiagLevel
import app.musicflow.core.designsystem.navigation.LocalNavigator
import app.musicflow.core.designsystem.navigation.LocalSnackbarHost
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(contentPadding: PaddingValues, vm: DiagnosticsViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    val events by vm.events.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val host = LocalSnackbarHost.current
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(vm::export) }
    LaunchedEffect(Unit) { vm.exported.collect { host.showSnackbar(resources.getString(if (it) R.string.diag_exported else R.string.diag_export_failed)) } }
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item {
            Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.set_back)) }
                Text(stringResource(R.string.set_diagnostics_screen), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { exporter.launch("musicflow-diagnostics.txt") }) { Icon(Icons.Rounded.IosShare, stringResource(R.string.diag_export)) }
                IconButton(onClick = vm::clear) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.diag_clear)) }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Line(stringResource(R.string.diag_version), "${vm.appInfo.versionName} (${vm.appInfo.versionCode}, ${vm.appInfo.buildType})")
                Line(stringResource(R.string.diag_android), "SDK ${vm.appInfo.sdkInt} · ${vm.appInfo.device}")
                Line(stringResource(R.string.diag_active_provider), vm.activeProvider ?: "—")
                providers.values.forEach { h ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        StatusDot(h.status); Spacer(Modifier.width(8.dp))
                        Text("${h.displayName}: ${statusLabel(h.status)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Line(stringResource(R.string.diag_network), "${network.type}${if (network.isMetered) " · metered" else ""}${if (network.isValidated) "" else " · not validated"}")
                Line(stringResource(R.string.diag_engine), if (engine.serviceRunning) stringResource(R.string.diag_running) else stringResource(R.string.diag_stopped))
                Line(stringResource(R.string.diag_state), "${if (playback.isPlaying) "playing" else "paused"}${if (engine.buffering) " · buffering" else ""}")
                Line(stringResource(R.string.diag_source_format), engine.stream?.let { listOfNotNull(it.mimeType, it.describe().ifEmpty { null }).joinToString(" · ") } ?: "—")
                Line(stringResource(R.string.diag_decoder), engine.format?.let { listOfNotNull(it.sampleMimeType, it.codecs, it.bitrateKbps?.let { b -> "$b kbps" }, it.sampleRateHz?.let { r -> "$r Hz" }, it.channels?.let { c -> "${c}ch" }).joinToString(" · ") } ?: "—")
                Line(stringResource(R.string.diag_last_error), engine.lastError?.let { "${it::class.simpleName}: ${engine.lastErrorMessage}" } ?: "—")
                Text(stringResource(R.string.diag_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.diag_events), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
        items(events.asReversed(), key = { it.timeMillis.toString() + it.message.hashCode() + it.tag }) { e ->
            Text(
                "${fmt.format(Date(e.timeMillis))} ${e.level.name.first()} [${e.tag}] ${e.message}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = when (e.level) { DiagLevel.ERROR -> MaterialTheme.colorScheme.error; DiagLevel.WARN -> app.musicflow.core.designsystem.theme.LocalMfExtras.current.warning; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
