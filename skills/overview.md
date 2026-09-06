# ============================================================
# HowToLogin 插件总览 Skill（Paper/Folia 登录插件·全功能索引）
# ============================================================
# 适用场景：AI 需要快速理解 HowToLogin 项目的功能全貌、模块架构、
#           核心逻辑与设计约束，以进行功能扩展、问题排查或代码修改
# 技术栈：Paper API 1.21、Folia、PacketEvents（可选）、PlaceholderAPI（可选）
# 数据存储：SQLite / MySQL（HikariCP 连接池）
# 开源协议：GPL-3.0
# ============================================================

# 一、项目定位

HowToLogin（简称 HTLogin）是一个基于 Paper API 的 Minecraft 登录插件，核心目标是在 `online-mode=false` 的服务端上提供完整的账号登录体系，同时支持通过 PacketEvents 实现 Mojang 正版验证。

**硬性约束**：
- 必须同时支持 Paper 与 Folia（所有调度使用统一调度器 API）
- 所有消息必须经 I18n 系统，语言文件位于 `lang/` 目录
- 玩家数据存储必须线程安全（ConcurrentHashMap + volatile + 异步落库）
- 配置与消息文件默认中文
- Paper 插件规范：命令通过 `LifecycleEvents.COMMANDS` 程序化注册，不使用 plugin.yml 声明
- 禁止 `htlogin.bypass` 权限，所有玩家必须登录
- 正版验证使用主世界出生点防止维度信息泄露


# 二、模块架构

主类 `HTLogin.java` 是编排入口，`onEnable` 初始化顺序固定：

```
HTLogin（编排入口）
├── I18n               国际化系统（最先初始化，ConfigManager 依赖它输出日志）
├── ConfigManager      配置加载 + 版本迁移 + 钳制校验
├── PlayerDataManager  数据存储（SQLite/MySQL + 内存缓存）
├── AuthManager        认证状态管理 + 坐标保护 + 暴力破解防护
├── command/*          命令系统（玩家命令 BasicCommand + 管理命令 brigadier）
├── PlayerListener     事件监听（登录前限制 + 坐标保护 + 超时踢出）
├── HTLoginExpansion   PlaceholderAPI 软依赖
├── InventoryPacketListener  背包保护（PacketEvents 反射加载）
└── premium/*          正版验证模块（PacketEvents 反射加载，详见 premium.md）
    ├── ConnectionHandler   编排中心 + 加密握手
    ├── DataService         内存仓储 + TTL 缓存标记
    ├── MojangClient        hasJoined 会话验证
    ├── PlayerInjector      authenticatedProfile 反射 + state 推进
    ├── SessionContext      单连接状态机
    └── CryptoHandler       AES-CFB8 Netty 处理器
```

**依赖加载策略**：
- PacketEvents 为硬性前置（paper-plugin.yml `required: true`，`load: BEFORE`）
- 依赖 PacketEvents 的类（ConnectionHandler/PlayerInjector/InventoryPacketListener）仍通过反射 `Class.forName` 加载，避免 HTLogin 常量池引用 PacketEvents 类触发类加载失败
- PlaceholderAPI 为可选软依赖（`required: false`），存在时注册变量扩展


# 三、核心登录功能（AuthManager）

## 3.1 注册 `/register <密码> <确认密码>`
- 校验链：未注册 → 密码一致 → 长度（4~128）→ 正则 → 哈希存储
- 哈希算法：BCrypt（默认，work factor=12，配置 `password.hash-cost`）或 SHA-256（`saltHex:hashHex` 格式）
- 注册成功后标记 `loggedIn`，触发 `onLoginSuccess`（updateInventory 刷新背包）
- 启用坐标保护时传送到上次退出位置

## 3.2 登录 `/login <密码>`
- 顺序：踢出期检查 → 已登录检查 → 账号存在 → 密码验证
- `PasswordHash.checkPassword` 自动识别存储格式（`$2` 开头为 BCrypt，否则 SHA-256）
- **自动算法迁移**：存储为 SHA-256 但配置为 bcrypt 时，登录成功后用 bcrypt 重新哈希
- 更新 lastLogin（秒级时间戳）+ ip，`markLoggedIn` 清理 failedAttempts/kickUntil
- 登录成功后 `returnToLogoutLocation`（标记 invulnerablePending 传送过渡期无敌）

## 3.3 改密 `/changepassword <旧密码> <新密码>`
- 已登录 → 旧密码校验 → 新密码强度 → 更新 hash
- **正版账号特殊逻辑**：`!data.premium()` 时才校验旧密码（正版玩家免密登录，密码为随机占位）

