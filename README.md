# VidSaver – Video & MP3 Downloader

App ya Android (lugha ya Kiingereza) inayodownload **video, MP3 na faili lolote**, yenye player yake na Junk Cleaner ya kweli.
(Zamani iliitwa SwiftLoad. Jina la package bado ni `com.swiftload.app` ili matoleo mapya yasakinike juu ya ya zamani.)

## Inafanya nini
- **Share → VidSaver**: kwenye YouTube, TikTok, Instagram, Facebook, X, Chrome, WhatsApp n.k. bonyeza *Share* kisha uchague **Download with VidSaver**.
- **Vichupo 3**: **Download** · **Play** · **Settings** (dark theme kwa chaguo-msingi, rangi ya chungwa #F2A541).
- **Download**: bandika link au andika maneno kutafuta YouTube, kisha bonyeza **MP4** au **MP3**.
  Search · YouTube · Music · More (TikTok, Facebook, Instagram, X, Threads, Pinterest, Vimeo, Dailymotion, SoundCloud, Google)
  hufunguka ndani ya app na kitufe cha chungwa cha **Download**.
- **Inatambua faili yenyewe**: link ya faili (mp4, mp3, pdf, zip, apk, picha…) inaanza kudownload papo hapo;
  link ya video (YouTube na zaidi ya site 1000) inakuuliza **Video (MP4)** au **Audio (MP3)**.
- **Kasi kama IDM**: faili linagawanywa vipande 2–6, na mtandao ukikatika inaendelea ilipoishia.
- **Play**: Videos · Music · Files, pamoja na player ya video (skrini nzima, kasi) na muziki unaoendelea nyuma ya pazia.
- **Junk Cleaner** (Settings → Phone clean): inaonyesha ukubwa halisi, unachagua mwenyewe, hakuna kinachofutwa bila uthibitisho.
- Mafaili yanahifadhiwa kwenye **Download/VidSaver/** ndani ya folda: Video, Music, Images, Documents, Archives, Apps, Other.

## Jinsi ya kupata APK
1. Nenda tab ya **Actions** ya repository hii — kila push inaanzisha build (dakika 2–5).
2. Ikimaliza, fungua build hiyo, shusha chini kwenye **Artifacts** → download **VidSaver-APK**.
3. Ndani kuna APK 3. Simu nyingi za kisasa zinahitaji **`app-arm64-v8a-release.apk`**
   (simu za zamani sana: `armeabi-v7a`). Isakinishe (ruhusu "Install unknown apps").
   APK zote zimesainiwa kwa ufunguo mmoja (`app/swiftload.jks`), kwa hiyo matoleo mapya yanasakinika kama update.

## Au kwa Android Studio
Fungua folda hii kwenye Android Studio → subiri Gradle sync → **Build > Build APK(s)**.

## Muundo wa code
| Faili | Kazi |
|---|---|
| `MainActivity.kt` | Vichupo 3 (Download / Play / Settings) |
| `DownloadFragment.kt`, `YouTubeSearch.kt` | Kubandika link, kutafuta YouTube, More sites |
| `BrowserActivity.kt` | Browser ndani ya app yenye kitufe cha Download |
| `ShareActivity.kt` | Pop-up ya Share: inatambua link na kuanza download |
| `Detector.kt` | Inatambua aina ya faili, jina na picha ya video |
| `Downloaders.kt` | Downloader ya vipande vingi + yt-dlp kwa video/MP3 |
| `DownloadService.kt` | Inadownload kwa background, notifications |
| `LibraryFragment.kt`, `PlayerActivity.kt`, `PlaybackService.kt` | Maktaba na player (Media3) |
| `SettingsFragment.kt` | Settings |
| `JunkCleaner.kt`, `CleanerActivity.kt` | Junk Cleaner na Storage overview |
| `Storage.kt`, `FolderMove.kt` | Kuhifadhi kwenye Download/VidSaver (na kuhamisha kutoka Download/SwiftLoad ukikubali) |

## Kumbuka
- Engine ya video ni **yt-dlp** (Unlicense) kupitia maktaba ya youtubedl-android (GPL-3.0). Ukiuza au kuchapisha app hii, code yako inapaswa kufuata leseni ya GPL-3.0.
- Google Play hairuhusu app zinazodownload video za YouTube, kwa hiyo isambaze kama APK au kupitia store nyingine.
- Download tu maudhui unayoruhusiwa kuyapakua.
