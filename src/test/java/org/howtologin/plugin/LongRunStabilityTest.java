package org.howtologin.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.howtologin.plugin.auth.AuthManager;
import org.howtologin.plugin.auth.AuthManager.LoginResult;
import org.howtologin.plugin.auth.Totp;
import org.howtologin.plugin.config.ConfigManager;
import org.howtologin.plugin.data.PlayerDataManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 长期运行稳定性压测：在 MockBukkit 模拟的 Bukkit 运行时下，使用真实生产构造器
 * （真实 ConfigManager / SQLite / 连接池 / AuthManager / bcrypt）模拟大量玩家反复
 * 注册、登录、改密、强制下线、2FA、注销等业务流，验证：
 * - 内存缓存收敛（脏标记、会话、2FA 临时状态无泄漏）
 * - 周期 flush 落库后与内存一致且无残留
 * - 注销/删号重启后不复活（历史竞态 bug 回归）
 * - IP 注册上限在并发下不被穿透
 * - 2FA 全生命周期（绑定 / 必须验证 / 管理员强制解除）
 * 刻意不触发 onEnable：dialog / packet / premium / 命令等外部 API 分支在 mock 下不可用
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LongRunStabilityTest {

    private static ServerMock server;
    private static HTLogin plugin;
    private static ConfigManager config;
    private static PlayerDataManager data;
    private static AuthManager auth;

    @BeforeAll
    static void bootstrap() throws Exception {
        Path tempDir = Files.createTempDirectory("htlogin-stab-test");
        // 自定义 ServerMock：补 GlobalRegionScheduler 实现（生产代码在异步线程触发同步事件时依赖它，
        // MockBukkit 默认未实现，缺它会抛 UnimplementedOperationException 导致异步回调丢失、压测假死）
        server = MockBukkit.mock(new TestServerMock());
        // 用 MockBukkit 的 loadPlugin 创建插件实例（由插件类加载器构造，满足 Paper 1.21.11 的
        // "JavaPlugin requires to be created by a valid classloader" 校验），但不触发 onEnable：
        // onEnable 的 dialog/packet/premium/命令等外部 API 分支在 mock 下不可用，测试只组装核心数据链路
        plugin = (HTLogin) server.getPluginManager().loadPlugin(HTLogin.class);
        inject(JavaPlugin.class, "dataFolder", plugin, tempDir.toFile());
        inject(JavaPlugin.class, "logger", plugin, Logger.getLogger("HTLoginTest"));
        // 与 onEnable 次序一致：I18n 先于 ConfigManager 初始化（配置版本检查/日志使用 I18n）
        I18n.init(plugin);
        config = new ConfigManager(plugin);
        // 压测参数：bcrypt cost 取 12（贴近生产强度，验证真实哈希负载下的稳定性）；IP 上限调小便于并发断言；
        // failProtection 关闭——多轮密码/2FA 错误码验证会累积失败计数触发踢出，干扰成功率类断言（踢出路径抽离）
        inject(ConfigManager.class, "bcryptCost", config, 12);
        inject(ConfigManager.class, "maxAccountsPerIp", config, 3);
        inject(ConfigManager.class, "failProtectionEnabled", config, false);
        // PlayerDataManager 建数据源走 plugin.getConfigManager()，需先注入（onEnable 中由字段赋值保证）
        inject(HTLogin.class, "configManager", plugin, config);
        data = new PlayerDataManager(plugin);
        auth = new AuthManager(plugin, data, config);
        // 自定义 WorldMock：补 getWorldFolder（注销删档路径依赖它定位世界目录，MockBukkit 默认未实现）
        server.addWorld(new TestWorldMock(new WorldCreator("world"), tempDir.resolve("world").toFile()));
    }

    @AfterAll
    static void teardown() {
        // bootstrap 失败时部分字段为空，逐项保护避免级联 NPE 掩盖真实失败原因
        if (data != null) {
            // 全量落库并排空写队列（saveSync 会关停写线程池，只允许在套件结束时调用一次）
            data.saveSync();
        }
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    /** IP 注册上限在并发注册下不被穿透（registerConfig 名额判定与建号须原子完成） */
    @Test
    @Order(1)
    void ipLimitConcurrentNoBypass() throws Exception {
        String ip = "10.0.0.77";
        // AutoCloseable（Java 19+）：块结束自动 shutdown，无需手工关闭
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                UUID uuid = UUID.randomUUID();
                String name = "ipu" + i;
                String password = "pw" + i;
                tasks.add(() -> auth.registerConfig(uuid, name, password, ip));
            }
            int ok = 0;
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                if (f.get(30, TimeUnit.SECONDS)) ok++;
            }
            assertTrue(ok >= 1, "并发注册应至少成功 1 个");
            assertTrue(ok <= 3, "IP 上限被并发穿透: 成功 " + ok + " > 3");
        }
    }

    /** 长期并发业务流：反复注册/登录（含错误密码）/改密/强制下线，最后断言内存收敛。
     *  cost=12 下单次 bcrypt 约 100-200ms，规模较此前收敛（3 线程 × 16 轮）以控制总时长 */
    @Test
    @Order(2)
    void longRunConcurrentBusinessConverges() throws Exception {
        int threads = 3;
        int roundsEach = 16; // 3*16 = 48 个账号的完整生命周期
        AtomicInteger registered = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                results.add(pool.submit(() -> {
                    for (int i = 0; i < roundsEach; i++) {
                        String name = "long" + tid + "x" + i;
                        String password = "pass" + i;
                        // i%250 在 16 轮内恒等于 i，保证每轮独立 IP（不触发 IP 名额上限）
                        String ip = "10.1." + tid + "." + (i % 250);
                        UUID uuid = UUID.randomUUID();
                        try {
                            if (!auth.registerConfig(uuid, name, password, ip)) {
                                continue; // 名额/重名等拒绝，不视为异常
                            }
                            registered.incrementAndGet();
                            // 错误密码登录：触发失败计数（踢出保护路径）
                            loginBlocking(uuid, "wrongpw", ip);
                            // 正确密码登录
                            assertEquals(LoginResult.SUCCESS, loginBlocking(uuid, password, ip), name + " 应登录成功");
                            // 修改密码：旧密码失效、新密码可用
                            assertTrue(auth.forceChangePassword(uuid, "newpass" + i), name + " 改密失败");
                            assertEquals(LoginResult.FAILED, loginBlocking(uuid, password, ip), name + " 旧密码应失效");
                            assertEquals(LoginResult.SUCCESS, loginBlocking(uuid, "newpass" + i, ip), name + " 新密码应可登录");
                            // 管理员强制下线后仍可再次登录
                            auth.forceLogout(uuid);
                            assertEquals(LoginResult.SUCCESS, loginBlocking(uuid, "newpass" + i, ip), name + " 强制下线后重登失败");
                            // 间歇触发周期任务（模拟 5s flush 与 30s 状态清理）
                            if ((i & 7) == 0) {
                                data.flushDirty();
                                auth.cleanupExpiredStates();
                            }
                        } catch (Throwable ex) {
                            errors.incrementAndGet();
                            plugin.getLogger().log(java.util.logging.Level.SEVERE, "业务循环异常: " + name, ex);
                        }
                    }
                }));
            }
            for (Future<?> f : results) {
                f.get(600, TimeUnit.SECONDS);
            }
        }

        // 最终收敛：flush 两轮后不应再有脏数据
        data.flushDirty();
        auth.cleanupExpiredStates();
        data.flushDirty();

        assertTrue(registered.get() >= threads * roundsEach / 2,
                "注册成功率异常低: " + registered.get() + "/" + (threads * roundsEach));
        assertEquals(0, errors.get(), "业务循环出现异常: " + errors.get());
        assertEquals(0, collectionSize(data, "dirty"), "flush 后仍有脏数据残留");
        assertEquals(0, collectionSize(auth, "verifying"), "密码校验重入标记未收敛");
        assertEquals(0, collectionSize(auth, "pending2fa"), "待验证 2FA 状态未收敛");
        assertEquals(0, collectionSize(auth, "loggedIn"), "登录态未收敛（该场景全为无 Player 操作）");
        assertEquals(0, mapSize(auth, "pending2faSecret"), "临时 2FA 密钥未清理");
        assertEquals(0, mapSize(auth, "pending2faSecretCreatedAt"), "临时 2FA 密钥时间戳未清理");
        assertEquals(0, mapSize(auth, "loginSessions"), "登录会话未清理");
        assertEquals(0, mapSize(auth, "twoFaSessions"), "2FA 会话未清理");
    }

    /** 注销后重启（同库重新装载）账号不得复活——历史"已删行被并发 upsert 复活"回归测试 */
    @Test
    @Order(3)
    void unregisterThenRestartNoResurrect() throws Exception {
        UUID uuid = UUID.randomUUID();
        assertTrue(auth.registerConfig(uuid, "ghost1", "pw1", "10.9.9.9"), "注册失败");
        assertTrue(auth.unregister(uuid), "注销失败");
        data.flushDirty(); // 注销的删除任务先入队
        awaitDbWrites();   // 排空写队列：等待 DELETE 落库后再重读
        assertFalse(data.hasAccount(uuid), "注销后内存应无账号");
        // 模拟重启：全新实例装载同一 SQLite 文件
        PlayerDataManager restarted = new PlayerDataManager(plugin);
        assertFalse(restarted.hasAccount(uuid), "重启后注销账号复活");
    }

    /** 2FA 全生命周期：绑定（正确/错误验证码）→ 登录需验证 → 管理员强制解除 → 恢复直接登录 */
    @Test
    @Order(4)
    void twoFaLifecycleBindDisableAndReset() throws Exception {
        var player = server.addPlayer("faUser");
        UUID uuid = player.getUniqueId();
        String ip = "10.8.8.8";
        String password = "pw";
        assertTrue(auth.registerConfig(uuid, "faUser", password, ip), "注册失败");

        // 绑定：错误验证码被拒，正确验证码生效
        String secret = auth.setup2fa(player);
        assertNotNull(secret, "setup 应返回临时密钥");
        assertFalse(auth.confirm2fa(player, "000000"), "错误验证码应被拒绝");
        assertTrue(auth.confirm2fa(player, totpCode(secret)), "正确验证码应绑定成功");
        assertTrue(auth.has2fa(uuid), "绑定后 has2fa 应为真");

        // 绑定后登录必须走 2FA（与密码正确与否无关，requires2faAtLogin 拦截）
        assertEquals(LoginResult.NEED_2FA, loginBlocking(uuid, password, ip), "绑定后应要求 2FA");

        // 管理员强制解除（误删验证器凭证的救济通道）：解除后直接登录
        assertTrue(auth.reset2fa(uuid), "管理员解除 2FA 失败");
        assertFalse(auth.has2fa(uuid), "解除后 has2fa 应为假");
        assertEquals(LoginResult.SUCCESS, loginBlocking(uuid, password, ip), "解除后应直接登录");
    }

    /** 立即落库（关键操作）后，断电模拟（重载新实例）不丢失注册与 2FA 绑定 */
    @Test
    @Order(5)
    void criticalOperationsSurviveReload() throws Exception {
        UUID uuid = UUID.randomUUID();
        assertTrue(auth.registerConfig(uuid, "crit1", "pw1", "10.7.7.7"), "注册失败");
        assertEquals(LoginResult.SUCCESS, loginBlocking(uuid, "pw1", "10.7.7.7"), "登录失败");
        // 注册为关键操作已经 saveNow 立即落库，排空写队列后重载验证（幂等覆盖：saveNow 直接入队）
        awaitDbWrites();
        PlayerDataManager restarted = new PlayerDataManager(plugin);
        assertTrue(restarted.hasAccount(uuid), "立即落库的注册在重载后丢失");
    }

    /** 场景 1：20 人几乎同时注册（CountDownLatch 齐发），全部成功且抽测可登录 */
    @Test
    @Order(6)
    void twentySimultaneousRegistrations() throws Exception {
        int n = 20;
        UUID[] uuids = new UUID[n];
        for (int i = 0; i < n; i++) {
            uuids[i] = UUID.randomUUID();
        }
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    gate.await(); // 齐发：所有注册任务同时起跑
                    return auth.registerConfig(uuids[idx], "sync" + idx, "spw" + idx, "10.20.0." + idx);
                }));
            }
            gate.countDown();
            int ok = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(60, TimeUnit.SECONDS)) ok++;
            }
            assertEquals(n, ok, "同时注册应全部成功");
        }
        // 抽测 3 人真实登录（cost=12 校验链）
        for (int i = 0; i < 3; i++) {
            assertEquals(LoginResult.SUCCESS, loginBlocking(uuids[i], "spw" + i, "10.20.0." + i), "同时注册的账号应可登录");
        }
    }

    /** 场景 2：200 人注册，期间 100 个未注册玩家反复重进（注册/重进任务交错提交同一并发池，模拟"中间插入"） */
    @Test
    @Order(7)
    void bulkRegisterWithGhostReentry() throws Exception {
        int reg = 200;
        int ghost = 100;
        int rounds = 5; // 每个未注册玩家反复重进 5 轮
        List<UUID> regUuids = new ArrayList<>(reg);
        List<UUID> ghostUuids = new ArrayList<>(ghost);
        for (int i = 0; i < reg; i++) {
            regUuids.add(UUID.randomUUID());
        }
        for (int g = 0; g < ghost; g++) {
            ghostUuids.add(UUID.randomUUID());
        }

        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger regOk = new AtomicInteger();
        AtomicInteger ghostRejected = new AtomicInteger();
        int baseAccountCount = data.getAllUuids().size(); // 重载口径基数：不含本批新增（历史用例账号同库共存）
        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            List<Callable<Object>> merged = new ArrayList<>();
            // 注册与重进任务按序交错入队：偶数位放注册、奇数位放某未注册玩家的 5 轮重进
            int ghostUsed = 0;
            for (int i = 0; i < reg; i++) {
                final int idx = i;
                merged.add(() -> {
                    gate.await();
                    return auth.registerConfig(regUuids.get(idx), "bulk" + idx, "bpw" + idx, "10.30.0." + idx);
                });
                if ((i & 1) == 1 && ghostUsed < ghost) { // 注册间隙穿插未注册玩家的反复重进
                    final UUID ghostUuid = ghostUuids.get(ghostUsed);
                    final int gIdx = ghostUsed++;
                    for (int r = 0; r < rounds; r++) {
                        merged.add(() -> {
                            gate.await();
                            // 未注册账号重进：玩家数据为 null，应直接拒入（FAILED）
                            return loginBlocking(ghostUuid, "nopw", "10.31.0." + gIdx);
                        });
                    }
                }
            }
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : merged) {
                futures.add(pool.submit(task));
            }
            gate.countDown();
            for (Future<Object> f : futures) {
                Object r = f.get(180, TimeUnit.SECONDS);
                if (r instanceof Boolean b) {
                    if (b) regOk.incrementAndGet();
                } else if (r == LoginResult.FAILED) {
                    ghostRejected.incrementAndGet();
                }
            }
        }

        assertEquals(reg, regOk.get(), "200 注册应全部成功");
        assertEquals(ghost * rounds, ghostRejected.get(), "未注册重进应全部被拒");

        // 未注册玩家不占 IP 名额、不产生账号：重载后账号数仅增本批注册数，且不含任何未注册账号
        data.flushDirty();
        awaitDbWrites();
        PlayerDataManager restarted = new PlayerDataManager(plugin);
        assertEquals(baseAccountCount + reg, restarted.getAllUuids().size(), "重载后账号数应仅增本批注册数");
        for (UUID ghostUuid : ghostUuids) {
            assertFalse(restarted.hasAccount(ghostUuid), "未注册账号不应出现在数据库");
        }
        // 内存收敛
        data.flushDirty();
        assertEquals(0, collectionSize(data, "dirty"), "flush 后仍有脏数据残留");
        assertEquals(0, collectionSize(auth, "pendingLogin"), "待登录状态未收敛");
        assertEquals(0, collectionSize(auth, "loggedIn"), "登录态未收敛");
        assertEquals(0, mapSize(auth, "loginSessions"), "登录会话未清理");
    }

    /** 场景 3：addpw / rmpw / 2FA 生命周期状态机稳定——绑定、移除密码、恢复密码、解绑按序交替两轮后无残留 */
    @Test
    @Order(8)
    void passwordLifecycleAnd2faStability() throws Exception {
        // 自定义 PlayerMock：addPasswordAsync 的回调经 player.getScheduler() 派发，MockBukkit 未实现需同步注入
        TestPlayerMock player = new TestPlayerMock(server);
        server.addPlayer(player);
        UUID uuid = player.getUniqueId();
        String ip = "10.40.0.1";
        String password = "start-pw";
        assertTrue(auth.registerConfig(uuid, "pwd2fa", password, ip), "注册失败");
        assertEquals(LoginResult.SUCCESS, loginBlocking(uuid, password, ip), "初始密码应可登录");

        // 绑定 2FA：错误码被拒，正确码生效
        String secret = auth.setup2fa(player);
        assertNotNull(secret, "setup 应返回临时密钥");
        assertFalse(auth.confirm2fa(player, "000000"), "错误验证码应被拒绝");
        assertTrue(auth.confirm2fa(player, totpCode(secret)), "正确验证码应绑定成功");
        assertTrue(auth.has2fa(uuid), "绑定后应生效");

        // 绑定后登录需 2FA：错误码保持待验证，正确码通过，同周期验证码被防重放拒绝
        assertEquals(LoginResult.NEED_2FA, loginBlocking(uuid, password, ip), "绑定后应要求 2FA");
        assertFalse(auth.verify2faConfig(uuid, "000000", ip), "错误 2FA 码应被拒");
        assertTrue(auth.isPending2fa(uuid), "失败后应回到待验证状态");
        String code = totpCode(secret);
        assertTrue(auth.verify2faConfig(uuid, code, ip), "正确 2FA 码应通过");
        auth.addPending2fa(uuid);
        assertFalse(auth.verify2faConfig(uuid, code, ip), "同周期验证码应被防重放拒绝");

        // rmpw：错误码拒、正确码转无密码账户；旧密码随之失效
        assertFalse(auth.removePassword(player, "000000"), "错误验证码移除密码应失败");
        assertTrue(auth.removePassword(player, totpCode(secret)), "正确验证码应可移除密码");
        assertTrue(auth.isPasswordless(uuid), "移除后应为无密码账户");
        assertEquals(LoginResult.FAILED, loginBlocking(uuid, password, ip), "密码已移除，旧密码应失效");

        // 无密码账户 2FA 为唯一登录因素：免密直入仍需验证码且可通过。
        // verify2faConfig 带防重放（按 30s TOTP 周期推进），上文已消费当前周期计数，须等新周期再验证
        awaitTotpPeriodAdvance();
        auth.addPending2fa(uuid);
        assertTrue(auth.verify2faConfig(uuid, totpCode(secret), ip), "无密码账户凭 2FA 应可完成验证");

        // addpw：无密码账户可设密；已有密码再设应被拒绝
        CompletableFuture<Boolean> setFuture = new CompletableFuture<>();
        auth.addPasswordAsync(player, "set-pw", setFuture::complete);
        assertTrue(setFuture.get(30, TimeUnit.SECONDS), "无密码账户应可设置密码");
        assertFalse(auth.isPasswordless(uuid), "设置后应为有密码账户");
        assertTrue(auth.has2fa(uuid), "设密后 2FA 仍生效");
        CompletableFuture<Boolean> again = new CompletableFuture<>();
        auth.addPasswordAsync(player, "another-pw", again::complete);
        assertFalse(again.get(30, TimeUnit.SECONDS), "已有密码再 addpw 应被拒绝");

        // 有密 + 2FA：登录仍须验证码（拦截生效）；解绑（错误码拒/正确码过）后直接登录
        assertEquals(LoginResult.NEED_2FA, loginBlocking(uuid, "set-pw", ip), "设密后仍需 2FA");
        assertFalse(auth.disable2fa(player, "000000"), "错误验证码解绑应失败");
        assertTrue(auth.disable2fa(player, totpCode(secret)), "正确验证码应可解绑");
        assertFalse(auth.has2fa(uuid), "解绑后 2FA 应失效");
        assertEquals(LoginResult.SUCCESS, loginBlocking(uuid, "set-pw", ip), "解绑后应直接登录");

        // 再压 2 轮绑定/移除密码/恢复密码/解绑交替（验证状态机可重复使用、无残留泄漏）。
        // 每轮以解绑收尾，下一轮 setup 才能生成新密钥
        String lastPw = "set-pw";
        for (int round = 1; round <= 2; round++) {
            String s = auth.setup2fa(player);
            assertNotNull(s, "第 " + round + " 轮 setup 应返回新密钥");
            assertTrue(auth.confirm2fa(player, totpCode(s)), "第 " + round + " 轮确认绑定失败");
            assertTrue(auth.has2fa(uuid), "第 " + round + " 轮绑定后应生效");
            assertEquals(LoginResult.NEED_2FA, loginBlocking(uuid, lastPw, ip), "第 " + round + " 轮应要求 2FA");
            assertTrue(auth.removePassword(player, totpCode(s)), "第 " + round + " 轮 rmpw 失败");
            assertTrue(auth.isPasswordless(uuid), "第 " + round + " 轮应为无密码账户");
            CompletableFuture<Boolean> set = new CompletableFuture<>();
            auth.addPasswordAsync(player, "cycle-pw" + round, set::complete);
            assertTrue(set.get(30, TimeUnit.SECONDS), "第 " + round + " 轮 addpw 失败");
            assertFalse(auth.isPasswordless(uuid), "第 " + round + " 轮应为有密码账户");
            lastPw = "cycle-pw" + round;
            // 解绑（凭本轮密钥验证码）后直接登录，为下一轮绑定腾出状态
            assertTrue(auth.disable2fa(player, totpCode(s)), "第 " + round + " 轮解绑失败");
            assertFalse(auth.has2fa(uuid), "第 " + round + " 轮解绑后应失效");
            assertEquals(LoginResult.SUCCESS, loginBlocking(uuid, lastPw, ip), "第 " + round + " 轮解绑后应直接登录");
        }
        // 终态：无 2FA 绑定、可凭密码直接登录
        assertFalse(auth.has2fa(uuid), "结束时应无 2FA 绑定");
        assertEquals(LoginResult.SUCCESS, loginBlocking(uuid, lastPw, ip), "结束时应凭密码直接登录");

        // 状态收敛：无临时密钥/待验证残留
        assertEquals(0, collectionSize(auth, "pending2fa"), "待验证状态未收敛");
        assertEquals(0, mapSize(auth, "pending2faSecret"), "临时密钥未清理");
        assertEquals(0, mapSize(auth, "pending2faSecretCreatedAt"), "临时密钥时间戳未清理");
        // 关键状态落库：注册/密码/2FA 绑定跨重载恢复（confirm 走脏标记，flush 排空后重读）
        data.flushDirty();
        awaitDbWrites();
        PlayerDataManager restarted = new PlayerDataManager(plugin);
        assertTrue(restarted.hasAccount(uuid), "注册未持久化");
        assertNull(restarted.getPlayer(uuid).totpSecret(), "解绑状态未持久化");
    }

    // ===== 工具方法 =====

    /** 阻塞等待异步登录结果（loginConfigAsync 的回调在异步调度线程执行） */
    private static LoginResult loginBlocking(UUID uuid, String password, String ip) throws Exception {
        CompletableFuture<LoginResult> future = new CompletableFuture<>();
        auth.loginConfigAsync(uuid, password, ip, (result, kickSeconds) -> future.complete(result));
        return future.get(30, TimeUnit.SECONDS);
    }

    /** 生产实现一致地生成当前 TOTP 验证码（反射调用 Totp 私有方法，与生产窗口对齐） */
    private static String totpCode(String base32Secret) throws Exception {
        Method decode = Totp.class.getDeclaredMethod("decodeBase32", String.class);
        decode.setAccessible(true);
        byte[] key = (byte[]) decode.invoke(null, base32Secret);
        Method generate = Totp.class.getDeclaredMethod("generateCode", byte[].class, long.class);
        generate.setAccessible(true);
        // generateCode 参数为 epoch 秒（内部再按 30s 周期取整），与 matchCounter 的窗口口径一致
        long timeSeconds = System.currentTimeMillis() / 1000;
        return (String) generate.invoke(null, key, timeSeconds);
    }

    /**
     * 等待共享写队列排空：向串行写线程池提交哨兵任务，其完成表明此前所有写入（INSERT/DELETE）已落库。
     * 区别于 saveSync：不关停写线程池，测试间可重复调用
     */
    private static void awaitDbWrites() throws Exception {
        Field field = PlayerDataManager.class.getDeclaredField("dbWriteExecutor");
        field.setAccessible(true);
        ExecutorService executor = (ExecutorService) field.get(data);
        executor.submit(() -> {
        }).get(30, TimeUnit.SECONDS);
    }

    /** 等待下一个 30s TOTP 周期（与生产 Totp.PERIOD_SECONDS=30 对齐）：verify2faConfig 防重放按周期计数，跨周期验证需先推进 */
    private static void awaitTotpPeriodAdvance() throws InterruptedException {
        long now = System.currentTimeMillis();
        Thread.sleep(30_000 - (now % 30_000) + 100);
    }

    private static void inject(Class<?> declaring, String fieldName, Object target, Object value) throws Exception {
        Field field = declaring.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static int collectionSize(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((Collection<?>) field.get(target)).size();
    }

    @SuppressWarnings("unchecked")
    private static int mapSize(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(target)).size();
    }

    // ===== MockBukkit 扩展 =====

    /** 补 GlobalRegionScheduler：生产代码在异步线程触发同步事件时经它转调度，MockBukkit 默认未实现 */
    private static final class TestServerMock extends ServerMock {
        private final TestGlobalScheduler globalScheduler = new TestGlobalScheduler();

        @Override
        @NotNull
        public GlobalRegionScheduler getGlobalRegionScheduler() {
            return globalScheduler;
        }
    }

    /** 同步执行的事件转发调度器：压测语义下无需真实 tick，立即跑完任务体（callEvent） */
    private static final class TestGlobalScheduler implements GlobalRegionScheduler {
        @Override
        public void execute(@NotNull Plugin plugin, @NotNull Runnable runnable) {
            runnable.run();
        }

        @Override
        @NotNull
        public ScheduledTask run(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task) {
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }

        @Override
        @NotNull
        public ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long delayTicks) {
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }

        @Override
        @NotNull
        public ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long initialDelayTicks, long periodTicks) {
            // 周期任务仅首次执行：测试不依赖后续 tick
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }

        @Override
        public void cancelTasks(@NotNull Plugin plugin) {
        }
    }

    /** 仅用于满足接口返回值的占位任务，测试不依赖其状态 */
    private static final class TestScheduledTask implements ScheduledTask {
        private static final TestScheduledTask INSTANCE = new TestScheduledTask();

        @Override
        @NotNull
        public Plugin getOwningPlugin() {
            return plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return false;
        }

        @Override
        @NotNull
        public CancelledState cancel() {
            return CancelledState.CANCELLED_ALREADY;
        }

        @Override
        @NotNull
        public ExecutionState getExecutionState() {
            return ExecutionState.FINISHED;
        }
    }

    /** 同步执行的实体调度器：addPasswordAsync 的回调经 player.getScheduler() 派发，压测语义下立即回调 */
    private static final class TestEntityScheduler implements EntityScheduler {
        @Override
        public boolean execute(@NotNull Plugin plugin, @NotNull Runnable runnable, Runnable retired, long initialDelayTicks) {
            runnable.run();
            return true;
        }

        @Override
        public ScheduledTask run(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, Runnable retired) {
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }

        @Override
        public ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, Runnable retired, long delayTicks) {
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }

        @Override
        public ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, Runnable retired, long initialDelayTicks, long periodTicks) {
            // 周期任务仅首次执行：测试不依赖后续 tick
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }
    }

    /** 补 getScheduler：addPasswordAsync 等账号密码操作依赖实体调度器，MockBukkit 的 PlayerMock 未实现 */
    private static final class TestPlayerMock extends PlayerMock {
        private final TestEntityScheduler scheduler = new TestEntityScheduler();

        private TestPlayerMock(ServerMock server) {
            super(server, "pwd2fa");
        }

        @Override
        @NotNull
        public EntityScheduler getScheduler() {
            return scheduler;
        }
    }

    /** 补 getWorldFolder：注销删档路径用它定位世界目录，MockBukkit 默认抛 UnimplementedOperationException */
    private static final class TestWorldMock extends WorldMock {
        private final File worldFolder;

        private TestWorldMock(WorldCreator creator, File worldFolder) {
            super(creator);
            this.worldFolder = worldFolder;
        }

        @Override
        @NotNull
        public File getWorldFolder() {
            return worldFolder;
        }
    }
}