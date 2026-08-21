package org.howtologin.plugin.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.PasswordHash;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// 表 players 在运行时由 initTable() 创建，IDE 静态分析无法解析，抑制 SqlResolve 检查
@SuppressWarnings("SqlResolve")
public final class PlayerDataManager {

    private final HTLogin plugin;
    private final HikariDataSource dataSource;
    // 内存缓存：启动时全量加载，运行时读操作走缓存，写操作标记脏后由周期任务批量落库
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    // 脏标记：内存数据已修改但尚未落库的玩家 UUID，由周期任务批量 flush
    private final java.util.Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    // 批量落库失败重试计数：达到上限后放弃该玩家，防止数据库故障时无限重试刷日志
    private final Map<UUID, Integer> flushFailures = new ConcurrentHashMap<>();
    // 批量落库最大重试次数
    private static final int MAX_FLUSH_RETRY = 3;
    // 正版玩家名索引：name(小写) → uuid，用于 getByName 快速查找，避免 O(n) 遍历
    private final Map<String, UUID> premiumNameIndex = new ConcurrentHashMap<>();

    // REPLACE INTO 在 SQLite 与 MySQL 均支持：主键存在则先 DELETE 再 INSERT，否则直接 INSERT
    private static final String SQL_UPSERT =
            "REPLACE INTO players (uuid, name, password_hash, ip, last_login, logout_location, premium, properties, game_mode, totp_secret, last_active) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SQL_DELETE = "DELETE FROM players WHERE uuid = ?";
    private static final String SQL_UPDATE_PASSWORD =
            "UPDATE players SET password_hash = ? WHERE uuid = ?";
    private static final String SQL_UPDATE_PREMIUM =
            "UPDATE players SET premium = ?, properties = ?, name = ? WHERE uuid = ?";

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

