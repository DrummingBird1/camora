#!/usr/bin/env node
// Minimal RTSP server for testing the app without a real camera.
// Serves an Annex B H.264 file in a loop as RTP over TCP (interleaved), to any number of clients.
//
//   ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30:duration=20 -c:v libx264 -profile:v baseline \
//          -g 30 -bf 0 -pix_fmt yuv420p -bsf:v h264_metadata=aud=insert -f h264 test.h264
//   node tools/dev/rtsp-test-server.js test.h264 8556 30
//
// From the Android emulator use rtsp://10.0.2.2:8556/live
'use strict';
const net = require('net');
const fs = require('fs');

const [, , file, portArg, fpsArg, audioArg] = process.argv;
if (!file) {
  console.error('usage: node rtsp-test-server.js <file.h264> [port] [fps] [audio|beep]');
  process.exit(1);
}
const port = Number(portArg) || 8554;
const fps = Number(fpsArg) || 30;
// With "audio" the server also sends a 440 Hz tone as G.711 mu-law (RTP payload type 0, 8 kHz).
// With "beep" the tone only sounds for the first 100 ms of every second, for lip-sync tests with a
// clip that flashes at the same moments (see docs/TESTING.md).
const withAudio = audioArg === 'audio' || audioArg === 'beep';
const beep = audioArg === 'beep';
const silence = Buffer.alloc(160, 0xff); // mu-law zero

function muLaw(sample) {
  const BIAS = 0x84;
  const CLIP = 32635;
  let sign = (sample >> 8) & 0x80;
  if (sign) sample = -sample;
  if (sample > CLIP) sample = CLIP;
  sample += BIAS;
  let exponent = 7;
  for (let mask = 0x4000; (sample & mask) === 0 && exponent > 0; exponent--, mask >>= 1);
  const mantissa = (sample >> (exponent + 3)) & 0x0f;
  return ~(sign | (exponent << 4) | mantissa) & 0xff;
}
// One second of tone: exactly 440 cycles, so it loops without a click.
const tone = Buffer.alloc(8000);
for (let i = 0; i < 8000; i++) tone[i] = muLaw(Math.round(Math.sin((2 * Math.PI * 440 * i) / 8000) * 12000));

function splitNals(buf) {
  const out = [];
  let start = -1;
  let i = 0;
  while (i + 2 < buf.length) {
    if (buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 1) {
      if (start >= 0) {
        let e = i;
        while (e > start && buf[e - 1] === 0) e--;
        out.push(buf.subarray(start, e));
      }
      start = i + 3;
      i += 3;
    } else {
      i++;
    }
  }
  if (start >= 0) out.push(buf.subarray(start));
  return out;
}

// Group NAL units into access units. An access unit delimiter (type 9) starts each one.
const aus = [];
let cur = null;
let sps = null;
let pps = null;
for (const n of splitNals(fs.readFileSync(file))) {
  const t = n[0] & 31;
  if (t === 9) {
    if (cur && cur.length) aus.push(cur);
    cur = [];
    continue;
  }
  if (!cur) cur = [];
  if (t === 7 && !sps) sps = n;
  if (t === 8 && !pps) pps = n;
  cur.push(n);
}
if (cur && cur.length) aus.push(cur);
if (!aus.length || !sps || !pps) {
  console.error('no usable H.264 access units found (was the file made with aud=insert?)');
  process.exit(1);
}

const sdp = [
  'v=0',
  'o=- 0 0 IN IP4 127.0.0.1',
  's=test',
  'c=IN IP4 0.0.0.0',
  't=0 0',
  'm=video 0 RTP/AVP 96',
  'a=rtpmap:96 H264/90000',
  `a=fmtp:96 packetization-mode=1;sprop-parameter-sets=${sps.toString('base64')},${pps.toString('base64')};profile-level-id=${sps.subarray(1, 4).toString('hex')}`,
  'a=control:track0',
  ...(withAudio ? ['m=audio 0 RTP/AVP 0', 'a=rtpmap:0 PCMU/8000', 'a=control:track1'] : []),
  '',
].join('\r\n');

