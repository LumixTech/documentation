---
title: "PostgreSQL Backup, WAL, PITR ve Restore Drill"
description: WAL archiving, point-in-time recovery, 15 dakika RPO ve 2 saat RTO hedefi için backup ve restore runbook taslağı.
sidebar_position: 6
---

## Giriş

Backup başarılı sayılmaz; restore edilebildiği ve hedef recovery süresine uyduğu zaman başarılı sayılır.

PostgreSQL için production-grade backup stratejisi genellikle base backup, WAL archiving, point-in-time recovery, monitoring ve düzenli restore drill birleşimidir. Bu doküman şu hedefler için teknik runbook yönü verir:

```text
RPO = 15 dakika
RTO = 2 saat
```

## Neden Önemli

Veri kaybı ve uzun recovery süresi core ürün verisi için büyük iş riski oluşturur.

Restore disiplini yoksa:

- backup eksik veya bozuk olabilir,
- WAL archive içinde gap olabilir,
- recovery adımları tek kişinin bilgisinde kalabilir,
- RPO/RTO hedefleri gerçeği yansıtmayabilir,
- disaster recovery ihtiyaç anında başarısız olabilir.

Restore drill, backup tasarımının çalıştığını kanıtlar.

## Temel Kavramlar

- Base backup: recovery başlangıç noktası olan fiziksel database cluster kopyası.
- WAL: database değişikliklerini replay etmek için kullanılan write-ahead log.
- WAL archiving: WAL segmentlerinin durable storage'a sürekli taşınması.
- PITR: base backup restore edilip WAL belirli hedef zamana kadar replay edilerek yapılan recovery.
- RPO: kabul edilebilir maksimum veri kaybı.
- RTO: kabul edilebilir maksimum recovery süresi.
- Restore drill: backup, dokümantasyon ve zamanlamayı doğrulayan planlı recovery provası.
- Timeline: PITR sonrası oluşan PostgreSQL recovery branch'i.

## Gerçek Ürün Geliştirmede Problem

Birçok ekip "backup var" der ama şu soruları cevaplayamaz:

- Son restore testi ne zaman yapıldı?
- Kaç dakikalık veri kaybı kabul edilebilir?
- Recovery gerçekte ne kadar sürüyor?
- WAL dosyaları archive ediliyor ve izleniyor mu?
- Production'ı bozmadan izole restore yapılabiliyor mu?
- Recovery target time kararını kim onaylıyor?

Operasyonel problem sadece backup almak değildir; tekrar edilebilir recovery workflow tasarlamaktır.

## Yaklaşım / Tasarım

### Backup Bileşenleri

| Bileşen | Amaç | Hedef |
| --- | --- | --- |
| Full/base backup | Recovery başlangıcı | En az günlük ve riskli değişiklik öncesi |
| WAL archive | Düşük RPO change capture | Sürekli ve monitored |
| Backup manifest/checksum | Integrity validation | Her backup |
| Restore environment | Güvenli validation target | İzole network/account |
| Runbook | İnsan tarafından çalıştırılabilir akış | Versioned ve prova edilmiş |

### 15 Dakika RPO Yönü

15 dakika RPO için:

- WAL sürekli archive edilmeli,
- WAL archiving fail veya lag durumunda alert üretmeli,
- son archived WAL zamanı izlenmeli,
- restore window'u kapsayacak WAL retention tutulmalı,
- backup storage durable ve access-controlled olmalı.

WAL archive lag 15 dakikayı aşarsa RPO ihlal edilir.

### 2 Saat RTO Yönü

2 saat RTO için:

- restore infrastructure IaC ile önceden tanımlı olmalı,
- son valid base backup'ın nerede olduğu bilinmeli,
- restore komutları mümkün olduğunca otomasyonla çalışmalı,
- gerçek data volume ile restore süresi test edilmeli,
- DNS/application cutover adımları database restore'dan ayrı dokümante edilmeli.

2 saat içinde application-compatible database ayağa kalkmazsa RTO ihlal edilir.

## Sektör Standardı / Best Practice

- PITR için physical base backup + WAL archiving kullan.
- Backup'ları encrypt et ve restore permission'larını sınırla.
- Backup'ları primary database host dışında tut.
- Hem backup success hem WAL archive freshness izle.
- Restore'u izole environment'ta düzenli test et.
- Runbook'u kısa, executable ve versioned tut.
- Restore drill sonucunu mühendislik kanıtı olarak kaydet.
- Backup, restore, approval ve communication sahipliklerini tanımla.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

### Restore Drill Runbook Taslağı

```text
restore_drill(target_time):
  announce_drill_window()
  select_latest_base_backup_before(target_time)
  provision_isolated_restore_instance()
  download_base_backup()
  verify_backup_manifest_and_checksums()

  configure_restore_command_for_wal_archive()
  set_recovery_target_time(target_time)
  start_postgresql_recovery()

  wait_until_recovery_completes()
  run_integrity_checks()
  run_application_smoke_tests()
  record_actual_rpo_rto()
  destroy_or_quarantine_restore_environment()
```

### Incident Restore Karar Akışı

```text
if data_corruption_detected:
  freeze_writes_if_required()
  identify_safe_recovery_target_time()
  get incident commander approval
  restore to isolated environment
  validate business-critical data
  cut over only after approval
```

## Bizim Notlarımız / Takım Kararları

- Backup tasarımı restore kanıtı içermelidir; yalnızca backup almak yeterli değildir.
- Hedef planlama 15 dakika RPO ve 2 saat RTO varsayımıyla yapılmalıdır.
- Restore drill planlı ve dokümante operasyon gereksinimi olmalıdır.
- Backup ve restore event'leri operasyonel audit evidence ile ilişkilendirilmelidir.
- PITR planı [Flyway Migrations ve Zero-Downtime DB Changes](./flyway-zero-downtime-migration-strategy) ile birlikte düşünülmelidir.

## Harici Referanslar

- PostgreSQL: [Continuous Archiving and Point-in-Time Recovery](https://www.postgresql.org/docs/current/continuous-archiving.html)
- PostgreSQL: [pg_basebackup](https://www.postgresql.org/docs/current/app-pgbasebackup.html)

## Sözlük

- WAL: PostgreSQL değişikliklerini kaydeden write-ahead log.
- PITR: belirli zamana restore etme süreci.
- RPO: kabul edilebilir maksimum veri kaybı.
- RTO: kabul edilebilir maksimum recovery süresi.
- Base backup: recovery başlangıcı olan fiziksel backup.
- Restore drill: kontrollü recovery provası.

## Araştırma Keywordleri

- `postgresql wal archiving pitr restore drill`
- `backup strategy rpo rto postgresql`
- `pg_basebackup restore runbook`
- `postgresql recovery_target_time`
- `wal archive monitoring`

## Sonuç

PostgreSQL backup stratejisi restore pratiği olmadan güvenilir değildir. Base backup, WAL archiving, PITR, monitoring ve restore drill birlikte RPO/RTO hedeflerini ölçülebilir hale getirir.
