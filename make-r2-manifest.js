// Run:  node make-r2-manifest.js        (Node.js only, no packages)
//
// Writes assets.json for the Android app. Upload to the ROOT of your Cloudflare R2 bucket
// (the same bucket as encoder-model.int8.onnx):
//    assets.json
//    bgm/...    best hook/...    transitionsfx/...    broll/...    frames/... (optional)
// The app downloads the model + these folders together, once, and later only what changed
// (files are compared by SHA-256). Run this again and re-upload assets.json whenever you add,
// rename or remove files.
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const here = __dirname;
const AUD = /\.(mp3|wav|ogg|m4a|aac|flac|opus)$/i;
const TRN = /\.(mp3|wav|ogg|m4a|aac|flac|opus|mp4|mov|webm|m4v)$/i;
const VIS = /\.(mp4|mov|webm|m4v|gif|png|jpe?g|webp)$/i;
const PIC = /\.(png|jpe?g|webp)$/i; // frame shapes: white shape on black
const norm = n => n.toLowerCase().replace(/[^a-z0-9]/g, '');

function walk(dir, re, kind, out) {
  if (!fs.existsSync(dir)) return;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full, re, kind, out);
    else if (re.test(e.name)) {
      const buf = fs.readFileSync(full);
      out.push({
        path: path.relative(here, full).split(path.sep).join('/'),
        kind,
        size: buf.length,
        sha256: crypto.createHash('sha256').update(buf).digest('hex'),
      });
    }
  }
}

const dirs = fs.readdirSync(here, { withFileTypes: true }).filter(d => d.isDirectory()).map(d => d.name);
const find = test => dirs.find(d => test(norm(d)));
const files = [];
const bgm = find(n => n === 'bgm'), hook = find(n => n === 'besthook');
const trans = find(n => /^trans[a-z]*sfx$/.test(n)), broll = find(n => n === 'broll' || n === 'brolls');
const frames = find(n => n === 'frames' || n === 'frame');
if (bgm) walk(path.join(here, bgm), AUD, 'bgm', files);
if (hook) walk(path.join(here, hook), AUD, 'sfx', files);
if (trans) walk(path.join(here, trans), TRN, 'trans', files);
if (broll) walk(path.join(here, broll), VIS, 'broll', files);
if (frames) walk(path.join(here, frames), PIC, 'frame', files);
files.sort((a, b) => a.path.localeCompare(b.path));

fs.writeFileSync(path.join(here, 'assets.json'), JSON.stringify({ version: Date.now(), files }, null, 1));
const count = k => files.filter(f => f.kind === k).length;
const mb = (files.reduce((s, f) => s + f.size, 0) / 1048576).toFixed(1);
console.log(`assets.json: ${count('bgm')} music, ${count('sfx')} hook SFX, ${count('trans')} transition SFX, ${count('broll')} B-roll, ${count('frame')} frames (${mb} MB)`);
console.log('Upload assets.json and the folders to the root of your R2 bucket (keep the folder names).');
