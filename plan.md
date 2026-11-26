# DiscordConverterFix: Plan

Status: Phase 2 MVP code is built (debug APK). Not yet tested on the phone.
Last update: 2026-11-27.

## 1. The problem

1. You record Mobile Legends clips at 120 fps.
2. You send a clip to a Discord chat.
3. The clip in Discord looks like 15 to 30 fps. It is choppy.
4. When you first convert the clip to 60 fps, Discord shows it smooth.

The goal: send smooth clips to Discord with no manual work each time.

## 1.1 Decisions (answers from the owner, 2026-11-27)

| Question | Answer | Effect on the plan |
|---|---|---|
| Record on | Samsung Galaxy S25+ (Android), Samsung built-in screen recorder | Build the **Android app**. The Windows app is not needed. |
| Send from | Discord Android app | The mobile compressor is the problem to avoid. |
| Nitro | Yes, usually | Size limit is large (up to 1 GB). Discord mobile "Best Quality" is 1080p. Size is not the main problem. |
| Clip length | 20 to 30 seconds | Conversion must take a few seconds only. |
| Keep 120 fps originals | **Yes** | Option 0 (record at 60 fps) is rejected. |
| Users | Only the owner | No Play Store. Sideload the APK. Less polish needed. |
| Wish | Send in Discord and get the 60 fps version by itself | **Not possible** without root (see 2.3). Next best: fewest taps. |
| Storage | **No extra copies in the gallery. No downloads.** | The 60 fps file lives only in a temporary app cache. It is deleted after use. |
| Flow | New clips show in the app. One button converts fast and puts the clip on the clipboard. Paste in Discord. | New design in section 6.1. |

## 2. What the research found

### 2.1 Facts (from Discord's own help pages)

| Fact | Source |
|---|---|
| The Discord **mobile app always compresses video uploads**. You choose the quality, but you cannot turn compression off. | Video Upload Quality Settings on Mobile FAQ |
| Mobile quality levels (no Nitro): Best = 720p, Standard (default) = 480p, Data Saver = 360p. With Nitro: 1080p / 720p / 360p. | Same FAQ |
| Data Saving Mode forces 360p on mobile data, even when you select Best. | Same FAQ |
| The FAQ says nothing about frame rate. Discord does not document what it does with 120 fps. | Same FAQ |
| Free upload limit is **20 MB** (since August 2026). Nitro Basic = 50 MB. Nitro = up to 1 GB. | File Attachments FAQ |
| Discord now checks the size of the **original** file, **before** compression. Android users hit the limit more often because phone files are large. | File Attachments FAQ |
| Discord plays MP4 video in H.264, HEVC (H.265) or AV1. | File Attachments FAQ |
| A user report from 2020 says Discord plays 120 fps clips with stutter until each keyframe, and at 60 fps maximum. Discord never replied. | Discord feedback forum, "Playback of 120fps videos" |

### 2.2 Why 120 fps clips go bad (best explanation, not proven yet)

Discord does not publish how its mobile compressor works. These are the likely causes:

1. **The compressor is tuned for 30 or 60 fps.** A 120 fps clip has 2 times the frames of a 60 fps clip. The compressor must drop frames or share the same bitrate across more frames. Both make the result look worse.
2. **Phone screen recordings use a variable frame rate (VFR).** The time between frames changes all the time (for example 8 ms, then 11 ms, then 9 ms). A simple "drop every second frame" rule then gives uneven gaps. Uneven gaps feel like 15 to 30 fps, even when the average is higher.
3. **Hardware encoder limits.** Some phone encoders cannot encode 120 fps at high resolution. They then skip frames.
4. **Playback limits.** The Discord player has a history of bad 120 fps playback (see 2.1). Most viewers also have 60 Hz screens, so 120 fps gives them no benefit.

Your own test supports this: a 60 fps copy goes through the same compressor and stays smooth. The compressor works well when it gets a 60 fps, constant-frame-rate file.

**Phase 0 (section 7) proves or rejects these causes before we build anything.**

### 2.3 Can we fix Discord's conversion itself?

**No, not in a safe way.**

- The compressor runs inside the closed Discord app and on Discord's servers. We cannot change it.
- Client mods (for example Vencord or BetterDiscord) break the Discord Terms of Service. They put your account at risk. They also do not work in the normal mobile app.
- Android does not let one app change a file that another app sends. This is a security rule. Only a rooted phone can get around it.

So the safe fix is: **give Discord a file that it handles well.** That is a 60 fps, constant-frame-rate H.264 MP4 that fits the size limit. This is what your manual 60 fps conversion already does. The project makes that step automatic.