const clients = new Set();

const elapsedNow = () => Number(process.hrtime.bigint() - startTime) / 1e9;

function rtp(client, payload, marker, ts) {
  const header = Buffer.alloc(12);
  header[0] = 0x80;
  header[1] = (marker ? 0x80 : 0) | 96;
  header.writeUInt16BE(client.seq++ & 0xffff, 2);
  header.writeUInt32BE(ts >>> 0, 4);
  header.writeUInt32BE(0x1234abcd, 8);
  const packet = Buffer.concat([header, payload]);
  const frame = Buffer.alloc(4);
  frame[0] = 0x24;
  frame[1] = client.channel;
  frame.writeUInt16BE(packet.length, 2);
  client.socket.write(Buffer.concat([frame, packet]));
}

function sendAu(client, au, ts) {
  au.forEach((nal, j) => {
    const lastNal = j === au.length - 1;
    if (nal.length <= 1400) {
      rtp(client, nal, lastNal, ts);
      return;
    }
    const indicator = (nal[0] & 0xe0) | 28;
    let off = 1;
    while (off < nal.length) {
      const len = Math.min(1400, nal.length - off);
      const first = off === 1;
      const last = off + len >= nal.length;
      const fuHeader = (first ? 0x80 : 0) | (last ? 0x40 : 0) | (nal[0] & 31);
      rtp(client, Buffer.concat([Buffer.from([indicator, fuHeader]), nal.subarray(off, off + len)]), lastNal && last, ts);
      off += len;
    }
  });
}

function sendFrame(frame) {
  const au = aus[frame % aus.length];
  const key = au.some((n) => (n[0] & 31) === 5);
  for (const c of clients) {
    if (!c.playing) continue;
    if (!c.started) {
      if (!key) continue;
      c.started = true;
    }
    // RTP time counts from the moment of PLAY, matching the rtptime announced in RTP-Info, so a
    // player can line video and audio up even though video waits for the next key frame.
    const ts = c.videoBase + Math.round((frame / fps - c.playTime) * 90000);
    try {
      sendAu(c, au, ts);
    } catch (e) {
      clients.delete(c);
    }
  }
}

function sendAudioPacket(index) {
  for (const c of clients) {
    if (!c.playing || c.audioChannel < 0) continue;
    const header = Buffer.alloc(12);
    header[0] = 0x80;
    header[1] = 0; // PCMU, no marker
    header.writeUInt16BE(c.audioSeq++ & 0xffff, 2);
    header.writeUInt32BE((c.audioBase + Math.round((index / 50 - c.playTime) * 8000)) >>> 0, 4);
    header.writeUInt32BE(0x5678abcd, 8);
    const payload = beep && index % 50 >= 5 ? silence : tone.subarray((index * 160) % 8000, ((index * 160) % 8000) + 160);
    const packet = Buffer.concat([header, payload]);
    const frame = Buffer.alloc(4);
    frame[0] = 0x24;
    frame[1] = c.audioChannel;
    frame.writeUInt16BE(packet.length, 2);
    try {
      c.socket.write(Buffer.concat([frame, packet]));
    } catch (e) {
      clients.delete(c);
    }
  }
}

// Schedule by wall clock instead of setInterval(1000 / fps): on Windows timers tick in ~15.6 ms
// steps, which turns a 30 fps interval into about 21 fps.
const startTime = process.hrtime.bigint();
let nextFrame = 0;
let nextAudio = 0;
setInterval(() => {
  const elapsed = Number(process.hrtime.bigint() - startTime) / 1e9;
  const due = Math.floor(elapsed * fps);
  while (nextFrame <= due) sendFrame(nextFrame++);
  if (withAudio) {
    const dueAudio = Math.floor(elapsed * 50);
    while (nextAudio <= dueAudio) sendAudioPacket(nextAudio++);
  }
}, 4);

