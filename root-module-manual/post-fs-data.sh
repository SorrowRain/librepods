#!/system/bin/sh

MODDIR=${0%/*}
WORKDIR=/dev/librepods-overlay
LOGFILE=/data/adb/librepods-mount.log

exec >"$LOGFILE" 2>&1

mount_layer() {
    relative_path="$1"
    target_path="/system/$relative_path"
    source_path="$MODDIR/system/$relative_path"
    layer_path="$WORKDIR/$relative_path"

    if [ ! -d "$source_path" ] || [ ! -d "$target_path" ]; then
        echo "skip $relative_path: source or target missing"
        return 1
    fi

    mkdir -p "$layer_path"
    /system/bin/cp -af --preserve=all "$source_path/." "$layer_path/"
    mount -t overlay -o "lowerdir=$layer_path:$target_path" overlay "$target_path"
}

rm -rf "$WORKDIR"
mkdir -p "$WORKDIR"

mount_layer priv-app
mount_layer etc/permissions
mount_layer bin

test -f /system/priv-app/LibrePods/LibrePods.apk && echo "LibrePods APK mounted"
test -f /system/etc/permissions/privapp-permissions-librepods.xml && \
    echo "LibrePods permission allowlist mounted"
test -x /system/bin/librepods-headtracker && echo "LibrePods headtracker mounted"
