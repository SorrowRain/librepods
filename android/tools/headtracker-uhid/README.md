# LibrePods UHID head-tracker helper

This native helper creates an Android Head Tracker HID v1 device through
`/dev/uhid`. LibrePods starts it through root and streams decoded AirPods poses
to its standard input.

The program:

- exposes `#AndroidHeadTracker#1.0` with the standard Android HID descriptor;
- associates the sensor UUID with a Bluetooth audio device MAC;
- handles feature report reads and the reporting, power, and interval controls;
- emits a slow synthetic yaw pose when Android enables the sensor.

Build for the connected arm64 Android device:

```sh
NDK=/path/to/android-ndk
"$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android33-clang++" \
  -std=c++20 -O2 -Wall -Wextra -Werror -static-libstdc++ \
  headtracker_uhid.cpp -o /tmp/headtracker_uhid
```

Run temporarily as root:

```sh
adb push /tmp/headtracker_uhid /data/local/tmp/headtracker_uhid
adb shell su -c 'chmod 0755 /data/local/tmp/headtracker_uhid'
adb shell su -c '/data/local/tmp/headtracker_uhid --mac XX:XX:XX:XX:XX:XX'
```

The default mode emits a synthetic yaw signal. For an AACP bridge, pass
`--stdin` and stream one pose per line:

```text
rx ry rz vx vy vz [discontinuity_counter]
```

Rotation and angular-velocity vectors use radians and radians per second,
respectively. The optional discontinuity counter is an unsigned byte. Input is
forwarded only while Android has powered and enabled the HID sensor.

While it runs, `dumpsys sensorservice` should list an
`android.sensor.head_tracker(37)` device. When the matching Bluetooth headset is
the active spatial-audio output and head tracking is enabled, `dumpsys audio`
should show a valid `HeadSensorHandle` and `RELATIVE_WORLD` as the actual mode.

Stopping the process destroys the virtual HID device; it does not modify the
system partition.

## Verified platform path

The helper has been verified on Android 16 / HyperOS 3 with an AirPods A2DP
route. SensorService registered the dynamic `android.sensor.head_tracker`
device, AudioService associated its UUID with the AirPods Bluetooth address,
and Spatializer reached `HEAD_TRACKING_MODE_RELATIVE_WORLD` while consuming
pose reports. Root access remains required to create the UHID device.