net
  .createServer((socket) => {
    const client = {
      socket, playing: false, started: false, seq: 0, channel: 0, audioChannel: -1, audioSeq: 0,
      playTime: 0, videoBase: 0, audioBase: 0,
    };
    clients.add(client);
    let buf = Buffer.alloc(0);
    const reply = (cseq, status, headers = {}, body = '') => {
      let r = `RTSP/1.0 ${status}\r\nCSeq: ${cseq}\r\n`;
      for (const [k, v] of Object.entries(headers)) r += `${k}: ${v}\r\n`;
      if (body) r += `Content-Length: ${Buffer.byteLength(body)}\r\n`;
      socket.write(r + '\r\n' + body);
    };
    socket.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      for (;;) {
        // Skip interleaved RTCP frames sent by the client.
        while (buf.length >= 4 && buf[0] === 0x24) {
          const len = buf.readUInt16BE(2);
          if (buf.length < 4 + len) return;
          buf = buf.subarray(4 + len);
        }
        const end = buf.indexOf('\r\n\r\n');
        if (end < 0) return;
        const text = buf.subarray(0, end).toString('utf8');
        buf = buf.subarray(end + 4);
        const lines = text.split('\r\n');
        const [method, url] = lines[0].split(' ');
        const h = {};
        lines.slice(1).forEach((l) => {
          const i = l.indexOf(':');
          if (i > 0) h[l.slice(0, i).trim().toLowerCase()] = l.slice(i + 1).trim();
        });
        const cseq = h['cseq'] || '0';
        console.log(method, url);
        if (method === 'OPTIONS') {
          reply(cseq, '200 OK', { Public: 'OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN' });
        } else if (method === 'DESCRIBE') {
          reply(cseq, '200 OK', { 'Content-Type': 'application/sdp', 'Content-Base': url.replace(/\/?$/, '/') }, sdp);
        } else if (method === 'SETUP') {
          const transport = h['transport'] || '';
          if (!transport.includes('RTP/AVP/TCP')) {
            reply(cseq, '461 Unsupported Transport');
          } else {
            const m = /interleaved=(\d+)-(\d+)/.exec(transport);
            const channel = m ? Number(m[1]) : 0;
            if (url.endsWith('track1')) client.audioChannel = channel;
            else client.channel = channel;
            reply(cseq, '200 OK', {
              Transport: `RTP/AVP/TCP;unicast;interleaved=${channel}-${channel + 1}`,
              Session: '12345678;timeout=60',
            });
          }
        } else if (method === 'PLAY') {
          client.playTime = elapsedNow();
          client.videoBase = Math.floor(Math.random() * 0x40000000);
          client.audioBase = Math.floor(Math.random() * 0x40000000);
          client.playing = true;
          const base = url.replace(/\/?$/, '/');
          const info = [`url=${base}track0;seq=0;rtptime=${client.videoBase}`];
          if (client.audioChannel >= 0) info.push(`url=${base}track1;seq=0;rtptime=${client.audioBase}`);
          reply(cseq, '200 OK', { Session: '12345678', Range: 'npt=0.000-', 'RTP-Info': info.join(',') });
        } else if (method === 'TEARDOWN') {
          reply(cseq, '200 OK', { Session: '12345678' });
          socket.end();
        } else {
          reply(cseq, '200 OK');
        }
      }
    });
    socket.on('close', () => clients.delete(client));
    socket.on('error', () => clients.delete(client));
  })
  .listen(port, () =>
    console.log(`RTSP test server on rtsp://0.0.0.0:${port}/live (${aus.length} frames, loop); frame 0 time (epoch ms): ${Date.now() - Number(process.hrtime.bigint() - startTime) / 1e6}`)
  );
