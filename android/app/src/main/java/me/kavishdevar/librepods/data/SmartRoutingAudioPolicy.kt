package me.kavishdevar.librepods.data

import android.content.SharedPreferences
import android.media.AudioAttributes
import androidx.core.content.edit

enum class SmartRoutingAudioRisk {
    STANDARD,
    CAUTION,
    HIGH
}

enum class SmartRoutingAudioCategory(
    val preferenceId: String,
    val platformUsages: Set<Int>,
    val enabledByDefault: Boolean = false,
    val canRouteToPhoneSpeaker: Boolean = true,
    val risk: SmartRoutingAudioRisk
) {
    MEDIA(
        preferenceId = "media",
        platformUsages = setOf(AudioAttributes.USAGE_MEDIA),
        enabledByDefault = true,
        canRouteToPhoneSpeaker = false,
        risk = SmartRoutingAudioRisk.STANDARD
    ),
    GAME(
        preferenceId = "game",
        platformUsages = setOf(AudioAttributes.USAGE_GAME),
        canRouteToPhoneSpeaker = false,
        risk = SmartRoutingAudioRisk.CAUTION
    ),
    NAVIGATION(
        preferenceId = "navigation",
        platformUsages = setOf(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE),
        risk = SmartRoutingAudioRisk.CAUTION
    ),
    ASSISTANT(
        preferenceId = "assistant",
        platformUsages = setOf(AudioAttributes.USAGE_ASSISTANT),
        risk = SmartRoutingAudioRisk.CAUTION
    ),
    ACCESSIBILITY(
        preferenceId = "accessibility",
        platformUsages = setOf(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY),
        risk = SmartRoutingAudioRisk.HIGH
    ),
    ALARM(
        preferenceId = "alarm",
        platformUsages = setOf(AudioAttributes.USAGE_ALARM),
        risk = SmartRoutingAudioRisk.HIGH
    ),
    NOTIFICATION(
        preferenceId = "notification",
        platformUsages = setOf(
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
            AudioAttributes.USAGE_NOTIFICATION_EVENT
        ),
        risk = SmartRoutingAudioRisk.HIGH
    ),
    SYSTEM_SONIFICATION(
        preferenceId = "system_sonification",
        platformUsages = setOf(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION),
        risk = SmartRoutingAudioRisk.HIGH
    ),
    UNKNOWN(
        preferenceId = "unknown",
        platformUsages = setOf(AudioAttributes.USAGE_UNKNOWN),
        risk = SmartRoutingAudioRisk.HIGH
    );

    companion object {
        private val byPreferenceId = entries.associateBy(SmartRoutingAudioCategory::preferenceId)
        private val byPlatformUsage = entries
            .flatMap { category -> category.platformUsages.map { usage -> usage to category } }
            .toMap()

        val defaultEnabled: Set<SmartRoutingAudioCategory> =
            entries.filterTo(linkedSetOf()) { it.enabledByDefault }

        fun fromPreferenceId(id: String): SmartRoutingAudioCategory? = byPreferenceId[id]

        fun fromPlatformUsage(usage: Int): SmartRoutingAudioCategory? = byPlatformUsage[usage]
    }
}

data class SmartRoutingAudioPolicy(
    val enabledCategories: Set<SmartRoutingAudioCategory>
) {
    fun isEnabled(category: SmartRoutingAudioCategory): Boolean = category in enabledCategories

    fun allowsPlatformUsage(usage: Int): Boolean {
        // Telephony-only and unrecognized platform usages are deliberately absent.
        val category = SmartRoutingAudioCategory.fromPlatformUsage(usage) ?: return false
        return category in enabledCategories
    }

    companion object {
        val DEFAULT = SmartRoutingAudioPolicy(SmartRoutingAudioCategory.defaultEnabled)
    }
}

/** Audio usages that should remain on the phone instead of opening an A2DP stream. */
data class PhoneSoundRoutingPolicy(
    val routedCategories: Set<SmartRoutingAudioCategory>
) {
    val routedUsages: Set<Int>
        get() = routedCategories.flatMapTo(linkedSetOf()) { it.platformUsages }

    fun routesPlatformUsage(usage: Int): Boolean {
        val category = SmartRoutingAudioCategory.fromPlatformUsage(usage) ?: return false
        return category.canRouteToPhoneSpeaker && category in routedCategories
    }

    companion object {
        val supportedCategories: Set<SmartRoutingAudioCategory> =
            SmartRoutingAudioCategory.entries
                .filterTo(linkedSetOf()) { it.canRouteToPhoneSpeaker }

        val DEFAULT = PhoneSoundRoutingPolicy(
            setOf(
                SmartRoutingAudioCategory.NOTIFICATION,
                SmartRoutingAudioCategory.SYSTEM_SONIFICATION
            )
        )
    }
}

