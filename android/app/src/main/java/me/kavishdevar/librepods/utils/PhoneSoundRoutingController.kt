package me.kavishdevar.librepods.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kavishdevar.librepods.data.PhoneSoundRoutingPreferences
import me.kavishdevar.librepods.diagnostics.RoutingEventDetail
import me.kavishdevar.librepods.diagnostics.RoutingSeverity
import me.kavishdevar.librepods.diagnostics.RoutingTrace
import java.lang.reflect.InvocationTargetException

data class PhoneSoundRoutingStatus(
    val initialized: Boolean = false,
    val active: Boolean = false,
    val targetAvailable: Boolean = false,
    val requestedUsages: Set<Int> = emptySet(),
    val state: String = "not_initialized",
    val lastError: String? = null,
)

/**
 * Owns a process-scoped dynamic AudioPolicy that keeps selected short sounds on the phone.
 * The policy is registered only while the AirPods AACP control channel is available.
 */
object PhoneSoundRoutingController {
    private const val TAG = "PhoneSoundRouting"
    private const val MODIFY_AUDIO_ROUTING_PERMISSION =
        "android.permission.MODIFY_AUDIO_ROUTING"
    private const val REGISTER_SUCCESS = 0
    private const val POLICY_STATUS_REGISTERED = 2
    private const val RULE_MATCH_ATTRIBUTE_USAGE = 1
    private const val ROUTE_FLAG_RENDER = 1

    private lateinit var appContext: Context
    private lateinit var audioManager: AudioManager
    private lateinit var preferences: SharedPreferences
    private lateinit var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener
    private var initialized = false
    private var targetAvailable = false
    private var registeredPolicy: Any? = null
    private var registeredUsages: Set<Int> = emptySet()
    private val _status = MutableStateFlow(PhoneSoundRoutingStatus())

    val status: StateFlow<PhoneSoundRoutingStatus> = _status.asStateFlow()

    @Synchronized
    fun initialize(
        context: Context,
        manager: AudioManager,
        sharedPreferences: SharedPreferences,
    ) {
        if (initialized) shutdown()
        appContext = context.applicationContext
        audioManager = manager
        preferences = sharedPreferences
        preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PhoneSoundRoutingPreferences.MASTER_ENABLED_KEY ||
                key == PhoneSoundRoutingPreferences.ROUTED_CATEGORIES_KEY
            ) {
                applyPolicy("settings_changed")
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        initialized = true
        _status.value = PhoneSoundRoutingStatus(initialized = true)
        applyPolicy("initialized")
    }

    @Synchronized
    fun setTargetAvailable(available: Boolean, reason: String) {
        if (!initialized || targetAvailable == available) return
        targetAvailable = available
        applyPolicy(reason)
    }

    @Synchronized
    fun recordDiagnosticBaseline() {
        if (!initialized) return
        recordStatus("diagnostic_baseline", _status.value.state, _status.value.lastError)
    }

    @Synchronized
    fun shutdown() {
        if (!initialized) return
        unregisterCurrentPolicy("controller_shutdown")
        runCatching {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
        targetAvailable = false
        initialized = false
        _status.value = PhoneSoundRoutingStatus()
    }

    @Synchronized
    private fun applyPolicy(reason: String) {
        if (!initialized) return
        val enabled = PhoneSoundRoutingPreferences.isEnabled(preferences)
        val desiredUsages = if (enabled && targetAvailable) {
            PhoneSoundRoutingPreferences.read(preferences).routedUsages
        } else {
            emptySet()
        }

        if (desiredUsages.isEmpty()) {
            unregisterCurrentPolicy(reason)
            val state = when {
                !enabled -> "disabled"
                !targetAvailable -> "target_unavailable"
                else -> "no_usages_selected"
            }
            updateStatus(false, desiredUsages, state, null)
            recordStatus(reason, state, null)
            return
        }

        if (registeredPolicy != null && registeredUsages == desiredUsages) {
            updateStatus(true, desiredUsages, "registered", null)
            return
        }

        unregisterCurrentPolicy("replace_policy")
        val permissionGranted = appContext.checkSelfPermission(
            MODIFY_AUDIO_ROUTING_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) {
            val message = "MODIFY_AUDIO_ROUTING is not granted; install the matching root module"
            updateStatus(false, desiredUsages, "permission_missing", message)
            recordStatus(reason, "permission_missing", message, RoutingSeverity.WARNING)
            return
        }

        val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it.isSink
            }
        if (speaker == null) {
            val message = "Built-in speaker output was not found"
            updateStatus(false, desiredUsages, "speaker_unavailable", message)
            recordStatus(reason, "speaker_unavailable", message, RoutingSeverity.WARNING)
            return
        }

        runCatching { buildAndRegisterPolicy(desiredUsages, speaker) }
            .onSuccess { policy ->
                registeredPolicy = policy
                registeredUsages = desiredUsages.toSet()
                updateStatus(true, registeredUsages, "registered", null)
                recordStatus(reason, "registered", null)
                Log.i(TAG, "Phone-speaker policy registered for usages=$registeredUsages")
            }
            .onFailure { rawError ->
                val error = unwrapInvocationError(rawError)
                val failureState = classifyFailure(error)
                val message = "${error::class.java.simpleName}: ${error.message.orEmpty()}"
                updateStatus(false, desiredUsages, failureState, message)
                recordStatus(reason, failureState, message, RoutingSeverity.ERROR)
                RoutingTrace.recordError("phone_sound_policy", failureState, error)
                Log.e(TAG, "Could not register phone-speaker policy", error)
            }
    }

