---
title: Kong API Gateway
description: Kong OSS nedir, plugin sistemi, Lumix kullanımı (rate-limit, auth header validate, request transform), Kong vs Spring Cloud Gateway karşılaştırması.
sidebar_position: 6
---

## Bu sayfa ne anlatıyor?

Lumix'in trafik mimarisinde **Traefik** L7 edge'de oturur; arkasında **Kong Gateway OSS** uygulama-katmanı API gateway olarak çalışır. Bu sayfa Kong'u sıfırdan anlatır, **plugin** mimarisini gösterir, Lumix'in **rate-limit + JWT validate + request transform + correlation-id + WAF** plugin zincirini detaylandırır, Kong'u **Spring Cloud Gateway** ile karşılaştırır ve neden ayrı bir gateway katmanının değerli olduğunu açıklar. Hedef kitle: Spring/Java geliştirici, K8s temellerini bilen, ilk kez bir API gateway'le karşılaşan.

## 1. Bu nedir? (Sıfırdan)

**Kong Gateway**, Nginx + Lua tabanlı, **plugin-driven** bir API gateway. Açık kaynak (Kong OSS) ve ticari (Kong Enterprise) sürümleri var. Lumix **OSS** kullanır.

Kong'un temel kavramları:
- **Service**: Upstream API'ya bir referans (`http://academic-service.lumix-app.svc.cluster.local:80`).
- **Route**: Service'e ulaşma yolu (host, path, method, header eşleştirmesi).
- **Consumer**: Kong'un tanıdığı istemci kimliği (Lumix'te genellikle "anonymous" + JWT'den gelen subject).
- **Plugin**: Service/Route/Consumer/Global seviyede çalışan davranış (rate-limit, JWT validate, transform, log).
- **Upstream**: Health check'li load-balanced backend grubu.

Kong K8s'te **Ingress Controller** olarak da çalışabilir (Kong Ingress Controller — KIC) — ama Lumix Kong'u **standalone Service** olarak kullanır; Traefik edge'i yapar, Kong arkasında uygulama gateway.

### Günlük hayattan analoji

Şirket telefon santrali. Dış arayan numarayı çevirir → santral cevap verir → "isminizi söyleyin (JWT validate), departmanı söyleyin (route), aynı kişi bugün 50 kez aradıysa engelle (rate-limit), arama kaydını al (logging), iletişim merkezi formatına çevir (transform)". Tek bir santral binlerce dahili için bu işi yapar.

## 2. Hangi problemi çözüyor?

Microservice mimaride **cross-cutting concerns** (her servisin tekrar yapmak istemediği işler): auth, rate-limit, request id, header normalization, request transform, response logging, WAF. Bunları her microservice'te kopyalamak:

| Acı | Kong'suz | Kong ile |
|---|---|---|
| Rate limit her servise | Spring `@RateLimit` her endpoint | Tek plugin, route-level |
| JWT validate | Her servis Spring Security + Redis lookup | Kong jwt + custom plugin |
| Header normalization | Her servis filter chain | Kong `request-transformer` |
| WAF | Her servis Lua/ModSec entegre et | Kong `modsecurity` plugin |
| Correlation-id inject | Her servis MDC | Kong `correlation-id` plugin |
| Response transform | Her servise yaz | Kong `response-transformer` |
| API versioning | Her servis route hard-code | Kong route header/path |
| Canary | Her servis weighted client | Kong upstream weighted targets |
| Observability | Her servis ayrı Prometheus | Kong tek endpoint |

### Patlamış üretim hikayesi

Bir takım her microservice'te rate-limit'i Redis tabanlı kendisi yazıyordu. 8 servis × 3 ayrı implementation → tutarsızlık. Bir servis Redis bağlantısı için pool size yanlıştı → tüm pod'lar bağlandı, Redis kapasitesi taştı, **tüm servisler etkilendi**. Tek bir gateway (Kong) + tek plugin = tek noktada doğru implementation.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Mimari

