# Core Banking System

[![CI](https://github.com/Goktugelbir/banking-system/actions/workflows/ci.yml/badge.svg)](https://github.com/Goktugelbir/banking-system/actions/workflows/ci.yml)

Java 21 · Spring Boot 3.3 · PostgreSQL · Redis · Kafka · Resilience4j · Testcontainers

Çift taraflı defter (double-entry ledger) üzerine kurulu bir bankacılık çekirdeği. Kapsadığı konular: eşzamanlı ve deadlock'suz transfer, idempotency, kur teklifi (quote) + circuit breaker, transactional outbox + Kafka ile fraud tespiti, bankalar arası EFT için orkestrasyon tabanlı saga.

```bash
docker compose up --build        # Postgres, Redis, Kafka, dış banka, çekirdek banka
open http://localhost:8080/swagger-ui.html
```

Varsayılan admin kullanıcısı: `admin@bank.local` / `admin12345`

> **Not:** JWT secret ve admin şifresi, projenin tek komutla ayağa kalkabilmesi için repoda bilinçli olarak açık bırakılmış **demo değerleridir**. Gerçek ortamda `JWT_SECRET`, `ADMIN_EMAIL`, `ADMIN_PASSWORD` environment variable'ları ile değiştirilmelidir.

---

## Mimari

```mermaid
flowchart LR
    client([İstemci]) -- JWT --> api

    subgraph core["core-banking (Spring Boot)"]
        api[REST API<br/>Spring Security + JWT]
        mm[MoneyMovementService]
        ledger[(LedgerService<br/>double-entry)]
        idem[IdempotencyService]
        fx[FxQuoteService]
        saga[EftSagaOrchestrator]
        relay[Outbox Relay<br/>@Scheduled]
        fraud[FraudService<br/>@KafkaListener]
    end

    pg[(PostgreSQL<br/>accounts · ledger_entries<br/>transactions · outbox · sagas)]
    redis[(Redis<br/>kur cache · quote TTL<br/>fraud pencereleri)]
    kafka{{Kafka<br/>bank.transactions}}
    ext[external-bank<br/>%20 timeout · %10 red]
    rates[open.er-api.com<br/>CoinGecko]

    api --> mm & fx & saga
    mm --> idem & ledger
    ledger --> pg
    mm -- "aynı TX'te outbox satırı" --> pg
    relay -- "FOR UPDATE SKIP LOCKED" --> pg
    relay --> kafka --> fraud
    fraud --> redis
    fraud -- "hesabı dondur / incelemeye al" --> pg
    fx --> redis
    fx -- "Circuit Breaker" --> rates
    saga -- "send / cancel (2s timeout)" --> ext
```

### EFT saga akışı

```mermaid
sequenceDiagram
    participant C as Müşteri
    participant O as Orchestrator
    participant DB as Postgres (ledger)
    participant X as Dış Banka

    C->>O: POST /api/transfers/eft (Idempotency-Key)
    O->>DB: TX1 müşteri → EFT_SUSPENSE (bloke) · saga=FUNDS_RESERVED
    O->>X: POST /transfers {reference=sagaId}
    alt ACCEPTED
        O->>DB: TX2 EFT_SUSPENSE → EXTERNAL_SETTLEMENT · COMPLETED · outbox event
    else REJECTED veya circuit açık
        O->>DB: TX2 EFT_SUSPENSE → müşteri (telafi) · COMPENSATED
    else Timeout (sonuç bilinmiyor)
        O->>DB: saga=IN_DOUBT (para bloke kalır)
        O->>X: POST /transfers/{ref}/cancel
        alt "zaten ACCEPTED"
            O->>DB: COMPLETED
        else CANCELLED
            O->>DB: telafi · COMPENSATED
        else cancel da başarısız
            Note over O,DB: IN_DOUBT kalır; recovery job 10 sn'de bir tekrar dener
        end
    end
```

### Modüller

| Modül | Açıklama |
|---|---|
| `core-banking` | API, defter, transfer, FX, outbox relay, saga orkestratörü, fraud consumer |
| `external-bank` | Dış banka simülasyonu: isteklerin %20'si 5 sn'de cevap verir (çağıranın timeout'u 2 sn), %10'u reddedilir |

Fraud mantıksal olarak ayrı bir servis: çekirdekle yalnızca Kafka üzerinden konuşuyor ve tek çıktısı hesap durumunu değiştirmek. Demo hafif kalsın diye aynı deployable içinde paketlendi. `FraudService` paketi ayrı bir Spring Boot uygulamasına taşınabilir.

---

## Neden bu kararı verdim?

### 1. Bakiye = defter kayıtlarının toplamı (ikisi birlikte tutuluyor)

- Her para hareketi `ledger_entries` tablosuna **en az iki satır** yazar. `LedgerService.post()` her para birimi için borç ve alacak toplamlarının eşit olduğunu doğrular; eşit değilse işlem atılır.
- Müşteri karşılığı olmayan hareketlerde de karşı bacak var: kasa (`CASH_*`), döviz pozisyonu (`FX_POSITION_*`), EFT bloke hesabı (`EFT_SUSPENSE_*`) ve muhabir hesap (`EXTERNAL_SETTLEMENT_*`). Bunlar bankanın kendi iç (GL) hesapları ve Flyway ile oluşturuluyor.
- `accounts.balance` alanı bir **cache**. Bakiye okumak için milyonlarca satırı toplamak gerekmiyor. Doğruluğu `ReconciliationService` kanıtlıyor: (a) her hesapta `balance = SUM(ledger)`, (b) defterin tamamı her para biriminde sıfıra kapanıyor. Bu kontrol 5 dakikada bir çalışıyor, ayrıca `GET /api/admin/reconciliation` ile de tetiklenebiliyor. Bütün testler sonunda bu kontrolü yapıyor.
- Son savunma hattı DB'de: `CHECK (type <> 'CUSTOMER' OR balance >= 0)`.

### 2. Para için her zaman `BigDecimal` ve para birimine özel ölçek

`double` 0.1 + 0.2 ≠ 0.3 hatasını yapar. Kolonlar `NUMERIC(38,8)`, Java tarafı `BigDecimal`. `Currency` enum'u her birimin ölçeğini biliyor (TRY 2, BTC 8 hane). Fazla hassasiyetli tutarlar (ör. `10.001 TRY`) **yuvarlanmıyor, reddediliyor**. FX sonucu ise her zaman aşağı yuvarlanıyor (`RoundingMode.DOWN`), çünkü banka elinde olmayan kuruşu dağıtmamalı.

### 3. Kilitleme: Pessimistic ve Optimistic

`bank.locking.mode` ile iki strateji arasında geçiş yapılabiliyor (`AccountLockManager`, `TransactionalExecutor`).

| | **PESSIMISTIC** (varsayılan) | **OPTIMISTIC** |
|---|---|---|
| Mekanizma | `SELECT … FOR UPDATE` | `@Version` kolonu; `UPDATE … WHERE version = ?` |
| Çakışmada | İkinci işlem **bekler** | İkinci işlem `OptimisticLockException` alır, tüm TX **baştan** denenir (jitter'lı backoff) |
| Yüksek çekişme (aynı hesaba 100 istek) | Sıraya girer, verim öngörülebilir | Çok sayıda boşa giden retry, DB'ye fazladan yük, en kötü durumda retry limiti aşılır |
| Düşük çekişme | Kısa süre kilit tutar | Kilit yok, en hızlı yol |
| Deadlock riski | Var, **sıralı kilitleme** ile önleniyor | Yok |

**Deadlock önleme:** Hesaplar her zaman **ID'ye göre küçükten büyüğe** kilitleniyor (`TreeSet`). A→B ve B→A transferleri aynı anda gelirse ikisi de önce `min(A,B)` kilidini ister. Biri bekler, döngü oluşmaz. `opposingTransfersDoNotDeadlock` testi 100 thread ile bunu doğruluyor. Kilitlenecek hesabın para birimi gerekiyorsa (ör. hangi kasa hesabı) önce kilitsiz bir projeksiyonla okunuyor (`findCurrency`). Böylece sıralama hiçbir zaman bozulmuyor.

**Neden varsayılan pessimistic?** Bankacılıkta "sıcak" hesaplar (maaş hesabı, iç kasa hesapları) olağan durum. Çekişme yüksek olduğunda bekleyip bir kez yapmak, 30 kez deneyip 29'unu çöpe atmaktan daha iyi. Bilinen trade-off: iç hesaplar (ör. `CASH_TRY`) her nakit işlemde kilitlendiği için darboğaz olabilir. Gerçek sistemlerde bu, iç hesapları shard'lamak ya da bakiyesini asenkron güncellemek gibi yöntemlerle çözülür.

### 4. Idempotency

İstemci her para hareketinde `Idempotency-Key` başlığı gönderiyor. `IdempotencyService` şöyle çalışıyor:

1. `(customer_id, key)` daha önce görüldüyse saklanan sonuç dönüyor. Para tekrar hareket etmiyor.
2. Görülmediyse işlem çalışıyor ve **ilk SQL komutu** `idempotency_records` tablosuna INSERT oluyor. Bu INSERT, parayı taşıyan **aynı DB transaction'ı** içinde.
3. Aynı anahtarla eşzamanlı iki istek gelirse ikincisinin INSERT'ü primary key index'inde bekliyor. İlki commit ederse ikincisi unique violation alıyor, geri alınıyor ve ilk sonucu döndürüyor. İlki rollback olursa (ör. yetersiz bakiye) ikincisi normal şekilde devam ediyor.
4. Anahtar ile para aynı TX'te olduğu için "para gitti ama anahtar kaydedilmedi" durumu imkânsız.
5. Aynı anahtar **farklı gövdeyle** gelirse (SHA-256 hash karşılaştırması) `422 IDEMPOTENCY_KEY_REUSED` dönüyor. Bu bir retry değil, istemci hatası.
6. Anahtarlar müşteri bazında. İki müşterinin `order-42` anahtarı birbirini etkilemiyor.

`sameKeySentConcurrentlyMovesMoneyOnce` testinde 20 thread aynı anahtarla aynı anda istek atıyor ve tek bir işlem oluşuyor.

### 5. Kur teklifi (quote) + Redis + Circuit Breaker

- **Cache:** Tüm kurlar "1 USD kaç birim" şeklinde tek bir snapshot olarak tutuluyor (çapraz kurlar buradan türetiliyor). Redis'te `fx:rates` anahtarı 30 sn TTL ile saklanıyor.
- **Quote:** `POST /api/fx/quotes`, spread uygulanmış sabit bir kur ve alınacak tutarı döndürüyor. Teklif Redis'te **30 sn TTL** ile duruyor. `accept` çağrısı piyasa o arada oynasa bile işlemi tam bu kurdan yapıyor.
- **Tek kullanımlık:** `transactions.quote_id UNIQUE`. Aynı teklifi farklı anahtarlarla iki kez kabul etme yarışını DB kısıtı çözüyor (Redis silme işlemi tek başına yeterli değil, çünkü TX'ten önce de silinebilir).
- **Kur API'si çökerse:** `LiveRateSource` Resilience4j `fxProvider` circuit breaker'ı ile korunuyor. Ard arda gelen hatalarda devre açılıyor ve çağrılar timeout beklemeden anında reddediliyor (thread'ler yığılmıyor). Bu durumda `fx:rates:last-good` anahtarındaki son başarılı snapshot kullanılıyor ve yanıtta `stale: true` dönüyor. Bu snapshot `max-staleness` süresinden (15 dk) eskiyse **teklif verilmiyor (503)**, çünkü bayat kurla işlem yapmak, hiç işlem yapmamaktan daha kötü.
- `FX_PROVIDER=static` ile internet olmadan deterministik kurlarla çalışılabiliyor (testler de bunu kullanıyor).

### 6. Transactional Outbox

`TransactionCompleted` event'i Kafka'ya doğrudan gönderilmiyor. Event, para hareketiyle **aynı TX'te** `outbox_events` tablosuna yazılıyor (`OutboxWriter`, `Propagation.MANDATORY`). Ayrı bir relay (`OutboxPublisher`) bu tabloyu düzenli olarak okuyup Kafka'ya gönderiyor.

- Doğrudan gönderimde iki hata modu var: "DB commit oldu, Kafka'ya gitmedi" (event kaybı) ve "Kafka'ya gitti, DB rollback oldu" (hayalet event). Outbox ikisini de imkânsız kılıyor.
- `FOR UPDATE SKIP LOCKED` sayesinde birden fazla instance relay'i çakışmadan paralel çalıştırabiliyor.
- Teslimat **at-least-once**: Kafka ack verdikten sonra, satır işaretlenmeden önce uygulama çökerse event tekrar gidiyor. Consumer `processed_events` tablosu ile tekilleştirme yapıyor (aynı TX'te).
- Partition key gönderen hesap. Böylece bir hesabın event'leri sıralı geliyor ve fraud kuralları doğru çalışıyor.
- Producer `acks=all` ve `enable.idempotence=true` ile çalışıyor. Hatalı mesajlar 3 denemeden sonra `bank.transactions.DLT` topic'ine düşüyor.

### 7. Fraud kuralları (Redis)

| Kural | Redis yapısı | Aksiyon |
|---|---|---|
| 5 dakikada 3'ten fazla transfer | Sorted set, skor = event zamanı. `ZADD` → `ZREMRANGEBYSCORE` → `ZCARD` (sliding window). Member = transactionId olduğu için tekrar gelen event sayacı artırmıyor | `FROZEN` |
| 02:00–05:00 arası (Europe/Istanbul) yüksek tutar | — | `UNDER_REVIEW` |
| Yeni alıcıya ilk transferde büyük tutar | Set, `SADD` yalnızca ilk görüşte 1 döner | `UNDER_REVIEW` |

`FROZEN` ve `UNDER_REVIEW` durumundaki hesaplar para alabiliyor ama **gönderemiyor**. Admin `POST /api/admin/fraud/alerts/{id}/decision` ile `RELEASED` (yanlış alarm, hesabı açar) ya da `CONFIRMED` (dondurur) kararı veriyor. Fraud sistemi durumu yalnızca ağırlaştırabiliyor, bir inceleme kararı dondurulmuş bir hesabı tekrar açamıyor.

Not: Kontrol asenkron yapıldığı için şüpheli işlemin **kendisi** gerçekleşiyor, sonraki işlemler engelleniyor. Senkron ön kontrol (işlemi `PENDING_APPROVAL` durumuna düşürmek) gecikme maliyeti olan bir alternatif.

### 8. EFT için orkestrasyon tabanlı saga

- **Neden saga?** Dış banka ile ortak bir DB transaction'ı (2PC) yok. Her adım kendi yerel ACID TX'i, ters adım ise telafi işlemi (compensation).
- **Neden koreografi değil, orkestrasyon?** Akış (bloke et → gönder → kesinleştir/telafi et) tek bir sınıfta (`EftSagaOrchestrator`) okunuyor. Durum `eft_sagas` tablosunda saklanıyor, izlemesi ve hata ayıklaması kolay.
- **Bloke:** Para ayrı bir "blocked" alanında değil, defter üzerinden `EFT_SUSPENSE` hesabına taşınıyor. Böylece bloke de çift taraflı kayıt kurallarına tabi oluyor ve mutabakata giriyor.
- **Ağ çağrısı TX dışında yapılıyor.** 2 sn'lik çağrı sırasında hiçbir satır kilidi tutulmuyor.
- **Asıl zor durum timeout:** Timeout olduğunda dış bankanın parayı işleyip işlemediği bilinmiyor. Körü körüne telafi edersek para hem gitmiş hem iade edilmiş olur (banka zarar eder). Körü körüne kesinleştirirsek para gitmediği halde müşteriden alınmış olur. Çözüm **cancel-or-confirm**: dış bankaya `cancel` gönderiliyor. Cevap `ACCEPTED` ise transfer zaten yapılmış demektir ve işlem kesinleştiriliyor. `CANCELLED` ise iptal kaydedilmiş demektir, geç gelen orijinal istek reddedilecek ve telafi yapılıyor. Dış banka her referans için "ilk yazan kazanır" kuralını atomik olarak uyguluyor (`ConcurrentHashMap.merge`).
- **Circuit açıksa** istek hiç gönderilmediği için doğrudan telafi yapılabiliyor (belirsizlik yok).
- **Çökme kurtarma:** `recoverStuckSagas()` 10 sn'de bir çalışıp `FUNDS_RESERVED` veya `IN_DOUBT` durumunda 30 sn'den fazla kalmış saga'ları cancel-or-confirm ile sonuca bağlıyor. Adımlar idempotent: terminal durumdaki bir saga'yı tekrar tamamlamak hiçbir şey yapmıyor.
- `chaosRunStaysConsistent` testi, rastgele kabul eden, reddeden ve (işledikten önce ya da sonra) timeout veren bir bankaya karşı 60 EFT çalıştırıyor. Sonunda şunlar doğrulanıyor: tüm saga'lar terminal durumda, **iki bankanın "gerçekleşti" listeleri birebir aynı**, suspense hesabında para kalmamış ve defter dengede.

### 9. Güvenlik

- Stateless JWT (HS256). Rol bilgisi token'da taşınıyor ve her istekte DB'ye gitmeye gerek kalmıyor.
- Müşteri yalnızca kendi hesaplarını görebiliyor. Başkasının hesabına erişim denemesinde **403 değil 404** dönüyor, böylece API hangi hesap ID'lerinin var olduğunu sızdırmıyor.
- `/api/admin/**` yalnızca `ADMIN` rolüne açık (URL kuralı ve `@PreAuthorize` birlikte).

---

## API turu

```bash
# 1) Kayıt ol ve token al
TOKEN=$(curl -s localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"ayse@example.com","password":"password123","fullName":"Ayşe"}' | jq -r .accessToken)
ADMIN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@bank.local","password":"admin12345"}' | jq -r .accessToken)

# 2) Hesap aç
curl -s localhost:8080/api/accounts -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"currency":"TRY"}'
curl -s localhost:8080/api/accounts -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"currency":"USD"}'

# 3) Para yatır (gişe/admin)
curl -s localhost:8080/api/admin/accounts/25/deposits -H "Authorization: Bearer $ADMIN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d '{"amount":10000}'

# 4) Kur teklifi al, 30 sn içinde onayla
QUOTE=$(curl -s localhost:8080/api/fx/quotes -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"fromAccountId":25,"toAccountId":26,"sellAmount":1000}' | jq -r .id)
curl -s -X POST localhost:8080/api/fx/quotes/$QUOTE/accept -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)"

# 5) EFT (dış banka). Sonuç COMPLETED, COMPENSATED veya IN_DOUBT olabilir
curl -s localhost:8080/api/transfers/eft -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' -d '{"fromAccountId":25,"targetIban":"TR330006100519786457841326","amount":50}'

# 6) Defter ve mutabakat
curl -s localhost:8080/api/accounts/25/statement -H "Authorization: Bearer $TOKEN"
curl -s localhost:8080/api/admin/reconciliation -H "Authorization: Bearer $ADMIN"
```

Hesap ID'leri 25'ten başlar (1–24 arası bankanın iç hesapları).

## Testler

```bash
./mvnw test        # Docker gerekli (Testcontainers: Postgres, Redis, Kafka)
```

| Test | Kanıtladığı şey |
|---|---|
| `ConcurrentTransferIT.hundredConcurrentWithdrawalsNeverOverdraw` | 1000 TL'lik hesaptan 100 thread aynı anda 50 TL çekiyor: tam 20 işlem başarılı, 80'i `INSUFFICIENT_FUNDS`, bakiye 0 |
| `ConcurrentTransferIT.opposingTransfersDoNotDeadlock` | A→B ve B→A yönünde 100 eşzamanlı transfer, deadlock yok |
| `OptimisticLockingIT` | Aynı garantiler `@Version` + retry ile |
| `IdempotencyIT` | Aynı anahtarla 20 eşzamanlı istek tek işlem üretiyor; farklı gövde 422; anahtarlar müşteri bazında |
| `FxQuoteIT` | Teklif kuru korunuyor; teklif tek kullanımlık; kripto için 8 hane |
| `EftSagaIT` | Kabul, red, iki tür timeout, IN_DOUBT + recovery, replay, 60 EFT'lik kaos testi |
| `FraudDetectionIT` | Transfer → outbox → Kafka → consumer → Redis → hesap donduruluyor (uçtan uca) |
| `SecurityIT` | Başkasının hesabına 404, token yoksa 401, müşteri admin uçlarına 403 |

Her test sonunda `assertLedgerConsistent()` çağrılıyor: cache'lenmiş bakiyeler defterle eşleşiyor ve defter sıfıra kapanıyor.

## Yapılandırma

| Değişken | Varsayılan | |
|---|---|---|
| `LOCKING_MODE` | `PESSIMISTIC` | `OPTIMISTIC` ile değiştirilebilir |
| `FX_PROVIDER` | `live` | `static` = internet olmadan sabit kurlar |
| `JWT_SECRET` | dev anahtarı | **Production'da mutlaka değiştirilmeli** (Base64, ≥256 bit) |
| `TIMEOUT_RATE` / `REJECT_RATE` | `0.2` / `0.1` | Dış bankanın hata oranları |

Faydalı uçlar: `/swagger-ui.html`, `/actuator/health`, `/actuator/circuitbreakers` (admin), `/api/admin/outbox`.
