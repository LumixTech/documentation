Aşağıda senin notlarından hareketle, **DocuRUs için MD dosyası üretebileceğin şekilde** her konuya uygun:

- **konu başlığı**
- **amaç / kapsam**
- **içerik detayları**
- **teknik terimler sözlüğü bölümü**
- **giriş / gelişme / sonuç akışı**
- **öğrenmek ve derinleşmek için keyword seti**
- **senin notlarından eklenmesi gereken özel vurgu**

hazırladım.

Bence burada en doğru yaklaşım şu olur:
Her `.md` dosyası aynı iskeleti kullansın. Böylece hem düzenli olur hem de ileride blog’a, wiki’ye ya da onboarding dokümanına çevirmek kolaylaşır.

---

# Önerdiğim standart MD şablonu

Her konu için dosya yapın buna benzeyebilir:

```md
# Konu Başlığı

## 1. Giriş

- Bu konu neden önemli?
- Üründe hangi problemi çözüyor?
- Yanlış tasarım yapılırsa ne bozulur?

## 2. Temel Kavramlar

- Kısa tanımlar
- Sistemdeki rolü

## 3. Problem

- Ürün geliştirirken karşımıza çıkan gerçek problem
- Neden klasik yaklaşım yetmiyor?

## 4. Yaklaşım / Tasarım

- Seçilen mimari yaklaşım
- Alternatifler
- Neden bu model seçildi?

## 5. Sektör Standardı Akış

- Best practice mantığı
- Sequence / lifecycle / policy anlatımı
- Pseudocode / örnek karar akışı

## 6. Bizim Notlarımız / Kararlar

- Takıma özel kararlar
- Parametrik davranışlar
- Trade-off’lar

## 7. Teknik Terimler Sözlüğü

- Kavram -> kısa açıklama

## 8. Sonuç

- Nihai öneri
- Uygulamada dikkat edilmesi gerekenler

## 9. Araştırma Keywordleri

- İngilizce arama terimleri
```

---

# Sana uygun klasörleme önerisi

```text
security-compliance/
  01-auth-jwt-refresh-flow.md
  02-session-device-refresh-lifecycle.md
  03-hybrid-rbac-abac-authorization.md
  04-kvkk-gdpr-legal-basis-consent.md
  05-audit-log-design.md
  06-retention-anonymization-dsar.md

database-architecture/
  07-postgresql-index-ordering-and-query-design.md
  08-postgresql-extensions-evaluation.md
  09-tenant-based-rls-policy-design.md
  10-read-write-replica-strategy.md
  14-flyway-zero-downtime-migration-strategy.md

frontend-architecture/
  11-parameterized-id-visible-routing-strategy.md
  12-smart-navigation-and-redux-route-state.md

engineering-notes/
  13-product-problems-and-solution-decisions.md
```

---

# 1) Spring Security Auth Akışı (JWT + Refresh Token)

## Dosya adı

`01-auth-jwt-refresh-flow.md`

## Konu başlığı

**Spring Security Authentication Flow: JWT, Refresh Token ve Stateless/Stateful Denge**

## Girişte anlatılacaklar

- Auth akışı neden sistemin güvenlik kapısıdır
- Neden sadece access token yeterli değildir
- Refresh token olmadan kullanıcı deneyimi ve güvenlik dengesi neden bozulur

## Gelişmede anlatılacaklar

- Login endpoint akışı
- Access token üretimi
- Refresh token üretimi
- Refresh endpoint akışı
- Logout / logout-all mantığı
- Token rotation
- Token revoke
- Access token stateless, refresh token stateful yaklaşımı

## Sonuçta bağlanacak nokta

- Hibrit modelin güvenlik ve yönetilebilirlik avantajı
- Sadece JWT değil, oturum yönetimiyle birlikte düşünülmesi gerektiği

## Teknik terimler sözlüğü

- Authentication
- Authorization
- Access Token
- Refresh Token
- Stateless Auth
- Stateful Session
- Token Rotation
- Token Revocation
- Expiration
- Claims
- Subject
- Issuer
- Audience
- Spring Security Filter Chain

## Senin notundan özel vurgu

- **Hibrit kullanır: Access token stateless, Refresh token stateful, Session/device stateful**

