/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package me.kavishdevar.librepods.utils

import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RootAvrcpVolumeResult(
    val success: Boolean,
    val output: String
)

/** Uses the APK's root app_process entry point when the app is not privileged. */
class RootAvrcpVolumeController(context: Context) {
    private val apkPath = context.applicationInfo.sourceDir
    private val processLock = Any()
    private var prewarmedProcess: Process? = null
    private var prewarmedAddress: String? = null
    private var activeProcess: Process? = null

    fun prewarm(address: String): RootAvrcpVolumeResult = synchronized(processLock) {
        val existing = prewarmedProcess
        if (existing?.isAlive == true && prewarmedAddress == address) {
            return@synchronized RootAvrcpVolumeResult(
                true,
                "Root AVRCP bridge already prewarmed"
            )
        }
        existing?.destroyForcibly()
        return@synchronized try {
            prewarmedProcess = startPrewarmCommand(address)
            prewarmedAddress = address
            RootAvrcpVolumeResult(true, "Root AVRCP bridge prewarming")
        } catch (error: IOException) {
            prewarmedProcess = null
            prewarmedAddress = null
            RootAvrcpVolumeResult(false, error.message ?: "Unable to prewarm root command")
        }
    }

    fun resend(
        address: String,
        pulseVolume: Int,
        restoreVolume: Int,
        awaitPlayback: Boolean = false
    ): RootAvrcpVolumeResult {
        var process: Process? = null
        return try {
            process = synchronized(processLock) {
                val existing = prewarmedProcess
                val existingAddress = prewarmedAddress
                prewarmedProcess = null
                prewarmedAddress = null
                val usingPrewarmed = existing?.isAlive == true && existingAddress == address
                val selected = if (usingPrewarmed) {
                    existing
                } else {
                    existing?.destroyForcibly()
                    if (awaitPlayback) {
                        startPrewarmCommand(address)
                    } else {
                        startDirectCommand(address, pulseVolume, restoreVolume)
                    }
                }
                if (usingPrewarmed || awaitPlayback) {
                    selected.outputStream.bufferedWriter().use { writer ->
                        writer.write(
                            "go ${pulseVolume.coerceIn(0, 255)} " +
                                "${restoreVolume.coerceIn(0, 255)}\n"
                        )
                        writer.flush()
                    }
                }
                activeProcess = selected
                selected
            }
            val running = process
            if (!running.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                RootAvrcpVolumeResult(false, "Root AVRCP command timed out")
            } else {
                val output = running.inputStream.bufferedReader().use { it.readText() }.trim()
                RootAvrcpVolumeResult(running.exitValue() == 0, output)
            }
        } catch (error: IOException) {
            RootAvrcpVolumeResult(false, error.message ?: "Unable to start root command")
        } finally {
            synchronized(processLock) {
                if (activeProcess === process) activeProcess = null
            }
        }
    }

    fun cancel() {
        synchronized(processLock) {
            prewarmedProcess?.destroyForcibly()
            prewarmedProcess = null
            prewarmedAddress = null
            activeProcess?.destroyForcibly()
            activeProcess = null
        }
    }

    private fun startPrewarmCommand(address: String): Process {
        return startCommand("${shellQuote(address)} --wait")
    }

    private fun startDirectCommand(
        address: String,
        pulseVolume: Int,
        restoreVolume: Int
    ): Process {
        val arguments = "${shellQuote(address)} ${pulseVolume.coerceIn(0, 255)} " +
            restoreVolume.coerceIn(0, 255)
        return startCommand(arguments)
    }

    private fun startCommand(arguments: String): Process {
        val command = "CLASSPATH=${shellQuote(apkPath)} exec /system/bin/app_process " +
            "/system/bin $COMMAND_CLASS $arguments"
        return ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    private companion object {
        const val COMMAND_CLASS =
            "me.kavishdevar.librepods.utils.AvrcpVolumeRootCommand"
        const val COMMAND_TIMEOUT_SECONDS = 8L
    }
}