## 3.4 登出 `/logout`
- **先保存当前位置** → `logout()`（loggedIn→pendingLogin，清 lastLogin 使 IP 自动登录失效）→ 踢出

## 3.5 注销（玩家自助 `/unregister` + 管理员 `/htlogin unreg`）
**玩家自助注销**（`settings.allow-self-unregister` 控制，默认开启）两步执行：
1. 凭据验证（`AuthManager.verifyUnregisterCredentialsAsync`）：按账户持有情况组合——密码与 2FA 验证码都须通过（绑了 2FA 时密码也须正确）；无密码账户验证码即唯一凭据；**正版账户凭正版验证免验**（无需参数）
2. `/unregister confirm` 二次确认（60 秒窗口，`pendingConfirms` 映射，超时作废）；confirm 仅在有待确认请求时才作为指令，否则回落为密码参数（避免密码恰为 "confirm" 时无法发起注销）
- confirm 后 `unregister()` + kick（触发 PlayerQuitEvent 完成 .dat 删除）
- 无凭据账户（无密码+无 2FA+非正版）无法自助注销——身份不可验证，须走管理员通道

**管理员删除**（`/htlogin unreg <玩家>`，无别名）：以数据库记录解析（`findUuidByName`，避开 usercache 同名缓存），在线玩家踢出

**共同核心** `AuthManager.unregister(uuid)`：
- 删 DB 记录 + 清理所有内存状态（loggedIn/pendingLogin/pending2fa/2fa 防重放计数/failedAttempts/kickUntil/升级降级回退标记/登录会话/2FA 会话）
- `real-unreg=true` 时：
  - 5 秒拒绝重连（`recentUnregister`），确保 .dat 删除完成
  - 在线玩家：标记 `pendingDatDelete`，由 PlayerQuitEvent 触发删除（避免文件锁）
  - 离线玩家：直接异步重试删除
  - 删除范围：`.dat`、`.dat_old`、`advancements/.json`、`stats/.json`
  - 重试机制：首次 500ms，后续每 300ms，5 秒内持续尝试（约 15 次）
- 兼容 26.1+ 新世界结构（`players/data` 旧版 `playerdata`），通过 `Bukkit.getBukkitVersion()` 主版本号 >=26 检测

## 3.6 会话保持（免输密码）
- `hasSession(uuid, ip)`：storedIp==当前 IP 且未过期
- 过期时间 `login.session.expire-minutes`（0=永不失效），基于 lastLogin 计算
- 命中时 `onSpawnLocation` 直接在退出位置出生，跳过传送

## 3.7 暴力破解防护
- `failedAttempts`：`Map<UUID, long[]{count, lastFailTime}>`，**跨连接保留**（clearSession 不清）
- 超过 `reset-seconds` 未再失败则过期清空
- 容量守卫 `FAILED_ATTEMPTS_CAP=1000`，超限时清理未达阈值或已过期的条目
- 达 `max-attempts` → `kickUntil`（now + kickDuration*1000）
- 踢出期内 `AsyncPlayerPreLoginEvent` 拒绝进入，`isKicked` 懒清理已过期记录
- `cleanupExpiredStates` 由周期任务每分钟调用 + reload 时调用

## 3.8 管理员强制操作
- `forceLogout`：清 loggedIn + 清 lastLogin（IP 自动登录立即失效）
- `forceChangePassword` / `forceRemovePassword`：无需旧密码，清 lastLogin 强制下次密码登录；清空密码后账号转为无密码账户（凭验证器或正版验证登录）。两者共用 `invalidateLoginSessions(uuid)` 清除登录会话与 2FA 会话
- `forceLogin`：仅对在线玩家，markLoggedIn + onLoginSuccess
- `forceRegister`：绕过 IP 限制，不自动登录，玩家需自行 /login
- `reset2fa`：强制解除 2FA 绑定（玩家误删验证器密钥致账号锁死时的救济通道）
- 目标账号解析统一走 `HTLoginCommand.resolveTargetUuid(name)`：离线 UUID 无账号时按名字回溯正版 UUID（premium=1），避免对正版玩家误报"不存在"或建同名平行离线账号

