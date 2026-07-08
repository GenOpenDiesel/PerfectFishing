package pl.bfscode.perfectfishing;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class PerfectFishingPlugin extends JavaPlugin implements Listener {

    private static final long CATCH_BLOCK_MS = 2500L;

    private final Map<UUID, Challenge> challenges = new ConcurrentHashMap<>();
    private final Map<UUID, Long> blockedCatches = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerStats> stats = new ConcurrentHashMap<>();
    private final Map<UUID, Detector> detectors = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private File statsFile;
    private FileConfiguration statsConfig;
    private int unsavedStats;

    private boolean enabled;
    private double chancePercent;
    private int statsSaveEvery;
    private int barLength;
    private int targetWidth;
    private int maxTicks;
    private int updatePeriodTicks;
    private boolean randomTarget;
    private String title;
    private String successTitle;
    private String failTitle;
    private String successSubtitle;
    private String failSubtitle;
    private String successSound;
    private String failSound;

    private boolean acEnabled;
    private int acMinSamples;
    private int acWindowSize;
    private double acMaxSuccessRate;
    private double acMinReactionStddevMs;
    private long acHumanFloorMs;
    private double acMaxInstantFraction;
    private boolean acRequireBoth;
    private long acAlertCooldownMs;
    private boolean acNotifyStaff;
    private boolean acLogToFile;
    private java.util.List<String> acCommands;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadStats();
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        registerPlaceholders();
        getLogger().info("PerfectFishing enabled.");
    }

    @Override
    public void onDisable() {
        challenges.values().forEach(Challenge::cancelTask);
        challenges.clear();
        blockedCatches.clear();
        detectors.clear();
        saveStats();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("perfectfishing.admin")) {
                sender.sendMessage(ChatColor.RED + "Brak permisji.");
                return true;
            }
            reloadConfig();
            loadSettings();
            sender.sendMessage(ChatColor.GREEN + "PerfectFishing przeladowany.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "/" + label + " reload");
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFishBite(PlayerFishEvent event) {
        if (!enabled || event.getState() != PlayerFishEvent.State.BITE) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Always clear any leftover challenge from a previous bite before we
        // decide whether this bite starts a new one, otherwise a failed chance
        // roll would leave the old challenge (and its task) running.
        Challenge previous = challenges.remove(uuid);
        if (previous != null) {
            previous.cancelTask();
        }

        if (random.nextDouble() * 100.0D >= chancePercent) {
            return;
        }

        int safeTargetWidth = Math.max(1, Math.min(targetWidth, barLength));
        int targetStart = randomTarget
                ? random.nextInt(Math.max(1, barLength - safeTargetWidth + 1))
                : Math.max(0, (barLength - safeTargetWidth) / 2);

        Challenge challenge = new Challenge(uuid, targetStart, safeTargetWidth);
        challenges.put(uuid, challenge);
        playMovingTitle(player, challenge);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onFishCatch(PlayerFishEvent event) {
        if (!enabled || event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }

        Player player = event.getPlayer();
        Challenge challenge = challenges.remove(player.getUniqueId());
        if (challenge == null) {
            if (isCatchBlocked(player.getUniqueId())) {
                cancelCatch(event);
            }
            return;
        }

        challenge.cancelTask();
        long reactionMs = System.currentTimeMillis() - challenge.startMillis;
        if (challenge.armed && challenge.isPerfect()) {
            recordSuccess(player.getUniqueId());
            feedDetector(player, reactionMs, true);
            sendTitle(player, successTitle, successSubtitle, 0, 16, 4);
            playConfiguredSound(player, successSound);
            return;
        }

        recordFail(player.getUniqueId());
        feedDetector(player, reactionMs, false);
        blockNextCatch(player.getUniqueId());
        cancelCatch(event);
        sendTitle(player, failTitle, failSubtitle, 0, 18, 6);
        playConfiguredSound(player, failSound);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNonCatchReel(PlayerFishEvent event) {
        if (!enabled) {
            return;
        }
        PlayerFishEvent.State state = event.getState();
        if (state != PlayerFishEvent.State.REEL_IN
                && state != PlayerFishEvent.State.FAILED_ATTEMPT
                && state != PlayerFishEvent.State.CAUGHT_ENTITY
                && state != PlayerFishEvent.State.IN_GROUND) {
            return;
        }

        Challenge challenge = challenges.remove(event.getPlayer().getUniqueId());
        if (challenge != null) {
            challenge.cancelTask();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Challenge challenge = challenges.remove(uuid);
        if (challenge != null) {
            challenge.cancelTask();
        }
        detectors.remove(uuid);
        blockedCatches.remove(uuid);
    }

    private void playMovingTitle(Player player, Challenge challenge) {
        TaskHandle task = scheduleRepeating(player, () -> {
            if (!player.isOnline()) {
                challenges.remove(challenge.playerId);
                challenge.cancelTask();
                return;
            }

            if (challenge.ageTicks > maxTicks) {
                challenges.remove(challenge.playerId);
                recordFail(challenge.playerId);
                feedDetector(player, -1L, false);
                blockNextCatch(challenge.playerId);
                challenge.cancelTask();
                sendTitle(player, failTitle, failSubtitle, 0, 18, 6);
                playConfiguredSound(player, failSound);
                return;
            }

            // The minigame is only "armed" once the bar has actually been shown
            // at least once. A catch that lands before this (reeling on the same
            // tick as the bite, before the marker moves) can never be a perfect.
            challenge.armed = true;
            sendTitle(player, title, renderBar(challenge), 0, updatePeriodTicks + 4, 0);
            challenge.step();
        }, 0L, updatePeriodTicks);
        challenge.task = task;
    }

    private String renderBar(Challenge challenge) {
        StringBuilder builder = new StringBuilder();
        builder.append(ChatColor.DARK_GRAY).append("[");
        for (int i = 0; i < barLength; i++) {
            if (i == challenge.marker) {
                builder.append(ChatColor.YELLOW).append("|");
            } else if (challenge.isTarget(i)) {
                builder.append(ChatColor.GREEN).append("-");
            } else {
                builder.append(ChatColor.GRAY).append("-");
            }
        }
        builder.append(ChatColor.DARK_GRAY).append("]");
        return builder.toString();
    }

    private void sendTitle(Player player, String main, String sub, int fadeIn, int stay, int fadeOut) {
        player.sendTitle(color(main), color(sub), fadeIn, stay, fadeOut);
    }

    private void playConfiguredSound(Player player, String soundName) {
        if (soundName == null || soundName.isBlank() || soundName.equalsIgnoreCase("none")) {
            return;
        }
        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            player.playSound(player.getLocation(), sound, 0.9f, 1.1f);
        } catch (IllegalArgumentException ignored) {
            getLogger().warning("Unknown sound in config: " + soundName);
        }
    }

    private void cancelCatch(PlayerFishEvent event) {
        event.setCancelled(true);
        event.setExpToDrop(0);
        Entity caught = event.getCaught();
        if (caught instanceof Item item) {
            item.remove();
        }
    }

    private void blockNextCatch(UUID playerId) {
        blockedCatches.put(playerId, System.currentTimeMillis() + CATCH_BLOCK_MS);
    }

    private boolean isCatchBlocked(UUID playerId) {
        Long blockedUntil = blockedCatches.get(playerId);
        if (blockedUntil == null) {
            return false;
        }
        if (blockedUntil < System.currentTimeMillis()) {
            blockedCatches.remove(playerId);
            return false;
        }
        // Keep the block active for its full duration so a fast burst of
        // clicks cannot slip a catch through after a single blocked attempt.
        // Refresh the timer so continuous spamming can never simply wait it out.
        blockedCatches.put(playerId, System.currentTimeMillis() + CATCH_BLOCK_MS);
        return true;
    }

    private TaskHandle scheduleRepeating(Player player, Runnable runnable, long delayTicks, long periodTicks) {
        try {
            Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
            Object task = scheduler.getClass()
                    .getMethod("runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class)
                    .invoke(scheduler, this, (Consumer<Object>) ignored -> runnable.run(), (Runnable) () -> {
                    }, delayTicks, periodTicks);
            return () -> {
                try {
                    task.getClass().getMethod("cancel").invoke(task);
                } catch (ReflectiveOperationException exception) {
                    getLogger().warning("Could not cancel Folia task: " + exception.getMessage());
                }
            };
        } catch (ReflectiveOperationException ignored) {
            org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler()
                    .runTaskTimer(this, runnable, delayTicks, periodTicks);
            return task::cancel;
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private void loadSettings() {
        enabled = getConfig().getBoolean("enabled", true);
        chancePercent = clampDouble(getConfig().getDouble("chance-percent", 35.0D), 0.0D, 100.0D);
        statsSaveEvery = clamp(getConfig().getInt("stats-save-every", 25), 1, 1000);
        barLength = clamp(getConfig().getInt("bar-length", 21), 7, 41);
        targetWidth = clamp(getConfig().getInt("target-width", 3), 1, barLength);
        maxTicks = clamp(getConfig().getInt("max-ticks", 42), 10, 200);
        updatePeriodTicks = clamp(getConfig().getInt("update-period-ticks", 1), 1, 10);
        randomTarget = getConfig().getBoolean("random-target", true);
        title = getConfig().getString("messages.title", "&bZACINAJ!");
        successTitle = getConfig().getString("messages.success-title", "&aPERFECT!");
        successSubtitle = getConfig().getString("messages.success-subtitle", "&7Ryba zlowiona.");
        failTitle = getConfig().getString("messages.fail-title", "&cRYBA UCIEKLA!");
        failSubtitle = getConfig().getString("messages.fail-subtitle", "&7Nie trafiles w zielone pole.");
        successSound = getConfig().getString("sounds.success", "ENTITY_EXPERIENCE_ORB_PICKUP");
        failSound = getConfig().getString("sounds.fail", "ENTITY_ITEM_BREAK");

        acEnabled = getConfig().getBoolean("anticheat.enabled", true);
        acMinSamples = clamp(getConfig().getInt("anticheat.min-samples", 25), 5, 1000);
        acWindowSize = clamp(getConfig().getInt("anticheat.window-size", 40), acMinSamples, 1000);
        acMaxSuccessRate = clampDouble(getConfig().getDouble("anticheat.max-success-rate", 94.0D), 1.0D, 100.0D);
        acMinReactionStddevMs = clampDouble(getConfig().getDouble("anticheat.min-reaction-stddev-ms", 45.0D), 0.0D, 5000.0D);
        acHumanFloorMs = clamp(getConfig().getInt("anticheat.human-floor-ms", 120), 0, 5000);
        acMaxInstantFraction = clampDouble(getConfig().getDouble("anticheat.max-instant-fraction", 0.5D), 0.0D, 1.0D);
        acRequireBoth = getConfig().getBoolean("anticheat.require-both-signals", true);
        acAlertCooldownMs = clamp(getConfig().getInt("anticheat.alert-cooldown-seconds", 120), 0, 86400) * 1000L;
        acNotifyStaff = getConfig().getBoolean("anticheat.notify-staff", true);
        acLogToFile = getConfig().getBoolean("anticheat.log-to-file", true);
        acCommands = getConfig().getStringList("anticheat.commands");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void loadStats() {
        statsFile = new File(getDataFolder(), "stats.yml");
        statsConfig = YamlConfiguration.loadConfiguration(statsFile);
        if (!statsConfig.isConfigurationSection("players")) {
            return;
        }

        for (String key : statsConfig.getConfigurationSection("players").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                PlayerStats playerStats = new PlayerStats();
                String path = "players." + key + ".";
                playerStats.attempts = statsConfig.getInt(path + "attempts");
                playerStats.successes = statsConfig.getInt(path + "successes");
                playerStats.fails = statsConfig.getInt(path + "fails");
                playerStats.streak = statsConfig.getInt(path + "streak");
                playerStats.bestStreak = statsConfig.getInt(path + "best-streak");
                playerStats.flags = statsConfig.getInt(path + "flags");
                stats.put(uuid, playerStats);
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Invalid UUID in stats.yml: " + key);
            }
        }
    }

    private void saveStats() {
        if (statsFile == null) {
            return;
        }
        statsConfig = new YamlConfiguration();
        stats.forEach((uuid, playerStats) -> {
            String path = "players." + uuid + ".";
            statsConfig.set(path + "attempts", playerStats.attempts);
            statsConfig.set(path + "successes", playerStats.successes);
            statsConfig.set(path + "fails", playerStats.fails);
            statsConfig.set(path + "streak", playerStats.streak);
            statsConfig.set(path + "best-streak", playerStats.bestStreak);
            statsConfig.set(path + "flags", playerStats.flags);
        });

        try {
            statsConfig.save(statsFile);
        } catch (IOException exception) {
            getLogger().warning("Could not save stats.yml: " + exception.getMessage());
        }
    }

    private void recordSuccess(UUID playerId) {
        PlayerStats playerStats = stats.computeIfAbsent(playerId, ignored -> new PlayerStats());
        playerStats.attempts++;
        playerStats.successes++;
        playerStats.streak++;
        playerStats.bestStreak = Math.max(playerStats.bestStreak, playerStats.streak);
        markStatsDirty();
    }

    private void recordFail(UUID playerId) {
        PlayerStats playerStats = stats.computeIfAbsent(playerId, ignored -> new PlayerStats());
        playerStats.attempts++;
        playerStats.fails++;
        playerStats.streak = 0;
        markStatsDirty();
    }

    private void markStatsDirty() {
        unsavedStats++;
        if (unsavedStats >= statsSaveEvery) {
            saveStats();
            unsavedStats = 0;
        }
    }

    private PlayerStats getStats(UUID playerId) {
        return stats.getOrDefault(playerId, PlayerStats.EMPTY);
    }

    private void feedDetector(Player player, long reactionMs, boolean perfect) {
        if (!acEnabled) {
            return;
        }
        Detector detector = detectors.computeIfAbsent(player.getUniqueId(), ignored -> new Detector());
        detector.add(reactionMs, perfect, acWindowSize);
        evaluate(player, detector);
    }

    private void evaluate(Player player, Detector detector) {
        int total = detector.samples.size();
        if (total < acMinSamples) {
            return;
        }

        int perfects = 0;
        int timed = 0;
        int instant = 0;
        double sum = 0.0D;
        for (long[] sample : detector.samples) {
            if (sample[1] == 1L) {
                perfects++;
            }
            if (sample[0] >= 0L) {
                timed++;
                sum += sample[0];
                if (sample[0] < acHumanFloorMs) {
                    instant++;
                }
            }
        }

        double successRate = perfects * 100.0D / total;
        double mean = timed > 0 ? sum / timed : 0.0D;
        double variance = 0.0D;
        if (timed > 1) {
            for (long[] sample : detector.samples) {
                if (sample[0] >= 0L) {
                    double diff = sample[0] - mean;
                    variance += diff * diff;
                }
            }
            variance /= timed;
        }
        double stddev = Math.sqrt(variance);
        double instantFraction = timed > 0 ? (double) instant / timed : 0.0D;

        boolean superhuman = successRate >= acMaxSuccessRate;
        boolean tooConsistent = timed >= 2 && stddev <= acMinReactionStddevMs;
        boolean tooFast = timed >= 2 && instantFraction >= acMaxInstantFraction;

        boolean flagged = acRequireBoth
                ? superhuman && (tooConsistent || tooFast)
                : superhuman || tooConsistent || tooFast;

        detector.flagged = flagged;
        if (!flagged) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - detector.lastAlertMs < acAlertCooldownMs) {
            return;
        }
        detector.lastAlertMs = now;

        StringBuilder reason = new StringBuilder();
        if (superhuman) {
            reason.append(String.format(java.util.Locale.US, "trafienia %.0f%%", successRate));
        }
        if (tooConsistent) {
            if (reason.length() > 0) {
                reason.append(", ");
            }
            reason.append(String.format(java.util.Locale.US, "rozrzut reakcji %.0fms", stddev));
        }
        if (tooFast) {
            if (reason.length() > 0) {
                reason.append(", ");
            }
            reason.append(String.format(java.util.Locale.US, "%.0f%% reakcji <%dms", instantFraction * 100.0D, acHumanFloorMs));
        }

        recordFlag(player.getUniqueId());
        writeAbuseLog(player, reason.toString(), successRate, stddev, total);
        fireAlert(player, reason.toString(), successRate, stddev);
    }

    private void writeAbuseLog(Player player, String reason, double successRate, double stddev, int samples) {
        if (!acLogToFile) {
            return;
        }
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String line = String.format(java.util.Locale.US,
                "[%s] %s (%s) - %s - trafienia %.1f%%, rozrzut %.0fms, prob %d%n",
                timestamp, player.getName(), player.getUniqueId(), reason, successRate, stddev, samples);
        runAsync(() -> {
            File logFile = new File(getDataFolder(), "naduzycia.log");
            try {
                if (!getDataFolder().exists()) {
                    getDataFolder().mkdirs();
                }
                java.nio.file.Files.writeString(logFile.toPath(), line,
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException exception) {
                getLogger().warning("Nie mozna zapisac naduzycia.log: " + exception.getMessage());
            }
        });
    }

    private void runAsync(Runnable runnable) {
        try {
            Object scheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            scheduler.getClass()
                    .getMethod("runNow", Plugin.class, Consumer.class)
                    .invoke(scheduler, this, (Consumer<Object>) ignored -> runnable.run());
        } catch (ReflectiveOperationException ignored) {
            Bukkit.getScheduler().runTaskAsynchronously(this, runnable);
        }
    }

    private void fireAlert(Player player, String reason, double successRate, double stddev) {
        String name = player.getName();
        getLogger().warning("Mozliwy automat na ryby: " + name + " (" + reason + ")");
        String message = color("&8[&bPerfectFishing&8] &e" + name
                + " &7moze uzywac automatu na ryby &8(" + reason + ")");
        runGlobal(() -> {
            if (acNotifyStaff) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.hasPermission("perfectfishing.alerts")) {
                        online.sendMessage(message);
                    }
                }
            }
            for (String command : acCommands) {
                if (command == null || command.isBlank()) {
                    continue;
                }
                String parsed = command
                        .replace("%player%", name)
                        .replace("%success_rate%", String.format(java.util.Locale.US, "%.1f", successRate))
                        .replace("%reaction_stddev%", String.format(java.util.Locale.US, "%.0f", stddev));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
            }
        });
    }

    private void runGlobal(Runnable runnable) {
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            scheduler.getClass()
                    .getMethod("run", Plugin.class, Consumer.class)
                    .invoke(scheduler, this, (Consumer<Object>) ignored -> runnable.run());
        } catch (ReflectiveOperationException ignored) {
            Bukkit.getScheduler().runTask(this, runnable);
        }
    }

    private void recordFlag(UUID playerId) {
        PlayerStats playerStats = stats.computeIfAbsent(playerId, ignored -> new PlayerStats());
        playerStats.flags++;
        markStatsDirty();
    }

    private void registerPlaceholders() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info("PlaceholderAPI not found. Placeholders disabled.");
            return;
        }
        new PerfectFishingExpansion().register();
        getLogger().info("PlaceholderAPI placeholders registered.");
    }

    private final class Challenge {
        private final UUID playerId;
        private final int targetStart;
        private final int targetWidth;
        private final long startMillis = System.currentTimeMillis();
        private int marker;
        private int direction = 1;
        private int ageTicks;
        private boolean armed;
        private TaskHandle task;

        private Challenge(UUID playerId, int targetStart, int targetWidth) {
            this.playerId = playerId;
            this.targetStart = targetStart;
            this.targetWidth = targetWidth;
        }

        private boolean isTarget(int position) {
            return position >= targetStart && position < targetStart + targetWidth;
        }

        private boolean isPerfect() {
            return isTarget(marker);
        }

        private void step() {
            ageTicks += updatePeriodTicks;
            marker += direction;
            if (marker >= barLength - 1) {
                marker = barLength - 1;
                direction = -1;
            } else if (marker <= 0) {
                marker = 0;
                direction = 1;
            }
        }

        private void cancelTask() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }

    private interface TaskHandle {
        void cancel();
    }

    private static final class Detector {
        private final java.util.ArrayDeque<long[]> samples = new java.util.ArrayDeque<>();
        private long lastAlertMs;
        private boolean flagged;

        private void add(long reactionMs, boolean perfect, int window) {
            samples.addLast(new long[]{reactionMs, perfect ? 1L : 0L});
            while (samples.size() > window) {
                samples.removeFirst();
            }
        }
    }

    private static final class PlayerStats {
        private static final PlayerStats EMPTY = new PlayerStats();
        private int attempts;
        private int successes;
        private int fails;
        private int streak;
        private int bestStreak;
        private int flags;

        private int successRate() {
            if (attempts <= 0) {
                return 0;
            }
            return (int) Math.round((successes * 100.0D) / attempts);
        }
    }

    private final class PerfectFishingExpansion extends PlaceholderExpansion {

        @Override
        public String getIdentifier() {
            return "perfectfishing";
        }

        @Override
        public String getAuthor() {
            return "BFSCode";
        }

        @Override
        public String getVersion() {
            return PerfectFishingPlugin.this.getDescription().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            if (player == null) {
                return "";
            }

            PlayerStats playerStats = getStats(player.getUniqueId());
            return switch (params.toLowerCase()) {
                case "successes", "perfects" -> String.valueOf(playerStats.successes);
                case "fails" -> String.valueOf(playerStats.fails);
                case "attempts" -> String.valueOf(playerStats.attempts);
                case "streak" -> String.valueOf(playerStats.streak);
                case "best_streak" -> String.valueOf(playerStats.bestStreak);
                case "success_rate" -> String.valueOf(playerStats.successRate());
                case "flags" -> String.valueOf(playerStats.flags);
                case "suspicious" -> {
                    Detector detector = detectors.get(player.getUniqueId());
                    yield String.valueOf(detector != null && detector.flagged);
                }
                default -> "";
            };
        }
    }
}
