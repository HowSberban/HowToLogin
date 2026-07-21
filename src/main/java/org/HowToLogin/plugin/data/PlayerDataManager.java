package org.HowToLogin.plugin.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.util.FoliaHelper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerDataManager {

    private final HTLogin plugin;
    private final File dataFile;
    private final Gson gson;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

    public PlayerDataManager(HTLogin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "players.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        load();
    }

    public void load() {
        if (!dataFile.exists()) {
            players.clear();
            return;
        }
        try (Reader reader = Files.newBufferedReader(dataFile.toPath(), StandardCharsets.UTF_8)) {
            Map<String, PlayerData> raw = gson.fromJson(reader,
                    new TypeToken<Map<String, PlayerData>>() {}.getType());
            if (raw != null) {
                players.clear();
                raw.forEach((key, value) -> players.put(UUID.fromString(key), value));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load player data: " + e.getMessage());
        }
    }

    public void save() {
        FoliaHelper.runAsync(plugin, this::syncSave);
    }

    private void syncSave() {
        try {
            File parent = dataFile.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("无法创建数据目录: " + parent.getAbsolutePath());
                return;
            }
            try (Writer writer = Files.newBufferedWriter(dataFile.toPath(), StandardCharsets.UTF_8)) {
                Map<String, PlayerData> raw = new java.util.LinkedHashMap<>();
                players.forEach((uuid, data) -> raw.put(uuid.toString(), data));
                gson.toJson(raw, writer);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save player data: " + e.getMessage());
        }
    }

    /** 同步保存，用于 onDisable（必须在关服前完成） */
    public void saveSync() {
        syncSave();
    }

    public boolean hasAccount(UUID uuid) {
        return players.containsKey(uuid);
    }

    public PlayerData getPlayer(UUID uuid) {
        return players.get(uuid);
    }

    public void createPlayer(UUID uuid, String passwordHash, String ip) {
        players.put(uuid, new PlayerData(passwordHash, ip));
        save();
    }

    public void removePlayer(UUID uuid) {
        players.remove(uuid);
        save();
    }

    public void updatePassword(UUID uuid, String newHash) {
        PlayerData data = players.get(uuid);
        if (data != null) {
            data.passwordHash = newHash;
            save();
        }
    }

    public static final class PlayerData {
        private String passwordHash;
        // Gson 序列化时通过反射读取这些字段（无显式 getter），IDE 静态分析无法识别
        @SuppressWarnings("unused")
        private String ip;
        @SuppressWarnings("unused")
        private long lastLogin;

        // Gson 反序列化需要无参构造函数（通过反射调用，IDE 静态分析无法识别）
        @SuppressWarnings("unused")
        public PlayerData() {}

        public PlayerData(String passwordHash, String ip) {
            this.passwordHash = passwordHash;
            this.ip = ip;
            this.lastLogin = System.currentTimeMillis() / 1000;
        }

        public String passwordHash() { return passwordHash; }

        public void lastLogin(long lastLogin) { this.lastLogin = lastLogin; }
        public void ip(String ip) { this.ip = ip; }
    }
}