## Öğrenmek için keywordler

- `spring security jwt refresh token flow`
- `access token refresh token best practices`
- `stateless authentication vs stateful session`
- `refresh token rotation`
- `spring security login refresh logout architecture`
- `jwt blacklist vs refresh token table`
- `spring boot auth flow sequence diagram`

## Bu konuda alt başlık önerileri

- JWT neden tek başına yeterli değil?
- Refresh token neden DB’de tutulmalı?
- Logout gerçekten neyi siler?
- Token rotation neden kritik?
- Stateless API tasarımında stateful parçalar neden kaçınılmaz?

---

# 2) Session, Device ve Refresh Token Yaşam Döngüsü

## Dosya adı

`02-session-device-refresh-lifecycle.md`

## Konu başlığı

**Session, Device ve Refresh Token Lifecycle Tasarımı**

## Giriş

- JWT bilmenin neden yetmediği
- Cihaz bazlı oturum ihtiyacının ürünle birlikte kaçınılmaz hale gelmesi
- Logout, logout-all, trusted devices gibi ihtiyaçların iş kuralı haline gelmesi

## Gelişme

- USER_SESSIONS tablosu neden gerekir
- REFRESH_TOKENS tablosu neden gerekir
- Her login’de yeni session mı, yeni device mı?
- Token rotation yapılınca eski refresh token ne olur?
- Logout bir cihazı mı kapatır, tüm cihazları mı?
- Revoke akışı nasıl işler?
- Trusted device mantığı ne zaman eklenir?

## Sonuç

- Session ve token’ın aynı şey olmadığı
- Device-aware session yönetiminin kurumsal ürünlerde neden standart olduğu

## Teknik terimler sözlüğü

- Session
- Device Session
- Refresh Token Family
- Revoke
- Rotation
- Trusted Device
- Session Expiration
- Idle Timeout
- Absolute Timeout
- Device Fingerprint
- Token Replay
- Session Invalidation

## Senin notundan özel vurgu

- `USER_SESSIONS + REFRESH_TOKENS`
- `logout-all`
- `revoke tokens`
- cihaz bazlı yaşam döngüsü

## Keywordler

- `refresh token lifecycle design`
- `device based session management`
- `logout all devices design`
- `refresh token revoke best practice`
- `session table design auth system`
- `trusted device authentication model`

## Alt başlık önerileri

- Session ve token arasındaki fark
- Tek kullanıcı çok cihaz nasıl yönetilir?
- Refresh token reuse tespiti nasıl yapılır?
- Session invalidation hangi event’lerde yapılmalı?

---

# 3) Hibrit RBAC + ABAC Yetkilendirme Modeli

## Dosya adı

`03-hybrid-rbac-abac-authorization.md`

## Konu başlığı

**Hibrit Yetkilendirme Modeli: RBAC + ABAC + User/Role/Common Permission + Allow/Deny**

## Giriş

- Rol bazlı yetkilendirmenin tek başına neden yetersiz kaldığı
- Kurumsal ürünlerde istisnaların neden çok olduğu
- “Sadece role bakarak karar vermek” neden kırılgan

## Gelişme

- RBAC nedir
- ABAC nedir
- Neden hibrit model gerekir
- `role_permission`
- `user_permission`
- `common permissions`
- `allow / deny` precedence
- Endpoint, servis, use case ve domain seviyesinde yetki kontrolü
- Method security / policy evaluation yaklaşımı
- Tenant, department, ownership gibi attribute’ların karara etkisi

## Sonuç

- Hibrit modelin esneklik ve kontrol dengesi
- Permission sistemi kurulurken “öncelik sırası”nın net tanımlanması gerektiği

## Teknik terimler sözlüğü

- RBAC
- ABAC
- Permission
- Role
- Principal
- Policy Evaluation
- Resource-based Authorization
- Method Security
- PreAuthorize
- Allow Rule
- Deny Rule
- Precedence
- Ownership
- Tenant Scope
- Contextual Authorization

## Senin notundan özel vurgu

- **HİBRİT MODEL OLACAK**
- `user_permission + role_permission + common permissions + allow/deny`

## Keywordler

