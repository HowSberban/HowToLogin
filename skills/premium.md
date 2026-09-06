# ============================================================
# 正版验证核心逻辑 Skill（纯单服·PacketEvents 劫持·模块化）
# ============================================================
# 适用场景：online-mode=false 的单机 Paper/Folia 服务端
# 核心目标：通过 PacketEvents 劫持登录流程，让正版客户端免密进服
# 技术栈：Paper、Folia、PacketEvents、authlib 9.0+
# ============================================================

# 一、核心目标
在 online-mode=false 的单机服务端上，通过 PacketEvents 拦截并重写登录握手流程，
让正版客户端完成 Mojang 加密验证，获取真实正版 UUID 并实现免密进服。

本 Skill 在原主线之上扩展三项能力：
- 模块化：将验证与登录接管流程拆分为职责单一、线程边界清晰的 4 个模块，模块间通过显式接口协作，由连接处理器统一编排。
- 全程加密：从加密握手完成起，到 LoginSuccess 及实体创建，连接全程维持 AES 加密；Mojang 验证结果通过共享密钥派生的服务器哈希与加密通道密码学绑定。
- 登录接管：从 LoginStart 拦截起，经加密握手、Mojang 验证，到设置 state=VERIFYING 让服务端 tick() 自然接管，由插件驱动状态机到 PROTOCOL_SWITCHING，随后交还服务端原生逻辑接手 Configuration→Play→实体创建→事件触发。


# 二、技术固定项
# 服务器
- server.properties 中 online-mode 保持 false

# 硬性依赖
- Paper API
- PacketEvents（paper-plugin.yml 声明 `required: true` 硬性前置；代码仍通过反射 `Class.forName` 加载，避免 HTLogin 常量池引用 PacketEvents 类导致无前置时类加载失败）

# Mojang 会话验证（正版验证）
- 会话验证端点：https://sessionserver.mojang.com/session/minecraft/hasJoined?username=<name>&serverId=<serverHash>
- 属性查询（可选，取皮肤等）：https://sessionserver.mojang.com/session/minecraft/profile/<uuid>


# 三、模块化架构

将整个验证与登录接管流程拆分为四个职责单一的模块。每个模块只暴露最小接口，模块间禁止直接访问彼此内部状态；跨模块协作统一由**连接处理器（ConnectionHandler）** 编排。

**设计原则**：
- **全量内存加载**：所有玩家账户数据（含 UUID、premium 标记、properties）在插件启动时一次性加载至内存 `ConcurrentHashMap`，运行时所有查询均为纯内存操作，无数据库 IO 开销。
- **强制实时验证**：无论数据库中是否标记为 `premium=true`，每次登录均必须通过 `hasJoined` 实时验证当前客户端是否仍持有有效 Mojang 会话。
- **选择性拦截**：仅拦截需要正版验证的连接（premium=1、开启自动验证的新玩家或升级尝试）；离线玩家（premium=0 且无升级标记、离线确认命中）不拦截 LoginStart，由服务端原生处理，降低复杂度与风险。
- **降级先行**：正版账号已有降级标记时，在 LoginStart 阶段迁移数据到离线 UUID 后放行原生离线登录，不进入验证流程。
- **回退直接放行**：正版账号回退标记有效且允许回退（`premiumFallbackAllowed`）时跳过加密握手，直接以正版 UUID 进入走密码登录。
- **设置 state=VERIFYING 后交还**：验证完成后通过反射设置 `authenticatedProfile` + `state=VERIFYING`，让服务端 `tick()` 自然调用 `verifyLoginAndFinishConnectionSetup` → 发送 LoginSuccess → `state=PROTOCOL_SWITCHING`，随后交还服务端原生逻辑接手 Configuration→Play→实体创建→事件触发，不手动创建实体，不手动发送 LoginSuccess。
- **全程加密基线**：加密启用后，所有后续包（含失败时的 Disconnect）强制走 AES 通道，不设旁路开关。


### 模块清单（依赖方向：连接处理器 → 其余模块，其余模块互不依赖）

#### 模块 1：连接处理器（ConnectionHandler）—— 调度 + 加密合并

> 合并理由：加密握手（RSA/AES）与连接生命周期（LoginStart/EncryptionResponse）高度耦合，合并后避免跨模块异步回调嵌套，降低状态机管理难度。

**职责**：
- 监听 `LoginStart` 与 `EncryptionResponse` 包事件（PacketEvents IO/事件线程）。
- 监听连接断开事件（`channelInactive`），用于检测离线客户端（发送 EncryptionRequest 后未收到响应即断开）。
- 为每个连接维护轻量级会话状态机（`SessionContext`，4 个阶段：`START → WAITING_ENCRYPTION_RESPONSE → ENCRYPTED → DONE`）。
- RSA 密钥对管理：启动时生成一次，所有连接复用。
- 解密与加密启用：解密客户端回传的共享密钥与验证令牌；校验令牌一致性后，**立即**通过 `CryptoHandler` 启用 AES-CFB8 双向加密。
- 计算服务器哈希（每次需要调用 `hasJoined` 时计算）。
- 按阶段调用其他模块（DataService、MojangClient、PlayerInjector），是唯一包含跨模块编排逻辑的地方。

**线程模型**：
- 包事件回调运行在 PacketEvents IO/事件线程。
- 阻塞操作（Mojang HTTP）必须转交异步线程；**数据查询为纯内存操作，可在 IO 线程直接完成**。
- 启用加密（修改连接状态）必须在持有连接引用的当前线程完成（即事件回调线程）。
- 状态机推进必须切回 IO 线程（`channel.eventLoop().execute()`）。

