# SBackups

Paper 26.3 plugin: backs up player inventories and gives admins a web interface to browse and restore them.

## What is backed up
Per backup, as separate sections: **main inventory** (hotbar + storage), **armor** (boots/leggings/chestplate/helmet),
**offhand**, **ender chest** and **XP**. Triggers: join, quit, death, world change, every N minutes (online players),
manual (`/sbackup now`, web UI). Routine backups are skipped when nothing changed.

Retention is per player in three pools (routine / death / manual+pre-restore) plus an optional global size cap,
see `retention` in `config.yml`. Every restore first saves the current state as a `pre-restore` backup.

Files: `plugins/SBackups/data/<uuid>/<epochMillis>-<reason>.sbk` (gzip, item data in Paper's version-aware format).

## Web interface
`http://127.0.0.1:8124` by default (`web.bind-address`, `web.port`). A random token is generated on first start and
written to `web.token` in `config.yml`; log in with it. Keep it on localhost or behind a tunnel you trust: whoever has
the token can change player inventories. Restore for offline players is applied on their next join; giving single
items needs the player online.

## Commands (permission `sbackups.admin`, default op)
`/sbackup now [player|*]`, `/sbackup list <player>`, `/sbackup restore <player> <id>`, `/sbackup reload`

## Build
    export JAVA_HOME=~/mc-tools/jdk-25*; ~/mc-tools/apache-maven-3.9.16/bin/mvn package
    cp target/SBackups.jar ~/mc-server/plugins/

## World backups
Deliberately not part of the plugin: use `scripts/world-backup.sh` from cron. It sends `save-off` /
`save-all flush` through the server's `console.in`, archives the world plus `plugins/SBackups` as `.tar.zst`, always
runs `save-on` afterwards and prunes old archives. Example crontab line (hourly):

    7 * * * * /home/sebi/Documents/Code/sbackups/scripts/world-backup.sh >> ~/mc-backups/cron.log 2>&1

Copy `~/mc-backups` off the machine as well (rsync/rclone), a backup on the same disk is not a backup.