- `hybrid authorization model rbac abac`
- `rbac vs abac enterprise application`
- `allow deny permission precedence`
- `user permission override role permission`
- `common permissions authorization design`
- `spring method security preauthorize custom permission evaluator`
- `resource based authorization spring boot`

## Alt başlık önerileri

- RBAC nerede yeterli, nerede yetersiz?
- ABAC sisteme ne kazandırır?
- Deny her zaman allow’u ezer mi?
- User override role mantığı nasıl kurulmalı?
- Common permission ne demek?

---

# 4) KVKK/GDPR: Legal Basis vs Consent

## Dosya adı

`04-kvkk-gdpr-legal-basis-consent.md`

## Konu başlığı

**KVKK/GDPR Temelleri: Legal Basis, Consent ve Veri İşleme Amacı**

## Giriş

- Her veri işleme açık rıza gerektirmez
- Consent ile legal basis’in karıştırılmasının ürün akışını nasıl bozduğu
- Privacy by design neden baştan düşünülmeli

## Gelişme

- Legal basis nedir
- Consent nedir
- Veri işleme amacı nasıl tanımlanır
- Hangi akışlar açık rıza gerektirir
- Hangi akışlar sözleşme, meşru menfaat, yasal yükümlülük gibi dayanaklarla yürür
- Aydınlatma metni ile rıza metni arasındaki fark
- Sistem tarafında purpose-based data processing nasıl modellenir

## Sonuç

- Ürün ekiplerinin “her şeyi consent’e bağlama” hatasından kaçınması
- Hukuki dayanak matrisi ile ürün davranışının uyumlu tasarlanması

## Teknik terimler sözlüğü

- KVKK
- GDPR
- Legal Basis
- Explicit Consent
- Legitimate Interest
- Contractual Necessity
- Legal Obligation
- Privacy Notice
- Data Controller
- Data Processor
- Processing Purpose
- Data Minimization

## Senin notundan özel vurgu

- Veri işleme amaçları ve hukuki dayanak matrisi oluşturma ihtiyacı

## Keywordler

- `gdpr legal basis vs consent`
- `kvkk açık rıza hukuki sebep farkı`
- `data processing purpose matrix`
- `privacy notice vs consent form`
- `lawful basis for processing personal data`
- `privacy by design product development`

## Alt başlık önerileri

- Her veri işleme için consent gerekir mi?
- Ürün akışında legal basis nasıl haritalanır?
- Marketing consent ile operational processing nasıl ayrılır?

---

# 5) Audit Log Tasarımı

## Dosya adı

`05-audit-log-design.md`

## Konu başlığı

**Audit Log Tasarımı: Kim, Ne Zaman, Ne Yaptı?**

## Giriş

- Audit log neden normal application log değildir
- Özellikle sağlık, ödeme, PDR, yetki değişimi gibi alanlarda neden kritik
- “Kim ne yaptı” sorusunun teknik olarak nasıl cevaplandığı

## Gelişme

- Append-only yaklaşım
- Immutable audit mantığı
- Correlation ID
- Actor, action, target, timestamp, tenant, ip, device gibi alanlar
- Kritik aksiyonlar listesi
- Ne loglanmalı, ne loglanmamalı
- Sensitive veri maskelenmeli mi?
- Audit log ile event log farkı
- DB tablosu mu, stream mi, external store mu?

## Sonuç

- Audit log’un compliance kadar incident analysis için de önemli olduğu
- Tasarımın baştan yapılması gerektiği

## Teknik terimler sözlüğü

- Audit Log
- Immutable Log
- Append-only
- Correlation ID
- Actor
- Event
- Action Type
- Target Entity
- Before/After Snapshot
- Tamper Resistance
- Compliance Logging

## Keywordler

- `audit log design best practices`
- `append only audit table`
- `immutable audit logging architecture`
- `who did what when logging`
- `correlation id audit tracing`
- `sensitive data masking in audit logs`

## Alt başlık önerileri

- Audit log ile application log farkı
- Hangi aksiyonlar kritik sayılır?
- Audit kaydı silinebilir mi?
- Before/after snapshot tutulmalı mı?

---

# 6) Retention, Anonimleştirme ve DSAR

