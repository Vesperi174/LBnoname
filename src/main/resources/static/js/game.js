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
const ROLE_SHORT_NAMES = {
    LORD:     '主公',
    MINION:   '忠臣',
    REBEL:    '反贼',
    INTRUDER: '内奸',
};

// 势力底色
const KINGDOM_COLORS = {
    1:   '#b22222',
    2:   '#2a4a7f',
    3:   '#2d7d46',
    4:   '#5a5a5a',
    101: '#2e8b57',
    102: '#8b1a1a',
    103: '#5b8cac',
    104: '#c8a84e',
    105: '#a0785a',
    106: '#4a2c5a',
    107: '#6b8e5a',
    108: '#8a7a4a',
    109: '#3a6b8a',
    110: '#7a5a3a',
    111: '#5a7a4a',
    112: '#6a5a3a',
};
const DEFAULT_KINGDOM_COLOR = '#3a3a3a';

// ============================================================
//  DOM 引用
// ============================================================
const $ = id => document.getElementById(id);
const loginScreen       = $('loginScreen');
const homeScreen        = $('homeScreen');
const gameScreen        = $('gameScreen');
const gameOverOverlay   = $('gameOverOverlay');
const nameInput         = $('nameInput');
const loginBtn          = $('loginBtn');
const loginStatus       = $('loginStatus');
const homePlayerName    = $('homePlayerName');

// 侧边栏 DOM
const selfAvatar        = $('selfAvatar');
const selfName          = $('selfName');
const selfStatus        = $('selfStatus');
const onlineCount       = $('onlineCount');
const playerList        = $('playerList');
const roomList          = $('roomList');

// 游戏结束弹窗 DOM
const gameOverTitle     = $('gameOverTitle');
const gameOverDesc      = $('gameOverDesc');
const gameOverOkBtn     = $('gameOverOkBtn');
const createRoomBtn     = $('createRoomBtn');
const roomScreen        = $('roomScreen');
const roomTitle         = $('roomTitle');
const roomSelfAvatar    = $('roomSelfAvatar');
const roomSelfName      = $('roomSelfName');
const roomSelfStatus    = $('roomSelfStatus');
const roomOnlineCount   = $('roomOnlineCount');
const roomPlayerList    = $('roomPlayerList');
const roomLeaveBtn      = $('roomLeaveBtn');
const roomNameOverlay   = $('roomNameOverlay');
const roomNameInput     = $('roomNameInput');
const roomNameConfirmBtn = $('roomNameConfirmBtn');
const roomNameCancelBtn  = $('roomNameCancelBtn');
const joinRoomOverlay    = $('joinRoomOverlay');
const joinRoomHint       = $('joinRoomHint');
const joinRoomConfirmBtn = $('joinRoomConfirmBtn');
const joinRoomCancelBtn  = $('joinRoomCancelBtn');

// 对局 UI
const backToHomeBtn     = $('backToHomeBtn');
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
const logContent        = $('logContent');

// ============================================================
//  日志工具
// ============================================================
var LOG_CLASSES = {
    info:      'log-info',
    action:    'log-action',
    damage:    'log-damage',
    heal:      'log-heal',
    system:    'log-system',
    highlight: 'log-highlight',
};
function addLog(text, type) {
    if (!logContent) return;
    type = type || 'info';
    var cls = LOG_CLASSES[type] || 'log-info';
    var div = document.createElement('div');
    div.className = 'log-entry ' + cls;
    div.textContent = text;
    logContent.appendChild(div);
    logContent.scrollTop = logContent.scrollHeight;
}

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

    if (me) {
        pcNameOverlay.textContent = me.playerName + (me.bot ? ' (AI)' : '');
        var roleName = ROLE_NAMES[me.role] || me.role || '?';
        pcRoleOverlay.textContent = roleName;
    } else {
        pcNameOverlay.textContent = '等待中';
        pcRoleOverlay.textContent = '?';
    }

    if (me) {
        var seat = me.gameSeat !== undefined ? me.gameSeat + 1 : 1;
        pcSeatNum.textContent = CN_NUMS[seat] || seat;
    } else {
        pcSeatNum.textContent = '零';
    }

    if (me && me.kingdom != null) {
        var color = KINGDOM_COLORS[me.kingdom] || DEFAULT_KINGDOM_COLOR;
        pcInfo.style.backgroundColor = color;
    } else {
        pcInfo.style.backgroundColor = DEFAULT_KINGDOM_COLOR;
    }

    pcCharName.textContent = me ? (me.charName || '卡斯奥佩娅') : '';

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

    requestAnimationFrame(fitPlayerCard);

    if (me && me.bot) {
        var skillBarEl = document.getElementById('skillBar') || document.querySelector('.skill-bar');
        if (skillBarEl) skillBarEl.style.display = 'none';
    } else {
        renderSkills(me);
    }
}