**核心接口**：
    void onPacketReceive(PacketReceiveEvent event);  // IO线程回调，分发 LoginStart / EncryptionResponse


#### 模块 2：数据服务（DataService）—— 纯内存仓储

> 全量加载策略：复用 PlayerDataManager 启动时已全量加载的内存缓存，运行时所有查询为 O(1)~O(n) 内存操作。

**职责**：
- 内存查询：`getProfile(name)` 先查离线 UUID（确定性计算），再查正版玩家名匹配，返回 `ProfileResult`（含 `exists`、`premium`、`uuid`、`properties`）。
- 持久化写入：`savePremium` 同时更新内存 Map 与底层存储（异步落盘，不阻塞 IO 线程）。
- 离线确认标记（内存 TTL）：在检测到离线客户端后写入 `(IP + 玩家名)`，有效期内该连接直接走离线登录，不再触发正版验证握手。
- 正版回退标记（内存 TTL）：已注册正版玩家的离线客户端握手前断开/验证失败后写入 `(IP + 玩家名)`，有效期内重连跳过加密握手，以正版 UUID 进入走密码登录。

**线程模型**：
- 查询操作为纯内存读取，可在任何线程（含 IO 线程）直接调用。
- 持久化写入由 PlayerDataManager 异步落盘。

**核心接口**：
    ProfileResult getProfile(String name);
    // ProfileResult { exists, premium, uuid, properties }
    void savePremium(UUID uuid, String name, String ip, String propertiesJson);
    void markOfflineConfirmed(String ip, String name);
    boolean isOfflineConfirmed(String ip, String name);
    void markPremiumFallbackConfirmed(String ip, String name);
    boolean isPremiumFallbackConfirmed(String ip, String name);
    boolean hasAccount(UUID uuid);


#### 模块 3：Mojang 客户端（MojangClient）—— 仅会话验证

> 保持单一职责，仅负责与 Mojang 会话服务器交互，剥离属性查询（皮肤由 `hasJoined` 返回的 properties 承载，无需额外调用）。

**职责**：
- 执行 `GET https://sessionserver.mojang.com/session/minecraft/hasJoined?username=<name>&serverId=<serverHash>`。
- 解析 200 OK 响应，返回正版 UUID（无横线）与皮肤 properties。
- **多端点依次尝试**：每个 HTTP 出站代理 → 官方地址（借道代理访问真正的 Mojang，验证结果可信）；直连 → 镜像候选列表（官方地址硬编码首位 + 配置镜像，代理均不可用时备选）。任一端点给出确定答复（200/204/其它 4xx）即结束；连接失败/过载重试耗尽则熔断并切换下一个端点。
- **必须包含 User-Agent 头**：Mojang sessionserver 会拒绝没有 User-Agent 的请求。
- **端点熔断**：端点连续失败按指数退避隔离（15s 起翻倍，封顶 5 分钟），冷却到期由后台轻量探测确认恢复（玩家验证永不当试探）；全部端点熔断时兜底全量重试。

**线程模型**：
- 严格异步：独立线程池（`premium.http-pool-size`，阻塞式任务不占公共 ForkJoinPool），连接超时固定 5 秒。
- **无需等待**：客户端在发送 EncryptionResponse 之前已向 sessionserver 发送 /join，收到 EncryptionResponse 即可立即查询（与原版服务端行为一致）。
- 启动时 `probeAll` 异步探测所有端点预热连接池。

**验证总时限**：
- 单次完整验证（含端点切换与单端点重试）受 `premium.verify-deadline-ms`（默认 30000）约束，须在客户端约 30 秒无响应主动断开之前结束。

**重试策略**（作用于单个端点）：
- 重试次数 = `premium.max-retries`（默认 2），仅对 429/502/503/504（过载）按 `retry-interval-ms`（默认 500ms）间隔重试
- **204 为确定结论**：客户端 /join 严格先于 EncryptionResponse 发出，204 即玩家未加入会话，零重试直接判未加入
- 连接失败/超时（IOException）：不在本端点重试，立即切换下一个端点（快速回退）

**核心接口**：
    CompletableFuture<Optional<PremiumProfile>> hasJoined(String serverHash, String username);
    // PremiumProfile { uuid, propertiesJson }

**约束**：
- 严禁使用 `/session/minecraft/profile/<uuid>` 做会话验证，该端点仅用于离线取皮肤，不具备会话绑定能力。


#### 模块 4：玩家注入器（PlayerInjector）—— authenticatedProfile 设置 + state 推进

> 承担 authenticatedProfile 设置与 state 反射推进。设置 state=VERIFYING 后交还服务端原生逻辑接手，不手动发送 LoginSuccess，不手动创建实体。

**职责**：
- 异步预触发：在主线程外手动触发 `AsyncPlayerPreLoginEvent`，让权限组/领地等插件异步加载数据；若事件结果为 KICK，则跳过后续流程直接断连。
- 状态机反射推进：通过反射将 `ServerLoginPacketListenerImpl` 的 `authenticatedProfile`、`requestedUsername`、`state`（置为 `VERIFYING`）设置好，使服务端 `tick()` 自然调用 `verifyLoginAndFinishConnectionSetup` → 发送 LoginSuccess → `state=PROTOCOL_SWITCHING`。
- authlib GameProfile 构造：`com.mojang.authlib.GameProfile/Property` 运行时由 Paper 服务端提供，编译期通过 compileOnly 可见。authlib 9.0+ GameProfile 是 record，PropertyMap 内部 Multimap 不可变，需用 `ArrayListMultimap` 收集 properties 后通过 `PropertyMap(Multimap)` 构造器传入，再用 `GameProfile(UUID, String, PropertyMap)` 三参构造器重建 profile。

