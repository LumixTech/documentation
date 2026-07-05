# Katkı Rehberi — Lumix Mono-repo

Bu dosya **sözleşme (contract) standartlarını** toplar: Protobuf şemaları, versiyonlama,
şema kayıt akışı. Git/branch/commit akışı ayrıdır → [docs/git-workflow.md](docs/git-workflow.md)
ve [CLAUDE.md](CLAUDE.md).

---

## Protobuf & Şema Yönetimi (Apicurio Registry)

Lumix'te gRPC (sync) ve Kafka (async) **tek şema dili** kullanır: **Protobuf**.
Tüm şemalar [Apicurio Registry](infra/apicurio/README.md)'de versiyonlanır ve
uyumluluk (`BACKWARD`) açısından denetlenir.

- **Kaynak (source of truth):** `.proto` dosyaları repo'da (`**/src/main/proto/**`).
- **Codegen:** `protobuf-gradle-plugin` (`.proto` → Java, `./gradlew build`).
- **Registry:** `.proto` → Apicurio, `./gradlew schemaRegister` ile.
- **Denetim:** iki katman → CI'da `buf` (derleme-zamanı) + Apicurio `BACKWARD` (çalışma-zamanı).

### Versiyonlama konvansiyonu

Şema kimlikleri (Apicurio artifactId) ve event/RPC proto dosya adları şu kalıbı izler:

```
<service>.<aggregate>.<event>.v<major>
```

| Parça | Anlam | Örnek |
|---|---|---|
| `<service>` | Şemanın sahibi mikroservis | `identity`, `payment`, `notification` |
| `<aggregate>` | DDD aggregate / kaynak | `user`, `invoice`, `session` |
| `<event>` | Olay / mesaj adı (geçmiş zaman, event ise) | `created`, `updated`, `deleted` |
| `v<major>` | **Major** şema versiyonu (kırıcı değişimde artar) | `v1`, `v2` |

**Örnekler:**

```
identity.user.created.v1
identity.user.updated.v1
payment.invoice.paid.v1
notification.email.requested.v1
```

- **Dosya adı** aynıdır: `identity.user.created.v1.proto` → Apicurio artifactId = dosya adı (uzantısız).
- **Minor/patch** (geriye uyumlu ekleme) → **v değişmez**; Apicurio içinde yeni *version* olur.
- **Kırıcı değişiklik** (alan silme, tip/tag değiştirme) → **yeni dosya**: `...created.v2.proto`.
  Eski `v1` yaşamaya devam eder; consumer'lar kendi hızında geçer.

### Proto yazım kuralları

- `syntax = "proto3";`
- `package <service>.<aggregate>.v<major>;` — örn. `package identity.user.v1;`
- `option java_package = "com.lumix.<service>.<aggregate>.grpc.v<major>";`
- Alan **tag numaralarını asla değiştirme/yeniden kullanma** (wire uyumluluğu bunlara bağlı).
- Alan **silme** yerine `reserved` kullan: `reserved 3; reserved "old_field";`
- Yeni alanları **yeni tag** ile ekle (BACKWARD güvenli).

### `BACKWARD` uyumluluk — nelere izin var?

`BACKWARD` = *yeni şema ile yazılan veriyi, eski şemayı bilen consumer okuyabilir.*
Consumer'lar önce upgrade edilir. Protobuf'ta güvenli/güvensiz değişimler:

| Değişiklik | BACKWARD? |
|---|---|
| Yeni alan ekleme (yeni tag) | ✅ güvenli |
| Alanı `reserved` yapıp bırakma | ✅ güvenli |
| Alan tag numarasını değiştirme | ❌ kırıcı |
| Alan tipini değiştirme | ❌ kırıcı |
| Zorunlu semantik/rename (tag sabit) | ⚠️ dikkat (isim gen kodu etkiler) |

---

## Geliştirici akışı (şema değiştirirken)

1. `.proto`'nu ekle/güncelle (`**/src/main/proto/**`, konvansiyona uygun ad).
2. **Codegen + build:** `cd backend && ./gradlew build` (kod üretilir, derlenir).
3. **Lokal uyumluluk ön-kontrolü:**
   ```bash
   buf lint backend
   buf breaking backend --against ".git#subdir=backend"     # main'e göre
   # (registry ayaktaysa) ./gradlew schemaValidate           # dryRun, yazmaz
   ```
4. Commit + MR aç. **CI** `schema:validate` job'u lint + breaking'i tekrar çalıştırır (kapı).
5. MR merge sonrası (veya deploy adımında) şemalar registry'e yazılır:
   ```bash
   ./gradlew schemaRegister -PapicurioUrl=http://<registry-host>:8080
   ```
   Uyumsuz bir versiyon `409` ile reddedilir — registry son savunma hattıdır.

### Faydalı komutlar

| İş | Komut |
|---|---|
| Codegen + derleme | `./gradlew build` |
| Şemaları register et | `./gradlew schemaRegister [-PapicurioUrl=...] [-PschemaGroup=...]` |
| Uyumluluk ön-kontrol (yazmadan) | `./gradlew schemaValidate` |
| Kurulum smoke testi | `./gradlew schemaSmokeTest`  ·  `scripts/schema-smoke.sh` |
| Lint | `buf lint backend` |
| Breaking-change | `buf breaking backend --against ".git#subdir=backend"` |

Registry'i ayağa kaldırma / sunucu kurulumu: [infra/apicurio/README.md](infra/apicurio/README.md).