## Dosya adı

`06-retention-anonymization-dsar.md`

## Konu başlığı

**Retention, Anonymization ve DSAR Workflow Tasarımı**

## Giriş

- Silme ve anonimleştirmenin neden ürünün en zor uyumluluk alanlarından biri olduğu
- Bir kaydı “veritabanından silmek” ile hukuki yükümlülüğü yerine getirmenin aynı şey olmadığı

## Gelişme

- Retention policy nedir
- Hangi veri ne kadar süre saklanır
- Soft delete, hard delete, anonymization farkları
- DSAR başvurusu nasıl alınır
- Başvurunun doğrulanması
- Onay süreci
- Anonimleştirme job’ı
- Audit kaydı
- Geri döndürülemezlik
- Downstream sistemler nasıl etkilenir

## Sonuç

- Veri yaşam döngüsünün baştan modellenmesi gerektiği
- DSAR’ın sadece hukuk işi değil, doğrudan teknik mimari konusu olduğu

## Teknik terimler sözlüğü

- Data Retention
- Data Erasure
- Right to be Forgotten
- DSAR
- Anonymization
- Pseudonymization
- Hard Delete
- Soft Delete
- Retention Policy
- Legal Hold
- Data Subject Request

## Keywordler

- `dsar workflow design`
- `right to be forgotten technical implementation`
- `data retention policy architecture`
- `anonymization vs pseudonymization`
- `data erasure job design`
- `audit trail for deletion requests`

## Alt başlık önerileri

- Ne zaman silinir, ne zaman anonimleştirilir?
- DSAR teknik olarak nasıl yürütülür?
- Audit log silme talebinden muaf olabilir mi?

---

# 7) PostgreSQL Index Sırası ve Sorgu Tasarımı

## Dosya adı

`07-postgresql-index-ordering-and-query-design.md`

## Konu başlığı

**PostgreSQL Composite Index Sırası: Neden Kolon Sırası Kritik?**

## Giriş

- Index’in sadece “ek performans” değil, sorgu stratejisinin bir parçası olduğu
- Yanlış index sırasının neden pahalı sorgulara yol açtığı

## Gelişme

- B-tree mantığı
- Telefon rehberi benzetmesi
- `INDEX (tenant_id, user_id)` neden bu sırada anlamlı
- Sol prefix mantığı
- DB önce `tenant_id` bölümüne gider, sonra `user_id`
- İlk filtre kolonunun kritikliği
- Query planner beklentisi
- Multi-tenant sistemlerde tenant-first yaklaşımı

## Sonuç

- Index tasarımının business query pattern’e göre yapılması gerektiği
- “Kolonları rastgele koymak” yerine erişim modeline göre sıralanması gerektiği

## Teknik terimler sözlüğü

- B-tree Index
- Composite Index
- Selectivity
- Cardinality
- Query Planner
- Index Scan
- Sequential Scan
- Prefix Matching
- Multi-tenant Filtering

## Senin notundan özel vurgu

- **B-tree index telefon rehberi gibi**
- **İlk başta tenant_id’ye göre süzme yapılacak**

## Keywordler

- `postgresql composite index column order`
- `btree index leftmost prefix`
- `tenant_id user_id index design`
- `postgresql query planner index usage`
- `multi tenant database indexing strategy`

## Alt başlık önerileri

- Neden sıra önemli?
- `(tenant_id, user_id)` ile `(user_id, tenant_id)` farkı
- Index her sorguda kullanılır mı?

---

# 8) PostgreSQL Extension / Plugin Değerlendirmesi

## Dosya adı

`08-postgresql-extensions-evaluation.md`

## Konu başlığı

**PostgreSQL Extension Değerlendirmesi: İhtiyaç Bazlı Plugin Seçimi**

## Giriş

- Extension eklemek neden sadece teknik değil operasyonel karardır
- “Güzel görünüyor” diye extension eklenmemesi gerektiği

## Gelişme

- PostgreSQL extension nedir
- Hangi problemleri çözer
- Örnek değerlendirme kriterleri
- Güvenlik etkisi
- Performans etkisi
- Backup / restore etkisi
- Cloud uyumluluğu
- Versiyon bağımlılığı
- `pg_cron`, `uuid-ossp`, `pg_stat_statements`, `pgcrypto`, `ltree`, `pg_partman` gibi örnek sınıflar
- Senin notundaki plugin araştırma yaklaşımı

