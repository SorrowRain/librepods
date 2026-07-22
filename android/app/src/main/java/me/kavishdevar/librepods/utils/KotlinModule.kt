package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.ImageView
import androidx.core.net.toUri
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import me.kavishdevar.librepods.data.A2dpStartGuardLease

private const val TAG = "LibrePodsHook"

@SuppressLint("DiscouragedApi", "PrivateApi")
class KotlinModule: XposedModule() {
    private var a2dpStartGuardPreferences: SharedPreferences? = null
    private var a2dpStartGuardPreferenceListener:
        SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val a2dpStartGuardApplyLock = Any()
    private var lastAppliedA2dpStartGuardEnabled: Boolean? = null
    private var lastAppliedA2dpStartGuardExpiryMs: Long? = null
    private var lastAppliedA2dpStartGuardBootId: String? = null
    private var lastAppliedA2dpStartGuardActive: Boolean? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "module initialized at :: ${param.processName}")
        log(Log.INFO, TAG, "framework: $frameworkName($frameworkVersionCode) API $apiVersion")
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    override fun onPackageLoaded(param: PackageLoadedParam) {
        log(Log.INFO, TAG, "onPackageLoaded :: ${param.packageName}")

        if (param.packageName == "com.google.android.bluetooth" || param.packageName == "com.android.bluetooth") {
            log(Log.INFO, TAG, "Bluetooth app detected, hooking l2c_fcr_chk_chan_modes")
            try {
                if (param.isFirstPackage) {
                    val abi = android.os.Build.SUPPORTED_ABIS.first()
                    val soName = "libl2c_fcr_hook.so"

                    val candidates = buildList {
                        add("${moduleApplicationInfo.sourceDir}!/lib/$abi/$soName")

                        moduleApplicationInfo.splitSourceDirs?.forEach { split ->
                            add("$split!/lib/$abi/$soName")
                        }
                    }

                    var loaded = false

                    for (path in candidates) {
                        try {
                            log(Log.INFO, TAG, "Trying to load native lib from $path")
                            System.load(path)
                            log(Log.INFO, TAG, "Loaded native lib from $path")
                            loaded = true
                            break
                        } catch (e: Throwable) {
                            log(Log.WARN, TAG, "Failed to load from $path: ${e.message}")
                        }
                    }

                    if (!loaded) {
                        log(Log.ERROR, TAG, "Could not load $soName from base or splits")
                        return
                    }

                    val remotePrefValue = getRemotePreferences("me.kavishdevar.librepods").getBoolean("vendor_id_hook", false)
                    log(Log.INFO, TAG, "sdp hook enabled (remote pref): $remotePrefValue")
                    NativeBridge.setSdpHook(remotePrefValue)
                    observeA2dpStartGuardLease()
                    log(Log.INFO, TAG, "Native library loaded successfully")
                }
            } catch (e: Exception) {
                log(Log.ERROR, TAG, "Failed to load native library: ${e.message}")
            }
        }

        if (param.packageName == "com.google.android.settings") {
            hookSettingsController(param, "com.google.android.settings.bluetooth.AdvancedBluetoothDetailsHeaderController")
        }

        if (param.packageName == "com.android.settings") {
            hookSettingsController(param, "com.android.settings.bluetooth.AdvancedBluetoothDetailsHeaderController")
        }
    }

    private fun observeA2dpStartGuardLease() {
        val preferences = getRemotePreferences(REMOTE_PREFERENCES_NAME)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPreferences, key ->
            if (key == A2dpStartGuardLease.ENABLED_KEY ||
                key == A2dpStartGuardLease.EXPIRY_ELAPSED_REALTIME_MS_KEY ||
                key == A2dpStartGuardLease.BOOT_ID_KEY ||
                key == A2dpStartGuardLease.REVISION_KEY
            ) {
                applyA2dpStartGuardLease(changedPreferences)
            }
        }

        a2dpStartGuardPreferenceListener?.let { oldListener ->
            a2dpStartGuardPreferences?.unregisterOnSharedPreferenceChangeListener(oldListener)
        }
        a2dpStartGuardPreferences = preferences
        a2dpStartGuardPreferenceListener = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)
        applyA2dpStartGuardLease(preferences)
    }

    private fun applyA2dpStartGuardLease(preferences: SharedPreferences) {
        try {
            val revisionBefore = preferences.getLong(A2dpStartGuardLease.REVISION_KEY, 0L)
            if (!A2dpStartGuardLease.isStableRevision(revisionBefore, revisionBefore)) return
            val enabled = preferences.getBoolean(A2dpStartGuardLease.ENABLED_KEY, false)
            val expiryElapsedRealtimeMs = preferences.getLong(
                A2dpStartGuardLease.EXPIRY_ELAPSED_REALTIME_MS_KEY,
                0L,
            )
            val leaseBootId = preferences.getString(A2dpStartGuardLease.BOOT_ID_KEY, null)
            val revisionAfter = preferences.getLong(A2dpStartGuardLease.REVISION_KEY, 0L)
            if (!A2dpStartGuardLease.isStableRevision(revisionBefore, revisionAfter)) return
            synchronized(a2dpStartGuardApplyLock) {
                if (enabled == lastAppliedA2dpStartGuardEnabled &&
                    expiryElapsedRealtimeMs == lastAppliedA2dpStartGuardExpiryMs &&
                    leaseBootId == lastAppliedA2dpStartGuardBootId
                ) {
                    return
                }

                val remainingMs = A2dpStartGuardLease.remainingMs(
                    enabled = enabled,
                    expiryElapsedRealtimeMs = expiryElapsedRealtimeMs,
                    leaseBootId = leaseBootId,
                    currentBootId = A2dpStartGuardLease.currentBootId(),
                    nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
                )
                NativeBridge.setA2dpStartGuardLease(remainingMs)
                lastAppliedA2dpStartGuardEnabled = enabled
                lastAppliedA2dpStartGuardExpiryMs = expiryElapsedRealtimeMs
                lastAppliedA2dpStartGuardBootId = leaseBootId

                val active = remainingMs > 0L
                if (active != lastAppliedA2dpStartGuardActive) {
                    log(Log.INFO, TAG, "A2DP START guard lease active: $active")
                    lastAppliedA2dpStartGuardActive = active
                }
            }
        } catch (e: Throwable) {
            runCatching { NativeBridge.setA2dpStartGuardLease(0L) }
            log(Log.ERROR, TAG, "Failed to update A2DP START guard lease: ${e.message}")
        }
    }

    private fun hookSettingsController(param: PackageLoadedParam, className: String) {
        log(Log.INFO, TAG, "Settings app detected, hooking Bluetooth icon handling")
        try {
            val headerControllerClass = Class.forName(className, false, param.defaultClassLoader)
            val updateIconMethod = headerControllerClass.getDeclaredMethod(
                "updateIcon",
                ImageView::class.java,
                String::class.java
            )

            hook(updateIconMethod).intercept { chain ->
                try {
                    log(Log.INFO, TAG, "Bluetooth icon hook called with args: ${chain.args.joinToString(", ")}")
                    val imageView = chain.args[0] as? ImageView
                    val iconUri = chain.args[1] as? String

                    if (imageView == null || iconUri == null) {
                        return@intercept chain.proceed()
                    }

                    val uri = iconUri.toUri()
                    if (!uri.toString().startsWith("android.resource://me.kavishdevar.librepods")) {
                        return@intercept chain.proceed()
                    }

                    log(Log.INFO, TAG, "Handling AirPods icon URI: $uri")

                    Handler(Looper.getMainLooper()).post {
                        try {
                            val context = imageView.context
                            val packageName = uri.authority ?: return@post
                            val packageContext = context.createPackageContext(
                                packageName,
                                Context.CONTEXT_IGNORE_SECURITY
                            )

                            val resPath = uri.pathSegments
                            if (resPath.size >= 2 && resPath[0] == "drawable") {
                                val resourceName = resPath[1]
                                val resourceId = packageContext.resources.getIdentifier(
                                    resourceName, "drawable", packageName
                                )

                                if (resourceId != 0) {
                                    val drawable = packageContext.resources.getDrawable(
                                        resourceId, packageContext.theme
                                    )
                                    imageView.setImageDrawable(drawable)
                                    imageView.alpha = 1.0f
                                    log(Log.INFO, TAG, "Successfully loaded icon from resource: $resourceName")
                                } else {
                                    log(Log.ERROR, TAG, "Resource not found: $resourceName")
                                }
                            }
                        } catch (e: Exception) {
                            log(Log.ERROR, TAG, "Error loading resource from URI $uri: ${e.message}")
                        }
                    }
                    null
                } catch (e: Exception) {
                    log(Log.ERROR, TAG, "Error in Bluetooth icon hook: ${e.message}")
                    chain.proceed()
                }
            }

            log(Log.INFO, TAG, "Successfully hooked updateIcon method in Bluetooth settings")
        } catch (e: Exception) {
            log(Log.ERROR, TAG, "Failed to hook Bluetooth icon handler: ${e.message}")
        }
    }
}


object NativeBridge {
    external fun setSdpHook(enabled: Boolean)
    external fun setA2dpStartGuardLease(remainingMs: Long)
}

private const val REMOTE_PREFERENCES_NAME = "me.kavishdevar.librepods"
