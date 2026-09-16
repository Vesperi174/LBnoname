# LBThreeCountry — WebSocket 消息协议文档（前端参考）

## 概述

所有消息均为 JSON 格式，通过 WebSocket 传输。

---

## 一、客户端 → 服务端（前端主动发送）

### 1.1 HEARTBEAT — 心跳

```json
{
  "type": "HEARTBEAT"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| type | string | 固定为 `"HEARTBEAT"` |

> 服务端回复：`HEARTBEAT_ACK`

---

### 1.2 CREATE_ROOM — 创建房间

```json
{
  "type": "CREATE_ROOM",
  "roomName": "我的房间",
  "maxPlayers": 8
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定为 `"CREATE_ROOM"` |
| roomName | string | 否 | 房间名称，默认 `"{玩家名}的房间"` |
| maxPlayers | number | 否 | 最大玩家数（4~8），默认 8 |

> 服务端回复：`ROOM_CREATED`

---

### 1.3 JOIN_ROOM — 加入房间

```json
{
  "type": "JOIN_ROOM",
  "roomId": "uuid-string"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定为 `"JOIN_ROOM"` |
| roomId | string | 是 | 目标房间 ID |

> 服务端回复：`ROOM_JOINED`（给自己） / `PLAYER_JOINED`（给房间其他人）

---

### 1.4 LEAVE_ROOM — 离开房间

```json
{
  "type": "LEAVE_ROOM"
}
```

> 服务端回复：`ROOM_LEFT`（给自己） / `PLAYER_LEFT`（给房间其他人）
>
> 游戏进行中离开会转为机器人托管。

---

### 1.5 ROOM_LIST — 查询房间列表

```json
{
  "type": "ROOM_LIST"
}
```

> 服务端回复：广播 `ROOM_LIST`

---

### 1.6 PLAYER_READY — 准备/取消准备

```json
{
  "type": "PLAYER_READY",
  "ready": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定为 `"PLAYER_READY"` |
| ready | boolean | 否 | `true`=准备，`false`=取消，默认 `true` |

> 服务端回复：`ROOM_UPDATE`（广播给房间所有人）

---

### 1.7 START_GAME — 开始游戏（房主）

```json
{
  "type": "START_GAME"
}
```

> 仅房主可发送。服务端自动用 Bot 填满空位 → 分配身份 → 发牌。
>
> 服务端回复：`GAME_START`、`YOUR_PRIVATE_INFO`、`MY_HAND`、`TURN_START` 等

---

### 1.8 PLAY_CARD — 出牌

```json
{
  "type": "PLAY_CARD",
  "cardInstanceId": 1001,
  "targetIds": ["playerId1", "playerId2"],
  "cardName": "杀",
  "cardDefId": "slash",
  "suit": "SPADE",
  "point": 10
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定为 `"PLAY_CARD"` |
| cardInstanceId | number | 是 | 卡牌实例 ID |
| targetIds | string[] | 是 | 目标玩家 ID 列表（可空数组） |
| cardName | string | 否 | 卡牌名称（用于广播显示，免去二次查询） |
| cardDefId | string | 否 | 卡牌定义 ID |
| suit | string | 否 | 花色 |
| point | number | 否 | 点数 |

> 服务端回复：`PLAY_ACTION`、`PLAYER_UPDATE`、`MY_HAND`

---

### 1.9 UPDATE_ROOM_SETTINGS — 更新房间设置（房主）

```json
{
  "type": "UPDATE_ROOM_SETTINGS",
  "settings": {
    "doubleIntruder": false,
    "turnTime": 15
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | 固定为 `"UPDATE_ROOM_SETTINGS"` |
| settings | object | 是 | 要更新的设置键值对，合并到现有设置 |

> 服务端回复：`ROOM_SETTINGS_UPDATED` + `ROOM_UPDATE`

---

### 1.10 LIST_ONLINE_PLAYERS — 查询在线玩家

```json
{
  "type": "LIST_ONLINE_PLAYERS"
}
```

> 服务端回复：广播 `ONLINE_PLAYERS`

---

## 二、服务端 → 客户端（前端接收处理）

### 2.1 CONNECTED — 连接成功

```json
{
  "type": "CONNECTED",
  "playerId": "uuid",
  "playerName": "玩家名"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| playerId | string | 服务端分配的玩家唯一 ID |
| playerName | string | 玩家名称 |

---

### 2.2 ERROR — 错误消息

```json
{
  "type": "ERROR",
  "message": "错误描述"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| message | string | 错误描述文本 |

---

### 2.3 HEARTBEAT_ACK — 心跳回复

```json
{
  "type": "HEARTBEAT_ACK"
}
```

---

### 2.4 ROOM_CREATED — 房间创建成功

```json
{
  "type": "ROOM_CREATED",
  "room": { "...完整房间信息..." }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| room | object | 完整房间信息（结构见附录） |

---

### 2.5 ROOM_JOINED — 加入房间成功（给自己）

```json
{
  "type": "ROOM_JOINED",
  "room": { "...完整房间信息..." }
}
```

---

### 2.6 ROOM_LEFT — 离开房间成功（给自己）

```json
{
  "type": "ROOM_LEFT",
  "roomId": "uuid"
}
```

---

### 2.7 ROOM_LIST — 房间列表

```json
{
  "type": "ROOM_LIST",
  "rooms": [
    { "...完整房间信息..." },
    { "...完整房间信息..." }
  ]
}
```

---

### 2.8 ROOM_UPDATE — 房间信息更新

```json
{
  "type": "ROOM_UPDATE",
  "room": { "...完整房间信息..." }
}
```

> 玩家加入/离开/准备/设置变更时广播给房间所有人

---

### 2.9 ROOM_CLOSED — 房间已关闭

```json
{
  "type": "ROOM_CLOSED",
  "roomId": "uuid",
  "message": "所有玩家已离开，房间已销毁"
}
```

---

### 2.10 ROOM_SETTINGS_UPDATED — 设置更新成功

```json
{
  "type": "ROOM_SETTINGS_UPDATED",
  "settings": { "...房间设置..." }
}
```

---

### 2.11 PLAYER_JOINED — 有玩家加入房间

```json
{
  "type": "PLAYER_JOINED",
  "playerId": "uuid",
  "playerName": "玩家名"
}
```

---

### 2.12 PLAYER_LEFT — 有玩家离开房间

```json
{
  "type": "PLAYER_LEFT",
  "playerId": "uuid",
  "playerName": "玩家名",
  "bot": false
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| bot | boolean | 是否转为机器人（玩家断线/游戏进行中离开时 `true`） |

---

### 2.13 OWNER_CHANGED — 房主变更

```json
{
  "type": "OWNER_CHANGED",
  "newOwnerId": "uuid",
  "newOwnerName": "新房主名"
}
```

---

### 2.14 GAME_START — 游戏开始

```json
{
  "type": "GAME_START",
  "roomId": "uuid",
  "players": [
    {
      "playerId": "uuid",
      "playerName": "玩家名",
      "gameSeat": 0,
      "maxHp": 4,
      "currentHp": 4,
      "handCardCount": 4,
      "kingdom": "WEI",
      "kingdomColor": "#0055A4",
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

| 字段 | 类型 | 说明 |
|------|------|------|
| players | array | 所有玩家公开信息列表（不含身份和手牌） |
| currentPlayerIndex | number | 当前行动玩家的 gameSeat |
| currentPhase | string | 当前阶段：`PREPARE` `JUDGE` `DRAW` `PLAY` `DISCARD` `END` |
| round | number | 当前轮次 |
| totalTurns | number | 总回合数 |
| turnTime | number | 每回合限时（秒） |
| kingdom | string | 势力代码：`WEI` `SHU` `WU` `QUN` `SHEN` |
| kingdomColor | string | 势力颜色（十六进制） |

---

### 2.15 YOUR_PRIVATE_INFO — 玩家私有信息（私发）

```json
{
  "type": "YOUR_PRIVATE_INFO",
  "playerId": "uuid",
  "role": "LORD",
  "gameSeat": 0,
  "handCardCount": 4
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| role | string | 身份：`LORD`(主公) `MINION`(忠臣) `REBEL`(反贼) `INTRUDER`(内奸) |
| gameSeat | number | 游戏座位号（0-based，排序决定行动顺序） |
| handCardCount | number | 手牌数量 |

> ⚠️ **此消息仅发给对应玩家本人**，前端应根据 role 字段展示身份。

---

### 2.16 MY_HAND — 我的手牌（私发）

```json
{
  "type": "MY_HAND",
  "cards": [
    {
      "instanceId": 1001,
      "defId": "slash",
      "name": "杀",
      "suit": "SPADE",
      "point": 10
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| instanceId | number | 卡牌实例唯一 ID（出牌时用此 ID） |
| defId | string | 卡牌定义 ID |
| name | string | 卡牌显示名称 |
| suit | string | 花色：`SPADE`(黑桃) `HEART`(红桃) `CLUB`(梅花) `DIAMOND`(方块) |
| point | number | 点数：1(A)~13(K) |

> ⚠️ **此消息仅发给对应玩家本人**

---

### 2.17 TURN_START — 回合开始

```json
{
  "type": "TURN_START",
  "roomId": "uuid",
  "gameSeat": 3,
  "playerName": "张三",
  "round": 2,
  "totalTurns": 8,
  "phase": "PREPARE",
  "turnTime": 15,
  "drawPileCount": 60,
  "discardPileCount": 10
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| gameSeat | number | 当前行动玩家的座位号 |
| playerName | string | 当前行动玩家名称 |
| round | number | 当前轮次 |
| phase | string | 当前阶段 |
| drawPileCount | number | 摸牌堆剩余数量 |
| discardPileCount | number | 弃牌堆数量 |

---

### 2.18 PHASE_CHANGE — 阶段变更

```json
{
  "type": "PHASE_CHANGE",
  "roomId": "uuid",
  "fromPhase": "DRAW",
  "toPhase": "PLAY",
  "gameSeat": 3
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| fromPhase | string | 上一阶段 |
| toPhase | string | 当前阶段 |
| gameSeat | number | 当前玩家座位号 |

---

### 2.19 PLAY_ACTION — 出牌动作

```json
{
  "type": "PLAY_ACTION",
  "playerId": "uuid",
  "playerName": "张三",
  "cardName": "杀",
  "cardDefId": "slash",
  "suit": "SPADE",
  "point": 10,
  "targetIds": ["playerId1"]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| playerId | string | 出牌玩家 ID |
| playerName | string | 出牌玩家名称 |
| cardName | string | 卡牌名称 |
| cardDefId | string | 卡牌定义 ID |
| targetIds | string[] | 目标玩家 ID 列表 |

---

### 2.20 PLAYER_UPDATE — 玩家状态更新

```json
{
  "type": "PLAYER_UPDATE",
  "players": [
    {
      "playerId": "uuid",
      "currentHp": 3,
      "handCardCount": 2,
      "status": "ALIVE"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| currentHp | number | 当前血量 |
| handCardCount | number | 手牌数量 |
| status | string | `ALIVE`(存活) `DYING`(濒死) `DEAD`(死亡) |

---

### 2.21 BATTLE_REPORT — 战报

```json
{
  "type": "BATTLE_REPORT",
  "message": "【张三】→ 出牌阶段"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| message | string | 战报文本 |

> 由游戏引擎事件触发推送，用于显示游戏流程日志。

---

### 2.22 GAME_LOG — 游戏日志（调试用）

```json
{
  "type": "GAME_LOG",
  "message": "玩家A对玩家B使用了杀"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| message | string | 日志文本 |

> 用于开发调试，前端收到后在日志面板显示。
>
> 发布方式（后端代码中使用）：
> ```java
> GameEvent logEvent = GameEvent.builder()
>     .type(GameEventType.GAME_LOG)
>     .sourceId("system")
>     .build();
> logEvent.putData("roomId", roomId);
> logEvent.putData("message", "日志内容");
> eventBus.publish(logEvent, match);
> ```

---

### 2.23 ONLINE_PLAYERS — 在线玩家列表

```json
{
  "type": "ONLINE_PLAYERS",
  "players": [
    {
      "playerId": "uuid",
      "playerName": "张三",
      "status": "ONLINE"
    }
  ],
  "count": 5
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| status | string | `ONLINE`(在线) `IN_ROOM`(在房间) `IN_GAME`(游戏中) |

---

## 三、附录：完整房间信息结构

```json
{
  "roomId": "uuid",
  "roomName": "我的房间",
  "ownerPlayerId": "uuid",
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
      "playerId": "uuid",
      "playerName": "张三",
      "isReady": true,
      "isAlive": true
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| status | string | `WAITING`(等待中) `PLAYING`(游戏中) |
| players[].isReady | boolean | 是否已准备 |
| players[].isAlive | boolean | 是否存活（游戏中） |

---

## 四、阶段说明

| 阶段代码 | 中文名称 | 说明 |
|----------|----------|------|
| PREPARE | 准备阶段 | 回合开始时触发 |
| JUDGE | 判定阶段 | 处理延时锦囊牌的判定 |
| DRAW | 摸牌阶段 | 自动从牌堆摸 2 张牌 |
| PLAY | 出牌阶段 | 玩家可出牌（需等待前端操作） |
| DISCARD | 弃牌阶段 | 手牌超过体力值时需弃牌 |
| END | 结束阶段 | 回合结束，自动切换到下一玩家 |

---

## 五、身份说明

| 身份代码 | 中文名称 | 说明 |
|----------|----------|------|
| LORD | 主公 | 游戏开始时排在第一位的玩家 |
| MINION | 忠臣 | 保护主公，与主公同一阵营 |
| REBEL | 反贼 | 目标是杀死主公 |
| INTRUDER | 内奸 | 需要杀死所有人存活到最后 |

---

## 六、交互流程示例

```
[客户端]                           [服务端]
   |                                  |
   |── HEARTBEAT ──────────────────→  |
   |←──────────── HEARTBEAT_ACK ─────|
   |                                  |
   |── CREATE_ROOM ────────────────→  |
   |←──────────── ROOM_CREATED ──────|
   |←──────────── ROOM_LIST ─────────| (广播)
   |                                  |
   |── JOIN_ROOM ──────────────────→  |
   |←──────────── ROOM_JOINED ───────|
   |←────────── PLAYER_JOINED ───────| (给房间其他人)
   |←──────────── ROOM_UPDATE ───────|
   |                                  |
   |── PLAYER_READY ───────────────→  |
   |←──────────── ROOM_UPDATE ───────| (广播)
   |                                  |
   |── START_GAME ─────────────────→  |
   |←──────────── GAME_START ────────|
   |←────── YOUR_PRIVATE_INFO ───────| (私发)
   |←──────────── MY_HAND ───────────| (私发)
   |←────────── TURN_START ──────────|
   |                                  |
   |── PLAY_CARD ──────────────────→  |
   |←────────── PLAY_ACTION ─────────|
   |←───────── PLAYER_UPDATE ────────|
   |←──────────── MY_HAND ───────────| (私发)
   |                                  |
   |←────────── TURN_START ──────────| (换回合)
   |                                  |
```

---

*文档版本: v1.0 | 最后更新: 2026-09-16*