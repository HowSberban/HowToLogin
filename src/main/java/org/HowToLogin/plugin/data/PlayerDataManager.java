package org.howtologin.plugin.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// 表 players 在运行时由 initTable() 创建，IDE 静态分析无法解析，抑制 SqlResolve 检查
@SuppressWarnings("SqlResolve")
public final class PlayerDataManager {

    private final HTLogin plugin;
    private final HikariDataSource dataSource;
    // 内存缓存：启动时全量加载，运行时读操作走缓存，写操作异步落库
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

    // REPLACE INTO 在 SQLite 与 MySQL 均支持：主键存在则先 DELETE 再 INSERT，否则直接 INSERT
    private static final String SQL_UPSERT =
            "REPLACE INTO players (uuid, password_hash, ip, last_login, logout_location) VALUES (?, ?, ?, ?, ?)";
    private static final String SQL_DELETE = "DELETE FROM players WHERE uuid = ?";
    private static final String SQL_UPDATE_PASSWORD =
            "UPDATE players SET password_hash = ? WHERE uuid = ?";

    public PlayerDataManager(HTLogin plugin) {
        this.plugin = plugin;
        this.dataSource = createDataSource(plugin);
        initTable();
        load();
    }

    private HikariDataSource createDataSource(HTLogin plugin) {
        var cm = plugin.getConfigManager();
        HikariConfig config = new HikariConfig();
        config.setPoolName("HTLogin-DB");

        if ("mysql".equals(cm.databaseType())) {
            // 拼接 MySQL JDBC URL 与连接参数
            StringBuilder url = new StringBuilder()
                    .append("jdbc:mysql://")
                    .append(cm.mysqlHost())
                    .append(":")
                    .append(cm.mysqlPort())
                    .append("/")
                    .append(cm.mysqlDatabase());
            if (!cm.mysqlParams().isEmpty()) {
                String query = cm.mysqlParams().entrySet().stream()
                        .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                        .collect(Collectors.joining("&"));
                url.append("?").append(query);
            }
            config.setJdbcUrl(url.toString());
            config.setUsername(cm.mysqlUsername());
            config.setPassword(cm.mysqlPassword());
            config.setMaximumPoolSize(Math.max(1, cm.poolSize()));
        } else {
            // SQLite：单连接即可，避免文件锁竞争
            File dbFile = new File(plugin.getDataFolder(), "players.db");
            if (!dbFile.getParentFile().exists() && !dbFile.getParentFile().mkdirs()) {
                plugin.getLogger().warning(I18n.get("log.create_data_dir_failed", dbFile.getParentFile().getAbsolutePath()));
            }
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            // SQLite 写入依赖文件锁，多连接会阻塞，强制单连接
            config.setMaximumPoolSize(1);
        }

        return new HikariDataSource(config);
    }

