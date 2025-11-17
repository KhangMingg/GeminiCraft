package dev.geminicraft;

import com.google.gson.*;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class GeminiCraft extends JavaPlugin {
    private boolean askEnabled = true;
    private String apiKey = "";
    private String model = "";
    private int maximumToken = 1000;
    private int cooldownSeconds = 5;
    private int maxChatHistorySaves = 100;
    private String systemPrompt = "You are Gemini which is ported into a minecraft server using plugins, your goal is to help players inside the server. You should avoid answering more than 2-3 sentences.";
    private OkHttpClient httpClient;
    private Gson gson;
    private File logFile;
    private Boolean apiValid = null;
    private Boolean modelValid = null;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Set<String> hasWarnedAsk = new HashSet<>();
    private final Set<String> busySenders = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String pluginVersion = "1.2";
        String configVersion = getConfig().getString("config-version", null);
        File configFile = new File(getDataFolder(), "config.yml");
        String savedApiKey = null;
        if (configFile.exists()) {
            try (InputStream in = new FileInputStream(configFile)) {
                org.bukkit.configuration.file.YamlConfiguration oldConfig = new org.bukkit.configuration.file.YamlConfiguration();
                oldConfig.load(new InputStreamReader(in));
                savedApiKey = oldConfig.getString("api-key", null);
            } catch (Exception ignored) {}
        }
        if (configVersion == null || !configVersion.equals(pluginVersion)) {
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
            getLogger().warning("Generated new config.yml with correct version (" + pluginVersion + ")");
        }
        apiKey = getConfig().getString("api-key", "");
        model = getConfig().getString("gemini-model", "");
        if (model == null || model.isEmpty()) {
            model = "gemini-2.5-flash";
        }
        maximumToken = getConfig().getInt("maximum-token", 1000);
        cooldownSeconds = getConfig().getInt("cooldown-seconds", 5);
        maxChatHistorySaves = getConfig().getInt("max-chat-history-saves", 100);
        systemPrompt = getConfig().getString(
                "prompt",
                "You are Gemini which is ported into a minecraft server using plugins, your goal is to help players inside the server. You should avoid answering more than 2-3 sentences."
        );
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
        if (!configFile.exists() || apiKey.equals("000000000000000000000")) {
            getLogger().warning(asciiWarning());
            getLogger().warning("First run detected, please import your GeminiAPI key into plugin config!");
            getLogger().warning("Shutting down!");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        validateGeminiModelAndKey();
    }

    @Override
    public void onDisable() {
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            if (httpClient.cache() != null) {
                try { httpClient.cache().close(); } catch (IOException ignored) {}
            }
        }
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
        sender.sendMessage(color("§b§lGeminiCraft v1.2"));
        sender.sendMessage(color("§f"));
        String apiStatus = apiValid == null ? "§eChecking..." : (apiValid ? "§aValid" : "§cInvalid");
        String modelStatus = modelValid == null ? "§eChecking..." : (modelValid ? "§aValid" : "§cInvalid");
        sender.sendMessage(color("§fAPI verify: " + apiStatus));
        sender.sendMessage(color("§fModel verify: " + modelStatus));
        sender.sendMessage(color("§fGemini model: §b" + model));
        sender.sendMessage(color("§fMaximum token: §b" + maximumToken));
        sender.sendMessage(color("§fCooldown: §b" + cooldownSeconds + "s"));
        sender.sendMessage(color("§fEnabled: " + (askEnabled ? "§aTrue" : "§cFalse")));
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
        sender.sendMessage(color("&7- &f/gemini &bask <your question> &7(ask gemini)"));
        sender.sendMessage(color("&7- &f/gemini &bhelp &7(help menu)"));
        sender.sendMessage(color(line));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = "&7[&bGeminiCraft&7] ";
        if (command.getName().equalsIgnoreCase("gemini")) {
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
                        sender.sendMessage(color(prefix + "&a/gemini ask is enabled!"));
                        return true;
                    } else {
                        sender.sendMessage(color(prefix + "&cYou don't have permission."));
                        return true;
                    }
                case "disable":
                    if (sender.hasPermission("geminicraft.admin") || sender.isOp()) {
                        askEnabled = false;
                        getConfig().set("enable-plugin", "disable");
                        saveConfig();
                        sender.sendMessage(color(prefix + "&c/gemini ask is disabled!"));
                        return true;
                    } else {
                        sender.sendMessage(color(prefix + "&cYou don't have permission."));
                        return true;
                    }
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
                        senderName = player.getName().toLowerCase();
                        long now = System.currentTimeMillis();
                        if (cooldowns.containsKey(senderName)) {
                            long last = cooldowns.get(senderName);
                            long waitMs = cooldownSeconds * 1000L - (now - last);
                            if (waitMs > 0) {
                                long waitSec = (waitMs + 999) / 1000;
                                sender.sendMessage(color(prefix + "&cPlease wait " + waitSec + "s before using gemini ask again"));
                                return true;
                            }
                        }
                        if (busySenders.contains(senderName)) {
                            sender.sendMessage(color("&7[&bGeminiCraft&7] &eYou already have a Gemini answer in progress. Please wait."));
                            return true;
                        }
                        if (!hasWarnedAsk.contains(senderName)) {
                            hasWarnedAsk.add(senderName);
                            player.sendMessage(color("&7[&bGeminiCraft&7] &6Warning: this plugin is intended for simple question like recipe asking, longer prompt like writing essay will likely be unfinished due to token limit"));
                            final String delayedPrompt = prompt;
                            Bukkit.getScheduler().runTaskLater(this, () -> {
                                cooldowns.put(senderName, System.currentTimeMillis());
                                busySenders.add(senderName);
                                askGeminiAsync(player, delayedPrompt, maximumToken, senderName);
                            }, 60L);
                            return true;
                        }
                        cooldowns.put(senderName, now);
                        busySenders.add(senderName);
                        askGeminiAsync(sender, prompt, maximumToken, senderName);
                    } else {
                        senderName = "CONSOLE";
                        if (busySenders.contains(senderName)) {
                            sender.sendMessage(color("&7[&bGeminiCraft&7] &eYou already have a Gemini answer in progress. Please wait."));
                            return true;
                        }
                        busySenders.add(senderName);
                        askGeminiAsync(sender, prompt, 8192, senderName);
                    }
                    return true;
                default:
                    sender.sendMessage(color("&7Unknown command, use &b/gemini &7help for usage"));
                    return true;
            }
        }
        return false;
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
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    String errorMsg = extractGeminiError(responseBody);
                    if (response.code() == 401 || response.code() == 403) {
                        getLogger().warning("Invalid Gemini API key, shutting down!");
                        apiValid = false;
                        modelValid = false;
                    } else if (response.code() == 404 || (errorMsg != null && errorMsg.contains("not found"))) {
                        getLogger().warning("Gemini model is invalid, shutting down!");
                        apiValid = true;
                        modelValid = false;
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
        List<String> limited = history.size() > maxLines
            ? history.subList(history.size() - maxLines, history.size())
            : history;
        try {
            Files.write(file.toPath(), limited, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            getLogger().warning("Could not save chat history for " + playerName + ": " + e.getMessage());
        }
    }

    private void askGeminiAsync(CommandSender sender, String prompt, int maxToken, String playerName) {
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
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
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
                String responseBody = response.body().string();
                if (!response.isSuccessful()) {
                    String errorMsg = extractGeminiError(responseBody);
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
                if (answer == null || answer.trim().isEmpty()) {
                    answer = "&cOver token limited";
                }
                logGemini(logPrompt);
                for (String l : answer.split("\n")) {
                    logGemini("   > " + l);
                }
                history.add("Player: " + prompt);
                history.add("Gemini: " + answer);
                saveHistory(playerName, history);
                final String[] lines = answer.split("\n");
                Bukkit.getScheduler().runTask(GeminiCraft.this, () -> {
                    String header = prefix + "&bGemini Response:";
                    sender.sendMessage(color(header));
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