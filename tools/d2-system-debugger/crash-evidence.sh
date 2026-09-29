#!/system/bin/sh
# Read-only bounded forensic snapshot. Missing/denied sources are recorded.
umask 077
out=$1
case "$out" in /data/adb/d2-system-debug/*) ;; *) exit 1;; esac
mkdir -p "$out" || exit 1
{
  echo "Collected: $(date) uptime=$(cat /proc/uptime)"
  echo 'Each command is time/size bounded; output may be truncated.'
} > "$out/coverage.txt"
cap() {
  dest=$1; shift
  timeout 20 "$@" 2>&1 | head -c 4194304 > "$out/$dest.tmp"
  mv -f "$out/$dest.tmp" "$out/$dest"
}
cap crash-buffer.txt logcat -b crash -d -v threadtime
cap dropbox.txt dumpsys dropbox --print
cap activity-exit-info.txt dumpsys activity exit-info
cap last-anr.txt dumpsys activity lastanr
cap kernel.txt dmesg
# Copy newest readable reports, never entire /data or application files.
# At most 6 files x 512 KiB per source. Keep original filenames and provenance.
for kind in tombstones anr pstore; do
  case "$kind" in
    tombstones) src=/data/tombstones;;
    anr) src=/data/anr;;
    pstore) src=/sys/fs/pstore;;
  esac
  mkdir -p "$out/$kind.new"
  ls -lt "$src" > "$out/$kind-index.txt" 2>&1
  n=0
  for f in $(ls -t "$src"/* 2>/dev/null); do
    [ -f "$f" ] || continue
    [ -r "$f" ] || continue
    case "$f" in *.pb) continue;; esac
    n=$((n + 1)); [ "$n" -le 6 ] || break
    head -c 524288 "$f" > "$out/$kind.new/${f##*/}" 2>> "$out/coverage.txt"
  done
  echo "$kind: copied $((n > 6 ? 6 : n)) readable non-protobuf files; up to 512 KiB each" >> "$out/coverage.txt"
  rm -rf "$out/$kind"
  mv "$out/$kind.new" "$out/$kind"
done
if [ -r /proc/last_kmsg ]; then cap last-kmsg.txt cat /proc/last_kmsg; fi
