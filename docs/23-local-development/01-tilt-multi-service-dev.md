---
title: Tilt ile Multi-Service Lokal Geliştirme
description: Tilt nedir, Tiltfile yazımı, K8s local (k3d — prod K3s parity), live update (hot reload), 10 microservice + Kafka + Postgres + Redis ayağa kaldırma akışı.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Lumix'te 10 microservice + Kafka + 10 PostgreSQL + Redis + Elasticsearch + Temporal + RustFS… **Geliştiricinin laptop'ında her gün bu yığını ayağa kaldırması nasıl mümkün?** Cevap: **Tilt**. Bu sayfa Tilt'i sıfırdan anlatır, **Tiltfile** (Starlark DSL) yapısını gösterir, **k3d** (Docker'da K3s) ile local K8s'i tanımlar, **live update (hot reload)** mekanizmasını açıklar, Lumix'in standart Tilt setup'ını ve **selective bring-up** (sadece çalıştığım servisleri ayağa kaldır) pattern'ini detaylandırır. Hedef kitle: Docker/K8s temellerini bilen, ilk gün laptop'ında "lumix-up" demek isteyen geliştirici.

## 1. Bu nedir? (Sıfırdan)

**Tilt** (tilt.dev), local K8s tabanlı microservice development için **orchestrator + dashboard**. Yapar:
- Source code'u izler.
- Değişiklik gördüğünde container image'ı **selectively rebuild + push** eder.
- K8s manifest'leri uygular.
- **Live update**: bazı dosyalar için yeni image build etmeden pod içindeki dosyayı **canlı update** eder.
- Web UI ile her servisin durumunu, log'unu, restart butonunu sunar.
- Tek `tilt up` komutu ile her şeyi başlatır.

Tilt'in alternatif/akrabaları: Skaffold (Google), DevSpace, Garden. Lumix Tilt'i tercih etti (UI, log aggregation, live update kalitesi).

### Günlük hayattan analoji