## Sonuç

- Extension seçiminin problem-driven yapılması gerektiği
- İhtiyaç olmayan extension’ın bakım yükü olduğu

## Teknik terimler sözlüğü

- PostgreSQL Extension
- Shared Library
- Migration Compatibility
- Operational Risk
- Managed DB Compatibility
- Performance Monitoring
- Scheduling Extension

## Senin notundan özel vurgu

- `postgresql pluginleri incelenip ihtiyaç olanlar eklenecek`

## Keywordler

- `postgresql extension best practices`
- `postgresql plugins production checklist`
- `pg_stat_statements use case`
- `pgcrypto postgres`
- `postgresql extension compatibility managed service`

## Alt başlık önerileri

- Extension seçerken neye bakılır?
- Her extension prod’a uygun mudur?
- Geliştirme kolaylığı mı, operasyon kolaylığı mı?

---

# 9) Frontend URL Path’lerde ID Görünürlüğü ve Parametrik Routing

## Dosya adı

`11-parameterized-id-visible-routing-strategy.md`

## Konu başlığı

**Frontend Routing Stratejisi: URL’de ID Görünsün mü, Sistem Parametresiyle mi Yönetilsin?**

## Giriş

- URL tasarımının sadece frontend konusu olmadığı
- Güvenlik, UX ve paylaşılabilirlik açısından etkileri

## Gelişme

- ID’li route vs idsiz route
- Sistem parametresiyle yönetme fikri
- Redux root state üzerinden route davranışı
- In-page route / navigate kararları
- SmartNavigation component fikri
- Parametrik route generation
- Backward compatibility
- Deep-linking etkisi
- Frontend config-driven navigation yaklaşımı

## Sonuç

- Routing kararının merkezi ve parametrik yönetilmesi gerektiği
- Hard-coded navigation’ın ileride sorun çıkardığı

## Teknik terimler sözlüğü

- Route Param
- Deep Link
- Navigation State
- Redux Root State
- Route Resolution
- Config-driven UI
- URL Canonicalization

## Senin notundan özel vurgu

- `id gözükme olayı sistem parametrelerinden yönetilecek`
- `reduxa state rootreducer içinden iletilecek`
- `smartnavigation component olabilir`

## Keywordler

- `react conditional routing by config`
- `redux driven navigation state`
- `url param visibility frontend architecture`
- `smart navigation component react`
- `config based route generation`

## Alt başlık önerileri

- URL’de ID göstermek ne zaman doğru?
- Parametrik routing neden faydalı?
- Redux route kontrolü overengineering mi?

---

# 10) Tenant Bazlı RLS Policy Tasarımı

## Dosya adı

`09-tenant-based-rls-policy-design.md`

## Konu başlığı

**Row-Level Security (RLS) ile Tenant Bazlı Veri İzolasyonu**

## Giriş

- Multi-tenant mimaride uygulama seviyesinde filtrelemenin neden tek başına güvenli olmadığı
- RLS’in neden ikinci savunma hattı olduğu

## Gelişme

- PostgreSQL RLS nedir
- Policy nedir
- Tenant bazlı erişim nasıl kısıtlanır
- Read policy
- Write policy
- Session context / current_setting yaklaşımı
- Uygulama katmanıyla DB policy uyumu
- Hatalı policy riskleri
- Admin / system actor senaryoları

## Sonuç

- Tenant izolasyonunun sadece kodla değil DB seviyesinde de güvenceye alınması gerektiği

## Teknik terimler sözlüğü

- RLS
- Policy
- Tenant Isolation
- Session Variable
- current_setting
- USING Clause
- WITH CHECK Clause
- Least Privilege

## Senin notundan özel vurgu

- `RLS için policy yazılacak tenant id bazlı olaraktan`

## Keywordler

- `postgresql row level security multi tenant`
- `tenant based rls policy`
- `postgresql current_setting tenant_id`
- `rls using with check example`
- `application level auth vs db level rls`

