package dev.geminicraft;

import com.google.gson.*;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class GeminiCraft extends JavaPlugin implements Listener {
    private boolean askEnabled = true;
    private String apiKey = "";
    private String model = "";
    private int maximumToken = 1000;
    private int cooldownSeconds = 10;
    private int maxChatHistorySaves = 100;
    private int maxRequestPerIp = 50;
    private String systemPrompt = "You are Gemini integrated inside a Minecraft server chat through plugin.\nReply using plain text only.\nDo not use italics, bold, markdown, emojis, or special formatting.\nKeep answers short and clear.\nLimit replies to at most 3 short lines.\nAvoid long paragraphs.\nIf the question is complex, give a brief answer and suggest asking again.";
    private OkHttpClient httpClient;
    private Gson gson;
    private File logFile;
    private Boolean apiValid = null;
    private Boolean modelValid = null;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Set<String> hasWarnedAsk = new HashSet<>();
    private final Set<String> busySenders = new HashSet<>();
    private final Set<String> toggledPlayers = new HashSet<>();

    private final Map<String, Integer> ipUsage = new HashMap<>();
    private final Set<String> ipWarned80 = new HashSet<>();
    private String ipUsageDay = "";

    private File ipUsageFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String expectedConfigVersion = "2.0";
        String configVersion = getConfig().getString("config-version", null);
        File configFile = new File(getDataFolder(), "config.yml");
        String savedApiKey = null;

        if (configFile.exists()) {
            try (InputStream in = new FileInputStream(configFile)) {
                org.bukkit.configuration.file.YamlConfiguration oldConfig = new org.bukkit.configuration.file.YamlConfiguration();
                oldConfig.load(new InputStreamReader(in));
                savedApiKey = oldConfig.getString("api-key", null);
            } catch (Exception ignored) { }
        }

        if (configVersion == null || !configVersion.equals(expectedConfigVersion)) {
            if (configFile.exists()) {
                File backup = new File(getDataFolder(), "config-old-" + System.currentTimeMillis() + ".yml");
                configFile.renameTo(backup);
                getLogger().warning("Config version mismatch or missing! Backed up old config to " + backup.getName());
            }
            saveResource("config.yml", true);
            reloadConfig();
            if (savedApiKey != null && !savedApiKey.isEmpty()) {
                getConfig().set("api-key", savedApiKey);
                saveConfig();
            }
            getLogger().warning("Generated new config.yml with correct version (" + expectedConfigVersion + ")");
        }

        apiKey = getConfig().getString("api-key", "");
        model = getConfig().getString("gemini-model", "");
        if (model == null || model.isEmpty()) model = "gemini-2.5-flash-lite";
        maximumToken = getConfig().getInt("maximum-token", 1000);
        cooldownSeconds = getConfig().getInt("cooldown-seconds", 10);
        maxChatHistorySaves = getConfig().getInt("max-chat-history-saves", 100);
        maxRequestPerIp = getConfig().getInt("max-request-per-ip", 50);
        systemPrompt = getConfig().getString("prompt", systemPrompt);
        String askStatus = getConfig().getString("enable-plugin", "enable");
        askEnabled = askStatus.equalsIgnoreCase("enable");

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();

        gson = new Gson();
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        logFile = new File(getDataFolder(), "GeminiCraft.log");
        ipUsageFile = new File(getDataFolder(), "ip-usage.json");

        ipUsageDay = todayKey();
        loadIpUsage();

        if (!configFile.exists() || apiKey.equals("000000000000000000000") || apiKey.trim().isEmpty()) {
            getLogger().warning(asciiWarning());
            getLogger().warning("First run detected, please import your GeminiAPI key into plugin config!");
            getLogger().warning("Shutting down!");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        validateGeminiModelAndKey();
    }

    @Override
    public void onDisable() {
        cooldowns.clear();
        hasWarnedAsk.clear();
        busySenders.clear();
        toggledPlayers.clear();
        saveIpUsage();

        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            if (httpClient.cache() != null) {
                try { httpClient.cache().close(); } catch (IOException ignored) { }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String name = player.getName().toLowerCase();
        if (!toggledPlayers.contains(name)) return;
        if (!askEnabled) return;

        String msg = event.getMessage();
        if (msg == null) return;
        if (msg.startsWith("/")) return;

        String ip = getPlayerIp(player);
        if (!checkAndConsumeIpQuota(player, ip)) {
            event.setCancelled(true);
            return;
        }

        long now = System.currentTimeMillis();
        if (cooldowns.containsKey(name)) {
            long last = cooldowns.get(name);
            long waitMs = cooldownSeconds * 1000L - (now - last);
            if (waitMs > 0) {
                long waitSec = (waitMs + 999) / 1000;
                player.sendMessage(color("&7[&bGeminiCraft&7] &cPlease wait " + waitSec + "s before asking again"));
                event.setCancelled(true);
                return;
            }
        }

        if (busySenders.contains(name)) {
            player.sendMessage(color("&7[&bGeminiCraft&7] &eYou already have a Gemini answer in progress. Please wait."));
            event.setCancelled(true);
            return;
        }

        cooldowns.put(name, now);
        busySenders.add(name);
        event.setCancelled(true);

        Bukkit.getScheduler().runTask(this, () -> player.sendMessage(color("&7[&bGeminiCraft&7] &8You: &f" + msg)));
        askGeminiAsync(player, msg, maximumToken, name, true);
    }

    private boolean checkAndConsumeIpQuota(CommandSender sender, String ip) {
        if (ip == null || ip.isEmpty()) ip = "unknown";
        String today = todayKey();
        if (!today.equals(ipUsageDay)) {
            ipUsageDay = today;
            ipUsage.clear();
            ipWarned80.clear();
            saveIpUsage();
        }

        int used = ipUsage.getOrDefault(ip, 0);
        if (used >= maxRequestPerIp) {
            sender.sendMessage(color("&7[&bGeminiCraft&7] &cDaily limit reached for your IP. Try again tomorrow."));
            return false;
        }

        used += 1;
        ipUsage.put(ip, used);
        saveIpUsage();

        int warnAt = (int) Math.ceil(maxRequestPerIp * 0.8);
        String warnKey = ipUsageDay + "|" + ip;
        if (used >= warnAt && !ipWarned80.contains(warnKey)) {
            ipWarned80.add(warnKey);
            sender.sendMessage(color("&7[&bGeminiCraft&7] &eWarning: you used " + used + "/" + maxRequestPerIp + " requests today."));
        }

        return true;
    }

    private String getPlayerIp(Player player) {
        try {
            InetSocketAddress addr = player.getAddress();
            if (addr == null) return "unknown";
            if (addr.getAddress() == null) return "unknown";
            return addr.getAddress().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void loadIpUsage() {
        if (ipUsageFile == null || !ipUsageFile.exists()) return;
        try {
            String raw = Files.readString(ipUsageFile.toPath(), StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) return;
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            String day = obj.has("day") ? obj.get("day").getAsString() : "";
            if (!todayKey().equals(day)) return;
            ipUsageDay = day;
            if (obj.has("usage") && obj.get("usage").isJsonObject()) {
                JsonObject usage = obj.getAsJsonObject("usage");
                for (Map.Entry<String, JsonElement> e : usage.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        ipUsage.put(e.getKey(), e.getValue().getAsInt());
                    }
                }
            }
            if (obj.has("warned80") && obj.get("warned80").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("warned80");
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive()) ipWarned80.add(el.getAsString());
                }
            }
        } catch (Exception ignored) { }
    }

    private void saveIpUsage() {
        if (ipUsageFile == null) return;
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("day", ipUsageDay);
            JsonObject usage = new JsonObject();
            for (Map.Entry<String, Integer> e : ipUsage.entrySet()) usage.addProperty(e.getKey(), e.getValue());
            obj.add("usage", usage);
            JsonArray warned = new JsonArray();
            for (String s : ipWarned80) warned.add(s);
            obj.add("warned80", warned);
            Files.writeString(ipUsageFile.toPath(), gson.toJson(obj), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) { }
    }

    private String todayKey() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        return sdf.format(new Date());
    }

    private String asciiWarning() {
        return "\n" +
                " _       __                 _             __\n" +
                "| |     / /___ __________  (_)___  ____ _/ /\n" +
                "| | /| / / __ `/ ___/ __ \\/ / __ \\/ __ `/ / \n" +
                "| |/ |/ / /_/ / /  / / / / / / / / /_/ /_/  \n" +
                "|__/|__/\\__,_/_/  /_/ /_/_/_/ /_/\\__, (_)   \n" +
                "                                /____/      \n";
    }

    private void sendInfoPanel(CommandSender sender) {
        String line = "§7§m----------------------------------------------------";
        sender.sendMessage(color(line));
        sender.sendMessage(color("§b§lGeminiCraft v2.0"));
        sender.sendMessage(color("§f"));
        String apiStatus = apiValid == null ? "§eChecking..." : (apiValid ? "§aValid" : "§cInvalid");
        String modelStatus = modelValid == null ? "§eChecking..." : (modelValid ? "§aValid" : "§cInvalid");
        sender.sendMessage(color("§fAPI verify: " + apiStatus));
        sender.sendMessage(color("§fModel verify: " + modelStatus));
        sender.sendMessage(color("§fGemini model: §b" + model));
        sender.sendMessage(color("§fMaximum token: §b" + maximumToken));
        sender.sendMessage(color("§fCooldown: §b" + cooldownSeconds + "s"));
        sender.sendMessage(color("§fMax requests per IP per day: §b" + maxRequestPerIp));
        sender.sendMessage(color("§fEnabled: " + (askEnabled ? "§aTrue" : "§cFalse")));

        if (sender instanceof Player) {
            Player p = (Player) sender;
            String n = p.getName().toLowerCase();
            String ip = getPlayerIp(p);
            String today = todayKey();
            if (!today.equals(ipUsageDay)) {
                ipUsageDay = today;
                ipUsage.clear();
                ipWarned80.clear();
                saveIpUsage();
            }
            int used = ipUsage.getOrDefault(ip, 0);
            sender.sendMessage(color("§fChat toggle: " + (toggledPlayers.contains(n) ? "§aOn" : "§cOff")));
            sender.sendMessage(color("§fYour IP usage today: §b" + used + "§f/§b" + maxRequestPerIp));
        }

        sender.sendMessage(color("§f"));
        sender.sendMessage(color("&fCommand usage: &b/gemini help"));
        sender.sendMessage(color(line));
    }

    private void sendHelpPanel(CommandSender sender) {
        String line = "§7§m----------------------------------------------------";
        sender.sendMessage(color(line));
        sender.sendMessage(color("§b§lGeminiCraft usage:"));
        sender.sendMessage(color(""));
        sender.sendMessage(color("&bCommands:"));
        sender.sendMessage(color("&7- &f/gemini &7(show plugin info)"));
        sender.sendMessage(color("&7- &f/gemini &bask <your question> &7(ask gemini once)"));
        sender.sendMessage(color("&7- &f/gemini &btoggle &7(toggle chat-to-gemini mode)"));
        sender.sendMessage(color("&7- &f/gemini &bhelp &7(help menu)"));
        sender.sendMessage(color("&7- &f/gemini &benable|disable &7(admin)"));
        sender.sendMessage(color(line));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = "&7[&bGeminiCraft&7] ";
        if (!command.getName().equalsIgnoreCase("gemini")) return false;

        if (args.length == 0) {
            sendInfoPanel(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help":
                sendHelpPanel(sender);
                return true;

            case "enable":
                if (sender.hasPermission("geminicraft.admin") || sender.isOp()) {
                    askEnabled = true;
                    getConfig().set("enable-plugin", "enable");
                    saveConfig();
                    sender.sendMessage(color(prefix + "&aGeminiCraft enabled!"));
                } else {
                    sender.sendMessage(color(prefix + "&cYou don't have permission."));
                }
                return true;

            case "disable":
                if (sender.hasPermission("geminicraft.admin") || sender.isOp()) {
                    askEnabled = false;
                    getConfig().set("enable-plugin", "disable");
                    saveConfig();
                    sender.sendMessage(color(prefix + "&cGeminiCraft disabled!"));
                } else {
                    sender.sendMessage(color(prefix + "&cYou don't have permission."));
                }
                return true;

            case "toggle":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(color(prefix + "&cOnly players can use /gemini toggle"));
                    return true;
                }
                Player p = (Player) sender;
                String n = p.getName().toLowerCase();
                if (toggledPlayers.contains(n)) {
                    toggledPlayers.remove(n);
                    p.sendMessage(color(prefix + "&cChat-to-Gemini: OFF"));
                } else {
                    toggledPlayers.add(n);
                    p.sendMessage(color(prefix + "&aChat-to-Gemini: ON &7(type normally, it will ask Gemini)"));
                }
                return true;

            case "ask":
                if (!askEnabled) {
                    sender.sendMessage(color(prefix + "&c/gemini ask is currently disabled by admin."));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(color(prefix + "&cUsage: /gemini ask <your question>"));
                    return true;
                }

                String prompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                String senderName;

                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    String ip = getPlayerIp(player);
                    if (!checkAndConsumeIpQuota(sender, ip)) return true;

                    senderName = player.getName().toLowerCase();
                    long now = System.currentTimeMillis();

                    if (cooldowns.containsKey(senderName)) {
                        long last = cooldowns.get(senderName);
                        long waitMs = cooldownSeconds * 1000L - (now - last);
                        if (waitMs > 0) {
                            long waitSec = (waitMs + 999) / 1000;
                            sender.sendMessage(color(prefix + "&cPlease wait " + waitSec + "s before using gemini again"));
                            return true;
                        }
                    }

                    if (busySenders.contains(senderName)) {
                        sender.sendMessage(color("&7[&bGeminiCraft&7] &eYou already have a Gemini answer in progress. Please wait."));
                        return true;
                    }

                    if (!hasWarnedAsk.contains(senderName)) {
                        hasWarnedAsk.add(senderName);
                        player.sendMessage(color("&7[&bGeminiCraft&7] &6Warning: long prompts may be unfinished due to token limit"));
                        final String delayedPrompt = prompt;
                        Bukkit.getScheduler().runTaskLater(this, () -> {
                            cooldowns.put(senderName, System.currentTimeMillis());
                            busySenders.add(senderName);
                            askGeminiAsync(player, delayedPrompt, maximumToken, senderName, false);
                        }, 60L);
                        return true;
                    }

                    cooldowns.put(senderName, now);
                    busySenders.add(senderName);
                    askGeminiAsync(sender, prompt, maximumToken, senderName, false);
                } else {
                    senderName = "CONSOLE";
                    if (busySenders.contains(senderName)) {
                        sender.sendMessage(color("&7[&bGeminiCraft&7] &eYou already have a Gemini answer in progress. Please wait."));
                        return true;
                    }
                    busySenders.add(senderName);
                    askGeminiAsync(sender, prompt, 8192, senderName, false);
                }
                return true;

            default:
                sender.sendMessage(color("&7Unknown command, use &b/gemini &7help for usage"));
                return true;
        }
    }

    private void validateGeminiModelAndKey() {
        apiValid = null;
        modelValid = null;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
            String json = "{\n" +
                    "  \"contents\": [\n" +
                    "    {\n" +
                    "      \"parts\": [\n" +
                    "        { \"text\": \"ping\" }\n" +
                    "      ]\n" +
                    "    }\n" +
                    "  ],\n" +
                    "  \"generationConfig\": {\n" +
                    "    \"maxOutputTokens\": " + maximumToken + "\n" +
                    "  }\n" +
                    "}";

            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder().url(url).post(body).build();

            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody rb = response.body();
                String responseBody = rb != null ? rb.string() : "";

                if (!response.isSuccessful()) {
                    String errorMsg = extractGeminiError(responseBody);
                    if (response.code() == 401 || response.code() == 403) {
                        getLogger().warning("Invalid Gemini API key, shutting down!");
                        apiValid = false;
                        modelValid = false;
                    } else if (response.code() == 404 || (errorMsg != null && errorMsg.toLowerCase().contains("not found"))) {
                        getLogger().warning("Gemini model is invalid, shutting down!");
                        apiValid = true;
                        modelValid = false;
                    } else if (response.code() == 429 || isQuotaExceeded(errorMsg)) {
                        getLogger().warning("Gemini quota/limit reached during validation. Plugin will stay enabled, but requests may fail until quota resets.");
                        apiValid = true;
                        modelValid = true;
                        return;
                    } else {
                        getLogger().warning("Gemini API error: " + response.code() + (errorMsg != null ? " - " + errorMsg : ""));
                        apiValid = false;
                        modelValid = false;
                    }

                    Bukkit.getScheduler().runTask(this, () -> Bukkit.getPluginManager().disablePlugin(this));
                } else {
                    getLogger().info("API key is valid!");
                    getLogger().info("Gemini model is valid!");
                    apiValid = true;
                    modelValid = true;
                }
            } catch (Exception e) {
                getLogger().warning("Failed to contact Gemini API: " + e.getMessage() + ", shutting down!");
                apiValid = false;
                modelValid = false;
                Bukkit.getScheduler().runTask(this, () -> Bukkit.getPluginManager().disablePlugin(this));
            }
        });
    }

    private File getChatHistoryDir() {
        File dir = new File(getDataFolder(), "PlayerChatHistory");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File getPlayerHistoryFile(String playerName) {
        return new File(getChatHistoryDir(), playerName.toLowerCase() + ".txt");
    }

    private List<String> loadHistory(String playerName) {
        File file = getPlayerHistoryFile(playerName);
        if (!file.exists()) return new ArrayList<>();
        try {
            return new ArrayList<>(Files.readAllLines(file.toPath()));
        } catch (IOException e) {
            getLogger().warning("Could not load chat history for " + playerName + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private void saveHistory(String playerName, List<String> history) {
        File file = getPlayerHistoryFile(playerName);
        int maxLines = Math.max(1, maxChatHistorySaves);
        List<String> limited = history.size() > maxLines ? history.subList(history.size() - maxLines, history.size()) : history;
        try {
            Files.write(file.toPath(), limited, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            getLogger().warning("Could not save chat history for " + playerName + ": " + e.getMessage());
        }
    }

    private void askGeminiAsync(CommandSender sender, String prompt, int maxToken, String playerName, boolean fromToggleChat) {
        if (!(sender instanceof ConsoleCommandSender)) {
            int wordCount = prompt.trim().split("\\s+").length;
            if (prompt.length() > 500 || wordCount > 100) {
                String prefix = "&7[&bGeminiCraft&7] ";
                sender.sendMessage(color(prefix + "&cYour prompt may be too large. Try requesting a shorter answer."));
                if (!playerName.equals("CONSOLE")) busySenders.remove(playerName);
                return;
            }
        }

        List<String> history = loadHistory(playerName);
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append(systemPrompt).append("\n\n");
        for (String h : history) contextBuilder.append(h).append("\n");
        contextBuilder.append("Player: ").append(prompt).append("\nGemini:");

        String requestText = contextBuilder.toString();
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

        String json = "{\n" +
                "  \"contents\": [\n" +
                "    {\n" +
                "      \"parts\": [\n" +
                "        { \"text\": \"" + escapeJson(requestText) + "\" }\n" +
                "      ]\n" +
                "    }\n" +
                "  ],\n" +
                "  \"generationConfig\": {\n" +
                "    \"maxOutputTokens\": " + maxToken + "\n" +
                "  }\n" +
                "}";

        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder().url(url).post(body).build();

        long timestamp = System.currentTimeMillis();
        String logPrompt = "[" + getTimeString(timestamp) + "] " + sender.getName() + " asked: " + prompt;

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                String prefix = "&7[&bGeminiCraft&7] ";
                String err = "Failed to contact Gemini API: " + e.getMessage();
                logGemini(logPrompt);
                logGemini("   > ERROR: " + err);

                Bukkit.getScheduler().runTask(GeminiCraft.this, () -> {
                    sender.sendMessage(color(prefix + "&c" + err));
                    busySenders.remove(playerName);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String prefix = "&7[&bGeminiCraft&7] ";
                ResponseBody rb = response.body();
                String responseBody = rb != null ? rb.string() : "";

                if (!response.isSuccessful()) {
                    String errorMsg = extractGeminiError(responseBody);

                    if (response.code() == 429 || isQuotaExceeded(errorMsg)) {
                        String out = "&cGemini daily limit/quota reached. Try again later.";
                        logGemini(logPrompt);
                        logGemini("   > ERROR: quota/limit reached");
                        Bukkit.getScheduler().runTask(GeminiCraft.this, () -> {
                            sender.sendMessage(color(prefix + out));
                            busySenders.remove(playerName);
                        });
                        return;
                    }

                    final String errorOut = "Gemini API error " + response.code() + (errorMsg != null ? " - " + errorMsg : "");
                    logGemini(logPrompt);
                    logGemini("   > ERROR: " + errorOut);

                    Bukkit.getScheduler().runTask(GeminiCraft.this, () -> {
                        sender.sendMessage(color(prefix + "&c" + errorOut));
                        busySenders.remove(playerName);
                    });
                    return;
                }

                String answer = extractGeminiFullAnswer(responseBody);
                if (answer == null || answer.trim().isEmpty()) answer = "&cOver token limited";

                logGemini(logPrompt);
                for (String l : answer.split("\n")) logGemini("   > " + l);

                history.add("Player: " + prompt);
                history.add("Gemini: " + answer);
                saveHistory(playerName, history);

                final String[] lines = answer.split("\n");

                Bukkit.getScheduler().runTask(GeminiCraft.this, () -> {
                    if (!fromToggleChat) sender.sendMessage(color(prefix + "&bGemini Response:"));
                    else sender.sendMessage(color(prefix + "&bGemini:"));

                    for (String line : lines) {
                        for (String chunk : splitLongLine(line, 230)) {
                            sender.sendMessage(color("&7" + chunk));
                        }
                    }
                    busySenders.remove(playerName);
                });
            }
        });
    }

    private boolean isQuotaExceeded(String errorMsg) {
        if (errorMsg == null) return false;
        String m = errorMsg.toLowerCase();
        return m.contains("quota") || m.contains("resource_exhausted") || m.contains("rate limit") || m.contains("too many requests") || m.contains("exceeded");
    }

    private String extractGeminiFullAnswer(String json) {
        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            if (!obj.has("candidates")) return null;
            JsonArray candidates = obj.getAsJsonArray("candidates");
            if (candidates.size() == 0) return null;
            JsonObject cand = candidates.get(0).getAsJsonObject();
            if (!cand.has("content")) return null;
            JsonObject content = cand.getAsJsonObject("content");
            if (!content.has("parts")) return null;
            JsonArray parts = content.getAsJsonArray("parts");
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : parts) {
                JsonObject part = el.getAsJsonObject();
                if (part.has("text")) sb.append(part.get("text").getAsString());
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractGeminiError(String json) {
        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            if (obj.has("error")) {
                JsonObject error = obj.getAsJsonObject("error");
                if (error.has("message")) return error.get("message").getAsString();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void logGemini(String line) {
        try {
            Files.write(logFile.toPath(), (line + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    private String color(String msg) {
        return msg.replace("&", "§");
    }

    private String getTimeString(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new Date(millis));
    }

    private List<String> splitLongLine(String input, int maxLen) {
        List<String> out = new ArrayList<>();
        while (input.length() > maxLen) {
            out.add(input.substring(0, maxLen));
            input = input.substring(maxLen);
        }
        if (!input.isEmpty()) out.add(input);
        return out;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}