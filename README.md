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
RCON (`save-off`, `save-all flush`, then `save-on` again, also on errors), copies the world (plus `plugins/SBackups`)
with `rsync` into a local staging folder, and then backs that up with [restic](https://restic.net) to a repository on
the share. Writes are only paused during the local `rsync` (seconds), not while data goes to the NAS. restic is
incremental and deduplicated: unchanged region files cost nothing, so even hundreds of snapshots stay close to the
size of the world. Default retention: 48 hourly, 14 daily, 8 weekly, 6 monthly snapshots (`KEEP_*`). The 04:xx run
also verifies the repository (`restic check`, 2% of the data).
It refuses to run when the share is not mounted (marker file), so a dropped NAS can never fill the local disk.

restic **always encrypts** the repository with the password in `/etc/sbackups/restic-password`. Keep a copy of that
password somewhere safe (password manager): without it the backups cannot be read. Restoring gives you a plain world
folder you can copy to any server, see "Restore" below.

Setup (server in `/opt/minecraft`, run as user `minecraft`, NAS share via SMB):

1. `apt install restic rsync python3` (and `cifs-utils` wherever the share is mounted, see 4).
2. `server.properties`: `enable-rcon=true`, a long random `rcon.password`, then restart the server. RCON listens on
   all interfaces by default, so do not forward/open port 25575 in the firewall.
3. Credentials for the share, readable by root only (`/etc/sbackups/smb-credentials`, mode 600, on the machine that
   mounts it):

       username=<nas user>
       password=<password>

4. Mount the share at `/mnt/nas-minecraft`, then `touch /mnt/nas-minecraft/.sbackups-target` once (the script
   refuses to run without this marker, so an unmounted share can never fill the local disk).
   - **Normal VM / host:** `/etc/fstab`

         //10.5.1.10/<share> /mnt/nas-minecraft cifs credentials=/etc/sbackups/smb-credentials,uid=minecraft,gid=minecraft,file_mode=0640,dir_mode=0750,vers=3.0,_netdev,nofail 0 0

   - **Proxmox LXC container:** containers cannot mount CIFS themselves (`mount error(1): Operation not permitted`).
     Mount on the **Proxmox host** and bind-mount into the container. On the host, `apt install cifs-utils`, then
     `/etc/fstab`:

         //10.5.1.10/<share> /mnt/nas-minecraft cifs credentials=/etc/sbackups/smb-credentials,uid=101000,gid=101000,noperm,file_mode=0660,dir_mode=0770,vers=3.0,_netdev,nofail 0 0

     `noperm` makes the host skip local permission checks (the NAS still enforces the login), which avoids uid
     mapping trouble between host and container. `101000` = 100000 + the uid of `minecraft` inside an unprivileged container (`id minecraft`, usually 1000); for a
     privileged container use the plain uid. Then `mkdir -p /mnt/nas-minecraft && mount /mnt/nas-minecraft`,
     create the marker, and add the mount point to the container (`<CTID>` = container id), then restart it:

         pct set <CTID> -mp0 /mnt/nas-minecraft,mp=/mnt/nas-minecraft
         pct reboot <CTID>

     Make sure the share is mounted on the host before the container starts (otherwise the marker check stops the
     backup, which is the intended failure mode).
5. Inside the container, create the restic password (`install -m 600 -o minecraft -g minecraft /dev/null
   /etc/sbackups/restic-password && openssl rand -base64 32 > /etc/sbackups/restic-password`), then install and enable:

       install -m 755 scripts/world-backup.sh /usr/local/bin/sbackups-world-backup
       install -m 755 scripts/world-restore.sh /usr/local/bin/sbackups-world-restore
       install -m 644 scripts/systemd/sbackups-world.* /etc/systemd/system/
       systemctl daemon-reload && systemctl enable --now sbackups-world.timer
       systemctl start sbackups-world.service && journalctl -u sbackups-world -e   # first run by hand

Defaults can be overridden in `/etc/sbackups/world-backup.conf` (`MC_DIR`, `DEST_MOUNT`, `STAGE_DIR`, `KEEP_*`, ...,
see the header of the script). The first run creates the restic repository in `/mnt/nas-minecraft/restic-world`.
The staging folder (`/var/tmp/sbackups-stage`) needs about the size of the world on the local disk.

### Restore
    sudo -u minecraft sbackups-world-restore                       # list snapshots
    sudo -u minecraft sbackups-world-restore /srv/restored latest  # or a snapshot id

`/srv/restored/world` is then an ordinary world folder: copy it to another server (set `level-name` to match, or
rename the folder) and start it. Restoring only reads the repository, so it is safe while the server runs; do not
restore on top of a live world. Without these scripts: `restic -r /mnt/nas-minecraft/restic-world
--password-file /etc/sbackups/restic-password restore latest:/var/tmp/sbackups-stage --target /srv/restored`.
A backup on a NAS in the same house is still not off-site.

### From the web interface
Set `world.enabled: true` in `config.yml` (and `/sbackup reload` or restart); the scripts must be installed as above and
runnable by the server user. A **World** button appears above the player list: it shows the snapshots, has **Back up
now** and **Restore…** per snapshot, and the output of the last job. One job runs at a time, and the backup script holds a
lock, so a click can never overlap the timer. A restore goes into a new folder `world.restore-dir/<time>-<snapshot>`
(default `/srv/restored`) and never touches the live world; to roll back, stop the server and copy that folder over the
world by hand.
