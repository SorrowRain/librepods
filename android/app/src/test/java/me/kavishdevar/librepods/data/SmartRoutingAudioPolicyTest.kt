@file:Suppress("DEPRECATION")

package me.kavishdevar.librepods.data

import android.media.AudioAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmartRoutingAudioPolicyTest {
    @Test
    fun `default policy enables only media`() {
        assertEquals(
            setOf(SmartRoutingAudioCategory.MEDIA),
            SmartRoutingAudioPolicy.DEFAULT.enabledCategories
        )
        assertTrue(
            SmartRoutingAudioPolicy.DEFAULT.allowsPlatformUsage(AudioAttributes.USAGE_MEDIA)
        )
    }

    @Test
    fun `nine categories have stable unique mappings`() {
        val expectedMappings = mapOf(
            SmartRoutingAudioCategory.MEDIA to setOf(AudioAttributes.USAGE_MEDIA),
            SmartRoutingAudioCategory.GAME to setOf(AudioAttributes.USAGE_GAME),
            SmartRoutingAudioCategory.NAVIGATION to setOf(
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            ),
            SmartRoutingAudioCategory.ASSISTANT to setOf(AudioAttributes.USAGE_ASSISTANT),
            SmartRoutingAudioCategory.ACCESSIBILITY to setOf(
                AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
            ),
            SmartRoutingAudioCategory.ALARM to setOf(AudioAttributes.USAGE_ALARM),
            SmartRoutingAudioCategory.NOTIFICATION to setOf(
                AudioAttributes.USAGE_NOTIFICATION,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
                AudioAttributes.USAGE_NOTIFICATION_EVENT
            ),
            SmartRoutingAudioCategory.SYSTEM_SONIFICATION to setOf(
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            ),
            SmartRoutingAudioCategory.UNKNOWN to setOf(AudioAttributes.USAGE_UNKNOWN)
        )

        assertEquals(9, SmartRoutingAudioCategory.entries.size)
        assertEquals(expectedMappings, SmartRoutingAudioCategory.entries.associateWith {
            it.platformUsages
        })
        assertEquals(
            SmartRoutingAudioCategory.entries.size,
            SmartRoutingAudioCategory.entries.map { it.preferenceId }.toSet().size
        )

        val allMappedUsages = SmartRoutingAudioCategory.entries.flatMap { it.platformUsages }
        assertEquals(allMappedUsages.size, allMappedUsages.toSet().size)
        expectedMappings.forEach { (category, usages) ->
            usages.forEach { usage ->
                assertEquals(category, SmartRoutingAudioCategory.fromPlatformUsage(usage))
            }
        }
    }

    @Test
    fun `telephony usages and future values fail closed`() {
        val allCategoriesEnabled = SmartRoutingAudioPolicy(
            SmartRoutingAudioCategory.entries.toSet()
        )
        val blockedUsages = setOf(
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
            15,
            1_000,
            37_000,
            Int.MAX_VALUE
        )

        blockedUsages.forEach { usage ->
            assertNull(SmartRoutingAudioCategory.fromPlatformUsage(usage))
            assertFalse(allCategoriesEnabled.allowsPlatformUsage(usage))
        }
    }

    @Test
    fun `unknown category accepts only public usage unknown`() {
        val unknownOnly = SmartRoutingAudioPolicy(setOf(SmartRoutingAudioCategory.UNKNOWN))

        assertTrue(unknownOnly.allowsPlatformUsage(AudioAttributes.USAGE_UNKNOWN))
        assertFalse(unknownOnly.allowsPlatformUsage(-1))
        assertFalse(unknownOnly.allowsPlatformUsage(15))
        assertFalse(unknownOnly.allowsPlatformUsage(1_000))
    }

    @Test
    fun `each enabled category accepts all and only its mapped usages`() {
        SmartRoutingAudioCategory.entries.forEach { enabledCategory ->
            val policy = SmartRoutingAudioPolicy(setOf(enabledCategory))

            enabledCategory.platformUsages.forEach { usage ->
                assertTrue(
                    policy.allowsPlatformUsage(usage),
                    "${enabledCategory.name} should allow usage $usage"
                )
            }

            SmartRoutingAudioCategory.entries
                .filterNot { it == enabledCategory }
                .flatMap { it.platformUsages }
                .forEach { usage ->
                    assertFalse(
                        policy.allowsPlatformUsage(usage),
                        "${enabledCategory.name} should not allow usage $usage"
                    )
                }
        }
    }

    @Test
    fun `empty policy denies every mapped usage`() {
        val emptyPolicy = SmartRoutingAudioPolicy(emptySet())

        SmartRoutingAudioCategory.entries
            .flatMap { it.platformUsages }
            .forEach { usage ->
                assertFalse(emptyPolicy.allowsPlatformUsage(usage))
            }
        assertFalse(emptyPolicy.allowsPlatformUsage(37_000))
    }

    @Test
    fun `phone routing defaults protect notifications and sonification only`() {
        assertEquals(
            setOf(
                SmartRoutingAudioCategory.NOTIFICATION,
                SmartRoutingAudioCategory.SYSTEM_SONIFICATION
            ),
            PhoneSoundRoutingPolicy.DEFAULT.routedCategories
        )
        assertTrue(
            PhoneSoundRoutingPolicy.DEFAULT.routesPlatformUsage(
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            )
        )
        assertTrue(
            PhoneSoundRoutingPolicy.DEFAULT.routesPlatformUsage(
                AudioAttributes.USAGE_NOTIFICATION
            )
        )
        assertFalse(
            PhoneSoundRoutingPolicy.DEFAULT.routesPlatformUsage(AudioAttributes.USAGE_MEDIA)
        )
    }

    @Test
    fun `phone routing can never redirect media or game`() {
        val unsafePolicy = PhoneSoundRoutingPolicy(SmartRoutingAudioCategory.entries.toSet())

        assertFalse(unsafePolicy.routesPlatformUsage(AudioAttributes.USAGE_MEDIA))
        assertFalse(unsafePolicy.routesPlatformUsage(AudioAttributes.USAGE_GAME))
        assertFalse(
            SmartRoutingAudioCategory.MEDIA in PhoneSoundRoutingPolicy.supportedCategories
        )
        assertFalse(
            SmartRoutingAudioCategory.GAME in PhoneSoundRoutingPolicy.supportedCategories
        )
    }
}
