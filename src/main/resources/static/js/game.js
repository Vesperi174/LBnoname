// ============================================================
//  三国杀 · 联机测试 - 游戏逻辑
// ============================================================

// ============================================================
//  状态
// ============================================================
const STATE = {
    playerId: null,
    playerName: null,
    ws: null,
    currentRoom: null,
    isHost: false,
    isReady: false,

    // 游戏状态
    game: {
        round: 1,
        totalTurns: 1,
        phase: 'PREPARE',
        currentPlayerIndex: 0,
        players: [],
        myPrivateInfo: {},
        started: false,
        myHandCards: [],
        myEquipment: {
            weapon: null,
            armor: null,
            mountPlus: null,
            mountMinus: null,
            treasure: null,
        },
        fieldCards: [],
    }
};

const STORAGE_KEY = 'lb_player_name';

// 阶段中文名
const PHASE_NAMES = {
    PREPARE: '准备阶段',
    JUDGE:   '判定阶段',
    DRAW:    '摸牌阶段',
    PLAY:    '出牌阶段',
    DISCARD: '弃牌阶段',
    END:     '结束阶段',
};

// 角色中文名
const ROLE_NAMES = {
    LORD:     '主公',
    MINION:   '忠臣',
    REBEL:    '反贼',
    INTRUDER: '内奸',
};
// 势力底色
const KINGDOM_COLORS = {
    1:   '#b22222', // 蜀 — 赤红
    2:   '#2a4a7f', // 魏 — 深蓝
    3:   '#2d7d46', // 吴 — 暗绿
    4:   '#5a5a5a', // 群 — 铁灰
    101: '#2e8b57', // 艾欧尼亚 — 碧绿
    102: '#8b1a1a', // 诺克萨斯 — 暗红
    103: '#5b8cac', // 弗雷尔卓德 — 冰蓝
    104: '#c8a84e', // 符文大陆 — 金黄
    105: '#a0785a', // 皮尔特沃夫 — 铜棕
    106: '#4a2c5a', // 暗影岛 — 暗紫
    107: '#6b8e5a', // 班德尔城 — 草绿
    108: '#8a7a4a', // 巨神锋 — 土黄
    109: '#3a6b8a', // 德玛西亚 — 天蓝
    110: '#7a5a3a', // 比尔吉沃特 — 棕褐
    111: '#5a7a4a', // 以绪塔尔 — 墨绿
    112: '#6a5a3a', // 祖安 — 烟灰
};
const DEFAULT_KINGDOM_COLOR = '#3a3a3a';

// ============================================================
//  DOM 引用
// ============================================================
const $ = id => document.getElementById(id);
const loginScreen       = $('loginScreen');
const modeSelectScreen  = $('modeSelectScreen');
const lobbyScreen       = $('lobbyScreen');
const roomScreen        = $('roomScreen');
const gameScreen        = $('gameScreen');
const gameOverOverlay   = $('gameOverOverlay');
const nameInput         = $('nameInput');
const loginBtn          = $('loginBtn');
const loginStatus       = $('loginStatus');
const modePlayerName    = $('modePlayerName');
const singleModeBtn     = $('singleModeBtn');
const multiModeBtn      = $('multiModeBtn');
const backToLoginBtn    = $('backToLoginBtn');
const playerBadge       = $('playerBadge');
const roomNameInput     = $('roomNameInput');
const createRoomBtn     = $('createRoomBtn');
const roomList          = $('roomList');
const roomIdInput       = $('roomIdInput');
const joinByIdBtn       = $('joinByIdBtn');
const lobbyStatus       = $('lobbyStatus');
const roomTitle         = $('roomTitle');
const roomIdDisplay     = $('roomIdDisplay');
const playerList        = $('playerList');
const readyBtn          = $('readyBtn');
const startGameBtn      = $('startGameBtn');
const leaveRoomBtn      = $('leaveRoomBtn');
const roomHint          = $('roomHint');
const roomStatus        = $('roomStatus');
const onlinePlayerList  = $('onlinePlayerList');
const onlineCount       = $('onlineCount');

// 游戏结束弹窗 DOM
const gameOverTitle     = $('gameOverTitle');
const gameOverDesc      = $('gameOverDesc');
const gameOverOkBtn     = $('gameOverOkBtn');

