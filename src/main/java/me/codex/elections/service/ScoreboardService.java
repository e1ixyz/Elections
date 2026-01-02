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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ScoreboardService {

    private final JavaPlugin plugin;
    private final Set<java.util.UUID> hidden = new HashSet<>();

    public ScoreboardService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateAll(Election election) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            showTo(player, election);
        }
    }

    public void showTo(Player player, Election election) {
        if (hidden.contains(player.getUniqueId())) {
            return;
        }
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

    public boolean toggle(Player player) {
        if (hidden.remove(player.getUniqueId())) {
            return true; // now enabled
        }
        hidden.add(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        return false; // now disabled
    }

    public boolean enable(Player player) {
        boolean changed = hidden.remove(player.getUniqueId());
        return changed;
    }

    public boolean disable(Player player) {
        boolean added = hidden.add(player.getUniqueId());
        if (added) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        return added;
    }

    public boolean isHidden(Player player) {
        return hidden.contains(player.getUniqueId());
    }

    public void resetHidden() {
        hidden.clear();
    }

    private List<Line> buildLines(Election election) {
        final int maxLines = 15;
        final boolean showVoteTip = plugin.getConfig().getBoolean("scoreboard.show-vote-tip", true);
        final boolean showHelpTip = plugin.getConfig().getBoolean("scoreboard.show-help-tip", true);
        final boolean showCounts = plugin.getConfig().getBoolean("scoreboard.show-vote-counts", true);
        final boolean showWinnerLine = !election.isActive();

        List<String> lines = new ArrayList<>();
        // Header
        String roleLine = election.getType() == Election.Type.NO_CONFIDENCE
                ? "&bNo Confidence: &f" + election.getRole()
                : "&bRole: &f" + election.getRole();
        lines.add(color(roleLine));
        if (election.getType() == Election.Type.NO_CONFIDENCE) {
            election.getNominees().stream().findFirst().ifPresent(target -> {
                String name = Bukkit.getOfflinePlayer(target).getName();
                lines.add(color("&bTarget: &f" + (name != null ? name : target.toString().substring(0, 8))));
            });
        }
        if (election.isActive()) {
            Duration remaining = election.getRemaining(java.time.Instant.now());
            lines.add(color("&bEnds in: &f" + DurationUtil.format(remaining)));
        } else {
            lines.add(color("&bStatus: &fFinished"));
        }
        lines.add(color("&bNominees:"));

        List<String> candidateLines = new ArrayList<>();
        for (java.util.UUID nominee : election.getNominees()) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(nominee);
            long votes = election.getVoteCounts().getOrDefault(nominee, 0L);
            String display = offlinePlayer.getName() != null ? offlinePlayer.getName() : nominee.toString().substring(0, 8);
            candidateLines.add(color(" &7- &f" + display + (showCounts ? (" &7(" + votes + ")") : "")));
        }

        int optionalLines = (showVoteTip ? 1 : 0) + (showHelpTip ? 1 : 0) + (showWinnerLine ? 1 : 0);
        int extraStatic = election.getType() == Election.Type.NO_CONFIDENCE ? 1 : 0; // target line
        int reserved = 3 /*role/status*/ + 1 /*nominees label*/ + optionalLines + extraStatic;
        int availableForCandidates = Math.max(0, maxLines - reserved);
        int configMax = plugin.getConfig().getInt("scoreboard.max-candidates", 10);
        int toShow = Math.min(candidateLines.size(), Math.min(configMax, availableForCandidates));
        boolean needsEllipsis = candidateLines.size() > toShow;
        if (needsEllipsis && toShow == availableForCandidates && toShow > 0) {
            // reserve a slot for the ellipsis without hiding all candidates
            toShow = Math.max(1, toShow - 1);
        }

        if (candidateLines.isEmpty()) {
            lines.add(color(" &7- None yet"));
        } else {
            for (int i = 0; i < toShow; i++) {
                lines.add(candidateLines.get(i));
            }
            if (needsEllipsis) {
                lines.add(color("&7..."));
            }
        }

        if (showVoteTip) {
            lines.add(color("&bVote: &f/vote <name>"));
        }

        if (showHelpTip) {
            lines.add(color("&bCommands: &f/elections"));
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

        List<String> unique = uniquify(lines);
        List<Line> result = new ArrayList<>();
        int score = unique.size();
        for (String line : unique) {
            result.add(new Line(line, score--));
        }
        return result;
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private List<String> uniquify(List<String> lines) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> unique = new ArrayList<>();
        int colorIndex = 0;
        ChatColor[] palette = ChatColor.values();
        for (String line : lines) {
            String candidate = line;
            while (seen.contains(candidate)) {
                candidate = line + palette[colorIndex % palette.length];
                colorIndex++;
            }
            seen.add(candidate);
            unique.add(candidate);
        }
        return unique;
    }

    private record Line(String text, int score) { }
}