**不承担**：
- LoginSuccess 发送：由服务端 `tick()` → `verifyLoginAndFinishConnectionSetup` → `placeNewPlayer` 自然发送（含正版 UUID + 皮肤）。
- 实体创建：由服务端原生逻辑在 PLAY 状态自动完成。
- PlayerLoginEvent / PlayerJoinEvent 触发：由服务端原生逻辑自动触发。

**线程模型**：
- `setProfileAndAdvanceState`：在连接线程（IO线程）完成。
- `AsyncPlayerPreLoginEvent`：在异步线程触发。

**核心接口**：
    CompletableFuture<Boolean> fireAsyncPreLogin(String name, UUID uuid, String ip);
    void setProfileAndAdvanceState(Channel channel, UUID uuid, String name, String propertiesJson) throws Exception;


### 辅助模块

#### 会话状态机（SessionContext）
- 绑定唯一网络连接，记录正版验证流程的当前阶段和临时数据。
- 内部状态变更使用 `AtomicReference` 保证可见性（IO线程写入，异步线程可能读取）。
- 阶段流转：`START → WAITING_ENCRYPTION_RESPONSE → ENCRYPTED → DONE`

#### 加密处理器（CryptoHandler）
- AES-CFB8 加密处理器，Netty 4.1.136 移除了 `io.netty.handler.codec.crypto` 包，自行实现等价处理器。
- 安装位置（与原版 Minecraft Connection.setupEncryption 一致）：
  - DecryptHandler: `addBefore("splitter", ...)` — 在帧解码器之前解密原始字节流
  - EncryptHandler: `addBefore("prepender", ...)` — 在帧编码器之前加密输出字节流
- **Java 17+ 必须显式指定 IvParameterSpec**：AES/CFB8 不再自动生成 IV，必须用共享密钥同时作为 key 和 IV（与 vanilla Connection.setupEncryption 一致），否则抛 "Parameters missing"。


### 模块化约束（统一）

1. **物理隔离**：每个模块独立成文件，可单独进行单元测试（Mock 其他模块接口）。
2. **单向依赖**：`ConnectionHandler` 是唯一持有其他模块引用的编排者；`DataService`、`MojangClient`、`PlayerInjector` 互不依赖，也不反向调用 `ConnectionHandler`。
3. **线程安全**：
   - 跨线程传递的数据（如 `UUID`、`name`、`properties`）必须为不可变对象（`final` 字段）。
   - 每个连接的会话状态机对象（`SessionContext`）绑定唯一 `Channel`，内部状态变更使用 `AtomicReference` 保证可见性。
4. **加密强制**：任何模块在加密启用后（ENCRYPTED 阶段）如需发包（`Disconnect`），必须通过 PacketEvents 的同一加密连接发送，禁止直接操作底层 `Channel` 旁路。
5. **正版验证强制（安全基线）**：
   - 所有标记为 `premium=1` 的玩家每次登录仍必须执行 `hasJoined` 实时验证，以确认当前连接确实持有有效 Mojang 会话，防止离线客户端冒充正版玩家。
   - 此策略为强制行为，不提供配置开关。


# 四、核心流程（7阶段）

### 【阶段1】LoginStart 拦截与查档

**触发**：客户端发送 LoginStart 包

**动作（连接处理器，IO线程）**：
1. 获取玩家名称与连接 IP（优先反射 `packet_handler`（NMS Connection）的 `getRemoteAddress()`，兼容 proxy-protocol 内网穿透；失败回退 `channel.remoteAddress`）。
2. **同步**调用 `DataService.getProfile(name)`（纯内存查询），按账号状态分派：
   - **离线账号（premium=0）** → 仅当正版总开关开启且有升级标记（`/upgrade`）时拦截做正版验证（升级成功迁移数据，失败回退离线）；否则**不拦截**，由服务端原生处理
   - **正版账号（premium=1）且有降级标记**（`/downgrade`）→ LoginStart 阶段即可算出离线 UUID，迁移账号数据后**放行**，走服务端原生离线登录（密码或 2FA 登录读到离线账号）
   - **正版账号（premium=1）且回退标记有效**（`isPremiumFallbackConfirmed` 且 `premiumFallbackAllowed(uuid)` 为真）→ 跳过加密握手，取消 LoginStart 后直接以正版 UUID 进入，`authManager.markPremiumFallback` 标记本次需密码登录（不自动免密）
   - **新玩家（不存在）** → 仅当正版总开关与 `auto-verify` 均开启且未命中离线确认标记时拦截；否则按离线处理
   - **premium=1 / 需拦截的新玩家 / 升级尝试** → 取消该包默认处理（`event.setCancelled(true)`），转【阶段2】
3. 创建 `SessionContext`，记录 username、ip（升级尝试另记 offlineUuid 与 upgradeAttempt）。
4. 生成一次性验证令牌 verifyToken（4 字节随机）。
5. 通过 PacketEvents 发送 `EncryptionRequest`（serverId="", publicKey, verifyToken）。
6. 注册连接断开监听（`channelInactive`）：若在收到 EncryptionResponse 之前断开（`WAITING_ENCRYPTION_RESPONSE` 状态）：
   - 新玩家 → `DataService.markOfflineConfirmed(ip, name)`（离线客户端，重连走离线）
   - 升级尝试 → 清除升级标记回退离线
   - 已注册正版玩家且 fallback 开启 → `DataService.markPremiumFallbackConfirmed(ip, name)`（离线启动器，重连走密码回退）
