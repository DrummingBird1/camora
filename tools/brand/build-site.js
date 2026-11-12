#!/usr/bin/env node
// Builds the static website (landing page and privacy policy, English and Hebrew) into site/
// from assets/brand/brand.json and docs/PRIVACY.md. Host the site/ folder anywhere static
// (GitHub Pages, Cloudflare Pages, Netlify). No dependencies.
//   node tools/brand/build-site.js
'use strict';
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..');
const brand = JSON.parse(fs.readFileSync(path.join(root, 'assets', 'brand', 'brand.json'), 'utf8'));
const out = path.join(root, 'site');
fs.mkdirSync(path.join(out, 'he'), { recursive: true });
fs.mkdirSync(path.join(out, 'assets'), { recursive: true });
for (const f of ['icon-512.png', 'feature-graphic.png']) {
  const src = path.join(root, 'assets', 'store', f);
  if (fs.existsSync(src)) fs.copyFileSync(src, path.join(out, 'assets', f));
}
const shots = path.join(root, 'assets', 'store', 'screenshots');
const shotFiles = fs.existsSync(shots) ? fs.readdirSync(shots).filter((f) => f.endsWith('.png')).sort() : [];
for (const f of shotFiles) fs.copyFileSync(path.join(shots, f), path.join(out, 'assets', f));

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const fill = (s) => s
  .replace(/IPCam Hub/g, brand.name)
  .replace(/\[DATE\]|\[תאריך\]/g, brand.policy_date)
  .replace(/\[EMAIL\]|\[אימייל\]/g, brand.contact_email);

