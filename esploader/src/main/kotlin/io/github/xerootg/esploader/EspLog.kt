package io.github.xerootg.esploader

/** Receives human readable progress messages. Implementations must be cheap and thread-safe. */
interface EspLog {
    fun info(message: String)
    fun warn(message: String)
    fun note(message: String) = info(message)
    fun error(message: String) = warn(message)
    /** Very chatty protocol tracing (every packet). Off by default. */
    fun trace(message: String) {}

    object Silent : EspLog {
        override fun info(message: String) {}
        override fun warn(message: String) {}
    }
}

/** Progress callback: [done] of [total] bytes, with a short description such as "Writing at 0x00010000". */
fun interface ProgressListener {
    fun onProgress(done: Long, total: Long, description: String)

    companion object {
        val None = ProgressListener { _, _, _ -> }
    }
}

/**
 * Cooperative cancellation. The library checks [check] inside long loops and throws
 * [EspCancelledException] when it reports cancellation. Thread interruption is honoured too.
 */
fun interface CancelToken {
    fun isCancelled(): Boolean

    companion object {
        val Never = CancelToken { false }
    }
}

internal fun CancelToken.check() {
    if (isCancelled() || Thread.currentThread().isInterrupted) throw EspCancelledException()
}
