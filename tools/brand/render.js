#!/usr/bin/env node
// Renders the brand images from assets/brand/mark.svg and assets/brand/brand.json with headless Chrome
// (or Edge), so every image uses the same drawing:
//   assets/brand/logo.svg + logo-1024.png   app logo on its rounded square
//   assets/brand/banner.png (1280x640)      README header and GitHub social preview
//   assets/store/icon-512.png               Play Store icon
//   assets/store/feature-graphic.png        Play Store feature graphic (1024x500)
// Usage: node tools/brand/render.js
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const root = path.join(__dirname, '..', '..');
const brandDir = path.join(root, 'assets', 'brand');
const storeDir = path.join(root, 'assets', 'store');
const brand = JSON.parse(fs.readFileSync(path.join(brandDir, 'brand.json'), 'utf8'));
const mark = fs.readFileSync(path.join(brandDir, 'mark.svg'), 'utf8').replace(/<!--[\s\S]*?-->/g, '');
const shot = (name) => 'data:image/png;base64,' + fs.readFileSync(path.join(storeDir, 'screenshots', name)).toString('base64');

const browsers = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/google-chrome', '/usr/bin/chromium', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
];
const browser = browsers.find((b) => fs.existsSync(b));
if (!browser) throw new Error('Chrome or Edge is needed to render the images');

// Each SVG copy needs its own gradient ids, or the browser mixes them up.
let copies = 0;
function markSvg(size) {
  const n = ++copies;
  return mark.replace(/id="(\w+)"/g, `id="$1${n}"`).replace(/url\(#(\w+)\)/g, `url(#$1${n})`)
    .replace('<svg ', `<svg width="${size}" height="${size}" `);
}

const BG = `radial-gradient(circle at 30% 35%, rgba(184,164,255,0.28), transparent 55%),
  radial-gradient(circle at 85% 80%, rgba(255,143,184,0.16), transparent 50%),
  linear-gradient(135deg, #2B2155 0%, #1B1B22 55%, #121019 100%)`;
const FONT = `'Segoe UI Variable Display', 'Segoe UI', Inter, system-ui, sans-serif`; // single quotes: it goes inside style="..."

function page(w, h, body) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;width:${w}px;height:${h}px;overflow:hidden;background:transparent}
*{box-sizing:border-box}
</style></head><body>${body}</body></html>`;
}

function render(name, w, h, html, out) {
  const tmp = path.join(os.tmpdir(), `camora-${name}.html`);
  fs.writeFileSync(tmp, page(w, h, html));
  execFileSync(browser, [
    '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
    '--default-background-color=00000000', `--window-size=${w},${h}`, `--screenshot=${out}`,
    'file:///' + tmp.replace(/\\/g, '/'),
  ], { stdio: 'ignore' });
  fs.unlinkSync(tmp);
  console.log('wrote', path.relative(root, out));
}

// The app logo: the mark on the brand's rounded square.
function logoSvg() {
  const inner = mark.replace(/^<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '');
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
  <defs>
    <linearGradient id="tile" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#33275F"/><stop offset="0.6" stop-color="#1B1B22"/><stop offset="1" stop-color="#121019"/>
    </linearGradient>
    <radialGradient id="tileGlow" cx="0.32" cy="0.3" r="0.7">
      <stop offset="0" stop-color="#B8A4FF" stop-opacity="0.35"/><stop offset="1" stop-color="#B8A4FF" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="512" height="512" rx="114" fill="url(#tile)"/>
  <rect width="512" height="512" rx="114" fill="url(#tileGlow)"/>
  <rect x="1.5" y="1.5" width="509" height="509" rx="112.5" fill="none" stroke="#FFFFFF" stroke-opacity="0.08" stroke-width="3"/>
  <g transform="translate(256 256) scale(0.84) translate(-256 -256)">${inner}</g>
</svg>
`;
}
fs.writeFileSync(path.join(brandDir, 'logo.svg'), logoSvg());
const logo = (size) => logoSvg().replace('<svg ', `<svg width="${size}" height="${size}" `);

render('logo', 1024, 1024, logo(1024), path.join(brandDir, 'logo-1024.png'));
render('icon', 512, 512, logo(512), path.join(storeDir, 'icon-512.png'));

const chips = ['IP cameras', 'Capture cards', 'Motion recording', 'Stream to any screen']
  .map((c) => `<span style="padding:10px 20px;border-radius:999px;background:rgba(184,164,255,0.12);border:1px solid rgba(184,164,255,0.35);color:#E6DEFF;font-size:21px;font-weight:600">${c}</span>`)
  .join('');

// README header and social preview: logo and name on the left, the app on a phone on the right.
render('banner', 1280, 640, `
<div style="position:relative;width:1280px;height:640px;background:${BG};font-family:${FONT};color:#F2EFFC;overflow:hidden;border-radius:28px">
  <div style="position:absolute;inset:0;background-image:radial-gradient(rgba(255,255,255,0.06) 1px, transparent 1px);background-size:28px 28px"></div>
  <div style="position:absolute;left:84px;top:118px;width:640px">
    <div style="display:flex;align-items:center;gap:26px">
      ${markSvg(150)}
      <div style="font-size:112px;font-weight:700;letter-spacing:-2px;line-height:1">${brand.name}</div>
    </div>
    <div style="margin-top:34px;font-size:36px;line-height:1.3;color:#D9D2F2;font-weight:500">${brand.tagline_en}</div>
    <div style="margin-top:34px;display:flex;flex-wrap:wrap;gap:12px;width:600px">${chips}</div>
    <div style="margin-top:34px;font-size:22px;color:#A9A2C2">Free and open source &#183; No account &#183; No ads &#183; Android</div>
  </div>
  <div style="position:absolute;right:92px;top:58px;width:276px;height:600px;border-radius:44px;background:#0B0A10;padding:12px;box-shadow:0 30px 80px rgba(0,0,0,0.55),0 0 0 2px rgba(184,164,255,0.25);transform:rotate(4deg)">
    <div style="width:100%;height:100%;border-radius:34px;overflow:hidden;background:#1B1B22">
      <img src="${shot('en-1-cameras.png')}" style="width:100%;display:block">
    </div>
  </div>
</div>`, path.join(brandDir, 'banner.png'));

// Play Store feature graphic: no text near the edges, where Play may crop or overlay.
render('feature', 1024, 500, `
<div style="position:relative;width:1024px;height:500px;background:${BG};font-family:${FONT};color:#F2EFFC;overflow:hidden">
  <div style="position:absolute;inset:0;background-image:radial-gradient(rgba(255,255,255,0.06) 1px, transparent 1px);background-size:26px 26px"></div>
  <div style="position:absolute;left:0;right:0;top:118px;display:flex;flex-direction:column;align-items:center">
    <div style="display:flex;align-items:center;gap:22px">${markSvg(128)}
      <div style="font-size:100px;font-weight:700;letter-spacing:-2px;line-height:1">${brand.name}</div></div>
    <div style="margin-top:30px;font-size:32px;color:#D9D2F2;font-weight:500">${brand.tagline_en}</div>
  </div>
</div>`, path.join(storeDir, 'feature-graphic.png'));
