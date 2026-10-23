
package net.surrealsmp.halloweenevents;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Particle;
import org.bukkit.Sound;

import java.util.Random;

public final class HalloweenEvents extends JavaPlugin implements Listener {

    private BossBar bossBar;
    private BukkitTask eventTask;
    private BukkitTask waveTask;
    private World eventWorld;
    private long eventEndTime;
    private final Random random = new Random();
    private boolean running;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        bossBar = Bukkit.createBossBar(
                color(getConfig().getString("event.bossbar.title",
                        "&6&lHALLOWEEN EVENT")),
                parseColor(getConfig().getString("event.bossbar.color", "PURPLE")),
                parseStyle(getConfig().getString("event.bossbar.style", "SEGMENTED_10"))
        );

        getLogger().info("HalloweenEvents enabled.");
        getLogger().info("Use /halloween help for commands.");
    }

    @Override
    public void onDisable() {
        stopEvent(false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("halloween")) {
            return false;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(color("&6&lHalloweenEvents &8- &fCommands"));
            sender.sendMessage(color("&e/halloween start [world] &7- Start event"));
            sender.sendMessage(color("&e/halloween stop &7- Stop event"));
            sender.sendMessage(color("&e/halloween status &7- Event status"));
            sender.sendMessage(color("&e/halloween reload &7- Reload config"));
            return true;
        }

        if (!sender.hasPermission("halloweenevents.admin")) {
            sender.sendMessage(message("messages.no-permission"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (running) {
                    sender.sendMessage(message("messages.event-already-running"));
                    return true;
                }

                World world = null;

                if (args.length >= 2) {
                    world = Bukkit.getWorld(args[1]);
                    if (world == null) {
                        sender.sendMessage(color("&cWorld not found: &f" + args[1]));
                        return true;
                    }
                } else if (sender instanceof Player player) {
                    world = player.getWorld();
                } else {
                    String configuredWorld = getConfig().getString("event.world", "");
                    if (configuredWorld != null && !configuredWorld.isBlank()) {
                        world = Bukkit.getWorld(configuredWorld);
                    }
                    if (world == null) {
                        sender.sendMessage(color("&cConsole: use /halloween start <world>"));
                        return true;
                    }
                }

                startEvent(world);
                sender.sendMessage(message("messages.event-started"));
            }
            case "stop" -> {
                if (!running) {
                    sender.sendMessage(message("messages.event-not-running"));
                    return true;
                }
                stopEvent(true);
                sender.sendMessage(message("messages.event-stopped"));
            }
            case "status" -> {
                sender.sendMessage(running
                        ? color("&6Halloween event: &aRUNNING &7(World: "
                            + eventWorld.getName() + ")")
                        : color("&6Halloween event: &cSTOPPED"));
            }
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(message("messages.reloaded"));
            }
            default -> sender.sendMessage(color("&cUnknown command. Use /halloween help"));
        }

        return true;
    }

    private void startEvent(World world) {
        running = true;
        eventWorld = world;
        eventEndTime = System.currentTimeMillis()
                + getConfig().getLong("event.duration-minutes", 30) * 60_000L;

        if (getConfig().getBoolean("event.force-night", true)) {
            world.setTime(18000);
            world.setStorm(true);
            world.setThundering(true);
        }

        for (Player player : world.getPlayers()) {
            player.sendTitle(color("&6&lHALLOWEEN"),
                    color("&7The spooky night has begun!"), 10, 60, 20);
            player.playSound(player.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.6f, 0.8f);
        }

        if (getConfig().getBoolean("event.bossbar.enabled", true)) {
            bossBar.setVisible(true);
            updateBossBar();
        }

        eventTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!running) return;

            if (System.currentTimeMillis() >= eventEndTime) {
                stopEvent(true);
                return;
            }

            updateBossBar();

            if (getConfig().getBoolean("event.effects.particles", true)) {
                for (Player player : eventWorld.getPlayers()) {
                    Location location = player.getLocation().add(0, 2, 0);
                    eventWorld.spawnParticle(Particle.SOUL, location, 4, 0.5, 0.5, 0.5, 0.01);
                }
            }

            if (getConfig().getBoolean("event.effects.spooky-sounds", true)) {
                for (Player player : eventWorld.getPlayers()) {
                    if (random.nextInt(10) == 0) {
                        player.playSound(player.getLocation(),
                                Sound.AMBIENT_CAVE, 0.5f, 0.7f);
                    }
                }
            }

            long interval = Math.max(1,
                    getConfig().getLong("event.announce-interval-seconds", 300));
            if (System.currentTimeMillis() % (interval * 1000L) < 1000L) {
                eventWorld.getPlayers().forEach(player ->
                        player.sendMessage(color("&6&lHALLOWEEN &8» &fThe spooky night continues...")));
            }
        }, 20L, 20L);

        if (getConfig().getBoolean("event.mobs.enabled", true)) {
            long interval = Math.max(1,
                    getConfig().getLong("event.mobs.spawn-interval-seconds", 20));
            waveTask = Bukkit.getScheduler().runTaskTimer(this,
                    this::spawnWave, interval * 20L, interval * 20L);
        }
    }

    private void spawnWave() {
        if (!running || eventWorld == null) return;

        int maximum = Math.max(1,
                getConfig().getInt("event.mobs.max-extra-mobs-per-wave", 5));

        for (Player player : eventWorld.getPlayers()) {
            for (int i = 0; i < maximum; i++) {
                Location base = player.getLocation();
                double angle = random.nextDouble() * Math.PI * 2;
                double distance = 5 + random.nextDouble() * 8;
                Location spawn = base.clone().add(
                        Math.cos(angle) * distance, 0, Math.sin(angle) * distance);

                spawn.setY(eventWorld.getHighestBlockYAt(spawn) + 1);
                if (!spawn.getBlock().isPassable()) continue;

                EntityType[] types = {
                        EntityType.ZOMBIE, EntityType.SKELETON,
                        EntityType.SPIDER, EntityType.WITCH
                };

                LivingEntity mob = (LivingEntity) eventWorld.spawnEntity(
                        spawn, types[random.nextInt(types.length)]);

                if (getConfig().getBoolean("event.effects.mob-buffs", true)) {
                    mob.setCustomName(color("&6Halloween " + mob.getType().name()));
                    mob.setCustomNameVisible(true);
                    mob.setGlowing(true);
                    mob.setHealth(Math.min(mob.getMaxHealth(), mob.getHealth() + 4));
                }
            }
        }
    }

    private void updateBossBar() {
        if (bossBar == null || eventWorld == null) return;

        double total = Math.max(1,
                getConfig().getLong("event.duration-minutes", 30) * 60_000.0);
        double remaining = Math.max(0, eventEndTime - System.currentTimeMillis());

        bossBar.setProgress(Math.max(0, Math.min(1, remaining / total)));
        bossBar.setTitle(color(getConfig().getString("event.bossbar.title",
                "&6&lHALLOWEEN EVENT &8» &fSurvive the night!")));

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(eventWorld)) {
                bossBar.addPlayer(player);
            } else {
                bossBar.removePlayer(player);
            }
        }
    }

    private void stopEvent(boolean announce) {
        boolean wasRunning = running;
        running = false;

        if (eventTask != null) {
            eventTask.cancel();
            eventTask = null;
        }
        if (waveTask != null) {
            waveTask.cancel();
            waveTask = null;
        }

        if (bossBar != null) {
            bossBar.removeAll();
            bossBar.setVisible(false);
        }

        if (announce && wasRunning && eventWorld != null) {
            eventWorld.getPlayers().forEach(player -> {
                player.sendMessage(message("messages.event-stopped"));
                player.sendTitle(color("&c&lEVENT OVER"),
                        color("&7The Halloween night has ended."), 10, 50, 20);
            });
        }

        eventWorld = null;
    }

    private String message(String path) {
        return color(getConfig().getString("settings.prefix", "&6&lHalloween &8» ")
                + getConfig().getString(path, "&cMessage unavailable."));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private BarColor parseColor(String text) {
        try {
            return BarColor.valueOf(text.toUpperCase());
        } catch (Exception exception) {
            return BarColor.PURPLE;
        }
    }

    private BarStyle parseStyle(String text) {
        try {
            return BarStyle.valueOf(text.toUpperCase());
        } catch (Exception exception) {
            return BarStyle.SEGMENTED_10;
        }
    }
}
