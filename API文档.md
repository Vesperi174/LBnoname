# 后端 API 文档 — LB三国杀

> 本文档供前端工程师阅读，描述服务端提供的所有接口。

---

## 目录

1. [基本信息](#1-基本信息)
2. [WebSocket 连接](#2-websocket-连接)
3. [客户端 → 服务端消息（请求）](#3-客户端--服务端消息请求)
4. [服务端 → 客户端消息（推送）](#4-服务端--客户端消息推送)
5. [数据模型](#5-数据模型)
6. [游戏流程](#6-游戏流程)
7. [房间设置说明](#7-房间设置说明)

---

## 1. 基本信息

| 项目 | 说明 |
|------|------|
| **服务地址** | `https://localhost:8443` |
| **WebSocket 地址** | `wss://localhost:8443/ws/game` |
| **数据格式** | 全部 JSON（UTF-8） |
| **身份校验** | 无（个人/局域网场景，直接信任客户端） |

> 本项目几乎完全基于 WebSocket 通信，REST 接口极少。

---

## 2. WebSocket 连接

### 2.1 连接地址

```
wss://localhost:8443/ws/game
```

### 2.2 连接参数（URL Query）

| 参数名 | 类型 | 必需 | 说明 |
|--------|------|------|------|
| `name` | string | 否 | 玩家名称，默认值 `"无名客"` |
| `avatar` | string | 否 | 头像 URL（预留字段） |

**示例：**

```
wss://localhost:8443/ws/game?name=张三&avatar=https://example.com/avatar.png
```

### 2.3 连接成功响应

连接建立后，服务端立即返回：

```json
{
  "type": "CONNECTED",
  "playerId": "uuid-string",
  "playerName": "张三"
}
```

前端应保存 `playerId`，后续所有操作中用它标识玩家身份。

### 2.4 心跳

客户端应周期性发送心跳以保持连接。建议间隔 **15 秒**。

---

## 3. 客户端 → 服务端消息（请求）

所有消息均为 JSON 格式，必须包含 `type` 字段。下面按 `type` 分类说明。

### 3.1 心跳

```json
{
  "type": "HEARTBEAT"
}
```

**响应：** `HEARTBEAT_ACK`

---

### 3.2 创建房间

```json
{
  "type": "CREATE_ROOM",
  "roomName": "张三的房间",
  "maxPlayers": 8
}
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `roomName` | string | 否 | 房间名，默认 `"{玩家名}的房间"` |
| `maxPlayers` | number | 否 | 最大玩家数（2~8），默认 `8` |

**响应：** `ROOM_CREATED`

---

### 3.3 加入房间

```json
{
  "type": "JOIN_ROOM",
  "roomId": "uuid-string"
}
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `roomId` | string | 是 | 要加入的房间 ID |

**响应：** `ROOM_JOINED` / `ERROR`

---

### 3.4 离开房间

```json
{
  "type": "LEAVE_ROOM"
}
```

> 如果游戏进行中离开，该玩家会被 **机器人（Bot）接管**。

**响应：** `ROOM_LEFT`

---

### 3.5 查询房间列表

```json
{
  "type": "ROOM_LIST"
}
```

**响应：** `ROOM_LIST`（全量房间列表）

---

### 3.6 准备/取消准备

```json
{
  "type": "PLAYER_READY",
  "ready": true
}
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `ready` | boolean | 否 | `true` = 准备，`false` = 取消，默认 `true` |

**响应：** `ROOM_UPDATE`（房间内全员广播）

---

### 3.7 开始游戏（房主操作）

```json
{
  "type": "START_GAME"
}
```

> 只有房主可发送。空座位会自动用 Bot 填充。
> 身份配置从房间设置 `roomSettings.doubleIntruder` 读取。

**响应：** `GAME_START` + `YOUR_PRIVATE_INFO` + `MY_HAND` + `TURN_START`

---

### 3.8 开始单机游戏

```json
{
  "type": "START_SINGLE_PLAYER",
  "totalPlayers": 8,
  "identityConfig": "standard"
}
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `totalPlayers` | number | 否 | 总人数（含玩家自己），默认 `8` |
| `identityConfig` | string | 否 | 身份配置：`"standard"`（标准）或 `"double_intruder"`（双内奸），默认 `"standard"` |

> 自动创建房间、填充 Bot、分配身份、开始游戏，一步完成。

---

### 3.9 推进下一阶段（当前行动玩家操作）

```json
{
  "type": "NEXT_PHASE"
}
```

> 只有**当前行动玩家**可以发送。
>
> 阶段推进顺序：`PREPARE → JUDGE → DRAW → PLAY → DISCARD → END`
> - 到达 `DISCARD` 时自动进入 `END`
> - 到达 `END` 时自动切换到下一名玩家的 `PREPARE` 阶段

**响应：** `PHASE_CHANGE` + 可能的 `TURN_START`

---

### 3.10 查询在线玩家列表

```json
{
  "type": "LIST_ONLINE_PLAYERS"
}
```

**响应：** `ONLINE_PLAYERS`

---

### 3.11 关闭空座位（房主操作）

```json
{
  "type": "CLOSE_SEAT"
}
```

> 至少保留 2 个座位，且不能少于当前玩家人数。

**响应：** `ROOM_UPDATE`

---

### 3.12 打开已关闭的座位（房主操作）

```json
{
  "type": "OPEN_SEAT"
}
```

> 最多 8 个座位。

**响应：** `ROOM_UPDATE`

---

### 3.13 更新房间设置（房主操作）

```json
{
  "type": "UPDATE_ROOM_SETTINGS",
  "settings": {
    "doubleIntruder": true,
    "turnTime": 20
  }
}
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `settings` | object | 是 | 要更新的设置键值对（增量合并） |

**响应：** `ROOM_UPDATE`（广播） + `ROOM_SETTINGS_UPDATED`（仅发送者）

详细字段见 [7. 房间设置说明](#7-房间设置说明)。

---

### 3.14 出牌

```json
{
  "type": "PLAY_CARD",
  "cardInstanceId": 12345,
  "targetIds": ["playerId1", "playerId2"],
  "cardName": "杀",
  "cardDefId": "sha",
  "suit": "SPADE",
  "point": 10
}
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `cardInstanceId` | number | 是 | 卡牌实例 ID |
| `targetIds` | string[] | 否 | 目标玩家 ID 列表 |
| `cardName` | string | 否 | 卡牌名称（用于广播展示，前端传递即可） |
| `cardDefId` | string | 否 | 卡牌定义 ID |
| `suit` | string | 否 | 花色 |
| `point` | number | 否 | 点数 |

> `cardName` / `cardDefId` / `suit` / `point` 不是后端校验字段，仅用于广播给其他人展示出牌动画/文字。

**响应：** `PLAY_ACTION`（广播） + `PLAYER_UPDATE`（广播） + `MY_HAND`（仅出牌者）

---

### 3.15 消息类型汇总

| type | 发送者 | 说明 |
|------|--------|------|
| `HEARTBEAT` | 任意 | 心跳 |
| `CREATE_ROOM` | 任意 | 创建房间 |
| `JOIN_ROOM` | 任意 | 加入房间 |
| `LEAVE_ROOM` | 任意 | 离开房间 |
| `ROOM_LIST` | 任意 | 查询房间列表 |
| `PLAYER_READY` | 房间内 | 准备/取消准备 |
| `START_GAME` | 房主 | 开始游戏 |
| `START_SINGLE_PLAYER` | 任意 | 开始单机游戏 |
| `NEXT_PHASE` | 当前行动玩家 | 推进下一阶段 |
| `LIST_ONLINE_PLAYERS` | 任意 | 查询在线玩家 |
| `CLOSE_SEAT` | 房主 | 关闭空座位 |
| `OPEN_SEAT` | 房主 | 打开座位 |
| `UPDATE_ROOM_SETTINGS` | 房主 | 更新房间设置 |
| `PLAY_CARD` | 当前行动玩家 | 出牌 |

---

## 4. 服务端 → 客户端消息（推送）

### 4.1 连接成功

```json
{
  "type": "CONNECTED",
  "playerId": "uuid-string",
  "playerName": "张三"
}
```

| 字段 | 说明 |
|------|------|
| `playerId` | 玩家唯一 ID（UUID），前端应保存 |
| `playerName` | 玩家名称 |

---

### 4.2 心跳响应

```json
{
  "type": "HEARTBEAT_ACK"
}
```

---

### 4.3 房间创建成功

```json
{
  "type": "ROOM_CREATED",
  "room": { "...房间信息..." }
}
```

---

### 4.4 加入房间成功

```json
{
  "type": "ROOM_JOINED",
  "room": { "...房间信息..." }
}
```

---

### 4.5 离开房间成功

```json
{
  "type": "ROOM_LEFT",
  "roomId": "uuid-string"
}
```

---

### 4.6 房间信息更新（房间内广播）

```json
{
  "type": "ROOM_UPDATE",
  "room": { "...房间信息..." }
}
```

---

### 4.7 房间列表（大厅广播/单人推送）

```json
{
  "type": "ROOM_LIST",
  "rooms": [ { "...房间信息..." }, ... ]
}
```

> 在连接成功、房间创建/销毁、玩家加入/离开房间时都会广播。

---

### 4.8 玩家加入房间通知（房间内广播）

```json
{
  "type": "PLAYER_JOINED",
  "playerId": "uuid-string",
  "playerName": "张三"
}
```

---

### 4.9 玩家离开房间通知（房间内广播）

```json
{
  "type": "PLAYER_LEFT",
  "playerId": "uuid-string",
  "playerName": "张三"
}
```

> 如果离开者转为 Bot（游戏中断线或主动离开），额外包含 `"bot": true`。

---

### 4.10 房主变更

```json
{
  "type": "OWNER_CHANGED",
  "newOwnerId": "uuid-string",
  "newOwnerName": "李四"
}
```

> 房主离开房间时触发。

---

### 4.11 房间关闭

```json
{
  "type": "ROOM_CLOSED",
  "roomId": "uuid-string",
  "message": "所有玩家已离开，房间已销毁"
}
```

---

### 4.12 游戏开始（房间内广播）

```json
{
  "type": "GAME_START",
  "roomId": "uuid-string",
  "players": [
    {
      "playerId": "uuid",
      "playerName": "张三",
      "gameSeat": 0,
      "maxHp": 4,
      "currentHp": 4,
      "handCardCount": 4,
      "kingdom": "wei",
      "bot": false
    }
  ],
  "currentPlayerIndex": 0,
  "currentPhase": "PREPARE",
  "round": 1,
  "totalTurns": 0,
  "turnTime": 15
}
```

> `kingdom` 为势力代码：`wei`（魏）、`shu`（蜀）、`wu`（吴）、`qun`（群）、`shen`（神）。
> `turnTime` 为出牌倒计时秒数，从房间设置中读取。

---

### 4.13 玩家私人信息（仅发送给对应玩家）

```json
{
  "type": "YOUR_PRIVATE_INFO",
  "playerId": "uuid-string",
  "role": "LORD",
  "gameSeat": 0,
  "handCardCount": 4
}
```

| 字段 | 说明 |
|------|------|
| `role` | 身份：`LORD`（主公）、`MINION`（忠臣）、`REBEL`（反贼）、`INTRUDER`（内奸） |
| `gameSeat` | 游戏座位号（0 起） |

---

### 4.14 我的手牌（仅发送给对应玩家）

```json
{
  "type": "MY_HAND",
  "cards": [
    {
      "instanceId": 10001,
      "defId": "sha",
      "name": "杀",
      "suit": "SPADE",
      "point": 10
    }
  ]
}
```

| 卡片字段 | 说明 |
|----------|------|
| `instanceId` | 卡牌实例 ID（出牌时需用它） |
| `defId` | 卡牌定义 ID |
| `name` | 卡牌显示名称 |
| `suit` | 花色：`SPADE`（黑桃）、`HEART`（红桃）、`CLUB`（梅花）、`DIAMOND`（方块） |
| `point` | 点数（1~13，1 为 A，11~13 为 J/Q/K） |

> 游戏开始、摸牌、出牌后都会推送更新。

---

### 4.15 回合开始（房间内广播）

```json
{
  "type": "TURN_START",
  "roomId": "uuid-string",
  "gameSeat": 0,
  "playerName": "张三",
  "round": 1,
  "totalTurns": 0,
  "phase": "PREPARE"
}
```

> 新回合开始或阶段推进到 `END` 自动切回合时触发。

---

### 4.16 阶段变更（房间内广播）

```json
{
  "type": "PHASE_CHANGE",
  "roomId": "uuid-string",
  "fromPhase": "DRAW",
  "toPhase": "PLAY",
  "gameSeat": 0
}
```

| 字段 | 说明 |
|------|------|
| `fromPhase` | 前一阶段 |
| `toPhase` | 当前阶段 |
| `gameSeat` | 当前行动玩家的游戏座位号 |

**阶段枚举：** `PREPARE`（准备）、`JUDGE`（判定）、`DRAW`（摸牌）、`PLAY`（出牌）、`DISCARD`（弃牌）、`END`（结束）

---

### 4.17 出牌动作（房间内广播）

```json
{
  "type": "PLAY_ACTION",
  "playerId": "uuid-string",
  "playerName": "张三",
  "cardName": "杀",
  "cardDefId": "sha",
  "suit": "SPADE",
  "point": 10,
  "targetIds": ["target-player-id"]
}
```

> 仅用于前端展示，不包含具体的游戏状态变更。

---

### 4.18 玩家状态更新（房间内广播）

```json
{
  "type": "PLAYER_UPDATE",
  "players": [
    {
      "playerId": "uuid-string",
      "currentHp": 3,
      "handCardCount": 2,
      "status": "ALIVE"
    }
  ]
}
```

| 字段 | 说明 |
|------|------|
| `currentHp` | 当前血量 |
| `handCardCount` | 手牌数量（不包含具体卡牌数据） |
| `status` | 玩家状态：`ALIVE`（存活）、`DYING`（濒死）、`DEAD`（死亡） |

---

### 4.19 战报（房间内广播）

```json
{
  "type": "BATTLE_REPORT",
  "message": "【张三】→ 出牌阶段"
}
```

> 由 `GameEventBroadcaster` 统一推送，用于前端显示战斗日志/消息列表。
>
> 触发时机：
> - 游戏开始/结束
> - 回合开始前/进行中/结束时/结束后
> - 进入每个阶段（准备/判定/摸牌/出牌/弃牌/结束）

---

### 4.20 在线玩家列表

```json
{
  "type": "ONLINE_PLAYERS",
  "players": [
    {
      "playerId": "uuid-string",
      "playerName": "张三",
      "status": "ONLINE"
    }
  ],
  "count": 1
}
```

| 字段 | 说明 |
|------|------|
| `status` | `ONLINE`（在线）、`IN_ROOM`（在房间中）、`IN_GAME`（游戏中） |

> 玩家连接/断开/进出房间/开始游戏时自动广播。

---

### 4.21 房间设置已更新（仅发送给发送者）

```json
{
  "type": "ROOM_SETTINGS_UPDATED",
  "settings": {
    "doubleIntruder": true,
    "turnTime": 20
  }
}
```

---

### 4.22 错误响应

```json
{
  "type": "ERROR",
  "message": "错误描述文字"
}
```

---

### 4.23 消息类型汇总

| type | 推送范围 | 说明 |
|------|----------|------|
| `CONNECTED` | 仅发送者 | 连接成功 |
| `HEARTBEAT_ACK` | 仅发送者 | 心跳响应 |
| `ROOM_CREATED` | 仅发送者 | 房间创建成功 |
| `ROOM_JOINED` | 仅发送者 | 加入房间成功 |
| `ROOM_LEFT` | 仅发送者 | 离开房间成功 |
| `ROOM_UPDATE` | 房间内 | 房间信息更新 |
| `ROOM_LIST` | 全部在线 | 房间列表 |
| `ROOM_CLOSED` | 房间内 | 房间已关闭 |
| `ROOM_SETTINGS_UPDATED` | 仅发送者 | 房间设置已更新 |
| `PLAYER_JOINED` | 房间内（除加入者） | 有玩家加入 |
| `PLAYER_LEFT` | 房间内（除离开者） | 有玩家离开 |
| `OWNER_CHANGED` | 房间内 | 房主变更 |
| `GAME_START` | 房间内 | 游戏开始 |
| `YOUR_PRIVATE_INFO` | 仅对应玩家 | 身份和座位信息 |
| `MY_HAND` | 仅对应玩家 | 手牌详情 |
| `TURN_START` | 房间内 | 回合开始 |
| `PHASE_CHANGE` | 房间内 | 阶段变更 |
| `PLAY_ACTION` | 房间内 | 出牌动作展示 |
| `PLAYER_UPDATE` | 房间内 | 玩家状态更新 |
| `BATTLE_REPORT` | 房间内 | 战报消息 |
| `ONLINE_PLAYERS` | 全部在线 | 在线玩家列表 |
| `ERROR` | 仅发送者 | 错误信息 |

---

## 5. 数据模型

### 5.1 房间信息（Room）

```json
{
  "roomId": "uuid-string",
  "roomName": "张三的房间",
  "ownerPlayerId": "uuid-string",
  "status": "WAITING",
  "maxPlayers": 8,
  "playerCount": 3,
  "roomSettings": {
    "doubleIntruder": false,
    "turnTime": 15,
    "placeholder1": ""
  },
  "players": [
    {
      "playerId": "uuid-string",
      "playerName": "张三",
      "seatNumber": 0,
      "isReady": true,
      "isAlive": true
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | string | `WAITING`（等待中）、`IN_PROGRESS`（进行中） |
| `roomSettings` | object | 房间设置，见第 7 节 |

### 5.2 玩家信息（PlayerInfo）

```json
{
  "playerId": "uuid-string",
  "name": "张三",
  "avatar": null
}
```

### 5.3 卡牌信息（CardInstance）

```json
{
  "instanceId": 10001,
  "defId": "sha",
  "name": "杀",
  "suit": "SPADE",
  "point": 10
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `instanceId` | number | 卡牌实例 ID（全局唯一），出牌时使用 |
| `defId` | string | 卡牌定义 ID，对应 `cards/standard.json` 中的 `id` 字段 |
| `name` | string | 卡牌显示名称 |
| `suit` | string | 花色：`SPADE` / `HEART` / `CLUB` / `DIAMOND` |
| `point` | number | 点数：1~13（1=A, 11=J, 12=Q, 13=K） |

### 5.4 游戏玩家信息（GamePlayer，用于游戏开始广播）

```json
{
  "playerId": "uuid-string",
  "playerName": "张三",
  "gameSeat": 0,
  "maxHp": 4,
  "currentHp": 4,
  "handCardCount": 4,
  "kingdom": "wei",
  "bot": false
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `gameSeat` | number | 游戏座位号（0 起） |
| `maxHp` | number | 最大体力值 |
| `currentHp` | number | 当前体力值 |
| `kingdom` | string | 势力：`wei`（魏）、`shu`（蜀）、`wu`（吴）、`qun`（群）、`shen`（神） |
| `bot` | boolean | 是否为机器人 |

### 5.5 身份枚举（RoleType）

| 值 | 中文 | 说明 |
|----|------|------|
| `LORD` | 主公 | 需要击杀所有反贼和内奸 |
| `MINION` | 忠臣 | 保护主公，击杀反贼和内奸 |
| `REBEL` | 反贼 | 击杀主公 |
| `INTRUDER` | 内奸 | 击杀所有人，最后与主公单挑取胜 |

### 5.6 游戏阶段枚举（GamePhase）

| 值 | 中文 | 说明 |
|----|------|------|
| `PREPARE` | 准备阶段 | 回合开始 |
| `JUDGE` | 判定阶段 | 判定区牌结算 |
| `DRAW` | 摸牌阶段 | 从牌堆摸 2 张牌 |
| `PLAY` | 出牌阶段 | 玩家可出牌 |
| `DISCARD` | 弃牌阶段 | 手牌超出体力值上限时弃牌 |
| `END` | 结束阶段 | 回合结束 |

### 5.7 玩家状态枚举（PlayerStatus）

| 值 | 说明 |
|-----|------|
| `ALIVE` | 存活 |
| `DYING` | 濒死（需要求桃） |
| `DEAD` | 死亡 |

### 5.8 游戏状态枚举（GameStatus）

| 值 | 说明 |
|-----|------|
| `INIT` | 初始化 |
| `PLAYING` | 进行中 |
| `FINISHED` | 已结束 |

---

## 6. 游戏流程

### 6.1 联机模式流程

```
1. 连接 WebSocket（携带 name 参数）
   → 收到 CONNECTED（获得 playerId）
   → 收到 ROOM_LIST（大厅房间列表）
   → 收到 ONLINE_PLAYERS（在线玩家列表）

2. 创建房间（CREATE_ROOM）或 加入房间（JOIN_ROOM）
   → 创建者收到 ROOM_CREATED
   → 加入者收到 ROOM_JOINED
   → 房间内全员收到 ROOM_UPDATE
   → 在线全员收到 ROOM_LIST 更新

3. 房主可操作：CLOSE_SEAT / OPEN_SEAT / UPDATE_ROOM_SETTINGS
   玩家操作：PLAYER_READY（准备）

4. 房主发送 START_GAME
   → 房间内全员收到 GAME_START（公开信息）
   → 每人分别收到 YOUR_PRIVATE_INFO（身份）
   → 每人分别收到 MY_HAND（手牌）
   → 全员收到 TURN_START（第一回合开始）

5. 游戏循环：
   - 当前行动玩家通过 NEXT_PHASE 推进阶段
   - 出牌阶段可发送 PLAY_CARD
   - 每个阶段变更 → PHASE_CHANGE / BATTLE_REPORT
   - 到达 END 阶段 → 自动切回合 → TURN_START
   - 每次切回合自动推进 PREPARE → JUDGE → DRAW → PLAY

6. 中途断线 → 转为 Bot 自动接管

7. 游戏结束 → BATTLE_REPORT
```

### 6.2 单机模式流程

```
1. 连接 WebSocket（同联机模式）

2. 发送 START_SINGLE_PLAYER
   → 后端自动创建房间 + 填充 Bot + 发牌
   → 收到 GAME_START / YOUR_PRIVATE_INFO / MY_HAND / TURN_START

3. 后续游戏循环同联机模式
```

### 6.3 阶段自动推进说明

每回合开始后，后端会自动快速推进以下阶段（无需玩家操作）：

```
PREPARE → JUDGE → DRAW → PLAY
```

- **PREPARE → JUDGE**：自动推进
- **JUDGE → DRAW**：如果判定区无牌，自动推进；有牌则停在 JUDGE 等待结算
- **DRAW**：自动摸 2 张牌，然后推进到 PLAY
- **PLAY**：**停住，等待玩家操作**

玩家在 PLAY 阶段可出牌或点 NEXT_PHASE 跳过。
到达 DISCARD 时自动推进到 END，然后自动切换到下一位玩家的回合。

---

## 7. 房间设置说明

房间设置存储在 `roomSettings` 字段中，是一个 Map。默认值：

```json
{
  "doubleIntruder": false,
  "turnTime": 15,
  "placeholder1": ""
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `doubleIntruder` | boolean | `false` | 是否启用双内奸模式；`false` = 标准身份，`true` = 双内奸 |
| `turnTime` | number | `15` | 每回合出手时间（秒） |

> 通过 `UPDATE_ROOM_SETTINGS` 发送的 `settings` 会与现有设置**增量合并**，未提供的字段保持不变。

---

> **版本：** v1.0
> **最后更新：** 2026-09-15