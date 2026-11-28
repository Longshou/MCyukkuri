import {categories, entryURL, loadEntries} from '/preview/review/entries.js';
import {mountVerdicts} from '/preview/review/verdicts.js';

const KIND_LABELS = {art: '对白与音画', motion: '动作与外观', playtest: '游戏内试玩'};
const THEME_RULES = [
    {id: 'life', label: '日常生活（饮食 / 睡眠 / 洗漱）', match: /feed|sleep|wake|eat|food|toilet|leak|wet|nuzzle|dialogue/i},
    {id: 'wild', label: '野外与天气（雷雨 / 迁徙 / 防护）', match: /rain|thunder|hat|kanroken|held|house/i},
    {id: 'family', label: '亲代、配对与繁殖', match: /family|pair|court|birth|hatch|child|stage3-social/i},
    {id: 'health', label: '受伤、惊吓与冲突', match: /fright|vomit|strike|scratch|rapem|sever|hurt/i},
];

function el(tag, props = {}, ...children) {
    const node = Object.assign(document.createElement(tag), props);
    node.append(...children);
    return node;
}

function resolveInboxUrl(url) {
    if (!url) return '#';
    if (url.startsWith('/') || url.startsWith('http')) return url;
    if (url.startsWith('../')) return './' + url.slice(3);
    return url;
}

function classifyTheme(id) {
    for (const rule of THEME_RULES) {
        if (rule.match.test(id)) return rule;
    }
    return {id: 'other', label: '其他完整场景'};
}

function groupBy(rows, keyFn) {
    const map = new Map();
    for (const row of rows) {
        const key = keyFn(row);
        if (!map.has(key)) map.set(key, []);
        map.get(key).push(row);
    }
    return map;
}

async function renderInbox(root) {
    const sections = [];

    // 1. Live in-game playtest scenes from /playtest
    const playtestSec = el('section', {className: 'kind-block', id: 'playtest-live'});
    const playtestCount = el('small', {textContent: '读取中…'});
    const playtestCards = el('div', {className: 'cards'});
    playtestSec.append(el('h2', {}, '游戏内试玩验收 ', playtestCount), playtestCards);
    sections.push(playtestSec);

    try {
        const res = await fetch('/playtest');
        const list = await res.json();
        if (!res.ok) throw new Error(list.error || 'HTTP ' + res.status);
        const runtime = (list.until || '').slice(0, 9);
        const byField = groupBy(list.items || [], row => row.scenes[0].field);
        const rank = name => (list.fields.includes(name) ? list.fields.indexOf(name) : list.fields.length);
        for (const name of [...byField.keys()].sort((a, b) => rank(a) - rank(b))) {
            playtestCards.append(el('h3', {className: 'field-heading', textContent: name}));
            for (const row of byField.get(name)) {
                const scene = row.scenes[0];
                const meta = el('div', {className: 'meta'},
                    el('span', {className: row.status === '复测失败' ? 'chip-status retest' : 'chip-status', textContent: row.status}),
                    el('span', {className: 'badge', textContent: '待审'}));
                if (row.fresh) meta.append(el('span', {className: 'chip-new', textContent: '新'}));
                const verdict = el('div', {});
                verdict.dataset.verdictId = `playtest.${scene.id}.${runtime}`;
                verdict.dataset.kind = 'playtest';
                verdict.dataset.title = scene.label;
                const card = el('article', {className: 'card'}, el('h4', {textContent: scene.label}), meta);
                if (row.watch) card.append(el('p', {className: 'decision', textContent: '看点：' + row.watch}));
                card.append(verdict);
                card.dataset.reviewable = '';
                card.dataset.status = 'pending';
                playtestCards.append(card);
            }
        }
        playtestCount.replaceChildren('待审 ', el('span', {className: 'pending-count', textContent: String((list.items || []).length)}),
            ` / 共 ${(list.items || []).length} ｜ 运行包 ${runtime}`);
        if (!(list.items || []).length) playtestCards.append(el('p', {className: 'muted', textContent: '当前没有待试玩的场景。'}));
    } catch (error) {
        playtestCount.textContent = '读取失败';
        playtestCards.append(el('p', {className: 'muted', textContent: '无法读取试玩清单：' + error.message}));
    }

    // 2. Art / audio / motion review cards from inbox/items.json
    try {
        const res = await fetch('inbox/items.json');
        if (res.ok) {
            const items = await res.json();
            for (const kind of ['art', 'motion']) {
                const group = items.filter(item => (item.kind || 'art') === kind);
                if (!group.length) continue;
                const sec = el('section', {className: 'kind-block'});
                const pendingSpan = el('span', {className: 'pending-count', textContent: String(group.length)});
                sec.append(el('h2', {}, KIND_LABELS[kind] + ' ', el('small', {}, '待审 ', pendingSpan, ` / 共 ${group.length}`)));
                const cards = el('div', {className: 'cards'});
                for (const item of group) {
                    const card = el('article', {className: 'card'});
                    card.dataset.reviewable = '';
                    card.dataset.status = 'pending';
                    const verdictId = item.entry || item.id;
                    const verdict = el('div', {});
                    verdict.dataset.verdictId = verdictId;
                    verdict.dataset.kind = kind;
                    verdict.dataset.title = item.title;
                    card.append(
                        el('h4', {textContent: item.title}),
                        el('p', {className: 'decision', textContent: '要你决定：' + item.decision}),
                        el('div', {className: 'meta'},
                            el('a', {className: 'open-link', href: resolveInboxUrl(item.url), target: '_blank', rel: 'noopener', textContent: '打开演示'}),
                            el('span', {className: 'badge', textContent: '待审'})),
                        verdict,
                    );
                    cards.append(card);
                }
                sec.append(cards);
                sections.push(sec);
            }
        }
    } catch (_) {
        // Optional inbox/items.json absent on fresh checkout
    }

    root.replaceChildren(...sections);
    await mountVerdicts(root.querySelectorAll('[data-verdict-id]'));
    const pendingTotal = document.querySelector('#pending-total');
    if (pendingTotal) {
        pendingTotal.textContent = String(root.querySelectorAll('.card[data-status="pending"]').length);
    }
}