    /** 建表（如果不存在）+ 迁移新列 */
    private void initTable() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS players (" +
                    "  uuid VARCHAR(36) PRIMARY KEY," +
                    "  name VARCHAR(16)," +
                    "  password_hash VARCHAR(255) NOT NULL," +
                    "  ip VARCHAR(45) NOT NULL DEFAULT ''," +
                    "  last_login BIGINT NOT NULL DEFAULT 0," +
                    "  logout_location TEXT," +
                    "  premium BOOLEAN NOT NULL DEFAULT 0," +
                    "  properties TEXT" +
                    ")"
            );
            // 迁移：为旧表添加新列（ALTER TABLE ADD COLUMN 在列已存在时抛异常，忽略即可）
            addColumnIfMissing(stmt, conn, "name", "VARCHAR(16)");
            addColumnIfMissing(stmt, conn, "premium", "BOOLEAN NOT NULL DEFAULT 0");
            addColumnIfMissing(stmt, conn, "properties", "TEXT");
            addColumnIfMissing(stmt, conn, "game_mode", "VARCHAR(16)");
            addColumnIfMissing(stmt, conn, "totp_secret", "VARCHAR(64)");
            addColumnIfMissing(stmt, conn, "last_active", "BIGINT NOT NULL DEFAULT 0");
        } catch (SQLException e) {
            plugin.getLogger().severe(I18n.get("log.init_table_failed", e.getMessage()));
        }
    }

    /** 安全添加列：若列不存在则执行 ALTER TABLE ADD COLUMN */
    private void addColumnIfMissing(Statement stmt, Connection conn, String column, String type) {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, "players", column)) {
            if (!rs.next()) {
                stmt.executeUpdate("ALTER TABLE " + "players" + " ADD COLUMN " + column + " " + type);
            }
        } catch (SQLException ignored) {
            // 列已存在或其他异常，忽略
        }
    }

    /** 启动时全量加载玩家数据到内存缓存 */
    public void load() {
        players.clear();
        premiumNameIndex.clear();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT uuid, name, password_hash, ip, last_login, logout_location, premium, properties, game_mode, totp_secret, last_active FROM players")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                PlayerData data = new PlayerData(
                        uuid,
                        rs.getString("name"),
                        rs.getString("password_hash"),
                        rs.getString("ip"),
                        rs.getLong("last_login"),
                        rs.getString("logout_location"),
                        rs.getBoolean("premium"),
                        rs.getString("properties"),
                        rs.getString("game_mode"),
                        rs.getString("totp_secret"),
                        rs.getLong("last_active")
                );
                players.put(uuid, data);
                if (data.premium() && data.name() != null) {
                    premiumNameIndex.put(data.name().toLowerCase(), uuid);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe(I18n.get("log.load_players_failed", e.getMessage()));
        }
    }

    /** 标记玩家数据为脏：由周期任务批量落库（合并写、降 DB 开销；崩溃时最多丢失一个 flush 周期内的改动） */
    public void save(UUID uuid) {
        if (players.containsKey(uuid)) {
            dirty.add(uuid);
        }
    }

    /** 周期任务调用：将脏标记的玩家数据批量落库，失败按上限重试 */
    public void flushDirty() {
        if (dirty.isEmpty()) return;
        final List<PlayerData> toSave = new ArrayList<>(dirty.size());
        // 逐个移除而非整体 clear：避免与主线程并发 save() 竞态（clear 可能清掉刚标记的脏数据）
        for (UUID uuid : dirty) {
            dirty.remove(uuid);
            PlayerData data = players.get(uuid);
            if (data != null) toSave.add(data);
        }
        if (toSave.isEmpty()) return;

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            // 批量写失败时按重试上限重新标记脏，等待下轮 flush；成功则清除重试计数
            if (upsertBatchSync(toSave)) {
                for (PlayerData data : toSave) {
                    flushFailures.remove(data.uuid());
                }
            } else {
                for (PlayerData data : toSave) {
                    int n = flushFailures.merge(data.uuid(), 1, Integer::sum);
                    if (n < MAX_FLUSH_RETRY) {
                        dirty.add(data.uuid());
                    } else {
                        flushFailures.remove(data.uuid()); // 放弃，停止重试
                    }
                }
            }
        });
    }

    /** 同步全量保存，用于 onDisable（必须在关服前完成，覆盖全部内存数据含脏标记） */
    public void saveSync() {
        dirty.clear();
        saveAllSync();
    }

    /** 批量 upsert，整批一个事务；成功返回 true，失败返回 false */
    private boolean upsertBatchSync(List<PlayerData> list) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT)) {
                for (PlayerData data : list) {
                    bindPlayerData(ps, data);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe(I18n.get("log.save_all_failed", e.getMessage()));
            return false;
        }
    }

    private void saveAllSync() {
        if (players.isEmpty()) return;
        upsertBatchSync(new ArrayList<>(players.values()));
    }

    private static void bindPlayerData(PreparedStatement ps, PlayerData data) throws SQLException {
        ps.setString(1, data.uuid().toString());
        ps.setString(2, data.name());
        ps.setString(3, data.passwordHash());
        ps.setString(4, data.ip());
        ps.setLong(5, data.lastLogin());
        ps.setString(6, data.logoutLocation());
        ps.setBoolean(7, data.premium());
        ps.setString(8, data.properties());
        ps.setString(9, data.gameMode());
        ps.setString(10, data.totpSecret());
        ps.setLong(11, data.lastActive());
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
        PlayerData data = new PlayerData(uuid, null, passwordHash, ip, System.currentTimeMillis() / 1000, null, false, null, null, null, 0);
        players.put(uuid, data);
        // 创建账号为关键操作：立即落库，避免崩溃丢新账号（区别于登录/退出等的周期批量 flush）
        saveNow(data);
    }

    /** 立即同步落库单个玩家数据（用于注册等不可丢失的关键操作），失败时退化为脏标记由周期任务兜底重试 */
    private void saveNow(PlayerData data) {
        if (!upsertBatchSync(List.of(data))) {
            // 立即写失败：转交周期 flush 兜底重试
            dirty.add(data.uuid());
        } else {
            flushFailures.remove(data.uuid());
        }
    }

    /**
     * 为正版账号生成 16 位随机密码（数字 + 大小写字母），哈希后写入数据库。
     * 返回明文密码，供首次注册/升级进服时提示玩家。
     */
    private String assignRandomPassword(PlayerData data) {
        String plain = PasswordHash.generateRandomPassword(16);
        data.passwordHash(PasswordHash.hashPassword(plain, plugin.getConfigManager().passwordHashAlgorithm(), plugin.getConfigManager().bcryptCost()));
        return plain;
    }

    /**
     * 创建正版玩家记录（premium=1，随机密码占位，带 properties 皮肤数据）。
     * @return 随机生成的明文密码（用于首次进服提示玩家），null 表示未创建
     */
    public String createPremiumPlayer(UUID uuid, String name, String ip, String properties) {
        PlayerData data = new PlayerData(uuid, name, "", ip, System.currentTimeMillis() / 1000, null, true, properties, null, null, 0);
        String plain = assignRandomPassword(data);
        players.put(uuid, data);
        if (name != null) {
            premiumNameIndex.put(name.toLowerCase(), uuid);
        }
        // 创建正版账号同样为关键操作：立即落库，避免崩溃丢新账号
        saveNow(data);
        return plain;
    }

    /** 标记已有账号为正版（premium=1），更新 properties 和 name */
    public void markPremium(UUID uuid, String name, String properties) {
        PlayerData data = players.get(uuid);
        if (data == null) return;
        // 更新索引：移除旧名映射，添加新名映射
        if (data.name() != null) {
            premiumNameIndex.remove(data.name().toLowerCase());
        }
        data.premium(true);
        data.properties(properties);
        data.name(name);
        if (name != null) {
            premiumNameIndex.put(name.toLowerCase(), uuid);
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_PREMIUM)) {
                ps.setBoolean(1, true);
                ps.setString(2, properties);
                ps.setString(3, name);
                ps.setString(4, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe(I18n.get("log.save_player_failed", uuid, e.getMessage()));
            }
        });
    }

    /**
     * 将离线账号迁移到正版账号（离线账号升级为正版）。
     * 用正版 UUID 创建新记录，保留退出位置等数据，清除密码（正版免密），premium=1。
     * 异步落库：删除离线账号 + 写入正版账号。
     * @return 随机生成的明文密码（用于首次进服提示玩家），迁移失败返回 null
     */
    public String migrateToPremium(UUID offlineUuid, UUID premiumUuid, String name, String ip, String properties) {
        PlayerData offline = players.remove(offlineUuid);
        if (offline == null) return null;
        if (offline.name() != null) {
            premiumNameIndex.remove(offline.name().toLowerCase());
        }
        // 离线记录即将从数据库删除，脏标记不再有意义（防止残留）
        dirty.remove(offlineUuid);
        flushFailures.remove(offlineUuid);
        PlayerData premium = new PlayerData(premiumUuid, name, "",
                ip != null && !ip.isEmpty() ? ip : offline.ip(),
                System.currentTimeMillis() / 1000, offline.logoutLocation(), true, properties, offline.gameMode(),
                offline.totpSecret(), offline.lastActive());
        String plain = assignRandomPassword(premium);
        players.put(premiumUuid, premium);
        premiumNameIndex.put(name.toLowerCase(), premiumUuid);
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = dataSource.getConnection()) {
                // 删除离线记录与写入正版记录须在同一事务，避免中途崩溃导致两记录皆失（账号丢失）
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement del = conn.prepareStatement(SQL_DELETE)) {
                        del.setString(1, offlineUuid.toString());
                        del.executeUpdate();
                    }
                    try (PreparedStatement ups = conn.prepareStatement(SQL_UPSERT)) {
                        bindPlayerData(ups, premium);
                        ups.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                plugin.getLogger().severe(I18n.get("log.migrate_failed", offlineUuid + ": " + e.getMessage()));
            }
        });
        return plain;
    }

    /** 按玩家名查询（用于正版验证 LoginStart 阶段，仅返回 premium=1 的记录） */
    public PlayerData getByName(String name) {
        if (name == null) return null;
        UUID uuid = premiumNameIndex.get(name.toLowerCase());
        return uuid != null ? players.get(uuid) : null;
    }

    /** 是否为正版账号（premium=1） */
    public boolean isPremium(UUID uuid) {
        PlayerData data = players.get(uuid);
        return data != null && data.premium();
    }

    /** 数据库中是否存在正版账号（premium=1）：premiumNameIndex 仅收录正版账号 */
    public boolean hasPremiumAccount() {
        return !premiumNameIndex.isEmpty();
    }

    public void removePlayer(UUID uuid) {
        PlayerData data = players.remove(uuid);
        if (data != null && data.name() != null) {
            premiumNameIndex.remove(data.name().toLowerCase());
        }
        // 清理落库相关状态：账号已删除，脏标记与重试计数不再有意义（防止残留）
        dirty.remove(uuid);
        flushFailures.remove(uuid);
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

    /**
     * 清理不活跃账号：删除超过 days 天未登录的非正版账号。
     * 活跃时间取 lastActive 与 lastLogin 的较大者；两者均为 0 时无法判断活跃度，跳过以保护数据。
     * 正版账号与在线玩家不受影响。同步执行（启动时调用，玩家尚未进入）。
     * @return 清理的账号数量
     */
    public int purgeInactive(int days) {
        long threshold = System.currentTimeMillis() / 1000 - days * 86400L;
        List<UUID> toDelete = new ArrayList<>();
        for (PlayerData data : players.values()) {
            if (data.premium()) continue;
            long activity = Math.max(data.lastActive(), data.lastLogin());
            if (activity > 0 && activity < threshold) {
                toDelete.add(data.uuid());
            }
        }
        for (UUID uuid : toDelete) {
            removePlayer(uuid);
        }
        return toDelete.size();
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
        private volatile String name;
        private volatile String passwordHash;
        private volatile String ip;
        private volatile long lastLogin;
        // 玩家上次已登录退出时的位置（序列化字符串），用于登录后传送回原位置
        private volatile String logoutLocation;
        // 正版标记：true=正版账号（免密），false=离线账号（密码登录）
        private volatile boolean premium;
        // 正版玩家皮肤 properties（JSON 字符串，来自 Mojang hasJoined 响应）
        private volatile String properties;
        // 玩家上次已登录退出时的游戏模式（名称），用于登录后恢复
        private volatile String gameMode;
        // 双因素认证 TOTP 密钥（Base32），null 表示未启用
        private volatile String totpSecret;
        // 最后活跃时间（epoch 秒）：登录成功时更新，用于清理不活跃账号
        private volatile long lastActive;

        public PlayerData(UUID uuid, String name, String passwordHash, String ip, long lastLogin,
                          String logoutLocation, boolean premium, String properties, String gameMode,
                          String totpSecret, long lastActive) {
            this.uuid = uuid;
            this.name = name;
            this.passwordHash = passwordHash;
            this.ip = ip;
            this.lastLogin = lastLogin;
            this.logoutLocation = logoutLocation;
            this.premium = premium;
            this.properties = properties;
            this.gameMode = gameMode;
            this.totpSecret = totpSecret;
            this.lastActive = lastActive;
        }

        public UUID uuid() { return uuid; }
        public String name() { return name; }
        public void name(String name) { this.name = name; }

        public String passwordHash() { return passwordHash; }
        public void passwordHash(String hash) { this.passwordHash = hash; }

        public String ip() { return ip; }
        public void ip(String ip) { this.ip = ip; }

        public long lastLogin() { return lastLogin; }
        public void lastLogin(long lastLogin) { this.lastLogin = lastLogin; }

        public String logoutLocation() { return logoutLocation; }
        public void logoutLocation(String logoutLocation) { this.logoutLocation = logoutLocation; }

        public boolean premium() { return premium; }
        public void premium(boolean premium) { this.premium = premium; }

        public String properties() { return properties; }
        public void properties(String properties) { this.properties = properties; }

        public String gameMode() { return gameMode; }
        public void gameMode(String gameMode) { this.gameMode = gameMode; }

        public String totpSecret() { return totpSecret; }
        public void totpSecret(String totpSecret) { this.totpSecret = totpSecret; }

        public long lastActive() { return lastActive; }
        public void lastActive(long lastActive) { this.lastActive = lastActive; }
    }
}
