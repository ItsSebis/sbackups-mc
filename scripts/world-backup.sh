#!/usr/bin/env bash
# Consistent, incremental world backup of a running Paper server into a restic repository (e.g. on a NAS share).
# Run it as the user that owns the server files, from a systemd timer or cron. See README.md ("World backups").
#
# How: save-off + save-all flush over RCON -> rsync the world into a local staging copy (seconds, incremental) ->
# save-on again -> restic backs the staging copy up. Saving is only paused during the local rsync, never while
# data is sent to the NAS. restic stores only what changed (deduplicated), so many snapshots stay small.
#
# Settings come from the environment or from /etc/sbackups/world-backup.conf (plain KEY=value shell lines):
#   MC_DIR        server directory                       (default /opt/minecraft)
#   LEVEL         world folder                           (default: level-name from server.properties, else world)
#   DEST_MOUNT    mount point of the share               (default /mnt/nas-minecraft)
#   REPO          restic repository                      (default $DEST_MOUNT/restic-world, created on first run)
#   RESTIC_PASSWORD_FILE  file with the repository password  (default /etc/sbackups/restic-password)
#   STAGE_DIR     local staging copy of the world, needs about the size of the world   (default /var/tmp/sbackups-stage)
#   REQUIRE_MOUNT 1 = abort unless DEST_MOUNT is a mount point AND contains the marker file below, so a missing
#                 or dropped share never makes backups land on the local disk   (default 1)
#   MARKER_FILE   file that exists on the NAS share only   (default $DEST_MOUNT/.sbackups-target; create it once
#                 with `touch` while the share is mounted)
#   RCON_HOST / RCON_PORT / RCON_PASSWORD   default 127.0.0.1 / rcon.port / rcon.password from server.properties
#   KEEP_HOURLY / KEEP_DAILY / KEEP_WEEKLY / KEEP_MONTHLY   snapshots to keep   (default 48 / 14 / 8 / 6)
set -euo pipefail

CONF="${SBACKUPS_CONF:-/etc/sbackups/world-backup.conf}"
# shellcheck disable=SC1090
[[ -r "$CONF" ]] && source "$CONF"

MC_DIR="${MC_DIR:-/opt/minecraft}"
PROPS="$MC_DIR/server.properties"
prop() { sed -n "s/^$1=//p" "$PROPS" 2>/dev/null | head -1; }

LEVEL="${LEVEL:-$(prop level-name)}"; LEVEL="${LEVEL:-world}"
DEST_MOUNT="${DEST_MOUNT:-/mnt/nas-minecraft}"
REQUIRE_MOUNT="${REQUIRE_MOUNT:-1}"
MARKER_FILE="${MARKER_FILE:-$DEST_MOUNT/.sbackups-target}"
STAGE_DIR="${STAGE_DIR:-/var/tmp/sbackups-stage}"
RCON_HOST="${RCON_HOST:-127.0.0.1}"
RCON_PORT="${RCON_PORT:-$(prop rcon.port)}"; RCON_PORT="${RCON_PORT:-25575}"
RCON_PASSWORD="${RCON_PASSWORD:-$(prop rcon.password)}"
KEEP_HOURLY="${KEEP_HOURLY:-48}"
KEEP_DAILY="${KEEP_DAILY:-14}"
KEEP_WEEKLY="${KEEP_WEEKLY:-8}"
KEEP_MONTHLY="${KEEP_MONTHLY:-6}"
export RESTIC_REPOSITORY="${REPO:-${RESTIC_REPOSITORY:-$DEST_MOUNT/restic-world}}"
export RESTIC_PASSWORD_FILE="${RESTIC_PASSWORD_FILE:-/etc/sbackups/restic-password}"
HOST_TAG="minecraft" # fixed, so snapshots stay one series even if the machine is renamed

die() { echo "world-backup: $*" >&2; exit 1; }

[[ -d "$MC_DIR/$LEVEL" ]] || die "no world folder $MC_DIR/$LEVEL"
[[ -n "$RCON_PASSWORD" ]] || die "no RCON password: set enable-rcon=true, rcon.password in server.properties (or RCON_PASSWORD)"
command -v restic >/dev/null || die "restic is not installed (apt install restic)"
command -v rsync >/dev/null || die "rsync is not installed (apt install rsync)"
[[ -r "$RESTIC_PASSWORD_FILE" ]] || die "cannot read the restic password file $RESTIC_PASSWORD_FILE"
if [[ "$REQUIRE_MOUNT" == 1 ]]; then
  mountpoint -q "$DEST_MOUNT" || die "$DEST_MOUNT is not a mount point, refusing to write to the local disk"
  [[ -e "$MARKER_FILE" ]] || die "$MARKER_FILE not found: share not mounted (or marker not created), refusing to write"
fi
mkdir -p "$RESTIC_REPOSITORY" 2>/dev/null || die "cannot write to $RESTIC_REPOSITORY as $(id -un) (uid $(id -u)): the share is mounted but not writable for this user, see README (LXC: uid mapping / noperm)"

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
trap resume_saving EXIT

# rsync exit 24 = a file vanished while copying, harmless with saving off.
sync_dir() { local rc=0; rsync -a --delete "$1/" "$2/" || rc=$?; (( rc == 0 || rc == 24 )) || die "rsync of $1 failed ($rc)"; }

mkdir -p "$STAGE_DIR/plugins"
rcon "say [backup] world backup starting, expect a short lag" >/dev/null || true
rcon save-off >/dev/null || die "cannot reach the server over RCON ($RCON_HOST:$RCON_PORT), is enable-rcon=true set?"
saving_off=1
reply="$(rcon "save-all flush")"
[[ "$reply" == *"Saved the game"* ]] || die "server did not confirm the save: $reply"
sync_dir "$MC_DIR/$LEVEL" "$STAGE_DIR/$LEVEL"
[[ -d "$MC_DIR/plugins/SBackups" ]] && sync_dir "$MC_DIR/plugins/SBackups" "$STAGE_DIR/plugins/SBackups"
resume_saving
rcon "say [backup] done" >/dev/null || true

restic cat config >/dev/null 2>&1 || { echo "creating restic repository $RESTIC_REPOSITORY"; mkdir -p "$RESTIC_REPOSITORY"; restic init; }
restic backup --host "$HOST_TAG" --tag world --quiet "$STAGE_DIR"
echo "backup written to $RESTIC_REPOSITORY"

restic forget --host "$HOST_TAG" --tag world --prune --quiet \
  --keep-hourly "$KEEP_HOURLY" --keep-daily "$KEEP_DAILY" --keep-weekly "$KEEP_WEEKLY" --keep-monthly "$KEEP_MONTHLY"

# Once a day (the 04:xx run) verify the repository structure and re-read a small sample of the data.
if [[ "$(date +%H)" == 04 ]]; then restic check --read-data-subset=2% --quiet; fi