// 对局 UI
const backToLobbyBtn    = $('backToLobbyBtn');
const gtbRound          = $('gtbRound');
const gtbTurn           = $('gtbTurn');
const gtbPhase          = $('gtbPhase');
const pcInfo            = $('pcInfo');
const pcCharName        = $('pcCharName');
const pcHpArea          = $('pcHpArea');
const pcNameOverlay     = $('pcNameOverlay');
const pcRoleOverlay     = $('pcRoleOverlay');
const pcSeatNum         = $('pcSeatNum');
const skillBar          = $('skillBar');
const eqRows            = $('eqRows');

const CN_NUMS = ['零','一','二','三','四','五','六','七','八','九','十'];

// ============================================================
//  装备配置
// ============================================================
const EQUIP_CONFIG = [
    { key: 'weapon',     label: '武器', placeholder: '无' },
    { key: 'armor',      label: '防具', placeholder: '无' },
    { key: 'mountMinus', label: '进攻马', placeholder: '无' },
    { key: 'mountPlus',  label: '防御马', placeholder: '无' },
    { key: 'treasure',   label: '宝物', placeholder: '无' },
];

/** 更新顶部状态栏 */
function updateTopBar() {
    var g = STATE.game;
    if (!g || !g.started) {
        gtbRound.textContent = '第 0 轮';
        gtbTurn.textContent  = '第 0 回合';
        gtbPhase.textContent = '等待开始';
        return;
    }
    gtbRound.textContent = '第 ' + (g.round || 1) + ' 轮';
    gtbTurn.textContent  = '第 ' + (g.totalTurns || 1) + ' 回合';

    var cur = g.players[g.currentPlayerIndex];
    if (cur) {
        var phaseName = PHASE_NAMES[g.phase] || g.phase || '';
        gtbPhase.textContent = cur.playerName + ' · ' + phaseName;
    } else {
        gtbPhase.textContent = '等待中';
    }
}

/** 更新底部玩家信息卡 */
function renderPlayerCard() {
    var g = STATE.game;
    var me = g && g.started ? g.players.find(function (p) { return p.playerId === STATE.playerId; }) : null;

    // --- 名字和身份悬停 ---
    if (me) {
        pcNameOverlay.textContent = me.playerName + (me.bot ? ' (AI)' : '');
        var roleName = ROLE_NAMES[me.role] || me.role || '?';
        pcRoleOverlay.textContent = roleName;
    } else {
        pcNameOverlay.textContent = '等待中';
        pcRoleOverlay.textContent = '?';
    }

    // --- 座位号 ---
    if (me) {
        var seat = me.gameSeat !== undefined ? me.gameSeat + 1 : 1;
        pcSeatNum.textContent = CN_NUMS[seat] || seat;
    } else {
        pcSeatNum.textContent = '零';
    }

    // --- 势力底色 ---
    if (me && me.kingdom != null) {
        var color = KINGDOM_COLORS[me.kingdom] || DEFAULT_KINGDOM_COLOR;
        pcInfo.style.backgroundColor = color;
    } else {
        pcInfo.style.backgroundColor = DEFAULT_KINGDOM_COLOR;
    }

    // --- 角色名（顶部） ---
    pcCharName.textContent = me ? '卡斯奥佩娅' : '';

    // --- 体力竖点/竖排文字（底部） ---
    if (!me) {
        pcHpArea.innerHTML = '';
        return;
    }
    var cur = me.currentHp || 0;
    var max = me.maxHp || 0;

    if (max <= 5) {
        var dots = '';
        for (var i = 0; i < max; i++) {
            var cls = i < cur ? 'alive' : 'lost';
            dots += '<span class="hp-dot ' + cls + '"></span>';
        }
        pcHpArea.innerHTML = dots;
    } else {
        var txt = cur + '/' + max;
        pcHpArea.innerHTML = '<span class="hp-text" id="pcHpText">' + txt + '</span>';
    }

    // --- 自适应字号（保证不溢出） ---
    requestAnimationFrame(fitPlayerCard);

    // --- 技能按钮 ---
    renderSkills(me);
}

