package com.mcsimonflash.sponge.activetime.managers;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mcsimonflash.sponge.activetime.ActiveTime;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.serializer.TextSerializers;
import org.spongepowered.api.util.Identifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Util {

    public static final Text prefix = toText("&1[&9ActiveTime&1]&r ");
    public static final int[] timeConst = {604800, 86400, 3600, 60, 1};
    public static final String[] unitAbbrev = {"w", "d", "h", "m", "s"};
    public static final Pattern timeFormat = Pattern.compile("(?:([0-9]+)w)?(?:([0-9]+)d)?(?:([0-9]+)h)?(?:([0-9]+)m)?(?:([0-9])+s)?");

    public static void initialize() {
        Config.readConfig();
        Storage.readStorage();
    }

    public static void startTasks() {
        if (Storage.updateTask != null) {
            Storage.updateTask.cancel();
        }
        if (Storage.saveTask != null) {
            Storage.saveTask.cancel();
        }
        if (Storage.milestoneTask != null) {
            Storage.milestoneTask.cancel();
        }
        startUpdateTask();
        startSaveTask();
        startMilestoneTask();
    }

    public static HoconConfigurationLoader getLoader(Path path, boolean asset) throws IOException {
        try {
            if (Files.notExists(path)) {
                if (asset) {
                    Sponge.getAssetManager().getAsset(ActiveTime.getPlugin(), path.getFileName().toString()).get().copyToFile(path);
                } else {
                    Files.createFile(path);
                }
            }
            return HoconConfigurationLoader.builder().setPath(path).build();
        } catch (IOException e) {
            ActiveTime.getPlugin().getLogger().error("Error loading file! File:[" + path.getFileName().toString() + "]");
            throw e;
        }
    }

    public static String getFileName(Calendar calendar) {
        return calendar.get(Calendar.YEAR) + "-" + String.format("%02d", calendar.get(Calendar.MONTH) + 1) + "-" + String.format("%02d", calendar.get(Calendar.DAY_OF_MONTH));
    }

    public static Text toText(String msg) {
        return TextSerializers.FORMATTING_CODE.deserialize(msg);
    }

    public static int parseTime(String timeStr) {
        int time = 0;
        try {
            time = Integer.parseInt(timeStr);
        } catch (NumberFormatException ignored) {
            Matcher matcher = timeFormat.matcher(timeStr);
            if (matcher.matches()) {
                for (int i = 1; i <= 5; i++) {
                    if (matcher.group(i) != null) {
                        time += Integer.parseInt(matcher.group(i)) * timeConst[i - 1];
                    }
                }
            }
        }
        return time;
    }

    public static String printTime(int time) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (time / timeConst[i] > 0) {
                builder.append(time / timeConst[i]).append(unitAbbrev[i]);
                time = time % timeConst[i];
            }
        }
        return builder.toString();
    }

    public static void startNameTask(Player player) {
        Task.builder()
                .name("ActiveTime SetPlayerName Task (" + player.getName() + ")")
                .execute(task -> {
                    String name = Storage.getUsername(player.getUniqueId());
                    if (!player.getName().equals(name) && !Storage.setUsername(player.getUniqueId(), player.getName())) {
                        ActiveTime.getPlugin().getLogger().error("Error updating playername. | UUID:[" + player.getUniqueId() + "] OldName:[" + name + "] NewName:[" + player.getName() + "]");
                    }
                })
                .async()
                .submit(ActiveTime.getPlugin());
    }

    public static void startUpdateTask() {
        if (ActiveTime.isNucleusEnabled()) {
            Storage.updateTask = Task.builder()
                    .name("ActiveTime UpdatePlayerTimes Task (w/ Nucleus)")
                    .execute(task -> {
                        Map<UUID, Boolean> players = Maps.newHashMap();
                        Sponge.getServer().getOnlinePlayers().stream().filter(p -> p.hasPermission("activetime.log.base")).forEach(p -> players.put(p.getUniqueId(), !NucleusIntegration.isPlayerAfk(p)));
                        Task.builder()
                                .name("ActiveTime UpdatePlayerTimes Task (Async Processor w/ Nucleus)")
                                .execute(t -> players.forEach((uuid, active) -> addCachedTime(uuid, 1, active)))
                                .async()
                                .submit(ActiveTime.getPlugin());
                    })
                    .interval(Config.updateInterval * 1000 - 1, TimeUnit.MILLISECONDS)
                    .submit(ActiveTime.getPlugin());
        } else {
            Storage.updateTask = Task.builder()
                    .name("ActiveTime UpdatePlayerTimes Task (w/out Nucleus)")
                    .execute(task -> {
                        List<UUID> players = Sponge.getServer().getOnlinePlayers().stream().filter(p -> p.hasPermission("activetime.log.base")).map(Identifiable::getUniqueId).collect(Collectors.toList());
                        Task.builder()
                                .name("ActiveTime UpdatePlayerTimes Task (Async Processor w/out Nucleus)")
                                .execute(t -> players.forEach((uuid) -> addCachedTime(uuid, Config.updateInterval, true)))
                                .async()
                                .submit(ActiveTime.getPlugin());
                    })
                    .interval(Config.updateInterval * 1000 - 1, TimeUnit.MILLISECONDS)
                    .submit(ActiveTime.getPlugin());
        }
    }

    public static void addCachedTime(UUID uuid, int time, boolean active) {
        if (active) {
            Storage.activetimes.put(uuid, Storage.activetimes.getOrDefault(uuid, 0) + time);
        } else {
            Storage.afktimes.put(uuid, Storage.afktimes.getOrDefault(uuid, 0) + time);
        }
    }

    public static void startSaveTask() {
        Storage.saveTask = Task.builder()
                .name("ActiveTime SavePlayerTimes Task")
                .execute(task -> {
                    Map<UUID, Integer> activetimes = Maps.newHashMap(Storage.activetimes);
                    Storage.activetimes.clear();
                    Map<UUID, Integer> afktimes = Maps.newHashMap(Storage.afktimes);
                    Storage.afktimes.clear();
                    Task.builder()
                            .name("ActiveTime SavePlayerTimes Task (Async Processor)")
                            .execute(t -> {
                                activetimes.forEach((uuid, time) -> saveTime(uuid, time, true));
                                afktimes.forEach((uuid, time) -> saveTime(uuid, time, false));
                                if (!Storage.syncCurrentDate()) {
                                    ActiveTime.getPlugin().getLogger().error("Unable to resync date!");
                                }
                            })
                            .async()
                            .submit(ActiveTime.getPlugin());
                })
                .interval(Config.saveInterval * 1000 - 1, TimeUnit.MILLISECONDS)
                .submit(ActiveTime.getPlugin());
    }

    public static void saveTime(UUID uuid, int time, boolean active) {
        boolean daily = Storage.setDailyTime(uuid, Storage.getDailyTime(uuid, active) + time, active);
        boolean total = Storage.setTotalTime(uuid, Storage.getTotalTime(uuid, active) + time, active);
        if (!daily || !total) {
            ActiveTime.getPlugin().getLogger().error("Error saving activetime for uuid " + uuid + "!");
        }
    }

    public static void startMilestoneTask() {
        Storage.milestoneTask = Task.builder().name("ActiveTime SyncPlayerTimes Task")
                .execute(task -> {
                    List<Player> players = Lists.newArrayList(Sponge.getServer().getOnlinePlayers());
                    Task.builder()
                            .name("ActiveTime UpdatePlayerTimes Task (Async Processor)")
                            .execute(t -> players.forEach(Util::checkMilestones))
                            .async()
                            .submit(ActiveTime.getPlugin());
                })
                .interval(Config.milestoneInterval * 1000 - 1, TimeUnit.MILLISECONDS)
                .submit(ActiveTime.getPlugin());
    }

    public static void checkMilestones(Player player) {
        int activetime = Storage.getTotalTime(player.getUniqueId(), true);
        Storage.milestones.forEach(m -> m.process(player, activetime));
    }
}