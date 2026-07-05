# Apicurio Registry — Şema Kayıt Sunucusu (Protobuf)

Lumix'te gRPC ve Kafka **tek Protobuf schema dili** kullanır. Apicurio Registry bu
şemaların **tek kaynağı (source of truth)**, versiyonlayıcısı ve **uyumluluk bekçisidir**.
Karar: [documentation `02-technology-stack-decisions.md` §5](../../../documentation/docs/00-overview/02-technology-stack-decisions.md).

- **Compatibility mode:** `BACKWARD` (consumer önce upgrade edilir — en yaygın strateji)
- **Storage:** PostgreSQL (kalıcı)
- **API:** REST v3 → `http://<host>:8080/apis/registry/v3`
- **Web konsolu:** `http://<host>:8888`

> Bu klasör yalnızca **çalıştırma tanımı**dır (compose + env). Şemaları register/validate
> etmek geliştirici/CI tarafındadır — bkz. [CONTRIBUTING.md](../../CONTRIBUTING.md) ve
> `./gradlew schemaRegister|schemaValidate|schemaSmokeTest`.

---

## 1. Sunucuda kurulum (tek seferlik)

**Gereksinim:** Docker Engine + Docker Compose v2 (Ubuntu Server 24.04). Yoksa:

```bash
# Docker resmi kurulum betiği (Ubuntu)
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"   # oturumu kapatıp açın
docker compose version            # v2 doğrulaması
```

**Registry'i ayağa kaldırma:**

```bash
cd campus/infra/apicurio
cp .env.example .env
# .env içindeki POSTGRES_PASSWORD'ü güçlü bir değerle değiştirin:
#   openssl rand -base64 24
nano .env

docker compose --env-file .env up -d
docker compose ps                 # 3 servis: db (healthy), registry (healthy), ui
docker compose logs -f apicurio-registry   # "started" / "listening on :8080" görün
```

İlk açılışta Apicurio, Postgres şemasını (tabloları) **otomatik** oluşturur; ekstra migration yok.

---

## 2. BACKWARD kuralını global olarak set et (tek seferlik)

Compose kuralı env ile set etmez; ilk açılıştan sonra **bir kez** global COMPATIBILITY
kuralını `BACKWARD` yapın. Bundan sonra her yeni şema versiyonu bu kurala göre denetlenir:

```bash
curl -sS -X POST http://localhost:8080/apis/registry/v3/admin/rules \
  -H "Content-Type: application/json" \
  -d '{"ruleType":"COMPATIBILITY","config":"BACKWARD"}'

# Doğrula (BACKWARD dönmeli):
curl -sS http://localhost:8080/apis/registry/v3/admin/rules/COMPATIBILITY
```

> Zaten set edilmişse `409 Conflict` döner — sorun değil. Değeri değiştirmek için:
> `curl -X PUT .../admin/rules/COMPATIBILITY -H 'Content-Type: application/json' -d '{"ruleType":"COMPATIBILITY","config":"BACKWARD"}'`

---

## 3. Kurulum doğrulama (smoke test)

Dummy bir `.proto` register edip geri çekerek uçtan uca doğrular. Repo kökünden:

```bash
# Sunucuda / curl varsa (Docker dışından bir kabuk):
bash campus/scripts/schema-smoke.sh http://localhost:8080
# Windows dev:
pwsh campus/scripts/schema-smoke.ps1 -RegistryUrl http://localhost:8080
```

Beklenen çıktı: `SMOKE OK — register + pull başarılı` ve artifact temizlenir.
Web konsolundan da bakabilirsiniz: `http://localhost:8888`.

---

## 4. Sunucu / üretim notları

- **Port açma:** Yalnızca `8888` (UI) ve `8080` (API) reverse proxy (Traefik/Kong)
  arkasına alın. Postgres compose'da `127.0.0.1`e sabitlidir — dışarı açmayın.
- **TLS:** Prod'da API + UI'yi HTTPS arkasına koyun; `.env`de
  `APICURIO_UI_API_URL=https://schema.lumix.example/apis/registry/v3` ve
  `APICURIO_CORS_ORIGINS=https://schema.lumix.example` yapın.
- **Auth:** Bu compose **auth'suz** (iç ağ / erken faz). Dışarı açmadan önce
  Keycloak/OIDC entegrasyonu ekleyin (Apicurio `APICURIO_AUTH_*` env'leri). Ayrı iş.
- **Yedekleme:** Kritik veri Postgres'te. `apicurio_pg_data` volume'ünü veya
  `pg_dump`ı düzenli yedekleyin (proje genel backup politikası kapsamına alın).
- **Sürüm yükseltme:** `.env`de `APICURIO_VERSION`ı değiştir → `docker compose pull &&
  docker compose up -d`. Önce staging'de dene; şema tabloları geriye dönük migrate edilir.

---

## 5. Sık kullanılan komutlar

| İş | Komut |
|---|---|
| Durum | `docker compose ps` |
| Loglar | `docker compose logs -f apicurio-registry` |
| Yeniden başlat | `docker compose restart apicurio-registry` |
| Durdur (veri kalır) | `docker compose down` |
| Tümünü sil (veri dahil) | `docker compose down -v` |
| Kayıtlı şemalar (grup) | `curl -s localhost:8080/apis/registry/v3/groups/default/artifacts \| jq` |
| Global kural | `curl -s localhost:8080/apis/registry/v3/admin/rules/COMPATIBILITY` |
