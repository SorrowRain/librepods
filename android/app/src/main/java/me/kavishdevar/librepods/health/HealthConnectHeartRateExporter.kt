/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.health

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.heart.HeartRateMeasurement

enum class HealthConnectExportStatus {
    UNAVAILABLE,
    UPDATE_REQUIRED,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    READY,
    ENABLED,
    ERROR,
}

data class HealthConnectExportState(
    val requestedEnabled: Boolean = false,
    val enabled: Boolean = false,
    val status: HealthConnectExportStatus = HealthConnectExportStatus.UNAVAILABLE,
    val detailedSamples: Boolean = false,
    /** Number of heart-rate measurements dropped by backpressure (never written). */
    val droppedMeasurements: Long = 0,
)

internal enum class HealthConnectSdkAvailability {
    AVAILABLE,
    UPDATE_REQUIRED,
    UNAVAILABLE,
}

internal interface HealthConnectHeartRateGateway {
    fun availability(): HealthConnectSdkAvailability
    suspend fun hasWriteHeartRatePermission(): Boolean
    suspend fun insert(record: PendingHeartRateExportRecord)
}

internal interface HeartRateExportPreferences {
    var exportEnabled: Boolean
    var detailedSamples: Boolean
}

internal fun interface HealthConnectExportLogger {
    fun warning(message: String)
}

internal object AndroidHealthConnectExportLogger : HealthConnectExportLogger {
    override fun warning(message: String) {
        Log.w("HealthConnectHR", message)
    }
}

internal class SharedPreferencesHeartRateExportPreferences(
    private val preferences: SharedPreferences,
) : HeartRateExportPreferences {
    override var exportEnabled: Boolean
        get() = preferences.getBoolean(EXPORT_ENABLED_KEY, false)
        set(value) = preferences.edit(commit = !value) {
            putBoolean(EXPORT_ENABLED_KEY, value)
        }

    override var detailedSamples: Boolean
        get() = preferences.getBoolean(DETAILED_SAMPLES_KEY, false)
        set(value) = preferences.edit { putBoolean(DETAILED_SAMPLES_KEY, value) }

    internal companion object {
        const val EXPORT_ENABLED_KEY = "heart_rate_health_connect_export_enabled"
        const val DETAILED_SAMPLES_KEY = "heart_rate_health_connect_detailed_samples"
    }
}

