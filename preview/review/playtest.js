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
    let group = null;
    for (const row of list.items) {
        const name = row.scenes[0].field;
        if (row.status + name !== group) cards.append(el('h4', {className: 'field', textContent: name}));
        group = row.status + name;
        cards.append(card(row, runtime));
    }
    section.querySelector('small').innerHTML = `待审 <span class="pending-count">${list.items.length}</span> / 共 ${list.items.length} ｜ 运行包 ${runtime}`;
    if (!list.items.length) cards.append(el('p', {textContent: '待办里没有待试玩的场景。'}));
    await mountVerdicts(cards.querySelectorAll('[data-verdict-id]'));
} catch (error) {
    cards.append(el('p', {textContent: '无法读取试玩清单，请检查本地预览服务。' + error.message}));
}
