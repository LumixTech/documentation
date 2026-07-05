---
title: ClamAV ile Yüklenen Dosyaların Virüs Taraması
description: ClamAV nedir, Kafka event-driven scan orchestration akışı, karantina, alert, false-positive yönetimi.
sidebar_position: 5
---

## Bu sayfa ne anlatıyor?

Lumix'e yüklenen her dosyanın **kullanılabilir olmadan önce virüs taramasından geçmesini** sağlayan ClamAV entegrasyonunu, **Kafka event-driven scan orchestration**'ı, **karantina** prosedürünü, **alert mekanizmasını** ve **false-positive** durumlarını yönetmeyi anlatır. Storage layer'ın güvenlik kontrolü buradan geçer.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Bir **kargo dağıtım merkezi**nde paketler gelir, etiketlenir, sonra **röntgen cihazından** geçer. Şüpheli paket çıkarsa **karantina** odasına alınır; alıcıya gönderilmez; güvenlik birimine alarm gider. Lumix'te aynı: dosya RustFS'e yüklenir (paket gelir), metadata kaydedilir (etiket), sonra ClamAV scan'den geçer (röntgen). Sonuç "clean" çıkarsa kullanıma açılır; "infected" çıkarsa karantinaya alınır + alert.

### 1.2. ClamAV nedir?

ClamAV, **Cisco Talos** tarafından sürdürülen, **GPL** lisanslı açık kaynak antivirus engine'dir. 2002'den beri aktif. Özellikleri:

- **Signature-based** detection (signature DB güncel tutulur)
- **Heuristic** detection (basit davranış analizi)
- Mail server, dosya server, gateway senaryoları için yaygın
- **clamd** daemon process: TCP/Unix socket üzerinden tarama isteği alır
- **freshclam**: signature DB'sini otomatik günceller
- **clamscan / clamdscan**: CLI scan client'ları

### 1.3. Neden ClamAV?

- **Ücretsiz** (GPL) — paid antivirus alternatiflerinin maliyeti olmaz
- **Self-hosted** — bulut antivirus servisine dosya göndermeye gerek yok (KVKK uyumlu)
- **API'lı** (clamd socket) — service-style entegrasyon kolay
- **Cisco Talos** tarafından sürekli güncellenen signature DB
- **Olgun** — 20+ yıl saha kullanımı

## 2. Hangi problemi çözüyor?

### 2.1. Açık kapı

Storage'a yüklenen dosyalar otomatik antivirus kontrolü yoksa:
- Bir öğretmen mesaj eki olarak virüslü .exe yükler → öğrenci indirir → bilgisayarı patlar
- Trojan içeren PDF makro → veli açar → cihaz infect olur
- Malware infrastructure'a sızar
- Yasal sorumluluk (servis sağlayıcı bilerek/bilmeden dağıttığı için)

### 2.2. Compliance gereksinimi

- KVKK veri sorumlusu yükümlülükleri içinde **veri güvenliği** var
- ISO 27001 control set'inde malware protection zorunlu
- Müşterinin denetiminde "yüklenen dosyalar taranıyor mu?" sorusu çıkar

### 2.3. Trust marker

"Yüklediğin dosyalar otomatik taranır" çoğu müşteri için **güven faktörü**dür. Görünür bir feature.

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Event-driven scan flow

```text
file-service                Kafka                  scan-orchestrator        ClamAV (clamd)
     │                        │                          │                       │
     │ Upload confirmed       │                          │                       │
     │  → publish             │                          │                       │
     ├───────────────────────►│                          │                       │
     │   topic: file.upload.completed.v1                 │                       │
     │                        ├─────────────────────────►│                       │
     │                        │                          │                       │
     │                        │       [1] HEAD object metadata                   │
     │                        │       [2] Download to stream (or pre-signed GET) │
     │                        │       [3] INSTREAM scan request                  │
     │                        │                          ├──────────────────────►│
     │                        │                          │  scan result (OK or FOUND virus_name)
     │                        │                          │◄──────────────────────┤
     │                        │       [4] Decide:                                 │
     │                        │           - clean → publish file.scan.clean.v1   │
     │                        │           - infected → quarantine + publish      │
     │                        │             file.scan.infected.v1                │
     │                        │◄─────────────────────────┤                       │
     │                        │                          │                       │
     │ Consume scan result    │                          │                       │
     │◄───────────────────────┤                          │                       │
     │ Update file_objects.status                        │                       │
     │ Update scan_results                               │                       │
```

