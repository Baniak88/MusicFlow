package app.musicflow.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.designsystem.component.MessageState
import app.musicflow.core.designsystem.navigation.LocalNavigator

@Composable
fun EqualizerScreen(contentPadding: PaddingValues, vm: PlayerViewModel = hiltViewModel()) {
    val eq by vm.equalizer.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item {
            Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.player_back)) }
                Text(stringResource(R.string.player_equalizer), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (eq.available) Switch(eq.enabled, vm::setEqEnabled, modifier = Modifier.padding(end = 12.dp))
            }
        }
        if (!eq.available) {
            item { MessageState(stringResource(R.string.player_eq_unavailable), body = stringResource(R.string.player_eq_unavailable_body)) }
        } else {
            if (eq.presets.isNotEmpty()) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(eq.presets) { i, p -> FilterChip(selected = eq.currentPreset == i, onClick = { vm.usePreset(i) }, label = { Text(p) }, enabled = eq.enabled) }
                }
            }
            items(eq.bands, key = { it.index }) { band ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Row {
                        Text(if (band.centerHz >= 1000) "${band.centerHz / 1000} kHz" else "${band.centerHz} Hz", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text("%+.1f dB".format(band.levelMb / 100f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(band.levelMb.toFloat(), { vm.setBand(band.index, it.toInt()) }, valueRange = eq.minMb.toFloat()..eq.maxMb.toFloat(), enabled = eq.enabled)
                }
            }
        }
        item {
            val intent = vm.systemEqualizer()
            if (intent != null) OutlinedButton(onClick = { runCatching { context.startActivity(intent) } }, modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.player_system_eq))
            }
        }
        item { Text(stringResource(R.string.player_eq_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp).width(560.dp)) }
    }
}
