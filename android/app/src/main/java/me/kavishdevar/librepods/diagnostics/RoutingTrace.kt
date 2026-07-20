/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.BuildConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class RoutingTraceStatus(
    val initialized: Boolean = false,
    val enabled: Boolean = false,
    val sessionId: String? = null,
    val fileCount: Int = 0,
    val totalBytes: Long = 0L,
    val droppedEventCount: Long = 0L,
    val lastError: String? = null,
)

/**
 * Stable, callback-safe entry point for Smart Routing diagnostics.
 *
 * Call [RoutingTrace.initialize] once from the owning service/application process. Producers must
 * put all potentially expensive work inside [record]'s [detailFactory]. When tracing is disabled,
 * that lambda is never evaluated and no diagnostic file is opened or written.
 */
interface RoutingTraceSink {
    val isEnabled: Boolean

    fun record(
        severity: RoutingSeverity,
        correlation: RoutingCorrelation,
        detailFactory: () -> RoutingEventDetail,
    ): Long?
}

object RoutingTrace : RoutingTraceSink {
    private const val TAG = "LibrePodsRouting"
    private const val PREFERENCES_NAME = "routing_diagnostics"
    private const val ENABLED_KEY = "enabled"
    private const val COMMAND_CAPACITY = 512

    private val initialized = AtomicBoolean(false)
    private val enabled = AtomicBoolean(false)
    private val generationCounter = AtomicLong(0L)
    private val sequenceCounter = AtomicLong(0L)
    private val droppedEventCounter = AtomicLong(0L)
    private val pendingDroppedEventCounter = AtomicLong(0L)
    private val lifecycleLock = Any()