    private fun buildAndRegisterPolicy(usages: Set<Int>, speaker: AudioDeviceInfo): Any {
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        val addRule = ruleBuilderClass.getMethod(
            "addRule",
            AudioAttributes::class.java,
            Int::class.javaPrimitiveType,
        )
        usages.sorted().forEach { usage ->
            val attributes = AudioAttributes.Builder().setUsage(usage).build()
            addRule.invoke(ruleBuilder, attributes, RULE_MATCH_ATTRIBUTE_USAGE)
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
        val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)
        val result = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass)
            .invoke(audioManager, policy) as Int
        val status = policyClass.getMethod("getStatus").invoke(policy) as Int
        check(result == REGISTER_SUCCESS && status == POLICY_STATUS_REGISTERED) {
            "registerAudioPolicy returned result=$result status=$status"
        }
        return checkNotNull(policy)
    }

    private fun unregisterCurrentPolicy(reason: String) {
        val policy = registeredPolicy ?: return
        runCatching {
            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass)
                .invoke(audioManager, policy)
            val status = policyClass.getMethod("getStatus").invoke(policy) as Int
            check(status == 1) { "unregisterAudioPolicy left status=$status" }
        }.onFailure { rawError ->
            val error = unwrapInvocationError(rawError)
            RoutingTrace.recordError("phone_sound_policy", "unregister_failed", error)
            Log.e(TAG, "Could not unregister phone-speaker policy", error)
        }
        registeredPolicy = null
        registeredUsages = emptySet()
        Log.d(TAG, "Phone-speaker policy unregistered: $reason")
    }

    private fun updateStatus(
        active: Boolean,
        usages: Set<Int>,
        state: String,
        error: String?,
    ) {
        _status.value = PhoneSoundRoutingStatus(
            initialized = initialized,
            active = active,
            targetAvailable = targetAvailable,
            requestedUsages = usages.toSet(),
            state = state,
            lastError = error,
        )
    }

    private fun recordStatus(
        reason: String,
        state: String,
        error: String?,
        severity: RoutingSeverity = RoutingSeverity.INFO,
    ) {
        val enabled = PhoneSoundRoutingPreferences.isEnabled(preferences)
        val usages = PhoneSoundRoutingPreferences.read(preferences).routedUsages
        val permissionGranted = appContext.checkSelfPermission(
            MODIFY_AUDIO_ROUTING_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
        RoutingTrace.record(severity, me.kavishdevar.librepods.diagnostics.RoutingCorrelation()) {
            RoutingEventDetail.PhoneSoundPolicy(
                enabled = enabled,
                targetAvailable = targetAvailable,
                requestedUsages = usages,
                status = if (error == null) state else "$state: $error",
                permissionGranted = permissionGranted,
                reason = reason,
            )
        }
    }

    private fun unwrapInvocationError(error: Throwable): Throwable =
        (error as? InvocationTargetException)?.targetException ?: error

    private fun classifyFailure(error: Throwable): String = when (error) {
        is ClassNotFoundException,
        is NoSuchMethodException,
        is IllegalAccessException -> "hidden_api_blocked"
        is IllegalArgumentException -> "local_build_rejected"
        is SecurityException -> "register_security_exception"
        is IllegalStateException -> if (
            error.message?.contains("registerAudioPolicy returned") == true
        ) {
            "register_policy_denied"
        } else {
            "registration_failed"
        }
        else -> "registration_failed"
    }
}
