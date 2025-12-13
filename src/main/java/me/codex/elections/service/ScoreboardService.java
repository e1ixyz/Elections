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
import java.util.Map;
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

        List<Line> lines = buildLines(election);
        for (Line line : lines) {
            Score s = objective.getScore(line.text());
            s.setScore(line.score());
        }
        player.setScoreboard(scoreboard);
    }

    public void clearAll() {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setScoreboard(main);
        }
    }

    private List<Line> buildLines(Election election) {
        final int maxLines = 15;
        final boolean showVoteTip = plugin.getConfig().getBoolean("scoreboard.show-vote-tip", true);
        final boolean showPlatformTip = plugin.getConfig().getBoolean("scoreboard.show-platform-tip", true);
        final boolean showWinnerLine = !election.isActive();
        final boolean showCounts = plugin.getConfig().getBoolean("scoreboard.show-vote-counts", true);

        List<Line> lines = new ArrayList<>();
        int highScore = 1_000_000; // keep headers above candidate lines
        lines.add(new Line(color("&7&m----------------"), highScore--));
        lines.add(new Line(color("&bRole: &f" + election.getRole()), highScore--));
        if (election.isActive()) {
            Duration remaining = election.getRemaining(java.time.Instant.now());
            lines.add(new Line(color("&bEnds in: &f" + DurationUtil.format(remaining)), highScore--));
        } else {
            lines.add(new Line(color("&bStatus: &fFinished"), highScore--));
        }
        lines.add(new Line(color("&bNominees:"), highScore--));

        Map<java.util.UUID, Long> voteCounts = election.getVoteCounts();
        List<Line> candidateLines = new ArrayList<>();
        int order = 0;
        for (java.util.UUID nominee : election.getNominees()) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(nominee);
            long votes = voteCounts.getOrDefault(nominee, 0L);
            String display = offlinePlayer.getName() != null ? offlinePlayer.getName() : nominee.toString().substring(0, 8);
            String text = color(" &7- &f" + display + (showCounts ? (" &7(" + votes + ")") : ""));
            int score = showCounts ? (int) Math.min(votes, Integer.MAX_VALUE) : (500 - order); // stable order when counts are hidden
            candidateLines.add(new Line(text, score));
            order++;
        }

        int optionalLines = (showVoteTip ? 1 : 0) + (showPlatformTip ? 1 : 0) + (showWinnerLine ? 1 : 0);
        int availableForCandidates = Math.max(0, maxLines - (lines.size() + optionalLines + 1 /*footer*/));
        int configMax = plugin.getConfig().getInt("scoreboard.max-candidates", 10);
        int toShow = Math.min(configMax, Math.min(candidateLines.size(), availableForCandidates));
        boolean needsEllipsis = candidateLines.size() > toShow;
        if (needsEllipsis && toShow == availableForCandidates) {
            if (toShow > 1) {
                toShow -= 1; // free a slot for the ellipsis while keeping at least one candidate line
            } else {
                needsEllipsis = false; // no room to show ellipsis without hiding all candidates
            }
        }

        if (candidateLines.isEmpty()) {
            lines.add(new Line(color(" &7- None yet"), 0));
        } else {
            for (int i = 0; i < toShow; i++) {
                lines.add(candidateLines.get(i));
            }
            if (needsEllipsis) {
                lines.add(new Line(color("&7..."), -5));
            }
        }

        int lowScore = -10; // keep tips/results at the bottom
        if (showVoteTip) {
            lines.add(new Line(color("&bVote: &f/vote <name>"), lowScore--));
        }

        if (showPlatformTip) {
            String example = election.getNominees().stream()
                    .map(uuid -> Bukkit.getOfflinePlayer(uuid).getName())
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse("<name>");
            lines.add(new Line(color("&bPlans: &f/elections platform " + example), lowScore--));
        }

        if (!election.isActive()) {
            if (election.getWinner().isPresent()) {
                java.util.UUID winner = election.getWinner().get();
                OfflinePlayer offline = Bukkit.getOfflinePlayer(winner);
                String display = offline.getName() != null ? offline.getName() : winner.toString().substring(0, 8);
                lines.add(new Line(color("&aWinner: &f" + display), lowScore--));
            } else {
                lines.add(new Line(color("&aWinner: &fNone"), lowScore--));
            }
        }

        lines.add(new Line(color("&7&m----------------"), lowScore--));
        return uniquify(lines);
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private List<Line> uniquify(List<Line> lines) {
        Set<String> seen = new LinkedHashSet<>();
        List<Line> unique = new ArrayList<>();
        int colorIndex = 0;
        ChatColor[] palette = ChatColor.values();
        for (Line line : lines) {
            String candidate = line.text();
            while (seen.contains(candidate)) {
                candidate = line.text() + palette[colorIndex % palette.length];
                colorIndex++;
            }
            seen.add(candidate);
            unique.add(new Line(candidate, line.score()));
        }
        return unique;
    }

    private record Line(String text, int score) { }
}
