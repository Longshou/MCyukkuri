export function buildTimeline(entry, clips) {
    const steps = entry.steps ?? [{clip: entry.clip, faces: [], tempo: 1, intensity: 1}];
    let duration = 0;
    const track = steps.map(step => {
        const start = duration;
        duration += step.duration ?? clips[step.clip].duration / step.tempo;
        return {...step, start, end: duration};
    });
    return {track, duration, loop: entry.loop ?? clips[entry.clip].loop};
}

export function sampleTimeline(track, clips, seconds, expression) {
    const step = track.find(row => seconds < row.end) ?? track.at(-1);
    const elapsed = seconds - step.start;
    const time = elapsed * step.tempo;
    const clip = clips[step.clip];
    const clipTime = clip.loop ? time % clip.duration : Math.min(time, clip.duration);
    const face = expression === 'auto'
        ? step.faces.findLast(([when]) => when <= elapsed)?.[1]
          ?? clip.faces.find(([start, end]) => clipTime >= start && clipTime < end)?.[2]
          ?? 'neutral'
        : expression;
    return {clip: step.clip, time, intensity: step.intensity, face, caption: step.caption};
}

export function textureFace(data, model, face, seconds) {
    const universal = data.faceAliases[face] ?? face;
    const selected = model.faceAliases[universal] ?? universal;
    const animation = data.faceAnimations[selected];
    if (!animation) return selected;
    const cycle = animation.frame_ticks.reduce((sum, ticks) => sum + ticks, 0);
    let tick = Math.floor(seconds * 20) % cycle;
    for (const [index, ticks] of animation.frame_ticks.entries()) {
        if (tick < ticks) return animation.frames[index];
        tick -= ticks;
    }
    throw new Error('Invalid face animation: ' + selected);
}