```
┌────────────────────────────────────────────────┐
│ Kong Gateway Pod                               │
│                                                │
│   ┌──────────────────────────────────────────┐ │
│   │ OpenResty (Nginx + LuaJIT)               │ │
│   │                                          │ │
│   │   ┌──────────────────────────────────┐   │ │
│   │   │ Plugin pipeline (Lua callback)   │   │ │
│   │   │   1. certificate                 │   │ │
│   │   │   2. rewrite                     │   │ │
│   │   │   3. access (auth, rate-limit)   │   │ │
│   │   │   4. balancer                    │   │ │
│   │   │   5. header_filter               │   │ │
│   │   │   6. body_filter                 │   │ │
│   │   │   7. log                         │   │ │
│   │   └──────────────────────────────────┘   │ │
│   └──────────────────────────────────────────┘ │
│                                                │
│   Config store: DB-less (declarative YAML)    │
│   or PostgreSQL (DB mode)                      │
└────────────────────────────────────────────────┘
```

### 3.2. Plugin lifecycle

Her HTTP isteği plugin'lerden sırayla geçer. Plugin **handler.lua** içinde lifecycle callback'leri tanımlar:
- `access`: backend'e gitmeden önce (auth, rate-limit burada)
- `header_filter`: response header'ı dönmeden önce
- `body_filter`: response body'si dönmeden önce
- `log`: response gönderildikten sonra (async)

### 3.3. DB-less mode (Lumix tercihi)

Kong iki modda çalışabilir:
- **DB mode** (PostgreSQL/Cassandra): admin API ile dinamik config.
- **DB-less mode**: Tüm config tek YAML dosyasında, K8s ConfigMap olarak. **GitOps dostu**.

Lumix DB-less mode kullanır → declarative, Git'te versiyonlu, ArgoCD ile uygulanır.

### 3.4. Kong Ingress Controller (KIC) — Lumix kullanır mı?

KIC, K8s `Ingress`/`HTTPRoute`/`KongIngress` CRD'lerini izleyip Kong config'ini günceller. Lumix'te:
- **Edge** Traefik (TLS, host routing).
- **API Gateway** Kong, K8s Service olarak, declarative config.
- KIC sadece geliştirme kolaylığı için opsiyonel; production'da declarative YAML.

### 3.5. Plugin türleri

| Tür | Örnek | Lumix kullanır mı |
|---|---|---|
| **Authentication** | jwt, oauth2, key-auth, ldap-auth | jwt (custom validate) |
| **Security** | acl, ip-restriction, bot-detection, modsecurity | modsecurity, ip-restriction |
| **Traffic control** | rate-limiting, response-ratelimiting, request-size-limiting | rate-limiting (Redis) |
| **Transformations** | request-transformer, response-transformer, correlation-id | request-transformer, correlation-id |
| **Logging** | http-log, tcp-log, file-log, datadog, prometheus | prometheus, http-log |
| **Analytics** | prometheus, opentelemetry | prometheus, opentelemetry |

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Versiyon ve kurulum

Kong v3.7+, Helm chart `kong/kong`, `lumix-system` namespace, **DB-less mode**.

```yaml
# values-kong.yaml
deployment:
  kong:
    enabled: true
image:
  repository: kong
  tag: "3.7"

env:
  database: "off"
  declarative_config: /kong/declarative/kong.yml
  plugins: bundled,modsecurity

dblessConfig:
  configMap: kong-declarative-config

ingressController:
  enabled: false   # standalone mode

proxy:
  type: ClusterIP
  http:
    enabled: true
    servicePort: 80
  tls:
    enabled: false   # TLS Traefik'te terminate
  stream:
    enabled: false

admin:
  enabled: true
  type: ClusterIP
  http:
    enabled: true
    servicePort: 8001

resources:
  requests:
    cpu: 500m
    memory: 512Mi
  limits:
    cpu: 2000m
    memory: 1Gi

podSecurityContext:
  runAsNonRoot: true
  runAsUser: 1000
  fsGroup: 1000

readinessProbe:
  httpGet:
    path: /status
    port: 8100
livenessProbe:
  httpGet:
    path: /status
    port: 8100

replicaCount: 2

autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 6
  targetCPUUtilizationPercentage: 70
```

