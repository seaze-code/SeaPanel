package xyz.seaze.seapanel.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.management.OperatingSystemMXBean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ServerService {

    private final Plugin plugin;
    private final long startTimeMillis;

    public ServerService(Plugin plugin) {
        this.plugin = plugin;
        this.startTimeMillis = System.currentTimeMillis();
    }

    public <T> T runSync(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return task.call();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    public JsonObject getServerStats(String serverDisplayName) throws Exception {
        return runSync(() -> {
            JsonObject root = new JsonObject();
            root.addProperty("serverName", serverDisplayName);
            root.addProperty("mcVersion", Bukkit.getMinecraftVersion());
            root.addProperty("serverVersion", Bukkit.getName() + " " + Bukkit.getVersion());
            root.addProperty("motd", PlainTextComponentSerializer.plainText().serialize(Bukkit.motd()));
            root.addProperty("onlinePlayers", Bukkit.getOnlinePlayers().size());
            root.addProperty("maxPlayers", Bukkit.getMaxPlayers());
            root.addProperty("whitelistEnabled", Bukkit.hasWhitelist());
            root.addProperty("defaultGameMode", Bukkit.getDefaultGameMode().name());
            root.addProperty("uptimeSeconds", (System.currentTimeMillis() - startTimeMillis) / 1000L);

            double[] tps = Bukkit.getTPS();
            JsonArray tpsArr = new JsonArray();
            for (double v : tps) {
                tpsArr.add(Math.min(20.0, Math.round(v * 100.0) / 100.0));
            }
            root.add("tps", tpsArr);
            root.addProperty("mspt", Math.round(Bukkit.getAverageTickTime() * 100.0) / 100.0);

            Runtime rt = Runtime.getRuntime();
            long maxMb = rt.maxMemory() / (1024 * 1024);
            long totalMb = rt.totalMemory() / (1024 * 1024);
            long freeMb = rt.freeMemory() / (1024 * 1024);
            long usedMb = totalMb - freeMb;

            JsonObject mem = new JsonObject();
            mem.addProperty("usedMb", usedMb);
            mem.addProperty("allocatedMb", totalMb);
            mem.addProperty("maxMb", maxMb);
            root.add("memory", mem);

            double cpuLoad = -1;
            var osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof OperatingSystemMXBean sunOs) {
                double load = sunOs.getProcessCpuLoad();
                if (load >= 0) {
                    cpuLoad = Math.round(load * 1000.0) / 10.0;
                }
            }
            root.addProperty("cpuPercent", cpuLoad);
            root.addProperty("worldCount", Bukkit.getWorlds().size());
            root.addProperty("pluginCount", Bukkit.getPluginManager().getPlugins().length);
            return root;
        });
    }

    public JsonArray getOnlinePlayers() throws Exception {
        return runSync(() -> {
            JsonArray arr = new JsonArray();
            for (Player p : Bukkit.getOnlinePlayers()) {
                arr.add(serializePlayerSummary(p));
            }
            return arr;
        });
    }

    public JsonObject getPlayerDetail(String idOrName) throws Exception {
        return runSync(() -> {
            Player p = findOnlinePlayer(idOrName);
            if (p == null) {
                return null;
            }
            JsonObject obj = serializePlayerSummary(p);
            obj.addProperty("ip", p.getAddress() != null && p.getAddress().getAddress() != null
                    ? p.getAddress().getAddress().getHostAddress() : "unknown");
            obj.addProperty("expLevel", p.getLevel());
            obj.addProperty("isFlying", p.isFlying());

            PlayerInventory inv = p.getInventory();
            JsonArray items = new JsonArray();
            ItemStack[] contents = inv.getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack item = contents[slot];
                if (item != null && !item.getType().isAir()) {
                    items.add(serializeItem(slot, item));
                }
            }
            obj.add("inventory", items);

            JsonArray armor = new JsonArray();
            ItemStack[] armorContents = inv.getArmorContents();
            String[] armorLabels = {"Boots", "Leggings", "Chestplate", "Helmet"};
            for (int i = 0; i < armorContents.length; i++) {
                ItemStack item = armorContents[i];
                if (item != null && !item.getType().isAir()) {
                    JsonObject itemObj = serializeItem(i, item);
                    itemObj.addProperty("slotLabel", armorLabels[i]);
                    armor.add(itemObj);
                }
            }
            obj.add("armor", armor);
            return obj;
        });
    }

    public JsonObject performPlayerAction(String idOrName, String action, JsonObject body) throws Exception {
        return runSync(() -> {
            JsonObject res = new JsonObject();
            Player p = findOnlinePlayer(idOrName);
            if (p == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "Player is not online");
                return res;
            }

            switch (action.toLowerCase()) {
                case "kick" -> {
                    String reason = getString(body, "reason", "Kicked by server administrator");
                    p.kick(Component.text(reason));
                }
                case "ban" -> {
                    String reason = getString(body, "reason", "Banned by server administrator");
                    Bukkit.getBanList(BanList.Type.NAME).addBan(p.getName(), reason, (Date) null, "SeaPanel");
                    p.kick(Component.text("Banned: " + reason));
                }
                case "op" -> p.setOp(true);
                case "deop" -> p.setOp(false);
                case "heal" -> {
                    AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
                    double maxHp = attr != null ? attr.getValue() : 20.0;
                    p.setHealth(maxHp);
                    p.setFoodLevel(20);
                    p.setSaturation(20f);
                    p.setFireTicks(0);
                }
                case "feed" -> {
                    p.setFoodLevel(20);
                    p.setSaturation(20f);
                }
                case "clear_inv" -> p.getInventory().clear();
                case "toggle_fly" -> {
                    boolean next = !p.getAllowFlight();
                    p.setAllowFlight(next);
                    p.setFlying(next);
                }
                case "kill" -> p.setHealth(0.0);
                case "gamemode" -> {
                    String modeStr = getString(body, "mode", "SURVIVAL").toUpperCase();
                    GameMode gm = GameMode.valueOf(modeStr);
                    p.setGameMode(gm);
                }
                case "message" -> {
                    String msg = getString(body, "message", "");
                    if (!msg.isBlank()) {
                        p.sendMessage(Component.text("[Admin] " + msg));
                    }
                }
                case "teleport" -> {
                    if (body.has("targetPlayer")) {
                        Player target = findOnlinePlayer(body.get("targetPlayer").getAsString());
                        if (target == null) {
                            res.addProperty("ok", false);
                            res.addProperty("error", "Target player not found");
                            return res;
                        }
                        p.teleport(target.getLocation());
                    } else {
                        String worldName = getString(body, "world", p.getWorld().getName());
                        World w = Bukkit.getWorld(worldName);
                        if (w == null) w = p.getWorld();
                        double x = body.has("x") ? body.get("x").getAsDouble() : p.getLocation().getX();
                        double y = body.has("y") ? body.get("y").getAsDouble() : p.getLocation().getY();
                        double z = body.has("z") ? body.get("z").getAsDouble() : p.getLocation().getZ();
                        p.teleport(new Location(w, x, y, z));
                    }
                }
                default -> {
                    res.addProperty("ok", false);
                    res.addProperty("error", "Unknown action: " + action);
                    return res;
                }
            }
            res.addProperty("ok", true);
            return res;
        });
    }

    public JsonArray getBannedPlayers() throws Exception {
        return runSync(() -> {
            JsonArray arr = new JsonArray();
            for (BanEntry entry : Bukkit.getBanList(BanList.Type.NAME).getBanEntries()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("target", entry.getTarget());
                obj.addProperty("reason", entry.getReason() != null ? entry.getReason() : "");
                obj.addProperty("source", entry.getSource());
                obj.addProperty("created", entry.getCreated().toInstant().toString());
                arr.add(obj);
            }
            return arr;
        });
    }

    public void unbanPlayer(String target) throws Exception {
        runSync(() -> {
            Bukkit.getBanList(BanList.Type.NAME).pardon(target);
            return null;
        });
    }

    public JsonArray getWorlds() throws Exception {
        return runSync(() -> {
            JsonArray arr = new JsonArray();
            for (World w : Bukkit.getWorlds()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", w.getName());
                obj.addProperty("environment", w.getEnvironment().name());
                obj.addProperty("seed", String.valueOf(w.getSeed()));
                obj.addProperty("time", w.getTime());
                obj.addProperty("difficulty", w.getDifficulty().name());
                obj.addProperty("hasStorm", w.hasStorm());
                obj.addProperty("isThundering", w.isThundering());
                obj.addProperty("players", w.getPlayers().size());
                obj.addProperty("loadedChunks", w.getLoadedChunks().length);
                obj.addProperty("entities", w.getEntityCount());
                arr.add(obj);
            }
            return arr;
        });
    }

    public JsonObject performWorldAction(String worldName, String action, JsonObject body) throws Exception {
        return runSync(() -> {
            JsonObject res = new JsonObject();
            World w = Bukkit.getWorld(worldName);
            if (w == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "World not found: " + worldName);
                return res;
            }
            switch (action.toLowerCase()) {
                case "time" -> {
                    String preset = getString(body, "preset", "");
                    long ticks = switch (preset.toLowerCase()) {
                        case "day" -> 1000L;
                        case "noon" -> 6000L;
                        case "sunset" -> 12500L;
                        case "midnight" -> 18000L;
                        default -> body.has("ticks") ? body.get("ticks").getAsLong() : w.getTime();
                    };
                    w.setTime(ticks);
                }
                case "weather" -> {
                    String weather = getString(body, "weather", "clear").toLowerCase();
                    switch (weather) {
                        case "clear" -> {
                            w.setStorm(false);
                            w.setThundering(false);
                        }
                        case "rain" -> {
                            w.setStorm(true);
                            w.setThundering(false);
                        }
                        case "thunder" -> {
                            w.setStorm(true);
                            w.setThundering(true);
                        }
                    }
                }
                case "difficulty" -> {
                    String diff = getString(body, "difficulty", "NORMAL").toUpperCase();
                    w.setDifficulty(Difficulty.valueOf(diff));
                }
                case "save" -> w.save();
                default -> {
                    res.addProperty("ok", false);
                    res.addProperty("error", "Unknown world action: " + action);
                    return res;
                }
            }
            res.addProperty("ok", true);
            return res;
        });
    }

    public JsonObject getWhitelist() throws Exception {
        return runSync(() -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", Bukkit.hasWhitelist());
            JsonArray players = new JsonArray();
            for (OfflinePlayer op : Bukkit.getWhitelistedPlayers()) {
                JsonObject p = new JsonObject();
                p.addProperty("name", op.getName() != null ? op.getName() : op.getUniqueId().toString());
                p.addProperty("uuid", op.getUniqueId().toString());
                players.add(p);
            }
            obj.add("players", players);
            return obj;
        });
    }

    public void setWhitelistEnabled(boolean enabled) throws Exception {
        runSync(() -> {
            Bukkit.setWhitelist(enabled);
            return null;
        });
    }

    public void modifyWhitelistPlayer(String name, boolean add) throws Exception {
        runSync(() -> {
            @SuppressWarnings("deprecation")
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            op.setWhitelisted(add);
            return null;
        });
    }

    public void setDefaultGameMode(String mode) throws Exception {
        runSync(() -> {
            Bukkit.setDefaultGameMode(GameMode.valueOf(mode.toUpperCase()));
            return null;
        });
    }

    public boolean dispatchCommand(String command) throws Exception {
        return runSync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    public void broadcast(String message) throws Exception {
        runSync(() -> {
            Bukkit.broadcast(Component.text(message));
            return null;
        });
    }

    public void saveAll() throws Exception {
        runSync(() -> {
            Bukkit.savePlayers();
            for (World w : Bukkit.getWorlds()) {
                w.save();
            }
            return null;
        });
    }

    public void stopServer() throws Exception {
        runSync(() -> {
            Bukkit.shutdown();
            return null;
        });
    }

    private Player findOnlinePlayer(String idOrName) {
        if (idOrName == null) return null;
        try {
            UUID uuid = UUID.fromString(idOrName);
            Player byId = Bukkit.getPlayer(uuid);
            if (byId != null) return byId;
        } catch (IllegalArgumentException ignored) {}
        return Bukkit.getPlayerExact(idOrName);
    }

    private JsonObject serializePlayerSummary(Player p) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", p.getName());
        obj.addProperty("uuid", p.getUniqueId().toString());
        obj.addProperty("ping", p.getPing());
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = attr != null ? attr.getValue() : 20.0;
        obj.addProperty("health", Math.round(p.getHealth() * 10.0) / 10.0);
        obj.addProperty("maxHealth", Math.round(maxHp * 10.0) / 10.0);
        obj.addProperty("food", p.getFoodLevel());
        obj.addProperty("gameMode", p.getGameMode().name());
        obj.addProperty("isOp", p.isOp());
        obj.addProperty("allowFlight", p.getAllowFlight());
        Location loc = p.getLocation();
        obj.addProperty("world", loc.getWorld() != null ? loc.getWorld().getName() : "unknown");
        obj.addProperty("x", Math.round(loc.getX()));
        obj.addProperty("y", Math.round(loc.getY()));
        obj.addProperty("z", Math.round(loc.getZ()));
        return obj;
    }

    private JsonObject serializeItem(int slot, ItemStack item) {
        JsonObject obj = new JsonObject();
        obj.addProperty("slot", slot);
        obj.addProperty("material", item.getType().name());
        obj.addProperty("amount", item.getAmount());
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            obj.addProperty("displayName", PlainTextComponentSerializer.plainText().serialize(meta.displayName()));
        }
        if (!item.getEnchantments().isEmpty()) {
            JsonArray ench = new JsonArray();
            for (Map.Entry<Enchantment, Integer> e : item.getEnchantments().entrySet()) {
                ench.add(e.getKey().getKey().getKey() + " " + e.getValue());
            }
            obj.add("enchantments", ench);
        }
        return obj;
    }

    private String getString(JsonObject obj, String key, String fallback) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return fallback;
    }
}
