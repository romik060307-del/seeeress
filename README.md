# RK Camera AI v2.1 — Android 8.0+

This repository contains the Android application source code (Java + CameraX); it is not a prebuilt APK. Build it with the included GitHub Actions workflow, then install the resulting APK.

## Baru di v2.1

- Mode POTRET (geser ke kanan / ketuk POTRET): ketuk objek di layar untuk memilih yang tetap tajam. Objek dipisah dengan GrabCut (OpenCV), latar dikaburkan berjenjang menurut jarak dari objek. Tanpa ketukan, objek dicari otomatis (wajah, lalu tengah gambar). Kekuatan blur diatur di Pengaturan.
- Mode Malam (ikon bulan di kiri atas, Otomatis / Aktif / Mati): 6 jepretan beruntun disejajarkan, dirata-rata untuk menekan noise, dicerahkan, dan dipertajam. Tahan HP diam sampai hitungan selesai. Flash dimatikan otomatis selama proses.
- Mode Bulan: saat zoom 2x ke atas dan ada lingkaran terang di langit gelap, muncul tanda "BULAN"; eksposur diturunkan dan diukur di bulan, lalu 8 jepretan digabung dan dipertajam kuat. Tidak menambah detail yang tidak ditangkap lensa.
- Galeri RK Camera (ketuk thumbnail): kisi foto dan video hasil kamera. Pengaturan > Galeri memilih Galeri RK atau galeri bawaan HP.
- Editor foto (ketuk foto di Galeri RK): Filter bergaya iPhone, Blur Latar (pilih objek), Hapus Objek (warnai dengan kuas, diisi ulang dengan inpainting), Urungkan, Simpan sebagai file baru.

Belum ada (tahap v2.2): Cinematic video dengan pilihan fokus, pelacak objek, Live Photo, editor video.

## Baru di v2.0

- Tampilan ala kamera iOS: bar atas (flash, timer, pengaturan), preview 4:3 untuk foto dan layar penuh untuk video, tombol zoom, pilihan VIDEO/FOTO (geser kiri/kanan di layar), rana putih, thumbnail galeri, tombol balik kamera.
- Menu Pengaturan (ikon slider di kanan atas): Stabilisasi Video (Mati / Standar / Maks), Kunci Horizon, Filter, Kualitas Video (HD/FHD), Grid. Semua tersimpan di HP.
- Stabilizer diperbaiki: arah koreksi vertikal dan rotasi dibenahi (Media3 memakai sumbu Y ke atas), cuplikan gerak lebih rapat, penghalusan Gaussian. Mode Maks: potongan layar lebih besar, jalur lebih halus, proses lebih lama.
- Kunci Horizon (kamera belakang): memakai sensor gravitasi, meluruskan cakrawala sampai 15 derajat saat HP miring.
- Zoom: cubit layar atau ketuk tombol (sampai 100x). Zoom di atas batas kamera dikerjakan software: preview diperbesar, foto di-crop lalu diperbesar dan dipertajam, video di-crop saat diproses. Kualitas turun drastis di atas sekitar 10x.
- Flash: Otomatis / Nyala / Mati (siklus di ikon kiri atas). Layar dikunci portrait; arah foto/video mengikuti cara HP dipegang.
- Bitrate hasil video dinaikkan (10 Mbps HD, 16 Mbps FHD).


## Baru di v1.4

- Warna bergaya iPhone (`IPhoneLook.java`): bayangan sedikit terangkat, highlight dilembutkan, kontras halus, sedikit hangat, vibrance yang melindungi warna kulit. Fungsi yang sama dipakai untuk foto (per piksel) dan video (3D LUT lewat Media3), jadi tampilan keduanya konsisten.
- 8 filter bergaya iPhone: STANDAR, CERAH, HANGAT, DINGIN, DRAMATIS, MONO, SILVERTONE, NOIR. Ketuk tulisan kuning di kiri atas untuk berganti filter. Filter diterapkan setelah foto diambil / video selesai direkam (preview live belum berfilter).
- Perbaikan: orientasi EXIF foto disalin ke hasil olahan supaya foto tidak miring.
- Ini pendekatan umum, bukan pipeline asli Apple; hasilnya perlu dicocokkan lewat uji di HP asli.

## What this version does

- Uses the real phone camera through CameraX for preview, photo capture, and MP4 recording.
- Opens photo mode separately from video mode so a video profile failure does not intentionally leave the entire camera screen black.
- In video mode, prefers HD, then FHD/SD, and includes CameraX's `HIGHEST` sentinel so other reported qualities can be selected if those preferences are unavailable.
- Keeps live preview free of extra software stabilization work.
- After recording stops, samples real video frames, tracks image motion with OpenCV, smooths the camera path, then renders per-frame translation/rotation correction with Media3 Transformer. An overscan crop reduces visible empty edges.
- Applies a restrained tone adjustment during the exported video and a small tone curve to captured photos. This is a generic natural-looking treatment, not Apple's proprietary image pipeline.
- Retains the original recording if motion analysis or export fails. Once a stabilized copy has been successfully published, the raw recording is removed so the gallery contains the processed result rather than two copies.
- Includes flash/torch where supported, digital 1x/2x zoom, tap-to-focus, a front/back camera switch, and a custom adaptive launcher icon.
- Packages ARM phone libraries (`armeabi-v7a` and `arm64-v8a`) rather than emulator-only x86 libraries.

## Stabilization limitations

The stabilizer is real post-processing, not a claim of perfect iPhone-equivalent results. It works best when the video has visible background detail and moderate movement. Strong motion blur, fast turns, rolling-shutter distortion, parallax, low light, or a scene with few trackable features can reduce quality. It re-encodes the video and can take a while on older phones. Some encoders may fail; in that case the original video is kept. Software cannot add optical stabilization hardware or reproduce Apple's ISP/Neural Engine.

The APK must be built and tested on real hardware before its camera preview, codec support, image quality, and stabilization result can be considered verified. This environment did not run an Android SDK/Gradle build.

## Build the APK using GitHub Actions

1. Create a repository at https://github.com/new.
2. Upload all files inside this folder, including the hidden `.github` directory. The `app/`, `build.gradle`, and `settings.gradle` files should be at the repository root in their shown structure—not inside an extra nested folder.
3. Open **Actions** → **Build RK Camera AI APK** → **Run workflow**, or push to `main`/`master`.
4. Wait for the workflow to finish. A green `Success` status is required; a ZIP source file alone is not an APK.
5. Download artifact **RK-Camera-AI-APK**, extract it, and install `app-debug.apk`.

## Updating an installed build

GitHub Actions debug builds may use different debug-signing keys between runs. Android can reject an update with a signature conflict. If this happens, uninstall the older RK Camera AI app first, then install the new APK. This removes app-local data but does not remove photos or videos saved in Gallery.

## Toolchain

- Android `minSdk 26` (Android 8 Oreo), `compileSdk 35`
- Java 17, Android Gradle Plugin 8.6.1, Gradle 8.7
- CameraX 1.4.2
- Media3 Transformer/Effect 1.5.1
- OpenCV 4.10.0
