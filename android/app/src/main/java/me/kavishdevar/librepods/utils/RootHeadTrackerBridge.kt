/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.utils

import android.util.Log
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Streams decoded AirPods poses to the root UHID helper. */
class RootHeadTrackerBridge {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var macAddress: String? = null

    @Synchronized
    fun start(mac: String): Boolean {
        if (!MAC_PATTERN.matches(mac)) {
            Log.e(TAG, "Refusing invalid Bluetooth address")
            return false
        }
        if (process?.isAlive == true && macAddress == mac) return true

        stop()
        return try {
            val command = buildString {
                append("if [ -x /system/bin/librepods-headtracker ]; then ")
                append("exec /system/bin/librepods-headtracker --mac ").append(mac)
                append(" --stdin")
                append("; elif [ -x /data/adb/modules/librepods/system/bin/librepods-headtracker ]; then ")
                append("exec /data/adb/modules/librepods/system/bin/librepods-headtracker --mac ")
                    .append(mac).append(" --stdin")
                append("; else exec /data/local/tmp/headtracker_uhid --mac ").append(mac)
                    .append(" --stdin; fi")
            }
            val started = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = started
            writer = BufferedWriter(
                OutputStreamWriter(started.outputStream, StandardCharsets.US_ASCII)
            )
            macAddress = mac
            Thread({
                try {
                    started.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { Log.d(TAG, it) }
                    }
                } catch (e: IOException) {
                    // stop() closes the process streams from another thread. That is a normal
                    // lifecycle event and must not escape as an uncaught app-process exception.
                    Log.d(TAG, "Head tracker log stream closed: ${e.message}")
                }
            }, "LibrePods-headtracker-log").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Started root head tracker for $mac")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Unable to start root head tracker", e)
            stop()
            false
        }
    }

    @Synchronized
    fun submit(pose: HeadPose) {
        val output = writer ?: return
        try {
            output.write(
                String.format(
                    Locale.US,
                    "%.7f %.7f %.7f %.7f %.7f %.7f %d\n",
                    pose.rx, pose.ry, pose.rz,
                    pose.vx, pose.vy, pose.vz,
                    pose.discontinuityCounter
                )
            )
            output.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Head tracker pose stream closed", e)
            stop()
        }
    }

    @Synchronized
    fun stop() {
        val running = process
        try {
            writer?.close()
        } catch (_: IOException) {
        }
        writer = null
        process = null
        macAddress = null
        running?.destroy()
        if (running != null) {
            try {
                if (!running.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    running.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                running.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
    }

    private companion object {
        const val TAG = "RootHeadTrackerBridge"
        const val STOP_TIMEOUT_MS = 250L
        val MAC_PATTERN = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    }
}
