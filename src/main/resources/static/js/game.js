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

    /** 单机模式待发送配置（连接成功后发送） */
    _pendingSinglePlayerConfig: null,

    /** 是否为主动断开（不显示重定向） */
    _intentionalDisconnect: false,

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

// 单机模式 · 房间设置弹窗 DOM
const singleRoomOverlay     = $('singleRoomOverlay');
const singleRoomCancelBtn   = $('singleRoomCancelBtn');
const singleRoomConfirmBtn  = $('singleRoomConfirmBtn');
const playerCountGroup      = $('playerCountGroup');
const identityConfigGroup   = $('identityConfigGroup');
const doubleIntruderOption  = $('doubleIntruderOption');
const summaryLine           = $('summaryLine');
const summaryDetail         = $('summaryDetail');

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
/**
 * 向日志区追加一条记录
 * @param {string} text  日志文本
 * @param {string} type  类型: info / action / damage / heal / system / highlight
 */
function addLog(text, type) {
    if (!logContent) return;
    type = type || 'info';
    var cls = LOG_CLASSES[type] || 'log-info';
    var div = document.createElement('div');
    div.className = 'log-entry ' + cls;
    div.textContent = text;
    logContent.appendChild(div);
    // 自动滚动到底部
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
    pcCharName.textContent = me ? (me.charName || '卡斯奥佩娅') : '';

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

    // --- 技能按钮（Bot 玩家不加载） ---
    if (me && me.bot) {
        // Bot 玩家：隐藏技能栏
        var skillBarEl = document.getElementById('skillBar') || document.querySelector('.skill-bar');
        if (skillBarEl) skillBarEl.style.display = 'none';
    } else {
        renderSkills(me);
    }
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

/** 渲染对手（其他玩家）分布在上、左、右三区 */
function calcOpponentDistribution(totalPlayers) {
    // 返回值: { top, left, right } 对应各区域对手数量
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

    // 非人类玩家（去掉自身）
    var others = players.filter(function (p) { return p.playerId !== STATE.playerId; });
    var dist = calcOpponentDistribution(players.length);
    var idx = 0;

    // 填充顺序：上 → 左 → 右
    function createCard(player) {
        var card = document.createElement('div');
        card.className = 'opp-card';
        card.dataset.seat = player.gameSeat;

        var infoDiv = document.createElement('div');
        infoDiv.className = 'oppc-info';
        var nameDiv = document.createElement('div');
        nameDiv.className = 'oppc-char-name';
        // 左侧竖排显示武将名称
        nameDiv.textContent = player.charName || player.playerName;
        infoDiv.appendChild(nameDiv);

        var hpArea = document.createElement('div');
        hpArea.className = 'oppc-hp-area';
        var maxHp = player.maxHp || 4;
        var curHp = player.currentHp != null ? player.currentHp : maxHp;
        for (var h = 0; h < maxHp; h++) {
            var dot = document.createElement('div');
            dot.className = 'oppc-hp-dot' + (h < curHp ? ' alive' : ' lost');
            hpArea.appendChild(dot);
        }
        infoDiv.appendChild(hpArea);
        card.appendChild(infoDiv);

        var genCol = document.createElement('div');
        genCol.className = 'oppc-general-col';
        var genDiv = document.createElement('div');
        genDiv.className = 'oppc-general';

        var img = document.createElement('img');
        img.className = 'oppc-general-img';
        img.src = '/images/lol_EZ.png';
        img.alt = '武将';
        genDiv.appendChild(img);

        var nameOverlay = document.createElement('span');
        nameOverlay.className = 'oppc-name-overlay';
        // 真人→显示玩家名；机器人→显示武将名
        nameOverlay.textContent = player.bot
            ? (player.charName || player.playerName)
            : player.playerName;
        genDiv.appendChild(nameOverlay);

        var roleOverlay = document.createElement('span');
        roleOverlay.className = 'oppc-role-overlay';
        roleOverlay.textContent = ROLE_SHORT_NAMES[player.role] || '?';
        genDiv.appendChild(roleOverlay);

        var seatNum = document.createElement('span');
        seatNum.className = 'oppc-seat-num';
        seatNum.textContent = CN_NUMS[player.gameSeat + 1] || (player.gameSeat + 1);
        genDiv.appendChild(seatNum);

        // 牌堆按钮（上/旁）
        var deckBtns = document.createElement('div');
        deckBtns.className = 'oppc-deck-btns';
        var aboveBtn = document.createElement('button');
        aboveBtn.className = 'oppc-deck-btn';
        aboveBtn.textContent = '上';
        deckBtns.appendChild(aboveBtn);
        var sideBtn = document.createElement('button');
        sideBtn.className = 'oppc-deck-btn';
        sideBtn.textContent = '旁';
        deckBtns.appendChild(sideBtn);
        genDiv.appendChild(deckBtns);

        genCol.appendChild(genDiv);
        card.appendChild(genCol);
        return card;
    }

    // top row
    for (var t = 0; t < dist.top && idx < others.length; t++, idx++) {
        oppTop.appendChild(createCard(others[idx]));
    }
    // left column
    for (var l = 0; l < dist.left && idx < others.length; l++, idx++) {
        oppLeft.appendChild(createCard(others[idx]));
    }
    // right column
    for (var r = 0; r < dist.right && idx < others.length; r++, idx++) {
        oppRight.appendChild(createCard(others[idx]));
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
    // 切换主屏时自动关闭单机设置弹窗
    singleRoomOverlay.classList.add('hidden');
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
        // 如果是主动断开（如返回模式选择），不跳转到登录页
        if (STATE._intentionalDisconnect) {
            STATE._intentionalDisconnect = false;
            return;
        }
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
            // 如果有待发送的单机配置，直接启动单机游戏
            if (STATE._pendingSinglePlayerConfig) {
                var cfg = STATE._pendingSinglePlayerConfig;
                STATE._pendingSinglePlayerConfig = null;
                sendMsg({
                    type: 'START_SINGLE_PLAYER',
                    totalPlayers: cfg.totalPlayers,
                    identityConfig: cfg.config,
                });
            } else {
                showSuccess('以"' + msg.playerName + '"身份进入大厅');
                showScreen(lobbyScreen);
            }
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

            // 判断当前玩家是否为 Bot — Bot 玩家不需要加载交互界面
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
                // Bot 玩家：隐藏手牌区、技能栏、装备栏等交互UI
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
            var roleName = ROLE_NAMES[msg.role] || msg.role;
            var roleShort = ROLE_SHORT_NAMES[msg.role] || msg.role;
            var totalPlayers = STATE.game.players.length;

            // 首次收到身份信息时初始化日志
            if (logContent && !STATE.game._logInited) {
                STATE.game._logInited = true;
                logContent.innerHTML = '';
                addLog('══════ 游戏开始 ══════', 'highlight');
                addLog(totalPlayers + '人局', 'system');
                addLog('你的身份：' + roleShort, 'info');
                addLog('等待你的第一个回合...', 'system');
            }

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

// ============================================================
//  单机模式 · 身份配置逻辑
// ============================================================

/** 角色中文名映射（仅用于前端展示） */
const ROLE_SHORT_NAMES = {
    LORD:     '主公',
    MINION:   '忠臣',
    REBEL:    '反贼',
    INTRUDER: '内奸',
};

/** 身份配置名称映射 */
const CONFIG_NAMES = {
    standard:        '标准身份',
    double_intruder: '双内模式',
};

/** 更新摘要显示（纯展示层，身份分配由后端计算） */
function updateSummary() {
    var countEl = playerCountGroup.querySelector('.active');
    var configEl = identityConfigGroup.querySelector('.active');
    var totalPlayers = countEl ? parseInt(countEl.dataset.count, 10) : 6;
    var config = configEl ? configEl.dataset.config : 'standard';
    summaryLine.textContent = '当前配置：' + totalPlayers + '人局 · ' + (CONFIG_NAMES[config] || config);
    summaryDetail.textContent = '身份将由服务器分配';
}

// 单机模式按钮 → 弹出房间设置
singleModeBtn.addEventListener('click', function () {
    singleRoomOverlay.classList.remove('hidden');
});

// 单机模式 · 取消
singleRoomCancelBtn.addEventListener('click', function () {
    singleRoomOverlay.classList.add('hidden');
});

// 点击遮罩层也关闭
singleRoomOverlay.addEventListener('click', function (e) {
    if (e.target === singleRoomOverlay) {
        singleRoomOverlay.classList.add('hidden');
    }
});

// 人数选择切换
playerCountGroup.addEventListener('click', function (e) {
    var btn = e.target.closest('.count-btn');
    if (!btn) return;
    playerCountGroup.querySelectorAll('.count-btn').forEach(function (b) { b.classList.remove('active'); });
    btn.classList.add('active');

    var totalPlayers = parseInt(btn.dataset.count, 10);

    // 双内模式仅对 8 人局可见
    if (totalPlayers === 8) {
        doubleIntruderOption.classList.remove('hidden');
    } else {
        doubleIntruderOption.classList.add('hidden');
        // 如果当前选中了双内模式，切回标准模式
        if (doubleIntruderOption.classList.contains('active')) {
            doubleIntruderOption.classList.remove('active');
            identityConfigGroup.querySelector('[data-config="standard"]').classList.add('active');
        }
    }

    updateSummary();
});

// 身份配置切换
identityConfigGroup.addEventListener('click', function (e) {
    var option = e.target.closest('.identity-option');
    if (!option) return;
    identityConfigGroup.querySelectorAll('.identity-option').forEach(function (o) { o.classList.remove('active'); });
    option.classList.add('active');
    updateSummary();
});

// 单机模式 · 确认开始 → 通过 WebSocket 连接后端启动
singleRoomConfirmBtn.addEventListener('click', function () {
    var countEl = playerCountGroup.querySelector('.active');
    var configEl = identityConfigGroup.querySelector('.active');
    if (!countEl || !configEl) return;

    var totalPlayers = parseInt(countEl.dataset.count, 10);
    var config = configEl.dataset.config;

    singleRoomOverlay.classList.add('hidden');

    // 保存配置，连接成功后自动发送
    STATE._pendingSinglePlayerConfig = { totalPlayers: totalPlayers, config: config };

    if (!STATE.ws || STATE.ws.readyState !== WebSocket.OPEN) {
        connectWebSocket(STATE.playerName);
    } else {
        // 已连接，直接发送
        var cfg = STATE._pendingSinglePlayerConfig;
        STATE._pendingSinglePlayerConfig = null;
        sendMsg({
            type: 'START_SINGLE_PLAYER',
            totalPlayers: cfg.totalPlayers,
            identityConfig: cfg.config,
        });
    }
});

/**
 * 启动单机游戏（本地 AI 对局）
 * 目前：已废弃，改用 WebSocket + 后端 GameServiceImpl.startSinglePlayer()
 */

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

// 联机大厅 · 返回模式选择页
const lobbyBackBtn = $('lobbyBackBtn');
lobbyBackBtn.addEventListener('click', function () {
    // 标记为主动断开，防止 onclose 跳转到登录页
    STATE._intentionalDisconnect = true;
    if (STATE.ws) {
        STATE.ws.close();
        STATE.ws = null;
    }
    STATE.currentRoom = null;
    showScreen(modeSelectScreen);
});

// 离开房间
leaveRoomBtn.addEventListener('click', function () {
    sendMsg({ type: 'LEAVE_ROOM' });
});

// 返回（对局界面）— 根据模式不同回到不同页面
backToLobbyBtn.addEventListener('click', function () {
    if (!STATE.game.started) return;

    // 单机模式 → 回到模式选择页
    var isSinglePlayer = STATE.currentRoom && STATE.currentRoom.roomId && STATE.currentRoom.roomId.indexOf('single_') === 0;
    if (isSinglePlayer) {
        STATE.game.started = false;
        STATE.game.players = [];
        STATE.game.myPrivateInfo = {};
        STATE.currentRoom = null;
        showScreen(modeSelectScreen);
        return;
    }

    // 联机模式 → 回到大厅（通过 WebSocket 离开房间，服务端会发 ROOM_LEFT）
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