## 3.9 无密码账户与 2FA 生命周期
- **无密码账户**：`/removepassword`（rmpw）移除密码转为无密码账户——离线账户须已绑定 2FA，移除时输入验证码确认（验证码成为唯一登录因素）；正版账户凭正版验证直接放行。`/addpassword`（addpw）为无密码账户设回密码（已有密码则拒绝）
- **2FA（TOTP）**：`/2fa setup` 生成临时密钥（`pending2faSecret`，可配置过期）→ `/2fa confirm <码>` 绑定持久化；登录时已绑定账户须验证码（`requires2faAtLogin`）；`/2fa <码>` 在线验证，`/2fa disable <码>` 解绑
- **防重放**：`used2faCounters` 按 TOTP 周期单调消费，同周期验证码拒绝（`verify2faCode` 入口 `pending2fa.remove` 保证单线程消费）；失败计数计入暴力破解防护
- **2FA 会话**：`mark2faSession(uuid, ip)` 验证成功后记录同 IP 短窗口免验证码（`login.2fa.session`），命中时 `has2faSession` 免码登录
- **无凭据账号**：`hasNoUsableLoginMethod(uuid)` = 无密码 + 未绑 2FA + 非正版，任何认证路径不可行。`protection.reject-no-auth-account`（默认 true）在连接阶段/配置阶段直接拒绝；关闭则放行但永远无法完成登录（挂起至超时踢出），管理员需 `/htlogin forceregister`（设密码）或绑定 2FA 恢复
- **提示文案**：挂起/提醒文案键统一走 `PlayerListener.authPromptKey`（注册→待 2FA→无密码无凭据细分→密码登录），无凭据玩家显示联系管理员而非空输验证码，并记录 WARN 日志（`log.passwordless_no_auth_account`）


# 四、登录前限制（PlayerListener）

所有事件 `EventPriority.LOWEST`，受 `prevent.*` 配置控制：

| 事件 | 限制 | 配置项 |
|------|------|--------|
| PlayerMoveEvent | 禁止位置移动；`prevent.look=true` 时连视角也禁止（用 setTo 而非 cancel） | prevent.move / prevent.look |
| AsyncChatEvent | 禁止聊天 | prevent.chat |
| PlayerCommandPreprocessEvent | 仅白名单命令（默认 login/l/register/reg）放行 | prevent.command.enabled / whitelist |
| BlockBreak/PlaceEvent | 禁止世界交互 | prevent.world-interaction |
| EntityDamageEvent | 未登录或传送过渡期不受伤害 | prevent.world-interaction |
| EntityTargetEvent | 怪物不锁定未登录玩家 | prevent.world-interaction |
| FoodLevelChangeEvent | 饥饿不变 | prevent.world-interaction |
| PlayerDropItem/PickupItem | 物品丢弃/拾取禁止 | prevent.world-interaction |
| PlayerInteractEvent | 交互禁止 | prevent.world-interaction |
| InventoryClick/Drag | 背包交互禁止 | prevent.inventory |
| PlayerPortalEvent | 传送门禁止 | prevent.world-interaction |
| PlayerItemConsumeEvent | 进食/喝药水禁止 | prevent.inventory |
| PlayerSwapHandItemsEvent | 副手切换禁止 | prevent.inventory |

## 4.1 坐标保护（protection.pos）
`AsyncPlayerSpawnLocationEvent` 处理（新玩家不干预，保留原版出生机制）：
- IP 免密玩家 → 直接在退出位置出生
- 启用 pos 保护 → `findSafeAuthSpawn`：
  - 固定坐标模式：直接返回配置坐标
  - 随机模式：在出生点 `spawn-radius` 内尝试 10 次找下方固体方块的位置
  - 全部失败：回退到世界出生点（玩家无敌期间不会受伤）
- 未启用但原位置悬空 → 用随机出生点（防反作弊误踢，不修正到正下方防逃避摔伤）

## 4.2 登录超时与提醒
- `scheduleLoginTimeout`：`markLoginTimeoutStart` 记录时间戳，超时任务触发时校验 `isLatestLoginTimeout`（forceRegister 重启后旧任务自动失效）
- `scheduleReminder`：周期重发登录/注册提示，玩家登录/注册/下线自动取消

## 4.3 PreLogin 拦截
`AsyncPlayerPreLoginEvent`（LOWEST）：
- 数据库加载失败（fail-closed）拒绝进入
- 踢出期内拒绝进入（KICK_BANNED）
- **无凭据账号**（`hasNoUsableLoginMethod`，`protection.reject-no-auth-account=true`）直接拒绝（KICK_OTHER，`prelogin.account_locked`）
- 注销后 5 秒内拒绝重连（KICK_OTHER）
- 同 IP 账号数超限拒绝（仅对新玩家检查，统计已注册 + 在线未注册）