### 3.2. Status state machine

```text
PENDING_UPLOAD
     │ confirm
     ▼
UPLOADED ──────── scan event
                       │
                       ▼
                  SCANNING
                       │
              ┌────────┴────────┐
              │                 │
              ▼                 ▼
           CLEAN            INFECTED
              │                 │
              │            ┌────┴────┐
              │            │         │
        usable by app  QUARANTINED  IGNORED
                       (storage      (admin
                        deleted)    override)
```

### 3.3. ClamAV scan modları

- **clamscan**: standalone, her dosya için process spawn → yavaş
- **clamdscan**: clamd daemon ile, dosya yolu gönderir → daemon okur
- **clamd INSTREAM**: clamd socket'ine TCP üzerinden stream gönderir → daemon read eder, sonuç döner. **Network-friendly**, ideal pod-to-pod

Lumix scan-orchestrator **INSTREAM** kullanır (clamd ayrı pod, scan-orchestrator dosyayı RustFS'ten stream eder ve clamd'ye geçirir).

### 3.4. Signature DB güncelleme

`freshclam` daemon her saat (default) signature DB'sini Cisco Talos'tan çeker:

```text
/var/lib/clamav/
  ├── main.cvd    (ana signature)
  ├── daily.cvd   (günlük güncelleme)
  └── bytecode.cvd (heuristic)
```

Production'da freshclam pod'u ayrı; sonra `clamd` reload (signal SIGHUP). Lumix'te freshclam **sidecar container** olarak clamd pod'unda çalışır.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Component'ler

| Component | Sorumluluk | Deployment |
|---|---|---|
| **clamd** | Antivirus engine daemon, INSTREAM scan | Deployment (1-2 replica), sidecar freshclam |
| **scan-orchestrator** | Kafka consumer, file fetch, clamd çağrısı, sonuç publish | Deployment (HPA ile scale) |
| **file-service** | Scan sonucunu consume, status update | Mevcut microservice |

scan-orchestrator ayrı bir module/microservice mi yoksa file-service'in bir consumer'ı mı? **Lumix kararı**: file-service içinde bir `ScanOrchestrationConsumer` component. Ayrı servis overhead'i tek consumer için fazla.

### 4.2. Kafka topic'leri

| Topic | Producer | Consumer |
|---|---|---|
| `file.upload.completed.v1` | file-service | scan-orchestrator (file-service içi) |
| `file.scan.clean.v1` | scan-orchestrator | file-service (status update), audit |
| `file.scan.infected.v1` | scan-orchestrator | file-service (quarantine), alert, audit |
| `file.scan.failed.v1` | scan-orchestrator | retry consumer, alert |

### 4.3. Karantina prosedürü

Infected çıkan dosya için:

1. RustFS'te object **delete** edilir (gerçek bytes silinir, accidental download riski biter)
2. `file_objects` row'unda `status='INFECTED'`, `quarantined_at=NOW()`, `virus_name='<name>'`
3. `scan_results` tablosuna detay yazılır
4. Owner user'a **email/push notification**: "Yüklediğiniz dosya virüs içerdiği için bloklandı"
5. Tenant admin'e **alert**
6. Audit log: `file.quarantined`
7. Domain referansı (örn. mesaj eki) için **placeholder** kalır ("Bu dosya güvenlik nedeniyle kaldırıldı")

### 4.4. Retry ve DLQ

ClamAV downtime durumunda scan başarısız olursa:
- Retry topic (`file.scan.completed.retry`) → exponential backoff (1m, 5m, 30m)
- 3 başarısız retry sonrası DLQ
- Bu sürede dosya status `SCAN_PENDING_RETRY`, kullanıma açılmaz

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

