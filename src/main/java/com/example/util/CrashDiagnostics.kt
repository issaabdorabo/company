package com.example.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the most recent uncaught app error in private app storage so it can be
 * copied from Settings and attached to a support message without requiring Logcat.
 */
object CrashDiagnostics {
    private const val REPORT_FILE = "last_crash_report.txt"

    fun record(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val trace = StringWriter()
            throwable.printStackTrace(PrintWriter(trace))
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val report = buildString {
                appendLine("Smart Inventory crash report")
                appendLine("Time: $time")
                appendLine("Package: ${context.packageName}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Thread: ${thread.name}")
                appendLine()
                append(trace.toString())
            }
            File(context.filesDir, REPORT_FILE).writeText(report, Charsets.UTF_8)
        } catch (_: Throwable) {
            // Never replace the original uncaught exception with a diagnostics failure.
        }
    }

    fun hasLastCrashReport(context: Context): Boolean =
        runCatching { File(context.filesDir, REPORT_FILE).isFile }.getOrDefault(false)

    fun readLastCrashReport(context: Context): String? =
        runCatching {
            File(context.filesDir, REPORT_FILE)
                .takeIf { it.isFile }
                ?.readText(Charsets.UTF_8)
        }.getOrNull()
}
