# SwiftLoad — Android downloader (kama IDM)

App ya Android (lugha ya Kiingereza) inayodownload **video, MP3 na faili lolote**.

## Inafanya nini
- **Share → SwiftLoad**: kwenye YouTube, TikTok, Instagram, Facebook, X, Chrome, WhatsApp n.k. bonyeza *Share* kisha uchague **SwiftLoad**.
- **Inatambua faili yenyewe**:
  - Link ya faili la moja kwa moja (mp4, mp3, pdf, zip, apk, picha…) → inaanza kudownload papo hapo.
  - Link ya video (YouTube na zaidi ya site 1000) → inakuuliza **Video (MP4)** au **Audio (MP3)**.
    Kwenye Settings unaweza kuchagua "Always video" au "Always MP3" ili ianze moja kwa moja bila kuuliza.
- **Kasi kama IDM**: faili linagawanywa vipande 2–6 vinavyodownload kwa wakati mmoja, na kama mtandao ukikatika inaendelea ilipoishia.
- **Ukinakili (copy) link** kisha ukafungua app, inakuuliza kama unataka kuidownload.
- Inadownload hata app ikiwa imefungwa, na inaonyesha progress kwenye notification (pamoja na kitufe cha Cancel).
- Mafaili yanahifadhiwa kwenye **Download/SwiftLoad/** ndani ya folda: Video, Music, Images, Documents, Archives, Apps, Other.
- Settings: ubora wa video (Best/1080p/720p/480p/360p), *Update download engine* (tumia hii YouTube ikianza kukataa).

## Jinsi ya kupata APK (bila kuinstall chochote kwenye kompyuta)
1. Fungua akaunti ya bure kwenye https://github.com na tengeneza repository mpya (mfano `SwiftLoad`).
2. Pakia (upload) mafaili yote ya folda hii, ikiwemo folda iliyofichwa `.github`.
3. Nenda tab ya **Actions** — build itaanza yenyewe (dakika 5–10).
4. Ikimaliza, fungua build hiyo, shusha chini kwenye **Artifacts** → download **SwiftLoad-APK**.
5. Ndani kuna APK 3. Simu nyingi za kisasa zinahitaji **`app-arm64-v8a-release.apk`**
   (simu za zamani sana: `armeabi-v7a`). Ituma kwenye simu na uinstall (ruhusu "Install unknown apps").

## Au kwa Android Studio
Fungua folda hii kwenye Android Studio → subiri Gradle sync → **Build > Build APK(s)**.

## Muundo wa code
| Faili | Kazi |
|---|---|
| `ShareActivity.kt` | Pop-up ya Share: inatambua link na kuanza download |
| `Detector.kt` | Inatambua aina ya faili (video page, mp3, pdf, zip, apk…) |
| `Downloaders.kt` | Downloader ya vipande vingi (kama IDM) + yt-dlp kwa video/MP3 |
| `DownloadService.kt` | Inadownload kwa background, notifications |
| `Storage.kt` | Inahifadhi kwenye Download/SwiftLoad |
| `MainActivity.kt` | Skrini kuu: kubandika link, orodha ya downloads, settings |

## Kumbuka
- Engine ya video ni **yt-dlp** (kupitia maktaba ya youtubedl-android, GPL-3.0). Ukiuza au kuchapisha app hii, code yako inapaswa kufuata leseni ya GPL-3.0.
- Google Play hairuhusu app zinazodownload video za YouTube, kwa hiyo isambaze kama APK au kupitia store nyingine.
- Download tu maudhui unayoruhusiwa kuyapakua.
