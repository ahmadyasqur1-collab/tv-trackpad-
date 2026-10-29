# TV Trackpad (Android)

HP Android jadi trackpad + keyboard untuk TV. Mandiri: tanpa Mac, tanpa Node.

| Merek | Cara kerja |
|---|---|
| LG webOS | Kursor penuh (geser, klik, scroll, seret) |
| Android / Google TV (TCL, Sony, Xiaomi, Philips, dll) | Mode panah + pairing kode di layar TV |
| Roku TV (TCL Roku, Hisense Roku, dll) | Mode panah |
| Samsung Tizen | Mode panah |

Mode panah: geser 1 jari = tombol panah, ketuk = OK, ketuk 2 jari = Kembali, geser 2 jari = atas/bawah.
Tombol Kembali, Home, dan Volume ada di kiri/kanan layar untuk semua merek.

## Cara dapat APK (tanpa compile sendiri)
1. Buat repo baru di GitHub, upload seluruh isi folder ini (termasuk folder `.github`).
2. Buka tab **Actions**, tunggu workflow "Build APK" selesai (sekitar 3-5 menit).
3. Download APK dari:
   - tab **Releases** (tag `latest`), atau
   - halaman run workflow, bagian **Artifacts**.
4. Buka file `.apk` di HP, izinkan "install dari sumber tidak dikenal", lalu install.

Kalau build gagal, buka log di tab Actions dan kirim pesan error-nya.

## Cara pakai
1. HP dan TV harus di WiFi yang sama.
2. Buka app. Saat pertama, layar "Hubungkan ke TV" muncul dan scan otomatis. Ketuk TV yang ketemu.
   Kalau tidak ketemu: pilih merek, isi IP TV manual, ketuk Sambung.
3. Izin / pairing:
   - **LG**: pilih YA/OK di layar TV.
   - **Android/Google TV**: kode 6 karakter muncul di TV, ketik di HP lalu Kirim. Cukup sekali.
   - **Samsung**: pilih Izinkan di TV.
   - **Roku**: aktifkan Settings > System > Advanced > Control by mobile apps > Permissive/Default.
4. Ketuk ikon TV di pojok kanan atas untuk ganti TV kapan saja.

## Catatan
- Cek IP TV: Pengaturan > Jaringan > Wi-Fi > Lanjutan.
- Input teks: LG dan Roku paling lengkap. Android TV hanya huruf, angka, spasi, dan beberapa simbol. Samsung tergantung model.
- Scroll terasa terbalik? Ketuk ikon panah atas-bawah di bar atas.
