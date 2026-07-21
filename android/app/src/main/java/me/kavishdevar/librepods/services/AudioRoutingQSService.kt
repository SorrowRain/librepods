/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.ManualAudioRouteRequestResult
import me.kavishdevar.librepods.data.ManualAudioRouteState

class AudioRoutingQSService : TileService() {
    private var receiverRegistered = false

    private val routeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AirPodsNotifications.AUDIO_ROUTE_CHANGED)
                addAction(AirPodsNotifications.AIRPODS_CONNECTED)
                addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
            }
            registerReceiver(routeReceiver, filter, RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        updateTile()
    }

    override fun onStopListening() {
        unregisterRouteReceiver()
        super.onStopListening()
    }

    override fun onDestroy() {
        unregisterRouteReceiver()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val result = ServiceManager.getService()?.toggleManualAudioRoute()
            ?: ManualAudioRouteRequestResult.UNAVAILABLE
        updateTile()

        val message = when (result) {
            ManualAudioRouteRequestResult.TAKEOVER_STARTED,
            ManualAudioRouteRequestResult.RELEASE_STARTED -> null
            ManualAudioRouteRequestResult.BUSY -> R.string.audio_route_tile_busy
            ManualAudioRouteRequestResult.UNAVAILABLE -> R.string.audio_route_tile_unavailable
            ManualAudioRouteRequestResult.UNKNOWN_SOURCE ->
                R.string.audio_route_tile_unknown_source
        }
        message?.let {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = ServiceManager.getService()?.getManualAudioRouteState()
            ?: ManualAudioRouteState.UNAVAILABLE

        tile.icon = Icon.createWithResource(this, R.drawable.airpods)
        when (state) {
            ManualAudioRouteState.PHONE -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.audio_route_tile_phone)
                tile.subtitle = getString(R.string.audio_route_tile_phone_action)
            }
            ManualAudioRouteState.REMOTE -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.audio_route_tile_remote)
                tile.subtitle = getString(R.string.audio_route_tile_remote_action)
            }
            ManualAudioRouteState.RELEASED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.audio_route_tile_released)
                tile.subtitle = getString(R.string.audio_route_tile_released_action)
            }
            ManualAudioRouteState.SWITCHING_TO_PHONE -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.audio_route_tile_switching_phone)
                tile.subtitle = null
            }
            ManualAudioRouteState.RELEASING_PHONE -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.audio_route_tile_releasing)
                tile.subtitle = null
            }
            ManualAudioRouteState.UNKNOWN -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.audio_route_tile_unknown)
                tile.subtitle = null
            }
            ManualAudioRouteState.UNAVAILABLE -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.audio_route_tile_unavailable)
                tile.subtitle = null
            }
        }
        tile.contentDescription = tile.label
        tile.stateDescription = tile.subtitle ?: tile.label
        tile.updateTile()
    }

    private fun unregisterRouteReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(routeReceiver) }
        receiverRegistered = false
    }
}
