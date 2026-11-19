const labels = {pass: '通过', fail: '不通过'};

async function api(path, options) {
    const response = await fetch(path, options);
    const result = await response.json();
    if (!response.ok) throw new Error(result.error);
    return result;
}

function showForm(target, record) {
    const id = target.dataset.verdictId;
    const form = document.createElement('form');
    const label = document.createElement('label');
    label.textContent = '备注（可选，最多 500 字）';
    const note = document.createElement('textarea');
    note.rows = 2;
    note.value = record?.note ?? '';
    note.oninput = () => note.setCustomValidity(
        [...note.value].length > 500 ? '备注须为最多 500 字的文本。' : '');
    label.append(document.createElement('br'), note);
    const controls = document.createElement('div');
    controls.className = 'verdict-controls';
    const status = document.createElement('output');
    status.setAttribute('role', 'status');
    const buttons = Object.entries(labels).map(([value, text]) => {
        const button = document.createElement('button');
        button.type = 'submit';
        button.value = value;
        button.textContent = text;
        controls.append(button);
        return button;
    });

    function showResult(latest) {
        status.textContent = latest ? '已记录：' + labels[latest.verdict] : '待审阅';
        for (const button of buttons) {
            button.setAttribute('aria-pressed', String(button.value === latest?.verdict));
        }
    }
    showResult(record);
    form.onsubmit = async event => {
        event.preventDefault();
        const verdict = event.submitter.value;
        for (const button of buttons) button.disabled = true;
        status.textContent = '正在保存…';
        try {
            const saved = await api('/verdict', {method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({id, verdict, note: note.value})});
            showResult(saved);
        } catch (error) {
            status.textContent = '保存失败。' + error.message;
        } finally {
            for (const button of buttons) button.disabled = false;
        }
    };
    controls.append(status);
    form.append(label, controls);
    target.replaceChildren(form);
}

export async function mountVerdicts(targets) {
    if (!targets.length) return;
    try {
        const records = await api('/verdicts');
        for (const target of targets) {
            const id = target.dataset.verdictId;
            showForm(target, Object.hasOwn(records, id) ? records[id] : undefined);
        }
    } catch {
        for (const target of targets) target.textContent = '无法读取审阅记录，请检查本地预览服务。';
    }
}

mountVerdicts(document.querySelectorAll('[data-verdict-id]'));