7. 推进会话状态：`START → WAITING_ENCRYPTION_RESPONSE`，并调度 `handshake-timeout-ms` 超时清理（防恶意客户端滞留会话）。

**线程**：全程 IO线程（内存查询 + 包发送）


### 【阶段2】接收 EncryptionResponse 与加密启用

**触发**：客户端回传 EncryptionResponse

**动作（连接处理器，IO线程 → 异步线程）**：
9. 校验会话存在且阶段为 `WAITING_ENCRYPTION_RESPONSE`，取消包默认处理。
10. 用 RSA 私钥解密共享密钥（SecretKey）；用 RSA 私钥解密验证令牌并校验一致性。
    - 令牌不匹配 → 直接 `channel.close()`（加密尚未启用，不发 Disconnect）。
11. **立即**通过 `CryptoHandler.enableEncryption(channel, sharedSecret)` 启用 AES-CFB8 双向加密。
12. 推进会话状态：`WAITING_ENCRYPTION_RESPONSE → ENCRYPTED`。
13. 移除断开检测器（已收到响应，确认为正版客户端）。
14. 计算服务器哈希：`serverHash = new BigInteger(SHA-1( "" + sharedSecret + publicKey )).toString(16)`（**注意：不带 signum=1 参数，与 vanilla 一致**）。
15. 异步转交 MojangClient 执行【阶段3】验证。

**线程**：IO线程（解密+加密启用+哈希计算）→ 异步线程（调用 MojangClient）


### 【阶段3】Mojang 会话验证

**触发**：阶段2 完成后

**语义说明**：能走到阶段3说明客户端已成功回传 EncryptionResponse 并完成加密握手，即确认为正版客户端。因此 `hasJoined` 失败只可能是 Mojang API 网络问题或限流，与客户端身份无关。

**动作（MojangClient 异步线程 → 连接处理器 IO线程）**：
16. 受 `verify-deadline-ms` 总时限约束，依次向后端点发起 `MojangClient.hasJoined(serverHash, username)`：
    - **200 OK** → 解析 JSON 取无横线 UUID 与 properties（皮肤），返回 `PremiumProfile`（此时已确认为正版客户端）。
    - **204** → 玩家未加入会话，确定结论，**零重试**直接判未加入。
    - **429/502/503/504** → 端点过载，按 `retry-interval-ms` 等待后重试（`max-retries` 次），重试耗尽则熔断该端点并切换下一个。
    - **连接失败/超时** → 端点不可达，立即切换下一个端点（快速回退）。
    - **全部端点不可用/总时限耗尽** → 返回 `Optional.empty()`。
17. 验证成功 → 按账号来源落库并转【阶段4】：
    - **升级尝试** → `migrateToPremium(离线UUID, 正版UUID, ...)` 迁移账号 + 原版玩家数据（背包/成就/统计，`migratePlayerDataAsync`），清除升级标记
    - **存量 pending 为 premium=1 的强制标记账号** → 同样迁移到正版 UUID（保留退出位置数据），防止与新建记录并存
    - **首次注册** → `dataService.savePremium(uuid, name, ip, properties)`，无密码账户（正版验证即身份凭证，可 `/addpassword` 设密码）
    - 清除 ip+名 回退标记（下次优先走正常验证）
18. 验证失败 → 根据账号状态回退或踢出（切回 IO线程）：
    - **升级尝试** → 清除升级标记回退离线，玩家重进按离线登录
    - **已注册正版账号且 `premiumFallbackAllowed(uuid)` 为真**（fallback 开启；无密码账号还要求 `reject-no-auth-account=false`）→ `authManager.markPremiumFallback(uuid)`，放行以正版 UUID 进入走密码登录（阶段7 不自动免密）
    - **其余** → 经加密通道发送 Disconnect（提示"正版验证服务暂时不可用，请稍后重试"），清理会话，**不写入任何标记**

**线程**：异步线程（HTTP）→ IO线程（发送 Disconnect 或继续下一步）


### 【阶段4】异步预登录事件

**触发**：阶段3 验证成功

**动作（PlayerInjector 异步线程 → 连接处理器 IO线程）**：
19. 异步触发 `AsyncPlayerPreLoginEvent`（`PlayerInjector.fireAsyncPreLogin`），让权限组/领地等插件加载玩家数据。
    - 由于取消了 LoginStart，服务端不会自动触发此事件，需手动 callEvent。
20. 事件结果处理（切回 IO线程 `channel.eventLoop().execute()`）：
    - 连接已断开 → 清理会话，结束。
    - 被 KICK → 经加密通道发送 Disconnect，清理会话。
    - 允许继续 → 转【阶段5】。

**线程**：异步线程（事件触发）→ IO线程（结果处理）


### 【阶段5】设置 authenticatedProfile + state=VERIFYING

**触发**：阶段4 允许继续

**动作（PlayerInjector，IO线程）**：
21. 通过反射设置 `ServerLoginPacketListenerImpl`：
    - `authenticatedProfile` = 通过反射构造的 `GameProfile`（含正版 UUID、name、properties）
    - `requestedUsername` = name（取消了 LoginStart，服务端未设置此字段）
    - `state` = `VERIFYING`（使服务端 `tick()` 自然调用 `verifyLoginAndFinishConnectionSetup`）
