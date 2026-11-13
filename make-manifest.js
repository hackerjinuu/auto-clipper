// Run:  node make-manifest.js      (needs Node.js only, no packages)
// Static hosts such as Vercel cannot list folders, so this writes two small files the editor reads instead:
//   broll.json    <- every video/image inside your "broll" folder
//   library.json  <- every audio file in "bgm" and "best hook"
// Run it again (and re-deploy) whenever you add, rename or remove files in those folders.
const fs = require('fs'), path = require('path');
const here = __dirname;
const AUD = /\.(mp3|wav|ogg|m4a|aac|flac|opus)$/i;
const TRN = /\.(mp3|wav|ogg|m4a|aac|flac|opus|mp4|mov|webm|m4v)$/i;   // transition SFX: audio or mp4 clips with sound
const VIS = /\.(mp4|mov|webm|m4v|gif|png|jpe?g|webp)$/i;

function walk(dir, re) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(full, re));
    else if (re.test(e.name)) {
      const st = fs.statSync(full);
      const rel = path.relative(here, full).split(path.sep).join('/');
      out.push({ url: rel.split('/').map(encodeURIComponent).join('/'), name: e.name, size: st.size, mtime: Math.floor(st.mtimeMs / 1000) });
    }
  }
  return out.sort((a, b) => a.url.toLowerCase().localeCompare(b.url.toLowerCase()));
}

const brollDir = fs.readdirSync(here, { withFileTypes: true })
  .find(d => d.isDirectory() && ['broll', 'brolls'].includes(d.name.toLowerCase().replace(/[^a-z0-9]/g, '')));
const broll = brollDir ? walk(path.join(here, brollDir.name), VIS) : [];
const transDir = fs.readdirSync(here, { withFileTypes: true })
  .find(d => d.isDirectory() && /^trans[a-z]*sfx$/.test(d.name.toLowerCase().replace(/[^a-z0-9]/g, '')));   // transitionsfx / transationsfx
const lib = { bgm: walk(path.join(here, 'bgm'), AUD), sfx: walk(path.join(here, 'best hook'), AUD), trans: transDir ? walk(path.join(here, transDir.name), TRN) : [] };

fs.writeFileSync(path.join(here, 'broll.json'), JSON.stringify({ folder: brollDir ? brollDir.name : 'broll', files: broll }));
fs.writeFileSync(path.join(here, 'library.json'), JSON.stringify(lib));
console.log(`broll.json: ${broll.length} B-roll file(s)` + (brollDir ? '' : '  (NO "broll" folder found next to this script!)'));
console.log(`library.json: ${lib.bgm.length} music track(s), ${lib.sfx.length} hook SFX, ${lib.trans.length} transition SFX`);