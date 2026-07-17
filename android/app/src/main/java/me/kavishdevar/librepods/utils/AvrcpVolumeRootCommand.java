/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package me.kavishdevar.librepods.utils;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Root app_process entry point that resends the active AirPods AVRCP volume. */
@SuppressLint("MissingPermission")
public final class AvrcpVolumeRootCommand {
    private static final long PLAYBACK_POLL_INTERVAL_MS = 20L;
    private static final long PREWARM_TIMEOUT_MS = 30L * 60L * 1_000L;

    private static BluetoothAdapter adapter;
    private static BluetoothProfile proxy;
    private static volatile boolean finished;

    private AvrcpVolumeRootCommand() {}

    public static void main(String[] args) {
        try {
            boolean waitForPlayback = args.length == 2 && "--wait".equals(args[1]);
            if (!waitForPlayback && args.length != 3) {
                throw new IllegalArgumentException(
                        "Expected MAC plus --wait, or MAC plus pulse and restore volumes");
            }

            String address = args[0];
            int pulseVolume = waitForPlayback ? 0 : parseVolume(args[1]);
            int restoreVolume = waitForPlayback ? 0 : parseVolume(args[2]);

            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
            Context context = getSystemContext();
            initializeBluetoothFramework();
            BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
            adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
            if (adapter == null) {
                throw new IllegalStateException("Bluetooth adapter is unavailable");
            }
            BluetoothDevice target = adapter.getRemoteDevice(address);

            BluetoothProfile.ServiceListener listener =
                    new BluetoothProfile.ServiceListener() {
                        @Override
                        public void onServiceConnected(int profileId, BluetoothProfile service) {
                            if (profileId != BluetoothProfile.A2DP) {
                                finishWithError("Unexpected Bluetooth profile " + profileId);
                                return;
                            }
                            proxy = service;
                            try {
                                BluetoothA2dp a2dp = (BluetoothA2dp) service;
                                if (waitForPlayback) {
                                    System.out.println("ready=1");
                                    System.out.flush();
                                    waitForTrigger(a2dp, target);
                                } else {
                                    sendVolume(a2dp, target, pulseVolume, restoreVolume);
                                }
                            } catch (Throwable error) {
                                finishWithError(describe(error));
                            }
                        }

                        @Override
                        public void onServiceDisconnected(int profileId) {
                            if (!finished) {
                                finishWithError("Bluetooth A2DP service disconnected");
                            }
                        }
                    };

            if (!adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)) {
                throw new IllegalStateException("Unable to obtain Bluetooth A2DP proxy");
            }
            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(
                    () -> {
                        if (proxy == null) {
                            finishWithError("Bluetooth A2DP proxy timed out");
                        }
                    },
                    5_000L);
            if (waitForPlayback) {
                handler.postDelayed(
                        () -> finishWithError("Prewarmed AVRCP command timed out"),
                        PREWARM_TIMEOUT_MS);
            }
            Looper.loop();
        } catch (Throwable error) {
            if (!finished) {
                System.out.println("error=" + describe(error));
            }
            closeProxy();
            System.exit(1);
        }
    }

    private static void waitForTrigger(BluetoothA2dp a2dp, BluetoothDevice target) {
        Thread triggerThread = new Thread(() -> {
            try {
                String trigger = new BufferedReader(
                        new InputStreamReader(System.in)).readLine();
                String[] fields = trigger == null
                        ? new String[0] : trigger.trim().split("\\s+");
                if (fields.length != 3 || !"go".equals(fields[0])) {
                    finishWithError("Invalid prewarmed AVRCP trigger");
                    return;
                }
                int pulseVolume = parseVolume(fields[1]);
                int restoreVolume = parseVolume(fields[2]);
                new Handler(Looper.getMainLooper()).post(
                        () -> waitForTargetPlayback(
                                a2dp, target, pulseVolume, restoreVolume));
            } catch (Throwable error) {
                finishWithError(describe(error));
            }
        }, "librepods-avrcp-trigger");
        triggerThread.setDaemon(true);
        triggerThread.start();
    }

    private static void waitForTargetPlayback(
            BluetoothA2dp a2dp,
            BluetoothDevice target,
            int pulseVolume,
            int restoreVolume
    ) {
        if (finished) return;
        try {
            boolean connected = a2dp.getConnectionState(target)
                    == BluetoothProfile.STATE_CONNECTED;
            boolean playing = connected && (Boolean) invoke(
                    a2dp,
                    "isA2dpPlaying",
                    new Class<?>[] {BluetoothDevice.class},
                    target);
            if (playing) {
                System.out.println("playback=1");
                System.out.flush();
                sendVolume(a2dp, target, pulseVolume, restoreVolume);
                return;
            }
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> waitForTargetPlayback(
                            a2dp, target, pulseVolume, restoreVolume),
                    PLAYBACK_POLL_INTERVAL_MS);
        } catch (Throwable error) {
            finishWithError(describe(error));
        }
    }

    private static void sendVolume(
            BluetoothA2dp a2dp,
            BluetoothDevice target,
            int pulseVolume,
            int restoreVolume
    ) throws Exception {
        if (a2dp.getConnectionState(target) != BluetoothProfile.STATE_CONNECTED ||
                !(Boolean) invoke(
                        a2dp,
                        "isA2dpPlaying",
                        new Class<?>[] {BluetoothDevice.class},
                        target)) {
            finishWithError("Target AirPods are not playing on this phone");
            return;
        }

        boolean absoluteVolumeSupported = (Boolean) invoke(
                a2dp,
                "isAvrcpAbsoluteVolumeSupported",
                new Class<?>[0]);
        // HyperOS reports false here while AvrcpVolumeManager still accepts
        // these commands, so retain the value for diagnostics only.
        invoke(
                a2dp,
                "setAvrcpAbsoluteVolume",
                new Class<?>[] {int.class},
                pulseVolume);
        invoke(
                a2dp,
                "setAvrcpAbsoluteVolume",
                new Class<?>[] {int.class},
                restoreVolume);
        System.out.println(
                "avrcp=" + absoluteVolumeSupported +
                        ",sent=" + pulseVolume +
                        ",restored=" + restoreVolume);
        finishSuccessfully();
    }

    private static int parseVolume(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0 || parsed > 255) {
            throw new IllegalArgumentException("Device volumes must be between 0 and 255");
        }
        return parsed;
    }

    private static Context getSystemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    private static void initializeBluetoothFramework() throws Exception {
        Class<?> initializer = Class.forName(
                "android.bluetooth.BluetoothFrameworkInitializer");
        Method getManager = initializer.getDeclaredMethod("getBluetoothServiceManager");
        getManager.setAccessible(true);
        if (getManager.invoke(null) != null) return;

        Class<?> managerClass = Class.forName("android.os.BluetoothServiceManager");
        Object manager = managerClass.getDeclaredConstructor().newInstance();
        Method setManager = initializer.getDeclaredMethod(
                "setBluetoothServiceManager", managerClass);
        setManager.setAccessible(true);
        setManager.invoke(null, manager);
    }

    private static Object invoke(
            Object target,
            String name,
            Class<?>[] parameterTypes,
            Object... args
    ) throws Exception {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw error;
        }
    }

    private static synchronized void finishSuccessfully() {
        if (finished) return;
        finished = true;
        System.out.flush();
        closeProxy();
        System.exit(0);
    }

    private static synchronized void finishWithError(String message) {
        if (finished) return;
        finished = true;
        System.out.println("error=" + message);
        System.out.flush();
        closeProxy();
        System.exit(1);
    }

    private static void closeProxy() {
        if (adapter != null && proxy != null) {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
            proxy = null;
        }
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
                ? error.getCause() : error;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() +
                (message == null || message.isEmpty() ? "" : ":" + message);
    }
}
