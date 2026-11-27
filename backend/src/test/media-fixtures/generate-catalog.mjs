// V2-04 acceptance fixture catalog generator (FAKE/local media, Git-external output).
//
// - Narration: each subtitle cue is synthesised separately with the local Piper engine and the public-domain
//   LJSpeech voice, trimmed, and joined with fixed pauses. The cue timetable is therefore exact by construction
//   (sample offsets of real decoded audio), which is the "verified fixture timetable" of the V2-04 design.
// - Images: produced beforehand by GenerateImages.java and mapped to scene image prompts by prompt SHA-256.
// - Music and sound effects: synthesised here from oscillators and noise (no samples, no third-party audio).
//
// Usage: node generate-catalog.mjs <repo-root> <acceptance-root>
//   expects <acceptance-root>/tools/piper/piper.exe, downloads/voice/*.onnx, downloads/font/NotoSansKR-Bold.otf
//   writes  <acceptance-root>/fixtures/ifnova-v204-1/{catalog.json,audio/*,images/*}
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const [repo, root] = process.argv.slice(2)
const CATALOG = 'ifnova-v204-1'
const out = join(root, 'fixtures', CATALOG)
const work = join(root, 'work', 'piper-cues')
mkdirSync(join(out, 'audio'), { recursive: true })
mkdirSync(work, { recursive: true })
const chunks = JSON.parse(readFileSync(join(repo, 'backend/src/test/media-fixtures/narration-chunks.json'), 'utf8'))
const sha = (buf) => createHash('sha256').update(buf).digest('hex')
const verifiedAt = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
const VERIFIED_BY = 'claude-code-v2-04-product'

const model = join(root, 'downloads/voice', chunks.voice.model)
if (sha(readFileSync(model)) !== chunks.voice.modelSha256) throw new Error('Voice model hash mismatch')

// ---------------------------------------------------------------- WAV helpers (16-bit PCM)
function parseWav(buf) {
  if (buf.toString('ascii', 0, 4) !== 'RIFF' || buf.toString('ascii', 8, 12) !== 'WAVE') throw new Error('not wav')
  let pos = 12, rate, channels, data
  while (pos + 8 <= buf.length) {
    const id = buf.toString('ascii', pos, pos + 4), size = buf.readUInt32LE(pos + 4), body = pos + 8
    if (id === 'fmt ') { channels = buf.readUInt16LE(body + 2); rate = buf.readUInt32LE(body + 4) }
    if (id === 'data') { data = new Int16Array(buf.buffer.slice(buf.byteOffset + body, buf.byteOffset + body + size)) ; break }
    pos = body + size + (size & 1)
  }
  if (channels !== 1) throw new Error('expected mono')
  return { rate, samples: data }
}
function wav(rate, channels, samples) {
  const header = Buffer.alloc(44)
  header.write('RIFF', 0); header.writeUInt32LE(36 + samples.length * 2, 4); header.write('WAVE', 8)
  header.write('fmt ', 12); header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(channels, 22)
  header.writeUInt32LE(rate, 24); header.writeUInt32LE(rate * channels * 2, 28); header.writeUInt16LE(channels * 2, 32)
  header.writeUInt16LE(16, 34); header.write('data', 36); header.writeUInt32LE(samples.length * 2, 40)
  return Buffer.concat([header, Buffer.from(samples.buffer, samples.byteOffset, samples.length * 2)])
}
function trim(samples, rate) {
  const threshold = 160, margin = Math.round(rate * 0.012)
  let a = 0, b = samples.length - 1
  while (a < samples.length && Math.abs(samples[a]) < threshold) a++
  while (b > a && Math.abs(samples[b]) < threshold) b--
  return samples.slice(Math.max(0, a - margin), Math.min(samples.length, b + 1 + margin))
}

