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
items needs the player online. The page updates live (server-sent events, no polling).

## Item icons
`src/main/resources/web/icons/` holds item textures extracted from the Minecraft 26.1 assets (via the
[PrismarineJS/minecraft-assets](https://github.com/PrismarineJS/minecraft-assets) package). The textures are
© Mojang Studios and only used to display items in the admin page.

## Commands (permission `sbackups.admin`, default op)
`/sbackup now [player|*]`, `/sbackup list <player>`, `/sbackup restore <player> <id>`, `/sbackup status` (main-thread cost of snapshots), `/sbackup reload`

## Build
    export JAVA_HOME=~/mc-tools/jdk-25*; ~/mc-tools/apache-maven-3.9.16/bin/mvn package
    cp target/SBackups.jar ~/mc-server/plugins/

## World backups
Deliberately not part of the plugin: `scripts/world-backup.sh` runs from a systemd timer, talks to the server over
RCON (`save-off`, `save-all flush`, then `save-on` again, also on errors), archives the world plus `plugins/SBackups`
to a local staging dir, and only then copies the `.tar.zst` to the share. Writes are therefore only paused while
`tar` runs, not during the slow network copy. Retention: every archive for 2 days, then the 04:xx one of each day for
14 days. It refuses to run when the share is not mounted, so a dropped NAS can never fill the local disk.

Setup on a Debian VM (server in `/opt/minecraft`, run as user `minecraft`):

1. `apt install cifs-utils zstd python3`
2. `server.properties`: `enable-rcon=true`, a long random `rcon.password`, then restart the server. RCON listens on
   all interfaces by default, so do not forward/open port 25575 in the firewall.
3. Credentials, readable by root only (`/etc/sbackups/smb-credentials`, mode 600):

       username=<nas user>
       password=<password>

4. `/etc/fstab` (share name is an example):

       //10.5.1.10/minecraft-backup /mnt/nas-minecraft cifs credentials=/etc/sbackups/smb-credentials,uid=minecraft,gid=minecraft,file_mode=0640,dir_mode=0750,vers=3.0,_netdev,nofail,x-systemd.automount,x-systemd.idle-timeout=60 0 0

   then `mkdir -p /mnt/nas-minecraft && systemctl daemon-reload && ls /mnt/nas-minecraft`.
5. Install and enable:

       install -m 755 scripts/world-backup.sh /usr/local/bin/sbackups-world-backup
       install -m 644 scripts/systemd/sbackups-world.* /etc/systemd/system/
       systemctl daemon-reload && systemctl enable --now sbackups-world.timer
       systemctl start sbackups-world.service && journalctl -u sbackups-world -e   # first run by hand

Defaults can be overridden in `/etc/sbackups/world-backup.conf` (`MC_DIR`, `DEST_MOUNT`, `STAGE_DIR`, `KEEP_ALL_DAYS`, ...,
see the header of the script). Restore: `zstd -dc world-<stamp>.tar.zst | tar -x -C /opt/minecraft` with the server
stopped. A backup on a NAS in the same house is still not off-site.
