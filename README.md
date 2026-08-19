<div align="center">

# HowToLogin

简称`HTLogin`  
一个 Minecraft 登录插件，使用 PaperAPI，支持 Folia.

</div>

> [!important]
> 部分代码来自生成式 AI 且**没有**经过严格审核，仅做了必要测试。若插件出现问题，请第一时间提交 issue.  
> 项目库中有时会包含实现不完整的功能，请尽量不要自行构建。

## 版本支持
- 使用的 PaperAPI 版本：1.21.  
- 经过测试的服务端：1.21.11，26.1.x，包括 Paper 与 Folia.

## 下载
- **GitHub Releases**
  - [最新正式版（Latest Release）]()
  - [最新预览版（Latest Pre-release）]()
<!-- TODO: 添加下载链接 -->

## 依赖

| 插件                                                                      | 必需 | 说明                           |
|---------------------------------------------------------------------------|------|--------------------------------|
| [PacketEvents](https://github.com/retrooper/packetevents)                 | 是   | 正版验证与背包保护的数据包拦截 |
| [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) | 否   | 提供登录状态变量               |


## 功能简介

### 常规功能：
- **注册** / **登录** / **登出** / **改密** / **删除账号**
- **密码加密存储**：`BCrypt`（默认）或 `SHA-256`，登录时会自动迁移到配置的算法
- **暴力破解防护**：连续失败超过阈值后踢出，失败计数跨连接保留
- **同 IP 限时免密登录**：上次登录 IP 一致且未过期时自动登录
- **登录前限制**：禁止移动、转视角、聊天、执行非白名单命令、与世界交互、物品交互

### 高级功能：
- **坐标保护**：玩家加入前修改出生点，支持随机坐标和固定坐标两种模式，登录后传送回退出位置
  > [!warning]
  > 坐标保护功能在极端情况下会导致玩家上次退出的位置丢失，详见 [坐标保护]() (wiki 还没写)。
<!-- TODO: 添加 Wiki -->
- **未登录旁观模式**：未登录期间切换为旁观模式，登录后恢复上次游戏模式（新玩家使用服务器默认游戏模式）；退出位置悬空且坐标保护未开启时强制旁观，防止坠落暴露位置
- **背包保护**：使用 `PacketEvents` 拦截数据包，防止未登录玩家查看自己的背包内容
- **正版验证**：在离线服务端上通过 `PacketEvents` 验证正版玩家，同时使用正版 UUID
- **离线账号升级为正版**：执行 `/upgrade` 后下次进服尝试正版验证，成功则迁移账号为正版（保留数据），失败自动回退
- **离线可登录正版**：在正版验证失败时，允许已注册正版玩家以密码登录
  > [!caution]
  > 正版验证正处于实验阶段。  
  > **离线可登录正版**功能并不安全，因为它绕过了正版验证。  
  > 虽然做了离线、正版数据迁移，但仍然存在丢失风险。请不要频繁开关正版验证功能。
- **国际化（I18n）**：根据客户端语言自动匹配消息，目前内置中英文
- **数据库**：支持 SQLite 与 MySQL 存储数据（HikariCP 连接池）

## 命令

### 玩家命令

| 命令                                | 别名               | 说明                                       |
|-------------------------------------|--------------------|--------------------------------------------|
| `/login <密码>`                     | `/l`               | 登录                                       |
| `/register <密码> <确认密码>`       | `/reg`             | 注册                                       |
| `/changepassword <旧密码> <新密码>` | `/changepw`、`/cp` | 修改密码                                   |
| `/logout`                           | —                  | 登出                                       |
| `/upgrade`                          | —                  | 将离线账号升级为正版账号（需开启升级功能） |

### 管理员命令（权限 `htlogin.admin`）

| 命令                                     | 说明                                         |
|------------------------------------------|----------------------------------------------|
| `/htlogin reload`                        | 重载配置和语言文件（数据库配置变更时需重启） |
| `/htlogin accounts <玩家>`               | 查询该玩家 IP 下的其它账号                   |
| `/htlogin forcelogout <玩家>`            | 强制登出                                     |
| `/htlogin forcechangepw <玩家> <新密码>` | 强制改密                                     |
| `/htlogin forcelogin <玩家>`             | 强制登录                                     |
| `/htlogin forceregister <玩家> <密码>`   | 强制注册                                     |
| `/unregister <玩家>`                     | 删除账号                                     |

## 配置

详见 [`config.yml`](src/main/resources/config.yml)。

常用开关速查：

| 配置项                         | 默认     | 说明                                               |
|--------------------------------|----------|----------------------------------------------------|
| `database.type`                | `sqlite` | 数据库类型（`sqlite` / `mysql`）                   |
| `protection.pos.enabled`       | `false`  | 坐标保护                                           |
| `protection.gamemode.enabled`  | `false`  | 未登录旁观模式                                     |
| `protection.inventory.enabled` | `false`  | 背包保护（数据包拦截）                             |
| `premium.enabled`              | `false`  | 正版验证总开关                                     |
| `premium.auto-verify`          | `true`   | 新玩家自动正版验证（关闭后仅 `/upgrade` 玩家验证） |
| `premium.upgrade.enabled`      | `false`  | 离线账号升级为正版                                 |
| `premium.fallback.enabled`     | `false`  | 正版验证失败回退密码登录（不安全）                 |

## 开发者 API

通过 `HTLoginApi.getInstance()` 获取 API 实例（插件未加载时返回 `null`）：

```java
HTLoginApi api = HTLoginApi.getInstance();
if (api != null && api.isAuthenticated(player)) {
    // 玩家已登录
}
```

主要能力：

- **状态查询**：`isAuthenticated` / `isRegistered` / `isPremium`
- **强制操作**：`forceLogin` / `forceLogout` / `forceRegister` / `unregister`
- **玩家信息**：`getLastLocation` / `getLastIp` / `getLastLoginTime` / `getRegisteredUuids`
- **密码操作**：`checkPassword` / `changePassword`

事件（`org.howtologin.plugin.api.event` 包）：

| 事件                     | 触发时机                                               |
|--------------------------|--------------------------------------------------------|
| `HTLoginLoginEvent`      | 登录成功（密码 / IP 免密 / 正版免密 / 强制登录）       |
| `HTLoginRegisterEvent`   | 注册成功（强制注册离线玩家时 `getPlayer()` 为 `null`） |
| `HTLoginLogoutEvent`     | 登出 / 强制登出                                        |
| `HTLoginUnregisterEvent` | 删除账号                                               |
| `HTLoginLoginFailEvent`  | 登录失败（密码错误）                                   |

## PlaceholderAPI 变量

安装 PlaceholderAPI 后可用：

| 变量                      | 说明                       |
|---------------------------|----------------------------|
| `%htlogin_is_logged_in%`  | 是否已登录（`yes` / `no`） |
| `%htlogin_is_registered%` | 是否已注册（`yes` / `no`） |

## 语言文件

插件目录下 `lang/` 文件夹存放语言文件（如 `zh_CN.properties`、`en_US.properties`）。  
添加 `<语言>.properties` 即可支持更多语言。

## 开源协议

[GPL-3.0](LICENSE)