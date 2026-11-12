#!/usr/bin/env node
// Copies the store images into fastlane/metadata/android/<locale>/images, the layout F-Droid, IzzyOnDroid
// and fastlane read. The texts next to them (title, descriptions, changelogs) are written by hand.
//   node tools/brand/fastlane.js
'use strict';
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..');
const store = path.join(root, 'assets', 'store');
const locales = { 'en-US': 'en', 'iw-IL': 'he' }; // fastlane uses iw-IL for Hebrew, like Google Play

for (const [locale, shots] of Object.entries(locales)) {
  const images = path.join(root, 'fastlane', 'metadata', 'android', locale, 'images');
  const phone = path.join(images, 'phoneScreenshots');
  fs.rmSync(phone, { recursive: true, force: true });
  fs.mkdirSync(phone, { recursive: true });
  fs.copyFileSync(path.join(store, 'icon-512.png'), path.join(images, 'icon.png'));
  fs.copyFileSync(path.join(store, 'feature-graphic.png'), path.join(images, 'featureGraphic.png'));
  ['1-cameras', '2-stream', '3-recordings', '4-settings'].forEach((s, i) =>
    fs.copyFileSync(path.join(store, 'screenshots', `${shots}-${s}.png`), path.join(phone, `${i + 1}.png`)));
  console.log('images for', locale);
}
