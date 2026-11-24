package app.musicflow.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class DiagLevel { DEBUG, INFO, WARN, ERROR }

data class DiagnosticEvent(
    val timeMillis: Long,
    val level: DiagLevel,
    val tag: String,
    val message: String,
)

/**
 * In-memory ring buffer of diagnostic events. Messages are sanitised on write, so nothing
 * secret is ever retained, displayed or exported. Never persisted to disk unless the user exports.
 */
@Singleton
class DiagnosticsLog @Inject constructor() {
    private val capacity = 300
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    @Volatile var enabled: Boolean = true

    fun log(level: DiagLevel, tag: String, message: String, clock: Clock = Clock.System) {
        if (!enabled && level < DiagLevel.WARN) return
        val event = DiagnosticEvent(clock.now(), level, tag, Sanitizer.sanitize(message))
        _events.update { (it + event).takeLast(capacity) }
    }

    fun d(tag: String, msg: String) = log(DiagLevel.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(DiagLevel.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(DiagLevel.WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        log(DiagLevel.ERROR, tag, if (t != null) "$msg: ${t::class.java.simpleName}: ${t.message}" else msg)

    fun clear() = _events.update { emptyList() }

    fun export(header: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return buildString {
            appendLine(Sanitizer.sanitize(header))
            appendLine("----")
            events.value.forEach { appendLine("${fmt.format(Date(it.timeMillis))} ${it.level} [${it.tag}] ${it.message}") }
        }
    }
}
