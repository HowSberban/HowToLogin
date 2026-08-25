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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
 * Canvas（Folia 分支）用核心层 pearls.dat 恢复了该机制，但恢复实体在 join 之后
 * 才生成且不注册进 getEnderPearls()，事件级接管看不见它
 * 三种服务端行为不同，故所有入口收敛到统一记账原语 absorb，不做平台检测：
 * - onQuit：玩家退出时接管其飞行珍珠（纯 Folia 上是唯一接管时机，
 *   Paper/Canvas 上与核心的保存并存——同区域时同步移除抢在核心保存之前，
 *   核心自然无珍珠可存，不产生恢复副本）
 * - onEntityAddToWorld：实体级拦截（时机无关的主动防线）。核心恢复的珍珠一进
 *   世界即接管，不依赖事件时序与 getEnderPearls() 注册（Canvas 两者都不满足），
 *   也覆盖 Folia 区块重载的退出残留
 * - returnPearls：返还前再吸收一次，封住核心迟到恢复的窗口
 * - onPearlTeleport：未登录玩家被珍珠传送时兜底拦截+按落点记账补偿（仅 Paper 有效，
 *   Folia/Canvas 不触发 ENDER_PEARL 的 PlayerTeleportEvent，Folia#490）。
 *   正常时序下不会触发（未登录玩家投不出珍珠，飞行珍珠都被接管点捕获），
 *   只覆盖接管机制自身的失效窗口。防线全失效时这是最后一道闸——
 *   不拦截即坐标保护被绕过。补偿记账仅记落点（珍珠已消耗，速度不可知）
 * absorb 记账语义（幂等）：珍珠状态与该玩家已记快照同值 → 视为已计数，
 * 仅移除世界副本防双倍；无同值快照 → 追加快照后移除。任何时序下同一颗珍珠
 * 只有一份账目，不会双倍返还
 * 归属链：shooter（投掷路径，内存 projectileSource 可解析）→ NBT Owner UUID 反射
 * （核心 NBT 恢复/区块重载路径，projectileSource 丢失但 Owner 持久在 NBT），
 * 均失败则无法归属，不接管
 * 登录成功后按 pearl.return 配置返还：
 * item = 作为物品进入背包；entity = 在接管时的位置重新生成飞行珍珠（延续原轨迹）
 * 记录持久化到 pearls.dat（单服数据无协同需求，不占用数据库）
 * pearl.enabled 关闭时所有入口均不工作（不接管/不返还），
 * refresh()（启动与 reload）此时清空内存记录与 dat 文件内容
 * Folia：各事件在不同区域线程触发，集合均用并发容器，
 * 珍珠移除优先同区域同步执行，跨区域走实体调度器（实体添加事件内
 * 禁止直接移除，区块状态更新期间会被服务端拒绝），
 * 返还走玩家调度器（onLoginSuccess 内），entity 模式重生走珍珠位置对应的区域调度器
 */
public final class PendingPearlManager implements Listener {

    /** 珍珠快照：接管瞬间的位置与速度，用于登录后原样重生 */
    private record PearlSnapshot(String world, double x, double y, double z,
                                 double vx, double vy, double vz) {}

