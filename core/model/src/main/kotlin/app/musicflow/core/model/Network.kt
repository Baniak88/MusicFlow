package app.musicflow.core.model

enum class NetworkType { NONE, WIFI, CELLULAR, ETHERNET, OTHER }

data class NetworkStatus(
    val type: NetworkType,
    val isMetered: Boolean,
    val isValidated: Boolean,
) {
    val isConnected: Boolean get() = type != NetworkType.NONE

    companion object {
        val Offline = NetworkStatus(NetworkType.NONE, isMetered = false, isValidated = false)
    }
}