22. 推进会话状态：`ENCRYPTED → DONE`。

**线程**：IO线程（反射操作不涉及发包）

**关键**：设置 `state=VERIFYING` 后，服务端 `tick()` 会在下一个 tick 自动调用 `verifyLoginAndFinishConnectionSetup`（经反编译 Paper 1.21.11 字节码确认）：
- 调用 `PlayerList.canPlayerLogin` → `CraftEventFactory.handleLoginResult`（触发同步的 `PlayerLoginEvent`，可踢出）
- 发送 Compression 包（若启用压缩），设置 `state=WAITING_FOR_DUPE_DISCONNECT`
- `tick()` 在 `WAITING_FOR_DUPE_DISCONNECT` 状态调用 `finishLoginAndWaitForClient` → 发送 LoginSuccess（含正版 UUID + 皮肤）→ `state=PROTOCOL_SWITCHING`
- **注意**：`AsyncPlayerPreLoginEvent` 由 `handleHello`（LoginStart 处理器）中的 `callPlayerPreLoginEvents` 触发，`verifyLoginAndFinishConnectionSetup` **不**触发它。由于我们取消了 LoginStart，必须手动触发 `AsyncPlayerPreLoginEvent`（阶段4）。


### 【阶段6】服务端自然发送 LoginSuccess 与接手

**触发**：阶段5 设置 state=VERIFYING 后的下一个 tick

**动作（服务端原生，主线程/tick 线程 → IO线程）**：
23. 服务端 `tick()` 检测到 `state=VERIFYING`，调用 `verifyLoginAndFinishConnectionSetup`：
    - 调用 `canPlayerLogin` → `handleLoginResult`（触发同步的 `PlayerLoginEvent`，可踢出）
    - 发送 Compression 包（若启用压缩），设置 `state=WAITING_FOR_DUPE_DISCONNECT`
24. 服务端 `tick()` 检测到 `state=WAITING_FOR_DUPE_DISCONNECT`，调用 `finishLoginAndWaitForClient`：
    - 经加密通道发送 `LoginSuccess`（UUID 为正版 UUID，附带皮肤 properties）
    - 设置 `state=PROTOCOL_SWITCHING`
25. 客户端收到 LoginSuccess 后回传 `LoginAcknowledged`，服务端原生逻辑接手：
    - `LoginAcknowledged` → Configuration 协商 → Play 状态过渡
    - 服务端原生创建 Player 实体（主线程 / Folia 区域线程）
    - 服务端原生触发 `PlayerJoinEvent`
26. 全程维持 AES 加密，连接不回退明文。

**线程**：服务端 tick 线程（发送 LoginSuccess）→ 服务端主线程（实体创建与事件触发）


### 【阶段7】玩家加入后免密登录

**触发**：`PlayerJoinEvent`

**动作（PlayerListener，主线程）**：
26. 检测 `authManager.isPremium(player)`：
    - **正版玩家且处于回退态**（`isPremiumFallback`，本次验证失败/离线启动器放行）→ **不自动免密**，走 `beginAuthFlow` 密码登录流程（登录成功清除回退标记）
    - **正常正版验证进服** → `autoLogin` 免密登录，发送"正版验证通过，已自动登录"提示，检查 IP 自动登录决定是否传送
    - **非正版玩家** → 走原有离线登录流程（密码登录/注册）

**线程**：主线程（Folia 区域线程）


# 五、正版验证全程加密

"全程加密"指：从阶段2启用 AES 起，到阶段6 LoginSuccess 发送完成，连接在任何阶段都不回退明文；
且 Mojang 验证结果通过共享密钥派生的服务器哈希与加密通道密码学绑定，无法伪造。

### 1. 握手前的安全基线
- 验证令牌（verifyToken）一次性、随机生成，握手完成后丢弃，禁止跨连接复用。
- RSA 私钥仅存内存，不写入可读文件。
- 共享密钥仅存于会话内存。

### 2. 握手后全程加密
- 阶段2 解出共享密钥后，第一时间启用 AES-CFB8 双向加密（入站+出站）。
- 阶段6 的 LoginSuccess、Configuration/Play 全部数据包均经加密通道收发（由服务端原生发送，经我们安装的加密处理器）。
- 任何模块在加密启用后发包，均应通过 PacketEvents 的同一加密连接，不得新建裸通道。

### 3. Mojang 验证的密码学绑定
会话验证使用：
  `GET https://sessionserver.mojang.com/session/minecraft/hasJoined?username=<name>&serverId=<serverHash>`
其中 `serverHash = new BigInteger(SHA-1( serverId("") + 共享密钥 + 服务端公钥 )).toString(16)`。
- **关键**：`BigInteger` 构造**不带 signum=1 参数**，与 vanilla Minecraft 一致。若 digest 首字节 >= 0x80，结果为负数（如 `-a3f2...`），客户端也用相同方式计算。若用 `BigInteger(1, digest)` 强制正数，会导致 serverHash 不匹配，Mojang 返回 204。
- `hasJoined` 返回 200 即证明：客户端持有共享密钥（与当前加密通道同一会话）且已在 Mojang 登录。
  验证结果与加密通道绑定，离线/中间人无法伪造 serverHash。
- `/session/minecraft/profile/<uuid>` 仅用于按 UUID 取皮肤等属性，不用于会话验证。
  `hasJoined` 返回的 properties 已含皮肤，通常无需再查 profile。

