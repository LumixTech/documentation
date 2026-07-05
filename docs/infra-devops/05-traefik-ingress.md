---
title: Traefik Ingress
description: Traefik nedir, K8s-native özellikler, IngressRoute CRD, TLS termination, middleware (rate-limit, redirect, strip-prefix), cert-manager entegrasyonu.
sidebar_position: 5
---

## Bu sayfa ne anlatıyor?

Cluster içinden dünyaya açılan trafik bir kapıdan geçer: **Ingress**. Lumix bu kapıda **Traefik**'i kullanır. Bu sayfa Traefik'i sıfırdan anlatır, standart K8s `Ingress` ile **IngressRoute CRD** farkını gösterir, **middleware** zincir kavramını açıklar, Lumix'in **Traefik → Kong → microservice** akışında Traefik'in rolünü netleştirir, cert-manager + Let's Encrypt entegrasyonunu detaylandırır. Hedef kitle: K8s ve Helm bilen, ingress controller'a yeni geçen mühendis.

## 1. Bu nedir? (Sıfırdan)

**Traefik**, Go ile yazılmış, **dinamik konfigürasyonu** ve **otomatik servis discovery'si** olan bir reverse proxy / load balancer. Kubernetes'te **Ingress Controller** olarak çalışır.

Ingress Controller nedir? K8s'te `Ingress` (ya da `IngressRoute`) bir manifest'tir — ama trafiği gerçekten yönlendiren bir bileşen yoksa hiçbir şey olmaz. Ingress Controller manifest'leri **okur**, bunlara karşılık gelen **gerçek HTTP routing kurallarını** uygular. Traefik, Nginx, HAProxy, Contour gibi seçenekler vardır.

Traefik'i benzersiz yapan:
- **Service discovery**: K8s API'yi dinler; yeni `IngressRoute` apply edilince anında konfigürasyona yansır (reload yok).
- **Middleware** kavramı: rate-limit, redirect, header manipulation, strip-prefix, auth gibi davranışları zincirleyebilirsin.
- **CRD-based config**: K8s native nesneler (`IngressRoute`, `Middleware`, `TLSStore`) ile YAML.
- **Built-in metrics + dashboard**: Prometheus expose + UI.
- **ACME entegrasyonu**: Kendisi de Let's Encrypt yapabilir (Lumix bunu kullanmaz; cert-manager kullanır).

### Günlük hayattan analoji

AVM'nin ana girişi: güvenlik (TLS), bilgi danışma (host header okur → "X mağazasına yönlendir"), iç koridor levhaları (path-based routing), VIP koridoru (priority). Traefik = bu giriş ekibi + sistemi. Dinamik: yeni mağaza açıldığında AVM'yi kapatmadan koridor levhası anında ekleniyor.

## 2. Hangi problemi çözüyor?

K8s'te 10 microservice'i dış dünyaya açmak şu acıları doğurur:

| Acı | Ingress Controller'sız | Traefik ile |
|---|---|---|
| Yeni servis eklemek | LB config + DNS + manuel nginx reload | `IngressRoute` apply, otomatik |
| TLS cert yönetimi | Manuel cert dosyası kopyala | cert-manager + Traefik `secretName` |
| Path-based routing (`/api/v1/academic/*`) | Nginx config el ile | Middleware `StripPrefix` + route Match |
| Aynı host'ta birden fazla servis | Manuel virtual host | Host + Path matcher |
| Rate limit (DDoS koruması ilk katman) | Custom Lua veya kapalı kaynak appliance | Middleware `RateLimit` |
| Canary deploy / traffic split | Manuel weighted backend | `services.weight` |
| Observability | Manuel access log + custom scrape | Built-in Prometheus + access log JSON |
| Sertifika rotation | Manuel HUP signal | cert-manager Secret değişimi otomatik |

### Patlamış üretim hikayesi

Bir takım Nginx ile manuel ingress yönetiyordu. Yeni servis eklemek için: nginx config dosyasını düzenle, ConfigMap apply, pod restart. Pod restart sırasında 5-10 saniye 503. Cuma sürümü: yeni servis konfigürasyonunda yazım hatası, nginx başlatılamadı, **eski servisler de düştü**. Traefik dinamik config + CRD validation ile bu acıyı dışlar.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Bileşenler

