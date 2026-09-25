import cv2
from deepface import DeepFace
import sys

def main():
    # Menggunakan webcam laptop (0) atau bisa diganti dengan URL IP Webcam
    default_source = "0"
    print("=== Aplikasi Deteksi Usia dan Jenis Kelamin ===")
    print("Masukkan '0' untuk webcam bawaan, atau masukkan URL stream IP Webcam (misal: http://192.168.1.15:8080/video)")
    
    source_input = input(f"Pilih sumber kamera [{default_source}]: ").strip()
    if not source_input:
        source_input = default_source
        
    if source_input == "0":
        cap = cv2.VideoCapture(0)
    else:
        cap = cv2.VideoCapture(source_input)
        
    if not cap.isOpened():
        print(f"[ERROR] Gagal membuka kamera dari sumber: {source_input}")
        return

    print("[SUCCESS] Kamera berhasil dibuka! Tekan 'q' untuk keluar.")
    
    # Load classifier deteksi wajah standar
    face_cascade = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalface_default.xml')
    
    frame_count = 0
    predictions = {}
    
    while True:
        ret, frame = cap.read()
        if not ret:
            print("[WARN] Gagal membaca frame dari stream...")
            continue
            
        frame_count += 1
        
        # Deteksi wajah menggunakan Haar Cascade untuk lokalisasi cepat
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        faces = face_cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(100, 100))
        
        for idx, (x, y, w, h) in enumerate(faces):
            margin = 20
            y1 = max(0, y - margin)
            y2 = min(frame.shape[0], y + h + margin)
            x1 = max(0, x - margin)
            x2 = min(frame.shape[1], x + w + margin)
            
            face_roi = frame[y1:y2, x1:x2]
            
            if frame_count % 10 == 0 or idx not in predictions:
                try:
                    result = DeepFace.analyze(face_roi, actions=['age', 'gender'], enforce_detection=False)
                    if isinstance(result, list):
                        result = result[0]
                    
                    age = result.get('age', 'N/A')
                    gender_dict = result.get('gender', {})
                    if isinstance(gender_dict, dict):
                        gender = max(gender_dict, key=gender_dict.get)
                    else:
                        gender = gender_dict
                        
                    predictions[idx] = f"{gender}, {age} thn"
                except Exception as e:
                    pass
            
            cv2.rectangle(frame, (x, y), (x + w, y + h), (0, 210, 106), 2)
            if idx in predictions:
                cv2.putText(frame, predictions[idx], (x, y - 10),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)

        cv2.imshow("Deteksi Usia dan Jenis Kelamin", frame)
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

    cap.release()
    cv2.destroyAllWindows()
    print("[INFO] Aplikasi ditutup.")

if __name__ == "__main__":
    main()