## 4.5 配置阶段 Dialog（PreJoinAuthListener）
`AsyncPlayerConnectionConfigureEvent`（**早于** AsyncPlayerPreLoginEvent——dialog 场景下 onPreLogin 轮不到，无凭据拦截须在此先行处理）：
- 1.21.6+ 客户端进入世界前弹图形化窗口完成登录/注册/2FA（`login.dialog.enabled`，开通后无坐标泄露风险）
- 无凭据账号在配置阶段按 reject 开关先行 disconnect 或跳过弹窗放行（由 onJoin 挂起）
- 超时未完成：`kick-on-timeout` 开启则断连，否则回退 onJoin 聊天栏挂起

## 4.5 飞行末影珍珠保管（pearl 包 PendingPearlManager）
飞行珍珠落地会传送未登录玩家绕过位置保护。三种服务端行为不同（Paper 原版退出存 NBT/重入恢复；Folia 移除该机制珍珠留在世界；Canvas 核心层恢复——恢复实体在 join 之后才生成且不注册进 getEnderPearls，事件级接管看不见），所有入口收敛到**统一记账原语 absorb**，不做平台检测（`pearl.enabled` 开关，关闭后所有入口均不工作，`refresh()` 清空内存记录与 dat 文件内容——启动与 /htlogin reload 时调用）：
- **absorb(pearl, owner) 记账原语（幂等）**：珍珠状态与该玩家已记快照同值 → 视为已计数，仅移除世界副本（防双倍返还）；无同值快照 → 追加快照后移除。任何时序下同一颗珍珠只有一份账目
- **归属链**：shooter（投掷路径，内存 projectileSource 可解析）→ NMS getHandle 反射 getOwnerUUID（核心 NBT 恢复/区块重载路径，projectileSource 丢失但 Owner 持久在 NBT），均失败不接管
- 接管入口（均 MONITOR）：
  - `PlayerQuitEvent`：接管玩家飞行珍珠；同区域时同步移除抢在核心保存读列表之前，核心自然无珍珠可存，Paper 重入不再产生恢复副本。getEnderPearls 弱一致读失败（Folia CME）按无珍珠处理，漏读由 removed 接管补偿
  - `EntityRemoveFromWorldEvent`：珍珠实体被移除且 owner 未登录（离线或在线未登录）时接管，用销毁瞬间最终状态记账。**Folia 上的主接管路径**——Folia 无核心保存，玩家断开后服务端销毁其飞行珍珠，且珍珠飞远后 getEnderPearls 在 quit 事件时已读不到。本插件调度移除的珍珠由 handledPearls 集合标记跳过（防双计；标记仅在移除活珍珠时添加、removed 事件消费，不泄漏）
  - `EntityAddToWorldEvent`：**实体级拦截，时机无关的主动防线**。核心恢复的珍珠一进世界即接管（Canvas 的恢复珍珠晚于 join 出现、不注册进 getEnderPearls，事件级接管看不见；也覆盖 Folia 区块重载的退出残留）。归属玩家在线且已登录 → 不拦截（正常投掷/本插件 entity 模式返还的重生）
  - `returnPearls` 开头：再吸收一次，封住核心迟到恢复的窗口
- 兜底拦截 `PlayerTeleportEvent`（LOWEST，仅 Paper 有效——Folia/Canvas 不触发 ENDER_PEARL 的 PlayerTeleportEvent，Folia#490）：**保险丝，正常时序不触发**，只覆盖接管机制自身的失效窗口——未登录玩家被 ENDER_PEARL 传送 → 取消（防坐标保护被绕过）+ 按落点记账补偿（珍珠已消耗，速度不可知，记零速度合并进 pending）。entity 模式补偿在落点重生静止珍珠，下坠撞方块才触发传送（落点悬空时实际传送点略偏下）；item 模式按物品退款
- 快照存 `pearls.dat`（YamlConfiguration，uuid→快照列表）
- 返还（`AuthManager.onLoginSuccess` 统一入口调用 `returnPearls`，玩家区域线程），按 `pearl.return` 配置：
  - `item`：addItem 入包，溢出掉落地面，提示 pearl.returned
  - `entity`：区域调度器在快照位置按原速度重生珍珠（setShooter 关联玩家），不提示（原版行为延续）；世界已卸载时该珍珠退化为物品返还；重生珍珠若飞行中玩家再退出，由原版卸载/恢复机制接管，重入时再次被本模块接管（闭环）
