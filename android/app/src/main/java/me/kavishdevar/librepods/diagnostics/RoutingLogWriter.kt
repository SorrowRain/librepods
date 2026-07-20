/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.diagnostics

import android.content.Context
import android.os.Build
import android.util.JsonWriter
import me.kavishdevar.librepods.BuildConfig
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class RoutingStorageStats(
    val fileCount: Int,
    val totalBytes: Long,
)

/** All methods are called by RoutingTrace's single writer coroutine. */
internal class RoutingLogWriter(context: Context) {
    private val appContext = context.applicationContext
    private val logDirectory = File(appContext.noBackupFilesDir, LOG_DIRECTORY_NAME)
    private val exportDirectory = File(appContext.cacheDir, EXPORT_DIRECTORY_NAME)

    private var currentWriter: BufferedWriter? = null
    private var currentFile: File? = null
    private var currentBytes = 0L
    private var currentSessionId: String? = null
    private var currentSessionTimestamp = ""
    private var segmentIndex = 0

    val activeSessionId: String?
        get() = currentSessionId

    fun startSession(sessionId: String) {
        if (currentSessionId == sessionId && currentWriter != null) return
        closeSession()
        currentSessionId = sessionId
        currentSessionTimestamp = timestampForFileName()
        segmentIndex = 0
        openSegment()
    }

    fun append(event: RoutingEvent) {
        check(currentSessionId == event.sessionId) {
            "Routing event session does not match the active writer session"
        }

        val line = RoutingEventEncoder.encode(event)
        val encodedSize = line.toByteArray(StandardCharsets.UTF_8).size + 1L
        if (currentBytes > 0L && currentBytes + encodedSize > MAX_SEGMENT_BYTES) {
            rotateSegment()
        }

        val writer = checkNotNull(currentWriter) { "Routing log writer is not open" }
        writer.write(line)
        writer.newLine()
        // Events are infrequent and causality is more valuable than retaining a larger buffer
        // across a process crash. This never blocks the audio or Bluetooth callback threads.
        writer.flush()
        currentBytes += encodedSize
    }

    fun closeSession() {
        runCatching { currentWriter?.flush() }
        runCatching { currentWriter?.close() }
        currentWriter = null
        currentFile = null
        currentBytes = 0L
        currentSessionId = null
        currentSessionTimestamp = ""
        segmentIndex = 0
    }

    fun flush() {
        currentWriter?.flush()
    }

    fun clear() {
        closeSession()
        logFiles().forEach { file ->
            if (!file.delete() && file.exists()) {
                throw IllegalStateException("Could not delete routing log segment ${file.name}")
            }
        }
        exportDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
            ?.forEach { file ->
                if (!file.delete() && file.exists()) {
                    throw IllegalStateException("Could not delete routing diagnostic export")
                }
            }
    }

    fun export(
        debugEnabled: Boolean,
        activeSessionId: String?,
        droppedEventCount: Long,
    ): File {
        flush()
        if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
            throw IllegalStateException("Could not create routing diagnostic export directory")
        }
        exportDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
            ?.forEach { it.delete() }

        val output = File(
            exportDirectory,
            "librepods-routing-${timestampForFileName()}.zip",
        )
        val segments = logFiles()
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(
                    encodeManifest(
                        debugEnabled = debugEnabled,
                        activeSessionId = activeSessionId,
                        droppedEventCount = droppedEventCount,
                        segments = segments,
                    ).toByteArray(StandardCharsets.UTF_8),
                )
                zip.closeEntry()

                segments.forEach { segment ->
                    zip.putNextEntry(ZipEntry("routing/${segment.name}"))
                    segment.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
        return output
    }

    fun stats(): RoutingStorageStats {
        val files = logFiles()
        return RoutingStorageStats(
            fileCount = files.size,
            totalBytes = files.sumOf(File::length),
        )
    }

