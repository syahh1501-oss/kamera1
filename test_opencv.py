"""
Test Client OpenCV & Face Detection untuk Android IP Webcam Pro
---------------------------------------------------------------
Script ini membaca streaming video dari aplikasi Android IP Webcam
dan mendeteksi wajah secara real-time menggunakan Haar Cascade.
"""

import cv2
import sys

def main():
    # Ganti dengan IP Android Anda yang tertera di aplikasi (contoh: 192.168.1.15)
    default_ip = "192.168.1.15"
    if len(sys.argv) > 1:
        ip = sys.argv[1]
    else:
        ip = input(f"Masukkan IP Android [{default_ip}]: ").strip() or default_ip

    stream_url = f"http://{ip}:50050/video"
    print(f"\n[INFO] Menghubungkan ke stream: {stream_url} ...")

    cap = cv2.VideoCapture(stream_url)
    # Atur buffer size kecil agar latensi minimal
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

    if not cap.isOpened():
        print(f"[ERROR] Gagal membuka stream dari {stream_url}")
        print("Pastikan:")
        print("1. HP dan Laptop/PC berada di jaringan Wi-Fi / Hotspot yang sama.")
        print("2. Server di aplikasi Android IP Webcam sudah di-START.")
        print("3. Coba buka http://{ip}:50050 di browser terlebih dahulu.")
        return

    print("[SUCCESS] Terhubung ke IP Webcam! Tekan 'q' pada jendela video untuk keluar.\n")

    # Load classifier deteksi wajah
    face_cascade = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalface_default.xml')

    while True:
        ret, frame = cap.read()
        if not ret:
            print("[WARN] Gagal membaca frame dari stream, mencoba kembali...")
            continue

        # Deteksi wajah sederhana
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        faces = face_cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(30, 30))

        # Gambar bounding box wajah
        for (x, y, w, h) in faces:
            cv2.rectangle(frame, (x, y), (x + w, y + h), (0, 210, 106), 2)
            cv2.putText(frame, "Wajah Terdeteksi", (x, y - 10),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 210, 106), 2)

        # Info overlay
        info_text = f"IP Webcam Stream | Wajah: {len(faces)} | Tekan 'q' untuk keluar"
        cv2.putText(frame, info_text, (10, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)

        cv2.imshow("Android IP Webcam - Face Detection Preview", frame)

        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

    cap.release()
    cv2.destroyAllWindows()
    print("[INFO] Stream ditutup.")

if __name__ == "__main__":
    main()