// ---------------------------------------------------------------- narration
const piper = join(root, 'tools/piper/piper.exe')
function synth(text) {
  const file = join(work, sha(Buffer.from(chunks.voice.lengthScale + '|' + text)).slice(0, 24) + '.wav')
  if (!existsSync(file)) {
    execFileSync(piper, ['--model', model, '--length_scale', chunks.voice.lengthScale, '--output_file', file],
      { input: text, stdio: ['pipe', 'ignore', 'ignore'] })
  }
  return parseWav(readFileSync(file))
}

const narrationRights = {
  source: 'Offline synthesis with Piper TTS 2023.11.14-2 (github.com/rhasspy/piper, MIT) using voice en_US-ljspeech-high',
  provider: 'LOCAL_PIPER',
  license: 'Voice model public domain (author statement at brycebeattie.com/files/tts; dataset LJ Speech public domain); Piper engine MIT',
  evidenceRef: 'ASSETS_AND_RIGHTS.md#narration; MODEL_CARD sha256 289f7421072d689a3d91f6c632f486af46f9b8b04417536104a4ea667b8a394b; model sha256 5d4f08ba6a2a48c44592eed3ce56bf85e9de3dd4e20df90541ae68a8310c029a',
  verifiedBy: VERIFIED_BY, verifiedAt, commercialUse: true, publish: true, redistribution: true,
  conditions: 'Synthetic voice; label as AI-generated narration where platform policy requires',
}
const imageRights = {
  source: 'Procedurally drawn with Java2D by backend/src/test/media-fixtures/GenerateImages.java (no third-party artwork)',
  provider: 'AI_STUDIO_SELF_MADE',
  license: 'Project-owned original work; question-mark glyphs drawn with Noto Sans KR Bold (SIL OFL 1.1)',
  evidenceRef: 'ASSETS_AND_RIGHTS.md#images',
  verifiedBy: VERIFIED_BY, verifiedAt, commercialUse: true, publish: true, redistribution: true, conditions: '',
}
const soundRights = {
  source: 'Synthesised from oscillators and noise by backend/src/test/media-fixtures/generate-catalog.mjs',
  provider: 'AI_STUDIO_SELF_MADE',
  license: 'Project-owned original work; no samples or third-party recordings',
  evidenceRef: 'ASSETS_AND_RIGHTS.md#music-and-sound-effects',
  verifiedBy: VERIFIED_BY, verifiedAt, commercialUse: true, publish: true, redistribution: true, conditions: '',
}

const images = { 'neutron-star': ['neutron-1.png', 'neutron-2.png', 'neutron-3.jpg', 'neutron-4.png', 'neutron-5.png'],
  'venus-day': ['venus-1.png', 'venus-2.png', 'venus-3.png'] }
