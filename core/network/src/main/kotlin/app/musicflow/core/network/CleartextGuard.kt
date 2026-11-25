package app.musicflow.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * MusicFlow's HTTPS rule, enforced in code for every hop (redirects included).
 *
 * Plain HTTP is refused, except for requests marked with [ALLOW_HEADER] — used only for internet
 * radio audio streams when the user allows unencrypted stations (many small broadcasters have no
 * HTTPS). The marker is removed before the request leaves the device. The platform network
 * security config permits cleartext so that this guard, not the OS, makes the decision.
 */
class CleartextGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val allowed = request.header(ALLOW_HEADER) != null
        if (!request.isHttps && !allowed) {
            throw IOException("Unencrypted HTTP to ${request.url.host} is not allowed")
        }
        return chain.proceed(if (allowed) request.newBuilder().removeHeader(ALLOW_HEADER).build() else request)
    }

    companion object {
        const val ALLOW_HEADER = "X-MusicFlow-Allow-Cleartext"
    }
}
