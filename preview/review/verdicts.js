const labels = {pass: '通过', fail: '不通过'};
const reasonSets = {
    motion: ['穿模', '动作僵硬', '节奏太快', '节奏太慢', '幅度太小', '幅度太大', '不像这个角色', '表情不对', '衔接突兀'],
    art: ['穿模', '比例不对', '颜色不对', '风格不统一', '细节太少', '太粗糙', '不像设定', '看不清'],
};
const NOTE_LIMIT = 500;

async function api(path, options) {
    const response = await fetch(path, options);
    const result = await response.json();
    if (!response.ok) throw new Error(result.error);
    return result;
}

function el(tag, props = {}, ...children) {
    const node = Object.assign(document.createElement(tag), props);
    node.append(...children);
    return node;
}

export function formatTime(ts) {
    const date = new Date(ts);
    if (Number.isNaN(date.getTime())) return '';
    const two = value => String(value).padStart(2, '0');
    return `${date.getMonth() + 1}-${date.getDate()} ${two(date.getHours())}:${two(date.getMinutes())}`;
}

function showControls(target, record, onChange) {
    const id = target.dataset.verdictId;
    const title = target.dataset.title;
    const reasonList = reasonSets[target.dataset.kind] ?? reasonSets.motion;
    let current = record;

    const status = el('output', {className: 'verdict-status'});
    status.setAttribute('role', 'status');
    const summary = el('p', {className: 'verdict-summary'});
    const buttons = Object.entries(labels).map(([value, text]) =>
        el('button', {type: 'button', value, textContent: text, className: 'verdict-' + value}));
    const [passButton, failButton] = buttons;
    const row = el('div', {className: 'verdict-buttons', role: 'group'}, ...buttons);
    row.setAttribute('aria-label', '审阅结论');

    const panel = el('form', {className: 'reasons-panel', hidden: true});
    const legend = el('legend', {textContent: '哪里不对？（可多选）'});
    const chips = reasonList.map(reason => {
        const label = el('label', {className: 'chip'});
        const box = el('input', {type: 'checkbox', value: reason});
        label.append(box, el('span', {textContent: reason}));
        return label;
    });
    const fieldset = el('fieldset', {}, legend, el('div', {className: 'chips'}, ...chips));
    const note = el('textarea', {rows: 5, maxLength: NOTE_LIMIT, placeholder: '补充说明（可选，最多 500 字）'});
    note.setAttribute('aria-label', '补充说明');
    const counter = el('span', {className: 'note-count'});
    const submit = el('button', {type: 'submit', textContent: '提交', className: 'primary'});
    const cancel = el('button', {type: 'button', textContent: '取消'});
    panel.append(fieldset, note, el('div', {className: 'panel-actions'}, submit, cancel, counter));

    const boxes = () => chips.map(label => label.firstChild);
    const updateCount = () => { counter.textContent = `${[...note.value].length} / ${NOTE_LIMIT}`; };
    note.oninput = updateCount;

    function render() {
        passButton.setAttribute('aria-pressed', String(current?.verdict === 'pass'));
        failButton.setAttribute('aria-pressed', String(current?.verdict === 'fail'));
        summary.replaceChildren();
        if (current) {
            const text = labels[current.verdict] + (current.ts ? ' · ' + formatTime(current.ts) : '');
            summary.append(el('strong', {textContent: text}));
            const reasons = current.reasons ?? [];
            if (reasons.length) summary.append(' ', ...reasons.map(r => el('span', {className: 'tag', textContent: r})));
            if (current.note) summary.append(el('span', {className: 'note-text', textContent: current.note}));
        }
        summary.hidden = !current;
        onChange(current);
    }

    function openPanel() {
        const had = current?.verdict === 'fail' ? current : null;
        for (const box of boxes()) box.checked = !!had?.reasons?.includes(box.value);
        note.value = had?.note ?? '';
        updateCount();
        panel.hidden = false;
        failButton.setAttribute('aria-expanded', 'true');
        boxes()[0].focus();
    }
    function closePanel() {
        panel.hidden = true;
        failButton.setAttribute('aria-expanded', 'false');
        failButton.focus();
    }
    async function save(body) {
        for (const button of [...buttons, submit, cancel]) button.disabled = true;
        status.textContent = '正在保存…';
        try {
            current = await api('/verdict', {method: 'POST',
                headers: {'Content-Type': 'application/json'}, body: JSON.stringify({id, ...(title && {title}), ...body})});
            status.textContent = '';
            panel.hidden = true;
            render();
        } catch (error) {
            status.textContent = '保存失败。' + error.message;
        } finally {
            for (const button of [...buttons, submit, cancel]) button.disabled = false;
        }
    }
    passButton.onclick = () => { panel.hidden = true; save({verdict: 'pass', note: '', reasons: []}); };
    failButton.onclick = () => (panel.hidden ? openPanel() : closePanel());
    cancel.onclick = closePanel;
    panel.onkeydown = event => { if (event.key === 'Escape') closePanel(); };
    panel.onsubmit = event => {
        event.preventDefault();
        save({verdict: 'fail', note: note.value, reasons: boxes().filter(box => box.checked).map(box => box.value)});
    };

    failButton.setAttribute('aria-expanded', 'false');
    target.replaceChildren(row, status, summary, panel);
    render();
    return record => {
        if (!record || (current?.ts ?? '') >= record.ts) return; // Only a newer record: an older in-flight GET must not undo a save.
        current = record;
        render();
    };
}

function updateCard(target, record) {
    const card = target.closest('.card');
    if (!card) return;
    const state = record ? record.verdict : 'pending';
    card.dataset.status = state;
    const badge = card.querySelector('.badge');
    if (badge) {
        badge.textContent = record ? labels[record.verdict] + (record.ts ? ' ' + formatTime(record.ts) : '') : '待审';
    }
    const section = card.closest('.kind');
    const count = section?.querySelector('.pending-count');
    if (count) count.textContent = section.querySelectorAll('.card[data-status="pending"]').length;
    const total = document.querySelector('#pending-total');
    if (total) total.textContent = document.querySelectorAll('.card[data-status="pending"]').length;
}

const mounted = new Map();
const POLL_MS = 5000;

const recordIn = (records, id) => (Object.hasOwn(records, id) ? records[id] : undefined);

export async function mountVerdicts(targets) {
    if (!targets.length) return;
    try {
        const records = await api('/verdicts');
        for (const target of targets) {
            mounted.set(target, showControls(target, recordIn(records, target.dataset.verdictId),
                record => updateCard(target, record)));
        }
    } catch {
        for (const target of targets) target.textContent = '无法读取审阅记录，请检查本地预览服务。';
    }
}

// Pick up verdicts saved from other tabs (or the player page) while this page stays open.
async function refreshVerdicts() {
    if (document.hidden || !mounted.size) return;
    let records;
    try {
        records = await api('/verdicts');
    } catch {
        return; // The server is briefly unreachable; the next poll retries.
    }
    for (const [target, setRecord] of mounted) {
        setRecord(recordIn(records, target.dataset.verdictId));
    }
}

setInterval(refreshVerdicts, POLL_MS);
document.addEventListener('visibilitychange', refreshVerdicts);
window.addEventListener('focus', refreshVerdicts);

const filter = document.querySelector('#only-pending');
if (filter) {
    filter.onchange = () => document.body.classList.toggle('only-pending', filter.checked);
}

mountVerdicts(document.querySelectorAll('[data-verdict-id]'));
