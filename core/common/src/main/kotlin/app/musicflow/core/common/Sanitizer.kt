package app.musicflow.core.common

/**
 * Redacts secrets from strings before they reach logs or diagnostic exports.
 * Covers signed URL parameters, cookies, bearer tokens, API keys and e-mail addresses.
 */
object Sanitizer {
    private val sensitiveParams = listOf(
        "sig", "signature", "lsig", "n", "pot", "key", "token", "access_token", "refresh_token",
        "auth", "authorization", "cookie", "session", "sid", "password", "pass", "secret", "ip", "ei",
    )
    private val queryParam = Regex("""((?:^|[?&\s;,"'(])(?:${sensitiveParams.joinToString("|")})=)[^&#\s"';,)]*""", RegexOption.IGNORE_CASE)
    private val headerLike = Regex("""\b(authorization|cookie|set-cookie|x-goog-[a-z-]+|x-youtube-identity-token)\s*[:=]\s*[^\n,;]+""", RegexOption.IGNORE_CASE)
    private val bearer = Regex("""\bBearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val longSecret = Regex("""\b[A-Za-z0-9_-]{40,}\b""")
    private val googleVideoHost = Regex("""https?://[a-z0-9-]+\.googlevideo\.com/[^\s"]*""", RegexOption.IGNORE_CASE)

    fun sanitize(input: String): String {
        var s = googleVideoHost.replace(input) { "https://<stream-host>/<redacted>" }
        s = headerLike.replace(s) { "${it.groupValues[1]}: <redacted>" }
        s = bearer.replace(s, "Bearer <redacted>")
        s = queryParam.replace(s) { "${it.groupValues[1]}<redacted>" }
        s = email.replace(s, "<email>")
        s = longSecret.replace(s, "<redacted>")
        return s
    }
}
