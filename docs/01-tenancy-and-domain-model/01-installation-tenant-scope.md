---
title: Installation / Tenant / Scope Modeli
description: Lumix'in üç katmanlı multi-tenancy modeli — kurum (installation), okul (tenant) ve kullanıcı yetki kapsamı (scope).
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Lumix'in **multi-tenancy** modeli üç katmandan oluşur: **Installation**, **Tenant**, **Scope**. Bu sayfa bu üç kavramı sıfırdan açıklar, neden böyle ayrıştırdığımızı anlatır, gerçek örneklerle (Hüseyin öğretmen, bölge müdürü) somutlaştırır. Bu model **auth, RLS, index tasarımı ve permission'ın temelidir** — buradan önce başka doc'a geçmek yanıltıcı olur.

## 1. Multi-tenancy nedir? (Sıfırdan)

**Multi-tenancy**, bir yazılım sisteminin aynı anda **birden fazla bağımsız müşteriye/birimle** hizmet vermesi. Her müşteri/birim kendi verisini, kendi kullanıcılarını, kendi ayarlarını görür; başkasınınkini göremez.

Klasik örnek: Gmail. Senin inbox'ın benim inbox'ıma karışmaz — ama ikimiz de aynı Gmail altyapısını kullanıyoruz. Sen Gmail için bir **tenant**'sın.

### Multi-tenancy'nin üç klasik modeli

| Model | Açıklama | Lumix'in seçimi |
|---|---|---|
| **DB per tenant** | Her tenant'a ayrı veritabanı | ❌ Bizim model değil tam böyle |
| **Schema per tenant** | Tek DB, her tenant kendi schema'sında | ❌ Bizim model değil |
| **Shared DB + tenant_id sütunu** | Tek DB, satırlar `tenant_id` ile ayrılır | ✅ Tenant seviyesinde böyle |

Ama Lumix bunlardan birini değil, **iki seviyeli bir hibrit** kullanır:

- **Installation seviyesinde** → DB per installation (her müşterinin tamamen ayrı kurulumu var)
- **Tenant seviyesinde** → Shared DB (o installation içinde) + `tenant_id` ile satır ayrımı + RLS

Bunu netleştirmek için önce iki kavramı tek tek anlatalım.

## 2. Installation nedir?

**Installation** = sistemi satın alan **kurumun kendi kurulumunun tamamı**.

- Bir installation = bir K8s cluster
- Bir installation = bir Vault instance
- Bir installation = ayrı PostgreSQL'ler (servis başına ama o cluster'a ait)
- Bir installation = ayrı Kafka cluster
- Bir installation = ayrı Redis, ayrı Elasticsearch, ayrı her şey

Bu kurulum **müşterinin kendi sunucusuna** (on-prem) veya **kendi seçtiği bulutuna** yapılır. Lumix sağlayıcısı (sen) bunu uzaktan yönetir ama altyapı müşterinin.

### Örnek
- **Installation 1** = "Ömer Okulları" kurumu — Kendi VPS'leri, kendi DB'leri.
- **Installation 2** = "X Eğitim Vakfı" kurumu — Tamamen ayrı altyapı.
- Installation 1'in herhangi bir verisi Installation 2'de **yok**, **erişilemez**, **görülmez**. Fiziksel ayrım.

### Neden installation seviyesinde fiziksel ayrım?

| Sebep | Açıklama |
|---|---|
| **Veri sahipliği** | Müşteri verisinin kendi sunucusunda olduğunu bilir, güvenir |
| **KVKK / data residency** | Türk müşterisi verisi Türkiye'de kalır, AB müşterisi AB'de |
| **Performans izolasyonu** | Bir müşterinin trafiği diğerini etkilemez |
| **Patlama yarıçapı** | Bir installation'da bug olursa sadece o müşteri etkilenir |
| **Lisanslama** | Offline çalışan müşteri kurulumu mümkün |

### Installation'lar nasıl yönetilir?