- 先清记录再发放：崩溃窗口宁可少还不重复还
- **已知限制（Folia 引擎缺陷，实测确认）**：滞留珍珠（stasis 装置）退出后 Folia 不销毁也不保存，留在世界上——装置原生存活可继续使用，本插件不接管不返还；owner 未登录期间被其他玩家触发的传送无法拦截（Folia 不触发 PlayerTeleportEvent）；滞留珍珠所在区块卸载时 Folia 静默删除珍珠（不发实体事件），无法感知与补偿


# 五、正版验证（premium 模块）

**详细逻辑见 [`premium.md`](./premium.md)**，此处仅列要点：

- 通过 PacketEvents 拦截 LoginStart，自行发送 EncryptionRequest 完成 Mojang 加密握手
- 验证完成后反射设置 `authenticatedProfile` + `state=VERIFYING`，让服务端 tick() 自然接管发送 LoginSuccess
- `premium=1` 玩家**始终走正版验证**，不受 `premium.enabled` 开关影响（防丢账号）
- 支持离线账号升级正版（`/upgrade` 标记，下次登录验证成功则迁移数据）
- 支持正版账号降级离线（`/downgrade` 标记，LoginStart 阶段迁移数据到离线 UUID 后放行原生离线登录；降级前须有密码或 2FA）
- 支持正版验证失败回退（`fallback.enabled` 开启时，已注册正版玩家可密码登录；无密码账号是否回退由 `protection.reject-no-auth-account` 一并决定——开启时拒绝进入，关闭时可放行进入但仅能验 2FA）
- 两个 TTL 缓存标记：
  - `offlineConfirmed`（TTL=`cracker-cache-seconds`）：新玩家离线客户端重连走离线
  - `premiumFallbackConfirmed`（TTL=`fallback.cache-seconds`）：正版玩家回退密码登录
- 全程加密：阶段 2 启用 AES-CFB8 后所有发包必须经加密通道


# 六、背包保护（InventoryPacketListener）

数据包层物品保护：未登录期间拦截发给该玩家本人的物品信息包，清空为 `ItemStack.EMPTY`。

| 数据包 | 拦截范围 | 动作 |
|--------|----------|------|
| WINDOW_ITEMS | windowId=0（主背包） | 清所有槽位 + carried_item |
| SET_SLOT | windowId=0/-1/-2 | 清该槽位 |
| ENTITY_EQUIPMENT | entityId==接收者 | 清所有装备槽 |

**关键设计**：
- 仅保护本人视角，其他玩家看到的装备不受影响
- 通过 `event.getUser().getUUID()` 识别接收者，无需解析数据包内容
- `handleEntityEquipment` 通过比较 entityId 判断目标是否自己，避免遍历全服玩家
- 登录后 `onLoginSuccess` 调 `updateInventory` 刷新本人背包
- 监听器始终注册，是否拦截由 `prevent.inventory` 实时判断（支持热重载）


# 七、命令系统

## 7.1 玩家命令（BasicCommand，brigadier 命令树为 `*Command` 形式）

| 命令 | 别名 | 说明 |
|------|------|------|
| `/register <密码> <确认密码>` | `/reg` | 注册 |
| `/login <密码>` | `/l` | 登录 |
| `/changepassword <旧> <新>` | `/changepw`、`/cp` | 改密 |
| `/addpassword <新> <确认>` | `/addpw` | 无密码账户设回密码 |
| `/removepassword [验证码]` | `/removepw`、`/rmpw` | 移除密码转无密码账户（离线+2FA 须验证码） |
| `/logout` | — | 登出（保存位置后踢出） |
| `/unregister [密码] [验证码]` + `/unregister confirm` | — | 自助注销两步（开关 `settings.allow-self-unregister`；正版免验） |
| `/upgrade` | — | 标记离线账号待升级正版（下次登录验证） |
| `/downgrade` | — | 标记正版账号待降级离线（下次登录迁移数据） |
| `/2fa setup / confirm <码> / disable <码> / <码>` | `/totp ...` | TOTP 绑定/确认/解绑/登录验证（brigadier 子命令） |

## 7.2 管理命令（brigadier 原生注册，权限 `htlogin.admin`）

| 命令 | 说明 |
|------|------|
| `/htlogin reload` | 重载配置+语言文件，数据库配置变更需重启 |
| `/htlogin accounts <玩家>` | 查同 IP 所有账号 |
| `/htlogin forcelogout <玩家>` | 强制登出 |
| `/htlogin forcechangepw <玩家> <新密码>` | 强制改密 |
| `/htlogin forcermpw <玩家>` | 强制清空密码转无密码账户（无 2FA/正版时该玩家将无法登录） |
| `/htlogin forcelogin <玩家>` | 强制登录（仅在线玩家） |
| `/htlogin forceregister <玩家> <密码>` | 强制注册（不自动登录，在线玩家挂起待登录） |
| `/htlogin reset2fa <玩家>` | 强制解除 2FA 绑定 |
| `/htlogin unreg <玩家>` | 删除账号（无别名；`findUuidByName` 数据库解析） |

