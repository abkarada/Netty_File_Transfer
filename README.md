# QUIC File Transfer

Netty QUIC tabanlı dosya transfer uygulaması.

## Gereksinimler

- Java 11+
- Linux x86_64 (native QUIC kütüphanesi için)

## Kullanım

### Proje Build Etme

```bash
./gradlew build
```

### Server Başlatma

```bash
./gradlew run --args="server 9443"
```

Ya da:

```bash
java -jar build/libs/QUICC-1.0-SNAPSHOT.jar server 9443
```

### Client İle Dosya Gönderme

```bash
./gradlew run --args="client localhost 9443 test-files/sample.txt"
```

Ya da:

```bash
java -jar build/libs/QUICC-1.0-SNAPSHOT.jar client localhost 9443 test-files/sample.txt
```

## Test Etme

1. İlk terminal'de server başlatın:
   ```bash
   ./gradlew run --args="server 9443"
   ```

2. İkinci terminal'de client ile dosya gönderin:
   ```bash
   ./gradlew run --args="client localhost 9443 test-files/sample.txt"
   ```

## ✅ Test Sonuçları

QUIC file transfer sistemi başarıyla test edildi:

### Performans Metrikleri
- **Dosya boyutu**: 628 bytes
- **Transfer süresi**: ~5ms
- **Throughput**: Gerçek zamanlı hesaplanıyor
- **QUIC protokolü**: v1
- **Şifreleme**: AES128_GCM
- **Anahtar değişimi**: X25519
- **Connection kurulum süresi**: ~30ms
- **Toplam işlem süresi**: ~111ms

## Troubleshooting

### UnsatisfiedLinkError

Eğer şu hatayı alırsanız:
```
java.lang.UnsatisfiedLinkError: no netty_quiche_xxx in java.library.path
```

Bu durumda:
1. `build.gradle`'daki classifier'ı platform'unuza uygun olarak değiştirin
2. Gradle dependency cache'ini temizleyin: `./gradlew clean`

### Classifier'lar

- Linux x86_64: `linux-x86_64`
- macOS x86_64: `osx-x86_64`  
- macOS ARM64: `osx-aarch_64`
- Windows x86_64: `windows-x86_64`

## Dosya Yapısı

```
src/
├── main/
│   ├── java/quic/
│   │   ├── FileTransferMain.java          # Ana uygulama
│   │   └── SelfSignedCertificateGenerator.java  # SSL sertifikası
│   └── resources/
│       └── logback.xml                    # Logging konfigürasyonu
```

## İleri Düzey Özellikler (TODO)

- [ ] Multi-stream paralel transfer
- [ ] Dosya fragmentasyonu ve yeniden birleştirme
- [ ] Progress tracking
- [ ] Checksum doğrulama
- [ ] Yeniden iletim stratejileri
- [ ] Compression desteği