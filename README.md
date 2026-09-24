# 📹 Android IP Webcam Pro

Aplikasi Android Native untuk mengubah smartphone Android menjadi **IP Webcam Server** berperforma tinggi dengan latensi rendah. Sangat cocok diintegrasikan dengan **Python**, **OpenCV**, sistem **Face Recognition**, VLC, OBS, maupun diakses langsung via Web Browser.

---

## 🌟 Fitur Utama

- **MJPEG Streaming over HTTP (`/video`)**: Kompatibel 100% langsung dengan `cv2.VideoCapture("http://<IP>:8080/video")` tanpa perlu dependensi RTSP yang rumit.
- **Snapshot Foto Instan (`/shot.jpg`)**: Mengambil 1 frame foto JPEG beresolusi tinggi langsung melalui URL.
- **Live Audio Streaming (`/audio`)**: Mengalirkan suara mikrofon HP secara real-time dalam format WAV/PCM ke PC atau browser.
- **Modern Web Dashboard (`http://<IP>:8080`)**:
  - UI Web responsif bertema Dark Mode dengan glassmorphism.
  - Pemutar video live dan audio live langsung di browser tanpa instalasi software tambahan.
  - Kontrol kamera jarak jauh: Ganti lensa depan/belakang, nyalakan senter (flash/torch), slider zoom digital, dan slider kualitas JPEG.
  - Tombol salin URL dan status FPS serta jumlah klien yang sedang terhubung.
- **Foreground Service**: Streaming tetap berjalan stabil di latar belakang meskipun aplikasi diminimalkan atau layar dimatikan.
- **CameraX + NanoHTTPD**: Arsitektur modern Android menggunakan CameraX ImageAnalysis untuk performa pemrosesan kamera yang efisien.

---

## 📁 Struktur Direktori Project

```text
Android-IP-Webcam/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/example/ipwebcam/
│   │   │   ├── MainActivity.kt               # UI Utama & Event Handling
│   │   │   ├── camera/CameraStreamManager.kt  # Pengelola CameraX & Konversi Frame
│   │   │   ├── server/MjpegHttpServer.kt      # Embedded HTTP Server & Web Dashboard
│   │   │   ├── audio/AudioStreamer.kt         # Audio Recorder & WAV Streamer
│   │   │   ├── service/WebcamService.kt       # Foreground Service (Background Stream)
│   │   │   └── utils/NetworkUtils.kt          # Deteksi IPv4 Wi-Fi / Hotspot
│   │   └── res/
│   │       ├── layout/activity_main.xml       # Desain UI Aplikasi
│   │       ├── values/                        # Tema, Warna & String
│   │       └── drawable/                      # Icon & Background
│   └── build.gradle.kts                       # Konfigurasi dependensi aplikasi
├── test_opencv.py                             # Script uji coba OpenCV & Face Detection
├── test_snapshot.py                           # Script uji coba unduh snapshot /shot.jpg
├── build.gradle.kts                           # Root Gradle Config
├── settings.gradle.kts
└── README.md
```

---

## 🚀 Cara Menjalankan Project di Android Studio

1. **Buka Project**:
   - Buka **Android Studio**.
   - Pilih **File > Open**, lalu arahkan ke folder project ini.
2. **Sinkronisasi Gradle**:
   - Tunggu hingga Android Studio menyelesaikan Gradle Sync & mengunduh dependensi (`CameraX`, `NanoHTTPD`, dll).
3. **Build & Jalankan di Perangkat**:
   - Hubungkan HP Android ke komputer dengan kabel USB (aktifkan *USB Debugging* di Opsi Pengembang HP).
   - Klik tombol **Run (▶️)** di toolbar atas Android Studio.
4. **Memberikan Izin**:
   - Saat aplikasi pertama kali dibuka, izinkan akses **Kamera** dan **Mikrofon**.

---

## 📱 Cara Menggunakan Aplikasi

1. **Koneksi Jaringan**:
   - Pastikan HP Android dan Laptop/PC berada pada **jaringan Wi-Fi yang sama** (atau nyalakan **Hotspot Portabel** di HP dan sambungkan Laptop ke hotspot tersebut).
2. **Jalankan Server**:
   - Tekan tombol hijau **START SERVER** di aplikasi.
   - Layar akan menampilkan URL, misalnya: `http://192.168.1.15:8080`.
3. **Akses Dashboard dari Laptop**:
   - Buka browser di PC/Laptop dan buka alamat `http://192.168.1.15:8080`.
   - Anda dapat melihat tampilan kamera secara langsung dan mengatur senter/lensa/kualitas dari PC.

---

## 🐍 Integrasi Python & OpenCV (Face Recognition)

Aplikasi ini dirancang khusus untuk mempermudah integrasi dengan OpenCV pada sistem Computer Vision / Face Recognition.

### 1. Uji Coba Cepat dengan Script Bawaan

Jalankan script pengujian deteksi wajah yang telah disediakan:

```bash
python test_opencv.py 192.168.1.15
```
*(Ganti `192.168.1.15` dengan IP yang tertera pada aplikasi Anda)*

### 2. Contoh Kode Minimal di Python:

```python
import cv2

# Ganti dengan IP smartphone Anda
URL = "http://192.168.1.15:8080/video"

cap = cv2.VideoCapture(URL)
cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)  # Minimalisir latensi buffer

while True:
    ret, frame = cap.read()
    if not ret:
        break

    # Di sini Anda bisa memproses frame untuk Face Recognition / AI
    cv2.imshow("Android IP Webcam Stream", frame)

    if cv2.waitKey(1) & 0xFF == ord('q'):
        break

cap.release()
cv2.destroyAllWindows()
```

### 3. Mengambil Foto Snapshot (`/shot.jpg`):

```python
import cv2
import urllib.request
import numpy as np

# Ambil 1 frame gambar resolusi penuh
resp = urllib.request.urlopen("http://192.168.1.15:8080/shot.jpg")
img_array = np.asarray(bytearray(resp.read()), dtype=np.uint8)
img = cv2.imdecode(img_array, cv2.IMREAD_COLOR)

cv2.imwrite("captured_face.jpg", img)
```

---

## 📡 Daftar Endpoint API Server

| Endpoint | Method | Deskripsi |
| :--- | :---: | :--- |
| `/` | `GET` | Halaman Web Dashboard HTML5 interaktif lengkap dengan kontrol kamera |
| `/video` atau `/mjpeg` | `GET` | Stream video berkelanjutan format MJPEG (`multipart/x-mixed-replace`) |
| `/shot.jpg` | `GET` | Mengambil 1 frame foto JPEG tunggal |
| `/audio` | `GET` | Live streaming audio mikrofon smartphone (format WAV PCM) |
| `/api/status` | `GET` | Mengembalikan status JSON (FPS, kamera aktif, torch, kualitas, jumlah klien) |
| `/api/control?action=torch&value=on` | `GET` | Menyalakan lampu flash / senter |
| `/api/control?action=torch&value=off` | `GET` | Mematikan lampu flash / senter |
| `/api/control?action=camera&value=switch`| `GET` | Mengalihkan kamera (Depan / Belakang) |
| `/api/control?action=quality&value=85` | `GET` | Mengubah kualitas kompresi JPEG (30 - 100) |
| `/api/control?action=zoom&value=2.0` | `GET` | Mengatur digital zoom (1.0x - 5.0x) |

---

## 📄 Lisensi
Proyek ini dibuat untuk kebutuhan pengembangan streaming video IP Webcam Android dengan integrasi OpenCV dan Face Recognition.
