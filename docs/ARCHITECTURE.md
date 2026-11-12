# Architecture

## Data flow

```
 RTSP camera ─► PlayerFactory (ExoPlayer, low latency, reconnect) ─┐
 USB capture ─► UvcManager (UVCAndroid, one device) ──────────────┤  video frames rendered into a Surface
                                                                  ▼
                                   VideoEncoder (MediaCodec H.264, input Surface)
                                   AudioEncoder (MediaCodec AAC) ◄─ AudioTap (RTSP sound) / UvcAudioSource (USB audio)
                                                                  │  EncodedSink callbacks
                                                                  ▼
                                                       Mux (Outputs.kt)
                              ┌───────────────────────────────┼───────────────────────────────┐
                              ▼                               ▼                               ▼
                        Mp4Recorder                      LiveStream                       HlsPackager
                       (MediaMuxer, MP4)       (per-viewer fMP4 over chunked HTTP)     (shared-timeline fMP4 segments)
                                                              │                               │
                                                              ▼                               ▼
                                                     StreamServer (NanoHTTPD, HTTP + HTTPS)
                                                 /watch/<id>   page with MSE player + unmute button
                                                 /stream/<id>.mp4   live fMP4
                                                 /hls/<id>/index.m3u8, init.mp4, seg<N>.m4s
```

Display uses a separate player per tile (`SourceView`), so recording and streaming never depend on what is on screen. The cost is a second RTSP connection to the camera.

## Components

| Part | Responsibility |
|---|---|
| `Hub` | Singleton. Owns `Outputs` per camera, recording start/stop, the servers, motion events, retention cleanup, background monitors, remote access. Exposes `StateFlow`s to the UI. |
| `Outputs` / `Mux` | One encoder session per camera. `Mux` fans samples out to the recorder and the live stream and replays format info to late recorders. A session starts on the first consumer and stops when none is left (viewers get a 5 s grace). |
| `EncodeSource` | `RtspEncodeSource` (ExoPlayer decodes, renders into the encoder surface, taps audio) and `UvcEncodeSource` (adds the encoder surface to the UVC renderer). |
| `VideoEncoder` | H.264 from a Surface. Quality = max height + bitrate preset (`EncodeParams`). Falls back to 1080p or VBR if the device refuses. |
| `LiveStream` | Builds the init segment once (video, plus audio if the source has it) and one fMP4 fragment per sample per viewer. Each viewer's timeline starts at its first key frame; a slow viewer is dropped back to the next key frame. |
| `HlsPackager` | Cuts the same samples into ~1 s segments that each start with a key frame; keeps the last 8; serves a live playlist. Created on the first HLS request and dropped after 20 s without requests. |
| `StreamServer` | Routes, secret key check (`?k=`), CORS for HLS. HTTPS is a second instance with a Keystore-backed self-signed certificate (`TlsSupport`). |
| `PlayerFactory` | RTSP ExoPlayer with tiny buffers (a little larger when feeding the encoder), reconnect after errors, ends and silent stalls (10 s watchdog). |
| `MotionDetector` | Pure: compares 64×36 luma thumbnails, three sensitivities, 10 s cool-down. |
| Foreground motion | `SourceView` reads a `TextureView` bitmap every 400 ms. |
| Background motion | `MotionMonitor` decodes each motion-enabled camera into `GlFrameSampler` (GPU → 64×36 pixels). Runs only when the app is in the background and the setting is on; `Hub.onAppBackground/Foreground` start and stop it. |
| `Retention` | Pure: which recordings to delete by age and total size, never the files being written. `Hub` also stops recording when free space is low. |
| `UvcManager` | Opens the capture card (UVCAndroid), picks 1080p / highest fps / MJPEG by default or the user's mode, fans frames out to any number of surfaces. |
| `Onvif`, `Dlna`, `Upnp`, `CastController` | Discovery and control protocols. Text building/parsing is in pure objects with unit tests. |
| `KeepAliveService` | Foreground service (special use + microphone when permitted) while recording, streaming or monitoring. |

## Timestamps and sync

- Video timestamps come from ExoPlayer's frame release times (`System.nanoTime` basis) as seen by the encoder surface; audio is mapped to the same clock through the frame currently on screen (`EncodeSource.onPcm`).
- Recorder: samples are relative to the first video sample; audio earlier than that is dropped. Video waits up to 2 s for the audio format, then records without sound.
- `LiveStream.Client`: separate `tfdt` timeline per viewer, 90 kHz video and sample-rate audio. `HlsPackager` uses one shared timeline from its first key frame.

## Data

- Cameras: DataStore (`cameras_json`), URL encrypted with `SecretBox`. Plain-text URLs written by tooling are accepted and encrypted on the next save.
- Settings: DataStore (`settings`). Motion events: SharedPreferences (`events`, newest 200). Stream key: SharedPreferences (`hub`).
- Recordings: app-specific external storage, `Movies/IPCamHub` (no storage permission needed).

## Network surface

HTTP 8080 and optional HTTPS 8443, every route behind the random key. Outgoing: RTSP to cameras, SSDP/mDNS discovery, ONVIF SOAP, DLNA SOAP, UPnP SOAP to the router (only with remote access on).
