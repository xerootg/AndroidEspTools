package io.github.xerootg.esptools.session

import android.content.Context
import io.github.xerootg.esploader.ResetAfter
import io.github.xerootg.esploader.ResetMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val baud: Int = 921600,
    val useStub: Boolean = true,
    val resetMode: ResetMode = ResetMode.DEFAULT,
    val connectAttempts: Int = 7,
    val resetAfter: ResetAfter = ResetAfter.HARD_RESET,
    val monitorBaud: Int = 115200,
    val keepScreenOn: Boolean = true,
    val trace: Boolean = false,
    val autoConnectOnAttach: Boolean = true,
    val compress: Boolean = true,
    val verify: Boolean = true,
) {
    companion object {
        val BAUD_RATES = listOf(115200, 230400, 460800, 921600, 1500000, 2000000)
        val MONITOR_BAUD_RATES = listOf(9600, 19200, 38400, 57600, 74880, 115200, 230400, 460800, 921600)
    }
}

/** SharedPreferences-backed settings exposed as a StateFlow. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("esptools", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state
    val value: AppSettings get() = _state.value

    private fun load(): AppSettings = AppSettings(
        baud = prefs.getInt("baud", 921600),
        useStub = prefs.getBoolean("useStub", true),
        resetMode = ResetMode.valueOf(prefs.getString("resetMode", ResetMode.DEFAULT.name)!!),
        connectAttempts = prefs.getInt("connectAttempts", 7),
        resetAfter = ResetAfter.valueOf(prefs.getString("resetAfter", ResetAfter.HARD_RESET.name)!!),
        monitorBaud = prefs.getInt("monitorBaud", 115200),
        keepScreenOn = prefs.getBoolean("keepScreenOn", true),
        trace = prefs.getBoolean("trace", false),
        autoConnectOnAttach = prefs.getBoolean("autoConnectOnAttach", true),
        compress = prefs.getBoolean("compress", true),
        verify = prefs.getBoolean("verify", true),
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        prefs.edit()
            .putInt("baud", s.baud).putBoolean("useStub", s.useStub).putString("resetMode", s.resetMode.name)
            .putInt("connectAttempts", s.connectAttempts).putString("resetAfter", s.resetAfter.name)
            .putInt("monitorBaud", s.monitorBaud).putBoolean("keepScreenOn", s.keepScreenOn).putBoolean("trace", s.trace)
            .putBoolean("autoConnectOnAttach", s.autoConnectOnAttach).putBoolean("compress", s.compress).putBoolean("verify", s.verify)
            .apply()
        _state.value = s
    }
}