```
┌──────────────────────────────────────────┐
│  Traefik Pod (DaemonSet veya Deployment) │
│                                          │
│   ┌────────────────────────────────────┐ │
│   │ Providers (K8s API watcher)        │ │
│   │  - K8s Ingress                     │ │
│   │  - Traefik IngressRoute CRD        │ │
│   │  - K8s Service                     │ │
│   └─────────────┬──────────────────────┘ │
│                 │                        │
│                 ▼                        │
│   ┌────────────────────────────────────┐ │
│   │ Configuration store (in-memory)    │ │
│   │  - Routers (route definitions)     │ │
│   │  - Services (backend definitions)  │ │
│   │  - Middlewares                     │ │
│   │  - TLS stores                      │ │
│   └─────────────┬──────────────────────┘ │
│                 │                        │
│                 ▼                        │
│   ┌────────────────────────────────────┐ │
│   │ Entrypoints (listening ports)      │ │
│   │  - :80  (web)                      │ │
│   │  - :443 (websecure, TLS)           │ │
│   │  - :9000 (traefik dashboard)       │ │
│   │  - :9100 (metrics)                 │ │
│   └────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

### 3.2. CRD'ler

| CRD | Görev |
|---|---|
| `IngressRoute` | HTTP routing (host + path matcher → service) |
| `IngressRouteTCP` | Raw TCP routing (SNI bazlı) |
| `IngressRouteUDP` | UDP |
| `Middleware` | İstek/yanıt manipulation zinciri |
| `TLSStore` | TLS sertifika store |
| `TLSOption` | TLS versiyon, cipher suite |
| `TraefikService` | Birden fazla backend birleştirme (weighted, mirror) |
| `ServersTransport` | Backend'e bağlanma davranışı (mTLS, cert) |

### 3.3. IngressRoute örneği

```yaml
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: lumix-api
spec:
  entryPoints:
    - websecure
  routes:
    - match: Host(`api.omer-okullari.lumix.io`) && PathPrefix(`/api`)
      kind: Rule
      middlewares:
        - name: rate-limit-anon
        - name: strip-api-prefix
      services:
        - name: kong-proxy
          port: 80
  tls:
    secretName: omer-okullari-tls
```

### 3.4. Middleware zinciri

İstek sırayla middleware'lerden geçer:

```
Request
   │
   ▼
┌─────────────────┐
│ rate-limit-anon │  → IP başına 100 req/s
└────────┬────────┘
         ▼
┌──────────────────┐
│ strip-api-prefix │  → /api prefix'i sil
└────────┬─────────┘
         ▼
┌──────────────────┐
│ add-headers      │  → X-Correlation-Id
└────────┬─────────┘
         ▼
Backend (kong-proxy)
```

Middleware tek seferlik tanımlanır, birden fazla IngressRoute paylaşır.

### 3.5. TLS termination

Traefik TLS'i **kendi pod'unda terminate eder**. Sertifika:
- cert-manager bir `Certificate` CRD üretir.
- cert-manager Let's Encrypt'ten cert alır, K8s `Secret` olarak yazar.
- IngressRoute `tls.secretName: omer-okullari-tls` ile bu secret'a referans verir.
- Traefik secret değişimini izler, yeni cert'i anında yükler.

### 3.6. Otomatik servis discovery

K8s `Service` nesnesi varsa Traefik onu otomatik tanır. `IngressRoute.services[].name` o Service adıdır. Service Endpoint'leri (Pod IP listesi) değişince Traefik anında günceller (apiserver watch).

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Versiyon ve kurulum

Traefik v3 (Helm chart `traefik/traefik`), `lumix-system` namespace.

```bash
helm repo add traefik https://traefik.github.io/charts
helm install traefik traefik/traefik \
  --version 26.0.0 \
  --namespace lumix-system \
  --create-namespace \
  -f values-traefik.yaml
```

`values-traefik.yaml` (Lumix):

```yaml
deployment:
  kind: Deployment
  replicas: 2

service:
  type: LoadBalancer
  annotations:
    metallb.universe.tf/loadBalancerIPs: 10.0.0.100  # baremetal MetalLB

ports:
  web:
    port: 80
    redirectTo:
      port: websecure
      scheme: https
  websecure:
    port: 443
    tls:
      enabled: true
      options: tls-strict
  metrics:
    port: 9100
    expose: false
  traefik:
    port: 9000
    expose: false

providers:
  kubernetesCRD:
    enabled: true
    allowCrossNamespace: true
    allowExternalNameServices: false
  kubernetesIngress:
    enabled: false   # sadece CRD kullanırız

logs:
  general:
    level: INFO
    format: json
  access:
    enabled: true
    format: json
    fields:
      headers:
        defaultMode: keep
        names:
          Authorization: drop
          Cookie: drop