### 4.2. Plugin zinciri (Lumix standardı)

Tüm `/api/*` route'larında bu sıralama:

```
1. correlation-id        → her isteğe X-Correlation-Id (UUID v7) inject
2. ip-restriction        → blacklist (audit-service'in bildirdiği IP'ler)
3. modsecurity           → OWASP CRS (bkz. ModSecurity sayfası)
4. rate-limiting         → Redis-based, route+consumer bazlı
5. jwt-validate-custom   → Lumix custom plugin: Redis token check
6. request-transformer   → X-Tenant-Id, X-User-Id header inject (JWT claim'lerinden)
7. prometheus            → metric expose
8. http-log              → audit-service'e HTTP POST
```

### 4.3. Declarative config örneği

```yaml
# kong-declarative-config (ConfigMap içeriği)
_format_version: "3.0"
_transform: true

services:
  - name: identity-service
    url: http://identity-service.lumix-app.svc.cluster.local:80
    connect_timeout: 5000
    read_timeout: 15000
    write_timeout: 15000
    retries: 1
    routes:
      - name: identity-public
        paths: ["/api/v1/auth"]
        strip_path: false
        plugins:
          - name: rate-limiting
            config:
              minute: 30
              policy: redis
              redis_host: redis-master.lumix-data.svc.cluster.local
              redis_port: 6379
              redis_password: ${{REDIS_PASSWORD}}
              redis_database: 3
              hide_client_headers: false
          - name: correlation-id
            config:
              header_name: X-Correlation-Id
              generator: uuid
              echo_downstream: true

  - name: academic-service
    url: http://academic-service.lumix-app.svc.cluster.local:80
    routes:
      - name: academic-api
        paths: ["/api/v1/academic"]
        strip_path: false
        plugins:
          - name: jwt-validate-custom        # Lumix custom plugin
            config:
              redis_host: redis-master.lumix-data.svc.cluster.local
              redis_password: ${{REDIS_PASSWORD}}
              redis_database: 0
              jwks_url: https://identity-service.lumix-app.svc.cluster.local/.well-known/jwks.json
              required_claims: ["sub", "tenant_id", "session_id"]
          - name: request-transformer
            config:
              add:
                headers:
                  - "X-Tenant-Id:$(jwt.tenant_id)"
                  - "X-User-Id:$(jwt.sub)"
                  - "X-Session-Id:$(jwt.session_id)"
          - name: rate-limiting
            config:
              minute: 120
              policy: redis
              redis_host: redis-master.lumix-data.svc.cluster.local
              limit_by: header
              header_name: X-Tenant-Id

plugins:
  - name: prometheus
    config:
      per_consumer: true
      status_code_metrics: true
      latency_metrics: true
      bandwidth_metrics: true
      upstream_health_metrics: true
```

### 4.4. Custom plugin: `jwt-validate-custom`

Lumix'in stateful JWT modelinde (token Redis'te), Kong'un built-in `jwt` plugin'i yeterli değildir. Custom plugin:
1. JWT signature verify (jwks-rs256 ile public key).
2. `jti` (JWT ID) Redis'te `token:active:{jti}` mevcut mu?
3. `session_id` Redis'te `session:active:{sid}` mevcut mu?
4. Claim'leri header'a inject et.
5. Yoksa 401 + `WWW-Authenticate: Bearer`.