Sen (Lumix sağlayıcı) merkezi olarak:
- **Rancher Manager** ile tüm installation cluster'larını tek UI'dan görürsün
- **ArgoCD ApplicationSet** ile yeni versiyonu tüm cluster'lara push'larsın
- **Internal Admin Panel** ile installation lifecycle (yeni müşteri ekle, lisans yenile, destek aç) yaparsın

## 3. Tenant nedir?

**Tenant** = bir installation içindeki **bağımsız operasyonel birim**.

Müşteri kurumun içinde **birden fazla okul/şube/birim** olabilir. Bunların her biri bir **tenant**'tır. Aynı DB'yi paylaşırlar, ama satırları `tenant_id` ile ayrılır.

### Örnek

"Ömer Okulları" kurumunun içinde:
- **Tenant 1** = Kadıköy Şubesi
- **Tenant 2** = Beşiktaş Şubesi
- **Tenant 3** = Üsküdar Şubesi

Aynı installation içindeler. Aynı `students` tablosunu paylaşıyorlar. Ama her satırda bir `tenant_id` var, ve **RLS policy** garanti ediyor ki Kadıköy şubesi yöneticisi sorgu attığında sadece `tenant_id=1` satırlarını görüyor.

### Tenant ayrımı nerede uygulanır?

| Katman | Nasıl |
|---|---|
| **Application** | İstek başında `tenant_id` JWT'den çekilir, MDC/context'e konur |
| **Database** | PostgreSQL session variable: `SET app.tenant_id = '...'` |
| **RLS Policy** | Her tenant-scoped tablo: `USING (tenant_id = current_setting('app.tenant_id')::uuid)` |
| **Index** | Composite index'lerde `tenant_id` lider sütun |
| **Kafka header** | Her event'in metadata'sında `tenant_id` |
| **Log/metric/trace** | Her sinyalde `tenant-id` attribute |

### Neden installation **içinde** tenant da ayrı?

Çünkü:
- Bir müşteri kurumu **birden fazla şube** işletebilir.
- Şubeler **birbirinden veri ayrımı** ister — Kadıköy yöneticisi Beşiktaş öğrencilerini görmemeli.
- Ama veriler **aynı altyapıyı** paylaşır — installation seviyesinde fiziksel ayırma müşteri kuruma değil, tenant'a değil.

Yani:
- Installation ayrımı = **müşteri kurumlar arası** (Ömer Okulları vs X Vakfı)
- Tenant ayrımı = **kurumun içindeki birimler arası** (Kadıköy vs Beşiktaş)

## 4. Scope nedir?

**Scope** = bir kullanıcının **tenant içinde görebileceği veri kapsamı**.

Tenant tek başına yeterli değil. Çünkü bir tenant'ta (örn. Kadıköy şubesi) **binlerce öğrenci** olabilir. Ama oradaki Hüseyin öğretmen sadece **kendi sınıflarını** görmeli.

Yani:
- **Tenant** = "hangi şubeye aitsin"
- **Scope** = "o şubede ne kadarını görebilirsin"

### Scope hiyerarşisi

```
Tenant (Kadıköy Şubesi)
  └─ School (Lise) — opsiyonel ara katman
       └─ Class (11-A)
            └─ Student (Ahmet, Mehmet, ...)
```

Bir kullanıcının scope'u bu hiyerarşinin **herhangi bir seviyesinde** olabilir:

- **School-level scope** → tüm 11. ve 12. sınıfları görür (lise yöneticisi)
- **Class-level scope** → sadece 11-A ve 12-B sınıflarını görür (sınıf öğretmeni Hüseyin)
- **Student-level scope** → sadece kendi çocuklarını görür (veli)

### Scope resolution kuralı (short-circuit)

```
1. School scope var mı? → en geniş scope, dur.
2. Class scope var mı? → class seviyesinde dur.
3. Student scope var mı? → o öğrencilerle sınırlı.
4. Hiçbiri yok mu? → erişim yok.
```

Bu sayede tek bir kod yolu tüm seviyelerde çalışır, her endpoint'te ayrı kontrol yazmaya gerek kalmaz.

## 5. Üç katman birlikte — gerçek örnekler

### Örnek A: Hüseyin Öğretmen

