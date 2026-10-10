package com.termux.terminal

import android.os.Handler
import android.os.Looper

/**
 * Drives a Termux [TerminalSession] from a byte stream instead of a PTY.
 *
 * [TerminalSession] is final and normally forks a shell through JNI, but [TerminalView] only
 * needs a session that owns a [TerminalEmulator] and accepts [TerminalSession.write] calls. This
 * class lives in the `com.termux.terminal` package so it can install the emulator directly and
 * drain the keyboard queue the view writes into. The subprocess code path is never touched
 * (the session's `updateSize` still issues a harmless window-size ioctl on fd 0).
 */
class SerialTerminalBridge(
    client: TerminalSessionClient,
    columns: Int = 80,
    rows: Int = 24,
    private val transcriptRows: Int = 5000,
    private val output: (ByteArray) -> Unit,
) {
    val session: TerminalSession = TerminalSession("/dev/null", "/", emptyArray(), emptyArray(), transcriptRows, client)
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<ByteArray>()
    @Volatile private var closed = false

    init {
        session.mEmulator = TerminalEmulator(session, columns, rows, transcriptRows, client)
        session.mShellPid = 1 // non-zero so write() queues the data instead of dropping it
        Thread({ drainKeyboard() }, "terminal-to-serial").apply { isDaemon = true }.start()
    }

    val emulator: TerminalEmulator get() = session.mEmulator

    /** Bytes from the device; may be called from any thread. */
    fun feed(bytes: ByteArray) {
        if (closed) return
        synchronized(pending) { pending.addLast(bytes) }
        main.post(::flush)
    }

    private fun flush() {
        val chunks = synchronized(pending) { val l = pending.toList(); pending.clear(); l }
        if (chunks.isEmpty()) return
        for (c in chunks) session.mEmulator.append(c, c.size)
        session.notifyScreenUpdate()
    }

    /** Pull what the view typed and hand it to the serial port. */
    private fun drainKeyboard() {
        val buf = ByteArray(4096)
        while (!closed) {
            val n = session.mTerminalToProcessIOQueue.read(buf, true)
            if (n < 0) break
            if (n > 0) output(buf.copyOf(n))
        }
    }

    fun updateClient(client: TerminalSessionClient) {
        session.updateTerminalSessionClient(client)
    }

    fun reset() = main.post { session.mEmulator.reset(); session.notifyScreenUpdate() }

    fun close() {
        closed = true
        session.mTerminalToProcessIOQueue.close()
    }
}