### 4. 加密贯穿实体创建
- 阶段6 玩家实体创建、Configuration 协商、Play 阶段初始同步包，全部在加密通道上进行。
- 加密状态由连接对象持有，状态机推进与实体创建不重建连接、不重置密钥。

### 5. 失败与异常
- `hasJoined` 失败时，若已启用加密，断连包同样经加密通道发送。
- 超时分支若主动发 Disconnect，必须经加密通道。
- 严禁为"快速失败"而绕过加密直发明文 Disconnect。


# 六、登录接管（state=VERIFYING 与服务端 tick() 接管）

本节说明插件如何从 LoginStart 拦截起，设置 state=VERIFYING 让服务端 tick() 自然接管，随后交还服务端原生逻辑。

### 1. 为何需要接管
取消 LoginStart 默认处理后，服务端 `ServerLoginPacketListenerImpl` 停留在 HELLO 状态，
既不会自动发 EncryptionRequest，也不会在收到 LoginAcknowledged 后自动转入 Configuration。
因此验证完成后必须由插件显式设置 `authenticatedProfile` + `state=VERIFYING`，让服务端 `tick()` 接管后续流程。

### 2. 服务端状态机（Paper 1.21.11 / Canvas 26.1.2）

`ServerLoginPacketListenerImpl.State` 枚举：
```
HELLO → KEY → AUTHENTICATING → NEGOTIATING → VERIFYING → WAITING_FOR_DUPE_DISCONNECT → PROTOCOL_SWITCHING → ACCEPTED
```

关键状态转换：
- `handleHello`（LoginStart）：`online-mode=false` 时 → `authenticatorPool.execute` → `startClientVerification` → `state=VERIFYING`
- `handleKey`（EncryptionResponse）：解密共享密钥 → 计算 serverHash → `state=AUTHENTICATING` → 异步 hasJoined
- `tick()` 在 `VERIFYING` 状态：调用 `verifyLoginAndFinishConnectionSetup` → `canPlayerLogin`（触发 `PlayerLoginEvent`）→ 发送 Compression 包 → `state=WAITING_FOR_DUPE_DISCONNECT`
- `tick()` 在 `WAITING_FOR_DUPE_DISCONNECT` 状态：调用 `finishLoginAndWaitForClient` → 发送 LoginSuccess → `state=PROTOCOL_SWITCHING`

**我们的方案**：取消 LoginStart 后，服务端 state 保持 HELLO。验证完成后设置 `authenticatedProfile` + `state=VERIFYING`，让 `tick()` 自然调用 `verifyLoginAndFinishConnectionSetup`。

### 3. 接管职责清单（PlayerInjector）
(a) **AsyncPlayerPreLoginEvent 触发**：手动 callEvent，让权限组/领地等插件能加载玩家数据。
    必须在实体创建前、主线程外（异步）触发；事件结果（KICK 等）需在转主线程前处理。
    注意：`AsyncPlayerPreLoginEvent` 由 `handleHello`（LoginStart 处理器）中的 `callPlayerPreLoginEvents` 触发。由于我们取消了 LoginStart，服务端不会触发此事件，需手动触发。`verifyLoginAndFinishConnectionSetup` 触发的是同步的 `PlayerLoginEvent`（经 `canPlayerLogin`），不是 `AsyncPlayerPreLoginEvent`。
(b) **状态机反射推进**：通过反射设置：
    - `authenticatedProfile` = GameProfile（含正版 UUID 和 properties）
    - `requestedUsername` = name
    - `state` = `VERIFYING`
    使服务端 `tick()` 自然调用 `verifyLoginAndFinishConnectionSetup` → 发送 LoginSuccess → `state=PROTOCOL_SWITCHING`。
(c) **LoginSuccess 发送**：由服务端 `tick()` → `placeNewPlayer` 自然发送，**不手动发送**。
(d) **Configuration→Play→实体创建→事件触发**：交还服务端原生逻辑，插件不手动创建实体。

### 4. 反射推进要点
- authlib（`com.mojang.authlib.GameProfile/Property`）运行时由 Paper 提供，编译期通过 compileOnly 可见。
- authlib 9.0+ GameProfile 是 record，PropertyMap 内部 Multimap 不可变。
- 构造含皮肤的 GameProfile：
  ```java
  Multimap<String, Property> multimap = ArrayListMultimap.create();
  multimap.put(propName, new Property(propName, value, signature));
  PropertyMap propertyMap = new PropertyMap(multimap);
  GameProfile profile = new GameProfile(uuid, name, propertyMap);
  ```
- NMS 字段（`authenticatedProfile`、`requestedUsername`、`state`）仍需反射，因为 NMS 类不在编译期依赖中。
- `state` 枚举值通过 `Enum.valueOf(Class, "VERIFYING")` 获取。
- 反射操作本身不涉及发包，在 IO 线程完成。

### 5. 事件顺序与线程（硬约束）
**插件触发**：`AsyncPlayerPreLoginEvent`（异步）→ [转 IO 线程] → 状态机反射推进（设置 state=VERIFYING）。

**服务端原生触发**（tick() 检测到 VERIFYING 后自动）：`PlayerLoginEvent`（同步，可踢出，在 `verifyLoginAndFinishConnectionSetup` 中）→ 发送 LoginSuccess（IO线程）→ `PlayerJoinEvent`（主线程，实体创建后）。
事件顺序由服务端原生逻辑保证，插件不干预。

Folia 下，主线程语义替换为对应区域线程；跨区域操作必须通过调度器提交。

### 6. 失败回退
- 状态机推进抛异常时，必须经加密通道发送 Disconnect 并清理会话与缓存，
  不得让连接停留在半接管状态。