| Alternatif | Neden elendi |
|---|---|
| **AWS GuardDuty + S3 malware scan** | Cloud-specific, Lumix self-hosted SaaS |
| **VirusTotal API** | Paid (quota'lı), dosyayı upload eder (KVKK riski) |
| **Bitdefender / Kaspersky engine** | Lisans maliyeti, embedded SDK kullanımı kompleks |
| **Sophos AV** | Commercial, self-host expensive |
| **MalwareBazaar / OPSWAT** | Cloud servisleri, KVKK uyumsuz |

### 5.2. ClamAV'in dezavantajları (ve nasıl yönetiyoruz)

- **Detection rate enterprise AV'lere göre düşük**: Signature DB tamamen ücretsiz olduğu için. Lumix bunu **defense in depth** ile dengeler: ClamAV + content-type validation + magic byte check + file size limit + sandbox preview (ileride).
- **Memory hungry**: clamd ~1-2 GB RAM kullanır. Production'da rezerv ayır.
- **Async scan**: Upload tamamlanır ama dosya **scan bitene kadar kullanılamaz**. Genelde 1-5 saniye. UI'da "Tarama bekliyor" durumu gösterilir.

### 5.3. Trade-off

- **Defense rate < commercial AV**: Kabul, defense in depth ile telafi.
- **Latency** (1-5 sec): UI'da scanning durumu göster.
- **False positive olasılığı**: Düşük ama mümkün. Admin override mekanizması var.

### 5.4. Ne değişirse kararı tekrar gözden geçiririz?

- Daha güçlü AV ihtiyacı doğarsa adapter pattern ile **CommercialAvAdapter** eklenir, ClamAV default kalır.
- Sandbox malware analysis (cuckoo, joe sandbox) ileride entegre edilebilir.

## 6. Pratik örnek

### 6.1. clamd Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: clamav
  namespace: security
spec:
  replicas: 2
  selector:
    matchLabels:
      app: clamav
  template:
    metadata:
      labels:
        app: clamav
    spec:
      containers:
      - name: clamd
        image: clamav/clamav:1.4.1
        ports:
        - containerPort: 3310
          name: clamd
        env:
        - name: CLAMAV_NO_FRESHCLAMD
          value: "false"
        - name: CLAMAV_NO_CLAMD
          value: "false"
        resources:
          requests:
            memory: "2Gi"
            cpu: "500m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
        livenessProbe:
          tcpSocket:
            port: 3310
          initialDelaySeconds: 60
          periodSeconds: 30
        readinessProbe:
          exec:
            command:
            - clamdscan
            - --ping
            - "1"
          initialDelaySeconds: 90
          periodSeconds: 15
        volumeMounts:
        - name: signatures
          mountPath: /var/lib/clamav
      volumes:
      - name: signatures
        emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: clamav
  namespace: security
spec:
  selector:
    app: clamav
  ports:
  - port: 3310
    targetPort: 3310
    name: clamd
```

### 6.2. Java ClamAV client (INSTREAM)

```java
package com.lumix.file.adapter.scan;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;

@Component
public class ClamAvScanClient implements VirusScanPort {

    private static final int CHUNK_SIZE = 8192;
    private static final int MAX_FILE_SIZE = 500 * 1024 * 1024; // 500 MB

    private final String host;
    private final int port;
    private final int timeoutMs;

    public ClamAvScanClient(ClamAvProperties props) {
        this.host = props.getHost();
        this.port = props.getPort();
        this.timeoutMs = (int) props.getTimeout().toMillis();
    }

    @Override
    public ScanResult scan(InputStream payload) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            try (OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream()) {

                // Initiate INSTREAM
                out.write("zINSTREAM\0".getBytes());
                out.flush();

                // Send chunks: 4-byte size prefix + chunk bytes
                byte[] buffer = new byte[CHUNK_SIZE];
                int read;
                long total = 0;
                while ((read = payload.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_FILE_SIZE) {
                        throw new ScanException("File exceeds max scan size");
                    }
                    ByteBuffer sizeBuf = ByteBuffer.allocate(4).putInt(read);
                    out.write(sizeBuf.array());
                    out.write(buffer, 0, read);
                }
                // Termination: 4-byte zero
                out.write(ByteBuffer.allocate(4).putInt(0).array());
                out.flush();

                // Read response
                ByteArrayOutputStream responseBuf = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != -1 && b != 0) {
                    responseBuf.write(b);
                }
                String response = responseBuf.toString("UTF-8").trim();

                return parseResponse(response);
            }
        } catch (IOException e) {
            throw new ScanException("ClamAV communication failed", e);
        }
    }

    private ScanResult parseResponse(String response) {
        // Examples:
        // "stream: OK"
        // "stream: Eicar-Test-Signature FOUND"
        // "stream: <error> ERROR"
        if (response.endsWith("OK")) {
            return ScanResult.clean();
        }
        if (response.endsWith("FOUND")) {
            String virusName = response.replaceFirst("^stream: ", "")
                                       .replaceFirst(" FOUND$", "");
            return ScanResult.infected(virusName);
        }
        return ScanResult.failed(response);
    }
}
```

### 6.3. Scan orchestrator consumer

```java
package com.lumix.file.application;

