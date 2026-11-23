import {mountVerdicts} from './verdicts.js';

// The in-game 待看 rows, read from the current TODO on every page load. A verdict id carries the scene and the installed
// runtime commit, so a verdict for an older runtime never shows as the current state of a card.
function el(tag, props = {}, ...children) {
    const node = Object.assign(document.createElement(tag), props);
    node.append(...children);
    return node;
}

function card(row, runtime) {
    const scene = row.scenes[0];
    const meta = el('div', {className: 'meta'}, el('span', {className: row.status === '复测失败' ? 'chip-status retest' : 'chip-status', textContent: row.status}),
        el('span', {className: 'badge', textContent: '待审'}));
    if (row.fresh) meta.append(el('span', {className: 'chip-new', textContent: '新'}));
    const verdict = el('div', {});
    verdict.dataset.verdictId = `playtest.${scene.id}.${runtime}`;
    verdict.dataset.kind = 'playtest';
    verdict.dataset.title = scene.label;
    const article = el('article', {className: 'card'}, el('h3', {textContent: scene.label}), meta);
    if (row.watch) article.append(el('p', {className: 'decision', textContent: '看点：' + row.watch}));
    article.append(verdict);
    article.dataset.reviewable = '';
    article.dataset.status = 'pending';
    return article;
}

const section = document.querySelector('#playtest-live');
const cards = section.querySelector('.cards');
try {
    const response = await fetch('/playtest');
    const list = await response.json();
    if (!response.ok) throw new Error(list.error);
    const runtime = list.until.slice(0, 9);
    // One heading per field in the scenes-catalog order, rows in payload order inside a field (as the in-game tab).
    const byField = Map.groupBy(list.items, row => row.scenes[0].field);
    const rank = name => (list.fields.includes(name) ? list.fields.indexOf(name) : list.fields.length);
    for (const name of [...byField.keys()].sort((a, b) => rank(a) - rank(b))) {
        cards.append(el('h4', {className: 'field', textContent: name}));
        for (const row of byField.get(name)) cards.append(card(row, runtime));
    }
    section.querySelector('small').replaceChildren('待审 ', el('span', {className: 'pending-count', textContent: list.items.length}),
        ` / 共 ${list.items.length} ｜ 运行包 ${runtime}`);
    if (!list.items.length) cards.append(el('p', {textContent: '待办里没有待试玩的场景。'}));
    await mountVerdicts(cards.querySelectorAll('[data-verdict-id]'));
} catch (error) {
    section.querySelector('small').textContent = '读取失败';
    cards.append(el('p', {textContent: '无法读取试玩清单，请检查本地预览服务。' + error.message}));
}
