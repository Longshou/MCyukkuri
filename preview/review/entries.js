export const categories = {performances: '演出', models: '模型', props: '道具', sounds: '声音'};

export function entryURL(id, model) {
    const params = new URLSearchParams({entry: id});
    if (model) params.set('model', model);
    return '/.local/previews/player.html?' + params;
}

export async function loadEntries() {
    const response = await fetch('entries.json');
    if (!response.ok) throw new Error('HTTP ' + response.status);
    return response.json();
}