Hüseyin, "Ömer Okulları → Kadıköy Şubesi"nde çalışan bir öğretmen. Sadece **11-A** ve **12-B** sınıflarına ders veriyor.

| Katman | Hüseyin için değer |
|---|---|
| Installation | "Ömer Okulları" kurumu (cluster_1) |
| Tenant | "Kadıköy Şubesi" (`tenant_id=uuid-kadikoy`) |
| Scope | Class-level: `class_ids = [11-A, 12-B]` |

Hüseyin sisteme girdiğinde:
- JWT'sinde `tenant_id = uuid-kadikoy` var
- `/api/v1/students` çağırdığında:
  - RLS: `WHERE tenant_id = uuid-kadikoy`
  - ScopeFilter: `AND class_id IN (11-A, 12-B)`
- Sonuç: Hüseyin **sadece kendi iki sınıfının** öğrencilerini görür.

Bir Beşiktaş şubesindeki öğrenciyi sorgulamaya çalışırsa? RLS engeller — tenant mismatch.

### Örnek B: Bölge Müdürü

Veli, "Ömer Okulları"nın İstanbul Anadolu yakası bölge müdürü. Beş şubeyi (Kadıköy, Üsküdar, Maltepe, Pendik, Tuzla) görmesi gerek.

| Katman | Veli için değer |
|---|---|
| Installation | "Ömer Okulları" kurumu (cluster_1) |
| Tenant | **Birden çok**: `tenant_ids = [Kadıköy, Üsküdar, Maltepe, Pendik, Tuzla]` |
| Scope | School-level (her tenant'ta full erişim) |

Bu durumda RLS policy'sini biraz farklı kuruyoruz:

```sql
-- Tek tenant kullanıcılar için:
USING (tenant_id = current_setting('app.tenant_id')::uuid)

-- Multi-tenant (bölge müdürü) kullanıcılar için:
USING (tenant_id = ANY(current_setting('app.tenant_ids')::uuid[]))
```

Yani DB session variable:
- Tek tenant'lı kullanıcı için `app.tenant_id` set edilir
- Multi-tenant'lı kullanıcı için `app.tenant_ids` array olarak set edilir

Spring tarafında karar:
```java
if (user.isMultiTenant()) {
  jdbc.execute("SET LOCAL app.tenant_ids = '{" + user.tenantIds + "}'");
} else {
  jdbc.execute("SET LOCAL app.tenant_id = '" + user.tenantId + "'");
}
```

RLS policy'leri her iki durumu da destekleyecek şekilde yazılır (UNION'lu USING clause veya iki ayrı policy).

### Örnek C: Veli

Ayşe Hanım, iki çocuğu olan bir veli (Kadıköy Şubesi).

| Katman | Ayşe için |
|---|---|
| Installation | "Ömer Okulları" |
| Tenant | "Kadıköy" (`tenant_id=uuid-kadikoy`) |
| Scope | Student-level: `student_ids = [child_1_id, child_2_id]` |

Ayşe ne görür?
- Sadece iki çocuğunun karnesi, devamsızlık durumu, ödevleri
- Hatta sınıftaki diğer öğrencileri **göremez**

## 6. Veri modeline yansıması

### Tenant-scoped tablolar (RLS uygulanır)

Bu tablolar her satırda `tenant_id` taşır:
- `students`, `teachers`, `parents`
- `classes`, `enrollments`, `attendances`
- `messages`, `notifications`
- `payments`, `invoices`
- `audit_logs` (tenant_id taşır ama immutable)

### Cross-tenant tablolar (RLS uygulanmaz veya farklı policy)