/** 更新技能按钮 */
function renderSkills(me) {
    // 占位技能列表：后续根据武将信息动态生成
    var skills = me
        ? ['霸体', '神威', '极意', '无双', '天崩', '地裂', '涅槃', '鬼谋']
        : [];

    var count = skills.length;
    var children = skillBar.children;

    // 保证按钮数量匹配
    while (children.length < count) {
        var btn = document.createElement('button');
        btn.className = 'skill-btn';
        skillBar.appendChild(btn);
    }
    while (children.length > count) {
        skillBar.removeChild(children[children.length - 1]);
    }

    for (var i = 0; i < count; i++) {
        children[i].textContent = skills[i];
    }
}

/** 渲染装备模块（左下角五行） */
function renderEquipment() {
    var equip = STATE.game.myEquipment || {};

    // 保证五行 DOM 存在
    var children = eqRows.children;
    var configLen = EQUIP_CONFIG.length;

    while (children.length < configLen) {
        var row = document.createElement('div');
        row.className = 'eq-row';

        var label = document.createElement('span');
        label.className = 'eq-label';
        row.appendChild(label);

        var name = document.createElement('span');
        name.className = 'eq-card-name';
        row.appendChild(name);

        eqRows.appendChild(row);
    }
    while (children.length > configLen) {
        eqRows.removeChild(children[children.length - 1]);
    }

    // 填充数据
    for (var i = 0; i < configLen; i++) {
        var cfg = EQUIP_CONFIG[i];
        var row = children[i];
        row.className = 'eq-row';

        var labelEl = row.children[0];
        var nameEl = row.children[1];

        labelEl.textContent = cfg.label;

        var card = equip[cfg.key];
        if (card) {
            nameEl.textContent = card.name || card.cardName || (card.id || '???');
            nameEl.className = 'eq-card-name';
        } else {
            nameEl.textContent = cfg.placeholder;
            nameEl.className = 'eq-card-name empty';
        }
    }
}

// ============================================================
//  自适应字号
// ============================================================
function fitPlayerCard() {
    // 角色名：根据剩余空间缩放
    fitVertText(pcCharName, 18, 7);
    // HP 文字（超过 5 点时）
    var hpTxt = document.getElementById('pcHpText');
    if (hpTxt) fitVertText(hpTxt, 24, 8);
}
function fitVertText(el, maxSize, minSize) {
    if (!el || !el.textContent) return;
    var parent = el.closest('.pc-info');
    if (!parent) return;
    var parentH = parent.clientHeight || 135;
    var hpH = pcHpArea.clientHeight || 0;
    var pad = 16;
    var available = parentH - hpH - pad;
    if (available < 15) available = 15;
    var len = el.textContent.length;
    if (len === 0) return;
    var size = Math.floor(available / (len * 1.2));
    size = Math.max(minSize, Math.min(maxSize, size));
    el.style.fontSize = size + 'px';
}

// ============================================================
//  辅助函数
// ============================================================
const containerEl = document.querySelector('.container');
const bodyEl = document.body;

/** 检测视口尺寸并设置 CSS 自定义属性 */
function adaptScreenSize() {
    var w = window.innerWidth;
    var h = window.innerHeight;
    document.documentElement.style.setProperty('--vw', w + 'px');
    document.documentElement.style.setProperty('--vh', h + 'px');
    document.documentElement.style.setProperty('--game-min-dim', Math.min(w, h) + 'px');
}
window.addEventListener('resize', adaptScreenSize);

function showScreen(screen) {
    [loginScreen, modeSelectScreen, lobbyScreen, roomScreen, gameScreen].forEach(s => s.classList.add('hidden'));
    screen.classList.remove('hidden');
    containerEl.classList.toggle('game-active', screen === gameScreen);
    containerEl.classList.toggle('lobby-active', screen === lobbyScreen || screen === modeSelectScreen);
    bodyEl.classList.toggle('game-body-lobby', screen !== gameScreen);
    if (screen === gameScreen) adaptScreenSize();
}

function status(el, msg, type) {
    if (!type) type = 'info';
    el.innerHTML = '<span class="' + type + '">' + msg + '</span>';
}

function showError(msg)   { status(lobbyStatus, msg, 'error'); }
function showInfo(msg)    { status(lobbyStatus, msg, 'info'); }
function showSuccess(msg) { status(lobbyStatus, msg, 'success'); }

function sendMsg(data) {
    if (STATE.ws && STATE.ws.readyState === WebSocket.OPEN) {
        STATE.ws.send(JSON.stringify(data));
    }
}