## 7.3 密码校验（PasswordValidator）
- `invalidLength`：4~128（钳制，min-len 默认 6，max-len 默认 32）
- `invalidPattern`：默认 `^[a-zA-Z0-9!@#$%^&*()\-_=+\[\]{}|;:,.<>?]+$`，留空不限制
- 采用 `preventXxx()` 模式，校验失败自动发送消息并返回 true（调用方 `if (!preventXxx()) return;`）

## 7.4 Tab 补全
- `SUGGEST_PLAYERS`：仅在线玩家，用于 forcelogin/forceregister
- `suggestAllPlayers`：在线玩家 + 已注册离线玩家（遍历 `getAllUuids`），用于 accounts/forcelogout/forcechangepw/forcermpw/reset2fa/unreg


# 八、数据存储（PlayerDataManager）

## 8.1 表结构（players）

| 字段 | 类型 | 含义 |
|------|------|------|
| uuid | VARCHAR(36) PK | 离线=离线UUID，正版=正版UUID |
| name | VARCHAR(16) | 仅正版玩家存储（用于 getByName） |
| password_hash | VARCHAR(255) | BCrypt `$2a$10$...` 或 SHA-256 `saltHex:hashHex` |
| ip | VARCHAR(45) | 最后登录 IP（IPv6 最长 45） |
| last_login | BIGINT | 秒级时间戳（0=失效，IP 免密立即可用） |
| logout_location | TEXT | `world:x:y:z:yaw:pitch` |
| premium | BOOLEAN | 0=离线，1=正版 |
| properties | TEXT | Mojang 皮肤 JSON |

## 8.2 线程安全
- `ConcurrentHashMap` 缓存（players + premiumNameIndex）
- `PlayerData` 所有字段 volatile
- 写操作异步落库（`AsyncScheduler.runNow`）
- SQLite **强制单连接**（`setMaximumPoolSize(1)`）避免文件锁
- MySQL 用 HikariCP，`pool-size` 1~128
- `onDisable` 用 `saveSync` 事务批量提交

## 8.3 核心方法
- `createPlayer` / `createPremiumPlayer`（生成 16 位随机密码占位）
- `markPremium` / `migrateToPremium`（保留退出位置等数据）
- `getByName`（仅返回 premium=1，用于正版验证 LoginStart 阶段）
- `findByIp`（管理员排查多账号）
- `hasPremiumPlayers`（PacketEvents 缺失时告警）

## 8.4 SQL 语句
- `SQL_UPSERT`：`REPLACE INTO`（主键存在先 DELETE 再 INSERT）
- `SQL_DELETE` / `SQL_UPDATE_PASSWORD` / `SQL_UPDATE_PREMIUM`
- 启动时 `addColumnIfMissing` 安全添加 name/premium/properties 列


# 九、配置系统（ConfigManager）

## 9.1 版本迁移
- `major.minor` 变化（如 1.0→1.1）：覆盖 config.yml + 所有语言文件
- 仅 `patch` 变化（如 1.0.0→1.0.1）：不覆盖 config.yml，仅更新 version 字段，覆盖语言文件

## 9.2 配置项分区

| 区段 | 说明 |
|------|------|
| `database` | type(sqlite/mysql)、mysql 连接信息、pool-size(1~128) |
| `login` | timeout、kick-on-timeout、fail-protection(max-attempts/kick-duration/reset-seconds)、remind-interval(5s)/remind-method(chat/title/actionbar/bossbar)、dialog(enabled/allow-risky-versions)、ip-change-notify、session(enabled/expire-minutes)、2fa(enabled/expire-seconds/session/qr/qr-url/server-name) |
| `password` | min-length(≥4)、max-length(≤128)、hash(bcrypt/sha256)、hash-cost(10~31，默认 12)、pattern |
| `register` | max-accounts-per-ip(limit，0=不限；reject-join 连接阶段拦截开关) |
| `protection` | pos(enabled/mode/spawn-radius/fixed)、gamemode、blindness、reject-no-auth-account、inventory、prevent(move/look/chat/command/world-interaction/inventory) |
| `pearl` | enabled、return(item/entity) |
| `messages` | join(disabled/hide-unauthenticated/delay-until-authenticated/template)、quit(disabled/hide-unauthenticated/template) |
| `settings` | default-language(zh_CN)、i18n、real-unreg、allow-self-unregister、purge(enabled/days) |
| `premium` | enabled、auto-verify、timeout-seconds、http-proxies、session-server-mirrors、verify-deadline-ms、cracker-cache-seconds、handshake-timeout-ms、max-retries、retry-interval-ms、http-pool-size(2~64)、cache-cap、upgrade、downgrade、fallback(enabled/cache-seconds) |