function renderDemos(root, data) {
    const entries = data.entries || [];
    const perfs = entries.filter(e => e.category === 'performances');
    const multiStep = perfs.filter(e => e.steps && !e.id.startsWith('moment-'));
    const moments = perfs.filter(e => e.id.startsWith('moment-')).sort((a, b) => a.title.localeCompare(b.title, 'zh-CN'));

    // Curated complete scene demos (dialogue pacing + multi-step performances)
    const themedCards = [
        {id: 'dialogue-pacing-sample', title: '多油共处对白节奏与防扎堆演示', detail: '4 只同居油库里轮流接话与分声线时长实录', href: 'dialogue-pacing-sample/index.html'},
        {id: 'stage3-social-dialogue', title: '阶段三：社交、家庭与长期关系演化演示', detail: '求偶约会 / 离巢送别 / 哀悼誓言 / 霉斑劝离 / 徽章考试与护崽实录', href: 'stage3-social-dialogue/index.html'},
        ...multiStep.map(e => ({
            id: e.id,
            title: e.title,
            detail: `${e.steps.length} 步连贯演出`,
            href: entryURL(e.id),
        })),
    ];

    const byTheme = groupBy(themedCards, c => classifyTheme(c.id).label);
    const out = [
        el('p', {className: 'intro-note', textContent: '这里只展示连贯的完整场景演出，隐藏了近两万条单一动作切片、零散语音文件与裸模型。'}),
    ];

    for (const [themeLabel, cards] of byTheme) {
        const sec = el('section', {className: 'demo-theme'});
        sec.append(el('h2', {textContent: themeLabel}));
        const grid = el('div', {className: 'demo-grid'});
        for (const item of cards) {
            grid.append(el('a', {className: 'demo-card', href: item.href},
                el('strong', {textContent: item.title}),
                el('small', {textContent: item.detail})));
        }
        sec.append(grid);
        out.push(sec);
    }

    if (moments.length) {
        const details = el('details', {className: 'moments-drawer'});
        details.append(el('summary', {textContent: `单步行为片段参考（共 ${moments.length} 条，平时可折叠）`}));
        const grid = el('div', {className: 'demo-grid'});
        for (const m of moments) {
            grid.append(el('a', {className: 'demo-card', href: entryURL(m.id)},
                el('strong', {textContent: m.title}),
                el('small', {textContent: '单步演出'})));
        }
        details.append(grid);
        out.push(details);
    }

    root.replaceChildren(...out);
}

