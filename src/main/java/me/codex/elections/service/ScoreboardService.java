package me.codex.elections.service;

import me.codex.elections.model.Election;
import me.codex.elections.util.DurationUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ScoreboardService {

    private final JavaPlugin plugin;

    public ScoreboardService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateAll(Election election) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            showTo(player, election);
        }
    }

    public void showTo(Player player, Election election) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("scoreboard.title", "&aElections"));
        Objective objective = scoreboard.registerNewObjective("elections", "dummy", title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<String> lines = buildLines(election);
        int score = lines.size();
        for (String line : lines) {
            Score s = objective.getScore(line);
            s.setScore(score--);
        }
        player.setScoreboard(scoreboard);
    }

    public void clearAll() {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setScoreboard(main);
        }
    }

    private List<String> buildLines(Election election) {
        List<String> lines = new ArrayList<>();
        lines.add(color("&7&m----------------"));
        lines.add(color("&bRole: &f" + election.getRole()));
        if (election.isActive()) {
            Duration remaining = election.getRemaining(java.time.Instant.now());
            lines.add(color("&bEnds in: &f" + DurationUtil.format(remaining)));
        } else {
            lines.add(color("&bStatus: &fFinished"));
        }

        lines.add(color("&bNominees:"));
        List<String> candidateLines = new ArrayList<>();
        boolean showCounts = plugin.getConfig().getBoolean("scoreboard.show-vote-counts", true);
        for (java.util.UUID nominee : election.getNominees()) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(nominee);
            long votes = election.getVoteCounts().getOrDefault(nominee, 0L);
            String display = offlinePlayer.getName() != null ? offlinePlayer.getName() : nominee.toString().substring(0, 8);
            candidateLines.add(color(" &7- &f" + display + (showCounts ? (" &7(" + votes + ")") : "")));
        }

        int maxLines = 15;
        boolean showVoteTip = plugin.getConfig().getBoolean("scoreboard.show-vote-tip", true);
        boolean showWinnerLine = !election.isActive();
        int reserved = 4 /*header, role, ends/status, nominees label*/ + (showVoteTip ? 1 : 0) + (showWinnerLine ? 1 : 0) + 1 /*footer*/;
        int availableForCandidates = Math.max(0, maxLines - reserved);
        int configMax = plugin.getConfig().getInt("scoreboard.max-candidates", 10);
        int limit = Math.min(configMax, availableForCandidates);
        boolean needsEllipsis = candidateLines.size() > limit;
        if (needsEllipsis && limit == availableForCandidates && availableForCandidates > 0) {
            // Make room for the ellipsis line so we never exceed the scoreboard row limit.
            limit = Math.max(0, limit - 1);
        }
        if (candidateLines.isEmpty()) {
            lines.add(color(" &7- None yet"));
        } else {
            int toShow = Math.min(limit, candidateLines.size());
            for (int i = 0; i < toShow; i++) {
                lines.add(candidateLines.get(i));
            }
            if (needsEllipsis) {
                lines.add(color("&7..."));
            }
        }

        if (plugin.getConfig().getBoolean("scoreboard.show-vote-tip", true)) {
            lines.add(color("&bVote: &f/vote <name>"));
        }

        if (!election.isActive()) {
            if (election.getWinner().isPresent()) {
                java.util.UUID winner = election.getWinner().get();
                OfflinePlayer offline = Bukkit.getOfflinePlayer(winner);
                String display = offline.getName() != null ? offline.getName() : winner.toString().substring(0, 8);
                lines.add(color("&aWinner: &f" + display));
            } else {
                lines.add(color("&aWinner: &fNone"));
            }
        }

        lines.add(color("&7&m----------------"));
        return uniquify(lines);
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private List<String> uniquify(List<String> lines) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> unique = new ArrayList<>();
        int colorIndex = 0;
        for (String line : lines) {
            String candidate = line;
            while (seen.contains(candidate)) {
                candidate = line + ChatColor.values()[colorIndex % ChatColor.values().length];
                colorIndex++;
            }
            seen.add(candidate);
            unique.add(candidate);
        }
        return unique;
    }
}
