package me.codex.elections.service;

import me.codex.elections.ElectionsPlugin;
import me.codex.elections.model.Election;
import me.codex.elections.util.DurationUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class ElectionManager {

    private final ElectionsPlugin plugin;
    private final ScoreboardService scoreboardService;
    private Election currentElection;
    private UUID lastWinnerId;
    private String lastRole;
    private int taskId = -1;

    public ElectionManager(ElectionsPlugin plugin, ScoreboardService scoreboardService) {
        this.plugin = plugin;
        this.scoreboardService = scoreboardService;
    }

    public Optional<Election> getCurrentElection() {
        return Optional.ofNullable(currentElection);
    }

    public void startTicking() {
        stopTicking();
        this.taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 20L, 20L);
    }

    public void stopTicking() {
        if (taskId != -1) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void tick() {
        if (currentElection == null) {
            return;
        }

        if (currentElection.isActive()) {
            if (currentElection.getType() == Election.Type.NO_CONFIDENCE) {
                int required = getRequiredNoConfidenceVotes();
                long votes = currentElection.getVoteCounts().values().stream().mapToLong(Long::longValue).sum();
                if (votes >= required) {
                    concludeNoConfidence(true, votes);
                    return;
                }
                if (Instant.now().isAfter(currentElection.getEndsAt())) {
                    concludeNoConfidence(false, votes);
                    return;
                }
            } else if (Instant.now().isAfter(currentElection.getEndsAt())) {
                concludeOrExtend();
            }
        }

        scoreboardService.updateAll(currentElection);
    }

    public ActionResult createElection(String role, Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return ActionResult.fail(color("&cDuration must be greater than zero."));
        }

        if (currentElection != null) {
            return ActionResult.fail(color("&cAn election already exists. Use /elections end first."));
        }

        Instant endsAt = Instant.now().plus(duration);
        currentElection = new Election(role, endsAt, Election.Type.REGULAR);
        scoreboardService.resetHidden();
        broadcast(msg("messages.created")
                .replace("%role%", role)
                .replace("%duration%", DurationUtil.format(duration)));
        scoreboardService.updateAll(currentElection);
        return ActionResult.ok(color("&aElection created for &f" + role));
    }

    public ActionResult nominate(CommandSender nominator, OfflinePlayer target) {
        if (currentElection == null) {
            return ActionResult.fail(msg("messages.no-election"));
        }
        if (!currentElection.isActive()) {
            return ActionResult.fail(color("&cThis election already ended. Use /elections end to clear it."));
        }
        if (nominator instanceof Player player && player.getUniqueId().equals(target.getUniqueId())) {
            return ActionResult.fail(msg("messages.cannot-self-nominate"));
        }
        if (currentElection.isNominee(target.getUniqueId())) {
            return ActionResult.fail(msg("messages.already-nominated"));
        }
        currentElection.addNominee(target.getUniqueId());
        broadcast(msg("messages.nomination-success")
                .replace("%target%", displayName(target))
                .replace("%role%", currentElection.getRole()));
        scoreboardService.updateAll(currentElection);
        return ActionResult.ok(color("&aNominated &f" + displayName(target)));
    }

    public ActionResult startNoConfidence(CommandSender sender, OfflinePlayer target) {
        if (currentElection != null) {
            return ActionResult.fail(color("&cAn election is already running. End it first."));
        }
        if (lastWinnerId == null || lastRole == null) {
            return ActionResult.fail(msg("messages.no-confidence-unavailable"));
        }
        if (!target.getUniqueId().equals(lastWinnerId)) {
            return ActionResult.fail(msg("messages.no-confidence-target").replace("%winner%", displayName(Bukkit.getOfflinePlayer(lastWinnerId))));
        }

        Duration duration = parseDurationOrDefault(plugin.getConfig().getString("no-confidence.duration", "24h"), Duration.ofHours(24));
        Instant endsAt = Instant.now().plus(duration);
        currentElection = new Election(lastRole, endsAt, Election.Type.NO_CONFIDENCE);
        currentElection.addNominee(target.getUniqueId());
        scoreboardService.resetHidden();
        broadcast(msg("messages.no-confidence-started")
                .replace("%role%", lastRole)
                .replace("%target%", displayName(target))
                .replace("%duration%", DurationUtil.format(duration)));
        scoreboardService.updateAll(currentElection);
        return ActionResult.ok(color("&aNo confidence vote started against &f" + displayName(target)));
    }

    public ActionResult setPlatform(CommandSender sender, OfflinePlayer nominee, String platform) {
        if (currentElection == null) {
            return ActionResult.fail(msg("messages.no-election"));
        }
        if (currentElection.getType() == Election.Type.NO_CONFIDENCE) {
            return ActionResult.fail(msg("messages.no-confidence-platform"));
        }
        if (!currentElection.isNominee(nominee.getUniqueId())) {
            return ActionResult.fail(msg("messages.not-nominee"));
        }
        if (!currentElection.isActive()) {
            return ActionResult.fail(color("&cElection already ended."));
        }
        if (sender instanceof Player player && !player.getUniqueId().equals(nominee.getUniqueId())
                && !sender.hasPermission("elections.admin")) {
            return ActionResult.fail(msg("messages.not-nominee"));
        }
        String trimmed = platform.trim();
        if (trimmed.isEmpty()) {
            return ActionResult.fail(color("&cPlatform cannot be empty."));
        }
        if (trimmed.length() > 256) {
            trimmed = trimmed.substring(0, 256);
        }
        currentElection.setPlatform(nominee.getUniqueId(), trimmed);
        return ActionResult.ok(msg("messages.platform-set").replace("%platform%", trimmed));
    }

    public ActionResult viewPlatform(OfflinePlayer nominee) {
        if (currentElection == null) {
            return ActionResult.fail(msg("messages.no-election"));
        }
        if (currentElection.getType() == Election.Type.NO_CONFIDENCE) {
            return ActionResult.fail(msg("messages.no-confidence-platform"));
        }
        if (!currentElection.isNominee(nominee.getUniqueId())) {
            return ActionResult.fail(msg("messages.not-nominee"));
        }
        Optional<String> platform = currentElection.getPlatform(nominee.getUniqueId());
        if (platform.isEmpty()) {
            return ActionResult.fail(msg("messages.platform-missing"));
        }
        return ActionResult.ok(msg("messages.platform-view")
                .replace("%target%", displayName(nominee))
                .replace("%platform%", platform.get()));
    }

    public ActionResult vote(Player voter, OfflinePlayer target) {
        if (currentElection == null) {
            return ActionResult.fail(msg("messages.no-election"));
        }
        if (!currentElection.isActive()) {
            return ActionResult.fail(color("&cVoting is closed. Results are being displayed."));
        }
        if (!currentElection.isNominee(target.getUniqueId())) {
            return ActionResult.fail(msg("messages.not-nominated"));
        }

        UUID previous = currentElection.getVotes().put(voter.getUniqueId(), target.getUniqueId());
        scoreboardService.updateAll(currentElection);

        if (previous != null && previous.equals(target.getUniqueId())) {
            return ActionResult.ok(color("&eYou already voted for &f" + displayName(target) + "&e."));
        }
        String path = previous == null ? "messages.vote-accepted" : "messages.vote-updated";
        return ActionResult.ok(msg(path).replace("%candidate%", displayName(target)));
    }

    public ActionResult rigVotes(OfflinePlayer target) {
        if (currentElection == null) {
            return ActionResult.fail(msg("messages.no-election"));
        }
        if (!currentElection.isNominee(target.getUniqueId())) {
            currentElection.addNominee(target.getUniqueId());
        }
        UUID targetId = target.getUniqueId();
        Map<UUID, UUID> votes = currentElection.getVotes();
        Set<UUID> voters = new HashSet<>(votes.keySet());
        for (UUID voter : voters) {
            votes.put(voter, targetId);
        }
        scoreboardService.updateAll(currentElection);
        return ActionResult.ok(msg("messages.rigged").replace("%winner%", displayName(target)));
    }

    public ActionResult endElection() {
        if (currentElection == null) {
            return ActionResult.fail(msg("messages.no-election"));
        }

        if (currentElection.isActive()) {
            conclude(false);
        }

        scoreboardService.clearAll();
        currentElection = null;
        return ActionResult.ok(msg("messages.ended"));
    }

    public ActionResult status() {
        if (currentElection == null) {
            return ActionResult.ok(color("&eNo election is running."));
        }

        StringBuilder builder = new StringBuilder();
        builder.append(color("&aRole: &f")).append(currentElection.getRole()).append("\n");
        builder.append(color("&aType: &f")).append(currentElection.getType() == Election.Type.NO_CONFIDENCE ? "No Confidence" : "Election").append("\n");
        builder.append(color("&aStatus: &f")).append(currentElection.isActive() ? "Active" : "Finished").append("\n");
        if (currentElection.isActive()) {
            builder.append(color("&aEnds in: &f")).append(DurationUtil.format(currentElection.getRemaining(Instant.now()))).append("\n");
        } else {
            builder.append(color("&aEnds in: &f")).append("Finished").append("\n");
        }
        builder.append(color("&aNominees: &f")).append(currentElection.getNominees().size());
        return ActionResult.ok(builder.toString());
    }

    private void concludeOrExtend() {
        if (currentElection == null || !currentElection.isActive()) {
            return;
        }

        Map<UUID, Long> counts = currentElection.getVoteCounts();
        long totalVotes = counts.values().stream().mapToLong(Long::longValue).sum();
        List<UUID> leaders = currentElection.getLeaders();

        if (leaders.size() >= 2) {
            handleTie(leaders);
            return;
        }

        if (totalVotes == 0 && currentElection.getNominees().size() >= 2) {
            // No votes cast but more than one nominee -> treat as tie and extend.
            handleTie(new ArrayList<>(currentElection.getNominees()));
            return;
        }

        // Single leader or only one nominee (with or without votes)
        conclude(false);
    }

    private void conclude(boolean silent) {
        if (currentElection == null) {
            return;
        }

        Map<UUID, Long> counts = currentElection.getVoteCounts();
        UUID winner = null;
        long topVotes = -1;
        for (Map.Entry<UUID, Long> entry : counts.entrySet()) {
            if (entry.getValue() > topVotes) {
                topVotes = entry.getValue();
                winner = entry.getKey();
            }
        }
        if (winner == null && currentElection.getNominees().size() == 1) {
            winner = currentElection.getNominees().iterator().next();
            topVotes = counts.getOrDefault(winner, 0L);
        }

        currentElection.setStatus(Election.Status.FINISHED);
        currentElection.setWinner(winner);

        if (!silent && !currentElection.isAnnouncedFinished()) {
            announceResults(winner, topVotes);
            currentElection.markAnnouncedFinished();
        }

        if (currentElection.getType() == Election.Type.NO_CONFIDENCE) {
            if (winner != null) {
                runNoConfidenceCommands(winner);
                // Remove incumbent
                lastWinnerId = null;
                lastRole = currentElection.getRole();
            }
        } else {
            runWinCommands(winner);
            lastWinnerId = winner;
            lastRole = currentElection.getRole();
        }
        scoreboardService.updateAll(currentElection);
    }

    private void concludeNoConfidence(boolean passed, long votes) {
        if (currentElection == null) {
            return;
        }
        if (currentElection.getType() != Election.Type.NO_CONFIDENCE) {
            return;
        }
        currentElection.setStatus(Election.Status.FINISHED);
        if (passed) {
            UUID target = currentElection.getNominees().stream().findFirst().orElse(null);
            currentElection.setWinner(target);
            if (!currentElection.isAnnouncedFinished()) {
                String name = target != null ? displayName(Bukkit.getOfflinePlayer(target)) : "Unknown";
                broadcast(msg("messages.no-confidence-passed")
                        .replace("%target%", name)
                        .replace("%role%", currentElection.getRole())
                        .replace("%votes%", String.valueOf(votes)));
                currentElection.markAnnouncedFinished();
            }
            if (target != null) {
                runNoConfidenceCommands(target);
            }
            lastWinnerId = null;
        } else {
            currentElection.setWinner(null);
            if (!currentElection.isAnnouncedFinished()) {
                broadcast(msg("messages.no-confidence-failed")
                        .replace("%role%", currentElection.getRole())
                        .replace("%needed%", String.valueOf(getRequiredNoConfidenceVotes()))
                        .replace("%votes%", String.valueOf(votes)));
                currentElection.markAnnouncedFinished();
            }
        }
        scoreboardService.updateAll(currentElection);
    }

    private void handleTie(List<UUID> leaders) {
        if (currentElection == null) {
            return;
        }
        // Keep only the first two leaders in the tie.
        List<UUID> survivors = new ArrayList<>();
        for (UUID nominee : currentElection.getNominees()) {
            if (leaders.contains(nominee)) {
                survivors.add(nominee);
            }
            if (survivors.size() == 2) {
                break;
            }
        }
        currentElection.setNominees(survivors);
        currentElection.clearVotesForNonNominees();
        currentElection.prunePlatformsForNonNominees();
        currentElection.extend(Duration.ofHours(24));

        broadcast(msg("messages.tie-extended"));
        scoreboardService.updateAll(currentElection);
    }

    private void announceResults(UUID winner, long votes) {
        if (winner == null) {
            broadcast(color("&eElection finished with no winner."));
            return;
        }
        String name = displayName(Bukkit.getOfflinePlayer(winner));
        broadcast(color("&aElection for &f" + currentElection.getRole() + " &ahas ended. Winner: &f" + name + " &7(" + votes + " votes)"));
    }

    private void runWinCommands(UUID winner) {
        if (winner == null || currentElection == null || currentElection.haveCommandsRun()) {
            return;
        }
        List<String> commands = plugin.getConfig().getStringList("commands-on-win");
        if (commands.isEmpty()) {
            return;
        }
        String winnerName = displayName(Bukkit.getOfflinePlayer(winner));
        String role = currentElection.getRole();
        for (String command : commands) {
            String parsed = command.replace("%winner%", winnerName).replace("%role%", role);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }
        currentElection.markCommandsRan();
    }

    private void runNoConfidenceCommands(UUID target) {
        if (target == null || currentElection == null || currentElection.haveCommandsRun()) {
            return;
        }
        List<String> commands = plugin.getConfig().getStringList("no-confidence.commands-on-pass");
        if (commands.isEmpty()) {
            return;
        }
        String targetName = displayName(Bukkit.getOfflinePlayer(target));
        String role = currentElection.getRole();
        for (String command : commands) {
            String parsed = command.replace("%target%", targetName).replace("%role%", role);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }
        currentElection.markCommandsRan();
    }

    private String msg(String path) {
        String fallback = switch (path) {
            case "messages.not-nominee" -> "&cYou must be a nominee to do that.";
            case "messages.no-election" -> "&cThere is no active election right now.";
            case "messages.platform-set" -> "&aUpdated your platform: &f%platform%";
            case "messages.platform-view" -> "&bPlatform for &f%target%&b: &f%platform%";
            case "messages.platform-missing" -> "&eThat nominee has not set a platform yet.";
            case "messages.already-nominated" -> "&eThat player is already nominated.";
            case "messages.nomination-success" -> "&a%target% has been nominated for %role%!";
            case "messages.no-confidence-started" -> "&eNo confidence vote started against %target% for %role% (%duration%).";
            case "messages.no-confidence-unavailable" -> "&cNo winner is currently in office to challenge.";
            case "messages.no-confidence-target" -> "&cOnly the current winner (%winner%) can face no confidence right now.";
            case "messages.no-confidence-passed" -> "&cNo confidence passed against %target% for %role% with %votes% votes.";
            case "messages.no-confidence-failed" -> "&aNo confidence failed for %role% (%votes%/%needed% votes).";
            case "messages.no-confidence-platform" -> "&cPlatforms are disabled for no confidence votes.";
            default -> "&cMissing message: " + path;
        };
        return color(plugin.getConfig().getString(path, fallback));
    }

    private void broadcast(String message) {
        Bukkit.getServer().broadcastMessage(message);
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    private String displayName(OfflinePlayer player) {
        String name = player.getName();
        return name != null ? name : player.getUniqueId().toString().substring(0, 8);
    }

    private Duration parseDurationOrDefault(String input, Duration fallback) {
        return DurationUtil.parseDuration(input).orElse(fallback);
    }

    private int getRequiredNoConfidenceVotes() {
        return Math.max(1, plugin.getConfig().getInt("no-confidence.required-votes", 6));
    }

    public record ActionResult(boolean success, String message) {
        public static ActionResult ok(String message) {
            return new ActionResult(true, message);
        }

        public static ActionResult fail(String message) {
            return new ActionResult(false, message);
        }
    }
}
