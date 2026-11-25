package app.musicflow.feature.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.musicflow.playback.AudioOutput
import app.musicflow.playback.OutputKind

/** Icon for the active output; null for the phone speaker (nothing to point out in the mini player). */
fun outputIcon(kind: OutputKind): ImageVector? = when (kind) {
    OutputKind.CAR -> Icons.Rounded.DirectionsCar
    OutputKind.BLUETOOTH -> Icons.Rounded.BluetoothAudio
    OutputKind.WIRED -> Icons.Rounded.Headphones
    OutputKind.USB -> Icons.Rounded.Usb
    OutputKind.PHONE -> null
}

fun outputIconOrPhone(kind: OutputKind): ImageVector = outputIcon(kind) ?: Icons.Rounded.PhoneAndroid

@Composable
fun outputLabel(o: AudioOutput): String = o.name ?: stringResource(
    when (o.kind) {
        OutputKind.WIRED -> R.string.player_out_wired
        OutputKind.USB -> R.string.player_out_usb
        OutputKind.BLUETOOTH, OutputKind.CAR -> R.string.player_out_bluetooth
        OutputKind.PHONE -> R.string.player_out_phone
    },
)