    private final HTLogin plugin;
    private final File file;
    // 待返还的珍珠快照（接管时写入，启动时从 dat 读入，列表不可变保证并发读安全）
    private final Map<UUID, List<PearlSnapshot>> pending = new ConcurrentHashMap<>();
    // 快照匹配容差：核心 NBT 恢复的坐标/速度与退出快照同为双精度原值，容差仅防浮点噪声
    private static final double STATE_MATCH_EPSILON = 1e-6;
    // NMS ThrowableProjectile.getOwnerUUID() 反射缓存（核心 NBT 恢复路径的珍珠归属）
    private static Method ownerUuidMethod;

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
        // 写入空内容而非删除文件：保留文件占位，避免文件系统反复增删
        saveSync();
    }

    // 退出：接管玩家飞行珍珠，防止留在世界上落地传送（纯 Folia 无核心保存，
    // 此处为唯一接管时机；Paper/Canvas 上同区域时同步移除抢在核心保存之前）
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getConfigManager().pearlEnabled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        boolean changed = false;
        for (EnderPearl pearl : copyFlyingPearls(event.getPlayer())) {
            absorb(pearl, uuid);
            changed = true;
        }
        if (changed) save();
    }

    // 实体级拦截（时机无关的主动防线，见类注释）：核心恢复的珍珠一进世界即接管，
    // 不依赖 PlayerJoinEvent 时序与 getEnderPearls() 注册（Canvas 两者都不满足）
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAddToWorld(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!plugin.getConfigManager().pearlEnabled()) return;
        UUID owner = resolveOwner(pearl);
        if (owner == null) return;
        // 归属玩家的在线实体存在（已登录正常投掷/本插件 entity 模式返还的重生），不拦截
        Player player = Bukkit.getPlayer(owner);
        if (player != null && plugin.getAuthManager().isLoggedIn(player)) return;
        absorb(pearl, owner);
        save();
    }

    // 未登录玩家被珍珠传送：兜底拦截（保险丝，正常时序不触发，见类注释）
    // 仅 Paper 有效——Folia/Canvas 不触发 ENDER_PEARL 的 PlayerTeleportEvent（Folia#490）
    // 只可能来自接管失效窗口漏网的珍珠；拦截传送防坐标保护被绕过。
    // 珍珠已消耗，按落点记账补偿（速度不可知，记零速度），登录后与其他珍珠一并返还：
    // entity 模式在落点重生静止珍珠，下坠撞地方块才触发传送（落点悬空时实际传送点略偏下）；
    // item 模式按物品退款
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
        // 返还前再吸收一次：封住核心迟到恢复珍珠的窗口（同一记账语义）
        boolean absorbed = false;
        for (EnderPearl pearl : copyFlyingPearls(player)) {
            absorb(pearl, player.getUniqueId());
            absorbed = true;
        }
        List<PearlSnapshot> snapshots = pending.remove(player.getUniqueId());
        if (snapshots == null || snapshots.isEmpty()) {
            if (absorbed) saveSync();
            return;
        }
        // 先清记录再发放：发放前崩溃宁可少还，不重复还。
        // 此处须同步写盘：异步写崩溃时会丢这次清记录，重启后旧记录读回导致重复返还
        saveSync();
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
                // 玩家在生成任务执行前退出：珍珠落地时 owner 离线会被销毁（Folia 直接不保存），
                // 改记回 pending 待下次登录返还，防止珍珠凭空丢失
                if (!player.isOnline()) {
                    pending.merge(player.getUniqueId(), List.of(snapshot), (oldList, newList) -> {
                        List<PearlSnapshot> merged = new ArrayList<>(oldList);
                        merged.addAll(newList);
                        return List.copyOf(merged);
                    });
                    saveSync();
                    return;
                }
                world.spawn(loc, EnderPearl.class, p -> {
                    p.setVelocity(new Vector(snapshot.vx(), snapshot.vy(), snapshot.vz()));
                    p.setShooter(player);
                });
            });
        }
        if (fallbackItems > 0) giveItems(player, fallbackItems);
    }

    /**
     * 统一记账原语：接管一颗珍珠进待返还账目（幂等）
     * 与该玩家已记快照同值 → 视为已计数，仅移除世界副本（防双倍返还）；
     * 无同值快照 → 追加快照后移除（新接管，或核心恢复/区块重载的退出残留）
     */
    private void absorb(EnderPearl pearl, UUID owner) {
        Location loc = pearl.getLocation();
        World world = loc.getWorld();
        if (world == null) return;
        Vector vel = pearl.getVelocity();
        String worldName = world.getName();
        if (!hasMatchingSnapshot(owner, worldName, loc, vel)) {
            PearlSnapshot snapshot = new PearlSnapshot(worldName, loc.getX(), loc.getY(), loc.getZ(),
                    vel.getX(), vel.getY(), vel.getZ());
            pending.merge(owner, List.of(snapshot), (oldList, newList) -> {
                List<PearlSnapshot> merged = new ArrayList<>(oldList);
                merged.addAll(newList);
                return List.copyOf(merged);
            });
        }
        removePearl(pearl);
    }

    /**
     * 解析珍珠归属：shooter → NBT Owner UUID 反射 → null（无法归属不接管）
     * 投掷路径写入内存 projectileSource，getShooter 可解析；
     * 核心 NBT 恢复路径不写 projectileSource，须读 NBT 持久化的 Owner UUID
     */
    private UUID resolveOwner(EnderPearl pearl) {
        if (pearl.getShooter() instanceof Player player) {
            return player.getUniqueId();
        }
        return readOwnerUuid(pearl);
    }

    /** 反射读取 NMS Owner UUID（Bukkit 包装类无此 API；getHandle 取 NMS 实体后调 getOwnerUUID，
     *  1.20.5+ 运行时 Mojang 映射下方法名稳定），失败返回 null */
    private static UUID readOwnerUuid(EnderPearl pearl) {
        try {
            Object handle = pearl.getClass().getMethod("getHandle").invoke(pearl);
            if (ownerUuidMethod == null) {
                ownerUuidMethod = handle.getClass().getMethod("getOwnerUUID");
            }
            return (UUID) ownerUuidMethod.invoke(handle);
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取玩家当前飞行珍珠的弱一致副本，失败按无珍珠处理 */
    // getEnderPearls() 标记为 @ApiStatus.Experimental，实际为 Paper 稳定提供的实体视图 API
    @SuppressWarnings("UnstableApiUsage")
    private static List<EnderPearl> copyFlyingPearls(Player player) {
        try {
            return List.copyOf(player.getEnderPearls());
        } catch (Exception e) {
            // Folia 下珍珠在其他区域线程消亡会并发修改该列表（CME），按无珍珠处理；
            // 漏读的珍珠由实体级拦截（区块重载时）与传送兜底拦截（仅 Paper）补偿
            return List.of();
        }
    }

    /** 珍珠是否已计入指定玩家的待返还快照（世界+位置+速度同值） */
    private boolean hasMatchingSnapshot(UUID owner, String world, Location loc, Vector vel) {
        List<PearlSnapshot> snapshots = pending.get(owner);
        if (snapshots == null) return false;
        for (PearlSnapshot s : snapshots) {
            if (sameState(s, world, loc, vel)) return true;
        }
        return false;
    }

    /** 状态比对：世界一致且位置/速度在极小容差内（NBT 双精度保存恢复无损，容差仅防浮点噪声） */
    private static boolean sameState(PearlSnapshot s, String world, Location loc, Vector vel) {
        return s.world().equals(world)
                && Math.abs(s.x() - loc.getX()) < STATE_MATCH_EPSILON
                && Math.abs(s.y() - loc.getY()) < STATE_MATCH_EPSILON
                && Math.abs(s.z() - loc.getZ()) < STATE_MATCH_EPSILON
                && Math.abs(s.vx() - vel.getX()) < STATE_MATCH_EPSILON
                && Math.abs(s.vy() - vel.getY()) < STATE_MATCH_EPSILON
                && Math.abs(s.vz() - vel.getZ()) < STATE_MATCH_EPSILON;
    }

    /**
     * 移除珍珠：当前线程即珍珠所属区域时同步执行（退出接管抢在核心保存读列表之前，
     * 核心 NBT 自然无珍珠可存，重入不再产生恢复副本）；
     * 跨区域或实体添加事件内（禁止直接移除，区块状态更新期间会被拒绝）走实体调度器
     */
    private void removePearl(EnderPearl pearl) {
        if (Bukkit.isOwnedByCurrentRegion(pearl.getLocation())) {
            pearl.remove();
            return;
        }
        // 已消亡实体的调度器为 retired 状态，任务直接跳过，无副作用
        pearl.getScheduler().run(plugin, task -> pearl.remove(), null);
    }

    /** 物品方式返还：优先入背包，溢出部分掉落地面 */
    private void giveItems(Player player, int count) {
        Map<Integer, ItemStack> leftover = player.getInventory()
                .addItem(new ItemStack(Material.ENDER_PEARL, count));
        for (ItemStack item : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        player.sendMessage(I18n.msg("pearl.returned", player, count));
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

    /** 记账路径写盘（退出/实体拦截/传送拦截，区域线程高频调用）：异步执行避免阻塞区域线程。
     *  返还路径的清记录须用 saveSync（崩溃语义），不走此处 */
    private void save() {
        // 全量写执行时刻的最新 pending，最终一致
        Bukkit.getAsyncScheduler().runNow(plugin, task -> saveSync());
    }

    /** 同步写盘（异步任务与 onDisable 兜底共用），串行化保证文件不交错 */
    private synchronized void saveSync() {
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

    /** 关服兜底：同步写盘一次。关服批量退出触发的异步写可能被 onDisable 的任务取消截断，此处确保落盘 */
    public void shutdown() {
        saveSync();
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }
}