metrics:
  prometheus:
    enabled: true
    addEntryPointsLabels: true
    addRoutersLabels: true
    addServicesLabels: true

resources:
  requests:
    cpu: 200m
    memory: 256Mi
  limits:
    cpu: 1000m
    memory: 512Mi

ingressClass:
  enabled: true
  isDefaultClass: true
  name: traefik

podSecurityContext:
  runAsNonRoot: true
  runAsUser: 65532
  fsGroup: 65532

securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: [ALL]
```

### 4.2. Trafik akışı (Lumix)

```
Internet
   │
   ▼
[ MetalLB / cloud LB → 10.0.0.100:443 ]
   │
   ▼
[ Traefik Pod (websecure entrypoint) ]
   - TLS terminate (cert-manager secret)
   - Middleware: rate-limit-anon, request-id
   │
   ▼
[ Kong Gateway Service ]   ← uygulamaya yakın L7 (auth, route per microservice)
   │
   ▼
[ Microservice Service → Pod ]
```

**Neden iki katman (Traefik + Kong)?**
- Traefik: cluster-level **edge**: TLS termination, host routing, kaba rate-limit (IP/anon), redirect.
- Kong: uygulama-level **gateway**: JWT validate, per-route rate-limit, plugin (request transform, mTLS to backend), WAF (ModSecurity). Detay: [Kong API Gateway](./06-kong-api-gateway.md).

### 4.3. Standart middleware'ler

`lumix-system` namespace'te ortak middleware tanımları:

```yaml
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: rate-limit-anon
  namespace: lumix-system
spec:
  rateLimit:
    average: 100
    burst: 200
    period: 1s
    sourceCriterion:
      ipStrategy:
        depth: 1   # X-Forwarded-For 1. IP (LB sonrası)
---
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: hsts-headers
  namespace: lumix-system
spec:
  headers:
    stsSeconds: 31536000
    stsIncludeSubdomains: true
    stsPreload: true
    contentTypeNosniff: true
    browserXssFilter: true
    referrerPolicy: strict-origin-when-cross-origin
    contentSecurityPolicy: "default-src 'self'"
---
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: ip-allowlist-admin
  namespace: lumix-system
spec:
  ipAllowList:
    sourceRange:
      - 10.0.0.0/8
      - 192.168.0.0/16
---
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: strip-api-prefix
  namespace: lumix-system
spec:
  stripPrefix:
    prefixes:
      - /api
```

Cross-namespace kullanım için `providers.kubernetesCRD.allowCrossNamespace: true` açıkken middleware'lere `lumix-system-rate-limit-anon@kubernetescrd` syntax'ı veya `namespace/name` referansı yazılır.

### 4.4. TLS politikası

```yaml
apiVersion: traefik.io/v1alpha1
kind: TLSOption
metadata:
  name: tls-strict
  namespace: lumix-system
spec:
  minVersion: VersionTLS13
  curvePreferences:
    - X25519
    - CurveP384
  sniStrict: true
```

TLS 1.3 zorunlu. SNI strict: SNI olmadan gelen request'leri reddet.

### 4.5. cert-manager entegrasyonu

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: omer-okullari-tls
  namespace: lumix-system
spec:
  secretName: omer-okullari-tls
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
  commonName: api.omer-okullari.lumix.io
  dnsNames:
    - api.omer-okullari.lumix.io
    - admin.omer-okullari.lumix.io
  duration: 2160h          # 90 gün
  renewBefore: 720h        # 30 gün önceden yenile
```

Detay: [cert-manager TLS](./08-cert-manager-tls.md).

### 4.6. Dashboard erişimi

Traefik dashboard production'da **public expose edilmez**. Lumix kuralı:
- `ports.traefik.expose: false`
- Erişim için `kubectl port-forward` veya VPN üzerinden iç IP.

### 4.7. Observability

Promtail Traefik JSON access log'unu Loki'ye gönderir. Grafana dashboard'da:
- p50/p95/p99 latency per router
- 4xx/5xx oranı per router
- Top 10 IP request volume
- Rate-limit hit'leri

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Nginx Ingress Controller** | Yaygın ama dinamik config sınırlı (template + reload). Middleware kavramı yok; her şey annotation. |
| **HAProxy Ingress** | Performansta güçlü ama K8s-native dinamiklik Traefik kadar değil. |
| **Contour (Envoy)** | Çok güçlü ama Envoy ekosistemi mesh düzeyinde (Istio); ingress için ağır. |
| **Istio Gateway** | mTLS, mesh ile birlikte gelir. Lumix mesh kullanmıyor (şimdilik). Overkill. |
| **Gloo Edge** | Ticari yön ağır. |
| **K3s gömülü Traefik (default)** | Versiyon kontrolü Lumix'te değil; biz Helm ile kendi Traefik'imizi yönetiriz. |

