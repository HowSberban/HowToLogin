# HTLogin

一个 Minecraft 登录插件，支持 Paper 与 Folia 服务器。

## 功能

- 注册 / 登录 / 登出 / 改密 / 删除账号
- 密码加密存储（SHA-256 + 随机盐值）
- 暴力破解防护：连续失败锁定账号
- 登录前限制：禁止移动、聊天、执行命令、破坏方块等
- 坐标保护：通过 `AsyncPlayerSpawnLocationEvent` 在玩家加入前修改出生点，防止真实坐标泄露给客户端
- 支持 SQLite 与 MySQL 存储数据（HikariCP 连接池）

## 命令

| 命令 | 别名 | 说明 |
| --- | --- | --- |
| `/login <密码>` | `/l` | 登录 |
| `/register <密码> <确认密码>` | `/reg` | 注册 |
| `/changepassword <旧密码> <新密码>` | `/changepw`、`/cp` | 改密 |
| `/logout` | — | 登出 |
| `/unregister <玩家>` | — | 删除账号（管理员） |
| `/htlogin reload` | — | 重载配置（管理员） |

## 配置

详见 [`config.yml`](src/main/resources/config.yml)。

## 开源协议

[GPL-3.0](LICENSE)