/** 更新技能按钮 */
function renderSkills(me) {
    var skills = me
        ? ['霸体', '神威', '极意', '无双', '天崩', '地裂', '涅槃', '鬼谋']
        : [];

    var count = skills.length;
    var children = skillBar.children;

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

/** 渲染对手分布 */
function calcOpponentDistribution(totalPlayers) {
    switch (totalPlayers) {
        case 2: return { top: 1, left: 0, right: 0 };
        case 3: return { top: 0, left: 1, right: 1 };
        case 4: return { top: 1, left: 1, right: 1 };
        case 5: return { top: 2, left: 1, right: 1 };
        case 6: return { top: 3, left: 1, right: 1 };
        case 7: return { top: 3, left: 2, right: 1 };
        case 8: return { top: 3, left: 2, right: 2 };
        default: return { top: 1, left: 0, right: 0 };
    }
}
function renderOpponents(players) {
    var oppTop    = $('oppTop');
    var oppLeft   = $('oppLeft');
    var oppRight  = $('oppRight');
    if (!oppTop) return;
    oppTop.innerHTML = '';
    oppLeft.innerHTML = '';
    oppRight.innerHTML = '';

    var others = players.filter(function (p) { return p.playerId !== STATE.playerId; });
    var dist = calcOpponentDistribution(players.length);
    var idx = 0;

    function createCard(player) {
        var card = document.createElement('div');
        card.className = 'opp-card';
        card.dataset.seat = player.gameSeat;

        var infoDiv = document.createElement('div');
        infoDiv.className = 'oppc-info';
        var nameDiv = document.createElement('div');
        nameDiv.className = 'oppc-char-name';
        nameDiv.textContent = player.charName || player.playerName;
        infoDiv.appendChild(nameDiv);

        var hpArea = document.createElement('div');
        hpArea.className = 'oppc-hp-area';
        var maxHp = player.maxHp || 4;
        var curHp = player.currentHp != null ? player.currentHp : maxHp;
        for (var i = 0; i < maxHp; i++) {
            var dot = document.createElement('div');
            dot.className = 'oppc-hp-dot ' + (i < curHp ? 'alive' : 'lost');
            hpArea.appendChild(dot);
        }
        infoDiv.appendChild(hpArea);

        if (player.kingdom != null) {
            var kingdomColor = KINGDOM_COLORS[player.kingdom] || DEFAULT_KINGDOM_COLOR;
            infoDiv.style.backgroundColor = kingdomColor;
        } else {
            infoDiv.style.backgroundColor = DEFAULT_KINGDOM_COLOR;
        }

        var genCol = document.createElement('div');
        genCol.className = 'oppc-general-col';
        var genDiv = document.createElement('div');
        genDiv.className = 'oppc-general';

        var img = document.createElement('img');
        img.className = 'oppc-general-img';
        img.src = '/images/lol_EZ.png';
        img.alt = '武将';

        var nameOverlay = document.createElement('div');
        nameOverlay.className = 'oppc-name-overlay';
        nameOverlay.textContent = player.playerName + (player.bot ? ' (AI)' : '');

        var roleOverlay = document.createElement('div');
        roleOverlay.className = 'oppc-role-overlay';
        roleOverlay.textContent = ROLE_SHORT_NAMES[player.role] || player.role || '?';

        var seatNum = document.createElement('div');
        seatNum.className = 'oppc-seat-num';
        var seat = player.gameSeat !== undefined ? player.gameSeat + 1 : 0;
        seatNum.textContent = CN_NUMS[seat] || seat;

        genDiv.appendChild(img);
        genDiv.appendChild(nameOverlay);
        genDiv.appendChild(roleOverlay);
        genDiv.appendChild(seatNum);
        genCol.appendChild(genDiv);

        card.appendChild(infoDiv);
        card.appendChild(genCol);
        return card;
    }

    var topCount = dist.top;
    for (var t = 0; t < topCount && idx < others.length; t++, idx++) {
        oppTop.appendChild(createCard(others[idx]));
    }
    for (var l = 0; l < dist.left && idx < others.length; l++, idx++) {
        oppLeft.appendChild(createCard(others[idx]));
    }
    for (var r = 0; r < dist.right && idx < others.length; r++, idx++) {
        oppRight.appendChild(createCard(others[idx]));
    }
}

// 装备渲染
function renderEquipment() {
    if (!eqRows) return;
    var eq = STATE.game.myEquipment || {};
    eqRows.innerHTML = '';
    EQUIP_CONFIG.forEach(function (cfg) {
        var row = document.createElement('div');
        row.className = 'eq-row';
        var label = document.createElement('span');
        label.className = 'eq-label';
        label.textContent = cfg.label + ':';
        row.appendChild(label);
        var nameSpan = document.createElement('span');
        nameSpan.className = 'eq-card-name';
        var card = eq[cfg.key];
        if (card && card.name) {
            nameSpan.textContent = card.name;
            nameSpan.classList.remove('empty');
        } else {
            nameSpan.textContent = cfg.placeholder || '无';
            nameSpan.classList.add('empty');
        }
        row.appendChild(nameSpan);
        eqRows.appendChild(row);
    });
}

// 武将卡自适应字号
function fitPlayerCard() {
    var charEl = document.querySelector('.pc-char-name');
    if (!charEl || !charEl.textContent) return;
    var parent = charEl.closest('.pc-info');
    if (!parent) return;
    var parentH = parent.clientHeight || 135;
    var hpH = pcHpArea.clientHeight || 0;
    var pad = 16;
    var available = parentH - hpH - pad;
    if (available < 15) available = 15;
    var len = charEl.textContent.length;
    if (len === 0) return;
    var size = Math.floor(available / (len * 1.2));
    size = Math.max(8, Math.min(24, size));
    charEl.style.fontSize = size + 'px';

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
//  侧边栏 - 在线玩家渲染
// ============================================================
function renderOnlinePlayers(players, count) {
    var total = count || (players ? players.length : 0);
    // 同时更新主页和房间界面的侧边栏
    onlineCount.textContent = total;
    roomOnlineCount.textContent = total;

    var listEl = playerList;
    var roomListEl = roomPlayerList;
    if (!players) return;

    // 找到自己在列表中的状态并更新
    var me = null;
    for (var i = 0; i < players.length; i++) {
        if (players[i].playerId === STATE.playerId) {
            me = players[i];
            break;
        }
    }
    if (me) {
        var myStatusClass = 'status-' + (me.status || 'online').toLowerCase();
        var myStatusText = statusLabel(me.status);
        selfStatus.className = 'sidebar-status ' + myStatusClass;
        selfStatus.textContent = myStatusText;
        roomSelfStatus.className = 'sidebar-status ' + myStatusClass;
        roomSelfStatus.textContent = myStatusText;
    }

    // 过滤掉自己（已经在顶部显示）
    var others = players.filter(function (p) { return p.playerId !== STATE.playerId; });

    var html = '';
    for (var i = 0; i < others.length; i++) {
        var p = others[i];
        var initial = p.playerName ? p.playerName.charAt(0).toUpperCase() : '?';
        var statusClass = 'status-' + (p.status || 'online').toLowerCase();
        var statusText = statusLabel(p.status);
        html += '<div class="sidebar-player-item">'
              +   '<div class="sidebar-avatar">' + escHtml(initial) + '</div>'
              +   '<div class="sidebar-info">'
              +     '<span class="sidebar-name">' + escHtml(p.playerName) + '</span>'
              +     '<span class="sidebar-status ' + statusClass + '">' + statusText + '</span>'
              +   '</div>'
              + '</div>';
    }

    if (listEl) listEl.innerHTML = html;
    if (roomListEl) roomListEl.innerHTML = html;
}

function statusLabel(status) {
    switch ((status || '').toLowerCase()) {
        case 'online':  return '在线';
        case 'in_room': return '房间中';
        case 'in_game': return '对局中';
        default:        return '未知';
    }
}

// ============================================================
//  房间列表渲染
// ============================================================
function renderRoomList(rooms) {
    if (!roomList) return;
    if (!rooms || rooms.length === 0) {
        roomList.innerHTML = '<div class="room-empty">暂无房间</div>';
        return;
    }
    var html = '';
    for (var i = 0; i < rooms.length; i++) {
        var r = rooms[i];
        var ownerName = '';
        if (r.players) {
            for (var j = 0; j < r.players.length; j++) {
                if (r.players[j].playerId === r.ownerPlayerId) {
                    ownerName = r.players[j].playerName;
                    break;
                }
            }
        }
        if (!ownerName) ownerName = '未知';

        var statusText = (r.status === 'PLAYING') ? '对局中' : '等待中';
        var statusClass = (r.status === 'PLAYING') ? 'room-status-playing' : 'room-status-waiting';

        html += '<div class="room-item" data-room-id="' + r.roomId + '">'
              +   '<span class="room-name">' + escHtml(r.roomName) + '</span>'
              +   '<span class="room-meta">' + escHtml(ownerName) + ' · ' + (r.playerCount || 0) + '/' + (r.maxPlayers || 0) + '</span>'
              +   '<span class="room-status ' + statusClass + '">' + statusText + '</span>'
              + '</div>';
    }
    roomList.innerHTML = html;
}

// ============================================================
//  辅助函数
// ============================================================
const containerEl = document.querySelector('.container');

function adaptScreenSize() {
    var w = window.innerWidth;
    var h = window.innerHeight;
    document.documentElement.style.setProperty('--vw', w + 'px');
    document.documentElement.style.setProperty('--vh', h + 'px');
    document.documentElement.style.setProperty('--game-min-dim', Math.min(w, h) + 'px');
}
window.addEventListener('resize', adaptScreenSize);

function showScreen(screen) {
    [loginScreen, homeScreen, gameScreen, roomScreen].forEach(function (s) { s.classList.add('hidden'); });
    screen.classList.remove('hidden');
    containerEl.classList.toggle('game-active', screen === gameScreen);
    containerEl.classList.toggle('home-active', screen === homeScreen);
    containerEl.classList.toggle('room-active', screen === roomScreen);
    if (screen === gameScreen) adaptScreenSize();
}

function status(el, msg, type) {
    if (!type) type = 'info';
    el.innerHTML = '<span class="' + type + '">' + msg + '</span>';
}

function showError(msg)   { status(loginStatus, msg, 'error'); }
function showInfo(msg)    { status(loginStatus, msg, 'info'); }
function showSuccess(msg) { status(loginStatus, msg, 'success'); }

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
        setTimeout(function () {
            showScreen(loginScreen);
            status(loginStatus, '连接已断开，刷新页面重试', 'error');
        }, 500);
    };

    ws.onerror = function () {
        showError('连接错误，请确认服务端已启动');
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
            homePlayerName.textContent = msg.playerName;
            // 侧边栏 - 自己（主页和房间界面共用）
            selfAvatar.textContent = msg.playerName.charAt(0).toUpperCase();
            selfName.textContent = msg.playerName;
            roomSelfAvatar.textContent = msg.playerName.charAt(0).toUpperCase();
            roomSelfName.textContent = msg.playerName;
            showScreen(homeScreen);
            showInfo('已连接服务器');
            break;

        case 'ONLINE_PLAYERS':
            renderOnlinePlayers(msg.players, msg.count);
            break;

        case 'ROOM_LIST':
            renderRoomList(msg.rooms || []);
            break;

        case 'ROOM_CREATED':
            var room = msg.room || {};
            roomTitle.textContent = room.roomName || (STATE.playerName + '的房间');
            roomSelfAvatar.textContent = STATE.playerName.charAt(0).toUpperCase();
            roomSelfName.textContent = STATE.playerName;
            // 立即将状态设为"房间中"，不等 ONLINE_PLAYERS 广播
            selfStatus.className = 'sidebar-status status-in_room';
            selfStatus.textContent = '房间中';
            roomSelfStatus.className = 'sidebar-status status-in_room';
            roomSelfStatus.textContent = '房间中';
            showScreen(roomScreen);
            break;

        case 'ROOM_JOINED':
            var joinedRoom = msg.room || {};
            roomTitle.textContent = joinedRoom.roomName || '房间';
            roomSelfAvatar.textContent = STATE.playerName.charAt(0).toUpperCase();
            roomSelfName.textContent = STATE.playerName;
            // 立即将状态设为"房间中"
            selfStatus.className = 'sidebar-status status-in_room';
            selfStatus.textContent = '房间中';
            roomSelfStatus.className = 'sidebar-status status-in_room';
            roomSelfStatus.textContent = '房间中';
            showScreen(roomScreen);
            break;

        case 'OWNER_CHANGED':
            showInfo('新房主：' + (msg.newOwnerName || '未知'));
            break;

        // ==================== 游戏事件 ====================
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

            var me = players.find(function (p) { return p.playerId === STATE.playerId; });
            var isBot = me && me.bot === true;

            updateTopBar();
            renderPlayerCard();
            renderOpponents(players);
            if (!isBot) {
                renderEquipment();
                document.getElementById('handCards') && (document.getElementById('handCards').style.display = '');
                document.getElementById('skillBar') && (document.getElementById('skillBar').style.display = '');
            } else {
                if (document.getElementById('handCards')) {
                    document.getElementById('handCards').style.display = 'none';
                }
                var skillBarEl = document.getElementById('skillBar') || document.querySelector('.skill-bar');
                if (skillBarEl) skillBarEl.style.display = 'none';
                var eqArea = document.querySelector('.eq-area');
                if (eqArea) eqArea.style.display = 'none';
                console.log('当前为 Bot 玩家，跳过交互界面加载');
            }
            console.log('游戏开始，共 ' + players.length + ' 名玩家');
            break;

        case 'YOUR_PRIVATE_INFO':
            STATE.game.myPrivateInfo = msg;
            var myIdx = STATE.game.players.findIndex(function (p) { return p.playerId === msg.playerId; });
            if (myIdx >= 0) {
                STATE.game.players[myIdx].role = msg.role;
            }
            renderPlayerCard();
            var roleShort = ROLE_SHORT_NAMES[msg.role] || msg.role;
            var totalPlayers = STATE.game.players.length;

            if (logContent && !STATE.game._logInited) {
                STATE.game._logInited = true;
                logContent.innerHTML = '';
                addLog('══════ 游戏开始 ══════', 'highlight');
                addLog(totalPlayers + '人局', 'system');
                addLog('你的身份：' + roleShort, 'info');
                addLog('等待你的第一个回合...', 'system');
            }

            console.log('你的身份：' + (ROLE_NAMES[msg.role] || msg.role) + '，手牌数：' + msg.handCardCount);
            break;

        case 'ROUND_CHANGE':
            STATE.game.round = msg.round;
            updateTopBar();
            renderPlayerCard();
            break;

        case 'TURN_START':
            STATE.game.currentPlayerIndex = msg.gameSeat;
            STATE.game.phase = msg.phase || 'PREPARE';
            STATE.game.totalTurns = msg.totalTurns || STATE.game.totalTurns;
            STATE.game.round = msg.round || STATE.game.round;
            updateTopBar();
            renderPlayerCard();
            break;

        case 'PHASE_CHANGE':
            STATE.game.phase = msg.toPhase;
            updateTopBar();
            renderPlayerCard();
            break;

        case 'FIELD_CARDS':
            STATE.game.fieldCards = msg.cards || [];
            break;

        case 'MY_HAND':
            STATE.game.myHandCards = msg.cards || [];
            break;

        case 'MY_EQUIPMENT':
            STATE.game.myEquipment = msg.equipment || STATE.game.myEquipment;
            renderEquipment();
            break;

        case 'PLAYER_UPDATE':
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

        case 'GAME_OVER':
            if (msg.winnerRole === 'NONE') {
                STATE.game.started = false;
                STATE.game.players = [];
                STATE.game.myPrivateInfo = {};
                showScreen(homeScreen);
                showInfo('对局已销毁');
                break;
            }
            var winnerDesc = msg.winnerDesc || msg.winnerRole || '未知';
            STATE.game.started = false;
            updateTopBar();
            renderPlayerCard();
            showGameOver(winnerDesc);
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
    if (!name) { showError('请输入昵称'); return; }
    localStorage.setItem(STORAGE_KEY, name);
    STATE.playerName = name;
    showInfo('正在连接服务器...');
    loginBtn.disabled = true;
    connectWebSocket(name);
});
nameInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') loginBtn.click();
});

