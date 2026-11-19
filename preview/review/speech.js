export async function loadSpeech(cue, pitch, mute) {
    const response = await fetch(cue.audio);
    if (!response.ok) throw new Error('HTTP ' + response.status);
    const context = new AudioContext();
    const buffer = await context.decodeAudioData(await response.arrayBuffer());
    const end = Math.min(buffer.duration, cue.duration * Math.max(1, pitch));
    let source;

    return {
        enable() { return context.resume(); },
        caption(time) { return time < cue.duration ? cue.text : ''; },
        sync(time, playing, speed) {
            const offset = pitch * time;
            const rate = pitch * speed;
            const audible = playing && !mute.checked && offset < end && context.state === 'running';
            if (source && (!audible || source.playbackRate.value !== Math.fround(rate))) {
                source.stop();
                source = undefined;
            }
            if (audible && !source) {
                source = context.createBufferSource();
                source.buffer = buffer;
                source.playbackRate.value = rate;
                source.connect(context.destination);
                source.start(0, offset, end - offset);
            }
        }
    };
}