object SmartRoutingAudioPolicyPreferences {
    const val MASTER_ENABLED_KEY = "smart_routing_auto_takeover"
    const val ENABLED_CATEGORIES_KEY = "smart_routing_takeover_audio_categories_v1"

    private const val LEGACY_MEDIA_ENABLED_KEY = "takeover_when_media_start"

    fun read(preferences: SharedPreferences): SmartRoutingAudioPolicy {
        migrateIfNeeded(preferences)
        return policyFromIds(readStoredIds(preferences))
    }

    fun setCategoryEnabled(
        preferences: SharedPreferences,
        category: SmartRoutingAudioCategory,
        enabled: Boolean
    ): SmartRoutingAudioPolicy {
        migrateIfNeeded(preferences)
        val ids = readStoredIds(preferences).toMutableSet()
        if (enabled) {
            ids += category.preferenceId
        } else {
            ids -= category.preferenceId
        }
        preferences.edit { putStringSet(ENABLED_CATEGORIES_KEY, ids.toSet()) }
        return policyFromIds(ids)
    }

    private fun migrateIfNeeded(preferences: SharedPreferences) {
        if (preferences.contains(ENABLED_CATEGORIES_KEY)) return

        val enabledCategories = when {
            preferences.contains(LEGACY_MEDIA_ENABLED_KEY) &&
                preferences.getBoolean(LEGACY_MEDIA_ENABLED_KEY, false) -> setOf(
                    SmartRoutingAudioCategory.MEDIA.preferenceId
                )
            preferences.contains(LEGACY_MEDIA_ENABLED_KEY) -> emptySet()
            else -> SmartRoutingAudioCategory.defaultEnabled.mapTo(linkedSetOf()) {
                it.preferenceId
            }
        }

        preferences.edit {
            putStringSet(ENABLED_CATEGORIES_KEY, enabledCategories)
        }
    }

    private fun readStoredIds(preferences: SharedPreferences): Set<String> =
        preferences.getStringSet(ENABLED_CATEGORIES_KEY, emptySet()).orEmpty().toSet()

    private fun policyFromIds(ids: Set<String>): SmartRoutingAudioPolicy =
        SmartRoutingAudioPolicy(
            enabledCategories = ids.mapNotNullTo(linkedSetOf()) {
                SmartRoutingAudioCategory.fromPreferenceId(it)
            }
        )
}

object PhoneSoundRoutingPreferences {
    const val MASTER_ENABLED_KEY = "phone_sound_routing_enabled"
    const val ROUTED_CATEGORIES_KEY = "phone_sound_routing_categories_v1"

    fun isEnabled(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(MASTER_ENABLED_KEY, true)

    fun read(preferences: SharedPreferences): PhoneSoundRoutingPolicy {
        val ids = if (preferences.contains(ROUTED_CATEGORIES_KEY)) {
            preferences.getStringSet(ROUTED_CATEGORIES_KEY, emptySet()).orEmpty()
        } else {
            PhoneSoundRoutingPolicy.DEFAULT.routedCategories.mapTo(linkedSetOf()) {
                it.preferenceId
            }
        }
        return policyFromIds(ids)
    }

    fun setCategoryEnabled(
        preferences: SharedPreferences,
        category: SmartRoutingAudioCategory,
        enabled: Boolean
    ): PhoneSoundRoutingPolicy {
        if (!category.canRouteToPhoneSpeaker) return read(preferences)
        val ids = read(preferences).routedCategories
            .mapTo(linkedSetOf()) { it.preferenceId }
        if (enabled) {
            ids += category.preferenceId
        } else {
            ids -= category.preferenceId
        }
        preferences.edit { putStringSet(ROUTED_CATEGORIES_KEY, ids.toSet()) }
        return policyFromIds(ids)
    }

    private fun policyFromIds(ids: Set<String>): PhoneSoundRoutingPolicy =
        PhoneSoundRoutingPolicy(
            ids.mapNotNullTo(linkedSetOf()) { id ->
                SmartRoutingAudioCategory.fromPreferenceId(id)
                    ?.takeIf(SmartRoutingAudioCategory::canRouteToPhoneSpeaker)
            }
        )
}
