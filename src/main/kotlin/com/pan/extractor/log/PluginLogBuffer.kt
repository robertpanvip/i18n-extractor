package com.pan.extractor.log

import com.intellij.openapi.diagnostic.Logger
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * 插件内存日志缓存（循环缓冲区）。
 *
 * 所有通过 [warn] / [error] 发出的日志都会同时写入 IntelliJ 的 idea.log 和本内存缓冲区。
 * 崩溃时可调用 [dump] 导出完整日志，附加到错误通知的"复制完整日志"按钮中。
 */
object PluginLogBuffer {

    /** 最大保留行数，超出后丢弃最旧的。 */
    private const val MAX_LINES = 2000

    private val buffer = ArrayDeque<String>()
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    // ── 公开的日志方法 ──────────────────────────────────────

    /** 记录 WARN 级别日志（同时写入 idea.log 和内存缓冲区）。 */
    @JvmStatic
    fun warn(logger: Logger, msg: String, t: Throwable? = null) {
        if (t != null) logger.warn(msg, t) else logger.warn(msg)
        append("WARN", msg, t)
    }

    /** 记录 ERROR 级别日志（同时写入 idea.log 和内存缓冲区）。 */
    @JvmStatic
    fun error(logger: Logger, msg: String, t: Throwable? = null) {
        if (t != null) logger.error(msg, t) else logger.error(msg)
        append("ERROR", msg, t)
    }

    /** 记录 INFO 级别日志（同时写入 idea.log 和内存缓冲区）。 */
    @JvmStatic
    fun info(logger: Logger, msg: String, t: Throwable? = null) {
        if (t != null) logger.info(msg, t) else logger.info(msg)
        append("INFO", msg, t)
    }

    /** 将内存中的全部日志拼接为纯文本，用于复制到剪贴板。 */
    @Synchronized
    fun dump(): String = buildString {
        val snapshot = ArrayDeque(buffer)   // 快照拷贝，避免并发修改
        snapshot.forEach { line -> appendLine(line) }
    }

    /** 清空缓冲区。 */
    @Synchronized
    fun clear() {
        buffer.clear()
    }

    // ── 内部实现 ──────────────────────────────────────────

    @Synchronized
    private fun append(level: String, msg: String, t: Throwable?) {
        val timestamp = formatter.format(LocalDateTime.now())
        val sb = StringBuilder()
        sb.append("[$timestamp] [$level] $msg")
        if (t != null) {
            sb.appendLine().append(stackTraceToString(t))
        }
        buffer.addLast(sb.toString())
        while (buffer.size > MAX_LINES) {
            buffer.removeFirst()
        }
    }

    private fun stackTraceToString(t: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }
}