- 若 `AsyncPlayerPreLoginEvent` 被 KICK，跳过状态机推进，直接断连。


# 七、技术拐点

### 🔴 拐点 A：state=VERIFYING 而非 PROTOCOL_SWITCHING（关键经验）
**说明**：之前尝试直接设置 `state=PROTOCOL_SWITCHING`，但服务端 `tick()` 不会对手动设置的 PROTOCOL_SWITCHING 调用 `handleAcceptedLogin()`，导致玩家卡在"通讯加密中"直到超时。
**方案**：设置 `state=VERIFYING`，让服务端 `tick()` 自然调用 `verifyLoginAndFinishConnectionSetup` → `placeNewPlayer` → 发送 LoginSuccess → `state=PROTOCOL_SWITCHING`。这样服务端会自然完成所有后续流程。

### 🔴 拐点 B：不要拦截 PacketSendEvent（关键经验）
**说明**：之前尝试通过 `onPacketSend` + `event.setCancelled(true)` 拦截服务端的 LoginSuccess，改为发送 EncryptionRequest。但 PacketEvents 在第一次 `setCancelled` 后，不再为后续连接触发 `onPacketSend`，导致第二次登录卡住直到超时。
**方案**：不要拦截 outbound 包。改为取消 LoginStart，自行发送 EncryptionRequest，验证完成后设置 `state=VERIFYING` 让服务端自然发送 LoginSuccess。

### 🔴 拐点 C：serverHash 计算的 BigInteger（关键经验）
**说明**：之前用 `new BigInteger(1, digest).toString(16)` 计算 serverHash，强制为正数。但 vanilla Minecraft 用 `new BigInteger(digest).toString(16)`（不带 signum=1），若 digest 首字节 >= 0x80，结果为负数（如 `-a3f2...`）。这导致约 50% 的登录因 serverHash 不匹配而失败（Mojang 返回 204）。
**方案**：用 `new BigInteger(digest).toString(16)`，与 vanilla 一致。通过反编译 `ServerLoginPacketListenerImpl.handleKey` 确认。

### 🔴 拐点 D：Mojang hasJoined 限流与 204 语义
**说明**：Mojang sessionserver 对频繁请求有限流（429）；204 并非限流信号，而是"玩家未加入会话"的确定结论——客户端 /join 严格先于 EncryptionResponse 发出，能走到 hasJoined 请求说明已收到 EncryptionResponse，收到 204 即未加入，重试只会浪费总时限。
**方案**：204 零重试直接判未加入；仅 429/502/503/504 按 `retry-interval-ms`（默认 500ms）有限重试（`max-retries`=2）；全部重试与端点切换受 `verify-deadline-ms`（默认 30s）总时限约束，防止超出客户端断开等待。

### 🔴 拐点 E：验证超时处理
**说明**：阶段3 异步请求 hasJoined 可能耗时较久，客户端等待 LoginSuccess/Disconnect，超 30 秒客户端主动断开。
**方案**：配置 `timeout-seconds`（默认 5），MojangClient HTTP 超时后返回 empty，经加密通道发 Disconnect。Disconnect 必须走加密通道。

### 🔴 拐点 F：加密启用时机与旁路风险
**说明**：若 Disconnect 在启用 AES 之前发送，会以明文暴露踢出文本，破坏"全程加密"。
**方案**：连接处理器在阶段2启用 AES 后，才允许发送 Disconnect。禁止任何模块旁路加密连接。

### 🔴 拐点 G：AES/CFB8 加密初始化
**说明**：Java 17+ 不再为 AES/CFB8 自动生成 IV，必须显式指定 IvParameterSpec，否则抛 "Parameters missing"。
**方案**：用共享密钥同时作为 key 和 IV（与 vanilla Connection.setupEncryption 一致）：
```java
SecretKeySpec keySpec = new SecretKeySpec(sharedSecret, "AES");
IvParameterSpec ivSpec = new IvParameterSpec(sharedSecret);
Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
```

### 🔴 拐点 H：authlib 9.0+ GameProfile 构造
**说明**：authlib 9.0+ 的 GameProfile 是 record，PropertyMap 内部 Multimap 不可变，无法直接 `put` 添加皮肤 properties。
**方案**：用 `ArrayListMultimap` 收集 properties 后通过 `PropertyMap(Multimap)` 构造器传入，再用 `GameProfile(UUID, String, PropertyMap)` 三参构造器重建 profile。

### 🔴 拐点 I：离线客户端的识别时机
**说明**：离线客户端无法解密 EncryptionRequest，会在阶段1发送后主动断开。此时需通过 `channelInactive` 事件捕获并写入离线确认标记。
**方案**：在阶段1发送 EncryptionRequest 后，为该连接注册 `channelInactive` 监听。若在收到 EncryptionResponse 之前断开（`WAITING_ENCRYPTION_RESPONSE` 状态），则确认为离线客户端，调用 `DataService.markOfflineConfirmed(ip, name)`。该标记有效期内重连直接走离线登录（阶段1不拦截）。

### 🔴 拐点 J：PacketEvents 依赖的反射加载
**说明**：ConnectionHandler、PlayerInjector、InventoryPacketListener 继承 PacketEvents 类，若直接 import 会在插件加载阶段触发 PacketEvents 类解析失败（NoClassDefFoundError），导致插件无法在无 PacketEvents 的服务器加载。
**方案**：在 HTLogin.onEnable 中通过反射 `Class.forName` 加载这些类，避免 HTLogin 常量池引用 PacketEvents 类。提取 `registerPacketEventsListener` 辅助方法处理反射注册。


