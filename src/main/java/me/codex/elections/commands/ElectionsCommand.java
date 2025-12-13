package me.codex.elections.commands;

import me.codex.elections.service.ElectionManager;
import me.codex.elections.util.DurationUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class ElectionsCommand implements CommandExecutor, TabCompleter {

    private final ElectionManager manager;

    public ElectionsCommand(ElectionManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> {
                if (!sender.hasPermission("elections.admin")) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " create <role> <duration>");
                    sender.sendMessage(ChatColor.GRAY + "Example: /" + label + " create Judge 2d6h");
                    return true;
                }
                String durationArg = args[args.length - 1];
                String role = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));
                Optional<Duration> duration = DurationUtil.parseDuration(durationArg);
                if (duration.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "Invalid duration. Use formats like 1d2h30m or 45m.");
                    return true;
                }
                ElectionManager.ActionResult result = manager.createElection(role, duration.get());
                sender.sendMessage(result.message());
                return true;
            }
            case "nominate" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " nominate <player>");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                ElectionManager.ActionResult result = manager.nominate(sender, target);
                sender.sendMessage(result.message());
                return true;
            }
            case "rig" -> {
                if (!sender.hasPermission("elections.admin")) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " rig <player>");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                ElectionManager.ActionResult result = manager.rigVotes(target);
                sender.sendMessage(result.message());
                return true;
            }
            case "end" -> {
                if (!sender.hasPermission("elections.admin")) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
                    return true;
                }
                ElectionManager.ActionResult result = manager.endElection();
                sender.sendMessage(result.message());
                return true;
            }
            case "status" -> {
                ElectionManager.ActionResult status = manager.status();
                sender.sendMessage(status.message());
                return true;
            }
            case "platform" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " platform set <your plan> OR /" + label + " platform <nominee>");
                    return true;
                }
                if (args[1].equalsIgnoreCase("set")) {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(ChatColor.RED + "Only players can set their platform.");
                        return true;
                    }
                    if (args.length < 3) {
                        sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " platform set <your plan>");
                        return true;
                    }
                    String platform = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    ElectionManager.ActionResult result = manager.setPlatform(player, player, platform);
                    sender.sendMessage(result.message());
                    return true;
                }

                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                ElectionManager.ActionResult result = manager.viewPlatform(target);
                sender.sendMessage(result.message());
                return true;
            }
            default -> {
                sendHelp(sender);
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        lines.add(color("&bElections commands:"));
        lines.add(color("&7/elections help &f- Show this help"));
        lines.add(color("&7/elections status &f- View current election info"));
        lines.add(color("&7/elections nominate <player> &f- Nominate someone (not yourself)"));
        lines.add(color("&7/elections platform <player> &f- View a nominee's platform"));
        lines.add(color("&7/elections platform set <text> &f- (Nominees) Set your platform"));
        lines.add(color("&7/vote <player> &f- Vote for a nominee"));
        if (sender.hasPermission("elections.admin")) {
            lines.add(color("&7/elections create <role> <duration> &f- Start a new election"));
            lines.add(color("&7/elections rig <player> &f- Force all votes to this player"));
            lines.add(color("&7/elections end &f- Clear the election & scoreboard"));
        }
        sender.sendMessage(lines.toArray(new String[0]));
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(Arrays.asList("help", "status", "nominate", "platform"));
            if (sender.hasPermission("elections.admin")) {
                base.addAll(Arrays.asList("create", "rig", "end"));
            }
            return base.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("nominate") || sub.equals("rig")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (sub.equals("platform")) {
                List<String> suggestions = new ArrayList<>();
                suggestions.add("set");
                manager.getCurrentElection().ifPresent(election -> election.getNominees().forEach(uuid -> {
                    String name = Bukkit.getOfflinePlayer(uuid).getName();
                    if (name != null) suggestions.add(name);
                }));
                return suggestions.stream()
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        return List.of();
    }
}