## 9.3 钳制机制
- `clampInt(key, value, min)`：低于下限调整到下限并告警
- `clampRange(key, value, min, max)`：超出区间钳制到边界并告警
- 非法枚举值（database.type、password.hash、protection.pos.mode）回退安全默认值

## 9.4 reload 机制
- `reload()` 返回 `boolean`，通过数据库配置指纹（`type|host|port|database|username|password|poolSize`）判断是否需要重启


# 十、国际化（I18n）

- 内置 `zh_CN` / `en_US`，支持外部 `lang/<locale>.properties` 扩展
- **三引号多行值**：`key="""` 开始，单独 `"""` 行结束，物理换行保留
- `MessageFormat` 占位符 `{0}{1}...`
- 玩家语言：`player.locale()`（如 `zh_CN`），`settings.i18n=false` 时全部用默认语言
- 回退：精确 locale → defaultLocale → key 本身
- `bundles` volatile 整体替换，reload 线程安全
- 编码 UTF-8（`InputStreamReader` 显式指定）


# 十一、PlaceholderAPI 变量（HTLoginExpansion）

| 变量 | 返回 |
|------|------|
| `%htlogin_is_logged_in%` | yes / no |
| `%htlogin_is_registered%` | yes / no |
| `%htlogin_has_account%` | yes / no |


# 十二、生命周期

## 12.1 onEnable 顺序
1. `I18n.init`（最先，ConfigManager 依赖它）
2. `ConfigManager`（版本检查 + 钳制）
3. `PlayerDataManager`（建表 + 全量加载）
4. `AuthManager`（检测世界结构 26.1+）
5. `registerCommands`（LifecycleEvents.COMMANDS）
6. `registerListeners`（PlayerListener）
7. `hookPlaceholderAPI`（软依赖）
8. `registerPacketListener`（背包保护，反射加载）
9. `registerPremiumListener`（正版验证，反射加载）
10. `rePendOnlinePlayers`（处理 /reload 后在线玩家状态丢失）

## 12.2 onDisable 顺序
1. 保存所有在线已登录玩家位置到缓存（`updateLogoutLocationCache`）
2. `playerDataManager.saveSync`（事务批量落库）
3. 关闭线程池（mojangClient / playerInjector）
4. 取消所有调度任务（GlobalRegionScheduler + AsyncScheduler）
5. 输出日志 + `I18n.shutdown`（清理静态状态）

**关服保存说明**：stop 关服时 PlayerQuitEvent 可能不触发或时序不确定，显式保存确保位置不丢失。用 `updateLogoutLocationCache` 只更新内存缓存，由后续 `saveSync` 统一落库（插件禁用后无法注册异步保存任务）。


# 十三、设计约束与原则

## 13.1 Folia 兼容
- 所有调度使用 Paper 统一调度器 API：
  - `Bukkit.getAsyncScheduler()` 异步任务
  - `Bukkit.getGlobalRegionScheduler()` 全局区域任务
  - `player.getScheduler()` 玩家区域任务（Folia 下在玩家所在区域线程执行）
- 禁止同步 teleport（Folia 禁止），用 `teleportAsync`
- `paper-plugin.yml` 配置 `folia-supported: true`

## 13.2 线程安全
- 跨线程数据为不可变对象（`final` 字段）
- 共享集合用 `ConcurrentHashMap` / `ConcurrentHashMap.newKeySet()`
- 共享对象字段用 `volatile`
- 状态机用 `AtomicReference` + CAS
- 数据库写操作异步落库

## 13.3 PacketEvents 依赖隔离
- ConnectionHandler / PlayerInjector / InventoryPacketListener 继承 PacketEvents 类
- 通过反射 `Class.forName` 加载，避免 HTLogin 常量池引用 PacketEvents 类
- `registerPacketEventsListener` 辅助方法处理反射注册
- 监听器始终注册，是否拦截由配置实时判断（支持热重载）

