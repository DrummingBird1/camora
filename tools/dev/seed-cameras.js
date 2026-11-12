#!/usr/bin/env node
// Writes the DataStore file that holds the camera list, so a debug build can start with cameras
// without typing into the UI.
//
//   node tools/dev/seed-cameras.js <out.preferences_pb> <cameras.json>
//
// cameras.json is an array. Only "id", "name" and "type" ("RTSP" or "UVC") are required:
//   { "id": "cam-a", "name": "Front", "type": "RTSP", "url": "rtsp://127.0.0.1:8556/live",
//     "motion": false, "autoRecord": false, "shared": true,
//     "sensitivity": 1, "maxHeight": 0, "bitrate": 1, "audio": true, "uvcSize": "", "tcp": true }
//
// Plain-text URLs are fine: the app accepts them and encrypts them the next time it saves.
// See docs/TESTING.md for how to push the file into the app (debug builds only).
'use strict';
const fs = require('fs');

const [, , outFile, jsonFile] = process.argv;
if (!outFile || !jsonFile) {
  console.error('usage: node tools/dev/seed-cameras.js <out.preferences_pb> <cameras.json>');
  process.exit(1);
}
const cameras = JSON.parse(fs.readFileSync(jsonFile, 'utf8'));
if (!Array.isArray(cameras)) throw new Error('cameras.json must be an array');
for (const c of cameras) {
  if (!c.id || !c.name || !['RTSP', 'UVC'].includes(c.type)) throw new Error('each camera needs id, name and type RTSP|UVC');
}

// Preferences DataStore file = protobuf: map<string, Value>; Value.string is field 5.
const varint = (n) => {
  const out = [];
  while (n > 127) { out.push((n & 127) | 128); n >>= 7; }
  out.push(n);
  return Buffer.from(out);
};
const field = (tag, buf) => Buffer.concat([Buffer.from([tag]), varint(buf.length), buf]);
const key = field(0x0a, Buffer.from('cameras_json'));
const value = field(0x12, field(0x2a, Buffer.from(JSON.stringify(cameras))));
fs.writeFileSync(outFile, field(0x0a, Buffer.concat([key, value])));
console.log(`wrote ${cameras.length} camera(s) to ${outFile}`);