    private fun rotateSegment() {
        runCatching { currentWriter?.flush() }.getOrThrow()
        runCatching { currentWriter?.close() }.getOrThrow()
        currentWriter = null
        currentFile = null
        currentBytes = 0L
        segmentIndex += 1
        openSegment()
    }

    private fun openSegment() {
        if (!logDirectory.exists() && !logDirectory.mkdirs()) {
            throw IllegalStateException("Could not create routing diagnostic directory")
        }
        val sessionId = checkNotNull(currentSessionId)
        val safeSessionId = sessionId.filter { it.isLetterOrDigit() }.take(12)
        val file = File(
            logDirectory,
            "routing_${currentSessionTimestamp}_${safeSessionId}_$segmentIndex.jsonl",
        )
        currentWriter = BufferedWriter(
            OutputStreamWriter(FileOutputStream(file, false), StandardCharsets.UTF_8),
        )
        currentFile = file
        currentBytes = 0L
        pruneOldSegments()
    }

    private fun pruneOldSegments() {
        val files = logFiles().toMutableList()
        var totalBytes = files.sumOf(File::length)
        while (files.size > MAX_SEGMENT_COUNT ||
            totalBytes > MAX_TOTAL_BYTES - MAX_SEGMENT_BYTES
        ) {
            val candidate = files.firstOrNull { it != currentFile } ?: break
            val candidateBytes = candidate.length()
            if (!candidate.delete() && candidate.exists()) break
            totalBytes -= candidateBytes
            files.remove(candidate)
        }
    }

    private fun logFiles(): List<File> {
        return logDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("jsonl", ignoreCase = true) }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .orEmpty()
    }

    private fun encodeManifest(
        debugEnabled: Boolean,
        activeSessionId: String?,
        droppedEventCount: Long,
        segments: List<File>,
    ): String {
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginObject()
            writer.name("schema").value(ROUTING_TRACE_SCHEMA_VERSION.toLong())
            writer.name("exported_at_epoch_ms").value(System.currentTimeMillis())
            writer.name("debug_enabled").value(debugEnabled)
            activeSessionId?.let { writer.name("active_session_id").value(it) }
            writer.name("dropped_event_count").value(droppedEventCount)
            writer.name("raw_packets_included").value(false)
            writer.name("logcat_included").value(false)
            writer.name("redaction").value(
                "Semantic routing events only; addresses, long hexadecimal values, serials, " +
                    "media metadata, notification content, and cryptographic keys are excluded.",
            )
            writer.name("app").beginObject()
            writer.name("version_name").value(BuildConfig.VERSION_NAME)
            writer.name("version_code").value(BuildConfig.VERSION_CODE.toLong())
            writer.name("build_type").value(BuildConfig.BUILD_TYPE)
            writer.name("flavor").value(BuildConfig.FLAVOR)
            writer.endObject()
            writer.name("device").beginObject()
            writer.name("manufacturer").value(Build.MANUFACTURER)
            writer.name("model").value(Build.MODEL)
            writer.name("android_release").value(Build.VERSION.RELEASE)
            writer.name("api_level").value(Build.VERSION.SDK_INT.toLong())
            writer.endObject()
            writer.name("segments").beginArray()
            segments.forEach { segment ->
                writer.beginObject()
                writer.name("name").value(segment.name)
                writer.name("bytes").value(segment.length())
                writer.endObject()
            }
            writer.endArray()
            writer.endObject()
        }
        return output.toString()
    }

    private fun timestampForFileName(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    companion object {
        private const val LOG_DIRECTORY_NAME = "routing-diagnostics"
        const val EXPORT_DIRECTORY_NAME = "diagnostic_exports"
        private const val MAX_SEGMENT_COUNT = 32
        private const val MAX_SEGMENT_BYTES = 1_024L * 1_024L
        private const val MAX_TOTAL_BYTES = 8L * 1_024L * 1_024L
    }
}
