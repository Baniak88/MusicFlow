package app.musicflow.core.model

/**
 * Classified provider failure. The classification decides retry behaviour:
 * only [isTransient] errors are retried automatically, and [StreamExpired] triggers a
 * URL renewal instead of a plain retry.
 */
sealed class ProviderError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    abstract val isTransient: Boolean

    /** Connectivity problems, timeouts, 5xx responses. */
    class Network(message: String = "Network error", cause: Throwable? = null) : ProviderError(message, cause) {
        override val isTransient = true
    }

    /** HTTP 429 or bot-detection challenges. Retried, but with long backoff. */
    class RateLimited(message: String = "Rate limited by provider", cause: Throwable? = null) : ProviderError(message, cause) {
        override val isTransient = true
    }

    /** The signed stream URL expired (HTTP 403/410 after it previously worked). Renew, then retry once. */
    class StreamExpired(message: String = "Stream URL expired", cause: Throwable? = null) : ProviderError(message, cause) {
        override val isTransient = true
    }

    /** Sign-in required or credentials rejected. Never retried automatically. */
    class Authentication(message: String = "Authentication required", cause: Throwable? = null) : ProviderError(message, cause) {
        override val isTransient = false
    }

    /** Track removed, private, or does not exist. */
    class Unavailable(message: String = "Track unavailable", cause: Throwable? = null) : ProviderError(message, cause) {
        override val isTransient = false
    }

    /** Geographic, age or paid-content restriction imposed by the provider. */
    class Restricted(val reason: Reason, message: String = "Restricted by provider", cause: Throwable? = null) :
        ProviderError(message, cause) {
        /** DRM: the source only serves an encrypted stream, which MusicFlow does not decrypt. */
        enum class Reason { GEOGRAPHIC, AGE, PAID, DRM, OTHER }
        override val isTransient = false
    }

    /** Provider response could not be understood (usually: provider changed its format). */
    class Parsing(message: String = "Unexpected provider response", cause: Throwable? = null) : ProviderError(message, cause) {
        override val isTransient = false
    }

    /** No provider is configured or the provider is disabled. */
    class NotSupported(message: String = "Not supported by this provider") : ProviderError(message) {
        override val isTransient = false
    }

    class Unknown(message: String = "Unknown error", cause: Throwable? = null) : ProviderError(message, cause) {
        override val isTransient = false
    }
}
