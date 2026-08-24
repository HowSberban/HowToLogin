package org.howtologin.plugin.pearl;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;

import java.io.File;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞行末影珍珠的保管与返还
 * 原版 1.21.2+（Paper）珍珠随玩家退出存入玩家数据，重入时恢复实体继续飞行，
 * 恢复的珍珠落地会传送未登录玩家，绕过登录位置保护——这是要拦截的场景
 * Folia 移除了该机制（线程安全原因）：珍珠不保存不恢复，退出后留在世界里
 * Canvas（Folia 分支）用核心层 pearls.dat 恢复了该机制
 * 三种服务端行为不同，故采用统一接管路径 + 吸收替换语义，不做平台检测：
 * - onQuit：接管玩家飞行珍珠（快照+移除）——纯 Folia 上是唯一接管时机，
 *   Paper/Canvas 上与核心的保存并存（顺序不确定，由替换语义消解）
 * - onJoin：接管核心恢复的飞行珍珠，替换 pending 中同一批珍珠的旧快照
 * - returnPearls：返还前再吸收一次（封住核心迟到恢复的窗口），替换后返还
 * - onPearlTeleport：未登录玩家被珍珠传送时兜底拦截+按落点记账补偿
 *   （覆盖接管点漏掉的一切路径；取消传送不再损失珍珠——登录后返还）
 * 替换语义保证任何时序下只有一份权威副本：登录时世界上有珍珠 → 以实体为准；
 * 没有 → 以退出时的快照为准。任何情况下都不会双倍返还
 * 登录成功后按 protection.pearl.return 配置返还：
 * item = 作为物品进入背包；entity = 在接管时的位置重新生成飞行珍珠（延续原轨迹）
 * 记录持久化到 pearls.dat（单服数据无协同需求，不占用数据库）
 * protection.pearl.enabled 关闭时所有入口均不工作（不接管/不返还），
 * refresh()（启动与 reload）此时清空内存记录与 dat 文件内容
 * Folia：退出/进入事件在不同区域线程触发，集合均用并发容器，
 * 珍珠删除走实体调度器（珍珠可能位于其他区域），返还走玩家调度器（onLoginSuccess 内），
 * entity 模式重生走珍珠位置对应的区域调度器
 */
public final class PendingPearlManager implements Listener {

    /** 珍珠快照：接管瞬间的位置与速度，用于登录后原样重生 */
    private record PearlSnapshot(String world, double x, double y, double z,
                                 double vx, double vy, double vz) {}

    private final HTLogin plugin;
    private final File file;
    // 待返还的珍珠快照（接管时写入，启动时从 dat 读入，列表不可变保证并发读安全）
    private final Map<UUID, List<PearlSnapshot>> pending = new ConcurrentHashMap<>();

    public PendingPearlManager(HTLogin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pearls.dat");
        load();
        refresh();
    }

    /** 配置刷新（启动与 /htlogin reload 时调用）：开关关闭则清空全部保管记录（内存 + dat 文件内容） */
    public void refresh() {
        if (plugin.getConfigManager().pearlEnabled()) return;
        pending.clear();
        // 清空文件内容而非删除：保留文件占位，避免文件系统反复增删
        try {
            new YamlConfiguration().save(file);
        } catch (Exception e) {
            plugin.getLogger().severe(I18n.get("log.pearl_persist_failed", e.getMessage()));
        }
    }