// ============================================================
//  花色 / 卡牌类型 工具
// ============================================================
function suitSymbol(suit) {
    var map = { 'SPADE': 'S', 'HEART': 'H', 'CLUB': 'C', 'DIAMOND': 'D' };
    return map[suit] || suit || '?';
}
function suitCssClass(suit) {
    var map = { 'SPADE': 'hc-suit-spade', 'HEART': 'hc-suit-heart', 'CLUB': 'hc-suit-club', 'DIAMOND': 'hc-suit-diamond' };
    return map[suit] || '';
}
function cardTypeClass(type) {
    var map = { 'BASIC': 'card-type-basic', 'TACTIC': 'card-type-tactic', 'EQUIPMENT': 'card-type-equip', 'EQUIP': 'card-type-equip' };
    return map[type] || 'card-type-basic';
}

// ============================================================
//  WebSocket
// ============================================================
function connectWebSocket(name) {
    var protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    var wsUrl = protocol + '//' + location.host + '/ws/game?name=' + encodeURIComponent(name);
    var ws = new WebSocket(wsUrl);

    ws.onopen = function () {
        showInfo('WebSocket 已连接');
    };

    ws.onmessage = function (event) {
        try {
            var msg = JSON.parse(event.data);
            handleMessage(msg);
        } catch (e) {
            console.warn('消息解析失败:', event.data);
        }
    };

    ws.onclose = function () {
        showError('与服务器断开连接');
        STATE.ws = null;
        multiModeBtn.disabled = false;
        setTimeout(function () {
            showScreen(loginScreen);
            status(loginStatus, '连接已断开，刷新页面重试', 'error');
        }, 500);
    };

    ws.onerror = function () {
        showError('连接错误，请确认服务端已启动');
        multiModeBtn.disabled = false;
    };

    STATE.ws = ws;
}

