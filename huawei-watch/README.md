# openGym HUAWEI WATCH FIT 5 Pro HAP

Bu klasör DevEco Studio ile açılabilen Lite Wearable JavaScript/HML saat uygulamasıdır.

## Kurulum

1. DevEco Studio'da `huawei-watch/` klasörünü açın.
2. `entry/src/main/js/MainAbility/constants/constants.js` içindeki `PHONE_APP_FINGERPRINT` değerini AppGallery Connect'teki telefon uygulaması imza parmak iziyle değiştirin.
3. `entry/src/main/config.json` içindeki `supportLists` değerini aynı parmak iziyle güncelleyin.
4. Huawei Developer hesabında Wear Engine hizmetini etkinleştirin.
5. HAP'ı imzalayın ve WATCH FIT 5 Pro'ya yükleyin.

Saat uygulaması telefon eşlikçili MVP'yi uygular: snapshot gösterir ve tamamlanan setleri Wear Engine üzerinden telefona gönderir.
Telefon aktif bir antrenman çalıştırmıyorsa, günün hazır rutini snapshot içinde varsa saatten antrenman başlatılabilir.

Telefon Android projesi için `frontend/android/gradle.properties` dosyanıza şu iki satırı yerel olarak ekleyin; bu değerleri commit etmeyin:

```properties
huaweiWatchPackage=ch.duartesantos.opengym.watch
huaweiWatchFingerprint=WATCH_APP_FINGERPRINT_FROM_APPGALLERY_CONNECT
```

Telefon tarafında Huawei plugin'i `com.huawei.hms:wearengine:5.0.2.306` kullanır. Fit 5 Pro üzerindeki saat paketi ve telefon APK'sı aynı AppGallery Connect/Wear Engine yapılandırmasına göre imzalanmalıdır.

## Protokol

Telefon -> saat: `{v:"opengym-fit-v1", type:"snapshot", json:"..."}`

Saat -> telefon: `{v:"opengym-fit-v1", event:{id,type:"setDone",wid,exId,entryIdx,setIdx,w,r,at}}`

Saat -> telefon (hazır rutini başlatma): `{v:"opengym-fit-v1", event:{id,type:"start",wid,routineIds,at}}`
