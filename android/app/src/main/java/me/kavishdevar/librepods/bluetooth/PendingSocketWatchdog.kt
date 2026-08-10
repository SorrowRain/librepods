/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

package me.kavishdevar.librepods.bluetooth

/**
 * Identity-based state for a watchdog that owns one pending socket at a time.
 *
 * Scheduling is deliberately kept outside this class. The service can use a dedicated watchdog
 * thread, while JVM tests can drive [expireIfCurrent] deterministically.
 */
internal class PendingSocketWatchdog<T : Any> {
    private val lock = Any()
    private var pending: T? = null

    fun arm(target: T) {
        synchronized(lock) {
            pending = target
        }
    }

    fun cancel(target: T): Boolean = synchronized(lock) {
        if (pending !== target) return@synchronized false
        pending = null
        true
    }

    /** Claims the timeout only if [target] is still the exact pending object. */
    fun expireIfCurrent(target: T): Boolean = synchronized(lock) {
        if (pending !== target) return@synchronized false
        pending = null
        true
    }

    fun clear(): T? = synchronized(lock) {
        pending.also { pending = null }
    }

    fun isPending(target: T): Boolean = synchronized(lock) {
        pending === target
    }
}