    private val writerScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1),
    )
    private val commands = Channel<WriterCommand>(capacity = COMMAND_CAPACITY)
    private val _status = MutableStateFlow(RoutingTraceStatus())

    val status: StateFlow<RoutingTraceStatus> = _status.asStateFlow()

    @Volatile
    private var activeGeneration = 0L

    @Volatile
    private var activeSessionId: String? = null

    private lateinit var preferences: SharedPreferences
    private lateinit var logWriter: RoutingLogWriter

    @Volatile
    private var baselineProvider: (() -> Unit)? = null

    override val isEnabled: Boolean
        get() = initialized.get() && enabled.get()

    @Synchronized
    fun initialize(context: Context) {
        if (initialized.get()) return

        val appContext = context.applicationContext
        preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        logWriter = RoutingLogWriter(appContext)
        initialized.set(true)

        val storage = logWriter.stats()
        _status.value = RoutingTraceStatus(
            initialized = true,
            fileCount = storage.fileCount,
            totalBytes = storage.totalBytes,
        )
        writerScope.launch { consumeCommands() }

        if (preferences.getBoolean(ENABLED_KEY, false)) {
            enable(persist = false)
        }
    }

    fun setEnabled(value: Boolean) {
        check(initialized.get()) { "RoutingTrace.initialize(context) must be called first" }
        if (value) enable(persist = true) else disable(RoutingStopReason.USER, persist = true)
    }

    fun setBaselineProvider(provider: (() -> Unit)?) {
        baselineProvider = provider
        if (provider != null && isEnabled) {
            runCatching(provider).onFailure { error ->
                Log.e(TAG, "Could not record routing diagnostic baseline", error)
            }
        }
    }

    fun mark(label: String = "user_marker"): Long? = record {
        RoutingEventDetail.Marker(label)
    }

    fun disable(reason: RoutingStopReason = RoutingStopReason.USER) {
        if (!initialized.get()) return
        disable(reason = reason, persist = true)
    }

    override fun record(
        severity: RoutingSeverity,
        correlation: RoutingCorrelation,
        detailFactory: () -> RoutingEventDetail,
    ): Long? {
        if (!isEnabled) return null

        val generation = activeGeneration
        val sessionId = activeSessionId ?: return null
        val detail = try {
            detailFactory()
        } catch (error: Throwable) {
            // Diagnostics must never change routing behavior.
            Log.e(TAG, "Could not construct routing diagnostic event", error)
            return null
        }
        if (!isEnabled || generation != activeGeneration || sessionId != activeSessionId) {
            return null
        }

        val event = newEvent(
            sessionId = sessionId,
            severity = severity,
            correlation = correlation,
            detail = detail,
        )
        val result = commands.trySend(WriterCommand.Event(generation, event))
        if (result.isFailure) {
            noteDroppedEvent()
            return null
        }
        Log.d(TAG, "seq=${event.sequence} event=${event.detail.eventName}")
        return event.sequence
    }

    fun record(
        correlation: RoutingCorrelation = RoutingCorrelation(),
        detailFactory: () -> RoutingEventDetail,
    ): Long? = record(RoutingSeverity.INFO, correlation, detailFactory)

    fun recordError(
        component: String,
        code: String,
        error: Throwable,
        correlation: RoutingCorrelation = RoutingCorrelation(),
    ): Long? {
        return record(RoutingSeverity.ERROR, correlation) {
            RoutingEventDetail.Error(
                component = component,
                code = code,
                exceptionType = error::class.java.name,
                message = error.message,
                stackTrace = error.stackTrace.take(24).map(StackTraceElement::toString),
            )
        }
    }

    suspend fun export(): File {
        check(initialized.get()) { "RoutingTrace.initialize(context) must be called first" }
        val result = CompletableDeferred<Result<File>>()
        commands.send(WriterCommand.Export(result))
        return result.await().getOrThrow()
    }

    suspend fun clear() {
        check(initialized.get()) { "RoutingTrace.initialize(context) must be called first" }
        val result = CompletableDeferred<Result<Unit>>()
        commands.send(WriterCommand.Clear(result))
        result.await().getOrThrow()
    }

    suspend fun flush() {
        if (!initialized.get()) return
        val result = CompletableDeferred<Result<Unit>>()
        commands.send(WriterCommand.Flush(result))
        result.await().getOrThrow()
    }

    private fun enable(persist: Boolean) {
        var started = false
        synchronized(lifecycleLock) {
            if (enabled.get()) return

            droppedEventCounter.set(0L)
            pendingDroppedEventCounter.set(0L)
            val generation = generationCounter.incrementAndGet()
            val sessionId = UUID.randomUUID().toString()
            activeGeneration = generation
            activeSessionId = sessionId
            val startEvent = newSessionStartedEvent(sessionId)
            enqueueControl(WriterCommand.Start(generation, sessionId, startEvent))
            enabled.set(true)
            if (persist) preferences.edit().putBoolean(ENABLED_KEY, true).apply()
            _status.update {
                it.copy(
                    enabled = true,
                    sessionId = sessionId,
                    droppedEventCount = 0L,
                    lastError = null,
                )
            }
            started = true
        }
        if (started) {
            baselineProvider?.let { provider ->
                runCatching(provider).onFailure { error ->
                    Log.e(TAG, "Could not record routing diagnostic baseline", error)
                }
            }
        }
    }

    private fun disable(reason: RoutingStopReason, persist: Boolean) {
        synchronized(lifecycleLock) {
            if (!enabled.getAndSet(false)) {
                if (persist) preferences.edit().putBoolean(ENABLED_KEY, false).apply()
                return
            }

            val stoppedGeneration = activeGeneration
            val stoppedSessionId = activeSessionId ?: return
            activeGeneration = generationCounter.incrementAndGet()
            activeSessionId = null
            val stopEvent = newEvent(
                sessionId = stoppedSessionId,
                severity = RoutingSeverity.INFO,
                correlation = RoutingCorrelation(),
                detail = RoutingEventDetail.SessionStopped(
                    reason = reason,
                    droppedEventCount = droppedEventCounter.get(),
                ),
            )
            enqueueControl(WriterCommand.Stop(stoppedGeneration, stopEvent))
            if (persist) preferences.edit().putBoolean(ENABLED_KEY, false).apply()
            _status.update { it.copy(enabled = false, sessionId = null) }
        }
    }

    private fun enqueueControl(command: WriterCommand) {
        if (commands.trySend(command).isFailure) {
            writerScope.launch { commands.send(command) }
        }
    }

    private fun noteDroppedEvent() {
        val total = droppedEventCounter.incrementAndGet()
        pendingDroppedEventCounter.incrementAndGet()
        _status.update { it.copy(droppedEventCount = total) }
    }

    private suspend fun consumeCommands() {
        var writerGeneration = -1L
        var closedGeneration = -1L
        var failedGeneration = -1L

        for (command in commands) {
            when (command) {
                is WriterCommand.Start -> {
                    if (command.generation <= closedGeneration) {
                        continue
                    }
                    runCatching {
                        logWriter.startSession(command.sessionId)
                        logWriter.append(command.event)
                        writerGeneration = command.generation
                        failedGeneration = -1L
                    }.onFailure { error ->
                        failedGeneration = command.generation
                        handleWriterError(error)
                    }
                    refreshStorageStatus()
                }

                is WriterCommand.Event -> {
                    if (command.generation <= closedGeneration ||
                        command.generation == failedGeneration
                    ) {
                        continue
                    }
                    runCatching {
                        if (writerGeneration != command.generation ||
                            logWriter.activeSessionId != command.event.sessionId
                        ) {
                            logWriter.startSession(command.event.sessionId)
                            logWriter.append(newSessionStartedEvent(command.event.sessionId))
                            writerGeneration = command.generation
                        }
                        appendDroppedEventIfNeeded(command.event.sessionId)
                        logWriter.append(command.event)
                    }.onFailure { error ->
                        failedGeneration = command.generation
                        handleWriterError(error)
                    }
                    refreshStorageStatus()
                }

                is WriterCommand.Stop -> {
                    closedGeneration = maxOf(closedGeneration, command.generation)
                    runCatching {
                        if (writerGeneration == command.generation &&
                            logWriter.activeSessionId == command.event.sessionId
                        ) {
                            appendDroppedEventIfNeeded(command.event.sessionId)
                            logWriter.append(command.event)
                            logWriter.closeSession()
                        }
                    }.onFailure(::handleWriterError)
                    if (writerGeneration == command.generation) writerGeneration = -1L
                    refreshStorageStatus()
                }

                is WriterCommand.Export -> {
                    command.result.complete(
                        runCatching {
                            logWriter.export(
                                debugEnabled = enabled.get(),
                                activeSessionId = activeSessionId,
                                droppedEventCount = droppedEventCounter.get(),
                            )
                        },
                    )
                }

                is WriterCommand.Clear -> {
                    val result = runCatching {
                        logWriter.clear()
                        writerGeneration = -1L
                        val sessionId = activeSessionId
                        val generation = activeGeneration
                        if (enabled.get() && sessionId != null) {
                            logWriter.startSession(sessionId)
                            logWriter.append(newSessionStartedEvent(sessionId))
                            writerGeneration = generation
                            failedGeneration = -1L
                        }
                    }
                    result.exceptionOrNull()?.let(::handleWriterError)
                    refreshStorageStatus()
                    command.result.complete(result)
                }

                is WriterCommand.Flush -> {
                    command.result.complete(runCatching { logWriter.flush() })
                }
            }
        }
    }

    private fun appendDroppedEventIfNeeded(sessionId: String) {
        val dropped = pendingDroppedEventCounter.getAndSet(0L)
        if (dropped <= 0L) return
        logWriter.append(
            newEvent(
                sessionId = sessionId,
                severity = RoutingSeverity.WARNING,
                correlation = RoutingCorrelation(),
                detail = RoutingEventDetail.DroppedEvents(dropped),
            ),
        )
    }

    private fun newSessionStartedEvent(sessionId: String): RoutingEvent {
        return newEvent(
            sessionId = sessionId,
            severity = RoutingSeverity.INFO,
            correlation = RoutingCorrelation(),
            detail = RoutingEventDetail.SessionStarted(
                appVersion = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                buildType = BuildConfig.BUILD_TYPE,
                flavor = BuildConfig.FLAVOR,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidRelease = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
            ),
        )
    }

    private fun newEvent(
        sessionId: String,
        severity: RoutingSeverity,
        correlation: RoutingCorrelation,
        detail: RoutingEventDetail,
    ): RoutingEvent {
        return RoutingEvent(
            sessionId = sessionId,
            sequence = sequenceCounter.incrementAndGet(),
            wallTimeEpochMs = System.currentTimeMillis(),
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            severity = severity,
            correlation = correlation,
            detail = detail,
        )
    }

    private fun handleWriterError(error: Throwable) {
        Log.e(TAG, "Routing diagnostic writer failed", error)
        logWriter.closeSession()
        enabled.set(false)
        activeGeneration = generationCounter.incrementAndGet()
        activeSessionId = null
        preferences.edit().putBoolean(ENABLED_KEY, false).apply()
        _status.update {
            it.copy(
                enabled = false,
                sessionId = null,
                lastError = error.message ?: error::class.java.simpleName
            )
        }
    }

    private fun refreshStorageStatus() {
        val storage = logWriter.stats()
        _status.update {
            it.copy(
                fileCount = storage.fileCount,
                totalBytes = storage.totalBytes,
                droppedEventCount = droppedEventCounter.get(),
            )
        }
    }

    private sealed interface WriterCommand {
        data class Start(
            val generation: Long,
            val sessionId: String,
            val event: RoutingEvent,
        ) : WriterCommand

        data class Event(
            val generation: Long,
            val event: RoutingEvent,
        ) : WriterCommand

        data class Stop(
            val generation: Long,
            val event: RoutingEvent,
        ) : WriterCommand

        data class Export(
            val result: CompletableDeferred<Result<File>>,
        ) : WriterCommand

        data class Clear(
            val result: CompletableDeferred<Result<Unit>>,
        ) : WriterCommand

        data class Flush(
            val result: CompletableDeferred<Result<Unit>>,
        ) : WriterCommand
    }
}
