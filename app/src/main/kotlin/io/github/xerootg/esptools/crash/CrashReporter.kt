package io.github.xerootg.esptools.crash

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import io.github.xerootg.esptools.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions into a self-contained text report (stack trace with causes,
 * app and device details, connected USB devices and the tail of the session log) saved under
 * `files/crashes/`. The report is offered to the user on the next launch; the process still dies
 * the way Android expects.
 */
object CrashReporter {
    private const val DIR = "crashes"
    private const val MAX_REPORTS = 10
    @Volatile private var extraInfo: (() -> String)? = null

    fun install(context: Context, logProvider: () -> String) {
        extraInfo = logProvider
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { write(app, buildReport(app, thread, throwable)) } catch (e: Throwable) { /* never mask the original crash */ }
            previous?.uncaughtException(thread, throwable) ?: run { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10) }
        }
    }

    fun reportsDir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun pendingReports(context: Context): List<File> =
        reportsDir(context).listFiles { f -> f.name.endsWith(".txt") }?.sortedByDescending { it.name } ?: emptyList()

    fun delete(context: Context, file: File) { file.delete() }
    fun deleteAll(context: Context) { pendingReports(context).forEach { it.delete() } }

    private fun write(context: Context, report: String) {
        val dir = reportsDir(context)
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        File(dir, "crash-$ts.txt").writeText(report)
        pendingReports(context).drop(MAX_REPORTS).forEach { it.delete() }
    }

    /** Device / app facts, reusable for a non-crash diagnostics export. */
    fun environment(context: Context): String = buildString {
        appendLine("App: ${context.packageName} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.BOARD})")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, ${Build.VERSION.SECURITY_PATCH})")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Locale: ${Locale.getDefault()}")
        try {
            val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = usb.deviceList.values
            appendLine("USB host: ${context.packageManager.hasSystemFeature("android.hardware.usb.host")}, devices: ${devices.size}")
            for (d in devices) {
                val name = try { d.productName } catch (e: SecurityException) { null }
                val mfr = try { d.manufacturerName } catch (e: SecurityException) { null }
                appendLine("  ${d.deviceName} %04x:%04x %s / %s, class %d, %d interface(s), permission=%s".format(d.vendorId, d.productId, mfr ?: "?", name ?: "?", d.deviceClass, d.interfaceCount, usb.hasPermission(d)))
                for (i in 0 until d.interfaceCount) {
                    val itf = d.getInterface(i)
                    appendLine("    if$i class ${itf.interfaceClass}/${itf.interfaceSubclass}/${itf.interfaceProtocol}, ${itf.endpointCount} endpoint(s)")
                }
            }
        } catch (e: Throwable) {
            appendLine("USB: unavailable (${e})")
        }
    }

    fun buildReport(context: Context, thread: Thread, throwable: Throwable): String = buildString {
        appendLine("===== ESP Tools crash report =====")
        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())}")
        appendLine("Thread: ${thread.name} (id ${thread.id})")
        appendLine()
        append(environment(context))
        appendLine()
        appendLine("----- Exception -----")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        appendLine(sw.toString().trimEnd())
        appendLine()
        appendLine("----- Other threads -----")
        try {
            for ((t, frames) in Thread.getAllStackTraces()) {
                if (t === thread) continue
                appendLine("${t.name} (${t.state})")
                frames.take(12).forEach { appendLine("    at $it") }
            }
        } catch (e: Throwable) { appendLine("unavailable: $e") }
        appendLine()
        appendLine("----- Session log (tail) -----")
        try {
            val log = extraInfo?.invoke() ?: ""
            val lines = log.lines()
            appendLine(lines.takeLast(400).joinToString("\n"))
        } catch (e: Throwable) { appendLine("unavailable: $e") }
        appendLine("===== end of report =====")
    }

    /** Diagnostics without a crash: environment + full session log. */
    fun diagnostics(context: Context): String = buildString {
        appendLine("===== ESP Tools diagnostics =====")
        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())}")
        append(environment(context))
        appendLine()
        appendLine("----- Session log -----")
        appendLine(extraInfo?.invoke() ?: "")
    }
}
