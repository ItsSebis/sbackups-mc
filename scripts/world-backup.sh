#!/usr/bin/env bash
# Consistent world backup of a running Paper server, meant for cron. Does not touch the server's tick thread
# for longer than the save-all flush. Commands go to the server through its console.in FIFO (see start.sh).
#
#   MC_DIR   server directory                (default ~/mc-server)
#   DEST     where archives are stored       (default ~/mc-backups/world)
#   LEVEL    world folder name               (default: level-name from server.properties)
#
# Retention: every archive of the last 2 days, plus the one taken in the 04:00 hour for 14 days.
# Example cron (hourly, quietly):  7 * * * * /home/sebi/Documents/Code/sbackups/scripts/world-backup.sh
set -euo pipefail

MC_DIR="${MC_DIR:-$HOME/mc-server}"
DEST="${DEST:-$HOME/mc-backups/world}"
LEVEL="${LEVEL:-$(sed -n 's/^level-name=//p' "$MC_DIR/server.properties" | head -1)}"
LEVEL="${LEVEL:-world}"
FIFO="$MC_DIR/console.in"
LOG="$MC_DIR/server.log"

mkdir -p "$DEST"
stamp="$(date +%Y%m%d-%H%M)"
out="$DEST/$LEVEL-$stamp.tar.zst"
tmp="$out.part"

running=0
if pgrep -f "paper.jar" >/dev/null && [[ -p "$FIFO" ]]; then running=1; fi

send() { timeout 5 bash -c 'echo "$1" > "$2"' _ "$1" "$FIFO"; }

if (( running )); then
  # Whatever happens below, saving must be switched back on.
  trap 'send save-on || true' EXIT
  offset=$(stat -c %s "$LOG")
  send save-off
  send "save-all flush"
  for _ in $(seq 1 120); do
    if tail -c +"$((offset + 1))" "$LOG" | grep -q "Saved the game"; then saved=1; break; fi
    sleep 1
  done
  [[ "${saved:-0}" == 1 ]] || { echo "server did not confirm the save in 120s, aborting" >&2; exit 1; }
else
  echo "server not running, archiving without save-off" >&2
fi

tar -C "$MC_DIR" -cf - "$LEVEL" "plugins/SBackups" 2>/dev/null | zstd -T0 -3 -q -o "$tmp" -f \
  || { rm -f "$tmp"; echo "archive failed" >&2; exit 1; }
mv "$tmp" "$out"
echo "wrote $out ($(du -h "$out" | cut -f1))"

# Prune: older than 2 days -> keep only the 04:xx archive of each day; older than 14 days -> delete.
find "$DEST" -name "$LEVEL-*.tar.zst" -mtime +14 -delete
find "$DEST" -name "$LEVEL-*.tar.zst" -mtime +2 ! -name "$LEVEL-*-04??.tar.zst" -delete