const catalog = { schemaVersion: 1, catalogId: CATALOG, images: [], narrations: [], audio: [], fonts: [] }
const report = []
for (const topic of chunks.topics) {
  const fixture = JSON.parse(readFileSync(join(repo, 'backend/src/main/resources/planning-fixtures', topic.key + '.json'), 'utf8'))
  const text = Object.fromEntries(fixture.segments.map((s) => [s.id, s.narration]))
  if (fixture.scenes.length !== topic.scenes.length) throw new Error(topic.key + ': scene count differs')
  let total = 0
  topic.scenes.forEach((scene, i) => {
    const plan = fixture.scenes[i]
    if (JSON.stringify(plan.segmentIds) !== JSON.stringify(scene.segments.map((s) => s.id))) throw new Error('segment ids differ')
    const rate = 22050
    const parts = [new Int16Array(Math.round(chunks.pauses.lead * rate))]
    let cursor = parts[0].length, cueIndex = 0
    const segments = []
    const cueCount = scene.segments.reduce((n, s) => n + s.cues.length, 0)
    for (const segment of scene.segments) {
      if (segment.cues.join(' ') !== text[segment.id]) throw new Error(`${topic.key} ${segment.id}: cue text differs from script`)
      const cues = []
      for (const cue of segment.cues) {
        const audio = synth(cue)
        if (audio.rate !== rate) throw new Error('unexpected Piper rate')
        const trimmed = trim(audio.samples, rate)
        cues.push({ text: cue, startSample: cursor, endSample: cursor + trimmed.length })
        parts.push(trimmed)
        cursor += trimmed.length
        const last = cue.at(-1)
        let pause = last === ',' ? chunks.pauses.comma : '.?:!'.includes(last) ? chunks.pauses.sentence : chunks.pauses.split
        if ((scene.beatAfter ?? []).includes(cueIndex)) pause = chunks.pauses.beat
        if (cueIndex === cueCount - 1) pause = chunks.pauses.tail
        const gap = new Int16Array(Math.round(pause * rate))
        parts.push(gap)
        cursor += gap.length
        cueIndex++
      }
      segments.push({ segmentId: segment.id, cues })
    }
    const samples = new Int16Array(cursor)
    let at = 0
    for (const p of parts) { samples.set(p, at); at += p.length }
    const file = `audio/${topic.key}-${i + 1}.wav`
    const bytes = wav(rate, 1, samples)
    writeFileSync(join(out, file), bytes)
    const narration = plan.segmentIds.map((id) => text[id]).join(' ')
    catalog.narrations.push({ id: `${topic.key}-${i + 1}`, voiceId: chunks.voice.voiceId, language: chunks.voice.language,
      text: narration, file, sha256: sha(bytes), bytes: bytes.length, sampleRate: rate, timingSource: 'VERIFIED_FIXTURE',
      segments, emphasis: scene.emphasis, rights: narrationRights })
    const imageFile = `images/${images[topic.key][i]}`
    const imageBytes = readFileSync(join(out, imageFile))
    catalog.images.push({ id: `${topic.key}-${i + 1}`, promptSha256: sha(Buffer.from(plan.imagePrompt, 'utf8')), file: imageFile,
      mimeType: imageFile.endsWith('.jpg') ? 'image/jpeg' : 'image/png', sha256: sha(imageBytes), bytes: imageBytes.length,
      rights: imageRights })
    total += samples.length
  })
  report.push(`${topic.key}: ${(total / 22050).toFixed(3)} s narration`)
}

