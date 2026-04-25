---
title: "S3 Uyumlu Object Storage, Pre-Signed URL ve Lifecycle"
description: Upload, download, silme, multipart upload, versioning ve lifecycle maliyet kontrolü için object storage mimarisi.
sidebar_position: 1
---

## Giriş

Object storage; kullanıcı dosyaları, attachment, media, export, rapor ve üretilen dokümanlar için doğal depolama katmanıdır. Relational database'i binary file store'a çevirmeden dayanıklı dosya saklama sağlar.

Tasarım yalnızca "dosyayı S3'e yükle" değildir. Production file flow; kimin upload edeceğini, URL'in ne kadar geçerli olacağını, metadata'nın nerede tutulacağını, silmenin nasıl çalışacağını, versioning etkisini ve lifecycle kurallarını belirlemelidir.

## Neden Önemli

Dosya akışları geç tasarlanırsa maliyet ve güvenlik riski büyür:

- büyük upload'lar application server'ları yorabilir,
- public bucket özel dosyaları açığa çıkarabilir,
- lifecycle kuralı yoksa storage maliyeti sessizce büyür,
- versioning açıksa silinen object eski version ile kalabilir,
- incomplete multipart upload maliyet üretir,
- DB metadata ile object storage gerçekliği ayrışabilir.

Pre-signed URL, client'ın doğrudan object storage ile konuşmasını sağlar; uygulama ise policy karar noktası olarak kalır.

## Temel Kavramlar

- Object storage: key tabanlı binary object ve metadata depolama.
- S3-compatible storage: S3 tarzı API operasyonlarını destekleyen object storage.
- Pre-signed URL: belirli operasyon için imzalanmış kısa ömürlü URL.
- Object key: bucket içindeki logical path.
- Multipart upload: büyük dosyanın parçalara bölünerek yüklenmesi.
- Versioning: object'in birden fazla version'ını saklayan mod.
- Delete marker: versioned bucket'ta logical delete'i temsil eden marker.
- Lifecycle rule: object transition, expiration veya cleanup politikası.
- Metadata row: object ownership, state ve business context'i tutan DB kaydı.

## Gerçek Ürün Geliştirmede Problem

Object storage ürün kurallarını bilmez:

- Dosya hangi tenant'a ait?
- Hangi user upload/download yapabilir?
- Dosya message, payment, report veya profile'a mı bağlı?
- Kullanımdan önce antivirüs scan gerekir mi?
- Silme immediate mi, soft-delete mi, retention mı, archive mı?
- Maksimum size ve MIME type nedir?

Client bucket'a uygulama tarafından verilmiş policy olmadan erişirse authorization ürün dışına taşar.

## Yaklaşım / Tasarım

### Upload Akışı

```text
1. Client API'den upload başlatmayı ister.
2. API tenant_id, user permission, file type, size ve target domain'i doğrular.
3. API FILE_OBJECTS metadata row oluşturur: status = pending_upload.
4. API kısa ömürlü pre-signed PUT veya multipart URL üretir.
5. Client doğrudan object storage'a upload eder.
6. Client upload tamamlandı diye API'yi bilgilendirir.
7. API object varlığını ve size/hash bilgisini doğrular.
8. API metadata'yı uploaded veya queued_for_scan yapar.
```

Önerilen metadata alanları:

- file_id
- tenant_id
- owner_user_id
- domain_type
- domain_id
- bucket
- object_key
- version_id
- content_type
- size_bytes
- checksum
- status
- retention_until
- created_at
- deleted_at

### Download Akışı

```text
1. Client download access ister.
2. API tenant_id ve domain permission kontrol eder.
3. API file status ve deletion/retention state kontrol eder.
4. API kısa ömürlü pre-signed GET URL üretir.
5. Client object storage'dan indirir.
6. Hassas dosya erişimi için audit event yazılır.
```

### Silme Akışı

```text
delete_file(file_id):
  metadata = load_metadata(file_id)
  authorize(metadata.tenant_id, metadata.domain_type, 'delete')

  mark_metadata_deleted(file_id)

  if hard_delete_allowed(metadata):
    delete_object(bucket, object_key, version_id_if_known)
  else:
    keep_object_until_retention_expires()

  write_audit_log(action='file.delete', target=file_id)
```

### Lifecycle Politika Yönü

| Object sınıfı | Lifecycle kuralı |
| --- | --- |
| Temporary uploads | Pending object'leri kısa sürede expire et |
| Incomplete multipart uploads | Belirli gün sonra abort et |
| Attachments | Business policy'ye göre retain et |
| Exports | Download window sonrası agresif expire et |
| Soft-deleted objects | Retention sonrası expire et |
| Old versions | Recovery window sonrası noncurrent version'ları expire et |

## Sektör Standardı / Best Practice

- Bucket'ları private tut; erişimi pre-signed URL veya kontrollü proxy ile aç.
- Pre-signed URL sürelerini kısa tut.
- Upload URL üretmeden önce file metadata'yı doğrula.
- Business metadata DB'de, bytes object storage'da dursun.
- Temporary file, old version ve incomplete multipart upload için lifecycle rules kullan.
- Versioning'i recovery özelliği olarak ele al; maliyet ve silme davranışını unutma.
- Object key içinde environment ve tenant-safe partitioning kullan.
- Hassas dosya erişimlerini audit et.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
create_upload_url(request):
  validate_user_can_upload(request.tenant_id, request.domain)
  validate_content_type(request.content_type)
  validate_size(request.size_bytes)

  file = create_file_metadata(status='pending_upload')
  object_key = build_object_key(file.tenant_id, file.file_id)

  url = object_storage.sign_put(
    bucket='private-files',
    key=object_key,
    expires_in='10m',
    content_type=request.content_type
  )

  return { file_id: file.id, upload_url: url }
```

## Bizim Notlarımız / Takım Kararları

- File upload ve delete lifecycle production büyümeden tasarlanmalıdır.
- Büyük dosyaların application server üzerinden stream edilmemesi için pre-signed URL tercih edilir.
- Metadata state business kararları için authoritative olmalıdır.
- Lifecycle rules hem maliyet kontrolü hem veri yönetişimi parçasıdır.
- Hassas file access [Audit Log Tasarımı](../security-compliance/audit-log-design) ile ilişkilendirilmelidir.

## Harici Referanslar

- AWS S3: [Sharing objects with presigned URLs](https://docs.aws.amazon.com/AmazonS3/latest/userguide/ShareObjectPreSignedURL.html)
- AWS S3: [Lifecycle configuration elements](https://docs.aws.amazon.com/AmazonS3/latest/userguide/intro-lifecycle-rules.html)

## Sözlük

- Pre-signed URL: belirli storage operasyonu için kısa ömürlü imzalı URL.
- Object key: bucket içinde object'i tanımlayan unique key.
- Multipart upload: büyük object'leri parça parça upload etme mekanizması.
- Lifecycle rule: transition, expiration veya cleanup politikası.
- Versioning: önceki object version'larını saklayan özellik.
- Delete marker: versioned bucket'ta silmeyi temsil eden marker.

## Araştırma Keywordleri

- `s3 presigned url lifecycle versioning object storage`
- `multipart upload pre-signed url design`
- `s3 lifecycle incomplete multipart uploads`
- `object storage metadata table design`
- `s3 versioning delete marker lifecycle`

## Sonuç

Object storage tasarımı application authorization, metadata ownership, direct client transfer ve lifecycle governance'ı birlikte ele almalıdır. Pre-signed URL transfer problemini çözer; metadata, deletion, retention ve cost control tasarımı ise akışı production-ready yapar.
