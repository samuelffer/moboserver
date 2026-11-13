package com.moboserver.app.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash in a file so the app can show it on the next launch
 * instead of silently closing. The report can be copied or shared from there,
 * which is the only practical way to debug a release build on a phone.
 */
object CrashLog {
    private const val FILE_NAME = "crash.log"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, error)
            } catch (ignored: Throwable) {
                // never let the reporting path hide the original crash
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun write(context: Context, error: Throwable) {
        val trace = StringWriter().also { writer -> error.printStackTrace(PrintWriter(writer)) }.toString()
        val header = buildString {
            appendLine("MoboServer — relatório de erro")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine(
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • " +
                    "${Build.MANUFACTURER} ${Build.MODEL} • ${Build.SUPPORTED_ABIS.joinToString(",")}",
            )
            appendLine()
        }
        try {
            file(context).writeText(header + trace)
        } catch (ignored: Throwable) {
            // storage full or read-only: nothing else to do
        }
    }

    fun read(context: Context): String? = try {
        val file = file(context)
        if (file.isFile) file.readText().takeIf { it.isNotBlank() } else null
    } catch (e: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (ignored: Exception) {
        }
    }
}
