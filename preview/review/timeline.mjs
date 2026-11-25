export function buildTimeline(entry, clips, momentClips) {
    const steps = entry.steps ?? [{clip: entry.clip, faces: [], tempo: 1}];
    let duration = 0;
    let stanceStart;
    const track = steps.map((step, index) => {
        const start = duration;
        if (step.stance !== steps[index - 1]?.stance) stanceStart = start;
        const length = entry.moment ? momentClips[step.clip] ?? 1 : clips[step.clip].duration;
        duration += step.duration ?? length / step.tempo;
        return {...step, start, end: duration, stanceStart};
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
    const pairStage = step.stance && {kind: step.stance, elapsed: seconds - step.stanceStart,
        progress: Math.min(1, elapsed / (step.end - step.start))};
    return {clip: step.clip, time, intensity: step.intensity, face, caption: step.caption, scratch: step.scratch, pairStage,
            emote: step.emote, stepSeconds: elapsed};
}

export function emoteFrame(sample, elapsed, duration, leak) {
    const tick = Math.floor(elapsed * 20);
    const sweat = Boolean(leak && tick >= leak.beat.puddleAt && tick < leak.sweatUntil);
    return {face: sample.face, sweat, elapsed, duration, stepSeconds: sample.stepSeconds, symbol: sample.emote ?? ''};
}

export function leakSteps(steps, leak, end = leak.beat.endAt) {
    const {flowAt, puddleAt, endAt} = leak.beat;
    const starts = [0, flowAt, puddleAt, endAt];
    const staged = steps.map((step, i) => ({
        ...step, duration: (starts[i + 1] - starts[i]) / 20, faces: [[0, leak.faces[i]]]
    }));
    if (end > endAt) staged.push({clip: 'idle', tempo: 1, faces: [], duration: (end - endAt) / 20});
    return staged;
}

export function textureFace(data, model, face, seconds) {
    const selected = face;
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
