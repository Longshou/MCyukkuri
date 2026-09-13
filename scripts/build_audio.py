"""Build original SFX and Japanese speech; --check only validates existing files.

Sources: art/audio/recipe.json and this synthesizer. Writes WAV masters, Ogg
runtime assets, an audition page and fingerprint manifest. Never starts a game.
"""
import argparse
import hashlib
import html
import importlib.metadata
import json
import platform
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "art/audio"
ASSETS = ROOT / "src/main/resources/assets/mcyukkuri"
RATE = 48000


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def fade(x, seconds=.012):
    x = np.array(x, dtype=np.float64, copy=True)
    n = min(round(seconds * RATE), len(x) // 2)
    ramp = np.sin(np.linspace(0, np.pi / 2, n)) ** 2
    x[:n] *= ramp
    x[-n:] *= ramp[::-1]
    return x


def tone(seconds, start, end, decay=5):
    t = np.arange(round(seconds * RATE)) / RATE
    phase = 2 * np.pi * (start * t + (end - start) * t * t / (2 * seconds))
    return fade((np.sin(phase) + .18 * np.sin(2 * phase)) * np.exp(-decay * t / seconds))


def noise(rng, seconds, low, high):
    n = round(seconds * RATE)
    freq = np.fft.rfftfreq(n, 1 / RATE)
    spectrum = np.fft.rfft(rng.normal(size=n))
    # Smooth spectral shoulders keep the result free of harsh band edges.
    filt = np.exp(-(freq / high) ** 4) * (1 - np.exp(-(freq / low) ** 4))
    x = np.fft.irfft(spectrum * filt, n=n)
    return fade(x / max(np.max(np.abs(x)), 1e-9))


def mix(seconds, parts):
    out = np.zeros(round(seconds * RATE))
    for offset, clip, gain in parts:
        i = round(offset * RATE)
        count = min(len(clip), len(out) - i)
        out[i:i + count] += clip[:count] * gain
    return fade(out)


def effect(kind, rng):
    p = rng.uniform(.92, 1.08)
    if kind == "waddle":
        return mix(.28, [(0, tone(.22, 155*p, 74*p), .8), (.03, noise(rng,.19,180,1800),.25)])
    if kind == "hop":
        return mix(.25, [(0,tone(.19,170*p,460*p,2),.7), (0,noise(rng,.23,700,4200),.2)])
    if kind == "land":
        return mix(.36, [(0,tone(.3,155*p,52*p,7),1), (.025,noise(rng,.24,100,1900),.25)])
    if kind == "chew":
        parts = []
        for offset in [0,.19,.4]:
            parts.extend([(offset,noise(rng,.12,450,3900),.6), (offset,tone(.12,240*p,95*p),.5)])
        return mix(.59, parts)
    if kind == "gulp":
        return mix(.4, [(0,tone(.19,380*p,105*p,2),.65), (.14,tone(.2,140*p,270*p,4),.35)])
    if kind == "sleep_breath":
        parts = []
        for offset, duration, gain in [(0,.72,.5),(1.05,1.03,.65)]:
            breath = noise(rng,duration,140,1700)
            breath *= np.sin(np.linspace(0,np.pi,len(breath))) ** 1.8
            parts.append((offset,breath,gain))
        return mix(2.35,parts)
    if kind == "puff":
        x = noise(rng,.65,180,2800)
        x *= np.sin(np.linspace(0,np.pi,len(x))) ** .8
        return mix(.75,[(0,x,.7),(.32,tone(.25,170*p,100*p,3),.15)])
    if kind == "rub":
        return mix(.9,[(i,noise(rng,.27,250,2100)*np.sin(np.linspace(0,np.pi,round(.27*RATE))),.7) for i in [0,.28,.55]])
    if kind == "wash":
        parts = [(0,noise(rng,1.1,850,6000),.35)]
        parts += [(float(rng.uniform(.05,.9)),tone(.07,float(rng.uniform(700,1600)),320,8),.3) for _ in range(12)]
        return mix(1.2,parts)
    if kind == "nest":
        return mix(.85,[(i,noise(rng,.2,900,5500),g) for i,g in [(0,.5),(.14,.7),(.35,.45),(.55,.3)]])
    if kind == "toilet":
        return mix(.3,[(0,tone(.24,190*p,65*p,6),.8),(.01,noise(rng,.15,150,1100),.3)])
    if kind == "item_pickup":
        return mix(.35,[(0,tone(.12,700*p,760*p,6),.5),(.085,tone(.19,1050*p,1120*p,6),.35)])
    raise ValueError(kind)


def finish(x, peak_db):
    x = fade(x - np.mean(x))
    peak = np.max(np.abs(x))
    if peak < 1e-8:
        raise ValueError("Silent synthesis")
    x *= 10 ** (peak_db / 20) / peak
    return np.pad(x, (round(.025 * RATE), round(.06 * RATE)))


def stats(path):
    x, rate = sf.read(path)
    info = sf.info(path)
    assert rate == RATE and x.ndim == 1, f"Expected 48 kHz mono: {path}"
    assert np.all(np.isfinite(x)) and len(x) > RATE*.1, path
    peak = float(np.max(np.abs(x)))
    rms = float(np.sqrt(np.mean(x*x)))
    assert .005 < peak < .9 and rms > .0001, f"Silent or clipped: {path}"
    assert np.max(np.abs(x[:240])) < .003 and np.max(np.abs(x[-240:])) < .003, f"Abrupt edge: {path}"
    return {"seconds":round(len(x)/rate,4), "sample_rate":rate,"channels":info.channels,
            "format":info.format,"subtype":info.subtype,"peak_dbfs":round(20*np.log10(peak),2),
            "rms_dbfs":round(20*np.log10(rms),2),"sha256":digest(path)}


def render_preview(rows):
    cards = []
    for row in rows:
        text = html.escape(row.get("text", "程序合成动作音效"))
        cards.append(f'<article data-kind="{row["kind"]}"><h3>{html.escape(row["label"])}</h3>'
                     f'<p>{text}</p><audio controls preload="none" src="masters/{row["id"]}.wav"></audio>'
                     f'<small>{row["id"]} · {row["wav"]["seconds"]:.2f} 秒</small></article>')
    page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>油库里声音试听</title><style>
body{margin:0;background:#f5f1e7;color:#28382d;font:16px/1.65 system-ui,sans-serif}main{max-width:1120px;margin:auto;padding:40px 24px}
h1{font-size:40px;margin:8px 0}p{margin:8px 0 14px}header{padding-bottom:24px}nav{display:flex;gap:8px;flex-wrap:wrap;margin:20px 0}
button{border:1px solid #a9b8a8;border-radius:20px;padding:9px 20px;cursor:pointer;background:white;color:#28382d}button.active{background:#365843;color:white}
#grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(290px,1fr));gap:16px}article{background:#fffcf5;padding:20px;border:1px solid #deded0;border-radius:16px}
h3{margin:0}small{display:block;color:#677468;overflow-wrap:anywhere}audio{width:100%;margin:10px 0}footer{margin-top:28px;font-size:13px;color:#677468}
</style><main><header><small>MCYUKKURI / AUDIO STUDY 01</small><h1>油库里的日常声音</h1>
<p>24 个动作音效 · 28 句日语语音。首版待试听确认，播放一条时会暂停其他声音。</p>
<p>灵梦轻柔、语速稍慢；魔理沙稍低、语速稍快。两者均来自 Mei 合成声模型，与经典ゆっくり声线有差异。</p>
<audio controls preload="none" src="preview.wav"></audio><small>精选串听：欢迎 → 进食 → 移动 → 照料 → 入睡</small></header>
<nav aria-label="音频分类"><button class="active" data-filter="all">全部</button><button data-filter="sfx">动作音效</button><button data-filter="reimu">灵梦</button><button data-filter="marisa">魔理沙</button></nav>
<section id="grid">CARDS</section><footer>日语语音：OpenJTalk / HTS Voice Mei · MMDAgent Project Team, Nagoya Institute of Technology · CC BY 3.0。已调整音高、语速及音量。<br>动作音效：本项目程序合成。台词、参数与来源详见 recipe.json 和 README.md。</footer></main>
<script>document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{if(a!==b)b.pause()})));
document.querySelectorAll('button').forEach(b=>b.onclick=()=>{document.querySelectorAll('button').forEach(c=>c.classList.toggle('active',c===b));document.querySelectorAll('article').forEach(c=>{c.hidden=b.dataset.filter!=='all'&&c.dataset.kind!==b.dataset.filter})});</script></html>'''
    (PACK / "index.html").write_text(page.replace("CARDS", "\n".join(cards)), encoding="utf-8")


def build(recipe):
    import pyopenjtalk
    rows, events = [], {}

    def save(asset_id, event, label, x, kind, **extra):
        wav = PACK / "masters" / (asset_id + ".wav")
        ogg = ASSETS / "sounds" / (asset_id + ".ogg")
        wav.parent.mkdir(parents=True, exist_ok=True)
        ogg.parent.mkdir(parents=True, exist_ok=True)
        sf.write(wav, x, RATE, subtype="PCM_16")
        sf.write(ogg, x, RATE, format="OGG", subtype="VORBIS")
        rows.append({"id":asset_id,"event":event,"label":label,"kind":kind,
                     "source":wav.relative_to(ROOT).as_posix(),"runtime":ogg.relative_to(ROOT).as_posix(),
                     "wav":stats(wav),"ogg":stats(ogg),**extra})
        events.setdefault(event,{"sounds":[]})["sounds"].append({"name":"mcyukkuri:"+asset_id,"attenuation_distance":16})

    for i, fx in enumerate(recipe["effects"]):
        for v in range(1, fx["variants"]+1):
            seed = recipe["seed"] + i*100 + v
            x = finish(effect(fx["id"],np.random.default_rng(seed)),fx["gain_db"])
            save(f'sfx/{fx["id"]}_{v:02}', 'sfx.'+fx["id"],f'{fx["label"]} · {v}',x,"sfx",seed=seed)
    for character, profile in recipe["voice_profiles"].items():
        for line in recipe["lines"]:
            x, rate = pyopenjtalk.tts(line[character], speed=profile["speed"], half_tone=profile["half_tone"],
                                      use_sudachi_kanji_yomi=False,predict_nani=False)
            assert rate == RATE
            # Trim synthesis padding without cutting quiet consonants.
            active = np.flatnonzero(np.abs(x) > max(np.max(np.abs(x))*.001, 1))
            if not len(active):
                raise ValueError(line)
            x = x[max(0,active[0]-2400):min(len(x),active[-1]+4800)] / 32768
            save(f'voice/{character}/{line["id"]}', f'voice.{character}.{line["id"]}',
                 f'{profile["label"]} · {line["label"]}', finish(x,-6),character,text=line[character],profile=profile)
        print(f'Created {character} speech',flush=True)
    sound_path = ASSETS / "sounds.json"
    existing = json.loads(sound_path.read_text(encoding="utf-8")) if sound_path.exists() else {}
    existing.update(events)
    write_json(sound_path,existing)
    montage = ["voice/reimu/greeting","voice/marisa/greeting","sfx/chew_01","voice/reimu/eat",
               "sfx/waddle_01","sfx/hop_01","sfx/land_01","sfx/wash_01","voice/marisa/thanks",
               "sfx/nest_01","voice/reimu/sleep","sfx/sleep_breath_01"]
    clips = []
    for asset_id in montage:
        x,_ = sf.read(PACK/"masters"/(asset_id+".wav"))
        clips.extend([x,np.zeros(round(.45*RATE))])
    sf.write(PACK/"preview.wav",np.concatenate(clips),RATE,subtype="PCM_16")
    write_json(PACK/"manifest.json",{"status":"pending-listening-review","gameplay_triggers":"not-connected",
        "recipe_sha256":digest(PACK/"recipe.json"),"builder_sha256":digest(Path(__file__)),
        "environment":{"python":platform.python_version(),"libsndfile":sf.__libsndfile_version__,
                       **{p:importlib.metadata.version(p) for p in ["numpy","soundfile","pyopenjtalk-plus"]}},
        "voice_model_sha256":digest(Path(pyopenjtalk.DEFAULT_HTS_VOICE.decode())),
        "sound_events":events,"montage_order":montage,"files":rows})
    render_preview(rows)


def check(recipe):
    manifest = json.loads((PACK/"manifest.json").read_text(encoding="utf-8"))
    if manifest.get("status") == "rejected-by-user":
        for row in manifest["files"]:
            assert digest(ROOT/row["source"]) == row["wav"]["sha256"]
            assert digest(ROOT/row["runtime"]) == row["ogg"]["sha256"]
            assert not (ROOT/row["withdrawn_runtime"]).exists()
        print("PASS: rejected trial archive intact; its 52 OGG files are absent from runtime. This is not voice acceptance.")
        return
    assert manifest["recipe_sha256"] == digest(PACK/"recipe.json"), "Recipe changed: rebuild audio"
    assert manifest["builder_sha256"] == digest(Path(__file__)), "Builder changed: rebuild audio"
    sounds = json.loads((ASSETS/"sounds.json").read_text(encoding="utf-8"))
    expected = {f'sfx/{fx["id"]}_{v:02}' for fx in recipe["effects"] for v in range(1,fx["variants"]+1)}
    expected |= {f'voice/{c}/{line["id"]}' for c in recipe["voice_profiles"] for line in recipe["lines"]}
    rows = manifest["files"]
    assert len(rows) == len(expected) and {r["id"] for r in rows} == expected
    for row in rows:
        for key, meta in [("source","wav"),("runtime","ogg")]:
            path = ROOT/row[key]
            assert digest(path) == row[meta]["sha256"], path
            result = stats(path)
            assert result["subtype"] == ("PCM_16" if meta=="wav" else "VORBIS"), path
        source,_ = sf.read(ROOT/row["source"])
        decoded,_ = sf.read(ROOT/row["runtime"])
        assert len(source)==len(decoded),row["id"]
        assert np.corrcoef(source,decoded)[0,1] > .95, f'Encoding damaged {row["id"]}'
    for event, value in manifest["sound_events"].items():
        assert sounds[event] == value,event
        for sound in value["sounds"]:
            assert (ASSETS/"sounds"/(sound["name"].split(":",1)[1]+".ogg")).is_file()
    print(f'PASS: {len(rows)} WAV + {len(rows)} mono Vorbis assets, {len(manifest["sound_events"])} events; fingerprints, decode, signal, edges and references valid.')


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check",action="store_true",help="Read-only checks; no synthesis")
    args = parser.parse_args()
    recipe = json.loads((PACK/"recipe.json").read_text(encoding="utf-8"))
    assert recipe["sample_rate"] == RATE
    if not args.check:
        manifest_path = PACK/"manifest.json"
        if manifest_path.exists() and json.loads(manifest_path.read_text(encoding="utf-8")).get("status") == "rejected-by-user":
            raise SystemExit("Mei trial rejected: do not regenerate or export it. See art/audio/DIARY_AUDIO_RESEARCH.md.")
        build(recipe)
    check(recipe)
