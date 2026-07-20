#!/system/bin/sh

# The default module permission pass treats regular files as 0644. This helper
# is launched by LibrePods through root and must remain executable.
set_perm "$MODPATH/system/bin/librepods-headtracker" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
