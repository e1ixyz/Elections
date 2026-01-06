package me.codex.elections.service;

import me.codex.elections.ElectionsPlugin;
import me.codex.elections.model.Election;
import me.codex.elections.util.DurationUtil;
import net.essentialsx.api.v2.events.AfkStatusChangeEvent;
import net.essentialsx.api.v2.services.IEssentials;
import net.essentialsx.api.v2.user.User;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.io.File;
import java.io.IOException;

public class ElectionManager implements Listener {

    private final ElectionsPlugin plugin;
    private final ScoreboardService scoreboardService;
    private Election currentElection;
    private UUID lastWinnerId;
    private String lastRole;
    private int taskId = -1;
    private int activityTaskId = -1;
    private final Map<UUID, Integer> activeSeconds = new HashMap<>();
    private final Set<UUID> afkPlayers = new HashSet<>();
    private IEssentials essentials;

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

    public void startActivityTracking() {
        stopActivityTracking();
        this.essentials = Bukkit.getServicesManager().load(IEssentials.class);
        this.activityTaskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, this::tickActivity, 20L, 20L);
    }

    public void stopActivityTracking() {
        if (activityTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(activityTaskId);
            activityTaskId = -1;
        }
    }

    public void loadState() {
        File file = new File(plugin.getDataFolder(), "state.yml");
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().warning("Failed to load election state: " + e.getMessage());
            return;
        }

        String lastWinnerStr = yaml.getString("last.winner");
        this.lastWinnerId = lastWinnerStr != null ? UUID.fromString(lastWinnerStr) : null;
        this.lastRole = yaml.getString("last.role", null);

        if (!yaml.isConfigurationSection("current")) {
            return;
        }
        String role = yaml.getString("current.role");
        String typeStr = yaml.getString("current.type", Election.Type.REGULAR.name());
        String statusStr = yaml.getString("current.status", Election.Status.ACTIVE.name());
        long endsAt = yaml.getLong("current.endsAt", 0L);
        long startedAt = yaml.getLong("current.startedAt", System.currentTimeMillis());

        Election.Type type = Election.Type.valueOf(typeStr);
        Election.Status status = Election.Status.valueOf(statusStr);
        Election election = new Election(role, Instant.ofEpochMilli(endsAt), type);
        election.setStatus(status);

        List<String> nominees = yaml.getStringList("current.nominees");
        nominees.forEach(n -> election.addNominee(UUID.fromString(n)));

        // votes
        if (yaml.isConfigurationSection("current.votes")) {
            for (String voter : yaml.getConfigurationSection("current.votes").getKeys(false)) {
                String candidate = yaml.getString("current.votes." + voter);
                if (candidate != null) {
                    election.getVotes().put(UUID.fromString(voter), UUID.fromString(candidate));
                }
            }
        }

        // platforms
        if (yaml.isConfigurationSection("current.platforms")) {
            for (String nominee : yaml.getConfigurationSection("current.platforms").getKeys(false)) {
                String text = yaml.getString("current.platforms." + nominee);
                if (text != null) {
                    election.setPlatform(UUID.fromString(nominee), text);
                }
            }
        }

        // vote changes
        if (yaml.isConfigurationSection("current.voteChanges")) {
            Map<UUID, Integer> changes = new HashMap<>();
            for (String voter : yaml.getConfigurationSection("current.voteChanges").getKeys(false)) {
                changes.put(UUID.fromString(voter), yaml.getInt("current.voteChanges." + voter, 0));
            }
            election.setVoteChanges(changes);
        }

        // active seconds
        if (yaml.isConfigurationSection("activitySeconds")) {
            for (String id : yaml.getConfigurationSection("activitySeconds").getKeys(false)) {
                activeSeconds.put(UUID.fromString(id), yaml.getInt("activitySeconds." + id, 0));
            }
        }

        // nominations map
        if (yaml.isConfigurationSection("current.nominations")) {
            Map<UUID, Set<UUID>> nominations = new HashMap<>();
            for (String nominator : yaml.getConfigurationSection("current.nominations").getKeys(false)) {
                List<String> targets = yaml.getStringList("current.nominations." + nominator);
                Set<UUID> set = new HashSet<>();
                targets.forEach(t -> set.add(UUID.fromString(t)));
                nominations.put(UUID.fromString(nominator), set);
            }
            election.setNominationsBy(nominations);
        }

        String winnerStr = yaml.getString("current.winner");
        if (winnerStr != null && !winnerStr.isBlank()) {
            election.setWinner(UUID.fromString(winnerStr));
        }
        election.setCommandsRan(yaml.getBoolean("current.commandsRan", false));
        election.setAnnouncedFinished(yaml.getBoolean("current.announcedFinished", false));

        this.currentElection = election;
        scoreboardService.updateAll(election);
    }

    public void saveState() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        File file = new File(plugin.getDataFolder(), "state.yml");
        YamlConfiguration yaml = new YamlConfiguration();

        if (lastWinnerId != null) {
            yaml.set("last.winner", lastWinnerId.toString());
        }
        if (lastRole != null) {
            yaml.set("last.role", lastRole);
        }

        if (currentElection != null) {
            Election e = currentElection;
            yaml.set("current.role", e.getRole());
            yaml.set("current.type", e.getType().name());
            yaml.set("current.status", e.getStatus().name());
            yaml.set("current.startedAt", e.getStartedAt().toEpochMilli());
            yaml.set("current.endsAt", e.getEndsAt().toEpochMilli());
            yaml.set("current.nominees", e.getNominees().stream().map(UUID::toString).toList());
            yaml.set("current.commandsRan", e.haveCommandsRun());
            yaml.set("current.announcedFinished", e.isAnnouncedFinished());
            e.getWinner().ifPresent(w -> yaml.set("current.winner", w.toString()));

            Map<String, String> votes = new HashMap<>();
            e.getVotes().forEach((voter, candidate) -> votes.put(voter.toString(), candidate.toString()));
            yaml.createSection("current.votes", votes);

            Map<String, String> platforms = new HashMap<>();
            e.getPlatforms().forEach((nominee, platform) -> platforms.put(nominee.toString(), platform));
            yaml.createSection("current.platforms", platforms);

            Map<String, Integer> changes = new HashMap<>();
            e.getVoteChanges().forEach((voter, count) -> changes.put(voter.toString(), count));
            yaml.createSection("current.voteChanges", changes);

            Map<String, List<String>> nominations = new HashMap<>();
            e.getNominationsBy().forEach((nominator, set) -> nominations.put(
                    nominator.toString(),
                    set.stream().map(UUID::toString).toList()
            ));
            yaml.createSection("current.nominations", nominations);
        }

        Map<String, Integer> activity = new HashMap<>();
        activeSeconds.forEach((id, seconds) -> activity.put(id.toString(), seconds));
        yaml.createSection("activitySeconds", activity);

        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save election state: " + ex.getMessage());
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

    private void tickActivity() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isAfk(player)) {
                continue;
            }
            activeSeconds.merge(player.getUniqueId(), 1, Integer::sum);
        }
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
        if (plugin.getServer().getPlayer(target.getUniqueId()) == null || !target.isOnline()) {
            return ActionResult.fail(msg("messages.nomination-offline"));
        }
        if (nominator instanceof Player player && player.getUniqueId().equals(target.getUniqueId())) {
            return ActionResult.fail(msg("messages.cannot-self-nominate"));
        }
        if (currentElection.isNominee(target.getUniqueId())) {
            return ActionResult.fail(msg("messages.already-nominated"));
        }
        if (nominator instanceof Player player && !nominator.hasPermission("elections.admin")) {
            int maxNoms = Math.max(1, plugin.getConfig().getInt("nominations.max-per-player", 1));
            int used = currentElection.getNominationCount(player.getUniqueId());
            if (used >= maxNoms) {
                return ActionResult.fail(msg("messages.nomination-limit").replace("%max%", String.valueOf(maxNoms)));
            }
        }
        currentElection.addNominee(target.getUniqueId());
        if (nominator instanceof Player player) {
            currentElection.recordNomination(player.getUniqueId(), target.getUniqueId());
        }
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
        int requiredHours = plugin.getConfig().getInt("voting.required-playtime-hours", 12);
        long secondsPlayed = activeSeconds.getOrDefault(voter.getUniqueId(), 0);
        long hoursPlayed = secondsPlayed / 3600;
        if (hoursPlayed < requiredHours) {
            return ActionResult.fail(msg("messages.vote-playtime")
                    .replace("%needed%", String.valueOf(requiredHours))
                    .replace("%have%", String.valueOf(hoursPlayed)));
        }
        if (!currentElection.isNominee(target.getUniqueId())) {
            return ActionResult.fail(msg("messages.not-nominated"));
        }

        UUID previous = currentElection.getVotes().put(voter.getUniqueId(), target.getUniqueId());
        scoreboardService.updateAll(currentElection);

        if (previous != null && previous.equals(target.getUniqueId())) {
            return ActionResult.ok(color("&eYou already voted for &f" + displayName(target) + "&e."));
        }
        if (previous != null && !previous.equals(target.getUniqueId())) {
            int maxChanges = Math.max(0, plugin.getConfig().getInt("voting.max-changes", 2));
            int used = currentElection.getVoteChanges(voter.getUniqueId());
            if (used >= maxChanges) {
                // Revert the vote change
                currentElection.getVotes().put(voter.getUniqueId(), previous);
                return ActionResult.fail(msg("messages.vote-change-limit").replace("%max%", String.valueOf(maxChanges)));
            }
            currentElection.incrementVoteChange(voter.getUniqueId());
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

    public ActionResult unnominate(CommandSender sender, OfflinePlayer target) {
        if (currentElection == null) {
            return ActionResult.fail(msg("messages.no-election"));
        }
        if (!currentElection.isActive()) {
            return ActionResult.fail(color("&cCannot unnominate after the election has ended."));
        }
        boolean removed = currentElection.removeNominee(target.getUniqueId());
        if (!removed) {
            return ActionResult.fail(msg("messages.unnominate-missing"));
        }
        currentElection.clearVotesForNonNominees();
        currentElection.prunePlatformsForNonNominees();
        scoreboardService.updateAll(currentElection);
        return ActionResult.ok(msg("messages.unnominate-success").replace("%target%", displayName(target)));
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
            case "messages.nomination-offline" -> "&cYou can only nominate online players.";
            case "messages.vote-change-limit" -> "&cYou have reached the vote change limit (%max%).";
            case "messages.vote-playtime" -> "&cYou need %needed% hours of playtime to vote. You have %have% hours.";
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

    @EventHandler
    public void onAfkChange(AfkStatusChangeEvent event) {
        Player player = event.getAffected().getBase();
        if (player == null) {
            return;
        }
        if (event.getValue()) {
            afkPlayers.add(player.getUniqueId());
        } else {
            afkPlayers.remove(player.getUniqueId());
        }
    }

    private boolean isAfk(Player player) {
        if (afkPlayers.contains(player.getUniqueId())) {
            return true;
        }
        if (essentials != null) {
            try {
                User user = essentials.getUser(player.getUniqueId());
                if (user != null && user.isAfk()) {
                    afkPlayers.add(player.getUniqueId());
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        afkPlayers.remove(player.getUniqueId());
        return false;
    }
}
