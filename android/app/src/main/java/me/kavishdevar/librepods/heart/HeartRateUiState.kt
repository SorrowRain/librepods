/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.heart

public enum class HeartRateUiStatus {
    OFF,
    WAITING_FOR_AIRPODS,
    WAITING_TO_BE_WORN,
    STARTING,
    CALIBRATING,
    LIVE,
    RECONNECTING,
    COULDNT_START,
}

public enum class HeartRateSampleFreshness {
    NO_SAMPLE,
    FRESH,
    STALE,
}

/** UI-safe HRM state. Connection leases, session keys, and acquisition tokens never leave here. */
public data class HeartRateUiState(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val status: HeartRateUiStatus = HeartRateUiStatus.OFF,
    val latestBpm: Int? = null,
    val sampleFreshness: HeartRateSampleFreshness = HeartRateSampleFreshness.NO_SAMPLE,
) {
    override fun toString(): String =
        "HeartRateUiState(supported=$supported, enabled=$enabled, status=$status, " +
            "latestBpm=redacted, sampleFreshness=$sampleFreshness)"
}

internal object HeartRateUiStateMapper {
    const val LIVE_SAMPLE_FRESHNESS_MILLIS = 10_000L

    fun map(
        eligibility: HeartRateEligibilityState,
        session: HeartRateSessionState,
        nowElapsedRealtimeMillis: Long,
    ): HeartRateUiState {
        val status = session.status.toUiStatus()
        val mayExposeSample = eligibility.supported &&
            eligibility.isCurrent(session.key) &&
            session.enabled &&
            session.status == HeartRateSessionStatus.LIVE
        val sample = session.latestMeasurement.takeIf { mayExposeSample }
        val sampleAgeMillis = sample?.let {
            nowElapsedRealtimeMillis - it.elapsedRealtimeMillis
        }
        val freshness = when {
            sample == null -> HeartRateSampleFreshness.NO_SAMPLE
            sampleAgeMillis != null &&
                sampleAgeMillis in 0L..LIVE_SAMPLE_FRESHNESS_MILLIS ->
                HeartRateSampleFreshness.FRESH
            else -> HeartRateSampleFreshness.STALE
        }

        return HeartRateUiState(
            supported = eligibility.featureVisible,
            enabled = session.enabled,
            status = status,
            latestBpm = sample?.bpm.takeIf {
                freshness == HeartRateSampleFreshness.FRESH
            },
            sampleFreshness = freshness,
        )
    }

    private fun HeartRateSessionStatus.toUiStatus(): HeartRateUiStatus = when (this) {
        HeartRateSessionStatus.OFF -> HeartRateUiStatus.OFF
        HeartRateSessionStatus.WAITING_FOR_AIRPODS ->
            HeartRateUiStatus.WAITING_FOR_AIRPODS
        HeartRateSessionStatus.WAITING_TO_BE_WORN ->
            HeartRateUiStatus.WAITING_TO_BE_WORN
        HeartRateSessionStatus.STARTING -> HeartRateUiStatus.STARTING
        HeartRateSessionStatus.CALIBRATING -> HeartRateUiStatus.CALIBRATING
        HeartRateSessionStatus.LIVE -> HeartRateUiStatus.LIVE
        HeartRateSessionStatus.RECONNECTING -> HeartRateUiStatus.RECONNECTING
        HeartRateSessionStatus.COULDNT_START -> HeartRateUiStatus.COULDNT_START
    }
}