    /** 建表（如果不存在） */
    private void initTable() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS players (" +
                    "  uuid VARCHAR(36) PRIMARY KEY," +
                    "  password_hash VARCHAR(255) NOT NULL," +
                    "  ip VARCHAR(45) NOT NULL DEFAULT ''," +
                    "  last_login BIGINT NOT NULL DEFAULT 0," +
                    "  logout_location TEXT" +
                    ")"
            );
        } catch (SQLException e) {
            plugin.getLogger().severe(I18n.get("log.init_table_failed", e.getMessage()));
        }
    }

    /** 启动时全量加载玩家数据到内存缓存 */
    public void load() {
        players.clear();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT uuid, password_hash, ip, last_login, logout_location FROM players")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                PlayerData data = new PlayerData(
                        uuid,
                        rs.getString("password_hash"),
                        rs.getString("ip"),
                        rs.getLong("last_login"),
                        rs.getString("logout_location")
                );
                players.put(uuid, data);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe(I18n.get("log.load_players_failed", e.getMessage()));
        }
    }

    /** 异步保存单个玩家数据（Folia 兼容，使用统一调度器） */
    public void save(UUID uuid) {
        PlayerData data = players.get(uuid);
        if (data == null) return;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> upsertSync(data));
    }

    /** 同步全量保存，用于 onDisable（必须在关服前完成） */
    public void saveSync() {
        saveAllSync();
    }

    private void upsertSync(PlayerData data) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT)) {
            bindPlayerData(ps, data);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe(I18n.get("log.save_player_failed", data.uuid(), e.getMessage()));
        }
    }

    private void saveAllSync() {
        if (players.isEmpty()) return;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT)) {
                for (PlayerData data : players.values()) {
                    bindPlayerData(ps, data);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe(I18n.get("log.save_all_failed", e.getMessage()));
        }
    }

    private static void bindPlayerData(PreparedStatement ps, PlayerData data) throws SQLException {
        ps.setString(1, data.uuid().toString());
        ps.setString(2, data.passwordHash());
        ps.setString(3, data.ip());
        ps.setLong(4, data.lastLogin());
        ps.setString(5, data.logoutLocation());
    }

    /** 获取所有已注册玩家的 UUID 集合 */
    public java.util.Set<UUID> getAllUuids() {
        return players.keySet();
    }

    public boolean hasAccount(UUID uuid) {
        return players.containsKey(uuid);
    }

    public PlayerData getPlayer(UUID uuid) {
        return players.get(uuid);
    }

    /** 查找指定 IP 下的所有账号（用于管理员排查多账号） */
    public java.util.List<PlayerData> findByIp(String ip) {
        if (ip == null || ip.isEmpty()) return java.util.List.of();
        return players.values().stream()
                .filter(d -> ip.equals(d.ip()))
                .toList();
    }

    public void createPlayer(UUID uuid, String passwordHash, String ip) {
        PlayerData data = new PlayerData(uuid, passwordHash, ip, System.currentTimeMillis() / 1000, null);
        players.put(uuid, data);
        save(uuid);
    }

    public void removePlayer(UUID uuid) {
        players.remove(uuid);
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SQL_DELETE)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe(I18n.get("log.delete_player_failed", uuid, e.getMessage()));
            }
        });
    }

    public void updatePassword(UUID uuid, String newHash) {
        PlayerData data = players.get(uuid);
        if (data == null) return;
        data.passwordHash(newHash);
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_PASSWORD)) {
                ps.setString(1, newHash);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe(I18n.get("log.update_password_failed", uuid, e.getMessage()));
            }
        });
    }

    /** 关闭数据源，释放连接池 */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /**
     * 将 Location 序列化为字符串，格式: world:x:y:z:yaw:pitch
     * 用于持久化存储玩家上次退出位置。
     */
    public static String serializeLocation(Location loc) {
        return loc.getWorld().getName() + ":"
                + loc.getX() + ":" + loc.getY() + ":" + loc.getZ() + ":"
                + loc.getYaw() + ":" + loc.getPitch();
    }

    /**
     * 将序列化的字符串反序列化为 Location。
     * 如果世界不存在或格式错误，返回 null。
     */
    public static Location deserializeLocation(String str) {
        if (str == null || str.isEmpty()) return null;
        try {
            String[] parts = str.split(":");
            if (parts.length != 6) return null;
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;
            return new Location(world,
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]),
                    Float.parseFloat(parts[5]));
        } catch (Exception e) {
            return null;
        }
    }

    public static final class PlayerData {
        private final UUID uuid;
        // volatile 保证可见性：主线程写入后，异步保存线程能读到最新值
        private volatile String passwordHash;
        private volatile String ip;
        private volatile long lastLogin;
        // 玩家上次已登录退出时的位置（序列化字符串），用于登录后传送回原位置
        private volatile String logoutLocation;

        public PlayerData(UUID uuid, String passwordHash, String ip, long lastLogin, String logoutLocation) {
            this.uuid = uuid;
            this.passwordHash = passwordHash;
            this.ip = ip;
            this.lastLogin = lastLogin;
            this.logoutLocation = logoutLocation;
        }

        public UUID uuid() { return uuid; }
        public String passwordHash() { return passwordHash; }
        public void passwordHash(String hash) { this.passwordHash = hash; }

        public String ip() { return ip; }
        public void ip(String ip) { this.ip = ip; }

        public long lastLogin() { return lastLogin; }
        public void lastLogin(long lastLogin) { this.lastLogin = lastLogin; }

        public String logoutLocation() { return logoutLocation; }
        public void logoutLocation(String logoutLocation) { this.logoutLocation = logoutLocation; }
    }
}