## Alt başlık önerileri

- RLS neden gerekli?
- Sadece service layer filtresi neden yetmez?
- Read ve write policy farkı nedir?

---

# 11) Read/Write Replica Stratejisi

## Dosya adı

`10-read-write-replica-strategy.md`

## Konu başlığı

**Read/Write Replica Stratejisi: Ölçeklenebilirlik, Tutarlılık ve Yönlendirme Kuralları**

## Giriş

- Replica kullanımının neden sadece performans kararı olmadığı
- Tutarlılık problemlerinin neden kritik olduğu

## Gelişme

- Primary / replica mantığı
- Hangi sorgular replica’ya gider
- Hangi işlemler primary’de kalır
- Replication lag
- Read-after-write problemi
- Consistency beklentisi
- Transactional flow’larda replica riskleri
- Routing rules
- Failover ve health check mantığı

## Sonuç

- Replica kullanımının “kuralla yönetilen” bir strateji olması gerektiği

## Teknik terimler sözlüğü

- Primary Database
- Read Replica
- Replication Lag
- Read-after-write Consistency
- Eventual Consistency
- Query Routing
- Failover

## Senin notundan özel vurgu

- `read write replica için kural bakmamız gerek`

## Keywordler

- `read write replica best practices`
- `postgresql read replica consistency`
- `read after write problem replica`
- `db query routing primary replica`
- `eventual consistency database replicas`

## Alt başlık önerileri

- Her SELECT replica’ya gider mi?
- Replica ne zaman tehlikeli olur?
- Auth ve payment gibi akışlar replica’da çalışır mı?

---

# 12) Flyway Migration ve Zero-Downtime DB Değişiklikleri

## Dosya adı

`14-flyway-zero-downtime-migration-strategy.md`

## Konu başlığı

**Flyway Migration ve Zero-Downtime DB Değişiklikleri: Backward-Compatible Expand/Contract Yaklaşımı**

## Giriş

- Production'da veritabanı değişikliğinin neden en riskli release adımlarından biri olduğu
- SQL doğru olsa bile rollout-safe tasarım yoksa canlı sistemin nasıl bozulabileceği

## Gelişme

- Flyway migration disiplini (versiyonlama, sıralı çalıştırma, rollback stratejisi)
- Backward-compatible schema değişikliği prensibi
- Expand and contract pattern
- Dual-read / dual-write yaklaşımı
- Backfill (batch) stratejisi
- Contract adımına geçiş için gözlem ve parity kontrolü
- PostgreSQL lock etkisi ve düşük-lock teknikleri
- 2 aşamalı kolon rename senaryosu
- 2 aşamalı tablo split senaryosu

## Sonuç

- Veritabanı değişikliklerinin tek migration dosyasıyla değil release akışıyla tasarlanması gerektiği
- Flyway + expand/contract kombinasyonunun production güvenliği için zorunlu olduğu

## Teknik terimler sözlüğü

- Flyway
- Zero-downtime migration
- Backward-compatible schema change
- Expand and contract
- Dual-write
- Backfill
- Contract phase
- Data parity

## Senin notundan özel vurgu

- `flyway migrations`
- `backward compatible schema changes`
- `expand and contract pattern`
- `2 aşamalı kolon rename / tablo split migration senaryosu`

## Keywordler

- `flyway zero downtime migration strategy`
- `expand contract database migration pattern`
- `backward compatible schema changes`
- `postgresql column rename without downtime`
- `table split migration dual write backfill`

## Alt başlık önerileri

- Expand ve contract aynı release'te yapılır mı?
- Kolon rename neden doğrudan `RENAME COLUMN` ile geçilmemeli?
- Table split geçişinde veri parity nasıl doğrulanır?

---

# 13) Ürün Geliştirirken Karşılaşılan Problemler ve Çözüm Kararları

## Dosya adı

`13-product-problems-and-solution-decisions.md`

## Konu başlığı

**Ürün Yazarken Karşılaşılan Problemler ve Bulduğumuz Yaklaşımlar**

## Giriş

- Teorik bilgi ile ürün geliştirme pratiği arasındaki fark
- Blog yazısı mantığında neden karar hikâyesi anlatılmalı

