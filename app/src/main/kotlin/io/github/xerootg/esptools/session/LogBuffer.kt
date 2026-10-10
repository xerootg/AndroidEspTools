package io.github.xerootg.esptools.session

import io.github.xerootg.esploader.EspLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { INFO, NOTE, WARN, ERROR, TRACE }

data class LogLine(val time: Long, val level: LogLevel, val text: String) {
    fun format(): String {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(time))
        val tag = when (level) { LogLevel.WARN -> "W "; LogLevel.ERROR -> "E "; LogLevel.NOTE -> "N "; LogLevel.TRACE -> "T "; LogLevel.INFO -> "  " }
        return "$ts $tag$text"
    }
}

/** Append-only log shown at the bottom of every screen. Thread-safe. */
class LogBuffer(private val maxLines: Int = 3000) : EspLog {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines
    @Volatile var traceEnabled: Boolean = false

    fun add(level: LogLevel, text: String) {
        val line = LogLine(System.currentTimeMillis(), level, text)
        _lines.update { old -> if (old.size >= maxLines) old.drop(old.size - maxLines + 1) + line else old + line }
    }

    override fun info(message: String) = add(LogLevel.INFO, message)
    override fun warn(message: String) = add(LogLevel.WARN, message)
    override fun note(message: String) = add(LogLevel.NOTE, message)
    override fun error(message: String) = add(LogLevel.ERROR, message)
    override fun trace(message: String) { if (traceEnabled) add(LogLevel.TRACE, message) }

    fun clear() = _lines.update { emptyList() }

    fun dump(): String = _lines.value.joinToString("\n") { it.format() }
}