### Kabul ettiğimiz trade-off'lar

- **Kong + Traefik iki katman**: ek hop, ek latency (~1-3ms). Karşılığında: ayrı sorumluluk, ayrı evrim.
- **Traefik dashboard public değil**: yan etkisi: ekibe ek erişim adımı. Karşılığında: saldırı yüzeyi azalır.
- **CRD-based config**: K8s API'ye sıkı bağ. Migrate etmek zor olabilir; ama Lumix K8s'i hedef seçti.

### Tekrar değerlendirme tetikleyicileri

- Mesh ihtiyacı doğarsa (mTLS service-to-service): Istio Gateway veya Linkerd ile değişim.
- Çok yüksek QPS'de (>50K rps per cluster): HAProxy/Envoy daha iyi olabilir; ölçüm sonrası karar.

## 6. Pratik örnek

### 6.1. Identity service için IngressRoute

```yaml
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: identity-public
  namespace: lumix-app
spec:
  entryPoints:
    - websecure
  routes:
    - match: Host(`api.omer-okullari.lumix.io`) && PathPrefix(`/api/v1/auth`)
      kind: Rule
      middlewares:
        - name: rate-limit-anon
          namespace: lumix-system
        - name: hsts-headers
          namespace: lumix-system
      services:
        - name: kong-proxy
          namespace: lumix-system
          port: 80
  tls:
    secretName: omer-okullari-tls
    options:
      name: tls-strict
      namespace: lumix-system
```

### 6.2. Customer Admin Panel için

```yaml
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: admin-panel
  namespace: lumix-app
spec:
  entryPoints:
    - websecure
  routes:
    - match: Host(`admin.omer-okullari.lumix.io`)
      kind: Rule
      middlewares:
        - name: hsts-headers
          namespace: lumix-system
      services:
        - name: customer-admin-web
          namespace: lumix-app
          port: 80
  tls:
    secretName: omer-okullari-tls
```

### 6.3. Canary deploy (10% yeni versiyon)

```yaml
apiVersion: traefik.io/v1alpha1
kind: TraefikService
metadata:
  name: academic-canary
  namespace: lumix-app
spec:
  weighted:
    services:
      - name: academic-service-stable
        port: 80
        weight: 9
      - name: academic-service-canary
        port: 80
        weight: 1
---
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: academic-with-canary
  namespace: lumix-app
spec:
  entryPoints: [websecure]
  routes:
    - match: Host(`api.omer-okullari.lumix.io`) && PathPrefix(`/api/v1/academic`)
      kind: Rule
      services:
        - name: academic-canary
          kind: TraefikService
  tls:
    secretName: omer-okullari-tls
```

### 6.4. Path-based routing örneği

```yaml
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: lumix-api-mux
  namespace: lumix-app
spec:
  entryPoints: [websecure]
  routes:
    - match: Host(`api.omer-okullari.lumix.io`) && PathPrefix(`/api/v1/finance`)
      services:
        - name: finance-service
          port: 80
    - match: Host(`api.omer-okullari.lumix.io`) && PathPrefix(`/api/v1/academic`)
      services:
        - name: academic-service
          port: 80
    - match: Host(`api.omer-okullari.lumix.io`) && PathPrefix(`/api/v1/files`)
      services:
        - name: file-service
          port: 80
  tls:
    secretName: omer-okullari-tls
```