import org.springframework.kafka.annotation.KafkaListener;

@Component
@RequiredArgsConstructor
public class FileScanOrchestrator {

    private final ObjectStoragePort storage;
    private final VirusScanPort scanner;
    private final FileObjectRepository repository;
    private final ScanResultRepository scanResultRepository;
    private final FileEventPublisher events;
    private final AuditLogger audit;

    @KafkaListener(
            topics = "file.upload.completed.v1",
            groupId = "file-service-scan-orchestrator",
            containerFactory = "protoKafkaListenerContainerFactory"
    )
    @Transactional
    public void handleUploadCompleted(FileUploadCompletedEvent event) {
        UUID fileId = UUID.fromString(event.getFileId());

        FileObject file = repository.findById(fileId)
                .orElseThrow(() -> new FileNotFoundException(fileId));

        if (file.status() != FileStatus.UPLOADED) {
            return; // already scanned or in other state
        }
        file.markScanning();
        repository.save(file);

        ScanResult result;
        try (InputStream stream = storage.openObjectStream(file.bucket(), file.objectKey())) {
            result = scanner.scan(stream);
        } catch (Exception e) {
            result = ScanResult.failed(e.getMessage());
        }

        scanResultRepository.save(ScanResultEntity.from(fileId, result));

        if (result.isClean()) {
            file.markClean();
            events.publishScanClean(file);
            audit.log("file.scan.clean", null, fileId, null);
        } else if (result.isInfected()) {
            file.markInfected(result.virusName());
            storage.deleteObject(file.bucket(), file.objectKey());
            events.publishScanInfected(file, result.virusName());
            audit.log("file.scan.infected", null, fileId, result.virusName());
        } else {
            file.markScanFailed(result.errorMessage());
            events.publishScanFailed(file, result.errorMessage());
        }

        repository.save(file);
    }
}
```

### 6.4. Alert notification

`file.scan.infected.v1` topic'ini consume eden notification-service:

```java
@KafkaListener(topics = "file.scan.infected.v1")
public void onInfected(FileScanInfectedEvent event) {
    UUID ownerId = UUID.fromString(event.getOwnerUserId());
    UUID tenantId = UUID.fromString(event.getTenantId());

    notificationUseCase.send(ownerId, "FILE_QUARANTINED_OWNER", Map.of(
            "file_name", event.getOriginalFileName(),
            "virus_name", event.getVirusName()));

    List<UUID> adminUserIds = adminLookup.findTenantAdmins(tenantId);
    for (UUID admin : adminUserIds) {
        notificationUseCase.send(admin, "FILE_QUARANTINED_ADMIN", Map.of(
                "file_name", event.getOriginalFileName(),
                "virus_name", event.getVirusName(),
                "owner_id", ownerId.toString()));
    }
}
```

### 6.5. Eicar test (manual sanity check)

Production'da test için **Eicar standard test file** kullanılır (gerçek virüs değil, AV tools tarafından tanınan test signature):

```bash
# Create EICAR
cat > /tmp/eicar.txt <<'EOF'
X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*
EOF

