# Testing

## 1. Unit tests (no device)

```bash
./gradlew testFullDebugUnitTest testFossDebugUnitTest lintFullDebug lintFossDebug
```

Pure logic only: fMP4/HLS packaging, Annex B conversion, ONVIF/DLNA/UPnP parsing, retention, motion detection, encode parameters. Add a test with every change to these.

## 2. Fake camera

`tools/dev/rtsp-test-server.js` (Node, no dependencies) serves an H.264 file over RTSP/TCP, any number of clients, optional G.711 tone.

```bash
# 20 s test clip with a burned-in clock and frame counter and an AUD before every frame
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30:duration=20 -c:v libx264 -profile:v baseline \
       -g 30 -bf 0 -pix_fmt yuv420p -bsf:v h264_metadata=aud=insert -f h264 test.h264
node tools/dev/rtsp-test-server.js test.h264 8556 30 audio     # file, port, fps, optional "audio"
```

- Emulator: the host is `10.0.2.2`, so the camera URL is `rtsp://10.0.2.2:8556/live`.
- Real phone over wireless debugging: `adb -s <device> reverse tcp:8556 tcp:8556`, then use `rtsp://127.0.0.1:8556/live`. No firewall changes needed.
- Check the port is free first. If something else holds it, pick another; do not kill it.

## 3. Putting cameras into a debug build

Typing into the UI with `adb input` is slow. `tools/dev/seed-cameras.js` writes the DataStore file; plain-text URLs are accepted.

```bash
node tools/dev/seed-cameras.js out.preferences_pb tools/dev/example-cameras.json
adb push out.preferences_pb /data/local/tmp/cameras.preferences_pb
adb shell am force-stop app.ipcamhub
adb shell "run-as app.ipcamhub sh -c 'mkdir -p files/datastore && cat /data/local/tmp/cameras.preferences_pb > files/datastore/cameras.preferences_pb'"
```

(`run-as` only works on debug builds.) Start the app, open the stream tab, switch the server on. The key is shown in the URLs and in the `hub` SharedPreferences.

## 4. Measuring

```bash
node tools/dev/stream-viewer.js "http://<phone>:8080/stream/<camera-id>.mp4?k=<key>" 14 out.mp4
ffprobe -v error -show_entries stream=codec_name,width,height,duration,nb_frames -of compact out.mp4
ffmpeg -i "http://<phone>:8080/hls/<camera-id>/index.m3u8?k=<key>" -t 12 -f null -      # HLS decodes cleanly?
```

- **Frame rate:** packet `pts_time` span from ffprobe.
- **Latency:** the test clip shows a frame counter; compare the counter of the first key frame you receive with the server's frame clock (the test server prints its frame-0 time).
- **Picture quality:** extract a frame (`ffmpeg -ss 5 -i out.mp4 -frames:v 1 frame.png`) and look at it. The emulator can corrupt the picture when its hardware decoder feeds the encoder; check on a real phone.
- **Heat/battery:** `adb shell dumpsys battery` (temperature is in tenths of a degree).

## 5. Real device

Wake the screen before every tap sequence. If the phone answers `Sending oneway calls to frozen process`, the app is in the background without a foreground service; check `KeepAliveService`.

## Do not

- Cast or control TVs/Chromecasts you were not told to use.
- Turn on remote access (UPnP) on a real router without explicit approval.
- Leave test servers, `adb reverse` rules or port forwards running when you finish.