// ---------------------------------------------------------------- music and effects (48 kHz stereo)
const R = 48000
function stereo(fn, seconds) {
  const n = Math.round(seconds * R), s = new Int16Array(n * 2)
  for (let i = 0; i < n; i++) {
    const [l, r] = fn(i / R, i)
    s[2 * i] = Math.max(-32767, Math.min(32767, Math.round(l * 32767)))
    s[2 * i + 1] = Math.max(-32767, Math.min(32767, Math.round(r * 32767)))
  }
  return s
}
const hz = (midi) => 440 * 2 ** ((midi - 69) / 12)
function pluck(t, f, decay) {
  if (t < 0) return 0
  const env = Math.exp(-t * decay) * Math.min(1, t * 400)
  return env * (Math.sin(2 * Math.PI * f * t) + 0.35 * Math.sin(4 * Math.PI * f * t) + 0.12 * Math.sin(6 * Math.PI * f * t))
}
// Light pizzicato comedy bed: 112 BPM, Am F C G, bass on beats, off-beat chord plucks, 8 bars = loopable.
const beat = 60 / 112
const chords = [[57, 60, 64], [53, 57, 60], [48, 52, 55], [55, 59, 62]]
const bars = 8, loop = bars * 4 * beat
let seed = 7
const rand = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648)
const melody = Array.from({ length: bars * 8 }, () => (rand() < 0.55 ? Math.floor(rand() * 3) : -1))
const music = stereo((t) => {
  let l = 0, r = 0
  for (let k = -1; k <= 0; k++) {
    const tt = t - k * 0 // single pass; notes are scheduled within the loop and wrap below
    const position = (tt % loop + loop) % loop
    const bar = Math.floor(position / (4 * beat))
    const chord = chords[bar % 4]
    const inBar = position - bar * 4 * beat
    const beatIndex = Math.floor(inBar / beat)
    const sinceBeat = inBar - beatIndex * beat
    const bass = pluck(sinceBeat, hz(chord[0] - 24 + (beatIndex % 2 ? 7 : 0)), 7) * 0.55
    const off = sinceBeat - beat / 2
    let comp = 0
    for (const note of chord) comp += pluck(off, hz(note), 11) * 0.16
    const eighth = Math.floor(inBar / (beat / 2))
    const m = melody[bar * 8 + eighth]
    const lead = m >= 0 ? pluck(inBar - eighth * beat / 2, hz(chord[m] + 12), 9) * 0.22 : 0
    l += bass + comp * 0.9 + lead * 0.6
    r += bass + comp * 0.6 + lead * 0.9
  }
  return [l * 0.5, r * 0.5]
}, loop)
// Short loop crossfade-free: the pattern restarts on a bar line with all notes decayed by then.
const sting = stereo((t) => {
  let v = 0
  ;[72, 76, 79, 84].forEach((n, i) => { v += pluck(t - i * 0.07, hz(n), 8) * 0.3 })
  return [v, v]
}, 0.9)
let noise = 1
const white = () => ((noise = (noise * 16807) % 2147483647) / 2147483647) * 2 - 1
let lp1 = 0, lp2 = 0
const whoosh = stereo((t) => {
  const d = 0.7, x = t / d
  const cutoff = 0.02 + 0.25 * Math.sin(Math.PI * Math.min(1, x))
  lp1 += cutoff * (white() - lp1)
  lp2 += cutoff * (lp1 - lp2)
  const env = Math.sin(Math.PI * Math.min(1, x)) ** 2
  const v = (lp1 - lp2) * env * 2.2
  return [v * (1 - x * 0.6), v * (0.4 + x * 0.6)]
}, 0.7)
const boing = stereo((t) => {
  const f = 90 + 180 * Math.exp(-t * 9)
  const vib = 1 + 0.06 * Math.sin(2 * Math.PI * 14 * t)
  const env = Math.exp(-t * 5.5) * Math.min(1, t * 300)
  const v = env * (Math.sin(2 * Math.PI * f * vib * t) * 0.8 + 0.2 * Math.sin(4 * Math.PI * f * t))
  return [v * 0.7, v * 0.7]
}, 0.6)
for (const [id, kind, samples, gainDb] of [['bgm-pizzicato', 'MUSIC', music, -20], ['sfx-sting', 'SFX_HOOK', sting, -12],
  ['sfx-whoosh', 'SFX_TRANSITION', whoosh, -14], ['sfx-boing', 'SFX_PUNCH', boing, -10]]) {
  const file = `audio/${id}.wav`
  const bytes = wav(R, 2, samples)
  writeFileSync(join(out, file), bytes)
  catalog.audio.push({ id, kind, file, sha256: sha(bytes), bytes: bytes.length, gainDb, rights: soundRights })
}

// ---------------------------------------------------------------- font rights record
const font = readFileSync(join(root, 'downloads/font/NotoSansKR-Bold.otf'))
catalog.fonts.push({ id: 'noto-sans-kr-bold', family: 'Noto Sans KR', sha256: sha(font), rights: {
  source: 'Noto Sans CJK / Noto Sans KR static Bold, github.com/notofonts/noto-cjk Sans/SubsetOTF/KR/NotoSansKR-Bold.otf',
  provider: 'Google / Adobe (Noto project)', license: 'SIL Open Font License 1.1',
  evidenceRef: 'ASSETS_AND_RIGHTS.md#font; LICENSE sha256 6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2',
  verifiedBy: VERIFIED_BY, verifiedAt, commercialUse: true, publish: true, redistribution: true,
  conditions: 'OFL 1.1: keep the licence with any redistributed font file; do not sell the font by itself' } })

writeFileSync(join(out, 'catalog.json'), JSON.stringify(catalog, null, 2) + '\n')
console.log(report.join('\n'))
console.log('catalog sha256', sha(readFileSync(join(out, 'catalog.json'))))