// 对局界面 → 返回主页
backToHomeBtn.addEventListener('click', function () {
    if (!STATE.game.started) return;
    STATE.game.started = false;
    STATE.game.players = [];
    STATE.game.myPrivateInfo = {};
    STATE.game._logInited = false;
    if (STATE.ws) {
        STATE.ws.close();
        STATE.ws = null;
    }
    showScreen(homeScreen);
});

// 游戏结束弹窗确认
gameOverOkBtn.addEventListener('click', function () {
    gameOverOverlay.classList.add('hidden');
    STATE.game.started = false;
    STATE.game.players = [];
    STATE.game.myPrivateInfo = {};
    STATE.game._logInited = false;
    if (STATE.ws) {
        STATE.ws.close();
        STATE.ws = null;
    }
    showScreen(homeScreen);
    showInfo('游戏已结束，返回主页');
});

// 创建房间 → 输入名称 → 发送到服务器
createRoomBtn.addEventListener('click', function () {
    roomNameInput.value = STATE.playerName + '的房间';
    roomNameOverlay.classList.remove('hidden');
    roomNameInput.focus();
    roomNameInput.select();
});
function doCreateRoom() {
    var name = roomNameInput.value.trim();
    if (!name) { name = STATE.playerName + '的房间'; }
    roomNameOverlay.classList.add('hidden');
    // 发送创建房间请求到服务器
    sendMsg({ type: 'CREATE_ROOM', roomName: name });
    showInfo('正在创建房间...');
}
roomNameConfirmBtn.addEventListener('click', doCreateRoom);
roomNameCancelBtn.addEventListener('click', function () {
    roomNameOverlay.classList.add('hidden');
});
roomNameInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { doCreateRoom(); }
});
roomLeaveBtn.addEventListener('click', function () {
    // 通知服务器离开房间
    sendMsg({ type: 'LEAVE_ROOM' });
    // 立即将状态恢复为"在线"
    selfStatus.className = 'sidebar-status status-online';
    selfStatus.textContent = '在线';
    roomSelfStatus.className = 'sidebar-status status-online';
    roomSelfStatus.textContent = '在线';
    showScreen(homeScreen);
});

