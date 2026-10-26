#!/usr/bin/env bash
# Consistent world backup of a running Paper server to a (network) share. Run it as the user that owns the server
# files, from a systemd timer or cron. See README.md ("World backups") for the setup.
#
# How: save-off + save-all flush over RCON -> tar the world to a local staging dir -> save-on again -> copy the
# archive to the share. Writes are only disabled while tar runs locally, not while the (slower) copy to the NAS runs.
#
# Settings come from the environment or from /etc/sbackups/world-backup.conf (plain KEY=value shell lines):
#   MC_DIR        server directory                       (default /opt/minecraft)
#   LEVEL         world folder                           (default: level-name from server.properties, else world)
#   DEST_MOUNT    mount point of the share               (default /mnt/nas-minecraft)
#   DEST          archive directory                      (default $DEST_MOUNT/world)
#   REQUIRE_MOUNT 1 = abort unless DEST_MOUNT is a mounted filesystem (so a dropped share never fills the local
#                 disk)                                  (default 1)
#   STAGE_DIR     local scratch space for one archive    (default /var/tmp)
#   RCON_HOST / RCON_PORT / RCON_PASSWORD   default 127.0.0.1 / rcon.port / rcon.password from server.properties
#   KEEP_ALL_DAYS keep every archive this long           (default 2)
#   KEEP_DAILY_DAYS keep one archive per day (the 04:xx one) this long   (default 14)
set -euo pipefail

CONF="${SBACKUPS_CONF:-/etc/sbackups/world-backup.conf}"
# shellcheck disable=SC1090
[[ -r "$CONF" ]] && source "$CONF"

MC_DIR="${MC_DIR:-/opt/minecraft}"
PROPS="$MC_DIR/server.properties"
prop() { sed -n "s/^$1=//p" "$PROPS" 2>/dev/null | head -1; }

LEVEL="${LEVEL:-$(prop level-name)}"; LEVEL="${LEVEL:-world}"
DEST_MOUNT="${DEST_MOUNT:-/mnt/nas-minecraft}"
DEST="${DEST:-$DEST_MOUNT/world}"
REQUIRE_MOUNT="${REQUIRE_MOUNT:-1}"
STAGE_DIR="${STAGE_DIR:-/var/tmp}"
RCON_HOST="${RCON_HOST:-127.0.0.1}"
RCON_PORT="${RCON_PORT:-$(prop rcon.port)}"; RCON_PORT="${RCON_PORT:-25575}"
RCON_PASSWORD="${RCON_PASSWORD:-$(prop rcon.password)}"
KEEP_ALL_DAYS="${KEEP_ALL_DAYS:-2}"
KEEP_DAILY_DAYS="${KEEP_DAILY_DAYS:-14}"

die() { echo "world-backup: $*" >&2; exit 1; }

[[ -d "$MC_DIR/$LEVEL" ]] || die "no world folder $MC_DIR/$LEVEL"
[[ -n "$RCON_PASSWORD" ]] || die "no RCON password: set enable-rcon=true, rcon.password in server.properties (or RCON_PASSWORD)"
if [[ "$REQUIRE_MOUNT" == 1 ]]; then
  mountpoint -q "$DEST_MOUNT" || die "$DEST_MOUNT is not mounted, refusing to write to the local disk"
fi
mkdir -p "$DEST"

# Minimal RCON client (python3 is part of a default Debian install). Prints the server's reply.
rcon() {
  RCON_HOST="$RCON_HOST" RCON_PORT="$RCON_PORT" RCON_PASSWORD="$RCON_PASSWORD" python3 - "$1" <<'PY'
import os, socket, struct, sys

def packet(rid, kind, body):
    data = struct.pack('<ii', rid, kind) + body.encode() + b'\0\0'
    return struct.pack('<i', len(data)) + data

def read_exact(s, n):
    buf = b''
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise SystemExit('rcon: connection closed')
        buf += chunk
    return buf

def read_packet(s):
    (length,) = struct.unpack('<i', read_exact(s, 4))
    data = read_exact(s, length)
    rid, kind = struct.unpack('<ii', data[:8])
    return rid, data[8:-2].decode(errors='replace')

try:
    s = socket.create_connection((os.environ['RCON_HOST'], int(os.environ['RCON_PORT'])), timeout=10)
except OSError as e:
    raise SystemExit('rcon: ' + str(e))
s.settimeout(300)  # a flush of a big world can take a while
s.sendall(packet(1, 3, os.environ['RCON_PASSWORD']))
if read_packet(s)[0] == -1:
    raise SystemExit('rcon: wrong password')
s.sendall(packet(2, 2, sys.argv[1]))
print(read_packet(s)[1])
PY
}

saving_off=0
resume_saving() { if (( saving_off )); then rcon save-on >/dev/null && saving_off=0 || echo "world-backup: WARNING: save-on failed, run it by hand!" >&2; fi; }
work="$(mktemp -d "$STAGE_DIR/sbackups.XXXXXX")"
cleanup() { resume_saving; rm -rf "$work"; }
trap cleanup EXIT

stamp="$(date +%Y%m%d-%H%M)"
name="$LEVEL-$stamp.tar.zst"

rcon "say [backup] world backup starting, expect a short lag" >/dev/null || true
rcon save-off >/dev/null || die "cannot reach the server over RCON ($RCON_HOST:$RCON_PORT), is enable-rcon=true set?"
saving_off=1
reply="$(rcon "save-all flush")"
[[ "$reply" == *"Saved the game"* ]] || die "server did not confirm the save: $reply"

# tar exits 1 when a file changed while reading (harmless here, saving is off); only >1 is a real error.
set +e
tar -C "$MC_DIR" -cf - "$LEVEL" plugins/SBackups 2>/dev/null | zstd -T0 -3 -q -o "$work/$name"
codes=("${PIPESTATUS[@]}")
set -e
tar_rc=${codes[0]}; rc=${codes[1]}
resume_saving
(( rc == 0 && tar_rc <= 1 )) || die "archiving failed (tar $tar_rc, zstd $rc)"
zstd -tq "$work/$name" || die "archive is corrupt"
rcon "say [backup] done" >/dev/null || true

cp "$work/$name" "$DEST/$name.part"
mv "$DEST/$name.part" "$DEST/$name"
echo "wrote $DEST/$name ($(du -h "$DEST/$name" | cut -f1))"

# Retention: everything for KEEP_ALL_DAYS, then only the 04:xx archive of each day until KEEP_DAILY_DAYS.
find "$DEST" -maxdepth 1 -name "$LEVEL-*.tar.zst" -mtime "+$KEEP_DAILY_DAYS" -delete
find "$DEST" -maxdepth 1 -name "$LEVEL-*.tar.zst" -mtime "+$KEEP_ALL_DAYS" ! -name "$LEVEL-*-04??.tar.zst" -delete
find "$DEST" -maxdepth 1 -name "*.part" -mtime +1 -delete