## Gelişme

- Karşılaşılan problem
- İlk düşünülen çözüm
- Neden yeterli gelmedi
- Seçilen yaklaşım
- Trade-off
- Sektör standardı ile bizim uyarlamamız
- Öğrenilen ders

## Sonuç

- Dokümantasyonun sadece “ne yaptık” değil “neden böyle yaptık” anlatması gerektiği

## Teknik terimler sözlüğü

Bu dosyada sözlük bölümünü ortak tutabilirsin:

- Trade-off
- Scalability
- Security Boundary
- Consistency
- Tenant Isolation
- Policy
- Session Lifecycle
- Idempotency
- Config-driven Design

## Senin notundan özel vurgu

- `Ürün yazılırken karşımıza çıkan problemler ve bu problemler için nasıl yaklaşım ve çözüm bulduğumuzun blog yazıları`

## Keywordler

- `architecture decision record examples`
- `engineering blog problem solution format`
- `tradeoff based technical writing`
- `real world software architecture decisions`

---

# “Değiştirmemiz gereken keywordler” kısmını nasıl düşünmelisin?

Buradaki “keyword”leri ikiye ayır:

## 1. Araştırma keywordleri

Google, docs, blog, GitHub, Stack Overflow, Medium vs araştırırken kullanacağın İngilizce terimler.
Yukarıda bunları verdim.

## 2. Doküman içinde standardize etmen gereken terimler

Bazı şeyleri her dosyada aynı isimle kullanman lazım. Bunları değişken bırakma.

Önerdiğim standardizasyon:

- `role_permission`
- `user_permission`
- `common_permission`
- `allow`
- `deny`
- `access token`
- `refresh token`
- `session`
- `device session`
- `tenant_id`
- `RLS policy`
- `audit log`
- `retention policy`
- `anonymization`
- `DSAR workflow`
- `smart navigation`
- `read replica`
- `write primary`

Yani mesela bir yerde `role-permission`, başka yerde `role permission`, başka yerde `role_permission_map` dersen belge dağılır.
Başta bir **Terminology Convention** dosyası da açabilirsin.

---

# Ek olarak ayrı bir sözlük dosyası açmanı öneririm

## Dosya adı

`00-glossary.md`

Bunun içine tüm ortak terimleri koy:

```md
# Glossary

## Access Token

Kısa ömürlü, stateless doğrulama token'ı.

## Refresh Token

Yeni access token üretmek için kullanılan, genellikle stateful izlenen token.

## RBAC

Role-Based Access Control.

## ABAC

Attribute-Based Access Control.

## RLS

Row-Level Security.

## DSAR

Data Subject Access Request.

## Audit Log

Kim, ne zaman, ne yaptı bilgisini değiştirilemez biçimde izlemeye yarayan kayıt yapısı.
```

Bu çok işine yarar çünkü her dokümanda tekrar tekrar açıklamak zorunda kalmazsın.

---

# Sana net bir başlangıç sırası da vereyim

İlk yazılacak 5 dosya bence şu sırada olsun:

1. `01-auth-jwt-refresh-flow.md`
2. `02-session-device-refresh-lifecycle.md`
3. `03-hybrid-rbac-abac-authorization.md`
4. `09-tenant-based-rls-policy-design.md`
5. `07-postgresql-index-ordering-and-query-design.md`

Çünkü bunlar birbirine en çok bağlı konular:

- auth
- session
- permission
- tenant isolation
- DB erişim modeli

---

# Bonus: başlıkları daha “dokümantasyon dostu” hale getirilmiş kısa liste

- Authentication Flow with JWT and Refresh Tokens
- Session and Device Lifecycle Design
- Hybrid Authorization Model with RBAC and ABAC
- Legal Basis and Consent in KVKK/GDPR
- Audit Logging Strategy
- Data Retention, Anonymization, and DSAR Workflow
- PostgreSQL Composite Index Ordering
- PostgreSQL Extension Evaluation
- Tenant-based Row-Level Security Policies
- Read/Write Replica Decision Rules
- Flyway Migrations and Zero-Downtime DB Changes
- Config-driven URL and Navigation Strategy
- Product Engineering Decisions and Solution Notes