// ============================================================
//  消息处理
// ============================================================
function handleMessage(msg) {
    switch (msg.type) {
        case 'CONNECTED':
            STATE.playerId = msg.playerId;
            STATE.playerName = msg.playerName;
            playerBadge.textContent = msg.playerName;
            showSuccess('以"' + msg.playerName + '"身份进入大厅');
            showScreen(lobbyScreen);
            break;

        case 'ROOM_LIST':
            renderRoomList(msg.rooms || []);
            break;

        case 'ONLINE_PLAYERS':
            renderOnlinePlayers(msg.players || [], msg.count || 0);
            break;

        case 'ROOM_CREATED':
        case 'ROOM_JOINED':
            STATE.currentRoom = msg.room;
            enterRoomView(msg.room);
            break;

        case 'ROOM_UPDATE':
            STATE.currentRoom = msg.room;
            updateRoomView(msg.room);
            break;

        case 'ROOM_LEFT':
            STATE.currentRoom = null;
            STATE.isHost = false;
            STATE.isReady = false;
            showScreen(lobbyScreen);
            showInfo('已离开房间');
            break;

        case 'PLAYER_JOINED':
            showInfo(msg.playerName + ' 加入了房间');
            break;

        case 'PLAYER_LEFT':
            if (msg.bot) {
                showInfo(msg.playerName + ' 已离线，机器人接管');
            } else {
                showInfo(msg.playerName + ' 离开了房间');
            }
            break;

        // ==================== 游戏事件（UI 待重建） ====================
        case 'GAME_START':
            var players = msg.players || [];
            STATE.game.started = true;
            STATE.game.players = players;
            STATE.game.round = msg.round || 1;
            STATE.game.totalTurns = msg.totalTurns || 1;
            STATE.game.phase = msg.currentPhase || 'PREPARE';
            STATE.game.currentPlayerIndex = msg.currentPlayerIndex || 0;
            STATE.game.myHandCards = [];
            STATE.game.myEquipment = { weapon: null, armor: null, mountPlus: null, mountMinus: null, treasure: null };
            STATE.game.fieldCards = [];

            showScreen(gameScreen);
            updateTopBar();
            renderPlayerCard();
            renderEquipment();
            console.log('游戏开始，共 ' + players.length + ' 名玩家');
            break;

        case 'YOUR_PRIVATE_INFO':
            STATE.game.myPrivateInfo = msg;
            var myIdx = STATE.game.players.findIndex(function (p) { return p.playerId === msg.playerId; });
            if (myIdx >= 0) {
                STATE.game.players[myIdx].role = msg.role;
            }
            renderPlayerCard();
            var roleName = ROLE_NAMES[msg.role] || msg.role;
            console.log('你的身份：' + roleName + '，手牌数：' + msg.handCardCount);
            break;

        case 'ROUND_CHANGE':
            STATE.game.round = msg.round;
            updateTopBar();
            renderPlayerCard();
            console.log('进入第 ' + msg.round + ' 轮');
            break;

        case 'TURN_START':
            STATE.game.currentPlayerIndex = msg.gameSeat;
            STATE.game.phase = msg.phase || 'PREPARE';
            STATE.game.totalTurns = msg.totalTurns || STATE.game.totalTurns;
            STATE.game.round = msg.round || STATE.game.round;
            updateTopBar();
            renderPlayerCard();
            var name = msg.playerName || '未知';
            var phaseName = PHASE_NAMES[msg.phase] || msg.phase;
            console.log(name + ' 的回合开始（阶段：' + phaseName + '）');
            break;

        case 'PHASE_CHANGE':
            STATE.game.phase = msg.toPhase;
            updateTopBar();
            renderPlayerCard();
            var fromName = PHASE_NAMES[msg.fromPhase] || msg.fromPhase;
            var toName = PHASE_NAMES[msg.toPhase] || msg.toPhase;
            console.log(fromName + ' -> ' + toName);
            break;

        case 'FIELD_CARDS': {
            STATE.game.fieldCards = msg.cards || [];
            break;
        }

        case 'MY_HAND': {
            STATE.game.myHandCards = msg.cards || [];
            break;
        }

        case 'MY_EQUIPMENT': {
            STATE.game.myEquipment = msg.equipment || STATE.game.myEquipment;
            renderEquipment();
            break;
        }

        case 'PLAYER_UPDATE': {
            var updatedPlayers = msg.players || [];
            updatedPlayers.forEach(function (up) {
                var target = STATE.game.players.find(function (p) { return p.playerId === up.playerId; });
                if (target) {
                    for (var key in up) {
                        if (up.hasOwnProperty(key)) target[key] = up[key];
                    }
                }
            });
            updateTopBar();
            renderPlayerCard();
            break;
        }

        case 'GAME_OVER':
            if (msg.winnerRole === 'NONE') {
                STATE.game.started = false;
                STATE.game.players = [];
                STATE.game.myPrivateInfo = {};
                showScreen(lobbyScreen);
                showInfo('对局已销毁（所有玩家离开）');
                sendMsg({ type: 'LIST_ROOMS' });
                break;
            }
            var winnerDesc = msg.winnerDesc || msg.winnerRole || '未知';
            STATE.game.started = false;
            updateTopBar();
            renderPlayerCard();
            console.log('游戏结束！胜者：' + winnerDesc);
            showGameOver(winnerDesc);
            break;

        case 'ROOM_CLOSED':
            STATE.currentRoom = null;
            STATE.isHost = false;
            STATE.isReady = false;
            STATE.game.started = false;
            STATE.game.players = [];
            STATE.game.myPrivateInfo = {};
            showScreen(lobbyScreen);
            showInfo(msg.message || '房间已关闭');
            sendMsg({ type: 'LIST_ROOMS' });
            break;

        case 'HEARTBEAT_ACK':
            break;

        case 'ERROR':
            showError(msg.message);
            break;

        default:
            console.log('未处理的消息类型:', msg.type, msg);
    }
}

// ============================================================
//  房间列表渲染
// ============================================================
function renderRoomList(rooms) {
    if (!rooms || rooms.length === 0) {
        roomList.innerHTML = '<div class="empty-hint">暂无房间，创建第一个吧</div>';
        return;
    }
    var html = '';
    for (var i = 0; i < rooms.length; i++) {
        var r = rooms[i];
        var canJoin = r.playerCount < r.maxPlayers && r.status === 'WAITING';
        var statusText = r.status === 'WAITING' ? '等待中' : '进行中';
        var btnText = canJoin ? '加入' : (r.status !== 'WAITING' ? '进行中' : '已满');
        html += '<div class="room-item">' +
            '<div class="info">' +
            '<div class="name">' + escHtml(r.roomName) + '</div>' +
            '<div class="meta">' + r.playerCount + '/' + r.maxPlayers + ' 人 · ' + statusText + '</div>' +
            '</div>' +
            '<button class="btn btn-success btn-sm join-room-btn" data-room-id="' + r.roomId + '"' +
            (canJoin ? '' : ' disabled') + '>' + btnText + '</button>' +
            '</div>';
    }
    roomList.innerHTML = html;
    document.querySelectorAll('.join-room-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            sendMsg({ type: 'JOIN_ROOM', roomId: btn.dataset.roomId });
        });
    });
}

