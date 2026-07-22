package me.kavishdevar.librepods.utils

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.InvocationTargetException

data class PhoneSoundRoutingStatus(
    val initialized: Boolean = false,
    val active: Boolean = false,
    val state: String = "not_initialized",
    val lastError: String? = null,
)

/** Routes non-call playback to the built-in speaker while another device owns AirPods audio. */
object PhoneSoundRoutingController {
    private const val TAG = "RemoteDeviceAudio"
    private const val MODIFY_AUDIO_ROUTING = "android.permission.MODIFY_AUDIO_ROUTING"
    private const val RULE_MATCH_ATTRIBUTE_USAGE = 1
    private const val ROUTE_FLAG_RENDER = 1
    private const val REGISTER_SUCCESS = 0
    private const val POLICY_STATUS_REGISTERED = 2

    private val guardedUsages = setOf(
        AudioAttributes.USAGE_UNKNOWN,
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_ALARM,
        AudioAttributes.USAGE_NOTIFICATION,
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
        AudioAttributes.USAGE_NOTIFICATION_EVENT,
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_ASSISTANT,
    )

    private lateinit var appContext: Context
    private lateinit var audioManager: AudioManager
    private var registeredPolicy: Any? = null
    private val retryHandler = Handler(Looper.getMainLooper())
    private var unregisterRetryCount = 0
    private var desiredEnabled = false
    private val unregisterRetryRunnable = Runnable {
        synchronized(this) {
            if (!desiredEnabled && registeredPolicy != null) {
                unregisterPolicy("scheduled retry")
            }
        }
    }

    @Volatile
    var status: PhoneSoundRoutingStatus = PhoneSoundRoutingStatus()
        private set

    @Synchronized
    fun initialize(context: Context, manager: AudioManager) {
        if (status.initialized) shutdown()
        desiredEnabled = false
        appContext = context.applicationContext
        audioManager = manager
        if (registeredPolicy == null) {
            status = PhoneSoundRoutingStatus(initialized = true, state = "idle")
        }
    }

    @Synchronized
    fun setGuardEnabled(enabled: Boolean, reason: String) {
        if (!status.initialized) {
            Log.e(TAG, "Speaker barrier is not initialized: $reason")
            return
        }
        desiredEnabled = enabled
        if (!enabled) {
            unregisterPolicy(reason)
            return
        }
        retryHandler.removeCallbacks(unregisterRetryRunnable)
        unregisterRetryCount = 0
        if (registeredPolicy != null) return

        if (appContext.checkSelfPermission(MODIFY_AUDIO_ROUTING) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val message = "MODIFY_AUDIO_ROUTING is missing; install the matching root module"
            status = PhoneSoundRoutingStatus(true, false, "permission_missing", message)
            Log.e(TAG, "$message ($reason)")
            return
        }

        val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speaker == null) {
            val message = "Built-in speaker output is unavailable"
            status = PhoneSoundRoutingStatus(true, false, "speaker_unavailable", message)
            Log.e(TAG, "$message ($reason)")
            return
        }

        runCatching { buildAndRegisterPolicy(speaker) }
            .onSuccess { policy ->
                registeredPolicy = policy
                status = PhoneSoundRoutingStatus(true, true, "registered")
                Log.i(TAG, "Speaker barrier registered: $reason")
            }
            .onFailure { rawError ->
                val error = unwrap(rawError)
                val message = "${error::class.java.simpleName}: ${error.message.orEmpty()}"
                val cleanupFailed = registeredPolicy != null
                status = PhoneSoundRoutingStatus(
                    initialized = true,
                    active = cleanupFailed,
                    state = if (cleanupFailed) {
                        "registration_cleanup_failed"
                    } else {
                        "registration_failed"
                    },
                    lastError = message,
                )
                Log.e(TAG, "Speaker barrier registration failed: $reason", error)
            }
    }

    @Synchronized
    fun shutdown() {
        if (!status.initialized) return
        desiredEnabled = false
        if (unregisterPolicy("shutdown")) {
            status = PhoneSoundRoutingStatus()
        }
    }

    private fun buildAndRegisterPolicy(speaker: AudioDeviceInfo): Any {
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        val addRule = ruleBuilderClass.getMethod(
            "addRule",
            AudioAttributes::class.java,
            Int::class.javaPrimitiveType,
        )
        guardedUsages.sorted().forEach { usage ->
            addRule.invoke(
                ruleBuilder,
                AudioAttributes.Builder().setUsage(usage).build(),
                RULE_MATCH_ATTRIBUTE_USAGE,
            )
        }
        val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(mixBuilder, ROUTE_FLAG_RENDER)
        mixBuilderClass.getMethod("setDevice", AudioDeviceInfo::class.java)
            .invoke(mixBuilder, speaker)
        val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyBuilder = policyBuilderClass.getConstructor(Context::class.java)
            .newInstance(appContext)
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        val policy = checkNotNull(policyBuilderClass.getMethod("build").invoke(policyBuilder))
        val result = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass)
            .invoke(audioManager, policy) as Int
        check(result == REGISTER_SUCCESS) {
            "registerAudioPolicy returned result=$result"
        }

        // Track the policy before any reflective validation that could throw so cleanup can retry.
        registeredPolicy = policy
        unregisterRetryCount = 0
        val policyStatus = runCatching {
            policyClass.getMethod("getStatus").invoke(policy) as Int
        }.getOrElse { error ->
            unregisterPolicy("registration status check failed")
            throw error
        }
        if (policyStatus != POLICY_STATUS_REGISTERED) {
            unregisterPolicy("registration returned status=$policyStatus")
            error("registerAudioPolicy returned status=$policyStatus")
        }
        return policy
    }

    private fun unregisterPolicy(reason: String): Boolean {
        val policy = registeredPolicy
        if (policy == null) {
            status = PhoneSoundRoutingStatus(initialized = true, state = "idle")
            return true
        }

        val result = runCatching {
            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass)
                .invoke(audioManager, policy)
        }
        result.exceptionOrNull()?.let { rawError ->
            val error = unwrap(rawError)
            val message = "${error::class.java.simpleName}: ${error.message.orEmpty()}"
            status = PhoneSoundRoutingStatus(
                initialized = true,
                active = true,
                state = "unregistration_failed",
                lastError = message,
            )
            Log.e(TAG, "Speaker barrier unregister failed: $reason", error)
            scheduleUnregisterRetry(policy, reason)
            return false
        }

        registeredPolicy = null
        unregisterRetryCount = 0
        retryHandler.removeCallbacks(unregisterRetryRunnable)
        status = PhoneSoundRoutingStatus(initialized = true, state = "idle")
        Log.i(TAG, "Speaker barrier unregistered: $reason")
        return true
    }

    private fun scheduleUnregisterRetry(policy: Any, reason: String) {
        if (desiredEnabled || registeredPolicy !== policy) return
        unregisterRetryCount++
        val retryDelayMs = when (unregisterRetryCount) {
            1 -> 250L
            2 -> 1_000L
            else -> 5_000L
        }
        Log.w(TAG, "Retrying speaker barrier release in ${retryDelayMs}ms: $reason")
        retryHandler.removeCallbacks(unregisterRetryRunnable)
        retryHandler.postDelayed(unregisterRetryRunnable, retryDelayMs)
    }

    private fun unwrap(error: Throwable): Throwable =
        (error as? InvocationTargetException)?.targetException ?: error

}
