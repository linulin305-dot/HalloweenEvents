
package net.surrealsmp.halloweenevents;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HalloweenEvents extends JavaPlugin implements Listener {

    private static final String GUI_TITLE = "§8Halloween Settings";
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("^(\\d+)(s|m|h)$", Pattern.CASE_INSENSITIVE);

    private BossBar bossBar;
    private BukkitTask eventTask;
    private BukkitTask waveTask;
    private World eventWorld;
    private long eventEndTime;
    private long eventDurationMillis;
    private final Random random = new Random();
    private boolean running;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);

        bossBar = Bukkit.createBossBar(
                color(getConfig().getString(
                        "event.bossbar.title", "&6&lHALLOWEEN EVENT")),
                parseColor(getConfig().getString(
                        "event.bossbar.color", "PURPLE")),
                parseStyle(getConfig().getString(
                        "event.bossbar.style", "SEGMENTED_10"))
        );

        getLogger().info("HalloweenEvents enabled.");
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
            sender.sendMessage(color("&e/halloween settings &7- Open settings GUI"));
            sender.sendMessage(color("&e/halloween start [duration] &7- Start event"));
            sender.sendMessage(color("&e/halloween stop &7- Stop event"));
            sender.sendMessage(color("&e/halloween status &7- Check event status"));
            sender.sendMessage(color("&e/halloween reload &7- Reload configuration"));
            sender.sendMessage(color("&7Duration examples: 30s, 10m, 2h"));
            return true;
        }

        if (!sender.hasPermission("halloweenevents.admin")) {
            sender.sendMessage(message("messages.no-permission"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "settings" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(color("&cOnly players can open the settings GUI."));
                    return true;
                }

                openSettings(player);
            }

            case "start" -> {
                if (running) {
                    sender.sendMessage(message("messages.event-already-running"));
                    return true;
                }

                long duration = getConfig().getLong(
                        "event.duration-minutes", 30) * 60_000L;

                World world = null;

                for (int i = 1; i < args.length; i++) {
                    Matcher matcher = DURATION_PATTERN.matcher(args[i]);

                    if (matcher.matches()) {
                        try {
                            long amount = Long.parseLong(matcher.group(1));

                            if (amount <= 0) {
                                sender.sendMessage(color(
                                        "&cDuration must be greater than zero."));
                                return true;
                            }

                            long multiplier = switch (
                                    matcher.group(2).toLowerCase()) {
                                case "s" -> 1_000L;
                                case "m" -> 60_000L;
                                case "h" -> 3_600_000L;
                                default -> 0L;
                            };

                            duration = Math.multiplyExact(amount, multiplier);
                        } catch (ArithmeticException | NumberFormatException ex) {
                            sender.sendMessage(color("&cDuration is too large."));
                            return true;
                        }
                    } else {
                        world = Bukkit.getWorld(args[i]);

                        if (world == null) {
                            sender.sendMessage(color(
                                    "&cInvalid duration or world: &f" + args[i]));
                            return true;
                        }
                    }
                }

                if (world == null && sender instanceof Player player) {
                    world = player.getWorld();
                }

                if (world == null) {
                    String configured = getConfig().getString("event.world", "");

                    if (configured != null && !configured.isBlank()) {
                        world = Bukkit.getWorld(configured);
                    }
                }

                if (world == null) {
                    sender.sendMessage(color(
                            "&cFrom console, specify a world: /halloween start 10m world"));
                    return true;
                }

                startEvent(world, duration);
                sender.sendMessage(color("&6Halloween event started for &e"
                        + formatDuration(duration) + "&6."));
            }

            case "stop" -> {
                if (!running) {
                    sender.sendMessage(message("messages.event-not-running"));
                    return true;
                }

                stopEvent(true);
                sender.sendMessage(color("&cHalloween event stopped."));
            }

            case "status" -> {
                if (running) {
                    sender.sendMessage(color("&6Event: &aRUNNING"
                            + " &7| World: " + eventWorld.getName()
                            + " | Time left: "
                            + formatDuration(Math.max(0,
                            eventEndTime - System.currentTimeMillis()))));
                } else {
                    sender.sendMessage(color("&6Event: &cSTOPPED"));
                }
            }

            case "reload" -> {
                reloadConfig();
                sender.sendMessage(message("messages.reloaded"));
            }

            default -> sender.sendMessage(color(
                    "&cUnknown command. Use /halloween help"));
        }

        return true;
    }

    // =========================
    // ADMIN SETTINGS GUI
    // =========================

    private void openSettings(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_TITLE);

        int mobCount = getConfig().getInt(
                "event.mobs.max-extra-mobs-per-wave", 5);

        gui.setItem(10, item(Material.RED_STAINED_GLASS_PANE,
                "&c-1 Mob", "&7Click to decrease mobs per wave"));

        gui.setItem(13, item(Material.ZOMBIE_SPAWN_EGG,
                "&6&lMobs Per Wave",
                "&7Current amount: &e" + mobCount,
                "&7Change how many mobs spawn",
                "&7in each wave."));

        gui.setItem(16, item(Material.LIME_STAINED_GLASS_PANE,
                "&a+1 Mob", "&7Click to increase mobs per wave"));

        gui.setItem(21, item(Material.JACK_O_LANTERN,
                "&a&lStart Event",
                "&7Start the event in your current world",
                "&7Duration: &e"
                        + getConfig().getLong("event.duration-minutes", 30)
                        + " minutes"));

        gui.setItem(22, item(Material.BARRIER,
                "&c&lStop Event", "&7Stop the active Halloween event"));

        gui.setItem(23, item(Material.BOOK,
                "&b&lEvent Status",
                running ? "&7Status: &aRUNNING" : "&7Status: &cSTOPPED",
                running ? "&7World: &f" + eventWorld.getName()
                        : "&7No event is running"));

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!player.hasPermission("halloweenevents.admin")) {
            player.closeInventory();
            player.sendMessage(message("messages.no-permission"));
            return;
        }

        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        switch (event.getRawSlot()) {
            case 10 -> {
                int amount = getConfig().getInt(
                        "event.mobs.max-extra-mobs-per-wave", 5);

                amount = Math.max(1, amount - 1);
                getConfig().set("event.mobs.max-extra-mobs-per-wave", amount);
                saveConfig();
                openSettings(player);
            }

            case 16 -> {
                int amount = getConfig().getInt(
                        "event.mobs.max-extra-mobs-per-wave", 5);

                amount = Math.min(50, amount + 1);
                getConfig().set("event.mobs.max-extra-mobs-per-wave", amount);
                saveConfig();
                openSettings(player);
            }

            case 21 -> {
                if (running) {
                    player.sendMessage(message("messages.event-already-running"));
                    openSettings(player);
                    return;
                }

                long duration = getConfig().getLong(
                        "event.duration-minutes", 30) * 60_000L;

                startEvent(player.getWorld(), duration);
                player.sendMessage(color("&aHalloween event started!"));
                openSettings(player);
            }

            case 22 -> {
                if (!running) {
                    player.sendMessage(message("messages.event-not-running"));
                } else {
                    stopEvent(true);
                    player.sendMessage(color("&cHalloween event stopped."));
                }

                openSettings(player);
            }

            case 23 -> {
                player.sendMessage(running
                        ? color("&6Event: &aRUNNING &7| World: "
                        + eventWorld.getName() + " | Time left: "
                        + formatDuration(Math.max(0,
                        eventEndTime - System.currentTimeMillis())))
                        : color("&6Event: &cSTOPPED"));
            }

            default -> {
                // Other GUI slots do nothing.
            }
        }
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(Arrays.stream(lore)
                    .map(this::color)
                    .toList());
            stack.setItemMeta(meta);
        }

        return stack;
    }

    // =========================
    // EVENT LOGIC
    // =========================

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
                    org.bukkit.Sound.ENTITY_WITHER_AMBIENT, 0.6f, 0.8f);
        }

        if (getConfig().getBoolean("event.bossbar.enabled", true)) {
            bossBar.setVisible(true);
            updateBossBar();
        }

        eventTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!running) {
                return;
            }

            if (System.currentTimeMillis() >= eventEndTime) {
                stopEvent(true);
                return;
            }

            updateBossBar();

            if (getConfig().getBoolean("event.effects.particles", true)) {
                for (Player player : eventWorld.getPlayers()) {
                    eventWorld.spawnParticle(
                            org.bukkit.Particle.SOUL,
                            player.getLocation().add(0, 2, 0),
                            4, 0.5, 0.5, 0.5, 0.01);
                }
            }

            if (getConfig().getBoolean("event.effects.spooky-sounds", true)) {
                for (Player player : eventWorld.getPlayers()) {
                    if (random.nextInt(10) == 0) {
                        player.playSound(player.getLocation(),
                                org.bukkit.Sound.AMBIENT_CAVE, 0.5f, 0.7f);
                    }
                }
            }
        }, 20L, 20L);

        if (getConfig().getBoolean("event.mobs.enabled", true)) {
            long interval = Math.max(1,
                    getConfig().getLong(
                            "event.mobs.spawn-interval-seconds", 20));

            waveTask = Bukkit.getScheduler().runTaskTimer(
                    this, this::spawnWave, interval * 20L, interval * 20L);
        }
    }

    private void spawnWave() {
        if (!running || eventWorld == null) {
            return;
        }

        int maximum = Math.max(1, Math.min(50,
                getConfig().getInt(
                        "event.mobs.max-extra-mobs-per-wave", 5)));

        EntityType[] types = {
                EntityType.ZOMBIE,
                EntityType.SKELETON,
                EntityType.SPIDER,
                EntityType.WITCH
        };

        for (Player player : eventWorld.getPlayers()) {
            for (int i = 0; i < maximum; i++) {
                Location base = player.getLocation();

                double angle = random.nextDouble() * Math.PI * 2;
                double distance = 5 + random.nextDouble() * 8;

                int x = base.getBlockX() + (int) Math.round(
                        Math.cos(angle) * distance);
                int z = base.getBlockZ() + (int) Math.round(
                        Math.sin(angle) * distance);

                // Find the highest ground at these coordinates.
                int y = eventWorld.getHighestBlockYAt(x, z);

                Location ground = new Location(eventWorld, x, y, z);
                Location spawn = ground.clone().add(0.5, 1, 0.5);

                // Surface only: solid ground with two clear blocks above it.
                if (!ground.getBlock().getType().isSolid()) {
                    continue;
                }

                if (!spawn.getBlock().isPassable()
                        || !spawn.clone().add(0, 1, 0).getBlock().isPassable()) {
                    continue;
                }

                // Prevent spawning in water or lava.
                if (spawn.getBlock().isLiquid()
                        || spawn.clone().add(0, 1, 0).getBlock().isLiquid()) {
                    continue;
                }

                LivingEntity mob = (LivingEntity) eventWorld.spawnEntity(
                        spawn, types[random.nextInt(types.length)]);

                if (getConfig().getBoolean("event.effects.mob-buffs", true)) {
                    mob.setCustomName(color(
                            "&6Halloween " + mob.getType().name()));
                    mob.setCustomNameVisible(true);
                    mob.setGlowing(true);
                    mob.setHealth(Math.min(
                            mob.getMaxHealth(), mob.getHealth() + 4));
                }
            }
        }
    }

    private void updateBossBar() {
        if (bossBar == null || eventWorld == null) {
            return;
        }

        double remaining = Math.max(
                0, eventEndTime - System.currentTimeMillis());

        bossBar.setProgress(Math.max(0, Math.min(
                1, remaining / (double) eventDurationMillis)));

        bossBar.setTitle(color(getConfig().getString(
                "event.bossbar.title",
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
                        color("&7The Halloween night has ended."),
                        10, 50, 20);
            }
        }

        eventWorld = null;
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000L;

        if (seconds >= 3600 && seconds % 3600 == 0) {
            return (seconds / 3600) + "h";
        }

        if (seconds >= 60 && seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }

        return seconds + "s";
    }

    private String message(String path) {
        return color(getConfig().getString(
                "settings.prefix", "&6&lHalloween &8» ")
                + getConfig().getString(
                path, "&cMessage unavailable."));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes(
                '&', text == null ? "" : text);
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
