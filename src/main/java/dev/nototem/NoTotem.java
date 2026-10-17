package dev.nototem;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** NoTotem: removes Totems of Undying from the server. */
public final class NoTotem extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Adds options introduced by updates without touching existing values
        getConfig().options().copyDefaults(true);
        saveConfig();

        TotemGuard guard = new TotemGuard(this);
        Bukkit.getPluginManager().registerEvents(guard, this);

        PluginCommand command = getCommand("nototem");
        if (command != null) {
            command.setExecutor(guard);
            command.setTabCompleter(guard);
        }
        getLogger().info("NoTotem enabled (" + (guard.isEnabled() ? "active" : "switched off in config.yml") + ").");
    }
}
