#!/system/bin/sh
MODDIR=${0%/*}
# Background immediately so other module services are not held up.
sh "$MODDIR/crash-watch.sh" >/dev/null 2>&1 &
sh "$MODDIR/collect.sh" boot >/dev/null 2>&1 &