Tilt = orkestra şefi (laptop'ında). Her servis bir enstrüman, sen kodu değiştirdiğinde sadece o enstrüman değişimi alıyor (live update). Tüm orkestrayı tek tıklamayla başlat / durdur / restart. Yan etki: enstrümanlar arası uyumsuzluk varsa UI'da kırmızı.

## 2. Hangi problemi çözüyor?

| Acı | Tilt yok | Tilt var |
|---|---|---|
| 10 servisi başlatmak | 10 terminal sekmesi | Tek komut |
| Code change → cluster'a yansıtma | docker build + helm install + kubectl apply | Otomatik |
| Live edit (HTML/YAML) | Image rebuild 2-5 dk | Live update 1-2 saniye |
| Pod log'ları görmek | 10 ayrı `kubectl logs -f` | Tilt UI tek pencere |
| Yeni geliştirici onboarding | "Şu adımları takip et" 2 saat | `tilt up` |
| Bağımlılık karmaşası (DB önce, app sonra) | Manuel sıralama | Resource dependencies |
| Sadece bir servisi çalıştırma | Manuel hangi servisi kaldıracağım | Tilt resource selection |

### Patlamış geliştirici hikayesi

Yeni geliştirici Pazartesi başlıyor; gün boyu local environment kurmaya çalışıyor: Docker, K8s, 10 servis, manifest editleme. Pazartesi akşam hâlâ yarım. Tilt + Lumix Tiltfile olsaydı: `git clone + tilt up` → öğleye sistem ayakta.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Mimari

```
Geliştirici Laptop
├── k3d (local K8s cluster — prod K3s ile aynı dağıtım)
├── Tilt binary (./tilt up)
└── Tiltfile (Starlark DSL)
        │
        ▼
  Tilt service:
    1. Source code watch (fsnotify)
    2. Image build (docker/buildkit)
    3. Push to local registry
    4. K8s manifest apply
    5. Live update for specific files
    6. Logs stream
    7. Web UI (localhost:10350)
```

### 3.2. Tiltfile

Starlark (Python-benzeri) syntax:

```python
# Tiltfile
# Tilt API extensions
load('ext://restart_process', 'docker_build_with_restart')
load('ext://helm_resource', 'helm_resource', 'helm_repo')

# Local K8s context kontrol (Lumix kararı: k3d — bkz. 4.1)
allow_k8s_contexts(['k3d-lumix-local'])

# Build
docker_build(
    'registry.local/lumix/academic-service',
    context='./services/academic',
    dockerfile='./services/academic/Dockerfile',
    live_update=[
        sync('./services/academic/src', '/app/src'),
        run('./gradlew compileJava', trigger=['./services/academic/src'])
    ]
)

# K8s manifest apply
k8s_yaml('./local/k8s/academic-deployment.yaml')

# Resource tanım
k8s_resource(
    'academic-service',
    port_forwards=['8080:8080', '8081:8081', '9090:9090'],
    resource_deps=['kafka', 'postgres-academic']
)
```

### 3.3. Live update detayı

İki mod:
- **`sync`**: dosyayı pod içine kopyala (image rebuild yok).
- **`run`**: pod içinde komut çalıştır (compile, migration).

Spring Boot devtools + Tilt sync: kod değişimi → JVM hot restart (saniyeler). Frontend (Vite): HMR ile saniyenin altında.

Eğer live update fail ederse Tilt otomatik full rebuild + redeploy yapar (fallback).

### 3.4. Resource dependencies

Tiltfile DAG:
```python
k8s_resource('postgres-academic', labels=['data'])
k8s_resource('kafka', labels=['data'])
k8s_resource('academic-service', resource_deps=['postgres-academic', 'kafka'], labels=['app'])
```

Tilt önce data, sonra app başlatır. UI'da gruplama label'la.

### 3.5. Helm chart entegrasyonu

```python
helm_repo('bitnami', 'https://charts.bitnami.com/bitnami')
helm_resource('kafka',
    chart='bitnami/kafka',
    namespace='lumix-data',
    flags=['--values=./local/kafka-values.yaml'],
    deps=['./local/kafka-values.yaml']
)
```

Lumix'te Helm chart'lar zaten var; Tilt onları local'de kullanır (production'la aynı template).

### 3.6. Local registry

`k3d`'nin built-in registry'si (`--registry-create`) ile local registry (`registry:2` container). Tilt image'ı buraya push eder; K8s buradan pull (`registry.local:5000`).

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Local cluster: `k3d` (kararımız)

Lumix lokal K8s cluster'ı olarak **k3d** kullanır. k3d, prod'da koştuğumuz [K3s](../infra-devops/k3s-lightweight-k8s)'i Docker container'larında çalıştırır — yani **prod ile aynı dağıtım**. Alternatif olan `kind` ise *vanilla (upstream)* Kubernetes çalıştırır; prod'umuz K3s olduğu için kind parity sağlamaz (farklı ingress/storage/CNI defaultları). Bu yüzden varsayılan **k3d**'dir; `kind` yalnızca "manifest'ler upstream K8s'te de çalışıyor mu" doğrulaması için opsiyoneldir.

