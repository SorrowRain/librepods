/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.diagnostics

import android.util.JsonWriter
import java.io.StringWriter

internal object RoutingEventEncoder {
    private const val MAX_TEXT_LENGTH = 1_024
    private const val MAX_COLLECTION_SIZE = 32
    private val macAddressPattern = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
    private val longHexPattern = Regex("(?i)\\b[0-9a-f]{24,}\\b")

    fun encode(event: RoutingEvent): String {
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginObject()
            writer.name("schema").value(event.schemaVersion.toLong())
            writer.name("session_id").value(sanitize(event.sessionId))
            writer.name("seq").value(event.sequence)
            writer.name("wall_time_epoch_ms").value(event.wallTimeEpochMs)
            writer.name("elapsed_realtime_ns").value(event.elapsedRealtimeNanos)
            writer.name("severity").value(event.severity.name.lowercase())
            writer.name("event").value(event.detail.eventName)
            writeCorrelation(writer, event.correlation)
            writer.name("data")
            writeDetail(writer, event.detail)
            writer.endObject()
        }
        return output.toString()
    }

    private fun writeCorrelation(writer: JsonWriter, correlation: RoutingCorrelation) {
        writer.name("correlation").beginObject()
        correlation.playbackCycleId?.let {
            writer.name("playback_cycle_id").value(sanitize(it))
        }
        correlation.takeoverAttemptId?.let {
            writer.name("takeover_attempt_id").value(sanitize(it))
        }
        correlation.aacpSessionId?.let {
            writer.name("aacp_session_id").value(sanitize(it))
        }
        correlation.causeSequence?.let {
            writer.name("cause_seq").value(it)
        }
        writer.endObject()
    }

    @Suppress("LongMethod")
    private fun writeDetail(writer: JsonWriter, detail: RoutingEventDetail) {
        writer.beginObject()
        when (detail) {
            is RoutingEventDetail.PlaybackSnapshot -> {
                writer.name("observation_source").value(sanitize(detail.observationSource))
                writer.name("configuration_count").value(detail.configurationCount.toLong())
                writer.name("eligible_configuration_count")
                    .value(detail.eligibleConfigurationCount.toLong())
                writer.name("eligible_playback_active").value(detail.eligiblePlaybackActive)
                writer.name("eligible_playback_routed_to_target")
                    .value(detail.eligiblePlaybackRoutedToTarget)
                writer.name("configurations").beginArray()
                detail.configurations.take(MAX_COLLECTION_SIZE).forEach { configuration ->
                    writer.beginObject()
                    writer.name("usage").value(configuration.usage.toLong())
                    writer.name("usage_name").value(sanitize(configuration.usageName))
                    writer.name("content_type").value(configuration.contentType.toLong())
                    writer.name("content_type_name")
                        .value(sanitize(configuration.contentTypeName))
                    configuration.outputDeviceType?.let {
                        writer.name("output_device_type").value(it.toLong())
                    }
                    configuration.outputDeviceTypeName?.let {
                        writer.name("output_device_type_name").value(sanitize(it))
                    }
                    configuration.targetDeviceMatched?.let {
                        writer.name("target_device_matched").value(it)
                    }
                    configuration.playerState?.let {
                        writer.name("player_state").value(it.toLong())
                    }
                    configuration.playerStateName?.let {
                        writer.name("player_state_name").value(sanitize(it))
                    }
                    writer.name("policy_enabled").value(configuration.policyEnabled)
                    writer.name("eligible").value(configuration.eligible)
                    writer.name("reason").value(sanitize(configuration.reason))
                    writer.endObject()
                }
                writer.endArray()
                if (detail.configurations.size > MAX_COLLECTION_SIZE) {
                    writer.name("truncated_configuration_count")
                        .value((detail.configurations.size - MAX_COLLECTION_SIZE).toLong())
                }
            }

            is RoutingEventDetail.Debounce -> {
                writer.name("debounce_event").value(detail.event.name.lowercase())
                writer.name("delay_ms").value(detail.delayMs)
                writer.name("generation").value(detail.generation)
                writer.name("reason").value(sanitize(detail.reason))
            }

            is RoutingEventDetail.Decision -> {
                writer.name("action").value(detail.action.name.lowercase())
                writer.name("allowed").value(detail.allowed)
                writer.name("reason").value(sanitize(detail.reason))
                writeBooleanMap(writer, "gates", detail.gates)
            }

            is RoutingEventDetail.StateTransition -> {
                writer.name("state_machine").value(sanitize(detail.stateMachine))
                writer.name("previous_state").value(sanitize(detail.previousState))
                writer.name("new_state").value(sanitize(detail.newState))
                writer.name("trigger").value(sanitize(detail.trigger))
                writer.name("reason").value(sanitize(detail.reason))
            }

            is RoutingEventDetail.A2dpStateChanged -> {
                writer.name("signal").value(sanitize(detail.signal))
                detail.previousState?.let {
                    writer.name("previous_state").value(sanitize(it))
                }
                writer.name("new_state").value(sanitize(detail.newState))
                writer.name("target_device_matched").value(detail.targetDeviceMatched)
                writer.name("observation_source").value(sanitize(detail.observationSource))
                writer.name("takeover_triggered").value(false)
            }

            is RoutingEventDetail.AacpSend -> {
                writer.name("operation").value(detail.operation.name.lowercase())
                writer.name("outcome").value(detail.outcome.name.lowercase())
                writer.name("socket_connected").value(detail.socketConnected)
                detail.requestedValue?.let {
                    writer.name("requested_value").value(it.toLong())
                }
                writer.name("reason").value(sanitize(detail.reason))
            }

            is RoutingEventDetail.AacpStateChanged -> {
                writer.name("kind").value(detail.kind.name.lowercase())
                detail.previousValue?.let {
                    writer.name("previous_value").value(sanitize(it))
                }
                writer.name("new_value").value(sanitize(detail.newValue))
                detail.sourceAlias?.let {
                    writer.name("source_alias").value(sanitize(it))
                }
                detail.sourceType?.let {
                    writer.name("source_type").value(sanitize(it))
                }
                detail.correlationBasis?.let {
                    writer.name("correlation_basis").value(sanitize(it))
                }
            }

            is RoutingEventDetail.PhoneSoundPolicy -> {
                writer.name("enabled").value(detail.enabled)
                writer.name("target_available").value(detail.targetAvailable)
                writer.name("requested_usages").beginArray()
                detail.requestedUsages.sorted().take(MAX_COLLECTION_SIZE).forEach {
                    writer.value(it.toLong())
                }
                writer.endArray()
                writer.name("status").value(sanitize(detail.status))
                writer.name("permission_granted").value(detail.permissionGranted)
                writer.name("reason").value(sanitize(detail.reason))
            }

            is RoutingEventDetail.SettingsSnapshot -> {
                writer.name("revision").value(detail.revision)
                writer.name("automatic_takeover_enabled")
                    .value(detail.automaticTakeoverEnabled)
                writer.name("media_takeover_enabled").value(detail.mediaTakeoverEnabled)
                writer.name("enabled_usages").beginArray()
                detail.enabledUsages.sorted().take(MAX_COLLECTION_SIZE).forEach {
                    writer.value(it.toLong())
                }
                writer.endArray()
                writer.name("debounce_ms").value(detail.debounceMs)
                writeBooleanMap(writer, "additional_flags", detail.additionalFlags)
            }

            is RoutingEventDetail.SettingsChanged -> {
                writer.name("revision").value(detail.revision)
                writer.name("setting").value(sanitize(detail.setting))
                writer.name("previous_value").value(sanitize(detail.previousValue))
                writer.name("new_value").value(sanitize(detail.newValue))
            }

            is RoutingEventDetail.ActionResult -> {
                writer.name("action").value(detail.action.name.lowercase())
                writer.name("outcome").value(sanitize(detail.outcome))
                writer.name("reason").value(sanitize(detail.reason))
            }

            is RoutingEventDetail.Error -> {
                writer.name("component").value(sanitize(detail.component))
                writer.name("code").value(sanitize(detail.code))
                detail.exceptionType?.let {
                    writer.name("exception_type").value(sanitize(it))
                }
                detail.message?.let {
                    writer.name("message").value(sanitize(it))
                }
                if (detail.stackTrace.isNotEmpty()) {
                    writer.name("stack_trace").beginArray()
                    detail.stackTrace.take(MAX_COLLECTION_SIZE).forEach {
                        writer.value(sanitize(it))
                    }
                    writer.endArray()
                }
            }

            is RoutingEventDetail.Marker -> {
                writer.name("label").value(sanitize(detail.label))
            }

            is RoutingEventDetail.SessionStarted -> {
                writer.name("app_version").value(sanitize(detail.appVersion))
                writer.name("version_code").value(detail.versionCode.toLong())
                writer.name("build_type").value(sanitize(detail.buildType))
                writer.name("flavor").value(sanitize(detail.flavor))
                writer.name("manufacturer").value(sanitize(detail.manufacturer))
                writer.name("model").value(sanitize(detail.model))
                writer.name("android_release").value(sanitize(detail.androidRelease))
                writer.name("api_level").value(detail.apiLevel.toLong())
                writer.name("raw_packets_included").value(false)
                writer.name("logcat_included").value(false)
            }

            is RoutingEventDetail.SessionStopped -> {
                writer.name("reason").value(detail.reason.name.lowercase())
                writer.name("dropped_event_count").value(detail.droppedEventCount)
            }

            is RoutingEventDetail.DroppedEvents -> {
                writer.name("count").value(detail.count)
            }
        }
        writer.endObject()
    }

    private fun writeBooleanMap(
        writer: JsonWriter,
        name: String,
        values: Map<String, Boolean>,
    ) {
        writer.name(name).beginObject()
        values.entries.sortedBy { it.key }.take(MAX_COLLECTION_SIZE).forEach { (key, value) ->
            writer.name(sanitizeKey(key)).value(value)
        }
        writer.endObject()
    }

    private fun sanitizeKey(value: String): String {
        val sanitized = sanitize(value).replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return sanitized.ifBlank { "unnamed" }
    }

    private fun sanitize(value: String): String {
        return value
            .replace(macAddressPattern, "<redacted-address>")
            .replace(longHexPattern, "<redacted-hex>")
            .take(MAX_TEXT_LENGTH)
    }
}
