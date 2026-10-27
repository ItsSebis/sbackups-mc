#!/usr/bin/env bash
# Restores a world backup made by world-backup.sh into a plain folder you can copy to any server.
#
#   world-restore.sh                       list the snapshots
#   world-restore.sh --json                the snapshots as restic's JSON (used by the web interface)
#   world-restore.sh <target-dir> [id]     restore snapshot <id> (default: latest); the world folder (and
#                                          plugins/SBackups) appear directly inside <target-dir>
#
# Uses the same settings as world-backup.sh (/etc/sbackups/world-backup.conf). Restoring only reads the repository,
# so it is safe to run while the server is running; never restore on top of a live world.
set -euo pipefail

CONF="${SBACKUPS_CONF:-/etc/sbackups/world-backup.conf}"
# shellcheck disable=SC1090
[[ -r "$CONF" ]] && source "$CONF"
DEST_MOUNT="${DEST_MOUNT:-/mnt/nas-minecraft}"
STAGE_DIR="${STAGE_DIR:-/var/tmp/sbackups-stage}"
export RESTIC_REPOSITORY="${REPO:-${RESTIC_REPOSITORY:-$DEST_MOUNT/restic-world}}"
export RESTIC_PASSWORD_FILE="${RESTIC_PASSWORD_FILE:-/etc/sbackups/restic-password}"

if [[ "${1:-}" == --json ]]; then
  restic snapshots --tag world --json
  exit 0
fi
if [[ $# -eq 0 ]]; then
  restic snapshots --tag world
  exit 0
fi
target="$1"; snap="${2:-latest}"
if [[ -e "$target" && -n "$(ls -A "$target" 2>/dev/null)" ]]; then
  echo "world-restore: $target exists and is not empty, refusing" >&2
  exit 1
fi
mkdir -p "$target"
restic restore "$snap:$STAGE_DIR" --target "$target"
echo "restored $snap to $target"
ls "$target"
