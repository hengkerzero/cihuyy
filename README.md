# Cihuyy

[![Stars](https://img.shields.io/github/stars/hengkerzero/cihuyy)](https://github.com/hengkerzero/cihuyy/stargazers)
[![GitHub](https://img.shields.io/github/downloads/hengkerzero/cihuyy/total?label=GitHub&logo=GitHub)](https://github.com/hengkerzero/cihuyy/releases)
[![release](https://img.shields.io/github/v/release/hengkerzero/cihuyy)](https://github.com/hengkerzero/cihuyy/releases)
[![build](https://img.shields.io/github/actions/workflow/status/hengkerzero/cihuyy/apk.yml)](https://github.com/hengkerzero/cihuyy/actions/workflows/apk.yml)
[![license](https://img.shields.io/github/license/hengkerzero/cihuyy)](https://github.com/hengkerzero/cihuyy/blob/master/LICENSE)
[![issues](https://img.shields.io/github/issues/hengkerzero/cihuyy)](https://github.com/hengkerzero/cihuyy/issues)

**Cihuyy** — GPS Spoofer berbasis Xposed framework dengan fitur Auto Walk, Joystick, dan hook lokasi sistem.

## Fitur Utama

- 🚶 **Auto Walk** — GPS bergerak otomatis mengikuti rute jalan asli (OSRM routing)
- 🖲️ **Joystick Overlay** — Kontrol lokasi langsung dari layar tanpa buka app
- 🎲 **Random Location** — Lokasi palsu berubah acak dalam radius tertentu
- 🔒 **System Hook** — Hook lokasi di level sistem (Android 14+) untuk bypass anti-cheat
- ⚡ **Speed Options** — Pilih kecepatan: jalan, lari, sepeda, mobil
- ⏸️ **Pause/Resume/Stop** — Kontrol penuh saat Auto Walk berjalan
- 🗺️ **Dual Map Support** — Google Maps (full) atau MapLibre (FOSS)

## Kompatibilitas

- Android 8.1+ (diuji hingga Android 16 Beta 2)
- Perangkat root dengan Xposed/LSPosed
- Perangkat non-root via LSPatch (pin lokasi manual)

## Cara Pakai Auto Walk

1. Buka app → pindah ke mode **Walk**
2. Tap peta untuk titik **START**
3. Tap lagi untuk titik **FINISH**
4. App gambar polyline rute jalan asli
5. Pilih speed → tekan **Play**
6. GPS jalan otomatis sampai finish 🎉

## Download

📥 [Releases](https://github.com/hengkerzero/cihuyy/releases)

## Kredit

Developed by **@hengkerzero** (2025)

Based on:
- [GPS Setter](https://github.com/jqssun/android-gps-setter) by @jqssun (2024-2025)
- [GpsSetter](https://github.com/Android1500/GpsSetter) by @Android1500 (2022-2023)
- [MapLibre](https://github.com/maplibre/maplibre-native) — pustaka pemetaan
- [microG](https://github.com/microg/GmsCore) — FOSS Google Mobile Services

## Lisensi

Lihat file [LICENSE](LICENSE) untuk detail.