```lua
-- jwt-validate-custom/handler.lua (özet)
local jwt_decoder = require "resty.jwt"
local redis = require "resty.redis"

local plugin = {}

function plugin:access(conf)
  local auth = kong.request.get_header("Authorization")
  if not auth then return kong.response.exit(401, { message = "missing token" }) end

  local token = auth:match("Bearer%s+(.+)")
  local jwt_obj = jwt_decoder:load_jwt(token)
  if not jwt_obj.valid then return kong.response.exit(401, { message = "invalid token" }) end

  -- signature verify (jwks)
  local ok, err = jwt_decoder:verify_jwt_obj(get_public_key(conf.jwks_url), jwt_obj)
  if not ok then return kong.response.exit(401, { message = "bad signature" }) end

  -- Redis active check
  local red = redis:new()
  red:set_timeout(200)
  red:connect(conf.redis_host, conf.redis_port)
  red:auth(conf.redis_password)
  red:select(conf.redis_database)

  local active = red:get("token:active:" .. jwt_obj.payload.jti)
  if active == ngx.null then return kong.response.exit(401, { message = "token revoked" }) end

  local sess = red:get("session:active:" .. jwt_obj.payload.session_id)
  if sess == ngx.null then return kong.response.exit(401, { message = "session ended" }) end

  red:set_keepalive(10000, 100)

  -- Inject claims for downstream
  kong.service.request.set_header("X-Tenant-Id", jwt_obj.payload.tenant_id)
  kong.service.request.set_header("X-User-Id", jwt_obj.payload.sub)
  kong.service.request.set_header("X-Session-Id", jwt_obj.payload.session_id)
end

return plugin
```

Plugin Kong image'ına `/usr/local/share/lua/5.1/kong/plugins/jwt-validate-custom/` altında paketlenir. Helm `image.repository` Lumix custom Kong image'ına işaret eder (`registry.lumix.io/lumix/kong:3.7-lumix-1`).

### 4.5. Rate-limit politikası

| Endpoint sınıfı | Politika |
|---|---|
| `/api/v1/auth/login` | 5/dakika per IP (brute force) |
| `/api/v1/auth/refresh` | 60/dakika per cookie hash |
| Tenant API'lar | 120/dakika per X-Tenant-Id |
| Internal admin | 30/dakika per X-User-Id |

Storage: Redis (`limit_by: header`, `policy: redis`). Lokal cluster Redis (lumix-data namespace).

### 4.6. Observability

- `/metrics` endpoint Prometheus tarafından scrape edilir.
- `http-log` plugin audit-service'e POST atar (asenkron; failure plugin'i bloke etmez).
- OpenTelemetry plugin OTLP exporter ile Tempo'ya trace gönderir.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Spring Cloud Gateway** | Java tabanlı, Spring ekosistemiyle uyumlu. **Ama**: ayrı microservice yazma zorunluluğu; plugin yerine her şey Java kod; her takım her şeyi tekrar yazıyor. Operasyonel ortaklığı azaltıyor. Lumix Kong'u tercih etti (lingua-franca config). |
| **Envoy** (raw) | Çok güçlü ama config XDS kompleks; gateway için overkill. |
| **AWS API Gateway / GCP API Gateway** | Bulut-kilit, on-prem yok. |
| **Tyk** | Açık kaynak, ama topluluk Kong'a kıyasla daha küçük. |
| **Krakend** | Hızlı ama plugin ekosistemi sınırlı. |
| **Apache APISIX** | Ciddi rakip. Kong ile karşılaştırıldı; Lumix Kong'u tercih etti (olgun OSS topluluğu, daha geniş plugin marketplace, Lua tooling, K8s entegrasyonu sertleşmiş). APISIX gelecekte yeniden değerlendirilebilir. |
| **Istio Gateway** | Mesh ile gelir; Lumix mesh kullanmıyor. Overkill. |

### Kabul ettiğimiz trade-off'lar

- **Lua öğrenmek**: Custom plugin yazımı için Lua. Ekibin daha önce Lua tecrübesi yok → öğrenme yatırımı yapıldı.
- **DB-less mode'da consumer dinamizmi yok**: Lumix consumer'ları "anonymous" + JWT validate; OAuth2 client gibi dinamik consumer ihtiyacı yok. Bu trade-off uygun.
- **Çok büyük config dosyası**: 30 müşteri × N route = büyük YAML. Helm template ile parçalı üretim.

