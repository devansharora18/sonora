# TODO / verification backlog

## On-device checks (need a phone or emulator)

The taste engine's logic is covered by JVM tests, but the pieces that touch Android cannot be.

### Monkey run

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p dev.sonora --throttle 200 -v 20000 \
  > /tmp/sonora-monkey.txt 2>&1
grep -i "CRASH\|ANR" /tmp/sonora-monkey.txt
```

Expected: no `CRASH`/`ANR`. The Autoplay path exercises the radio wrapper, so run it once with
network available and once in airplane mode (cold start must fall back without crashing).

### Autoplay end-to-end

1. Play a track from Search, skip through the queue until two items remain.
2. Confirm the queue grows by ~5 items, each labelled `AUTOPLAY`.
3. Confirm a downloaded lossless copy is preferred when one exists for a pick.
4. Confirm what you queued by hand is never reordered or removed.

### Radio

1. Home → **Start a radio** → add two artists → confirm the picker lists your top artists first
   with their real pictures (needs network; the suggestion lookup is one request per artist).
2. Drag the variety slider across all five stops and press **Play mix** at each; the queue should
   be 100:0, 80:20, 60:40, 50:50 and 30:70 picked artists to everyone else. The far right still
   keeps 30% for the picks.
3. Press **Save**, then check Home's *Your radios* shelf and Library → *Radios*. Play from the
   Library's disc; the queue header should read `Radio · <artists>`.
4. Tap the saved radio's row: its artists and variety should come back into the Radio screen.
5. With **Familiar only** on and a small history, the mix may be shorter than forty tracks — that is
   the filter working, not a failure.
6. Airplane mode: pressing **Play mix** should show "Could not build that mix" and leave the queue
   untouched.
7. Search the artist picker for a name that returns several artists ("passenger" returns six):
   the list must render, and only the one actually added may read **Added**.
8. Start an 80:20 radio from artists you have been playing all week: the queue should be full (not
   two tracks) and mostly them. The queue header prints the ratio it was built at, so a queue can
   be checked against the slider at a glance.
9. Play a 100:0 radio of one artist and skip past the whole queue: the refill must still be that
   artist only, and the queue header must still say `Radio · <artist>`. Never another artist's
   name unless the slider allows it.

### Search as you type

1. Type into Search without pressing enter: results, artists and albums appear after a pause.
2. Type a word and delete it before the pause: nothing is asked, and the previous page stays.
3. Check Settings → Taste does not grow from typing, and the recent-search list on Search shows
   only queries that were submitted.
4. On the Soulseek tab, typing should query peers after a longer pause rather than never.

### Persistence across process death

1. Play a few tracks, then swipe the app away.
2. Relaunch and open Settings → Taste; the counts should include those plays.
3. `adb shell run-as dev.sonora ls files/` should show `taste.json` and `taste.json.bak`.

## Host-side (no device needed)

### YTM radio live check

`YtmRadio` is parsed and tested against `app/src/test/resources/ytm/radio-next.json`, and the live
endpoint was verified on an emulator. The request must name the generated mix
(`playlistId=RDAMVM<videoId>`) or the endpoint answers with only the current track:

```bash
curl -s 'https://music.youtube.com/youtubei/v1/next?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30' \
  -H 'Content-Type: application/json' \
  --data '{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20260707.12.00","hl":"en","gl":"US"}},"videoId":"dQw4w9WgXcQ","playlistId":"RDAMVMdQw4w9WgXcQ","isAudioOnly":true,"params":"wAEB"}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("radio rows:", json.dumps(d).count("playlistPanelVideoRenderer"))'
```

Expected: about 50 rows. If the shape has moved, update `YtmRadio.parse` (it recursively scans for
`playlistPanelVideoRenderer`, so only the row's own field names would need changing) and re-save
the fixture from the real response.
