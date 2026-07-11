---
title: Domain Servisleri — 10 Microservice
description: Lumix'in 10 domain microservice'i, sorumlulukları, sahip oldukları veri ve dışa açtıkları event/API'lar.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Lumix sistemini **hangi servisler** oluşturuyor, **her servis ne yapıyor**, **hangi veriye sahip**, **hangi event'leri üretiyor/tüketiyor**? Bu sayfa servis sınırlarının haritasıdır. Yeni feature eklenirken **"bu hangi servise ait?"** sorusunun ilk başvuru noktası.

## 1. Servis sınırlarını nasıl çiziyoruz?

DDD'nin **bounded context** kavramına dayanarak. Her servis:
- **Tek bir iş kabiliyetinin** (business capability) sahibi
- **Kendi DB'sinin** sahibi (DB-per-service)
- **Kendi domain dilinin** sahibi (aynı kelime başka servislerde farklı anlama gelebilir)
- **Dışa karşı sözleşmeli iletişim**: gRPC API + Kafka integration event

Servisler arası **direkt DB erişimi yasaktır**. Veri başka servisten geliyorsa: gRPC sorgusu veya event abonelik.

## 2. Servisler — özet tablo

| # | Servis | Tek cümlede ne yapıyor |
|---|---|---|
| 1 | **identity-service** | Kullanıcı, rol, permission, scope, auth/login, session |
| 2 | **organization-service** | Installation, tenant, okul, şube, sınıf, hiyerarşi |
| 3 | **academic-service** | Müfredat, ders programı, kayıt, yoklama |
| 4 | **assessment-service** | Sınav, not, karne |
| 5 | **counseling-service** | PDR (rehberlik) — özel kategori veri |
| 6 | **performance-service** | Öğrenci performans takibi, gözlem, hedef |
| 7 | **communication-service** | Mesajlaşma, sohbet, duyurular |
| 8 | **finance-service** | Fatura, ödeme, borç, iade |
| 9 | **file-service** | Dosya metadata + RustFS adapter |
| 10 | **audit-service** | Merkezi audit log toplama (event consumer) |
| 11 | **compliance-service** | DSAR, retention, anonymization |
| 12 | **notification-service** | Email, SMS, push notification (adapter) |

> **NOT:** 10 ana domain + 2 cross-cutting (audit, notification) = toplam 12 servis. "10" temel domain kabiliyeti sayısı.

## 3. Her servisin detayı

Aşağıdaki bölümlerde her servis için: sorumluluk, sahip olduğu veriler, dışa açtığı gRPC API'lar, ürettiği event'ler, tükettiği event'ler.

---

### 3.1. identity-service

**Sorumluluk:**
- Kullanıcı kayıt, login, logout
- JWT üretim ve doğrulama
- Session lifecycle (Redis'te)
- Token refresh ve rotation
- Permission resolution
- Scope assignment
- Keycloak entegrasyonu (opsiyonel)

**Sahip olduğu veri:**
- `users` — kullanıcı kimlik bilgileri
- `roles` — sistem roller
- `permissions` — atomic permission tanımları
- `role_permissions` — rol-permission ataması
- `user_permissions` — direkt user-permission ataması
- `user_tenant_assignments` — kim hangi tenant'a atanmış
- `user_scope_assignments` — kim hangi scope'a atanmış
- `audit log mirror` — auth event'leri (audit-service'e de gider)

**Veri Redis'te:**
- `session:{id}`, `token:access:{jti}`, `token:refresh:{hash}`
- `user:sessions:{user_id}`
- `user:permissions:{user_id}` (cache)

**gRPC API:**
- `Authenticate(credentials)` → JWT
- `RefreshToken(refresh_token)` → new JWT
- `ValidateToken(jwt)` → claims + status
- `GetUserScope(user_id)` → effective scope
- `GetUserPermissions(user_id)` → permission list
- `RevokeTokensForUser(user_id, reason)` → side effect