function renderRawDeveloperView(root, data) {
    const entries = data.entries || [];
    const models = data.models || {};
    const out = [
        el('div', {className: 'raw-banner'},
            el('strong', {textContent: '开发者底层素材库（仅供排查原始资源）'}),
            el('a', {href: './index.html', textContent: '返回简洁工作台'})),
    ];
    for (const [id, label] of Object.entries(categories)) {
        const rows = entries.filter(e => e.category === id);
        const details = el('details', {className: 'moments-drawer'});
        details.append(el('summary', {textContent: `${label}（${rows.length} 项）`}));
        details.addEventListener('toggle', () => {
            const list = el('ul', {className: 'raw-list'});
            for (const row of rows.slice(0, 500)) {
                const sub = row.detail || (row.model && models[row.model]?.name) || row.speaker || '';
                list.append(el('li', {},
                    el('a', {href: entryURL(row.id), textContent: row.title}),
                    sub ? el('small', {textContent: ' · ' + sub}) : ''));
            }
            if (rows.length > 500) {
                list.append(el('li', {className: 'muted', textContent: `其余 ${rows.length - 500} 项已省略`}));
            }
            details.append(list);
        }, {once: true});
        out.push(details);
    }
    root.replaceChildren(...out);
}

function setupTabs(defaultTab) {
    const buttons = document.querySelectorAll('.tab-btn');
    const panels = {
        inbox: document.getElementById('panel-inbox'),
        demos: document.getElementById('panel-demos'),
    };
    const filterToggle = document.querySelector('.filter-toggle');

    function activate(tab) {
        const target = panels[tab] ? tab : 'inbox';
        for (const btn of buttons) {
            btn.setAttribute('aria-selected', String(btn.dataset.tab === target));
        }
        for (const [k, panel] of Object.entries(panels)) {
            panel.hidden = k !== target;
        }
        if (filterToggle) filterToggle.hidden = target !== 'inbox';
    }

    for (const btn of buttons) {
        btn.addEventListener('click', () => {
            history.replaceState(null, '', btn.dataset.tab === 'demos' ? '?tab=demos' : './index.html');
            activate(btn.dataset.tab);
        });
    }
    activate(defaultTab);
}

async function init() {
    const params = new URLSearchParams(location.search);
    const inboxRoot = document.getElementById('inbox-root');
    const demosRoot = document.getElementById('demos-root');
    const rawRoot = document.getElementById('raw-root');

    const onlyPending = document.getElementById('only-pending');
    if (onlyPending) {
        onlyPending.onchange = () => document.body.classList.toggle('only-pending', onlyPending.checked);
    }

    if (params.get('raw') === '1') {
        document.getElementById('panel-inbox').hidden = true;
        document.getElementById('panel-demos').hidden = true;
        rawRoot.hidden = false;
        const data = await loadEntries();
        renderRawDeveloperView(rawRoot, data);
        return;
    }

    setupTabs(params.get('tab') === 'demos' ? 'demos' : 'inbox');
    const [_, data] = await Promise.all([
        renderInbox(inboxRoot),
        loadEntries().catch(err => ({entries: [], error: err.message})),
    ]);
    if (data.error) {
        demosRoot.replaceChildren(el('p', {className: 'status-msg', textContent: '无法读取演出目录：' + data.error}));
    } else {
        renderDemos(demosRoot, data);
    }
}

init();