### Tekrar değerlendirme tetikleyicileri

- Çok yüksek QPS (&gt;50K/sn) → daha düşük overhead'li Envoy/APISIX.
- Kong Enterprise özellikleri (OIDC built-in, more security plugins) kritik olursa lisans değerlendirilir.

## 6. Pratik örnek

### 6.1. Kong K8s manifest (Helm install çıktısı özet)

```bash
helm repo add kong https://charts.konghq.com
helm install kong kong/kong \
  --namespace lumix-system \
  --version 2.40.0 \
  -f values-kong.yaml \
  --set "env.declarative_config=/kong/declarative/kong.yml"
```

ConfigMap güncellenince Kong pod restart için Helm hook annotation:

```yaml
metadata:
  annotations:
    rollme: {{ randAlphaNum 5 | quote }}
```

veya `kubectl rollout restart deploy/kong`.

### 6.2. Health check ve circuit breaker (upstream)

```yaml
upstreams:
  - name: academic-upstream
    targets:
      - target: academic-service.lumix-app.svc.cluster.local:80
        weight: 100
    healthchecks:
      active:
        type: http
        http_path: /actuator/health/readiness
        healthy:
          interval: 5
          successes: 2
        unhealthy:
          interval: 5
          http_failures: 3
          timeouts: 3
      passive:
        healthy:
          successes: 5
        unhealthy:
          http_failures: 3
          timeouts: 3
```

### 6.3. Request transform örneği

```yaml
plugins:
  - name: request-transformer
    config:
      remove:
        headers: ["X-Forwarded-Server"]
      add:
        headers:
          - "X-Lumix-Edge:kong"
          - "X-Tenant-Id:$(jwt.tenant_id)"
      replace:
        headers:
          - "Host:academic-service.lumix-app"
```

### 6.4. Prometheus scrape

```yaml
# ServiceMonitor (Lumix Prometheus operator)
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: kong
  namespace: lumix-system
spec:
  selector:
    matchLabels:
      app.kubernetes.io/name: kong
  endpoints:
    - port: metrics
      interval: 15s
      path: /metrics
```

### 6.5. Manuel test

```bash
# Pod içinde curl
kubectl -n lumix-system exec -it deploy/kong -- kong health
kubectl -n lumix-system exec -it deploy/kong -- kong config db_export

# Smoke test
curl -i \
  -H "Host: api.omer-okullari.lumix.io" \
  -H "Authorization: Bearer $TOKEN" \
  https://api.omer-okullari.lumix.io/api/v1/academic/students?page=0
# Beklenen: 200 OK, X-Correlation-Id header
```

### 6.6. Kong + Spring Cloud Gateway karşılaştırma

| Konu | Kong | Spring Cloud Gateway |
|---|---|---|
| Dil | Lua plugin / config YAML | Java |
| Performans | Yüksek (Nginx + Lua) | Reactive Netty, iyi |
| Plugin ekosistemi | Geniş, marketplace | Kendi yazmak yaygın |
| K8s native | Helm chart, KIC | Spring Boot pod |
| Operational ortaklık | Tek araç, polyglot ekip | Java ekibi rahat |
| Dynamic config | Admin API veya declarative | Yeni jar deploy |
| Lumix kararı | **✅ Kong** | (Eskiden değerlendirildi) |

## 7. Dikkat edilecek tuzaklar