# Send to clamd
echo "INSTREAM" | nc -U /tmp/clamd.socket
# veya production: upload eicar.txt → scan result should be 'Win.Test.EICAR_HDB-1 FOUND'
```

## 7. Dikkat edilecek tuzaklar

- **freshclam unutma**. Stale signature DB = yeni malware tespit edemez. Sidecar veya cron ile düzenli güncelle.
- **clamd memory limit'i ayarla**. Default 1-2 GB minimum; az verirse process kill olur.
- **Scan timeout büyük dosyalarda yetmez**. INSTREAM stream sürer; timeout 60-120 saniye olabilir.
- **Network bandwidth maliyeti**. Her dosya scan = ekstra RustFS → scan-orchestrator → clamd transfer. Aynı pod'a ClamAV koymayı düşün veya bandwidth'i ölç.
- **Senkron scan ANTI-PATTERN**. Upload sırasında scan yapma; UI bloklar. Async scan + status state machine kullan.
- **Infected dosyayı silmemek**. RustFS'te durmaya devam ederse erişim mümkün. Quarantine = storage'dan sil.
- **Audit log atlama**. Her scan sonucu (özellikle infected) audit log'a düşmeli.
- **False positive yönetimi**. Bir admin override path'i olsun ("Bu dosya temiz, ben onaylıyorum" — yetkili kullanıcı + ekstra audit).
- **Test environment'ta gerçek virüs DOSYASI kullanma**. Eicar yeterli; gerçek malware = legal/safety risk.
- **clamd healthcheck**: pingFailure'da scan-orchestrator dosyayı `SCAN_PENDING_RETRY` yapsın, sessizce başarılı saymaSIN.
- **Dosya türü vs scan**. ClamAV PDF makro, archive içeriği, OLE document scan edebilir; config (`ScanArchive`, `ScanOLE2`, `ScanPDF`) açık olsun.

## 8. Diğer konularla ilişkisi

- [Pre-signed URL Akışı](./03-presigned-urls.md) — confirm sonrası scan event publish edilir
- [Lifecycle Policy](./04-lifecycle-policies.md) — infected quarantine + retention
- [Kafka Topic Design](../event-driven-architecture) — file.scan.* topic'leri
- [Notification](../notification) — infected alert push/email
- [Audit Log Design](../security-compliance/audit-log-design) — file.scan event'leri audit
- [Domain Servisleri](../01-tenancy-and-domain-model/02-domain-services-overview.md) — file-service detay

## 9. Daha derine inmek için

- ClamAV — [Official Documentation](https://docs.clamav.net/)
- ClamAV INSTREAM protocol — [clamd manpage](https://docs.clamav.net/manual/Usage/Configuration.html#clamd)
- Eicar test file — [eicar.org](https://www.eicar.org/download-anti-malware-testfile/)
- Cisco Talos signature updates — [ClamAV blog](https://blog.clamav.net/)
- Araştırma keyword'leri: `clamav instream scan java`, `clamd kubernetes deployment`, `file upload virus scan event driven`, `freshclam automation`

## 10. Sözlük

- **ClamAV** — Açık kaynak GPL antivirus engine (Cisco Talos).
- **clamd** — ClamAV daemon process; socket üzerinden scan isteği alır.
- **freshclam** — Signature DB güncelleme daemon'u.
- **INSTREAM** — clamd protokolü; stream halinde dosyayı scan eder.
- **Signature DB** — Bilinen malware imzalarının veritabanı.
- **Quarantine** — Infected dosyanın izole edilmesi (Lumix'te storage'dan silinme).
- **False positive** — Temiz dosyanın hatalı olarak infected işaretlenmesi.
- **Eicar** — Antivirus testing için standard sahte signature.
- **Defense in depth** — Tek koruma katmanına güvenmeyip çoklu katman uygulamak.
- **Scan orchestrator** — Upload tamamlanma event'ini consume edip scan tetikleyen component.
