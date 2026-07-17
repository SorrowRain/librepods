/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package me.kavishdevar.librepods.utils

import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SpatialAudioRootState(
    val helperAvailable: Boolean,
    val spatializerAvailable: Boolean,
    val spatializerEnabled: Boolean = false,
    val desiredMode: Int = -1,
    val actualMode: Int = -1,
    val error: String? = null
) {
    val supported: Boolean
        get() = spatializerAvailable && error == null
}

/** Queries and controls the hidden platform Spatializer from a short-lived root process. */
class RootSpatialAudioController(context: Context) {
    private val apkPath = context.applicationInfo.sourceDir

    fun query(): SpatialAudioRootState {
        val helperAvailable = runRoot(HELPER_PROBE).exitCode == 0
        val spatializerResult = runSpatializer(null)
        return parseState(helperAvailable, spatializerResult)
    }

    fun setMode(mode: SpatialAudioMode): SpatialAudioRootState {
        val helperAvailable = runRoot(HELPER_PROBE).exitCode == 0
        if (mode == SpatialAudioMode.HEAD_TRACKED && !helperAvailable) {
            return SpatialAudioRootState(
                helperAvailable = false,
                spatializerAvailable = false,
                error = "Root head-tracker helper is unavailable"
            )
        }
        val spatializerEnabled = mode != SpatialAudioMode.OFF
        val headTrackingMode = if (mode == SpatialAudioMode.HEAD_TRACKED) 1 else -1
        return parseState(
            helperAvailable,
            runSpatializer(spatializerEnabled, headTrackingMode)
        )
    }

    private fun runSpatializer(
        spatializerEnabled: Boolean? = null,
        headTrackingMode: Int = -1
    ): CommandResult {
        val modeArguments = spatializerEnabled?.let {
            " ${if (it) 1 else 0} $headTrackingMode"
        }.orEmpty()
        val command = "CLASSPATH=${shellQuote(apkPath)} exec /system/bin/app_process " +
            "/system/bin $COMMAND_CLASS$modeArguments"
        return runRoot(command)
    }

    private fun parseState(
        helperAvailable: Boolean,
        command: CommandResult
    ): SpatialAudioRootState {
        val values = command.output.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to
                    line.substring(separator + 1)
            }
            .toMap()
        val desiredMode = values["desired"]?.toIntOrNull()
        val actualMode = values["actual"]?.toIntOrNull()
        val spatializerEnabled = values["enabled"]?.toBooleanStrictOrNull()
        val error = values["error"] ?: command.error
        return SpatialAudioRootState(
            helperAvailable = helperAvailable,
            // Head-tracker availability is transient and becomes true only
            // while the dynamic UHID sensor exists. Successful mode queries
            // prove that the platform exposes the Spatializer controls.
            spatializerAvailable = command.exitCode == 0 &&
                spatializerEnabled != null && desiredMode != null && actualMode != null,
            spatializerEnabled = spatializerEnabled ?: false,
            desiredMode = desiredMode ?: -1,
            actualMode = actualMode ?: -1,
            error = error
        )
    }

    private fun runRoot(command: String): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return CommandResult(-1, "", "Root command timed out")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.exitValue()
            CommandResult(
                exitCode = exitCode,
                output = output,
                error = if (exitCode == 0) null else output.ifBlank { "Root command failed" }
            )
        } catch (error: IOException) {
            CommandResult(-1, "", error.message ?: "Unable to start root command")
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
        val error: String? = null
    )

    private companion object {
        const val COMMAND_CLASS =
            "me.kavishdevar.librepods.utils.SpatializerRootCommand"
        const val COMMAND_TIMEOUT_SECONDS = 8L
        const val HELPER_PROBE =
            "test -r /dev/uhid && test -w /dev/uhid && " +
                "(test -x /system/bin/librepods-headtracker || " +
                "test -x /data/adb/modules/librepods/system/bin/librepods-headtracker || " +
                "test -x /data/local/tmp/headtracker_uhid)"
    }
}