## 3. Honest check: do we need to build anything?

Before we build, try the free options:

| Option | How | Good | Bad |
|---|---|---|---|
| **0. Record at 60 fps** | Set the screen recorder to 60 fps. The game still runs at 120 fps. Only the recording changes. | No app, no extra step, smaller files. | You lose 120 fps originals (for TikTok, YouTube, or edits). |
| **1. Manual convert** (what you do now) | Export at 60 fps in an editor (for example CapCut). | Works today. | Manual work for each clip. |
| **2. This project** | An app converts every new 120 fps clip to 60 fps by itself. | No editing. On/off switch. | We must build and maintain it. |

**Recommendation:** If Discord is the only place your clips go, try option 0 first. It can solve the problem with zero code. Build this project if you want to keep 120 fps originals, or if option 0 does not fix it.

## 4. The key question: where do you send clips from?

The answer decides what we build. This PC has an Android emulator installed (MuMu Player), so Mobile Legends on PC is possible too.

| You record on | You send from | What goes wrong | What we build |
|---|---|---|---|
| Android phone | Discord Android app | Mobile compressor (720p/480p) handles 120 fps badly. Also the 20 MB check on the original file. | **Android app** (section 6.1) |
| iPhone | Discord iOS app | Same mobile compressor. | iOS is harder (see 5, option E). |
| Phone | Discord on PC | Desktop does not compress, but the 120 fps playback stutters, and big files hit 20 MB. | **Windows tray app** (section 6.2) |
| Emulator or OBS on PC | Discord on PC | Same as above. | **Windows tray app** (section 6.2) |

## 5. All options compared

| # | Option | Fixes mobile sends? | Automatic? | Effort | Risk | Verdict |
|---|---|---|---|---|---|---|
| A | **Android app**: share target + auto-convert switch | Yes | Yes (auto mode) | Medium | Low | **Build if you send from Android** |
| B | **Windows tray app**: watch folder + auto-convert + copy to clipboard | Only for sends from PC | Yes | Low | Low | **Build if you send from PC** |
| C | Discord bot that re-encodes clips | **No.** The bot gets the clip after the mobile app already damaged it. | Yes | Medium | Medium (hosting, bot repost is not your message) | Reject |
| D | Discord client mod (desktop plugin) | No (desktop only) | Yes | Medium | **High** (breaks Terms of Service, breaks on Discord updates) | Reject |
| E | iOS app or Shortcut | Yes | Partly | High | Medium | Later. An iOS app needs a paid Apple developer account (99 USD per year) to stay installed. A free install expires after 7 days. |
| F | Termux + ffmpeg + Tasker on Android | Yes | Yes | Low code, but fiddly | Medium | Prototype only. Software encode on the phone CPU is slow and hot. |
| G | Send as a generic file (zip or renamed) to skip compression | Yes | No | None | Low | Reject. Friends must download the file. No inline player. |

## 6. Recommended design

Build only the platform you need first. Both designs share the same output rules.

### 6.0 Shared output rules ("Discord-safe profile")

| Setting | Value | Why |
|---|---|---|
| Frame rate | **60 fps, constant** (CFR) | Smooth, and the compressor handles it well. |
| Codec | H.264, High profile, yuv420p | Plays everywhere. HEVC fails on some PCs and browsers. |
| Resolution | Keep, or scale down to 1080p or 720p (setting) | Discord mobile scales to 720p (no Nitro) anyway. A smaller file passes the 20 MB check. |
| Size target | 20 MB (free), 50 MB (Nitro Basic), 1 GB (Nitro) (setting) | Discord checks the original file size. The tool sets the bitrate from size and length. |
| Audio | AAC, copy if possible, else 128 kbit/s | Keep game sound. |
| Container | MP4 with "faststart" | Discord can start playback before the full download. |
| Skip rule | Skip a clip if its frame rate is 61 fps or less | Do not touch clips that are already fine. |
| Originals | **Never delete or change the original** | Safety. You keep your 120 fps clip. |

### 6.1 Android app (working name: "ClipFix") — CHOSEN

Use a neutral name. Do not use the Discord logo or name as the app name.

