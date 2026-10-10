package io.github.xerootg.esptools.session

import android.util.Log
import com.termux.terminal.SerialTerminalBridge
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView

/**
 * App-scoped owner of the terminal emulator so its scrollback survives navigating away from the
 * monitor screen. The currently attached [TerminalView] (if any) is repainted on changes.
 */
class TerminalHost(private val sendToDevice: (ByteArray) -> Unit, private val log: LogBuffer) {
    @Volatile var view: TerminalView? = null

    private val client: TerminalSessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { view?.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val v = view ?: return
            val cm = v.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val v = view ?: return
            val cm = v.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(v.context)?.toString() ?: return
            bridge.emulator.paste(text)
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) { view?.onScreenUpdated() }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: "terminal", message ?: "") ; log.error("terminal: $message") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: "terminal", message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: "terminal", message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: "terminal", message ?: "") }
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: "terminal", message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: "terminal", "", e) }
    }

    val bridge: SerialTerminalBridge = SerialTerminalBridge(client, output = sendToDevice)
    val session: TerminalSession get() = bridge.session

    fun feed(bytes: ByteArray) = bridge.feed(bytes)
    fun clear() = bridge.reset()
}
