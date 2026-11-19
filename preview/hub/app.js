import {categories, entryURL, loadEntries} from '/preview/review/entries.js';

const filters = document.getElementById('filters');
const catalog = document.getElementById('catalog');
const status = document.getElementById('status');

function entryList(entries, detail) {
    const list = document.createElement('ul');
    list.className = 'entry-list';
    for (const entry of entries) {
        const row = document.createElement('li');
        const link = document.createElement('a');
        link.href = entryURL(entry.id);
        link.textContent = entry.title;
        row.append(link);
        const info = detail(entry);
        if (info) {
            const label = document.createElement('small');
            label.textContent = info;
            row.append(label);
        }
        list.append(row);
    }
    return list;
}

function groupBy(rows, key) {
    const groups = new Map();
    for (const row of rows) {
        const group = key(row);
        if (!groups.has(group)) groups.set(group, []);
        groups.get(group).push(row);
    }
    return groups;
}

function render({entries, models}) {
    for (const [id, label] of Object.entries({all: '全部', ...categories})) {
        const link = document.createElement('a');
        link.href = id === 'all' ? 'index.html' : '?category=' + id;
        link.textContent = label;
        link.dataset.category = id;
        filters.append(link);
    }
    for (const [id, label] of Object.entries(categories)) {
        const section = document.createElement('section');
        section.dataset.category = id;
        const title = document.createElement('h2');
        title.textContent = label;
        section.append(title);
        const rows = entries.filter(entry => entry.category === id);
        if (id === 'performances') {
            section.append(entryList(rows.filter(entry => entry.steps), () => ''));
            const clips = groupBy(rows.filter(entry => entry.clip), entry => entry.clip);
            const details = document.createElement('details');
            const summary = document.createElement('summary');
            summary.textContent = '动作片段';
            const featured = [...clips.values()].map(group => group[0]);
            featured.sort((a, b) => a.title.localeCompare(b.title, 'zh-CN'));
            details.append(summary, entryList(featured, entry => {
                const count = clips.get(entry.clip).length;
                return count > 1 ? count + ' 种形态' : models[entry.model].name;
            }));
            section.append(details);
        } else if (id === 'sounds') {
            for (const [speaker, sounds] of groupBy(rows, entry => entry.speaker)) {
                const details = document.createElement('details');
                const summary = document.createElement('summary');
                summary.textContent = speaker + '（' + sounds.length + ' 条）';
                details.append(summary);
                details.addEventListener('toggle', () => {
                    details.append(entryList(sounds, entry => entry.detail));
                }, {once: true});
                section.append(details);
            }
        } else {
            section.append(entryList(rows, entry => entry.detail));
        }
        catalog.append(section);
    }
    const selected = new URLSearchParams(location.search).get('category');
    const active = Object.hasOwn(categories, selected) ? selected : 'all';
    for (const link of filters.children) {
        if (link.dataset.category === active) link.setAttribute('aria-current', 'page');
    }
    for (const section of catalog.children) {
        section.hidden = active !== 'all' && section.dataset.category !== active;
    }
    status.hidden = true;
}

loadEntries().then(render).catch(error => {
    status.textContent = '无法读取放映室目录。' + error.message;
});
