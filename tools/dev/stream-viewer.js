#!/usr/bin/env node
// Reads a live stream from the app for a while and prints how many bytes arrived each second
// (a zero means a stall). Optionally saves the data, so it can be inspected with ffprobe/ffmpeg.
//
//   node tools/dev/stream-viewer.js <url> [seconds=30] [outfile]
//
// Example: node tools/dev/stream-viewer.js "http://192.168.1.50:8080/stream/cam-a.mp4?k=KEY" 14 out.mp4
// It also prints the arrival time of the first chunks, which helps with start-up and latency checks.
'use strict';
const http = require('http');
const https = require('https');
const fs = require('fs');

const [, , url, secs, outfile] = process.argv;
if (!url) {
  console.error('usage: node tools/dev/stream-viewer.js <url> [seconds=30] [outfile]');
  process.exit(1);
}
const total = Number(secs) || 30;
const client = url.startsWith('https') ? https : http;
const options = url.startsWith('https') ? { rejectUnauthorized: false } : {}; // the app's HTTPS certificate is self-signed
const start = Date.now();
const perSecond = new Array(total + 1).fill(0);
const out = outfile ? fs.createWriteStream(outfile) : null;
let status = '';
let chunks = 0;
const firstChunks = [];

const req = client.get(url, options, (res) => {
  status = String(res.statusCode);
  res.on('data', (d) => {
    const now = Date.now();
    const s = Math.floor((now - start) / 1000);
    if (s <= total) {
      perSecond[s] += d.length;
      if (out) out.write(d);
    }
    if (chunks++ < 5) firstChunks.push(`chunk ${chunks} after ${now - start} ms (${d.length} bytes)`);
  });
  res.on('end', () => { status += ` (ended by the server after ${Math.floor((Date.now() - start) / 1000)} s)`; });
});
req.on('error', (e) => { status += ` error ${e.message}`; });

setTimeout(() => {
  if (out) out.end();
  console.log('status ' + status);
  firstChunks.forEach((l) => console.log(l));
  perSecond.slice(0, total).forEach((b, i) => console.log(`${i},${b}`));
  process.exit(0);
}, total * 1000 + 500);
