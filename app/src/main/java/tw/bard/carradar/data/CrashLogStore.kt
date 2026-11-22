package tw.bard.carradar.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CrashLogStore(context: Context) {
    private val appCtx = context.applicationContext
    private val logFile = File(appCtx.filesDir, "crash_logs.txt")
    private val flagFile = File(appCtx.filesDir, "crash_unread.flag")

    fun recordThrowable(tag: String, t: Throwable) {
        runCatching {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val taipei = ZoneId.of("Asia/Taipei")
            val timeStr = Instant.now().atZone(taipei)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val header = "=== [$timeStr] $tag | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL} ==="
            val entry = "$header\n$sw\n"

            val existing = if (logFile.exists()) logFile.readText(Charsets.UTF_8) else ""
            val combined = (entry + existing).take(32_000)
            logFile.writeText(combined, Charsets.UTF_8)
            flagFile.writeText("1", Charsets.UTF_8)
        }
    }

    fun hasUnread(): Boolean = flagFile.exists()

    fun markRead() {
        if (flagFile.exists()) flagFile.delete()
    }

    fun readAll(): String {
        if (!logFile.exists()) return ""
        return runCatching { logFile.readText(Charsets.UTF_8) }.getOrDefault("")
    }

    fun clear() {
        if (logFile.exists()) logFile.delete()
        if (flagFile.exists()) flagFile.delete()
    }
}