// ============================================================
//  在线玩家列表渲染
// ============================================================
var STATUS_MAP = {
    ONLINE:  { text: '在线',   dotClass: 'dot-online',  lineClass: 'status-online' },
    IN_ROOM: { text: '房间中', dotClass: 'dot-room',    lineClass: 'status-room'   },
    IN_GAME: { text: '游戏中', dotClass: 'dot-game',    lineClass: 'status-game'   },
};

function renderOnlinePlayers(players, count) {
    onlineCount.textContent = count;
    if (!players || players.length === 0) {
        onlinePlayerList.innerHTML = '<div class="empty-hint">暂无在线玩家</div>';
        return;
    }
    var html = '';
    for (var i = 0; i < players.length; i++) {
        var p = players[i];
        var isMe = p.playerId === STATE.playerId;
        var initial = (p.playerName || '?').charAt(0);
        var st = STATUS_MAP[p.status] || STATUS_MAP.ONLINE;
        var meBadge = isMe ? '<span class="me-badge">我</span>' : '';
        html += '<div class="online-player-item">' +
            '<div class="avatar">' + initial + '</div>' +
            '<div class="info">' +
            '<div class="name-line">' +
            '<span class="name-text">' + escHtml(p.playerName) + '</span>' +
            meBadge +
            '</div>' +
            '<div class="status-line ' + st.lineClass + '">' + st.text + '</div>' +
            '</div>' +
            '<div class="status-dot ' + st.dotClass + '"></div>' +
            '</div>';
    }
    onlinePlayerList.innerHTML = html;
}

// ============================================================
//  房间界面
// ============================================================
function enterRoomView(room) {
    STATE.currentRoom = room;
    STATE.isHost = room.ownerPlayerId === STATE.playerId;
    var me = room.players.find(function (p) { return p.playerId === STATE.playerId; });
    STATE.isReady = me ? me.isReady : false;
    showScreen(roomScreen);
    updateRoomView(room);
}

function updateRoomView(room) {
    STATE.currentRoom = room;
    STATE.isHost = room.ownerPlayerId === STATE.playerId;
    var me = room.players.find(function (p) { return p.playerId === STATE.playerId; });
    STATE.isReady = me ? me.isReady : false;

    roomTitle.textContent = escHtml(room.roomName);
    roomIdDisplay.textContent = room.roomId;

    var playerHtml = '';
    for (var i = 0; i < room.players.length; i++) {
        var p = room.players[i];
        var isOwner = p.playerId === room.ownerPlayerId;
        var readyText = p.isReady ? '已准备' : '未准备';
        var readyClass = p.isReady ? 'ready-tag' : 'not-ready-tag';
        var isMe = p.playerId === STATE.playerId;
        var meSpan = isMe ? '<span style="color:#4a8af4;font-size:11px;">[我]</span>' : '';
        var ownerTag = isOwner ? '<span class="host-tag">房主</span>' : '';
        playerHtml += '<div class="player-chip">' +
            '<span>' + escHtml(p.playerName) + '</span>' +
            ownerTag + meSpan +
            '<span class="' + readyClass + '">' + readyText + '</span>' +
            '</div>';
    }
    playerList.innerHTML = playerHtml;

    readyBtn.textContent = STATE.isReady ? '取消准备' : '准备';
    readyBtn.className = 'btn btn-block ' + (STATE.isReady ? 'btn-warning' : 'btn-success');

    if (STATE.isHost) {
        startGameBtn.classList.remove('hidden');
        var allReady = room.players.every(function (x) { return x.isReady; });
        startGameBtn.disabled = !allReady || room.players.length < 2;
        startGameBtn.textContent = '开始游戏 (' + room.players.length + '人)';
    } else {
        startGameBtn.classList.add('hidden');
    }

    if (room.players.length < 2) {
        roomHint.textContent = '至少需要 2 名玩家才能开始游戏';
    } else if (STATE.isHost && room.players.some(function (p) { return !p.isReady; })) {
        roomHint.textContent = '等待所有玩家准备...';
    } else if (STATE.isHost) {
        roomHint.textContent = '全员已准备，可以开始游戏！';
    } else {
        roomHint.textContent = '等待房主开始游戏';
    }
    status(roomStatus, '房间内有 ' + room.players.length + ' 名玩家', 'info');
}

