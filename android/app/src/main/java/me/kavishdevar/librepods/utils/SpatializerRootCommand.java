/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package me.kavishdevar.librepods.utils;

import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Root app_process entry point for Android's hidden Spatializer controls. */
public final class SpatializerRootCommand {
    private SpatializerRootCommand() {}

    public static void main(String[] args) {
        try {
            Object audioService = getAudioService();
            if (args.length > 0) {
                boolean spatializerEnabled = Integer.parseInt(args[0]) != 0;
                int headTrackingMode = args.length > 1 ? Integer.parseInt(args[1]) : -1;

                // Disable head tracking before disabling the effect so vendor
                // implementations cannot retain a stale world-relative mode.
                if (!spatializerEnabled) {
                    invoke(
                            audioService,
                            "setDesiredHeadTrackingMode",
                            new Class<?>[] {int.class},
                            -1);
                    invoke(
                            audioService,
                            "setSpatializerEnabled",
                            new Class<?>[] {boolean.class},
                            false);
                } else {
                    invoke(
                            audioService,
                            "setSpatializerEnabled",
                            new Class<?>[] {boolean.class},
                            true);
                    invoke(
                            audioService,
                            "setDesiredHeadTrackingMode",
                            new Class<?>[] {int.class},
                            headTrackingMode);
                }

                if (headTrackingMode == -1) {
                    // Some vendor implementations retain the last actual mode
                    // while the A2DP route remains active, even after the head
                    // tracker is removed. Desired=-1 is the authoritative stop.
                    Thread.sleep(300);
                } else {
                    for (int attempt = 0; attempt < 20; attempt++) {
                        int actual = (Integer) invoke(
                                audioService, "getActualHeadTrackingMode", new Class<?>[0]);
                        if (actual == headTrackingMode) {
                            break;
                        }
                        Thread.sleep(100);
                    }
                }
            }

            System.out.println("enabled=" + invoke(
                    audioService, "isSpatializerEnabled", new Class<?>[0]));
            System.out.println("desired=" + invoke(
                    audioService, "getDesiredHeadTrackingMode", new Class<?>[0]));
            System.out.println("actual=" + invoke(
                    audioService, "getActualHeadTrackingMode", new Class<?>[0]));
        } catch (Throwable error) {
            System.out.println("error=" + error.getClass().getSimpleName() + ":" + error.getMessage());
            System.exit(1);
        }
    }

    private static Object getAudioService() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, "audio");
        if (binder == null) {
            throw new IllegalStateException("AudioService binder is unavailable");
        }

        Class<?> stub = Class.forName("android.media.IAudioService$Stub");
        return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static Object invoke(
            Object target,
            String name,
            Class<?>[] parameterTypes,
            Object... args
    ) throws Exception {
        try {
            return target.getClass().getMethod(name, parameterTypes).invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw error;
        }
    }
}
