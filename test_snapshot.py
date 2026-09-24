"""
Test Client Snapshot untuk Android IP Webcam Pro
------------------------------------------------
Mengambil 1 frame foto dari endpoint /shot.jpg
"""

import sys
import urllib.request
import numpy as np
import cv2

def main():
    default_ip = "192.168.1.15"
    if len(sys.argv) > 1:
        ip = sys.argv[1]
    else:
        ip = input(f"Masukkan IP Android [{default_ip}]: ").strip() or default_ip

    shot_url = f"http://{ip}:8080/shot.jpg"
    print(f"[INFO] Mengambil foto snapshot dari {shot_url} ...")

    try:
        req = urllib.request.urlopen(shot_url, timeout=5)
        img_arr = np.asarray(bytearray(req.read()), dtype=np.uint8)
        img = cv2.imdecode(img_arr, cv2.IMREAD_COLOR)

        if img is not None:
            filename = "snapshot.jpg"
            cv2.imwrite(filename, img)
            print(f"[SUCCESS] Foto berhasil disimpan ke '{filename}' (Ukuran: {img.shape[1]}x{img.shape[0]})")
            cv2.imshow("Snapshot", img)
            print("Tekan sembarang tombol pada jendela foto untuk menutup.")
            cv2.waitKey(0)
            cv2.destroyAllWindows()
        else:
            print("[ERROR] Gagal men-decode gambar.")
    except Exception as e:
        print(f"[ERROR] Terjadi kesalahan: {e}")

if __name__ == "__main__":
    main()
