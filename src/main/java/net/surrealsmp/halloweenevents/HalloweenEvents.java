
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
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Particle;
import org.bukkit.Sound;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HalloweenEvents extends JavaPlugin {

    private BossBar bossBar;
    private BukkitTask eventTask;
    private BukkitTask waveTask;
    private World eventWorld;
    private long eventEndTime;
    private long eventDurationMillis;
    private final Random random = new Random();
    private boolean running;

    private static final Pattern DURATION_PATTERN =
            Pattern.compile("^(\\d+)(s|m|h)$", Pattern.CASE_INSENSITIVE);

    @Override
    public void onEnable() {
        saveDefaultConfig();

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
            sender.sendMessage(color("&e/halloween start [duration] &7- Start event"));
            sender.sendMessage(color("&e/halloween stop &7- Stop event"));
            sender.sendMessage(color("&e/halloween status &7- Event status"));
            sender.sendMessage(color("&e/halloween reload &7- Reload config"));
            sender.sendMessage(color("&7Durations: 30s, 10m, 2h"));
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

                long durationMillis = getConfig().getLong(
                        "event.duration-minutes", 30) * 60_000L;

                World world = null;

                for (int i = 1; i < args.length; i++) {
                    Matcher matcher = DURATION_PATTERN.matcher(args[i]);

                    if (matcher.matches()) {
                        try {
                            long amount = Long.parseLong(matcher.group(1));
                            if (amount <= 0) {
                                sender.sendMessage(color("&cDuration must be greater than zero."));
                                return true;
                            }

                            long multiplier = switch (matcher.group(2).toLowerCase()) {
                                case "s" -> 1_000L;
                                case "m" -> 60_000L;
                                case "h" -> 3_600_000L;
                                default -> 0L;
                            };

                            durationMillis = Math.multiplyExact(amount, multiplier);
                        } catch (ArithmeticException | NumberFormatException ex) {
                            sender.sendMessage(color("&cThat duration is too large."));
                            return true;
                        }
                    } else {
                        world = Bukkit.getWorld(args[i]);
                        if (world == null) {
                            sender.sendMessage(color("&cInvalid duration or world: &f" + args[i]));
                            sender.sendMessage(color("&7Examples: /halloween start 10m"));
                            return true;
                        }
                    }
                }

                if (world == null && sender instanceof Player player) {
                    world = player.getWorld();
                }

                if (world == null) {
                    String configuredWorld = getConfig().getString("event.world", "");
                    if (configuredWorld != null && !configuredWorld.isBlank()) {
                        world = Bukkit.getWorld(configuredWorld);
                    }
                }

                if (world == null) {
                    sender.sendMessage(color("&cConsole: use /halloween start 10m world"));
                    return true;
                }

                startEvent(world, durationMillis);
                sender.sendMessage(color("&6Halloween event started for &e"
                        + formatDuration(durationMillis) + "&6."));
            }

            case "stop" -> {
                if (!running) {
                    sender.sendMessage(message("messages.event-not-running"));
                    return true;
                }
                stopEvent(true);
            }

            case "status" -> {
                sender.sendMessage(running
                        ? color("&6Halloween event: &aRUNNING &7(World: "
                        + eventWorld.getName() + ", Time left: "
                        + formatDuration(Math.max(0, eventEndTime - System.currentTimeMillis()))
                        + ")")
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

    private void startEvent(World world, long durationMillis) {
        running = true;
        eventWorld = world;
        eventDurationMillis = durationMillis;
        eventEndTime = System.currentTimeMillis() + durationMillis;

        if (getConfig().getBoolean("event.force-night", true)) {
            world.setTime(18000);
            world.setStorm(true);
            world.setThundering(true);
        }

        for (Player player : world.getPlayers()) {
            player.sendTitle(color("&6&lHALLOWEEN"),
                    color("&7The spooky night has begun!"), 10, 60, 20);
            player.playSound(player.getLocation(),
                    Sound.ENTITY_WITHER_AMBIENT, 0.6f, 0.8f);
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
                    eventWorld.spawnParticle(Particle.SOUL, location,
                            4, 0.5, 0.5, 0.5, 0.01);
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

        EntityType[] types = {
                EntityType.ZOMBIE, EntityType.SKELETON,
                EntityType.SPIDER, EntityType.WITCH
        };

        for (Player player : eventWorld.getPlayers()) {
            for (int i = 0; i < maximum; i++) {
                Location base = player.getLocation();
                double angle = random.nextDouble() * Math.PI * 2;
                double distance = 5 + random.nextDouble() * 8;

                Location spawn = base.clone().add(
                        Math.cos(angle) * distance, 0,
                        Math.sin(angle) * distance);

                spawn.setY(eventWorld.getHighestBlockYAt(spawn) + 1);

                if (!spawn.getBlock().isPassable()) continue;

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

        double remaining = Math.max(0, eventEndTime - System.currentTimeMillis());
        bossBar.setProgress(Math.max(0, Math.min(1,
                remaining / (double) eventDurationMillis)));

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
            for (Player player : eventWorld.getPlayers()) {
                player.sendMessage(message("messages.event-stopped"));
                player.sendTitle(color("&c&lEVENT OVER"),
                        color("&7The Halloween night has ended."), 10, 50, 20);
            }
        }

        eventWorld = null;
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1_000L;
        if (seconds >= 3_600 && seconds % 3_600 == 0) {
            return (seconds / 3_600) + "h";
        }
        if (seconds >= 60 && seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    private String message(String path) {
        return color(getConfig().getString("settings.prefix",
                "&6&lHalloween &8» ")
                + getConfig().getString(path, "&cMessage unavailable."));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&',
                text == null ? "" : text);
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
