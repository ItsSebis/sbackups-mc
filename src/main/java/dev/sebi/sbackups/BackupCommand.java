package dev.sebi.sbackups;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

final class BackupCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private final SBackupsPlugin plugin;
    private final BackupService service;

    BackupCommand(SBackupsPlugin plugin, BackupService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) return false;
        switch (args[0].toLowerCase()) {
            case "now" -> now(sender, args);
            case "list" -> list(sender, args);
            case "restore" -> restore(sender, args);
            case "status" -> sender.sendMessage("§e" + service.timingSummary());
            case "reload" -> {
                plugin.reload();
                sender.sendMessage("§aSBackups config reloaded.");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void now(CommandSender sender, String[] args) {
        List<Player> targets = new ArrayList<>();
        if (args.length < 2 || args[1].equals("*")) {
            if (args.length < 2 && sender instanceof Player p) targets.add(p);
            else targets.addAll(Bukkit.getOnlinePlayers());
        } else {
            Player p = Bukkit.getPlayerExact(args[1]);
            if (p == null) {
                sender.sendMessage("§c" + args[1] + " is not online.");
                return;
            }
            targets.add(p);
        }
        for (Player p : targets) {
            service.snapshotMain(p, Reason.MANUAL).whenComplete((m, err) -> sender.sendMessage(err != null
                    ? "§cBackup of " + p.getName() + " failed, see console."
                    : "§aBacked up " + p.getName() + " (" + m.map(Meta::id).orElse("unchanged") + ")"));
        }
    }

    private OfflinePlayer target(CommandSender sender, String name) {
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        if (op == null) sender.sendMessage("§cUnknown player " + name + " (must have joined before).");
        return op;
    }

    private void list(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /sbackup list <player>");
            return;
        }
        OfflinePlayer op = target(sender, args[1]);
        if (op == null) return;
        List<Meta> all = service.list(op.getUniqueId());
        sender.sendMessage("§e" + all.size() + " backups of " + args[1] + " (newest first, showing 15):");
        for (Meta m : all.stream().limit(15).toList()) {
            sender.sendMessage("§7" + TIME.format(Instant.ofEpochMilli(m.time())) + " §f" + m.id()
                    + " §7" + m.itemCount() + " items");
        }
    }

    private void restore(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /sbackup restore <player> <id>  (sections from config restore.*)");
            return;
        }
        OfflinePlayer op = target(sender, args[1]);
        if (op == null) return;
        UUID uuid = op.getUniqueId();
        if (service.find(uuid, args[2]).isEmpty()) {
            sender.sendMessage("§cNo such backup. Use /sbackup list " + args[1]);
            return;
        }
        Sections sections = service.settings().restoreDefaults();
        try {
            Player online = op.getPlayer();
            if (online != null) {
                service.restoreMain(online, service.read(uuid, args[2]), sections);
                sender.sendMessage("§aRestored " + args[2] + " to " + online.getName()
                        + " (previous state saved as pre-restore).");
            } else {
                service.restoreLater(uuid, args[2], sections);
                sender.sendMessage("§aQueued; applied when " + args[1] + " next joins.");
            }
        } catch (IOException e) {
            sender.sendMessage("§cRestore failed: " + e.getMessage());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) return List.of("now", "list", "restore", "status", "reload").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("restore")) {
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (op != null) return service.list(op.getUniqueId()).stream().limit(20).map(Meta::id).toList();
        }
        return List.of();
    }
}