**Parity neyi kapsar**: aynı K3s dağıtımı, aynı bileşenler (Traefik, ServiceLB, local-path, CoreDNS), aynı `--disable` flag davranışı → manifest/Helm chart birebir. **Neyi kapsamaz**: altyapı 1:1 değildir — k3d, K3s'i container içinde (systemd yok, privileged process) çalıştırır; node resource limit'leri sahtedir (`/proc/meminfo` patch'lenir), storage container içinde ephemeral'dır, networking Docker bridge üzerindendir. Node/HA/storage/networking'e duyarlı testleri gerçek bir K3s VM'inde (staging) yap, k3d'de değil.

> **Versiyon pinle**: k3d'nin default `latest` imajını kullanma; prod baseline ([K3s versiyonu](../infra-devops/k3s-lightweight-k8s)) ile aynı imajı sabitle. Docker tag'inde `+` → `-` olur (`v1.30.4+k3s1` → `v1.30.4-k3s1`). `--disable` flag'leri k3d'ye `--k3s-arg` ile geçer ve `@server:*` node-filter zorunludur (yoksa flag sessizce çalışmaz).

```bash
# Cluster oluştur (prod K3s versiyonuna pinli)
k3d cluster create lumix-local \
  --image rancher/k3s:v1.30.4-k3s1 \
  --servers 1 \
  --agents 2 \
  --port 8080:80@loadbalancer \
  --port 8443:443@loadbalancer \
  --port 5000:5000@server:0 \
  --registry-create lumix-registry:0.0.0.0:5000 \
  --k3s-arg "--disable=traefik@server:*" \
  --k3s-arg "--disable=servicelb@server:*"

# kubeconfig
kubectl config use-context k3d-lumix-local
```

### 4.2. Lumix Tiltfile yapısı

```
lumix-monorepo/   (veya çoklu repo top-level)
├── Tiltfile                    # ana entry
├── tilt/
│   ├── modules/                # her servisin Tilt parçası
│   │   ├── academic.tilt
│   │   ├── identity.tilt
│   │   ├── finance.tilt
│   │   └── ...
│   ├── infra/
│   │   ├── postgres.tilt
│   │   ├── kafka.tilt
│   │   ├── redis.tilt
│   │   ├── elasticsearch.tilt
│   │   ├── temporal.tilt
│   │   └── rustfs.tilt
│   └── frontend/
│       ├── customer-admin.tilt
│       └── internal-admin.tilt
└── services/
    ├── academic-service/
    ├── identity-service/
    └── ...
```

`Tiltfile` (ana):
```python
load('./tilt/modules/academic.tilt', 'academic_service')
load('./tilt/modules/identity.tilt', 'identity_service')
load('./tilt/infra/postgres.tilt', 'postgres')
load('./tilt/infra/kafka.tilt', 'kafka')
load('./tilt/infra/redis.tilt', 'redis')

config.define_string_list("services")
cfg = config.parse()
enabled = cfg.get("services", ["all"])

# Infra her zaman
postgres(['identity', 'academic', 'finance', 'audit', 'compliance', 'organization', 'communication'])
kafka()
redis()

# App servisler — opsiyonel
if 'all' in enabled or 'identity' in enabled:
    identity_service()
if 'all' in enabled or 'academic' in enabled:
    academic_service()
# ...
```

Komut:
```bash
tilt up                                    # tüm sistem
tilt up -- --services=identity,academic    # sadece bu ikisi
```

### 4.3. Servis modülü örnek (`academic.tilt`)

```python
def academic_service():
    docker_build(
        'lumix-registry:5000/lumix/academic-service',
        context='./services/academic-service',
        dockerfile='./services/academic-service/Dockerfile.dev',
        live_update=[
            sync('./services/academic-service/build/classes', '/app/classes'),
            sync('./services/academic-service/build/resources', '/app/resources'),
            restart_container()
        ],
        ignore=['./services/academic-service/build/libs/*',
                './services/academic-service/build/tmp/*']
    )

    k8s_yaml(helm(
        './services/academic-service/charts/academic-service',
        name='academic-service',
        namespace='lumix-app',
        values=['./local/values/academic-local.yaml']
    ))

    k8s_resource(
        'academic-service',
        port_forwards=['18080:8080', '18081:8081', '19090:9090'],
        resource_deps=['postgres-academic', 'kafka', 'redis'],
        labels=['app']
    )
```

### 4.4. Local-specific values

`local/values/academic-local.yaml`:
```yaml
replicaCount: 1
image:
  repository: lumix-registry:5000/lumix/academic-service
  pullPolicy: Always
  tag: latest
resources:
  requests: { cpu: 100m, memory: 256Mi }
  limits: { cpu: 1000m, memory: 1Gi }
env:
  SPRING_PROFILES_ACTIVE: local
  SPRING_DEVTOOLS_RESTART_ENABLED: "true"
  LOG_LEVEL: DEBUG
  JAVA_TOOL_OPTIONS: "-XX:+UseG1GC"
ingress:
  enabled: false
postgresql:
  enabled: false   # ayrı tilt resource
```

### 4.5. Dockerfile.dev

```dockerfile
FROM eclipse-temurin:21-jdk
WORKDIR /app
# Layer'lı build (live update için)
COPY build/dependencies /app/
COPY build/spring-boot-loader /app/
COPY build/snapshot-dependencies /app/
COPY build/classes /app/classes/
COPY build/resources /app/resources/
EXPOSE 8080 8081 9090
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

### 4.6. Frontend (React) Tilt

```python
def customer_admin_web():
    local_resource(
        'customer-admin-web',
        serve_cmd='npm run dev -- --host 0.0.0.0 --port 5173',
        deps=['./apps/customer-admin/package.json'],
        readiness_probe=probe(
            http_get=http_get_action(port=5173, path='/'),
            period_secs=2
        )
    )
```

Vite dev server local'de çalışır; backend k3d içinde. Vite proxy → cluster IngressRoute.

### 4.7. Tilt UI

`http://localhost:10350` :
- Her servis tile
- Health (yeşil/kırmızı)
- Log stream
- Trigger update (manuel rebuild)
- Pod restart
- Port-forward link'leri
- Resource dependency graph

### 4.8. Seed data

```python
local_resource(
    'seed-data',
    cmd='./scripts/seed-local-data.sh',
    resource_deps=['postgres-academic', 'identity-service'],
    auto_init=True,
    trigger_mode=TRIGGER_MODE_AUTO
)
```

Her `tilt up` sonrası seed otomatik çalışır.

### 4.9. Teardown

```bash
tilt down                       # kaynakları cluster'dan kaldır
k3d cluster delete lumix-local  # cluster sil
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Docker Compose** | K8s parity yok; production'la farklı manifest. |
| **Skaffold** | Tilt'le çok benzer; UI Tilt kadar polished değil. |
| **DevSpace** | Güçlü ama Tilt topluluk + Lumix ekibi tercihi. |
| **Garden** | Benzer; daha karmaşık config. |
| **Telepresence** | Local code + remote cluster; debug için faydalı, ama her şey local yerine hybrid. Lumix Tilt + (opsiyonel) Telepresence. |
| **Manuel kubectl + watch script** | Tilt'in yerini almıyor. |

### Kabul ettiğimiz trade-off'lar

- **Laptop resource**: 16 GB RAM minimum; 32 GB önerilen. Küçük makinede tüm yığını kaldırmak zor.
- **Tiltfile bakım**: prod chart'larıyla senkronize tutmak gerekiyor.
- **k3d altyapı sınırlamaları**: k3d K3s'i container'da çalıştırır → node resource limit'leri sahte, storage ephemeral, systemd yok. Bileşen/config parity tam, ama altyapı 1:1 değil; node/HA/storage testleri gerçek K3s VM'inde.

### Tekrar değerlendirme tetikleyicileri

- Geliştirici sayısı 30+ olunca **remote dev environment** (örn. Coder, Gitpod) seçeneği değerlendirilir.
- Tilt projesinin geleceği belirsizleşirse → DevSpace veya Skaffold.

## 6. Pratik örnek

### 6.1. Tam Tiltfile (özet)

```python
load('ext://restart_process', 'restart_container')
load('ext://helm_resource', 'helm_resource')
load('ext://namespace', 'namespace_create')

allow_k8s_contexts(['k3d-lumix-local'])

# Namespace'ler
namespace_create('lumix-data')
namespace_create('lumix-app')
namespace_create('lumix-system')
namespace_create('lumix-temporal')

# Local registry
default_registry('lumix-registry:5000')

# Infra
include('./tilt/infra/postgres.tilt')
include('./tilt/infra/kafka.tilt')
include('./tilt/infra/redis.tilt')
include('./tilt/infra/elasticsearch.tilt')
include('./tilt/infra/temporal.tilt')
include('./tilt/infra/rustfs.tilt')

# Apps
config.define_string_list("services")
cfg = config.parse()
enabled = cfg.get("services", ["all"])

all_services = ['identity', 'organization', 'academic', 'assessment',
                'counseling', 'performance', 'communication',
                'finance', 'file', 'audit', 'compliance']

for svc in all_services:
    if 'all' in enabled or svc in enabled:
        include('./tilt/modules/{}.tilt'.format(svc))

# Frontend
include('./tilt/frontend/customer-admin.tilt')

# Seed
local_resource(
    'seed',
    cmd='./scripts/seed-local-data.sh',
    resource_deps=['identity-service', 'academic-service'],
    labels=['ops']
)
```

### 6.2. Postgres infra (tilt parça)

```python
# tilt/infra/postgres.tilt
def postgres_for(service_name):
    name = 'postgres-' + service_name
    helm_resource(name,
        chart='oci://registry-1.docker.io/bitnamicharts/postgresql',
        flags=[
            '--set=auth.postgresPassword=postgres',
            '--set=auth.database=' + service_name,
            '--set=primary.persistence.enabled=false',  # local: persistence yok
            '--set=primary.resources.requests.memory=128Mi',
            '--set=primary.resources.requests.cpu=100m',
        ],
        namespace='lumix-data',
        port_forwards=[port_forward(5400 + hash(service_name) % 100, 5432)],
        labels=['data']
    )

# tüm servisler için aç
for s in ['identity', 'organization', 'academic', 'finance',
          'file', 'audit', 'compliance']:
    postgres_for(s)
```

### 6.3. Kafka infra (Strimzi single-node)

```python
# tilt/infra/kafka.tilt
k8s_yaml('./local/kafka/kafka-strimzi.yaml')
k8s_resource('kafka',
    port_forwards=['9094:9094'],
    labels=['data']
)

k8s_yaml('./local/kafka/topics-bootstrap.yaml')
k8s_resource('kafka-topics-bootstrap',
    resource_deps=['kafka'],
    labels=['data']
)
```

### 6.4. Spring Boot devtools + sync

`build.gradle.kts`:
```kotlin
dependencies {
    developmentOnly("org.springframework.boot:spring-boot-devtools")
}
```

`application-local.yml`:
```yaml
spring:
  devtools:
    restart:
      enabled: true
      additional-paths: [/app/classes]
      exclude: META-INF/maven/**,META-INF/resources/**
```

Tilt `sync` → `build/classes` → pod `/app/classes` → devtools auto-restart.

### 6.5. Komut akışı (geliştirici)

```bash
# Cluster oluştur (bir kerelik)
./scripts/setup-local-cluster.sh

# Tüm sistem
tilt up

# Sadece academic + identity
tilt up -- --services=identity,academic

# Specific resource trigger
# UI → "academic-service" → "Trigger Update"

# Servisi yeniden başlatma
# UI → restart button (veya `tilt trigger academic-service`)

# Sistem kapat
tilt down
```

### 6.6. CI'da Tilt CI mode

Pull Request gözden geçirme cluster'larında `tilt ci`:
```bash
tilt ci --timeout 5m
```

Tilt tüm resource'ları başlatır, hazır olana kadar bekler, test çalıştırır, biter.

### 6.7. Useful Tilt commands

```bash
tilt up                          # interactive
tilt up --stream                 # log stream stdout
tilt up --no-browser             # browser açma
tilt down                        # teardown
tilt args --services=identity    # config update
tilt trigger <resource>          # manual rebuild
tilt logs <resource>             # specific log
tilt enable <resource>           # paused → active
tilt disable <resource>          # active → paused
```

## 7. Dikkat edilecek tuzaklar

- **Laptop RAM yetersiz**: 8GB ile tüm yığın imkansız. 16-32 GB.
- **k3d cluster IP karışıklığı**: lokalhost vs cluster internal DNS. Service discovery için service DNS kullan.
- **`live_update` ile JVM hot reload uyumsuzluğu**: bazı kod değişiklikleri (annotation, classpath) restart gerektirir; Tilt fallback'i tetikler ama uzun sürer. `restart_container()` selective.
- **Local registry TLS yok**: cluster pull `insecure-registry` listede olmalı.
- **Persistence açık + cluster recreate**: data kaybı. Lokalde persistence kapalı; "test verisi" sürekli yenidir.
- **Frontend port mapping çakışması**: Vite 5173 ile başka servis çakışırsa Vite başlamaz.
- **`tilt up` sonrası seed otomatik çalışmıyor**: `auto_init=True` ve `resource_deps` doğru.
- **Çok büyük sync klasör**: tüm `node_modules` kopyalanır → laptop yavaşlar. `.tiltignore` veya `ignore=[...]`.
- **Tilt UI 10350 başka port'ta blocked**: env `TILT_PORT=10351`.
- **Tiltfile'a side effect koymak**: file system değişiklikleri yapan komutlar `local_resource` ile değil, doğrudan top-level → her Tilt eval'da tekrar çalışır.
- **Production manifest'lerin local'de değişimi**: chart Lumix shared; values override ile değiştir; chart edit yasak.
- **Helm dependency güncel değil**: `helm dependency update` gerekir; Tilt extension veya `local_resource` ile otomatize.

## 8. Diğer konularla ilişkisi

- [Kubernetes Temelleri](../infra-devops/kubernetes-fundamentals) — pod, service, ingress kavramları
- [K3s](../infra-devops/k3s-lightweight-k8s) — k3d ile parity
- [Helm Charts](../infra-devops/helm-charts) — local Tilt aynı chart'ları kullanır
- [Test Data Management](./test-data-management) — seed data stratejisi
- [GitLab CI Pipelines](../21-ci-cd/gitlab-ci-pipelines) — Tilt CI mode

## 9. Daha derine inmek için

- Resmi doc: [https://docs.tilt.dev/](https://docs.tilt.dev/)
- Tilt API reference: [https://docs.tilt.dev/api.html](https://docs.tilt.dev/api.html)
- k3d: [https://k3d.io/](https://k3d.io/)
- "Kubernetes for Developers" — Joseph Heck
- Tilt extension galery
- Search keyword'leri: *"tilt live update sync"*, *"k3d local registry"*, *"tilt helm_resource"*, *"spring boot devtools tilt"*, *"tilt resource_deps"*

## 10. Sözlük

- **Tilt**: Local K8s development orchestrator + UI.
- **Tiltfile**: Tilt configuration (Starlark — Python-benzeri DSL).
- **Live update**: Image rebuild etmeden pod içi dosyayı sync/run komutu ile güncelleme.
- **`docker_build`**: Tilt image build fonksiyonu.
- **`k8s_yaml` / `helm` / `helm_resource`**: K8s manifest dahil etme yöntemleri.
- **`k8s_resource`**: Kaynak konfig (port-forward, deps, labels).
- **`local_resource`**: Cluster'da değil, host'ta çalışan komut/process.
- **`resource_deps`**: Tilt-level dependency (X bitmeden Y başlamaz).
- **`port_forwards`**: Pod port → localhost mapping.
- **`labels`**: Tilt UI'da grup başlığı.
- **`sync`**: Live update — dosya kopyala.
- **`run`**: Live update — pod içinde komut.
- **`restart_container`**: Live update sonrası container restart.
- **k3d**: Docker üzerinde K3s; Lumix'in lokal K8s cluster'ı (prod K3s ile aynı dağıtım → parity).
- **kind**: Docker üzerinde *vanilla* (upstream) K8s; Lumix'te varsayılan değil — yalnızca upstream uyum testi için opsiyonel.
- **`tilt ci`**: CI mode, interactive değil; bitince exit.
- **devtools (Spring Boot)**: Code change → JVM hot restart.
- **Local registry**: Cluster içinde / yanında çalışan minimal image registry.