    // 退出：接管玩家飞行珍珠（快照+移除），防止留在世界上落地传送（纯 Folia 无核心保存，
    // 此处为唯一接管时机；Paper/Canvas 上核心也会保存，竞态由 onJoin 的替换语义消解）
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getConfigManager().pearlEnabled()) return;
        Player player = event.getPlayer();
        List<PearlSnapshot> snapshots = takeOver(player);
        if (snapshots == null) return;
        pending.put(player.getUniqueId(), snapshots);
        save();
    }

    // 进入世界：接管核心恢复的飞行珍珠并替换 pending——同一批珍珠以实体为准，防止双倍
    // 纯 Folia 无恢复，此路径为空操作（pending 保留退出时的快照）
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().pearlEnabled()) return;
        Player player = event.getPlayer();
        List<PearlSnapshot> snapshots = takeOver(player);
        // 无恢复珍珠且无旧记录：不覆盖 pending（退出时快照的珍珠仍有效）
        if (snapshots == null) return;
        pending.put(player.getUniqueId(), snapshots);
        save();
    }

    // 未登录玩家被珍珠传送：兜底拦截 + 记账补偿
    // 覆盖接管点漏掉的一切路径（核心迟到恢复的珍珠、Folia 弱一致漏读的珍珠等）：
    // 未登录玩家丢不出珍珠，此处的 ENDER_PEARL 传送只可能来自漏网珍珠
    // 拦截传送（防绕过），珍珠已消耗故按落点记账补偿（零速度），登录后与其他珍珠一并返还：
    // entity 模式在落点重生即落地传送——珍珠完成它中断的旅程；item 模式按物品退款
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (!plugin.getConfigManager().pearlEnabled()) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        Player player = event.getPlayer();
        if (plugin.getAuthManager().isLoggedIn(player)) return;
        event.setCancelled(true);
        Location to = event.getTo();
        World world = to.getWorld();
        if (world == null) return;
        PearlSnapshot credit = new PearlSnapshot(world.getName(),
                to.getX(), to.getY(), to.getZ(), 0, 0, 0);
        pending.merge(player.getUniqueId(), List.of(credit), (oldList, newList) -> {
            List<PearlSnapshot> merged = new ArrayList<>(oldList);
            merged.addAll(newList);
            return List.copyOf(merged);
        });
        save();
    }

    /** 登录成功后返还待还珍珠（onLoginSuccess 内于玩家区域线程调用）
     *  开关关闭时完全不工作（保管记录由 refresh() 在启动/reload 时清空） */
    public void returnPearls(Player player) {
        if (!plugin.getConfigManager().pearlEnabled()) return;
        // 返还前再吸收一次：封住核心迟到恢复珍珠的窗口（同样替换语义）
        List<PearlSnapshot> absorbed = takeOver(player);
        if (absorbed != null) {
            pending.put(player.getUniqueId(), absorbed);
        }
        List<PearlSnapshot> snapshots = pending.remove(player.getUniqueId());
        if (snapshots == null || snapshots.isEmpty()) return;
        // 先清记录再发放：发放前崩溃宁可少还，不重复还
        save();
        if (!plugin.getConfigManager().pearlReturnEntity()) {
            giveItems(player, snapshots.size());
            return;
        }
        // entity 模式：在接管时的位置按原速度重生飞行珍珠
        // 重生即原版行为的延续（珍珠继续飞），不提示；仅退化补偿的部分按物品提示
        int fallbackItems = 0;
        for (PearlSnapshot snapshot : snapshots) {
            World world = Bukkit.getWorld(snapshot.world());
            // 世界已卸载或删除：该珍珠退化为物品返还
            if (world == null) {
                fallbackItems++;
                continue;
            }
            Location loc = new Location(world, snapshot.x(), snapshot.y(), snapshot.z());
            // 区域调度器：重生位置可能不在玩家所在区域，须在珍珠位置所属区域线程执行
            Bukkit.getRegionScheduler().run(plugin, loc, task -> {
                world.spawn(loc, EnderPearl.class, p -> {
                    p.setVelocity(new Vector(snapshot.vx(), snapshot.vy(), snapshot.vz()));
                    p.setShooter(player);
                });
            });
        }
        if (fallbackItems > 0) giveItems(player, fallbackItems);
    }

    /**
     * 接管玩家当前飞行珍珠：快照后移除实体
     * @return 快照列表；玩家当前无飞行珍珠时返回 null（调用方据 null 与否决定是否覆盖 pending）
     */
    private List<PearlSnapshot> takeOver(Player player) {
        List<EnderPearl> pearls;
        try {
            pearls = List.copyOf(player.getEnderPearls());
        } catch (ConcurrentModificationException e) {
            // Folia 下珍珠在其他区域线程消亡会并发修改该列表，弱一致读失败按无珍珠处理
            // 漏读的珍珠由传送兜底拦截（onPearlTeleport）补偿
            return null;
        }
        if (pearls.isEmpty()) return null;
        List<PearlSnapshot> snapshots = new ArrayList<>(pearls.size());
        for (EnderPearl pearl : pearls) {
            if (!pearl.isValid()) continue;
            Location loc = pearl.getLocation();
            World world = loc.getWorld();
            if (world == null) continue;
            Vector vel = pearl.getVelocity();
            snapshots.add(new PearlSnapshot(world.getName(), loc.getX(), loc.getY(), loc.getZ(),
                    vel.getX(), vel.getY(), vel.getZ()));
            // 实体调度器：珍珠可能位于其他区域，删除必须在其所在区域线程执行
            // 已消亡实体的调度器为 retired 状态，任务直接跳过，无副作用
            pearl.getScheduler().run(plugin, task -> pearl.remove(), null);
        }
        return snapshots.isEmpty() ? null : List.copyOf(snapshots);
    }

    /** 物品方式返还：优先入背包，溢出部分掉落地面 */
    private void giveItems(Player player, int count) {
        Map<Integer, ItemStack> leftover = player.getInventory()
                .addItem(new ItemStack(Material.ENDER_PEARL, count));
        for (ItemStack item : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        player.sendMessage(HTLogin.legacy(I18n.get("pearl.returned", player, count)));
    }

    /** 从 dat 读入待返还记录（uuid → 珍珠快照列表） */
    private void load() {
        if (!file.exists()) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String key : yaml.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException ignored) {
                    // 无效 UUID 行跳过
                    continue;
                }
                List<PearlSnapshot> snapshots = new ArrayList<>();
                for (Map<?, ?> map : yaml.getMapList(key)) {
                    if (!(map.get("world") instanceof String world)) continue;
                    snapshots.add(new PearlSnapshot(world,
                            asDouble(map.get("x")), asDouble(map.get("y")), asDouble(map.get("z")),
                            asDouble(map.get("vx")), asDouble(map.get("vy")), asDouble(map.get("vz"))));
                }
                if (!snapshots.isEmpty()) {
                    pending.put(uuid, List.copyOf(snapshots));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe(I18n.get("log.pearl_persist_failed", e.getMessage()));
        }
    }

    /** 将待返还记录写回 dat。synchronized：不同区域线程可能并发接管，串行化避免文件交错损坏 */
    private synchronized void save() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            pending.forEach((uuid, snapshots) -> {
                List<Map<String, Object>> entries = new ArrayList<>(snapshots.size());
                for (PearlSnapshot s : snapshots) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("world", s.world());
                    map.put("x", s.x());
                    map.put("y", s.y());
                    map.put("z", s.z());
                    map.put("vx", s.vx());
                    map.put("vy", s.vy());
                    map.put("vz", s.vz());
                    entries.add(map);
                }
                yaml.set(uuid.toString(), entries);
            });
            yaml.save(file);
        } catch (Exception e) {
            plugin.getLogger().severe(I18n.get("log.pearl_persist_failed", e.getMessage()));
        }
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }
}