**Main flow (from the owner's wish):**

1. You record a clip at 120 fps. The original stays in the gallery, unchanged.
2. ClipFix shows your newest screen recordings as a list. A notification can also say "New clip".
3. You tap **Copy for Discord** (or **Send to Discord**) on a clip.
4. ClipFix converts the clip to 60 fps with the phone's hardware encoder. Target: a few seconds for a 30 second clip.
5. ClipFix puts the 60 fps clip on the clipboard, or opens Discord's share screen.
6. In Discord, paste the clip (or tap the chat in the share screen). Send.
7. ClipFix deletes the 60 fps file from its cache after a short time (for example 1 hour), or when you copy the next clip.

**Storage rule:** The 60 fps file never goes into the gallery. It lives in the app's private cache. Only one file exists at a time (about 20 to 60 MB). Android needs a real file for Discord to read, so a short-lived cache file is the minimum.

**Catch 1: we cannot replace the file inside Discord.** The wish "send in Discord and get 60 fps by itself" needs Discord to ask our app, and it does not. Android blocks apps from changing other apps' files. Only a rooted phone can do it. The flow above is the next best thing: 1 tap in ClipFix, 1 paste in Discord.

**Catch 2: clipboard paste of a video into Discord is not proven.** On Android, the clipboard holds a link to the file, not the file. Discord must accept a pasted video from the keyboard (Samsung Keyboard or Gboard). Images work. Videos are unknown. Phase 0 tests this.
- If paste works: use **Copy for Discord**.
- If paste fails: use **Send to Discord**. It opens Android's share screen with Discord's recent chats on top. It is the same number of taps and also needs no gallery copy.

**Speed plan:**
- Media3 Transformer with the hardware H.264 encoder. The S25+ has a fast encoder. Estimate: 3 to 10 seconds for a 30 second 1080p clip. This is not measured yet. Phase 2 measures it.
- Optional "Prepare in advance" switch: when a new clip appears, ClipFix converts it in the background into the cache. Then Copy is instant. Only the newest clip is kept, so storage stays small.

**Discord setting to check:** In Discord, set Text & Media > Video Uploads to **Best Quality** and turn off **Data Saving Mode**. Else Discord can scale our 60 fps clip down to 720p or 360p.

```mermaid
flowchart LR
    R[You record a 120 fps clip] --> L[ClipFix lists the new clip]
    L --> T[You tap Copy for Discord]
    T --> C[Hardware convert to 60 fps into app cache]
    C --> P{Paste works in Discord?}
    P -- yes --> V[Paste in Discord chat and send]
    P -- no --> S[Share screen opens, tap the Discord chat]
    V --> D[Cache file deleted later]
    S --> D
```

**Later idea (not for version 1):** ClipFix as a file provider. In Discord, "Upload a File" opens Android's file picker. ClipFix could show a "ClipFix 60 fps" source there and convert on open. Discord's own gallery tab does not use this picker, so it helps only part of the time. It is also more complex.

**Technology:**

| Part | Choice | Why |
|---|---|---|
| Language and UI | Kotlin, Jetpack Compose | Standard for new Android apps. |
| Video engine | **Android Media3 Transformer** | Uses the phone's hardware encoder. Fast and low battery use. `EditedMediaItem.Builder.setFrameRate()` (Media3 1.10+) or `FrameDropEffect` lowers 120 fps to 60 fps. |
| New-clip detection | MediaStore content observer + WorkManager | Starts work only when a new video appears. No battery drain while idle. |
| Long conversions | Foreground service, type `mediaProcessing` (Android 15+) | Android stops background work that runs too long. |
| Install | Sideload the APK from GitHub Releases | No Play Store account needed. |

**Settings screen:** recordings folder (default: Samsung's screen recordings folder), resolution (Keep / 1080p / 720p), size target (default: Nitro), "Prepare in advance" (on/off), "New clip" notification (on/off), cache clean-up time.

**Android catches:**

- Android 13+ needs the "videos" permission. The clip list needs full access, not "selected videos only".
- Samsung's battery saver can stop background apps. "Prepare in advance" may need ClipFix set to "Unrestricted" in the battery settings. The tap-to-copy flow works without this.
- Samsung screen recordings are usually variable frame rate. The converter must make a constant 60 fps.

### 6.2 Windows tray app (working name: "ClipFix for PC")

**Flow:**

1. ClipFix sits in the system tray. The tray menu has "Auto mode: on/off".
2. ClipFix watches one or more folders (for example the emulator's recordings folder or the OBS folder).
3. A new clip above 61 fps appears. ClipFix makes a 60 fps copy in a "Discord-ready" folder.
4. ClipFix puts the copy on the clipboard and shows a notification.
5. In Discord, press Ctrl+V to attach the clip. Press Enter to send.

Other ways in: drag a clip onto the tray icon, or right-click a file and select "Send to > ClipFix".

**Technology:**

| Part | Choice | Why |
|---|---|---|
| Video engine | **ffmpeg** (bundled) | Free, fast, handles all inputs including VFR. |
| Encoder | Hardware first (Intel QSV, NVIDIA NVENC, AMD AMF), `libx264` as fallback | Speed. |
| App | Python with a tray library and a folder watcher, packed into one .exe | Simple to read and learn from. Small. |

**Measured on a mid-range Windows laptop:** a 20 second 1080p 120 fps test clip converts to 60 fps in about 10 seconds (about 2 times faster than real time). The output has exactly 60 fps and half the frames (2400 to 1200). This is a synthetic test clip, not a real game clip.

The core conversion is one ffmpeg command:

```text
ffmpeg -i in.mp4 -vf fps=60 -c:v libx264 -preset veryfast -crf 23 -c:a copy -movflags +faststart out_60fps.mp4
```

## 7. Phases

### Phase 0: Prove the cause (no code, about 30 minutes)

1. Pick one 120 fps clip of 10 to 20 seconds.
2. Make a 60 fps copy with your current method.
3. Send both clips to a private test chat, from your normal device.
4. Download both clips back from Discord.
5. Run this command on all 4 files and compare the results:

   ```text
   ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height,r_frame_rate,avg_frame_rate,nb_frames,bit_rate -of default=nw=1 FILE.mp4
   ```

6. In Discord, check that Video Uploads is "Best Quality" and Data Saving Mode is off. Send the 120 fps clip again. Check if it is still choppy.
7. **Clipboard test:** In the Samsung Gallery, try to copy a video (if your phone offers it). Then long-press the Discord message box and select Paste, or use the keyboard's clipboard. Write down if Discord attaches the video.
8. Write the results in `docs/findings.md`.

**Result so far (2026-11-27):** The owner's Discord was on Video Uploads = **Standard** (720p with Nitro) and Data Saving Mode = **off**. Clips were still choppy. Still to test: **Best Quality** (1080p with Nitro). Do not use "Data Saver": it lowers the clip to 360p, so it cannot help. The owner approved the start of the app build in parallel.

**Build environment (owner's Windows PC):** Android Studio installed, Android SDK in `%LOCALAPPDATA%\Android\Sdk` (platform-tools, build-tools, platforms, NDK), JDK 17 (`JAVA_HOME` set), `adb` in the SDK `platform-tools` folder (not on `PATH`). `ANDROID_HOME` is not set. No global Gradle: use the Gradle wrapper.

**Decision gate:** If step 6 already fixes the problem, stop here. If not, continue. Step 7 decides "Copy for Discord" or "Send to Discord" as the main button.

### Phase 1: Output rules and test clips

1. Make a set of test clips: 120 fps CFR, 120 fps VFR, 90 fps, 60 fps (must skip), portrait, 1 minute long, HEVC input, clip with no audio.
2. Lock the output rules from section 6.0.
3. Confirm each converted test clip plays smooth on Discord desktop and Discord mobile.

### Phase 2: Minimum app

- A list of the newest screen recordings, and one button per clip.
- Convert to 60 fps into the cache, then clipboard or share screen (from the Phase 0 result).
- Measure the conversion time for a 30 second clip on the S25+.
- Done when: one tap plus one paste sends a smooth 60 fps clip, no file appears in the gallery, and a 30 second clip converts in 10 seconds or less.

**Status (2026-11-27): code done, builds, not tested on the phone yet.**

What exists in `android/`:

| Part | File | What it does |
|---|---|---|
| Clip list | `Clip.kt` | Lists the 30 newest videos from MediaStore, newest first. Filter: "Screen recordings" folder (default) or "All videos". Reads the real average frame rate (frame count / length). |
| Converter | `Converter.kt` | Media3 Transformer 1.11.1. H.264, `EditedMediaItem.Builder.setFrameRate(60)`, short side scaled down to 1080 only if larger, audio kept, MP4. Hardware encoder. |
| Cache | `CacheFiles.kt` | Output only in `cacheDir/converted/`. One file at most. Files older than 1 hour are deleted on app start. |
| Screen | `MainActivity.kt`, `MainViewModel.kt` | Thumbnail, length, resolution, fps, size. Buttons **Copy for Discord** and **Send to Discord**. Progress bar. Shows the conversion time per clip. |

Behavior:

- Clips at 61 fps or less are not converted. The app shares the original.
- If the same clip is already converted, the app uses the cached file at once.
- **Send to Discord** opens Discord's own share screen directly. If Discord is not installed, it opens the Android share sheet.
- The conversion runs while the app is open. There is no foreground service yet (Phase 3).

Build: `cd android` then `gradlew assembleDebug`. Output: `android/app/build/outputs/apk/debug/app-debug.apk` (about 40 MB, debug build). Needs Android SDK platform 37.2 (Compose 1.13 needs it) and `local.properties` with `sdk.dir` (gitignored).

Still to do for Phase 2: install on the S25+, measure a 30 second clip, test paste in Discord, check that the output is smooth in Discord.

### Phase 3: Less waiting

- "New clip" notification with the button inside it, so you do not need to open the app.
- "Prepare in advance" switch, so the copy is instant.
- Cache clean-up.
- Done when: the switches stay set after a restart, and the cache never holds more than the newest clip.

### Phase 4: Polish and release

- Settings screen, size targets, auto-delete of copies.
- Build the APK or .exe in GitHub Actions. Attach it to GitHub Releases.
- Write a README with install steps and screenshots.

### Phase 5 (optional): Second platform

Build the other app (Android or Windows) only if you need it.

## 8. Risks and catches

| Risk | Effect | What we do |
|---|---|---|
| We cannot hook into Discord's upload. | You still pick the 60 fps copy by hand. | Make the copy the newest gallery item and add a Share button in the notification. |
| Discord changes its compressor. | The fix can stop working, or stop being needed. | Run Phase 0 again after big Discord updates. |
| 20 MB limit on the original file. | Long clips fail before compression. | Size target setting. The tool lowers the bitrate or resolution to fit. |
| Phone battery and heat. | Bad experience. | Hardware encoder only. Work only when a new clip appears. |
| Phone brands kill background apps. | Auto mode stops. | Battery setting guide in the README. Share mode always works. |
| Public repo. | Private clips could leak. | `.gitignore` blocks video files. Never commit clips or screenshots. |
| iPhone users. | Not covered by the first version. | Option E, later. |

## 9. Open questions

All first-round questions have answers (section 1.1). Still open, answered by Phase 0:

1. Does Discord Android accept a pasted video?
2. Does "Best Quality" plus "Data Saving Mode off" fix the problem alone?
3. What frame rate and resolution does the Samsung recorder really write? (`ffprobe` shows it.)

## 10. Definition of done

1. A 120 fps clip sent through ClipFix plays smooth (60 fps) for viewers on Discord desktop and mobile.
2. You do no editing: 1 tap in ClipFix, 1 paste (or 1 tap) in Discord.
3. A 30 second clip is ready in 10 seconds or less.
4. No extra file in the gallery. The cache holds one clip at most and cleans itself.
5. The original 120 fps clip stays unchanged.

## 11. Planned repo layout

```text
plan.md          this plan
docs/findings.md Phase 0 results
android/         Android app (if chosen)
windows/         Windows tray app (if chosen)
testclips/       ignored by git, local test clips only
```

## Sources

- [Discord: Video Upload Quality Settings on Mobile FAQ](https://support.discord.com/hc/en-us/articles/9665451164951-Video-Upload-Quality-Settings-on-Mobile-FAQ)
- [Discord: File Attachments FAQ](https://support.discord.com/hc/en-us/articles/25444343291031-File-Attachments-FAQ)
- [Discord feedback forum: Playback of 120fps videos](https://support.discord.com/hc/en-us/community/posts/360051360753-Playback-of-120fps-videos)
- [Discord feedback forum: Forced terrible compression on mobile video uploads](https://support.discord.com/hc/en-us/community/posts/22949265359895-Forced-terrible-compression-on-mobile-video-uploads)
- [Dexerto: Discord raises free upload limit to 20MB](https://www.dexerto.com/entertainment/discord-raises-free-upload-limit-to-20mb-after-lowering-it-two-years-ago-3398251/)
- [Android Developers: Media3 FrameDropEffect](https://developer.android.com/reference/androidx/media3/effect/FrameDropEffect)
- [Android Developers: Media3 Transformer transformations](https://developer.android.com/media/media3/transformer/transformations)
- [Android Developers blog: Media3 1.10 is out](https://developer.android.com/blog/posts/media3-1-10-is-out)
- [Samsung Community: screen recorder capped at 60 fps in Mobile Legends](https://r1.community.samsung.com/t5/gaming-hub/screen-recorder/td-p/21022645)
- [OBS forum: high FPS recordings guide](https://obsproject.com/forum/resources/how-to-troubleshoot-high-fps-recordings-120-144-240-300-fps-recording.1433/updates)
