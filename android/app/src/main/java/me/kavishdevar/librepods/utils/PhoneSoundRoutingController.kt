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
import me.kavishdevar.librepods.audio.PreferredRouteLease
import me.kavishdevar.librepods.audio.PreferredRoutePort
import me.kavishdevar.librepods.audio.RouteDevice
import me.kavishdevar.librepods.audio.AudioProductStrategyLookup
import org.json.JSONArray
import org.json.JSONObject

data class PhoneSoundRoutingStatus(
    val initialized: Boolean = false,
    val active: Boolean = false,
    val state: String = "not_initialized",
    val lastError: String? = null,
    /**
     * Whether the preferred-device override is in place. The AudioPolicy mix alone only
     * matches at track creation, so without this an already-playing app keeps its stream
     * on the AirPods and simply goes silent once A2DP START is blocked.
     */
    val preferredRouteApplied: Boolean = false,
)

/** Routes non-call playback to the built-in speaker while another device owns AirPods audio. */
object PhoneSoundRoutingController {
    private const val TAG = "RemoteDeviceAudio"
    private const val MODIFY_AUDIO_ROUTING = "android.permission.MODIFY_AUDIO_ROUTING"
    private const val RULE_MATCH_ATTRIBUTE_USAGE = 1
    private const val ROUTE_FLAG_RENDER = 1
    private const val REGISTER_SUCCESS = 0
    private const val POLICY_STATUS_REGISTERED = 2
    private const val AUDIO_DEVICE_ROLE_OUTPUT = 2
    private const val ROUTING_STATE_PREFS = "phone_sound_routing"
    /**
     * Set before the preferred-device override is applied. AudioService does not
     * necessarily drop the override when this process dies, which would leave media
     * pinned to the speaker; the next service start reads this and clears it.
     */
    private const val KEY_PREFERRED_ROUTE_APPLIED = "preferred_route_applied"

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
    private lateinit var preferredRoutes: PreferredRouteLease
    private var strategyObjects: Map<Int, Any> = emptyMap()
    private var recoveryReadFailed = false
    private const val ORIGINAL_ROUTES = "original_routes_v2"
    private val retryHandler = Handler(Looper.getMainLooper())
    private var unregisterRetryCount = 0
    private var desiredEnabled = false
    private val unregisterRetryRunnable = Runnable {
        synchronized(this) {
            if (!desiredEnabled && (registeredPolicy != null || preferredRoutes.hasPendingCleanup)) {
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
        initializePreferredRoutes()
        val recovered = recoverLeakedPreferredRoute()
        if (registeredPolicy == null && recovered) {
            status = PhoneSoundRoutingStatus(initialized = true, state = "idle")
        } else if (!recovered) {
            status = PhoneSoundRoutingStatus(true, true, "unregistration_failed")
            scheduleUnregisterRetry(null, "startup recovery")
        }
    }

    /** Migrate the previous boolean marker without discarding cleanup ownership. */
    private fun recoverLeakedPreferredRoute(): Boolean {
        val prefs = appContext.getSharedPreferences(ROUTING_STATE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PREFERRED_ROUTE_APPLIED, false) && !prefs.contains(ORIGINAL_ROUTES)) {
            val original = runCatching { guardedStrategies().associateWith { emptyList<RouteDevice>() } }
                .getOrElse { return false }
            preferredRoutes = PreferredRouteLease(preferredRoutePort(), original)
        }
        return preferredRoutes.release()
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
        if (registeredPolicy != null) {
            // A policy is already registered. If a prior partial teardown left the preferred route
            // cleared (unregisterPolicy clears it before attempting to unregister, and can fail),
            // re-apply the route so an already-open phone A2DP stream is pulled back to the speaker
            // (F2). If the policy is stuck in a failed-unregister state, force a clean rebuild and
            // fall through to re-register it.
            if (status.state == "unregistration_failed" ||
                status.state == "registration_cleanup_failed"
            ) {
                if (!unregisterPolicy(reason)) return
            } else {
                val routeApplied = preferredRoutes.isApplied(guardedStrategies()) || applyPreferredSpeakerRoute()
                status = status.copy(preferredRouteApplied = routeApplied)
                return
            }
        }

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
                val preferredRouteApplied = applyPreferredSpeakerRoute()
                status = PhoneSoundRoutingStatus(
                    initialized = true,
                    active = true,
                    state = "registered",
                    preferredRouteApplied = preferredRouteApplied,
                )
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

    private fun guardedStrategies(): List<Int> {
        val strategyClass = Class.forName("android.media.audiopolicy.AudioProductStrategy")
        val supports = strategyClass.getMethod("supportsAudioAttributes", AudioAttributes::class.java)
        val strategies = AudioProductStrategyLookup.query(AudioManager::class.java, strategyClass)
        strategyObjects = strategies.filterNotNull().associateBy {
            strategyClass.getMethod("getId").invoke(it) as Int
        }
        return strategyObjects.filterValues { strategy ->
            guardedUsages.any { usage ->
                supports.invoke(strategy, AudioAttributes.Builder().setUsage(usage).build()) == true
            } && supports.invoke(strategy, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build()) != true
        }.keys.toList()
    }

    private fun initializePreferredRoutes() {
        recoveryReadFailed = false
        val saved = appContext.getSharedPreferences(ROUTING_STATE_PREFS, Context.MODE_PRIVATE)
            .getString(ORIGINAL_ROUTES, "{}") ?: "{}"
        val originals = runCatching {
            val json = JSONObject(saved)
            json.keys().asSequence().associate { key ->
                val array = json.getJSONArray(key)
                key.toInt() to (0 until array.length()).map { index ->
                    val device = array.getJSONObject(index)
                    RouteDevice(device.getInt("type"), device.optString("address"))
                }
            }
        }.getOrElse {
            Log.e(TAG, "Cannot read saved route recovery state", it)
            recoveryReadFailed = true
            emptyMap()
        }
        preferredRoutes = PreferredRouteLease(preferredRoutePort(), originals)
    }

    private fun preferredRoutePort() = object : PreferredRoutePort {
        private val strategyClass get() = Class.forName("android.media.audiopolicy.AudioProductStrategy")
        private val deviceClass get() = Class.forName("android.media.AudioDeviceAttributes")
        private fun strategy(id: Int): Any {
            guardedStrategies()
            return checkNotNull(strategyObjects[id]) { "Audio strategy unavailable" }
        }
        override fun read(strategy: Int): List<RouteDevice> {
            val devices = AudioManager::class.java.getMethod("getPreferredDevicesForStrategy", strategyClass)
                .invoke(audioManager, strategy(strategy)) as List<*>
            return devices.filterNotNull().map { device ->
                RouteDevice(deviceClass.getMethod("getType").invoke(device) as Int,
                    deviceClass.getMethod("getAddress").invoke(device) as String)
            }
        }
        override fun write(strategy: Int, devices: List<RouteDevice>): Boolean {
            val result = if (devices.isEmpty()) {
                AudioManager::class.java.getMethod("removePreferredDeviceForStrategy", strategyClass)
                    .invoke(audioManager, strategy(strategy))
            } else {
                val values = devices.map { device ->
                    deviceClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                        .newInstance(AUDIO_DEVICE_ROLE_OUTPUT, device.type, device.address)
                }
                AudioManager::class.java.getMethod("setPreferredDevicesForStrategy", strategyClass, List::class.java)
                    .invoke(audioManager, strategy(strategy), values)
            }
            return result == true
        }
        override fun save(originals: Map<Int, List<RouteDevice>>): Boolean {
            if (recoveryReadFailed) return false
            val json = JSONObject()
            originals.forEach { (strategy, devices) ->
                json.put(strategy.toString(), JSONArray().apply {
                    devices.forEach { put(JSONObject().put("type", it.type).put("address", it.address)) }
                })
            }
            return appContext.getSharedPreferences(ROUTING_STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putString(ORIGINAL_ROUTES, json.toString())
                .putBoolean(KEY_PREFERRED_ROUTE_APPLIED, originals.isNotEmpty()).commit()
        }
    }

    private fun applyPreferredSpeakerRoute(): Boolean = runCatching {
        preferredRoutes.apply(guardedStrategies())
    }.onFailure { Log.e(TAG, "Preferred speaker route failed", unwrap(it)) }.getOrDefault(false)

    @Synchronized
    fun verifyPreferredSpeakerRoute(): Boolean = runCatching {
        preferredRoutes.isApplied(guardedStrategies())
    }.getOrDefault(false).also { status = status.copy(preferredRouteApplied = it) }

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
        // Always first: leaving the override in place would keep media on the speaker even
        // after the guard has released everything else.
        val routesReleased = preferredRoutes.release()

        val policy = registeredPolicy
        if (policy == null) {
            status = PhoneSoundRoutingStatus(true, !routesReleased,
                if (routesReleased) "idle" else "unregistration_failed")
            if (!routesReleased) scheduleUnregisterRetry(null, reason)
            return routesReleased
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
                preferredRouteApplied = false,
            )
            Log.e(TAG, "Speaker barrier unregister failed: $reason", error)
            scheduleUnregisterRetry(policy, reason)
            return false
        }

        registeredPolicy = null
        unregisterRetryCount = 0
        retryHandler.removeCallbacks(unregisterRetryRunnable)
        status = PhoneSoundRoutingStatus(true, !routesReleased,
            if (routesReleased) "idle" else "unregistration_failed")
        if (!routesReleased) scheduleUnregisterRetry(null, reason)
        Log.i(TAG, "Speaker barrier released=$routesReleased: $reason")
        return routesReleased
    }

    private fun scheduleUnregisterRetry(policy: Any?, reason: String) {
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