# 八、配置项

统一配置在 `premium` 段下（完整注释见 config.yml），关键项：

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `enabled` | false | 正版验证总开关；关闭后新玩家/升级不触发验证，但 **premium=1 玩家仍强制验证**（防丢账号） |
| `auto-verify` | true | 新玩家进服是否自动尝试正版验证；false 时一律按离线身份进入，需手动 `/upgrade` |
| `timeout-seconds` | 5 | 单次 hasJoined 请求超时（秒），仅兜底挂起请求，正常响应在 1 秒内 |
| `verify-deadline-ms` | 30000 | 一次完整验证（含端点切换与重试）总时限，须小于客户端约 30 秒断开上限 |
| `handshake-timeout-ms` | 30000 | 加密握手阶段等待 EncryptionResponse 的超时，防会话被挂机客户端滞留 |
| `max-retries` | 2 | 429/5xx 过载重试次数（不含首次），204 为确定结论不重试 |
| `retry-interval-ms` | 500 | 过载重试间隔 |
| `http-proxies` | — | HTTP 出站代理列表，借道访问官方 Mojang（验证结果可信） |
| `session-server-mirrors` | — | 会话验证镜像，直连备选（官方地址硬编码首位，勿用不可信第三方镜像） |
| `http-pool-size` | 2 | 正版验证异步线程池大小（2~64，阻塞式任务），高并发可调大；启动时构建，需重启生效 |
| `cache-cap` | 1000 | 离线确认/回退缓存上限 |
| `cracker-cache-seconds` | 120 | 离线确认标记 TTL（IP+名 → 直接离线登录） |
| `fallback.enabled` | false | 正版验证失败/离线启动器回退密码登录（仅已注册正版玩家，不安全） |
| `fallback.cache-seconds` | 300 | 回退标记 TTL（IP+名 → 跳过握手走密码回退） |
| `upgrade` | true | 是否允许 `/upgrade` 离线账号升级正版 |
| `downgrade` | false | 是否允许 `/downgrade` 正版账号降级离线（降级前须已设密码或绑定 2FA） |


# 九、同名冲突处理（仅正版验证视角）

**场景**：数据库中存在离线 UUID 记录（名字 = Steve），正版 Steve 尝试进入。

**处理**：
- 因阶段1先查内存 Map（先查离线 UUID 再查正版名），正版玩家在阶段1会被识别为"已注册离线玩家"（premium=0）。
- 此时不会触发 EncryptionRequest，正版玩家将直接以离线账号身份进服。

**唯一解决方案**：
管理员手动操作迁移数据（将离线 UUID 迁移至正版 UUID，或清理冲突记录）。


# 十、AI 响应约束

当讨论正版验证模块时，AI 必须遵守：

1. **禁止建议**：
   - 使用 BungeeCord / Velocity 或任何群组代理。
   - 修改 server.properties 的 online-mode=true。
   - 拦截 PacketSendEvent（onPacketSend + setCancelled）——会导致 PacketEvents 副作用。
   - 手动发送 LoginSuccess——应由服务端 tick() 自然发送。

2. **必须区分同步操作 vs 异步操作**，明确标注哪些代码运行在 IO 线程、异步线程、主线程（Folia 区域线程）。

3. **UUID 覆盖必须围绕"设置 authenticatedProfile + state=VERIFYING"展开**，让服务端 tick() 自然发送含正版 UUID 的 LoginSuccess。禁止手动发送 LoginSuccess 或使用 `event.setUniqueId()`。

4. **模块化**：实现时按第三节四模块组织，不得在模块外直接查库/调 Mojang/发包；跨模块仅经连接处理器编排。

5. **全程加密**：阶段2启用 AES 后，所有发包（含 Disconnect）必须经加密通道；Mojang 验证用 hasJoined + 服务器哈希，禁止用 `profile?token=`。

6. **登录接管**：验证完成后设置 `state=VERIFYING`，让服务端 `tick()` 自然调用 `verifyLoginAndFinishConnectionSetup` → 发送 LoginSuccess → `state=PROTOCOL_SWITCHING`；实体创建与 PlayerLoginEvent/PlayerJoinEvent 由服务端原生逻辑自动完成，插件不手动创建实体。

7. **serverHash 计算**：必须用 `new BigInteger(digest).toString(16)`（不带 signum=1），与 vanilla 一致。

8. **安全基线**：所有标记为 `premium=1` 的玩家每次登录必须执行 `hasJoined` 实时验证。不得提供任何跳过此验证的配置开关或代码路径。

9. **authlib 依赖**：GameProfile/Property 通过 compileOnly 依赖直接使用。authlib 9.0+ 需用 `ArrayListMultimap` + `PropertyMap(Multimap)` + `GameProfile` 三参构造。NMS 字段仍需反射（NMS 类不在编译期）。

10. **Mojang 限流与重试**：hasJoined 受 `verify-deadline-ms` 总时限约束；204 为确定结论零重试，仅 429/502/503/504 按 `max-retries`/`retry-interval-ms` 重试（默认 2 次、500ms 间隔），连接失败立即切换端点；端点熔断按指数退避（15s 起翻倍，封顶 5 分钟），冷却到期由后台探测确认恢复。

11. **PacketEvents 依赖**：通过反射加载 ConnectionHandler/PlayerInjector/InventoryPacketListener，避免 HTLogin 常量池引用 PacketEvents 类导致无前置时加载失败。
