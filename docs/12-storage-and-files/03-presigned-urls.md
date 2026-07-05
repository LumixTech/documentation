---
title: Pre-Signed URL ile Direct Upload/Download
description: Pre-signed URL nedir, file-service akışı, multipart upload, expiry/content-type/size enforcement ve security pratikleri.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Lumix'te dosya yüklemenin **neden app server üzerinden geçmediğini**, **pre-signed URL'in ne olduğunu**, **file-service'in upload/download akışını** (request → metadata create → signed PUT URL → direct client upload → confirm), **multipart upload** mekaniğini, **expiry / content-type / size** zorlamalarını ve **güvenlik kuralları**nı anlatır. RustFS'e (veya herhangi S3-compatible storage'a) direkt güvenli erişim mekaniği burası.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Bir **özel galeri**de pahalı tabloların olduğu kasayı düşün. Galeri sahibi (file-service), kasanın anahtarını (root credential) misafire vermez. Yerine **tek seferlik, süresi sınırlı bir kart** üretir: "Bu kart Salı 14:00-14:10 arası 3 numaralı kasayı **sadece açıp belirli boyuttan büyük olmayan** bir tablo koymaya yarar". Misafir (client) bu kartla doğrudan kasaya gidip işini halleder; sahip oradan oraya tabloyu eliyle taşımak zorunda kalmaz.

Pre-signed URL tam olarak budur: bir operasyonu (PUT veya GET), belirli bir object key'ini, belirli bir süre içinde, belirli content-type ve size sınırı altında yapmaya yarayan **imzalı URL**.

### 1.2. Teknik tanım

Pre-signed URL, AWS Signature V4 (Sigv4) algoritmasıyla **belirli bir S3 operasyonunu yetkilendiren** kısa ömürlü URL'dir. URL'in içinde:

- Hedef bucket + object key
- HTTP method (PUT, GET, vs.)
- Expiry timestamp
- AccessKeyId (sadece public bilgi)
- Signature (HMAC-SHA256, secret key ile)
- Opsiyonel: Content-Type, Content-Length sınırı, custom header'lar

bulunur. Storage server bu signature'ı doğrular; süresi geçmiş veya değiştirilmiş URL reddedilir.

### 1.3. Üç pattern karşılaştırması

| Pattern | Akış | Avantaj | Dezavantaj |
|---|---|---|---|
| **Proxy upload** | Client → App → Storage | App tam kontrol | App bandwidth bottleneck, memory pressure |
| **Public bucket** | Client → Storage (direct) | En basit | Güvenlik felaketi (auth yok) |
| **Pre-signed URL** | Client → App (URL al) → Client → Storage | Direct transfer + auth | Biraz daha karmaşık akış |

Lumix tek pattern: **pre-signed URL**.

## 2. Hangi problemi çözüyor?

### 2.1. App server bottleneck

Bir öğretmen 200 MB'lık video yüklerse ve proxy upload kullanırsak:
- App server'ın memory'sinde stream buffer şişer.
- Bağlantı 30 saniye+ tutulur; pool'daki connection azalır.
- Aynı anda 10 kullanıcı yüklerse pod CPU/memory limit'e patlar.
- Network bandwidth iki kat tüketilir (client → app → storage).

### 2.2. Güvenlik problemi (public bucket)

Bucket'ı public yapmak en kötü senaryo:
- Anonymous okuma + yazma trafiği gelir.
- Tenant izolasyonu kaybolur (Hüseyin'in dosyasını Ali okur).
- DDoS riski (anonim upload spam).
- Audit kaybolur (kim ne yükledi belirsiz).

### 2.3. Pre-signed URL hep birden çözüyor

- App sadece **yetki dağıtır** (URL üretir), bytes onun üzerinden geçmez.
- Tenant, owner, content-type, size her URL üretimde kontrol edilir.
- URL kısa ömürlü (5-15 dakika); leak olsa bile ömrü dolar.
- Audit log üretim noktasında atılır.

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Upload akışı — sequence

```text
Client (Web/Mobile)         file-service               RustFS
       │                         │                       │
       │ POST /api/v1/files/upload-requests              │
       │ { tenant_id, content_type, size, domain }       │
       ├────────────────────────►│                       │
       │                         │                       │
       │              [1] AuthZ check (JWT + scope)      │
       │              [2] Validate content_type, size    │
       │              [3] Generate file_id (UUID v7)     │
       │              [4] Build object_key               │
       │              [5] INSERT file_objects (pending)  │
       │              [6] Generate Sigv4 presigned PUT   │
       │                                                 │
       │ 201 Created                                     │
       │ { file_id, upload_url, expires_in: 600 }        │
       │◄────────────────────────┤                       │
       │                                                 │
       │ PUT <upload_url>                                │
       │ Header: Content-Type, Content-Length            │
       │ Body: binary bytes                              │
       ├─────────────────────────────────────────────────►
       │                          200 OK + ETag          │
       │◄─────────────────────────────────────────────────
       │                                                 │
       │ POST /api/v1/files/{file_id}/confirm-upload     │
       ├────────────────────────►│                       │
       │              [7] HEAD object on RustFS          │
       │                         ├──────────────────────►│
       │                         │◄──────────────────────┤
       │              [8] Verify size + checksum         │
       │              [9] UPDATE file_objects.status     │
       │                  = 'uploaded'                   │
       │              [10] Publish Kafka:                │
       │                  file.upload.completed.v1       │
       │ 200 OK                                          │
       │◄────────────────────────┤                       │
```

### 3.2. Download akışı

```text
Client                  file-service               RustFS
   │ GET /files/{id}/download    │                  │
   ├────────────────────────────►│                  │
   │                             │                  │
   │  [1] AuthZ + ownership      │                  │
   │  [2] Check status=clean     │                  │
   │  [3] Generate Sigv4 GET     │                  │
   │  [4] Audit log              │                  │
   │                             │                  │
   │ 200 { download_url, expires_in: 300 }          │
   │◄────────────────────────────┤                  │
   │                             │                  │
   │ GET <download_url>          │                  │
   ├─────────────────────────────────────────────────►
   │                                stream bytes    │
   │◄─────────────────────────────────────────────────
```

### 3.3. Multipart upload

Büyük dosyalar (>5 MB pratik, >100 MB önerilen) **multipart** yüklenir. Tek parça yerine 5-50 part'a bölünür; her part paralel yüklenir; başarısız part retry edilir.

```text
1. Client → file-service: "100 MB upload başlatacağım"
2. file-service → RustFS: CreateMultipartUpload → upload_id
3. file-service: Her part için presigned PUT URL üretir (10 part = 10 URL)
4. Client part'ları paralel yükler (örn. 5 paralel stream)
5. Her part RustFS'ten ETag alır
6. Client → file-service: "Tüm partlar bitti, ETag listesi şu"
7. file-service → RustFS: CompleteMultipartUpload (ETag listesi ile)
8. RustFS part'ları birleştirir, object oluşur
9. file-service confirm akışını çalıştırır
```

Part boyutu Lumix'te **8 MB** (RustFS minimum 5 MB + ağ verimli boyut).

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. file-service endpoint'leri

| Endpoint | Amaç |
|---|---|
| `POST /api/v1/files/upload-requests` | Tek-part upload için URL üret |
| `POST /api/v1/files/multipart/start` | Multipart upload başlat |
| `POST /api/v1/files/multipart/{id}/parts` | Part URL'i üret |
| `POST /api/v1/files/multipart/{id}/complete` | Multipart'ı tamamla |
| `POST /api/v1/files/{id}/confirm-upload` | Upload tamamlandığını bildir |
| `GET /api/v1/files/{id}/download` | Download URL üret |

### 4.2. Validation kuralları

| Kural | Limit | Yer |
|---|---|---|
| Tek dosya max size | 500 MB | application validation |
| Multipart min size | 5 MB | RustFS sınırı |
| Multipart part size | 8 MB | Lumix standardı |
| Upload URL expiry | 10 dakika | Sigv4 imzasında |
| Download URL expiry | 5 dakika | Sigv4 imzasında |
| İzinli content-type | Domain başına allowlist | DB config |
| Toplam tenant quota | License config | identity-service'ten |

### 4.3. Object key formatı

```text
tenant/{tenant_id}/file/{yyyy}/{mm}/{file_id}
```

Path-style URL örnek:
```text
https://rustfs.lumix.internal:9000/lumix-files-private/tenant/abc-123/file/2025/11/01HXYZ
?X-Amz-Algorithm=AWS4-HMAC-SHA256
&X-Amz-Credential=...
&X-Amz-Date=20251127T120000Z
&X-Amz-Expires=600
&X-Amz-SignedHeaders=content-type%3Bcontent-length%3Bhost
&X-Amz-Signature=...
```

### 4.4. Signed headers ile zorlamak

Sadece URL'i imzalamak yetmez — **Content-Type ve Content-Length** de signed headers'a katılmalı. Aksi halde client farklı content-type ile yükleyebilir:

```java
PutObjectRequest putRequest = PutObjectRequest.builder()
        .bucket(bucket)
        .key(objectKey)
        .contentType(request.contentType())     // SIGN'a girer
        .contentLength(request.sizeBytes())     // SIGN'a girer
        .build();

PutObjectPresignRequest presign = PutObjectPresignRequest.builder()
        .signatureDuration(Duration.ofMinutes(10))
        .putObjectRequest(putRequest)
        .build();
```

Client farklı content-type veya size ile gönderirse RustFS imza uyumsuzluğu nedeniyle reddeder.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

| Alternatif | Neden elendi |
|---|---|
| **App proxy upload** | Bandwidth bottleneck, memory pressure |
| **CDN signed cookie** | Mostly CDN-specific, multi-step flow uyumsuz |
| **Tus.io protocol** | İlginç (resumable upload) ama S3-native değil, ekstra server |
| **Direct public upload + post-upload check** | Güvenlik riski (auth yok), tenant izolasyonu zor |

### 5.2. Kabul ettiğimiz trade-off'lar

- **3-step flow** (request → upload → confirm) basit 1-step'ten uzun. Karşılığında app tam yetki kontrolünde kalır.
- **URL leak riski**: Kısa expiry + signed headers + HTTPS ile minimize edilir. URL header değil URL parametrelerinde olduğu için log'a düşme riski var — log filtering zorunlu.
- **Multipart complexity**: Büyük dosya için zorunlu. Client SDK'sı ile kapsüllenmeli.

### 5.3. Ne değişirse kararı tekrar gözden geçiririz?

- Web push notification veya offline upload ihtiyacı doğarsa **resumable upload** (tus.io tarzı) eklenebilir, hâlâ presigned URL temeli üzerinde.
- CDN katmanı eklenirse download URL'leri CDN signed URL'e dönebilir.

## 6. Pratik örnek

### 6.1. file-service — Upload request controller

```java
package com.lumix.file.adapter.rest;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileUploadUseCase uploadUseCase;

    @PostMapping("/upload-requests")
    @PreAuthorize("hasAuthority('file:upload')")
    public ResponseEntity<UploadResponse> requestUpload(
            @Valid @RequestBody UploadRequestDto dto,
            @AuthenticationPrincipal UserPrincipal principal) {

        UploadRequestCommand command = new UploadRequestCommand(
                principal.userId(),
                principal.tenantId(),
                dto.domainType(),
                dto.domainId(),
                dto.fileName(),
                dto.contentType(),
                dto.sizeBytes()
        );

        UploadResponse response = uploadUseCase.requestUpload(command);
        return ResponseEntity.status(201).body(response);
    }

    @PostMapping("/{fileId}/confirm-upload")
    @PreAuthorize("hasAuthority('file:upload')")
    public ResponseEntity<FileMetadataDto> confirmUpload(
            @PathVariable UUID fileId,
            @AuthenticationPrincipal UserPrincipal principal) {

        FileMetadataDto file = uploadUseCase.confirmUpload(fileId, principal.userId());
        return ResponseEntity.ok(file);
    }
}
```

### 6.2. Use case implementation

```java
package com.lumix.file.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileUploadUseCase {

    private static final long MAX_SIZE_BYTES = 500L * 1024 * 1024; // 500 MB

    private final ObjectStoragePort storagePort;
    private final FileObjectRepository fileRepository;
    private final FileEventPublisher eventPublisher;
    private final ContentTypeAllowlist contentTypeAllowlist;
    private final ObjectKeyBuilder keyBuilder;

    @Transactional
    public UploadResponse requestUpload(UploadRequestCommand cmd) {
        validate(cmd);

        UUID fileId = UuidV7Generator.generate();
        String objectKey = keyBuilder.build(cmd.tenantId(), fileId, cmd.fileName());

        FileObject fileObject = FileObject.create(
                fileId,
                cmd.tenantId(),
                cmd.userId(),
                cmd.domainType(),
                cmd.domainId(),
                StorageBucket.PRIVATE,
                objectKey,
                cmd.contentType(),
                cmd.sizeBytes(),
                FileStatus.PENDING_UPLOAD
        );
        fileRepository.save(fileObject);

        PresignedUploadUrl url = storagePort.createUploadUrl(new UploadRequest(
                StorageBucket.PRIVATE.bucketName(),
                objectKey,
                cmd.contentType(),
                cmd.sizeBytes(),
                Duration.ofMinutes(10)
        ));

        eventPublisher.publishUploadRequested(fileObject);

        return new UploadResponse(
                fileId,
                url.url(),
                url.expiresInSeconds(),
                objectKey
        );
    }

    @Transactional
    public FileMetadataDto confirmUpload(UUID fileId, UUID userId) {
        FileObject file = fileRepository.findById(fileId)
                .orElseThrow(() -> new FileNotFoundException(fileId));

        if (!file.ownerUserId().equals(userId)) {
            throw new ForbiddenException("Owner mismatch");
        }
        if (file.status() != FileStatus.PENDING_UPLOAD) {
            throw new IllegalStateException("File already confirmed: " + file.status());
        }

        ObjectMetadata head = storagePort.headObject(file.bucket(), file.objectKey());
        if (head.sizeBytes() != file.sizeBytes()) {
            throw new UploadValidationException(
                    "Size mismatch: expected=" + file.sizeBytes() + ", actual=" + head.sizeBytes());
        }

        file.markUploaded(head.eTag(), head.versionId());
        fileRepository.save(file);

        eventPublisher.publishUploadCompleted(file);
        return FileMetadataDto.from(file);
    }

    private void validate(UploadRequestCommand cmd) {
        if (cmd.sizeBytes() > MAX_SIZE_BYTES) {
            throw new UploadValidationException("File too large: " + cmd.sizeBytes());
        }
        if (!contentTypeAllowlist.isAllowed(cmd.domainType(), cmd.contentType())) {
            throw new UploadValidationException("Content type not allowed: " + cmd.contentType());
        }
    }
}
```

### 6.3. Multipart upload controller

```java
@PostMapping("/multipart/start")
@PreAuthorize("hasAuthority('file:upload')")
public ResponseEntity<MultipartStartResponse> startMultipart(
        @Valid @RequestBody MultipartStartDto dto,
        @AuthenticationPrincipal UserPrincipal principal) {

    int partCount = (int) Math.ceil((double) dto.sizeBytes() / (8L * 1024 * 1024));
    if (partCount > 1000) {
        throw new UploadValidationException("Too many parts: " + partCount);
    }

    MultipartSession session = uploadUseCase.startMultipart(
            principal.tenantId(), principal.userId(),
            dto.fileName(), dto.contentType(), dto.sizeBytes(), partCount);

    return ResponseEntity.ok(new MultipartStartResponse(
            session.fileId(),
            session.uploadId(),
            session.partUrls()
    ));
}
```

### 6.4. Frontend (React) upload örneği

```typescript
async function uploadFile(file: File, domain: string) {
  // 1. Request upload URL
  const initRes = await fetch('/api/v1/files/upload-requests', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({
      fileName: file.name,
      contentType: file.type,
      sizeBytes: file.size,
      domainType: domain,
    }),
  });
  const { file_id, upload_url } = await initRes.json();

  // 2. PUT directly to RustFS
  const uploadRes = await fetch(upload_url, {
    method: 'PUT',
    headers: { 'Content-Type': file.type },
    body: file,
  });
  if (!uploadRes.ok) throw new Error('Upload failed');

  // 3. Confirm to app
  await fetch(`/api/v1/files/${file_id}/confirm-upload`, {
    method: 'POST',
    credentials: 'include',
  });

  return file_id;
}
```

## 7. Dikkat edilecek tuzaklar

- **Signed headers eksik bırakmak**. Content-Type ve Content-Length signature'a katılmalı. Aksi halde client farklı tip yükleyebilir.
- **Uzun expiry kullanma**. 10 dakikadan uzun upload URL = leak penceresi.
- **URL'i log'a yazma**. Sigv4 query string'inde signature var — secret değil ama leak edilirse expiry'ye kadar abuse riski.
- **Confirm endpoint'ini atlama**. Confirm yapmadan dosyayı kullanma; HEAD ile doğrulamadan business state'i `uploaded` yapma.
- **Public bucket presigned URL karışıklığı**. Public bucket'ta presigned URL gereksiz; gizli olanlar private kalmalı.
- **`X-Amz-Meta-*` ile sensitive data koyma**. Custom metadata storage'da plaintext durur.
- **CORS unutma**. Browser'dan direct upload için RustFS bucket'ında CORS policy ayarlı olmalı.
- **Path-style vs virtual-hosted style URL** karıştırma. RustFS path-style; SDK config'inde tutarlı olmalı.
- **HEAD doğrulamasını skip etme**. Confirm sırasında size + checksum verify zorunlu.
- **Multipart upload abort etmeme**. Yarım kalan multipart'lar disk yer; lifecycle rule ile temizle.
- **PII'yi file name'de bırakma**. Object key'de UUID kullan, original name DB metadata'ya yaz.
- **MIME type'a güvenme**. Client gönderdiği content-type spoof'lanabilir. Magic byte check (Apache Tika) ile doğrula.

## 8. Diğer konularla ilişkisi

- [Object Storage Temelleri](./object-storage-fundamentals) — temel kavramlar
- [RustFS Self-Hosted](./rustfs-self-hosted) — storage layer
- [Lifecycle Policy](./lifecycle-policies) — incomplete multipart cleanup
- [ClamAV Virus Scanning](./clamav-virus-scanning) — confirm sonrası scan akışı
- [Audit Log Design](../security-compliance/audit-log-design) — file access audit
- [Domain Servisleri](../01-tenancy-and-domain-model/domain-services-overview) — file-service detay

## 9. Daha derine inmek için

- AWS — [Sharing objects with presigned URLs](https://docs.aws.amazon.com/AmazonS3/latest/userguide/ShareObjectPreSignedURL.html)
- AWS — [Signature Version 4 signing](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_aws-signing.html)
- AWS — [Multipart upload overview](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html)
- AWS SDK Java v2 — [S3Presigner Javadoc](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/presigner/S3Presigner.html)
- Araştırma keyword'leri: `s3 presigned url put content-type signed header`, `multipart upload best practices`, `s3 sigv4 query parameters`, `presigned url security pitfalls`

## 10. Sözlük

- **Pre-signed URL** — Belirli bir S3 operasyonu için süresi sınırlı imzalı URL.
- **Sigv4** — AWS Signature Version 4 imzalama algoritması.
- **Multipart upload** — Büyük dosyayı parçalara bölerek paralel yükleme mekaniği.
- **Part** — Multipart upload'da tek bir parça (min 5 MB, son hariç).
- **Upload ID** — Multipart session tanımlayıcısı.
- **ETag** — Object/part içeriğinin hash'i; integrity check.
- **Signed headers** — İmzaya katılan HTTP header'ları; içerikleri değiştirilemez.
- **Content-Type** — MIME type; signed header olarak girilir.
- **Confirm endpoint** — Upload tamamlandığını backend'e bildiren ikinci çağrı.
- **HEAD object** — Object'in metadata'sını body olmadan döndüren S3 operasyonu.