**Ürettiği event'ler (Kafka):**
- `identity.user.created.v1`
- `identity.user.role_changed.v1`
- `identity.user.permission_changed.v1`
- `identity.user.logged_in.v1`
- `identity.user.logged_out.v1`
- `identity.user.scope_assigned.v1`

**Tükettiği event'ler:**
- `organization.tenant.created.v1` (default rolleri seed et)
- `compliance.user.anonymization_requested.v1`

---

### 3.2. organization-service

**Sorumluluk:**
- Installation kayıtları (master)
- Tenant CRUD
- Okul, şube, sınıf hiyerarşisi
- Öğretmen-sınıf ataması
- Öğrenci-sınıf kaydı (enrollment hangi sınıfta olduğu — academic değil)

**Sahip olduğu veri:**
- `installations` — kurumun ana kaydı
- `tenants` — şubeler/birimler
- `schools` — okul tipleri (lise, ortaokul, ilkokul)
- `branches` — şube
- `classes` — sınıflar (11-A, 12-B gibi)
- `class_assignments` — kim hangi sınıfa atandı
- `school_levels` / `assignment_roles` — reference (lookup) verisi; hard-coded enum yerine config-driven seed (typed FK, installation-global)

**gRPC API:**
- `GetTenant(tenant_id)`
- `ListTenantsForInstallation(installation_id)`
- `GetClass(class_id)` → tenant_id, school_id, ...
- `GetClassesForTeacher(teacher_id)`
- `GetClassesForStudent(student_id)`

**Ürettiği event'ler:**
- `organization.tenant.created.v1`
- `organization.tenant.deactivated.v1`
- `organization.class.created.v1`
- `organization.class_assignment.granted.v1`

**Tükettiği event'ler:**
- `identity.user.created.v1` (yeni user için tenant ataması kontrolü)

---

### 3.3. academic-service

**Sorumluluk:**
- Müfredat, ders, ders programı
- Yoklama (attendance) — peak saatte yoğun
- Ödev, ödev teslim takibi
- Akademik takvim

**Sahip olduğu veri:**
- `courses`, `course_assignments`
- `schedules`
- `attendances` — yoklama kayıtları
- `attendance_summaries` — denormalized özet
- `homework_assignments`, `homework_submissions`

**gRPC API:**
- `MarkAttendance(class_id, date, student_marks[])`
- `GetAttendance(class_id, date)`
- `GetStudentAttendanceHistory(student_id, range)`

**Ürettiği event'ler:**
- `academic.attendance.marked.v1`
- `academic.attendance.revised.v1`
- `academic.homework.assigned.v1`
- `academic.homework.submitted.v1`

**Tükettiği event'ler:**
- `organization.class.created.v1`
- `organization.class_assignment.granted.v1`
- `identity.user.scope_assigned.v1`

---

### 3.4. assessment-service

**Sorumluluk:**
- Sınav tanımları
- Not girişi
- Karne hesabı (report card)
- Sınav istatistikleri