Bu tablolar tenant'lar üstünde, installation seviyesinde:
- `tenants` (tenant kayıtlarının kendisi)
- `users` (bir kullanıcı birden çok tenant'a atanabilir — bölge müdürü)
- `user_tenant_assignments` — kim hangi tenant'a atanmış
- `installation_config` — global ayarlar

### Scope verisi nerede?

`user_scope_assignments` tablosu (veya servis-spesifik tablolar):

```sql
CREATE TABLE user_scope_assignments (
  user_id UUID NOT NULL,
  tenant_id UUID NOT NULL,
  scope_type TEXT NOT NULL,        -- 'school', 'class', 'student'
  scope_target_id UUID NOT NULL,   -- school_id, class_id, student_id
  granted_at TIMESTAMPTZ NOT NULL,
  granted_by UUID NOT NULL,
  PRIMARY KEY (user_id, tenant_id, scope_type, scope_target_id)
);
```

ScopeResolver bu tabloyu okur, kullanıcının effective scope'unu hesaplar, request context'e koyar.

## 7. Request lifecycle'da üç katmanın yolu

```
1. Kullanıcı login olur
   → identity-service: kullanıcıyı doğrular
   → JWT üretir:
     {
       sub: user_id,
       installation_id: <bu cluster>,
       tenant_id: "uuid-kadikoy",     (tek tenant'lı kullanıcı)
       // VEYA
       tenant_ids: ["t1", "t2", ...], (multi-tenant kullanıcı)
       roles: ["teacher"],
       jti: <token id>
     }

2. Her API request gelir
   → Kong: JWT signature validate, geçerse forward
   → Servis: SecurityFilterChain JWT decode
   → Redis check: token + session aktif mi
   → Request context kurulur:
       MDC: tenant_id, user_id, correlation_id
       DB session: SET app.tenant_id veya SET app.tenant_ids

3. Controller @PreAuthorize
   → ScopeResolver.resolveScope(user_id) → effective scope
   → Policy: kullanıcı bu kaynağa erişebilir mi?

4. Repository sorgu
   → JPA query yazılır (tenant_id explicit eklenmez)
   → PostgreSQL RLS policy otomatik filter ekler
   → Defense-in-depth: app filter + RLS filter

5. Response
   → Audit log: kim ne yaptı, tenant_id ile
   → Metric: tenant-id label
   → Log: tenant-id field
```

## 8. Permission ve Scope farkı

Bu iki kavram karıştırılır:

| Kavram | Cevapladığı soru | Örnek |
|---|---|---|
| **Permission** | "Ne yapabilir?" | "Hüseyin attendance güncelleyebilir" |
| **Scope** | "Kimin/neyin üzerinde?" | "Sadece 11-A ve 12-B sınıfı için" |

Birlikte:
- Hüseyin'in `attendance:write` permission'ı var (RBAC).
- Hüseyin'in scope'u `class_ids=[11-A, 12-B]` (ABAC + organizational scope).
- 12-A'ya yoklama yazmaya kalkarsa permission var, scope yok → deny.
- 11-A için yoklama? Permission var, scope var → allow.

Detay: [Hibrit Authorization Model](../04-authentication-authorization/04-rbac-abac-hybrid.md).

## 9. Pratik tasarım kararları

### 9.1. tenant_id veri tipi → UUID v7
Sıralanabilir, global unique, IDOR koruması (sequential int'ten daha güvenli).

### 9.2. tenant_id NOT NULL her tenant-scoped tabloda
Default değer YOK. Migration ile geçişte temp default olabilir ama production'da NULL kabul edilmez.

### 9.3. Composite index lider sütun = tenant_id
```sql
CREATE INDEX idx_students_tenant_class ON students (tenant_id, class_id);
CREATE INDEX idx_attendance_tenant_date ON attendances (tenant_id, date_taken);
```

### 9.4. RLS her tenant-scoped tabloda aktif
```sql
ALTER TABLE students ENABLE ROW LEVEL SECURITY;
ALTER TABLE students FORCE ROW LEVEL SECURITY; -- table owner bile bypass etmesin
```

### 9.5. Migration script'lerde RLS atlama ihtiyacı için ayrı rol
Background job, ETL, migration için RLS bypass etmesi gereken senaryolarda **ayrı PostgreSQL role** (audit edilir, sınırlıdır).

### 9.6. tenant_id Kafka header'da
Event payload içinde değil, header'da. Çünkü routing/filtreleme önce header'a bakar, body'yi deserialize etmeden tenant'ı bilmek isteyebilirsin.

### 9.7. Multi-tenant kullanıcı için endpoint pattern
Tek tenant API: `/api/v1/students` — JWT'deki tek `tenant_id`'yi kullanır.
Multi-tenant API: `/api/v1/admin/students?tenant_id=...` — header veya query param ile aktif tenant seçilir, ama yine JWT'deki `tenant_ids` listesinden olmak zorunda.

## 10. Trade-off'lar ve riskler

| Konu | Trade-off |
|---|---|
| **DB per installation** | İzolasyon yüksek; ama tüm müşteriler için cross-customer analytics zor (sen sağlayıcı olarak ihtiyacın varsa external aggregation gerekir) |
| **Shared DB + tenant_id + RLS** | Operasyon kolay; ama "noisy tenant" performansı etkileyebilir (büyük tenant'lar küçükleri yavaşlatır) |
| **Multi-tenant kullanıcı (bölge müdürü)** | Esnek ama RLS policy karmaşıklaşır; sorgu planı değişebilir |
| **Scope resolution per request** | Net ve audit edilebilir; ama her request'te scope tablosu sorgusu (cache'lenmeli) |
| **JWT'de scope yok, sadece tenant_id** | JWT küçük kalır; ama scope için her request'te DB sorgusu gerekir |

## 11. Dikkat edilecek tuzaklar

- **Tenant_id'yi client'tan alma.** Header'a koysan bile **JWT'deki ile karşılaştır**. Client manipüle edebilir.
- **RLS policy unutmak.** Yeni bir tenant-scoped tablo eklendiğinde RLS açılmazsa application bug'ı verinin hepsini gösterebilir. CI gate yaz: "RLS olmayan tenant_id'li tablo varsa fail."
- **Background job'da tenant context yok.** Outbox relay, scheduled job, Temporal worker → tenant context manuel set edilmeli.
- **Index sırası yanlış.** `INDEX (class_id, tenant_id)` yerine `INDEX (tenant_id, class_id)` olmalı, çünkü her sorgu önce tenant filtrelemek istiyor.
- **Multi-tenant kullanıcı için ayrı policy unutmak.** Tek tenant policy hazır, multi-tenant kullanıcı geldiğinde sessiz fail.

## 12. Diğer konularla ilişkisi

- [Domain Servisleri](./02-domain-services-overview.md) — bu modelin servislere yansıması
- [Hibrit Authorization (RBAC + ABAC)](../04-authentication-authorization/04-rbac-abac-hybrid.md) — scope resolver detayı
- [Tenant-based RLS](../database-architecture/tenant-based-rls-policy-design) — PostgreSQL RLS implementation
- [Composite Index Design](../database-architecture/postgresql-index-ordering-and-query-design) — tenant_id-first index
- [Stateful Token Modeli](../04-authentication-authorization/01-stateful-token-model.md) — JWT'de tenant claim

## 13. Daha derine inmek için

- PostgreSQL: [Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- Microsoft: [Multi-tenant SaaS database tenancy patterns](https://learn.microsoft.com/azure/azure-sql/database/saas-tenancy-app-design-patterns)
- Search keywords:
  - `multi tenant database design patterns`
  - `postgresql rls multi tenant`
  - `tenant per database vs shared database`
  - `multi-tenant authorization scope resolver`
  - `hierarchical permission school class student`

## 14. Sözlük

- **Installation** — sistemi satın alan kurumun kendi K8s + altyapı kurulumu (örn. Ömer Okulları)
- **Tenant** — installation içindeki bağımsız operasyonel birim (örn. Kadıköy Şubesi)
- **Scope** — kullanıcının tenant içinde görebileceği veri kapsamı (school / class / student)
- **RLS (Row-Level Security)** — PostgreSQL'in satır seviyesinde otomatik filtreleme özelliği
- **tenant_id** — bir satırın hangi tenant'a ait olduğunu gösteren UUID
- **ScopeResolver** — bir kullanıcının effective scope'unu hesaplayan servis
- **Multi-tenant kullanıcı** — birden çok tenant'ta aktif olabilen kullanıcı (bölge müdürü gibi)