// ============================================================
//  工具
// ============================================================
function escHtml(str) {
    var div = document.createElement('div');
    div.textContent = str || '';
    return div.innerHTML;
}

// ============================================================
//  事件绑定
// ============================================================

// 登录
loginBtn.addEventListener('click', function () {
    var name = nameInput.value.trim();
    if (!name) { status(loginStatus, '请输入昵称', 'error'); return; }
    localStorage.setItem(STORAGE_KEY, name);
    STATE.playerName = name;
    modePlayerName.textContent = name;
    showScreen(modeSelectScreen);
});
nameInput.addEventListener('keydown', function (e) { if (e.key === 'Enter') loginBtn.click(); });

// 返回登录（模式选择页）
backToLoginBtn.addEventListener('click', function () {
    showScreen(loginScreen);
    nameInput.focus();
});

// 单机模式（暂定，留空）
singleModeBtn.addEventListener('click', function () {
    showInfo('单机模式正在开发中，敬请期待~');
});

// 联机模式 → 连接 WebSocket 进入大厅
multiModeBtn.addEventListener('click', function () {
    var name = STATE.playerName;
    if (!name) { showError('昵称丢失，请重新登录'); return; }
    status(loginStatus, '正在连接服务器...', 'info');
    multiModeBtn.disabled = true;
    connectWebSocket(name);
});

// 创建房间
createRoomBtn.addEventListener('click', function () {
    var name = roomNameInput.value.trim() || STATE.playerName + '的房间';
    sendMsg({ type: 'CREATE_ROOM', roomName: name, maxPlayers: 8 });
    roomNameInput.value = '';
});
roomNameInput.addEventListener('keydown', function (e) { if (e.key === 'Enter') createRoomBtn.click(); });

// 按 ID 加入
joinByIdBtn.addEventListener('click', function () {
    var roomId = roomIdInput.value.trim();
    if (!roomId) { showError('请输入房间ID'); return; }
    sendMsg({ type: 'JOIN_ROOM', roomId: roomId });
    roomIdInput.value = '';
});
roomIdInput.addEventListener('keydown', function (e) { if (e.key === 'Enter') joinByIdBtn.click(); });

// 准备
readyBtn.addEventListener('click', function () {
    sendMsg({ type: 'PLAYER_READY', ready: !STATE.isReady });
});

// 开始游戏
startGameBtn.addEventListener('click', function () {
    sendMsg({ type: 'START_GAME' });
});

// 离开房间
leaveRoomBtn.addEventListener('click', function () {
    sendMsg({ type: 'LEAVE_ROOM' });
});

// 返回大厅
backToLobbyBtn.addEventListener('click', function () {
    if (!STATE.game.started) return;
    sendMsg({ type: 'LEAVE_ROOM' });
});

// 游戏结束弹窗确认
gameOverOkBtn.addEventListener('click', function () {
    gameOverOverlay.classList.add('hidden');
    STATE.game.started = false;
    STATE.game.players = [];
    STATE.game.myPrivateInfo = {};
    showScreen(lobbyScreen);
    showInfo('游戏已结束，返回大厅');
    sendMsg({ type: 'LIST_ROOMS' });
});

// ============================================================
//  初始化
// ============================================================
(function init() {
    adaptScreenSize();
    var savedName = localStorage.getItem(STORAGE_KEY);
    if (savedName) {
        nameInput.value = savedName;
        modePlayerName.textContent = savedName;
        STATE.playerName = savedName;
        showScreen(modeSelectScreen);
    } else {
        nameInput.focus();
    }
})();

console.log('三国杀 · 桌面对局界面已加载');