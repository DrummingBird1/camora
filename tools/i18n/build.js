#!/usr/bin/env node
// Generates res/values*/strings.xml and res/xml/locales_config.xml from tools/i18n/catalog.js.
// Usage: node tools/i18n/build.js
const fs = require('fs');
const path = require('path');
const { langs, rows } = require('./catalog');

const res = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'res');

function esc(s) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/\\/g, '\\\\')
    .replace(/'/g, "\\'")
    .replace(/"/g, '\\"')
    .replace(/^([@?])/, '\\$1')
    .replace(/^ /, '\\u0020')
    .replace(/ $/, '\\u0020');
}

const seen = new Set();
for (const r of rows) {
  if (r.length !== langs.length + 1) throw new Error(`row ${r[0]} has ${r.length - 1} languages, expected ${langs.length}`);
  if (seen.has(r[0])) throw new Error(`duplicate key ${r[0]}`);
  seen.add(r[0]);
  // The same placeholders must appear in every language.
  const ph = (s) => (s.match(/%\d\$[sd]/g) || []).sort().join(',');
  for (let i = 2; i < r.length; i++) {
    if (ph(r[i]) !== ph(r[1])) throw new Error(`placeholder mismatch in ${r[0]} (${langs[i - 1]})`);
  }
}

// Android uses "iw" for Hebrew in resource folders; BCP-47 "he" in the locale config.
const folder = (l) => (l === 'en' ? 'values' : l === 'he' ? 'values-iw' : `values-${l}`);

langs.forEach((lang, idx) => {
  const dir = path.join(res, folder(lang));
  fs.mkdirSync(dir, { recursive: true });
  const body = rows
    .map((r) => {
      const extra = r[0] === 'app_name' ? '' : '';
      return `    <string name="${r[0]}"${extra}>${esc(r[idx + 1])}</string>`;
    })
    .join('\n');
  fs.writeFileSync(path.join(dir, 'strings.xml'), `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n${body}\n</resources>\n`);
});

fs.mkdirSync(path.join(res, 'xml'), { recursive: true });
fs.writeFileSync(
  path.join(res, 'xml', 'locales_config.xml'),
  `<?xml version="1.0" encoding="utf-8"?>\n<locale-config xmlns:android="http://schemas.android.com/apk/res/android">\n${langs
    .map((l) => `    <locale android:name="${l}" />`)
    .join('\n')}\n</locale-config>\n`
);
console.log(`wrote ${langs.length} languages, ${rows.length} strings`);
