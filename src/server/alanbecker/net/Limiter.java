package server.alanbecker.net;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Limiter extends JavaPlugin implements Listener {
    private FileConfiguration playerData;
    private File playerDataFile;
    private Map<Material, Integer> materialLimits;
    private Map<Material, Long> materialPeriods;
    private Map<UUID, Map<Material, Long>> lastPlaced;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadMaterialLimitsAndPeriods();

        playerDataFile = new File(getDataFolder(), "playerdata.yml");
        if (!playerDataFile.exists()) {
            playerDataFile.getParentFile().mkdirs();
            saveResource("playerdata.yml", false);
        }
        playerData = YamlConfiguration.loadConfiguration(playerDataFile);
        lastPlaced = new HashMap<>();

        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, this::cleanupExpiredCooldowns, 1200, 1200);
    }

    @Override
    public void onDisable() {
        savePlayerData();
    }

    private void loadMaterialLimitsAndPeriods() {
        materialLimits = new HashMap<>();
        materialPeriods = new HashMap<>();
        for (String materialName : getConfig().getConfigurationSection("blacklist").getKeys(false)) {
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                getLogger().warning("Invalid material name in blacklist: " + materialName);
                continue;
            }
            int limit = getConfig().getInt("blacklist." + materialName + ".limit");
            long period = getConfig().getLong("blacklist." + materialName + ".period") * 1000;
            materialLimits.put(material, limit);
            materialPeriods.put(material, period);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("limiter.bypass")) return;

        UUID playerId = player.getUniqueId();
        ItemStack item = event.getItem();
        if (item == null) return;
        Material material = item.getType();

        if (!materialLimits.containsKey(material)) return;

        long currentTime = System.currentTimeMillis();
        lastPlaced.putIfAbsent(playerId, new HashMap<>());

        Map<Material, Long> playerMaterialTimes = lastPlaced.get(playerId);

        if (!playerMaterialTimes.containsKey(material) || currentTime - playerMaterialTimes.get(material) > materialPeriods.get(material)) {
            playerMaterialTimes.put(material, currentTime);
            playerData.set(playerId.toString() + "." + material.name() + ".count", 1);
            playerData.set(playerId.toString() + "." + material.name() + ".lastPlaced", currentTime);
        } else {
            int count = playerData.getInt(playerId.toString() + "." + material.name() + ".count");
            if (count < materialLimits.get(material)) {
                playerData.set(playerId.toString() + "." + material.name() + ".count", count + 1);
            } else {
                event.setCancelled(true);
                
                long remainingTime = (materialPeriods.get(material) - (currentTime - playerMaterialTimes.get(material))) / 1000;
                
                player.sendMessage("§cYou have reached the limit for placing " + material.name().toLowerCase().replace("_", " ") + "s.");
                player.sendMessage("§cYou can place them again in " + remainingTime + " seconds.");
            }
        }

        savePlayerData();
    }

    private void savePlayerData() {
        try {
            playerData.save(playerDataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("limiterreload")) {
            if (sender.hasPermission("limiter.reload")) {
                reloadConfig();
                loadMaterialLimitsAndPeriods();
                sender.sendMessage("§aConfiguration reloaded successfully.");
            } else {
                sender.sendMessage("§cYou don't have permission to reload the configuration.");
            }
            return true;
        }
        return false;
    }

    private void cleanupExpiredCooldowns() {
        long currentTime = System.currentTimeMillis();
        lastPlaced.values().forEach(playerMaterialTimes -> playerMaterialTimes.entrySet().removeIf(entry -> currentTime - entry.getValue() > materialPeriods.getOrDefault(entry.getKey(), Long.MAX_VALUE)));
    }
}