- **DB-less mode'da admin API'yi public expose etmek**: kimse declarative değil ama runtime endpoint'ler hala var. Lumix kuralı: `admin.type: ClusterIP`, sadece internal.
- **`strip_path: true` yanlış kullanımı**: backend'in URL'i değişir, 404 alırsın. Lumix: `strip_path: false` çoğu route'ta.
- **Plugin sıralaması**: rate-limit'i auth'tan **önce** koymak → unauthenticated istekler rate-limit'i yer. Lumix: auth → rate-limit (kimliği bilinmeyenin önce limit yenmesi tercih edilebilir ama auth header check ucuz). Sıra disiplini documentation'da.
- **Redis için connect_timeout düşük**: Redis hıçkırığında Kong istekleri bekletir. `set_timeout(200)` ms uygun.
- **`http-log` plugin senkron**: audit-service çökerse Kong bekler. **Async** olarak yapılandır (queue + retry).
- **Custom plugin paketlemeyi unutmak**: image içinde olmayan plugin `plugins: bundled,custom-name` ile çalışmaz; Kong başlatılamaz. CI'da Kong image build adımı zorunlu.
- **JWT signature verify sırasında JWKS'i her istekte çekmek**: jwks-rs256 plugin cache yapmazsa identity-service'i ezer. Lumix custom plugin in-memory cache (TTL 5 dk) + Redis backup.
- **Rate-limit `local` policy'yi production'da kullanmak**: scale-out olduğunda her pod ayrı sayar. `policy: redis` zorunlu.
- **DB mode'a kazara geçmek**: PostgreSQL admin API ile manuel değişim → drift. Lumix sadece DB-less.
- **Connect timeout düşük (&lt;2s)**: yavaş backend pod'unda erken timeout. Lumix: `connect: 5s, read/write: 15s`.
- **Replicas: 1**: rolling update sırasında downtime. Min 2 + HPA.
- **WAF middleware yanlış konum**: ModSecurity rate-limit'ten **önce** çalışmalı (kötü istek hızlı bitsin). Sıra: ip-restriction → modsecurity → rate-limit → auth.

## 8. Diğer konularla ilişkisi

- [Traefik Ingress](./05-traefik-ingress.md) — Kong'un önündeki edge
- [ModSecurity WAF](./07-modsecurity-waf.md) — Kong plugin
- [Kubernetes Temelleri](./01-kubernetes-fundamentals.md) — Service, Pod yapısı
- [Helm Charts](./03-helm-charts.md) — Kong deploy
- [Authentication](../authentication-authorization) — JWT, Redis, stateful model
- [Observability](../observability-qa) — Kong metrics, log

## 9. Daha derine inmek için

- Resmi doc: [https://docs.konghq.com/](https://docs.konghq.com/)
- Plugin development: [https://docs.konghq.com/gateway/latest/plugin-development/](https://docs.konghq.com/gateway/latest/plugin-development/)
- "Kong in Action" — Hans Brender
- Apache APISIX karşılaştırma: blog yazıları
- Search keyword'leri: *"kong db-less declarative"*, *"kong custom plugin lua"*, *"kong rate-limiting redis"*, *"kong jwt plugin"*
- Lumix engineering-notes referansı: `21-kubernetes-helm-infra-devops-engineering-guide.md`

## 10. Sözlük

- **Kong Gateway**: Nginx + Lua tabanlı, plugin-driven API gateway.
- **Service (Kong)**: Upstream API referansı.
- **Route (Kong)**: Service'e ulaşma yolu tanımı (host/path/method).
- **Consumer (Kong)**: İstemci kimliği (anonymous + JWT bağlamında bilinen kullanıcı).
- **Plugin**: Service/Route/Consumer/Global seviyede çalışan davranış modülü.
- **Upstream**: Health check'li load-balanced backend grubu.
- **DB-less mode**: Tüm config tek YAML'da, ConfigMap'ten okur.
- **KIC (Kong Ingress Controller)**: K8s CRD'lerinden Kong config üreten controller.
- **OpenResty**: Nginx + LuaJIT runtime; Kong'un altyapısı.
- **JWKS**: JSON Web Key Set; public key dağıtım mekanizması.
- **Plugin lifecycle (access/header_filter/body_filter/log)**: Plugin'in HTTP isteğine müdahale anları.
- **Declarative config**: Tüm Kong config'inin tek YAML olarak GitOps ile yönetilmesi.
