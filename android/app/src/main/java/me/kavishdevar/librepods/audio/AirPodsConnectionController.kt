package me.kavishdevar.librepods.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
internal class AirPodsConnectionController(
    private val context: Context,
    private val target: () -> BluetoothDevice?,
    private val manager: () -> AACPManager,
    private val ensureControl: (BluetoothDevice, () -> Boolean) -> Unit,
    private val profileObserved: (Int, Boolean) -> Unit = { _, _ -> },
) : HandoffConnection {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java).adapter
    private var callRouteAddress: String? = null

    override suspend fun takePhone(trigger: HandoffTrigger, isCurrent: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val device = target() ?: return@withContext false
        if (!isCurrent()) return@withContext false
        ensureControl(device, isCurrent)
        val lease = BluetoothConnectionManager.currentAacpSocketLease() ?: return@withContext false
        val current = { isCurrent() && BluetoothConnectionManager.isCurrent(lease) }
        if (!current()) return@withContext false
        if (!manager().sendControlCommand(AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                byteArrayOf(1), lease)) return@withContext false
        val call = trigger == HandoffTrigger.CALL
        val connected = connectProfile(device, if (call) BluetoothProfile.HEADSET else BluetoothProfile.A2DP,
            current, makeActive = true)
        if (!connected || !current()) return@withContext false
        if (call) {
            val audio = context.getSystemService(AudioManager::class.java)
            val route = audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && it.address.equals(device.address, true)
            } ?: return@withContext false
            if (!current() || !audio.setCommunicationDevice(route)) return@withContext false
            callRouteAddress = device.address
        } else connectProfile(device, BluetoothProfile.HEADSET, current, makeActive = false)
        if (current()) manager().requestAudioSource(lease)
        current()
    }

    override suspend fun clearCallRoute() = withContext(Dispatchers.IO) {
        val audio = context.getSystemService(AudioManager::class.java)
        if (callRouteAddress != null && audio.communicationDevice?.address.equals(callRouteAddress, true)) {
            audio.clearCommunicationDevice()
        }
        callRouteAddress = null
    }

    override suspend fun querySource() = withContext(Dispatchers.IO) {
        BluetoothConnectionManager.currentAacpSocketLease()?.let { manager().requestAudioSource(it) }
        Unit
    }

    /** Isolates one protocol write from profile selection and the normal takeover sequence. */
    suspend fun synchronizeIdleOwnership(isCurrent: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val lease = BluetoothConnectionManager.currentAacpSocketLease() ?: return@withContext false
        if (!isCurrent() || !BluetoothConnectionManager.isCurrent(lease)) return@withContext false
        val sent = manager().sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
            byteArrayOf(0), lease)
        if (sent && BluetoothConnectionManager.isCurrent(lease)) manager().requestAudioSource(lease)
        sent
    }

    suspend fun connectProfile(
        device: BluetoothDevice,
        profile: Int,
        isCurrent: () -> Boolean,
        makeActive: Boolean,
        perform: (() -> Boolean) -> Boolean = { it() },
    ): Boolean {
        val proxy = withTimeoutOrNull(1_500) {
            suspendCancellableCoroutine<BluetoothProfile?> { continuation ->
                val requested = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (continuation.isActive) continuation.resume(proxy) { _, value, _ ->
                            adapter.closeProfileProxy(profile, value)
                        } else adapter.closeProfileProxy(profile, proxy)
                    }
                    override fun onServiceDisconnected(profile: Int) = Unit
                }, profile)
                if (!requested && continuation.isActive) continuation.resume(null)
            }
        } ?: return false
        return try {
            if (!isCurrent()) return false
            profileObserved(profile, proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED)
            if (proxy.getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) {
                val policy = proxy.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                if (!perform { isCurrent() && policy.invoke(proxy, device, 100) == true } || !isCurrent()) return false
                if (!perform { isCurrent() && proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, device) == true } &&
                    proxy.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED) return false
            }
            if (!makeActive) return isCurrent()
            repeat(15) {
                if (!isCurrent()) return false
                if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                    profileObserved(profile, true)
                    return perform { isCurrent() && proxy.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java)
                        .invoke(proxy, device) == true }
                }
                delay(150)
            }
            false
        } finally {
            adapter.closeProfileProxy(profile, proxy)
        }
    }
}
