"""Measures the sound/picture offset in a recording of the flash+beep test (see docs/TESTING.md, "Lip-sync").
Usage: python tools/dev/lipsync.py <recording.mp4>   (needs ffmpeg and ffprobe on PATH)
Video: frames whose average brightness jumps (flash onsets). Audio: 5 ms windows whose level jumps (beep onsets).
Prints the offset of each beep from the nearest flash (positive = sound late)."""
import re, subprocess, sys

f = sys.argv[1]
v = subprocess.run(['ffprobe', '-v', 'error', '-f', 'lavfi', f'movie={f.replace(chr(92), "/").replace(":", chr(92) + ":")},signalstats',
                    '-show_entries', 'frame=pts_time:frame_tags=lavfi.signalstats.YAVG', '-of', 'csv=p=0'],
                   capture_output=True, text=True).stdout
frames = []
for line in v.splitlines():
    p = line.split(',')
    if len(p) >= 2 and p[0] and p[1]:
        frames.append((float(p[0]), float(p[1])))
flashes = [t for (t, y), (_, yp) in zip(frames[1:], frames) if y > 120 and yp <= 120]

a = subprocess.run(['ffmpeg', '-v', 'error', '-i', f, '-map', '0:a', '-af',
                    'asetnsamples=n=240,astats=metadata=1:reset=1,ametadata=print:key=lavfi.astats.Overall.RMS_level:file=-',
                    '-f', 'null', '-'], capture_output=True, text=True).stdout
times, levels, t = [], [], None
for line in a.splitlines():
    m = re.search(r'pts_time:([\d.]+)', line)
    if m: t = float(m.group(1))
    m = re.search(r'RMS_level=(-?[\d.inf]+)', line)
    if m and t is not None:
        lv = float(m.group(1)) if 'inf' not in m.group(1) else -200.0
        times.append(t); levels.append(lv)
beeps = [times[i] for i in range(1, len(levels)) if levels[i] > -30 and levels[i - 1] <= -30]

print(f'{len(flashes)} flashes, {len(beeps)} beeps')
offs = []
for b in beeps:
    if not flashes: break
    near = min(flashes, key=lambda x: abs(x - b))
    if abs(near - b) < 0.5:
        offs.append((b - near) * 1000)
if offs:
    offs.sort()
    print('offsets ms: min %.0f  median %.0f  max %.0f  (n=%d)' % (offs[0], offs[len(offs) // 2], offs[-1], len(offs)))