**Sahip olduğu veri:**
- `exams`, `exam_definitions`
- `grades` — sınav notları
- `report_cards` — dönem karneleri
- `grading_scales` — not skalaları (4'lük, 5'lik, 100'lük)

**gRPC API:**
- `RecordGrade(exam_id, student_id, grade)`
- `GenerateReportCard(student_id, term_id)`
- `GetStudentGrades(student_id, term_id)`

**Ürettiği event'ler:**
- `assessment.grade.recorded.v1`
- `assessment.report_card.generated.v1`

**Tükettiği event'ler:**
- `academic.attendance.summary.calculated.v1`

---

### 3.5. counseling-service (PDR)

**Sorumluluk:**
- Rehberlik görüşmeleri
- Psikolojik notlar
- Davranış kayıtları
- Aile görüşmeleri

⚠️ **KVKK özel kategori veri** — at-rest envelope encryption zorunlu, ayrı erişim kontrolü, ayrı audit detayı.

**Sahip olduğu veri:**
- `counseling_sessions` — görüşme kayıtları
- `psychological_notes` — encrypted (Vault Transit DEK)
- `behavioral_records`
- `family_meetings`

**gRPC API:**
- `CreateCounselingSession(student_id, session_data)`
- `GetSessionsForStudent(student_id)` — özel auth check
- `AddNote(session_id, encrypted_note)`

**Ürettiği event'ler:**
- `counseling.session.created.v1` (PII redact)
- `counseling.note.added.v1` (sadece metadata)

**Özel kurallar:**
- Sadece **rehber öğretmen** ve **okul yönetimi** erişebilir
- Her erişim **explicit audit** (read action bile)
- Veri at-rest: per-tenant DEK ile envelope encryption
- Anonymization request'inde **gerçek silme** (anonymize değil hard-delete)

---

### 3.6. performance-service

**Sorumluluk:**
- Öğrenci performans göstergeleri
- Gözlem notları
- Hedef belirleme ve takip
- Trend analizi

**Sahip olduğu veri:**
- `performance_observations`
- `student_goals`
- `goal_progress`
- `performance_metrics_aggregates`

**gRPC API:**
- `RecordObservation(student_id, observation)`
- `SetGoal(student_id, goal)`
- `GetPerformanceReport(student_id, range)`

**Ürettiği event'ler:**
- `performance.observation.recorded.v1`
- `performance.goal.set.v1`
- `performance.goal.achieved.v1`

**Tükettiği event'ler:**
- `assessment.grade.recorded.v1` (performans hesabı için)
- `academic.attendance.marked.v1` (devamsızlık eklemek için)

---

### 3.7. communication-service

**Sorumluluk:**
- Mesajlaşma (1-1, 1-N, sınıf grup)
- Sohbet (chat)
- Duyurular (announcement)
- Real-time delivery (WebSocket fan-out)

**Sahip olduğu veri:**
- `conversations`
- `messages`
- `message_attachments` (file-service'e referans)
- `announcements`
- `message_read_status`

**gRPC API:**
- `SendMessage(conversation_id, sender_id, body, attachments[])`
- `GetMessages(conversation_id, before, limit)`
- `MarkAsRead(message_ids[])`

**WebSocket destinations:**
- `/topic/messages.{conversation_id}` — sohbet
- `/user/queue/notifications` — kişiye özel
- `/topic/announcements.{tenant_id}` — duyurular

**Ürettiği event'ler:**
- `communication.message.sent.v1`
- `communication.announcement.published.v1`

**Tükettiği event'ler:**
- `file.upload.completed.v1` (mesaja eklenen dosya hazır)

---

### 3.8. finance-service

**Sorumluluk:**
- Fatura oluşturma
- Ödeme süreçleri
- Borç takibi
- İade
- Çoklu sanal POS routing

**Sahip olduğu veri:**
- `invoices`
- `payments` — state machine
- `payment_attempts`
- `refunds`
- `tenant_payment_provider_config` — hangi tenant hangi POS'u kullanıyor

**gRPC API:**
- `CreateInvoice(tenant_id, student_id, line_items)`
- `InitiatePayment(invoice_id, provider_hint)`
- `HandleProviderCallback(provider, signed_payload)`
- `Refund(payment_id, amount, reason)`

**Ürettiği event'ler:**
- `finance.invoice.created.v1`
- `finance.payment.authorized.v1`
- `finance.payment.captured.v1`
- `finance.payment.failed.v1`
- `finance.refund.completed.v1`

**Tükettiği event'ler:**
- `organization.student.registered.v1` (yeni öğrenciye fatura kur)

**Saga örneği** (Temporal):
- `EnrollmentSaga`: öğrenci kayıt → finance.create_invoice → payment.initiate → enrollment.confirm (veya compensate)

---

### 3.9. file-service

**Sorumluluk:**
- Dosya metadata yönetimi
- RustFS adapter (S3-compatible)
- Pre-signed URL üretimi
- Lifecycle policy uygulaması
- Antivirus tarama orkestre

**Sahip olduğu veri:**
- `file_objects` — metadata kaydı
- `file_versions`
- `scan_results` — ClamAV sonuçları

**gRPC API:**
- `RequestUploadUrl(tenant_id, user_id, content_type, size)`
- `ConfirmUpload(file_id)`
- `RequestDownloadUrl(file_id)`
- `DeleteFile(file_id)`

**Ürettiği event'ler:**
- `file.upload.requested.v1`
- `file.upload.completed.v1`
- `file.scan.clean.v1`
- `file.scan.infected.v1` → file karantinaya, alert

**Tükettiği event'ler:**
- `compliance.dsar.delete_requested.v1`

---

### 3.10. audit-service

**Sorumluluk:**
- Tüm kritik event'leri Kafka'dan toplayıp **append-only audit DB**'ye yazmak
- Sorgu API'si (kim ne zaman ne yaptı)
- Retention policy uygulaması
- Tamper-evident storage

**Sahip olduğu veri:**
- `audit_logs` — append-only; `audit_app` yalnızca **SELECT + INSERT** (UPDATE/DELETE reddedilir → immutable). SELECT, sorgu API'si (`QueryAuditLogs`) için gerekli.

**gRPC API:**
- `QueryAuditLogs(filters)` → sadece yetkili kullanıcılara
- `ExportAuditLogs(tenant_id, range)` (compliance amaçlı)

**Tükettiği event'ler:** (hepsini consume eder)
- `identity.user.*`
- `finance.payment.*`
- `counseling.*` (özel hassasiyet)
- `file.*`
- `compliance.dsar.*`

**Üretmez** — son durağı.

---

### 3.11. compliance-service

**Sorumluluk:**
- DSAR workflow orchestration (Temporal)
- Retention policy enforcement
- Anonymization job tetikleme
- KVKK/GDPR uyum reporting

**Sahip olduğu veri:**
- `dsar_requests` — workflow state
- `retention_policies` — per-purpose
- `anonymization_jobs` — execution evidence

**gRPC API:**
- `SubmitDSAR(subject_id, request_type)` → workflow start
- `GetDSARStatus(request_id)`
- `TriggerRetentionEnforcement()` — scheduled

**Ürettiği event'ler:**
- `compliance.dsar.requested.v1`
- `compliance.dsar.approved.v1`
- `compliance.user.anonymization_requested.v1`
- `compliance.retention.expired.v1` (her servis kendi verisini siler)

**Tükettiği event'ler:**
- Hiçbiri (her şey Temporal workflow ile)

---

### 3.12. notification-service

**Sorumluluk:**
- Email, SMS, Push gönderim
- Provider adapter (SES, SendGrid, FCM, Twilio, vs.)
- Template rendering (MJML → HTML, dil değişimi)
- Delivery tracking

**Sahip olduğu veri:**
- `notification_templates`
- `notification_logs` — kim ne aldı, ne zaman, hangi kanaldan
- `provider_configs` — tenant başına provider seçimi

**gRPC API:**
- `SendNotification(recipient_id, template_id, variables, channel_hint)` (genelde kullanılmaz)

**Tükettiği event'ler:** (çoğu)
- `academic.attendance.marked.v1` → veliye SMS
- `communication.message.sent.v1` → push notification
- `finance.invoice.created.v1` → email
- `finance.payment.failed.v1` → email + SMS
- `assessment.report_card.generated.v1` → email

**Üretmez** (provider'a delegate eder, log tutar).

---

## 4. Cross-service iletişim haritası

### 4.1. Sync (gRPC) — kim kimi sorgular?

| Caller | Callee | Neden |
|---|---|---|
| Tüm servisler | identity-service | Token validate, scope check |
| academic-service | organization-service | "Bu sınıf hangi tenant'a ait?" |
| assessment-service | academic-service | "Bu öğrencinin yoklama özeti" |
| communication-service | file-service | "Bu mesajdaki dosya tarandı mı?" |
| Her servis | organization-service | Tenant validation |

### 4.2. Async (Kafka) — kim kimi etkiler?

```
identity ──user.created──► organization (tenant atama kontrolü)
identity ──permission_changed──► audit
                                  notification (kullanıcıya bilgi)

organization ──tenant.created──► identity (default rol seed)
                                  finance (default invoice config)
                                  audit

academic ──attendance.marked──► notification (veliye SMS)
                                  performance (devamsızlık eklemesi)
                                  audit
                                  elasticsearch-indexer

finance ──payment.captured──► academic (kayıt onayı)
                                notification (makbuz)
                                audit

counseling ──session.created──► audit (özel hassasiyet)

file ──upload.completed──► communication (mesaja ek hazır)
                            scan-orchestrator
                            audit
file ──scan.infected──► alerts
                         file-service (karantina)

compliance ──dsar.approved──► her servis (kendi verisini sil/anonymize)
```

### 4.3. WebSocket fan-out — kim ne pushlar?

- **communication-service** → `/topic/messages.{conv_id}`, `/user/queue/notifications`
- **academic-service** → `/topic/attendance.{class_id}`
- **finance-service** → `/user/queue/payment-status`
- **organization-service** → `/topic/announcements.{tenant_id}`

Hepsi **Redis Pub/Sub backplane** üzerinden multi-pod'a yayılır.

## 5. Servis sayısı neden bu kadar?

| Soru | Cevap |
|---|---|
| "Daha az olamaz mıydı?" | Olabilirdi ama PDR ayrı (özel veri), audit ayrı (cross-cutting), notification ayrı (adapter sürüleri) — birleştirilirse sınır bulanır |
| "Daha çok olamaz mı?" | Olabilirdi (organization → 3-4'e bölünebilir) ama ekip boyutu büyümeden gereksiz overhead |
| "Bütün servisler aynı anda mı yazılacak?" | Hayır. MVP'de identity + organization + academic + audit yeter. Diğerleri ihtiyaç sırasıyla |

## 6. Servis ekleme/silme/birleştirme kuralı

Yeni servis ne zaman eklenir?
- Bir iş kabiliyeti **kendi bounded context**'ini hak ediyorsa
- Mevcut servis büyüyüp **iki ekibe** bölünmek istendiğinde
- Spesifik teknoloji ihtiyacı doğduğunda (örn. video-stream-service Rust ile)

Servis ne zaman birleştirilir?
- İki servis **her zaman birlikte değişiyorsa**
- Cross-service çağrı **gereksiz network hop** üretiyorsa
- Sahibi olan ekipler aynıysa ve bağımsızlık değer üretmiyorsa

## 7. Diğer konularla ilişkisi

- [Installation/Tenant/Scope Modeli](./01-installation-tenant-scope.md) — bu servislerin altında yatan multi-tenancy
- Microservices vs Modular Monolith (yeniden yazılacak)
- [gRPC Service Communication](../03-backend/03-grpc-service-communication.md) (yazılacak)
- Kafka Topic Design (yazılacak)
- [Outbox Pattern](../02-architecture-patterns/06-outbox-pattern.md) — her servisteki event publish

## 8. Sözlük

- **Bounded context** — DDD'de bir modelin/dilin geçerli olduğu sınır
- **Domain event** — bounded context içindeki iş olayı (internal)
- **Integration event** — başka bounded context'lere yayınlanan public sözleşme (Kafka topic'leri)
- **gRPC** — sync inter-service RPC framework
- **DB-per-service** — her microservice'in kendi PostgreSQL DB'sine sahip olması
- **Outbox pattern** — transactional event publish guarantee
- **Saga** — distributed transaction (Temporal workflow olarak)