// Just enough Markdown for the policy: headings, paragraphs, bullet lists, tables, bold, italics, links.
function inline(s) {
  return esc(s)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/_(.+?)_/g, '<em>$1</em>')
    .replace(/(https?:\/\/[^\s)<]+)/g, '<a href="$1">$1</a>');
}
function markdown(md) {
  const lines = md.split(/\r?\n/);
  const html = [];
  let list = false;
  let table = false;
  let para = [];
  const close = () => {
    if (para.length) { html.push(`<p>${inline(para.join(" "))}</p>`); para = []; }
    if (list) { html.push('</ul>'); list = false; }
    if (table) { html.push('</table>'); table = false; }
  };
  for (const line of lines) {
    const t = line.trim();
    if (!t) { close(); continue; }
    const h = /^(#{1,3})\s+(.*)$/.exec(t);
    if (h) { close(); html.push(`<h${h[1].length}>${inline(h[2])}</h${h[1].length}>`); continue; }
    if (t.startsWith('- ')) { if (!list) { close(); html.push('<ul>'); list = true; } html.push(`<li>${inline(t.slice(2))}</li>`); continue; }
    if (t.startsWith('|')) {
      if (/^\|[\s:-|]+\|$/.test(t)) continue; // separator row
      const cells = t.slice(1, -1).split('|').map((c) => inline(c.trim()));
      if (!table) { close(); html.push('<table>'); table = true; html.push('<tr>' + cells.map((c) => `<th>${c}</th>`).join('') + '</tr>'); continue; }
      html.push('<tr>' + cells.map((c) => `<td>${c}</td>`).join('') + '</tr>');
      continue;
    }
    if (list || table) close();
    para.push(t); // consecutive lines form one paragraph
  }
  close();
  return html.join('\n');
}

const policy = fill(fs.readFileSync(path.join(root, 'docs', 'PRIVACY.md'), 'utf8'))
  // The draft note is for the maintainer, not for readers.
  .split(/\r?\n/).filter((l) => !/^_(Draft|טיוטה)/.test(l.trim())).join('\n');
const [policyEn, policyHe] = policy.split(/^---$/m);

const css = `
:root{--bg:${brand.background};--accent:${brand.accent};--text:#ECEAF6;--muted:#B9B5CC;--card:#24242E}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:17px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans Hebrew",Arial,sans-serif}
a{color:var(--accent)}main{max-width:880px;margin:0 auto;padding:32px 20px 64px}
header{display:flex;align-items:center;gap:14px;justify-content:space-between;flex-wrap:wrap}
.logo{display:flex;align-items:center;gap:12px;color:var(--text);text-decoration:none;font-weight:600;font-size:22px}
.logo img{width:44px;height:44px;border-radius:12px}nav a{margin-inline-start:16px;text-decoration:none}
.hero{padding:56px 0 24px}.hero h1{font-size:44px;line-height:1.15;margin:0 0 12px}.hero p{color:var(--muted);font-size:20px;margin:0 0 28px}
.btn{display:inline-block;background:var(--accent);color:#1B1B22;padding:12px 22px;border-radius:12px;font-weight:600;text-decoration:none}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:16px;margin:40px 0}
.card{background:var(--card);border-radius:16px;padding:20px}.card h3{margin:0 0 6px;font-size:18px}.card p{margin:0;color:var(--muted)}
.shots{display:flex;gap:14px;overflow-x:auto;padding-bottom:8px}.shots img{height:420px;border-radius:18px}
table{border-collapse:collapse;width:100%;margin:12px 0}td,th{border:1px solid #3A3A48;padding:8px;text-align:start;vertical-align:top}
.donate{display:flex;flex-wrap:wrap;gap:12px;margin-top:8px}.support{margin:40px 0}
footer{margin-top:56px;color:var(--muted);font-size:14px}`;

function page({ lang, dir, title, body, base }) {
  const other = lang === 'he' ? { href: `${base}index.html`, label: 'English' } : { href: `${base}he/index.html`, label: 'עברית' };
  const t = lang === 'he' ? { home: 'בית', privacy: 'פרטיות' } : { home: 'Home', privacy: 'Privacy' };
  const home = lang === 'he' ? `${base}he/index.html` : `${base}index.html`;
  const priv = lang === 'he' ? `${base}he/privacy.html` : `${base}privacy.html`;
  return `<!doctype html>
<html lang="${lang}" dir="${dir}">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><meta name="description" content="${esc(lang === 'he' ? brand.tagline_he : brand.tagline_en)}">
<link rel="icon" href="${base}assets/icon-512.png"><style>${css}</style></head>
<body><main>
<header><a class="logo" href="${home}"><img src="${base}assets/icon-512.png" alt="">${esc(brand.name)}</a>
<nav><a href="${home}">${t.home}</a><a href="${priv}">${t.privacy}</a><a href="${other.href}">${other.label}</a></nav></header>
${body}
<footer>© ${new Date().getFullYear()} ${esc(brand.name)}</footer>
</main></body></html>
`;
}

const features = {
  en: [
    ['IP cameras', 'RTSP cameras with ONVIF discovery, automatic reconnect, TCP or UDP.'],
    ['Capture cards', 'USB HDMI capture cards (UVC) with sound, up to 60 fps.'],
    ['Recording', 'MP4 with sound, record on motion, automatic cleanup by age or size.'],
    ['Motion alerts', 'Motion detection with notifications, also in the background.'],
    ['On every screen', 'Browser with low delay, DLNA TVs, Chromecast and HLS players.'],
    ['Up to 4K, H.265', 'Choose resolution, quality and H.264 or H.265 per camera.'],
  ],
  he: [
    ['מצלמות IP', 'מצלמות RTSP עם חיפוש ONVIF, התחברות מחדש אוטומטית, TCP או UDP.'],
    ['כרטיסי לכידה', 'כרטיסי לכידה HDMI ב-USB (UVC) עם קול, עד 60 פריימים לשנייה.'],
    ['הקלטה', 'MP4 עם קול, הקלטה כשיש תנועה, מחיקה אוטומטית לפי גיל או נפח.'],
    ['התראות תנועה', 'זיהוי תנועה עם התראות, גם ברקע.'],
    ['על כל מסך', 'דפדפן בהשהיה נמוכה, טלוויזיות DLNA, Chromecast ונגני HLS.'],
    ['עד 4K, ‏H.265', 'בחירת רזולוציה, איכות ו-H.264 או H.265 לכל מצלמה.'],
  ],
};

function landing(lang) {
  const he = lang === 'he';
  const base = he ? '../' : '';
  const shotsHtml = shotFiles.filter((f) => f.startsWith(lang + '-')).map((f) => `<img src="${base}assets/${f}" alt="">`).join('');
  const body = `
<section class="hero"><h1>${esc(he ? brand.tagline_he : brand.tagline_en)}</h1>
<p>${he ? 'אפליקציית Android לצפייה, הקלטה ושידור. בלי חשבון, בלי פרסומות, הכל נשאר אצלך.' : 'An Android app to watch, record and stream. No account, no ads, everything stays on your devices.'}</p>
<a class="btn" href="#">${he ? 'בקרוב ב-Google Play' : 'Coming soon to Google Play'}</a></section>
${shotsHtml ? `<div class="shots">${shotsHtml}</div>` : ''}
<section class="grid">${features[lang].map(([t, d]) => `<div class="card"><h3>${esc(t)}</h3><p>${esc(d)}</p></div>`).join('')}</section>
${(brand.donate || []).length ? `<section class="support"><h2>${he ? 'תמיכה בפרויקט' : 'Support the project'}</h2>
<p>${he ? `${esc(brand.name)} חינמית ובקוד פתוח. אם היא עוזרת לך, אפשר לתמוך בפיתוח:` : `${esc(brand.name)} is free and open source. If it is useful to you, you can support its development:`}</p>
<div class="donate">${brand.donate.map((d) => `<a class="btn" style="background:#${d.color};color:${d.logoColor === 'black' ? '#1B1B22' : '#fff'}" href="${esc(d.url)}">${esc(d.name)}</a>`).join('')}</div></section>` : ''}
<p><a href="https://github.com/DrummingBird1/camora">${he ? 'קוד המקור בגיטהב' : 'Source code on GitHub'}</a></p>`;
  return page({ lang, dir: he ? 'rtl' : 'ltr', title: brand.name, body, base });
}

fs.writeFileSync(path.join(out, 'index.html'), landing('en'));
fs.writeFileSync(path.join(out, 'he', 'index.html'), landing('he'));
fs.writeFileSync(path.join(out, 'privacy.html'), page({ lang: 'en', dir: 'ltr', title: `${brand.name} privacy policy`, body: markdown(policyEn), base: '' }));
fs.writeFileSync(path.join(out, 'he', 'privacy.html'), page({ lang: 'he', dir: 'rtl', title: `מדיניות פרטיות – ${brand.name}`, body: markdown(policyHe), base: '../' }));
console.log(`site written to ${out} (${shotFiles.length} screenshots)`);