(Pratikte tüm `/api/*` Kong'a gider; Kong içeride route'a göre microservice'e dağıtır. Bu örnek alternatif için.)

### 6.5. Bir middleware'i test etme

```bash
# Traefik pod'una port-forward
kubectl -n lumix-system port-forward svc/traefik 9000:9000

# Browser → http://localhost:9000/dashboard/
# Veya komut satırından
curl -k -H "Host: api.omer-okullari.lumix.io" https://localhost/api/v1/health
```

## 7. Dikkat edilecek tuzaklar

- **K3s gömülü Traefik'i kapatmamak**: iki Traefik aynı 80/443'ü ister, çakışma. K3s install: `--disable=traefik`.
- **`Host()` yerine `HostRegexp()` yanlış kullanımı**: regex hata yapmaya açık. Mümkün olduğunca `Host()` veya `Host()||Host()`.
- **TLS 1.0/1.1 destek bırakmak**: SSL Labs F notu. Lumix kuralı: TLS 1.3 only, 1.2 sadece geçici geriye uyumluluk.
- **Middleware cross-namespace izin vermeden referans**: silentliği `MiddlewareNotFound` ile router-down olur. `allowCrossNamespace: true` veya middleware'leri her namespace'te tekrarla.
- **`StripPrefix` sonrası backend rewrite yanılgısı**: backend `/api/v1/academic` bekliyor ama Traefik strip ettiyse `/v1/academic` gönderiyor. Backend yolu ile uyumu doğrula.
- **`sticky` session olmadan WebSocket scale**: WebSocket bağlantısı bir pod'da kurulur, scale-down olduğunda bağlantı kopar. Lumix WebSocket için Redis Pub/Sub backplane kullanır (sticky şart değil); ama edge'de Kong sticky tercih edilebilir.
- **Dashboard public**: Traefik UI tüm route'ları gösterir → recon avantajı. Production'da expose etmeyin.
- **Rate-limit'i sadece IP üzerinden kurmak**: NAT'lı kurumsal IP'lerde herkes aynı IP. Anonim erişim için IP; authenticated için user-id. Kong tarafında detay.
- **Access log'da Authorization header sızıntısı**: token Loki'ye yazılır. Lumix: `headers.names.Authorization: drop`.
- **Cert-manager Secret'ı silmek**: Traefik route TLS'i kaybeder → 503. Secret manuel silme yasak, yenileme cert-manager tarafından.
- **Resource limit eksik Traefik pod'u**: bir DDoS sırasında OOM. Lumix Traefik: `limits.cpu: 1000m, memory: 512Mi` (boyuta göre büyütülür).
- **`replicas: 1` Traefik**: rolling update sırasında 5-10s downtime. Lumix kuralı: minimum 2.

## 8. Diğer konularla ilişkisi

- [Kubernetes Temelleri](./01-kubernetes-fundamentals.md) — Ingress/Service kavramları
- [K3s](./02-k3s-lightweight-k8s.md) — gömülü Traefik'i kapatma
- [Kong API Gateway](./06-kong-api-gateway.md) — Traefik'ten sonraki katman
- [ModSecurity WAF](./07-modsecurity-waf.md) — Kong içinde değil, Kong plugin
- [cert-manager TLS](./08-cert-manager-tls.md) — Traefik'in TLS secret'ı buradan
- [NetworkPolicy + mTLS](./11-networkpolicy-mtls.md) — Traefik pod'unun cluster-içi izolasyonu
- [Observability (Loki, Prometheus)](../observability-qa) — Traefik metrik/log

## 9. Daha derine inmek için

- Resmi doc: [https://doc.traefik.io/traefik/](https://doc.traefik.io/traefik/)
- Middleware kataloğu: [https://doc.traefik.io/traefik/middlewares/overview/](https://doc.traefik.io/traefik/middlewares/overview/)
- "Traefik in Kubernetes" YouTube serisi (Containous resmi)
- Search keyword'leri: *"traefik ingressroute crd"*, *"traefik middleware chain"*, *"traefik canary weighted"*, *"traefik dashboard security"*
- Lumix engineering-notes referansı: `21-kubernetes-helm-infra-devops-engineering-guide.md`

## 10. Sözlük

- **Ingress Controller**: K8s'te `Ingress`/`IngressRoute` manifest'lerini gerçek HTTP routing'e çeviren bileşen.
- **`IngressRoute`**: Traefik'in CRD'si, host + path + middleware + service tanımı.
- **Entrypoint**: Traefik'in dinlediği port (web, websecure, metrics).
- **Router**: Match koşulu + middleware + service üçlüsü.
- **Middleware**: İstek/yanıt manipulation zinciri parçası.
- **`Service` (Traefik bağlamında)**: Backend tanımı; K8s Service'i wrap eder.
- **`TraefikService`**: Birden fazla backend birleştirme (weighted, mirror).
- **`TLSStore` / `TLSOption`**: TLS sertifika store ve protokol seçenekleri.
- **SNI (Server Name Indication)**: TLS handshake'inde client'ın istediği hostname'i belirtmesi.
- **HSTS**: HTTP Strict Transport Security — browser'a "her zaman HTTPS" söyleyen header.
- **`StripPrefix`**: URL prefix'ini backend'e iletmeden silen middleware.
- **`IpAllowList`**: IP allowlist middleware'i (eski adı `ipWhiteList`).
- **`RateLimit`**: Saniye başına/dakika başına istek sınırı.
- **MetalLB**: Baremetal K8s cluster'ları için LoadBalancer IP atayan controller.
