# GPS Setter

[![Stars](https://img.shields.io/github/stars/jqssun/android-gps-setter)](https://github.com/jqssun/android-gps-setter/stargazers)
[![LSPosed](https://img.shields.io/github/downloads/Xposed-Modules-Repo/io.github.jqssun.gpssetter/total?label=LSPosed&logo=Android&style=flat&labelColor=F48FB1&logoColor=ffffff)](https://github.com/Xposed-Modules-Repo/io.github.jqssun.gpssetter/releases)
[![GitHub](https://img.shields.io/github/downloads/jqssun/android-gps-setter/total?label=GitHub&logo=GitHub)](https://github.com/jqssun/android-gps-setter/releases)
[![release](https://img.shields.io/github/v/release/jqssun/android-gps-setter)](https://github.com/jqssun/android-gps-setter/releases)
[![build](https://img.shields.io/github/actions/workflow/status/jqssun/android-gps-setter/apk.yml)](https://github.com/jqssun/android-gps-setter/actions/workflows/apk.yml)
[![license](https://img.shields.io/github/license/jqssun/android-gps-setter)](https://github.com/jqssun/android-gps-setter/blob/master/LICENSE)
[![issues](https://img.shields.io/github/issues/jqssun/android-gps-setter)](https://github.com/jqssun/android-gps-setter/issues)
  
Sebuah aplikasi pengatur GPS (GPS setter) yang berbasis pada framework Xposed. Fork ini adalah modul pertama yang mencapai dukungan untuk Android 15+ dengan kode sumber yang tersedia secara publik.

## Rilis

<table>
    <tr>
        <th>Versi</th>
        <th>app-full-*.apk</th>
        <th>app-foss-*.apk</th>
    </tr>
    <tr>
        <th>Pustaka Peta</th>
        <td>GMS (com.google.android.gms:play-services-maps)</td>
        <td>MapLibre (org.maplibre.gl:android-sdk)</td>
    </tr>
    <tr>
        <th>Lokasi Terpadu (Fused)</th>
        <td>GMS (com.google.android.gms:play-services-location)</td>
        <td>microG (org.microg.gms:play-services-location)</td>
    </tr>
    <tr>
        <th>Distribusi</th>
        <td>
            <a href="https://github.com/jqssun/android-gps-setter/releases">
                <img
                    src="https://raw.githubusercontent.com/NeoApplications/Neo-Backup/refs/heads/main/badge_github.png"
                    alt="Dapatkan di GitHub" width="200" />
            </a>
        </td>
        <td>
            <a href="https://f-droid.org/packages/io.github.jqssun.gpssetter">
                <img
                    src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
                    alt="Dapatkan di F-Droid" width="200" />
            </a>
        </td>
    </tr>
</table>

<!-- 
[<img src="https://raw.githubusercontent.com/NeoApplications/Neo-Backup/refs/heads/main/badge_github.png" alt="Get it on GitHub" height="80">](https://github.com/jqssun/android-gps-setter/releases)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">]()
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" height="80">]()
-->

## Motivasi

Semakin banyak aplikasi yang menyalahgunakan izin lokasi untuk tujuan pelacakan, dan mencegah pengguna menggunakan aplikasi tanpa memberikan izin tersebut. Secara tradisional di Android, memodifikasi respons dari server Android dilakukan menggunakan penyedia lokasi palsu (mock location provider) - namun, ketersediaan fitur ini sangat bergantung pada perangkat. Selain itu, beberapa aplikasi mulai secara eksplisit memeriksa sinyal untuk mengetahui apakah lokasi yang diberikan dapat diandalkan atau palsu. Modul ini bertujuan untuk mengurangi hal tersebut dengan memberikan kemampuan untuk:
1. Melakukan *hook* pada aplikasi itu sendiri untuk memodifikasi lokasi yang diterimanya, atau
2. Melakukan *hook* pada server sistem jika aplikasi secara eksplisit memeriksa apakah aplikasi tersebut sedang di-*hook*.

Secara khusus, dalam kasus hanya melakukan *hook* pada aplikasi, modul ini menyadap metode dari [`android.location.Location`](https://developer.android.com/reference/android/location/Location) dan [`android.location.LocationManager`](https://developer.android.com/reference/android/location/LocationManager) termasuk:
- [`getLatitude()`](https://developer.android.com/reference/android/location/Location#getLatitude())
- [`getLongitude()`](https://developer.android.com/reference/android/location/Location#getLongitude())
- [`getAccuracy()`](https://developer.android.com/reference/android/location/Location#getAccuracy())
- [`getLastKnownLocation(java.lang.String)`](https://developer.android.com/reference/android/location/LocationManager#getLastKnownLocation(java.lang.String))

## Kompatibilitas

- Android 8.1+ (telah diuji coba hingga Android 16 Beta 2)
- Perangkat yang sudah di-root dengan framework Xposed terinstal (misalnya LSPosed)
- Perangkat tanpa root menggunakan LSPatch (dengan menyematkan lokasi tertentu secara manual)

## Fitur Utama

- ✨ **(Baru) Mendukung API lokasi server sistem (Android 14+)**
  Modul ini dirancang untuk dapat berfungsi dengan baik pada sistem operasi terbaru, dengan mengatasi perubahan API lokasi yang diperkenalkan sejak Android 14.
- 🍀 **(Baru) Mendukung varian build FLOSS (Sepenuhnya Open-Source)**
  Tersedia versi tanpa layanan Google (GMS) yang menggunakan alternatif murni open-source, termasuk seluruh pustaka dasar seperti microG dan MapLibre.
- 🖲️ **(Baru) Hamparan Joystick Layar (On-Screen Joystick)**
  Memungkinkan Anda menyesuaikan atau menggeser lokasi secara langsung (on-the-fly) dari layar mana saja tanpa harus membuka aplikasi pengatur lokasi kembali.
- 🎉 **(Baru) Resource Kustom & Pembaruan Dependensi**
  Menghadirkan desain bundel sumber daya yang baru beserta pembaruan ke pustaka yang dibutuhkan untuk menjamin stabilitas dan performa.
- 🎲 **Lokasi Acak (Random Location)**
  Bisa mengatur lokasi palsu secara dinamis yang terus berubah secara acak dalam radius sekitar titik yang telah Anda tentukan (membuatnya terlihat lebih realistis/natural).
- 🔥 **Kompatibel dengan Material Design**
  Antarmuka pengguna didesain dengan gaya Material Design terbaru yang memberikan pengalaman penggunaan (UI/UX) yang modern dan mulus.

## Demo

<video loop src='https://github.com/user-attachments/assets/fbc0901c-b126-4ca7-9239-34390a76e7f9' alt="demo" width="200" style="display: block; margin: auto;"></video> <!-- https://github.com/jqssun/android-gps-setter/releases/download/v0.0.1/0.mp4 -->

## Kredit & Penghargaan

- [Android1500](https://github.com/Android1500/GpsSetter) atas versi asli GpsSetter yang menargetkan Android 8.1 hingga 13.
- [MapLibre](https://github.com/maplibre/maplibre-native) untuk pustaka pemetaan (mapping library).
- [microG](https://github.com/microg/GmsCore) untuk implementasi FOSS (Free and Open Source Software) dari Google Mobile Services.