/** Android Health Connect adapter around the platform-neutral aggregation and retry core. */
class HealthConnectHeartRateExporter internal constructor(
    private val gateway: HealthConnectHeartRateGateway,
    private val preferences: HeartRateExportPreferences,
    private val scope: CoroutineScope,
    private val elapsedRealtimeMillis: () -> Long,
    private val retryDelayMillis: Long = RETRY_DELAY_MILLIS,
    private val operationTimeoutMillis: Long = OPERATION_TIMEOUT_MILLIS,
    private val logger: HealthConnectExportLogger = AndroidHealthConnectExportLogger,
) {
    constructor(
        context: Context,
        sharedPreferences: SharedPreferences,
        scope: CoroutineScope,
    ) : this(
        gateway = AndroidHealthConnectHeartRateGateway(context.applicationContext),
        preferences = SharedPreferencesHeartRateExportPreferences(sharedPreferences),
        scope = scope,
        elapsedRealtimeMillis = SystemClock::elapsedRealtime,
    )

    private val mutex = Mutex()
    private val writeGate = AtomicBoolean(false)
    private val requestedEnabled = AtomicBoolean(preferences.exportEnabled)
    private val consentEpoch = AtomicLong(0L)
    private val activeConsentEpoch = AtomicLong(NO_ACTIVE_CONSENT)
    private val requestStateLock = Any()
    private val providerOperation = AtomicReference<Deferred<*>?>(null)
    private val exporterJob = SupervisorJob(scope.coroutineContext[Job])
    private val exporterScope = CoroutineScope(scope.coroutineContext + exporterJob)
    private val core = HealthConnectHeartRateExportCore().apply {
        setDetailedSamples(preferences.detailedSamples)
    }
    private var windowFlushJob: Job? = null
    private var windowFlushDeadline: Long? = null
    private var retryJob: Job? = null
    @Volatile
    private var closed = false
    private val measurementChannel = Channel<BufferedHeartRateMeasurement>(
        capacity = MAX_BUFFERED_MEASUREMENTS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val measurementConsumerJob = exporterScope.launch {
        for (buffered in measurementChannel) {
            acceptBufferedAndAwait(buffered)
        }
    }

    private val _state = MutableStateFlow(
        HealthConnectExportState(
            requestedEnabled = preferences.exportEnabled,
            enabled = false,
            status = gateway.availability().toDisabledStatus(),
            detailedSamples = preferences.detailedSamples,
        ),
    )
    val state: StateFlow<HealthConnectExportState> = _state.asStateFlow()

    fun refresh() {
        exporterScope.launch { refreshAndAwait() }
    }

    suspend fun refreshAndAwait() {
        val request = currentRequest()
        mutex.withLock {
            if (closed || !request.isCurrent()) return
            resolveRequestedStateLocked(request)
            if (_state.value.enabled) {
                drainPendingLocked(request.epoch)
                scheduleWindowFlushLocked()
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        val request = updateRequestedState(enabled)
        if (!enabled) {
            _state.value = _state.value.copy(
                requestedEnabled = false,
                enabled = false,
                status = disabledStatusForCurrentState(),
            )
        }
        exporterScope.launch { applyEnabledRequestAndAwait(request) }
    }

    suspend fun setEnabledAndAwait(enabled: Boolean) {
        applyEnabledRequestAndAwait(updateRequestedState(enabled))
    }

    private suspend fun applyEnabledRequestAndAwait(request: ConsentRequest) {
        mutex.withLock {
            if (closed || !request.isCurrent()) return
            if (!request.enabled) {
                disableRuntimeLocked(disabledStatusForCurrentState())
                return
            }

            resolveRequestedStateLocked(request)
            if (_state.value.enabled) {
                drainPendingLocked(request.epoch)
                scheduleWindowFlushLocked()
            }
        }
    }

    fun setDetailedSamples(detailed: Boolean) {
        preferences.detailedSamples = detailed
        _state.value = _state.value.copy(detailedSamples = detailed)
        exporterScope.launch { setDetailedSamplesAndAwait(detailed) }
    }

    suspend fun setDetailedSamplesAndAwait(detailed: Boolean) {
        preferences.detailedSamples = detailed
        mutex.withLock {
            if (closed || core.detailedSamples == detailed) return
            core.setDetailedSamples(detailed)
            _state.value = _state.value.copy(detailedSamples = detailed)
            cancelWindowFlushLocked()
            if (_state.value.enabled) {
                drainPendingLocked(activeConsentEpoch.get())
                scheduleWindowFlushLocked()
            }
        }
    }

    fun markPermissionDenied() {
        val request = updateRequestedState(false)
        _state.value = HealthConnectExportState(
            requestedEnabled = false,
            enabled = false,
            status = HealthConnectExportStatus.PERMISSION_DENIED,
            detailedSamples = core.detailedSamples,
        )
        exporterScope.launch { markPermissionDeniedAndAwait(request) }
    }

    suspend fun markPermissionDeniedAndAwait() {
        markPermissionDeniedAndAwait(updateRequestedState(false))
    }

    private suspend fun markPermissionDeniedAndAwait(request: ConsentRequest) {
        mutex.withLock {
            if (closed || !request.isCurrent()) return
            disableRuntimeLocked(HealthConnectExportStatus.PERMISSION_DENIED)
        }
    }

    private val droppedMeasurements = AtomicLong(0)

    internal fun accept(
        acquisitionIdentity: HeartRateExportAcquisitionIdentity,
        measurement: HeartRateMeasurement,
    ) {
        val epoch = activeConsentEpoch.get()
        if (!isConsentActive(epoch)) return
        val result = measurementChannel.trySend(
            BufferedHeartRateMeasurement(epoch, acquisitionIdentity, measurement)
        )
        if (result.isFailure) {
            // Backpressure: the 128-slot channel is full (a stalled Health Connect write). Count
            // the drop so the user is not silently losing medical data.
            droppedMeasurements.incrementAndGet()
            _state.value = _state.value.copy(droppedMeasurements = droppedMeasurements.get())
        }
    }

    internal suspend fun acceptAndAwait(
        acquisitionIdentity: HeartRateExportAcquisitionIdentity,
        measurement: HeartRateMeasurement,
    ): Boolean {
        val epoch = activeConsentEpoch.get()
        return acceptBufferedAndAwait(
            BufferedHeartRateMeasurement(epoch, acquisitionIdentity, measurement)
        )
    }

    private suspend fun acceptBufferedAndAwait(
        buffered: BufferedHeartRateMeasurement,
    ): Boolean =
        mutex.withLock {
            if (closed || !isConsentActive(buffered.consentEpoch)) return@withLock false
            val accepted = core.consume(buffered.acquisitionIdentity, buffered.measurement)
            if (!accepted) return@withLock false
            drainPendingLocked(buffered.consentEpoch)
            scheduleWindowFlushLocked()
            true
        }

    fun flush() {
        exporterScope.launch { flushAndAwait() }
    }

    suspend fun flushAndAwait(): Boolean = mutex.withLock {
        val epoch = activeConsentEpoch.get()
        if (closed || !isConsentActive(epoch)) return@withLock false
        cancelWindowFlushLocked()
        val flushed = core.flushPartialWindow()
        drainPendingLocked(epoch)
        scheduleWindowFlushLocked()
        flushed
    }

    internal suspend fun retryNowAndAwait(): Boolean = mutex.withLock {
        if (closed) return@withLock false
        cancelRetryLocked()
        if (!writeGate.get() || !_state.value.enabled) {
            if (_state.value.status != HealthConnectExportStatus.ERROR ||
                !requestedEnabled.get()
            ) {
                return@withLock false
            }
            val request = currentRequest()
            resolveRequestedStateLocked(request)
            if (!writeGate.get() || !_state.value.enabled) return@withLock false
        }
        val drained = drainPendingLocked(activeConsentEpoch.get())
        scheduleWindowFlushLocked()
        drained
    }

    fun close() {
        closeWriteGate()
        measurementChannel.close()
        measurementConsumerJob.cancel()
        windowFlushJob?.cancel()
        retryJob?.cancel()
        exporterJob.cancel()
        scope.launch { closeAndAwait() }
    }

    suspend fun closeAndAwait() {
        closeWriteGate()
        measurementChannel.close()
        exporterJob.cancelAndJoin()
        mutex.withLock {
            if (closed) return
            closed = true
            cancelWindowFlushLocked()
            cancelRetryLocked()
            core.setEnabled(false)
            _state.value = _state.value.copy(enabled = false)
        }
    }

    private suspend fun resolveRequestedStateLocked(request: ConsentRequest) {
        if (!request.isCurrent()) return
        when (gateway.availability()) {
            HealthConnectSdkAvailability.UNAVAILABLE -> {
                disableRuntimeLocked(HealthConnectExportStatus.UNAVAILABLE)
            }

            HealthConnectSdkAvailability.UPDATE_REQUIRED -> {
                disableRuntimeLocked(HealthConnectExportStatus.UPDATE_REQUIRED)
            }

            HealthConnectSdkAvailability.AVAILABLE -> {
                val granted = when (
                    val result = runProviderOperation(request.epoch) {
                        gateway.hasWriteHeartRatePermission()
                    }
                ) {
                    is ProviderOperationResult.Success -> result.value
                    ProviderOperationResult.Cancelled -> return
                    is ProviderOperationResult.Failure -> {
                        val message = if (result.error is TimeoutCancellationException) {
                            "Health Connect permission check timed out; retry queued"
                        } else {
                            "Health Connect permission check failed; retry queued"
                        }
                        logger.warning(message)
                        if (request.isCurrent()) {
                            disableRuntimeLocked(HealthConnectExportStatus.ERROR)
                            scheduleRetryLocked()
                        }
                        return
                    }
                }
                if (!request.isCurrent()) return

                when {
                    !granted -> {
                        if (request.enabled) updateRequestedState(false)
                        disableRuntimeLocked(HealthConnectExportStatus.PERMISSION_REQUIRED)
                    }

                    request.enabled -> {
                        val retryingWrite = retryJob?.isActive == true && core.enabled
                        core.setEnabled(true)
                        activeConsentEpoch.set(request.epoch)
                        writeGate.set(true)
                        preferences.exportEnabled = true
                        _state.value = HealthConnectExportState(
                            requestedEnabled = true,
                            enabled = true,
                            status = if (retryingWrite) {
                                HealthConnectExportStatus.ERROR
                            } else {
                                HealthConnectExportStatus.ENABLED
                            },
                            detailedSamples = core.detailedSamples,
                        )
                    }

                    else -> {
                        disableRuntimeLocked(HealthConnectExportStatus.READY)
                    }
                }
            }
        }
    }

    private suspend fun drainPendingLocked(epoch: Long): Boolean {
        if (!isConsentActive(epoch)) return false
        if (retryJob?.isActive == true) return false

        while (isConsentActive(epoch)) {
            val record = core.nextPendingRecord() ?: run {
                cancelRetryLocked()
                if (_state.value.status == HealthConnectExportStatus.ERROR) {
                    _state.value = _state.value.copy(status = HealthConnectExportStatus.ENABLED)
                }
                return true
            }

            val granted = when (
                val result = runProviderOperation(epoch) {
                    gateway.hasWriteHeartRatePermission()
                }
            ) {
                is ProviderOperationResult.Success -> result.value
                ProviderOperationResult.Cancelled -> {
                    core.onWriteFailed(record.clientRecordId)
                    return false
                }
                is ProviderOperationResult.Failure -> {
                    core.onWriteFailed(record.clientRecordId)
                    when (result.error) {
                        is SecurityException -> handlePermissionRevokedLocked()
                        is TimeoutCancellationException -> handleRetryableFailureLocked(
                            "Health Connect permission query timed out; retry queued"
                        )
                        else -> handleRetryableFailureLocked(
                            "Health Connect permission query failed; retry queued"
                        )
                    }
                    return false
                }
            }

            if (!granted) {
                handlePermissionRevokedLocked()
                return false
            }
            if (!isConsentActive(epoch)) return false

            when (
                val result = runProviderOperation(epoch) {
                    gateway.insert(record)
                }
            ) {
                is ProviderOperationResult.Success -> Unit
                ProviderOperationResult.Cancelled -> {
                    core.onWriteFailed(record.clientRecordId)
                    return false
                }
                is ProviderOperationResult.Failure -> {
                    core.onWriteFailed(record.clientRecordId)
                    when (result.error) {
                        is SecurityException -> handlePermissionRevokedLocked()
                        is TimeoutCancellationException ->
                            handleRetryableFailureLocked(
                                "Health Connect write timed out; retry queued"
                            )
                        is IOException -> handleRetryableFailureLocked(
                            "Health Connect write failed; retry queued"
                        )
                        is IllegalStateException -> handleRetryableFailureLocked(
                            "Health Connect is temporarily unavailable"
                        )
                        else -> handleRetryableFailureLocked(
                            "Health Connect write failed unexpectedly"
                        )
                    }
                    return false
                }
            }

            if (!isConsentActive(epoch)) return false
            check(core.onWriteSucceeded(record.clientRecordId)) {
                "Health Connect export acknowledgement was stale"
            }
            if (!isConsentActive(epoch)) return false
            _state.value = _state.value.copy(status = HealthConnectExportStatus.ENABLED)
        }
        return false
    }

    private fun handlePermissionRevokedLocked() {
        logger.warning("Health Connect write permission is unavailable; export disabled")
        updateRequestedState(false)
        disableRuntimeLocked(HealthConnectExportStatus.PERMISSION_REQUIRED)
    }

    private fun handleRetryableFailureLocked(message: String) {
        logger.warning(message)
        _state.value = _state.value.copy(status = HealthConnectExportStatus.ERROR)
        scheduleRetryLocked()
    }

    private fun disableRuntimeLocked(status: HealthConnectExportStatus) {
        closeWriteGate()
        cancelWindowFlushLocked()
        cancelRetryLocked()
        core.setEnabled(false)
        _state.value = HealthConnectExportState(
            requestedEnabled = requestedEnabled.get(),
            enabled = false,
            status = status,
            detailedSamples = core.detailedSamples,
        )
    }

    private fun disabledStatusForCurrentState(): HealthConnectExportStatus =
        when (_state.value.status) {
            HealthConnectExportStatus.ENABLED -> HealthConnectExportStatus.READY
            HealthConnectExportStatus.ERROR -> if (core.enabled) {
                HealthConnectExportStatus.READY
            } else {
                HealthConnectExportStatus.ERROR
            }

            else -> _state.value.status
        }

    private fun scheduleWindowFlushLocked() {
        val deadline = core.activeWindowDeadlineElapsedRealtimeMillis ?: run {
            cancelWindowFlushLocked()
            return
        }
        if (windowFlushJob?.isActive == true && windowFlushDeadline == deadline) return

        cancelWindowFlushLocked()
        windowFlushDeadline = deadline
        val job = exporterScope.launch(start = CoroutineStart.LAZY) {
            delay((deadline - elapsedRealtimeMillis()).coerceAtLeast(0L))
            mutex.withLock {
                val epoch = activeConsentEpoch.get()
                if (closed || !isConsentActive(epoch)) return@withLock
                if (windowFlushDeadline != deadline ||
                    core.activeWindowDeadlineElapsedRealtimeMillis != deadline
                ) {
                    scheduleWindowFlushLocked()
                    return@withLock
                }
                windowFlushJob = null
                windowFlushDeadline = null
                core.flushPartialWindow()
                drainPendingLocked(epoch)
                scheduleWindowFlushLocked()
            }
        }
        windowFlushJob = job
        job.start()
    }

    private fun scheduleRetryLocked() {
        if (closed || retryJob?.isActive == true) return
        val retryingWrite = writeGate.get() && _state.value.enabled
        val retryingActivation = _state.value.status == HealthConnectExportStatus.ERROR &&
            requestedEnabled.get()
        if (!retryingWrite && !retryingActivation) return

        val job = exporterScope.launch(start = CoroutineStart.LAZY) {
            delay(retryDelayMillis)
            mutex.withLock {
                retryJob = null
                if (closed) return@withLock
                if (writeGate.get() && _state.value.enabled) {
                    drainPendingLocked(activeConsentEpoch.get())
                    return@withLock
                }
                if (_state.value.status == HealthConnectExportStatus.ERROR &&
                    requestedEnabled.get()
                ) {
                    val request = currentRequest()
                    resolveRequestedStateLocked(request)
                    if (writeGate.get() && _state.value.enabled) {
                        drainPendingLocked(activeConsentEpoch.get())
                        scheduleWindowFlushLocked()
                    }
                }
            }
        }
        retryJob = job
        job.start()
    }

    private fun cancelWindowFlushLocked() {
        windowFlushJob?.cancel()
        windowFlushJob = null
        windowFlushDeadline = null
    }

    private fun cancelRetryLocked() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun updateRequestedState(enabled: Boolean): ConsentRequest =
        synchronized(requestStateLock) {
            val changed = requestedEnabled.getAndSet(enabled) != enabled
            if (changed) consentEpoch.incrementAndGet()
            preferences.exportEnabled = enabled
            if (changed || !enabled) closeWriteGate()
            ConsentRequest(enabled, consentEpoch.get())
        }

    private fun currentRequest(): ConsentRequest = synchronized(requestStateLock) {
        ConsentRequest(requestedEnabled.get(), consentEpoch.get())
    }

    private fun ConsentRequest.isCurrent(): Boolean = synchronized(requestStateLock) {
        requestedEnabled.get() == enabled && consentEpoch.get() == epoch
    }

    private fun closeWriteGate() {
        writeGate.set(false)
        activeConsentEpoch.set(NO_ACTIVE_CONSENT)
        providerOperation.getAndSet(null)?.cancel()
    }

    private fun isConsentActive(epoch: Long): Boolean =
        epoch != NO_ACTIVE_CONSENT &&
            epoch == activeConsentEpoch.get() &&
            epoch == consentEpoch.get() &&
            requestedEnabled.get() &&
            writeGate.get() &&
            _state.value.enabled

    private suspend fun <T> runProviderOperation(
        epoch: Long,
        operationBlock: suspend () -> T,
    ): ProviderOperationResult<T> {
        if (closed || consentEpoch.get() != epoch) return ProviderOperationResult.Cancelled
        val operation = exporterScope.async(start = CoroutineStart.LAZY) { operationBlock() }
        check(providerOperation.compareAndSet(null, operation)) {
            "Health Connect provider operation overlap"
        }
        if (closed || consentEpoch.get() != epoch || providerOperation.get() !== operation) {
            providerOperation.compareAndSet(operation, null)
            operation.cancel()
            return ProviderOperationResult.Cancelled
        }

        return try {
            operation.start()
            ProviderOperationResult.Success(
                withTimeout(operationTimeoutMillis) { operation.await() }
            )
        } catch (timeout: TimeoutCancellationException) {
            ProviderOperationResult.Failure(timeout)
        } catch (cancellation: CancellationException) {
            currentCoroutineContext().ensureActive()
            ProviderOperationResult.Cancelled
        } catch (error: Exception) {
            ProviderOperationResult.Failure(error)
        } finally {
            providerOperation.compareAndSet(operation, null)
            operation.cancel()
        }
    }

    private fun HealthConnectSdkAvailability.toDisabledStatus(): HealthConnectExportStatus =
        when (this) {
            HealthConnectSdkAvailability.AVAILABLE ->
                HealthConnectExportStatus.PERMISSION_REQUIRED
            HealthConnectSdkAvailability.UPDATE_REQUIRED ->
                HealthConnectExportStatus.UPDATE_REQUIRED
            HealthConnectSdkAvailability.UNAVAILABLE -> HealthConnectExportStatus.UNAVAILABLE
        }

    companion object {
        private const val RETRY_DELAY_MILLIS = 30_000L
        private const val OPERATION_TIMEOUT_MILLIS = 10_000L
        private const val MAX_BUFFERED_MEASUREMENTS = 128
        private const val NO_ACTIVE_CONSENT = Long.MIN_VALUE

        val WRITE_HEART_RATE_PERMISSION: String =
            HealthPermission.getWritePermission(HeartRateRecord::class)
        val REQUIRED_PERMISSIONS: Set<String> = setOf(WRITE_HEART_RATE_PERMISSION)
    }

    private data class ConsentRequest(
        val enabled: Boolean,
        val epoch: Long,
    )

    private sealed interface ProviderOperationResult<out T> {
        data class Success<T>(val value: T) : ProviderOperationResult<T>
        data class Failure(val error: Exception) : ProviderOperationResult<Nothing>
        data object Cancelled : ProviderOperationResult<Nothing>
    }

    private data class BufferedHeartRateMeasurement(
        val consentEpoch: Long,
        val acquisitionIdentity: HeartRateExportAcquisitionIdentity,
        val measurement: HeartRateMeasurement,
    ) {
        override fun toString(): String = "BufferedHeartRateMeasurement(redacted)"
    }
}

internal class AndroidHealthConnectHeartRateGateway(context: Context) :
    HealthConnectHeartRateGateway {
    private val appContext = context.applicationContext
    private var client: HealthConnectClient? = null

    override fun availability(): HealthConnectSdkAvailability =
        when (HealthConnectClient.getSdkStatus(appContext)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectSdkAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectSdkAvailability.UPDATE_REQUIRED
            else -> HealthConnectSdkAvailability.UNAVAILABLE
        }

    override suspend fun hasWriteHeartRatePermission(): Boolean =
        HealthConnectHeartRateExporter.WRITE_HEART_RATE_PERMISSION in
            getClient().permissionController.getGrantedPermissions()

    override suspend fun insert(record: PendingHeartRateExportRecord) {
        if (!hasWriteHeartRatePermission()) throw SecurityException("Health permission unavailable")
        getClient().insertRecords(listOf(record.toHealthConnectRecord()))
    }

    private fun getClient(): HealthConnectClient = client
        ?: HealthConnectClient.getOrCreate(appContext).also { client = it }
}

internal fun PendingHeartRateExportRecord.toHealthConnectRecord(): HeartRateRecord {
    val start = Instant.ofEpochMilli(startTimeMillis)
    val end = Instant.ofEpochMilli(endTimeMillis)
    val sampleTime = Instant.ofEpochMilli(sampleTimeMillis)
    val zoneRules = ZoneId.systemDefault().rules

    return HeartRateRecord(
        startTime = start,
        startZoneOffset = zoneRules.getOffset(start),
        endTime = end,
        endZoneOffset = zoneRules.getOffset(end),
        samples = listOf(
            HeartRateRecord.Sample(
                time = sampleTime,
                beatsPerMinute = beatsPerMinute,
            ),
        ),
        metadata = Metadata.autoRecorded(
            device = Device(type = Device.TYPE_UNKNOWN),
            clientRecordId = clientRecordId,
            clientRecordVersion = 0L,
        ),
    )
}