// ============================================================
//  加入房间
// ============================================================
/** 待加入的房间 ID */
var pendingJoinRoomId = null;

// 事件委托：点击房间列表中的房间项
roomList.addEventListener('click', function (e) {
    var item = e.target.closest('.room-item');
    if (!item) return;
    var roomId = item.dataset.roomId;
    var nameEl = item.querySelector('.room-name');
    var roomName = nameEl ? nameEl.textContent : '未知房间';

    pendingJoinRoomId = roomId;
    joinRoomHint.textContent = '是否加入房间「' + roomName + '」？';
    joinRoomOverlay.classList.remove('hidden');
});

joinRoomConfirmBtn.addEventListener('click', function () {
    if (pendingJoinRoomId) {
        sendMsg({ type: 'JOIN_ROOM', roomId: pendingJoinRoomId });
        showInfo('正在加入房间...');
    }
    joinRoomOverlay.classList.add('hidden');
    pendingJoinRoomId = null;
});

joinRoomCancelBtn.addEventListener('click', function () {
    joinRoomOverlay.classList.add('hidden');
    pendingJoinRoomId = null;
});

// ============================================================
//  游戏结束弹窗
// ============================================================
function showGameOver(winnerDesc) {
    gameOverTitle.textContent = '游戏结束';
    gameOverDesc.textContent = '胜者阵营：' + winnerDesc;
    gameOverOverlay.classList.remove('hidden');
}

// ============================================================
//  初始化
// ============================================================
(function init() {
    adaptScreenSize();
    var savedName = localStorage.getItem(STORAGE_KEY);
    if (savedName) {
        nameInput.value = savedName;
        STATE.playerName = savedName;
    }
    nameInput.focus();
})();

console.log('三国杀 · 桌面对局界面已加载');