## 13.4 命令注册
- Paper 插件规范：通过 `LifecycleEvents.COMMANDS` 程序化注册
- 玩家命令用 `BasicCommand`，管理命令用 brigadier 原生注册（支持 Tab 补全）
- `htlogin` 主命令子命令作为 literal 节点，客户端输入空格后自动显示子命令列表

## 13.5 资源配置钳制
- port、pool sizes 等数值配置必须用范围钳制（min/max 显式边界）
- 防止配置错误导致 OOM 或连接失败

## 13.6 日志规范
- 移除调试日志（step logs、参数详情、重复状态日志）
- 保留必要 INFO（验证开始）、WARNING（Mojang hasJoined 问题）、SEVERE（critical 错误）
- 日志使用 i18n，i18n 系统自身报错时用硬编码


# 十四、关键文件索引

| 文件 | 职责 |
|------|------|
| `HTLogin.java` | 主类，编排入口 |
| `I18n.java` | 国际化系统 |
| `PluginLoaderImpl.java` | Paper 现代插件加载器 |
| `auth/AuthManager.java` | 认证状态 + 坐标保护 + 暴力破解防护 |
| `auth/PasswordHash.java` | BCrypt/SHA-256 哈希 + 自动迁移 |
| `command/*` | 命令系统 |
| `command/PasswordValidator.java` | 密码强度校验 |
| `config/ConfigManager.java` | 配置加载 + 版本迁移 + 钳制 |
| `data/PlayerDataManager.java` | SQLite/MySQL 存储 + 内存缓存 |
| `listener/PlayerListener.java` | 事件监听（登录前限制） |
| `packet/InventoryPacketListener.java` | 背包保护 |
| `premium/ConnectionHandler.java` | 正版验证编排中心 |
| `premium/DataService.java` | 内存仓储 + TTL 缓存 |
| `premium/MojangClient.java` | hasJoined HTTP 验证 |
| `premium/PlayerInjector.java` | authenticatedProfile 反射 |
| `premium/SessionContext.java` | 单连接状态机 |
| `premium/CryptoHandler.java` | AES-CFB8 加密处理器 |
| `hook/HTLoginExpansion.java` | PlaceholderAPI 变量 |
| `resources/config.yml` | 配置文件 |
| `resources/lang/zh_CN.properties` | 中文语言文件 |
| `resources/lang/en_US.properties` | 英文语言文件 |
| `resources/paper-plugin.yml` | 插件元数据 |


# 十五、AI 响应约束

当讨论 HowToLogin 项目时，AI 必须遵守：

1. **Folia 兼容**：所有调度必须使用 Paper 统一调度器 API，禁止同步 teleport，禁止假设主线程。

2. **线程安全**：跨线程数据必须不可变；共享集合用 ConcurrentHashMap；共享字段用 volatile；数据库写操作必须异步。

3. **PacketEvents 隔离**：依赖 PacketEvents 的类必须反射加载，禁止 HTLogin 常量池直接引用 PacketEvents 类。

4. **命令注册**：必须通过 `LifecycleEvents.COMMANDS` 程序化注册，禁止使用 plugin.yml 声明命令。

5. **I18n 强制**：所有玩家可见消息必须经 I18n 系统，禁止硬编码消息文本（日志除外）。

6. **数据存储**：必须使用 SQLite/MySQL，禁止 JSON 文件存储；SQLite 强制单连接；写操作异步落库。

7. **安全基线**：
   - 禁止 `htlogin.bypass` 权限
   - `premium=1` 玩家始终走正版验证，不受 `premium.enabled` 开关影响
   - 暴力破解失败计数跨连接保留，clearSession 不清

8. **正版验证**：详见 [`premium.md`](./premium.md)，不得违背其中的 AI 响应约束。

9. **配置钳制**：数值配置必须有范围钳制（min/max 显式边界），防止 OOM。

10. **日志规范**：移除调试日志，保留必要 INFO/WARNING/SEVERE，日志用 i18n。

11. **世界结构兼容**：处理玩家数据文件时必须兼容 26.1+ 新结构（`players/data` 旧版 `playerdata`），通过 `Bukkit.getBukkitVersion()` 主版本号 >=26 检测。

12. **代码风格**：
    - 不添加冗余文件（如 package-info.java）
    - 不添加单次使用的 helper 方法，倾向内联
    - properties 文件倾向直接换行而非 `\n` 转义
    - 坐标相关命名用 `pos` 而非 `location`
    - 日志简化，仅保留必要信息
    - 正版验证代码与其他代码分离，便于维护
