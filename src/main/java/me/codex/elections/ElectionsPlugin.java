package me.codex.elections;

import me.codex.elections.commands.ElectionsCommand;
import me.codex.elections.commands.VoteCommand;
import me.codex.elections.service.ElectionManager;
import me.codex.elections.service.ScoreboardService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class ElectionsPlugin extends JavaPlugin implements Listener {

    private ElectionManager electionManager;
    private ScoreboardService scoreboardService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.scoreboardService = new ScoreboardService(this);
        this.electionManager = new ElectionManager(this, scoreboardService);

        getServer().getPluginManager().registerEvents(this, this);

        ElectionsCommand electionsCommand = new ElectionsCommand(electionManager);
        getCommand("elections").setExecutor(electionsCommand);
        getCommand("elections").setTabCompleter(electionsCommand);

        VoteCommand voteCommand = new VoteCommand(electionManager);
        getCommand("vote").setExecutor(voteCommand);
        getCommand("vote").setTabCompleter(voteCommand);

        electionManager.startTicking();
    }

    @Override
    public void onDisable() {
        if (electionManager != null) {
            electionManager.stopTicking();
        }
        if (scoreboardService != null) {
            scoreboardService.clearAll();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (electionManager != null && electionManager.getCurrentElection().isPresent()) {
            scoreboardService.showTo(event.getPlayer(), electionManager.getCurrentElection().get());
        }
    }
}
