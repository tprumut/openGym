# HUAWEI WATCH FIT 5 Pro entegrasyonu

## Hedef

openGym'in mevcut Apple Watch entegrasyonundaki telefon-saat senkron modelini, Android telefon + HUAWEI WATCH FIT 5 Pro kombinasyonuna taşımak.

İlk sürümün hedefi telefon eşlikçili antrenmandır:

- telefonda başlayan aktif antrenmanı saate göndermek,
- saat üzerinde egzersiz, set, tekrar/ağırlık ve dinlenme bilgisini göstermek,
- saatten set tamamlandı ve antrenmanı bitir olaylarını telefona göndermek,
- gelen olayları openGym'in mevcut antrenman aksiyonları üzerinden uygulamak.

Kalp atışı, Huawei Health kayıtları ve telefondan bağımsız antrenman başlatma ilk sürümün dışında tutulur. Bunlar saat protokolü ve izinleri doğrulandıktan sonra ayrı bir aşamadır.

## Neden ayrı bir saat projesi gerekiyor?

`frontend/` React 19 + Vite uygulamasıdır ve Android tarafında Capacitor kabuğu olarak çalışır. WATCH FIT 5 Pro ise Lite Wearable sınıfındadır. Saat uygulaması doğrudan Capacitor APK'sı olarak paketlenmez; DevEco Studio ile JavaScript/HML tabanlı bir Lite Wearable/HAP projesi olarak oluşturulur.

Telefon ile saat arasındaki iletişim Huawei Wear Engine üzerinden yapılır. Telefon tarafında Android Wear Engine SDK'sını kullanan bir Capacitor native plugin, saat tarafında ise Wear Engine saat SDK'sını kullanan HAP bulunur.

## Önerilen veri akışı

```text
openGym React state
  -> watch-sync.js snapshot
  -> Capacitor HuaweiWatchBridge
  -> Wear Engine phone SDK
  -> Bluetooth / Huawei Health Wear Engine
  -> Lite Wearable HAP

Lite Wearable HAP event
  -> Wear Engine watch SDK
  -> phone SDK
  -> Capacitor HuaweiWatchBridge event queue
  -> watch-sync.js applyEvents()
  -> existing workout actions
```

Saat protokolü mevcut `frontend/src/lib/watch-sync.js` sözleşmesine dayanır. Böylece Android saat olayları, Apple Watch olayları gibi ikinci bir antrenman mantığı oluşturmaz.

## Snapshot ve olay sözleşmesi

Telefonun saate gönderdiği JSON için mevcut alanlar korunur:

- `v`, `unit`, `active`, `ready`, `settings`, `healthGone`, `discarded`
- `active.entries[]` içinde `id`, `name`, `mode`, `restSec`, `sets[]`
- set içinde `w`, `r`, `sec`, `done`, `warmup`, gerektiğinde `sides`

Saatten telefona gelen minimum olaylar:

```json
{"id":"evt-...","type":"setDone","wid":"...","exId":"...","entryIdx":0,"setIdx":1,"w":40,"r":10,"at":0}
```

```json
{"id":"evt-...","type":"finish","wid":"...","at":0}
```

Olaylar saat tarafında kuyruklanır. Telefon olayı başarıyla uyguladıktan sonra `ack` gönderir. Bu sayede Bluetooth kopması veya telefonun arka plana alınması veri kaybına dönüşmez.

## Cihaz ve hesap gereksinimleri

- HUAWEI WATCH FIT 5 Pro ve güncel firmware.
- Poco F5 Pro üzerinde HUAWEI Health ve HMS Core.
- Huawei Health'in arka planda çalışmasına ve bildirim/gerekli bağlantı izinlerine izin verilmesi.
- Huawei Developer hesabı ve AppGallery Connect projesi.
- DevEco Studio ile Lite Wearable JavaScript/HML proje şablonu.
- Telefon uygulaması için Wear Engine izni ve imzalı debug/release paketleri.

Huawei'nin güncel Wear Engine dokümanında WATCH FIT serisi için WATCH FIT 1 ve sonrası desteklenir. Desteklenen kabiliyetler arasında telefon-saat uygulama mesajlaşması ve dosya paylaşımı bulunur; kalp atışı gibi kullanıcı durumu API'leri enterprise kapsamına girebilir.

## Uygulama sırası

1. `frontend/src/lib/watch-sync.js` içindeki iOS'a özel plugin seçimini platform bağımsız bir `HuaweiWatchBridge` seçimiyle genişlet.
2. Android Capacitor plugin'i ekle. Plugin; `status`, `sendSnapshot`, `drain`, `ack`, `addListener` ve `openOnWatch` sözleşmesini uygulasın.
3. Wear Engine bağımlılığını Huawei'nin güncel SDK sürümüne sabitle ve uygulama kimliği/izinlerini AppGallery Connect projesiyle eşleştir.
4. DevEco Studio'da `huawei-watch/` Lite Wearable HAP'ını oluştur. Snapshot parse/render, set tamamla, dinlenme sayacı ve olay kuyruğunu ekle.
5. Önce test cihazı iletişimini doğrula: snapshot gönderme, set olayı, ack ve Bluetooth kesilip gelince yeniden gönderim.
6. Gerçek Fit 5 Pro üzerinde paket imzalama, kurulum ve Huawei Health eşlikçi akışını doğrula.
7. İkinci aşamada Huawei Health/kalp atışı entegrasyonunu ayrıca tasarla.

## Bilinen sınırlamalar

- Bu depodaki Apple Watch Swift kodu Fit 5 Pro'da yeniden kullanılamaz; yalnızca veri sözleşmesi ve React tarafındaki `applyEvents()` mantığı yeniden kullanılabilir.
- Fit 5 Pro için HAP paketinin gerçek cihazda Huawei imzalama ve Wear Engine izinleriyle test edilmesi gerekir. Linux sandbox'ta DevEco Studio ve fiziksel cihaz doğrulaması yapılamaz.
- Huawei SDK sürümü ve dağıtım izinleri değişebildiği için SDK artifact bilgisi sabitlenmeden sahte import veya derlenmeyen native kod eklenmemelidir.

## Kaynaklar

- [Huawei Wear Engine Service Introduction](https://developer.huawei.com/consumer/en/doc/connectivity-Guides/service-introduction-0000000000018585)
- [Huawei Wear Engine Wearable App Development](https://developer.huawei.com/consumer/en/doc/connectivity-Guides/watch-dev-0000001119195134)
- [openGym architecture](https://github.com/DuarteSantos8/openGym/blob/main/docs/ARCHITECTURE.md)
