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

import android.content.SharedPreferences

enum class SpatialAudioMode(val preferenceValue: String) {
    OFF("off"),
    FIXED("fixed"),
    HEAD_TRACKED("head_tracked");

    companion object {
        const val PREFERENCE_KEY = "spatial_audio_mode"
        private const val LEGACY_ENABLED_KEY = "spatial_audio_enabled"

        fun fromPreferences(preferences: SharedPreferences): SpatialAudioMode {
            val stored = preferences.getString(PREFERENCE_KEY, null)
            return entries.firstOrNull { it.preferenceValue == stored }
                ?: if (preferences.getBoolean(LEGACY_ENABLED_KEY, false)) {
                    HEAD_TRACKED
                } else {
                    OFF
                }
        }
    }
}
