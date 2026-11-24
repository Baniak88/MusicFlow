package app.musicflow.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.musicflow.core.designsystem.navigation.LocalNavigator

data class OssLibrary(val name: String, val license: String, val url: String)

/** Third-party components shipped in the APK (see THIRD_PARTY_NOTICES.md for full texts). */
val OSS_LIBRARIES = listOf(
    OssLibrary("NewPipe Extractor (TeamNewPipe)", "GPL-3.0-or-later", "https://github.com/TeamNewPipe/NewPipeExtractor"),
    OssLibrary("nanojson (TeamNewPipe fork)", "MIT / Apache-2.0", "https://github.com/TeamNewPipe/nanojson"),
    OssLibrary("Mozilla Rhino", "MPL-2.0", "https://github.com/mozilla/rhino"),
    OssLibrary("jsoup", "MIT", "https://jsoup.org"),
    OssLibrary("Protocol Buffers (lite)", "BSD-3-Clause", "https://github.com/protocolbuffers/protobuf"),
    OssLibrary("AndroidX (Media3, Compose, Room, DataStore, Lifecycle, Navigation, Palette, AppCompat)", "Apache-2.0", "https://developer.android.com/jetpack"),
    OssLibrary("Material Design 3 / Material Icons", "Apache-2.0", "https://m3.material.io"),
    OssLibrary("Dagger / Hilt", "Apache-2.0", "https://dagger.dev"),
    OssLibrary("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache-2.0", "https://kotlinlang.org"),
    OssLibrary("OkHttp / Okio", "Apache-2.0", "https://square.github.io/okhttp"),
    OssLibrary("Coil", "Apache-2.0", "https://coil-kt.github.io/coil"),
    OssLibrary("Reorderable (Calvin Liang)", "Apache-2.0", "https://github.com/Calvin-LL/Reorderable"),
    OssLibrary("Guava", "Apache-2.0", "https://github.com/google/guava"),
    OssLibrary("LRCLIB (lyrics service, not bundled)", "Service terms", "https://lrclib.net"),
)

@Composable
fun LicensesScreen(contentPadding: PaddingValues) {
    val nav = LocalNavigator.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item {
            Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.set_back)) }
                Text(stringResource(R.string.set_licenses), style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.lic_app_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.lic_app_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Text(stringResource(R.string.lic_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        }
        items(OSS_LIBRARIES) { lib ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(lib.name, style = MaterialTheme.typography.titleSmall)
                Text("${lib.license} · ${lib.url}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
