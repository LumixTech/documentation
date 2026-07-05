---
title: "Kubernetes & Helm: Infra/DevOps Engineering Guide"
description: Kubernetes orchestration, object model, networking, deployment lifecycle, production debugging, Helm ve infra/devops akışları için mühendislik rehberi.
sidebar_position: 8
---

## Giriş

Bu dokümanın amacı Kubernetes'i ezber değil, mühendislik perspektifiyle anlamaktır.

Hedef, production ortamda şu davranışları kavramaktır:

- trafik nasıl akar,
- deployment nasıl yapılır,
- hata nasıl debug edilir,
- infra, DevOps ve uygulama tasarımı birbiriyle nasıl ilişkilidir.

## Kubernetes Neden Var?

Docker problemin bir kısmını çözer:

```text
Uygulamayı container içinde çalıştır
```

Production ortamda ise daha fazlası gerekir:

```text
Kaç tane çalışacak?
Ölürse ne olacak?
Trafik nereye gidecek?
Update nasıl yapılacak?
```

Kubernetes bunu orchestration ile çözer:

```text
Desired State -> Sen tanımlarsın
Actual State -> Kubernetes izler
Reconciliation -> Kubernetes farkı kapatır
```

Kritik fikir:

```yaml
replicas: 4
```

şu anlama gelir:

```text
Her zaman 4 pod çalışmalı
```

## Kubernetes Object Model

Yaygın sahiplik zinciri şudur:

```text
Deployment
   |
   v
ReplicaSet
   |
   v
Pod
   |
   v
Container
```

### Pod

Pod, Kubernetes'in en küçük çalışma birimidir.

Özellikleri:

- ephemeral'dır,
- kendi IP'si vardır,
- bir veya daha fazla container içerebilir,
- aynı pod içindeki container'lar lifecycle ve network namespace paylaşır.

### ReplicaSet

ReplicaSet istenen pod sayısını korur.

Bir pod ölürse, desired count korunacak şekilde yeni pod oluşturur.

### Deployment

Deployment rollout davranışını yönetir.

Şunları yapabilir:

- ReplicaSet oluşturur,
- rolling update yapar,
- yeni version rollout eder,
- eski version instance'larını azaltır,
- gerektiğinde rollback yapar.

### Label ve Selector

Label objeleri tanımlar:

```yaml
labels:
  app: payment-api
```

Selector eşleşen objeleri bulur:

```yaml
selector:
  app: payment-api
```

Label ve selector eşleşmezse:

```text
Service -> Pod bulamaz -> trafik uygulamaya ulaşmaz
```

## Networking ve Traffic Flow

Standart dış trafik akışı:

```text
User
 -> Ingress
 -> Service
 -> Pod
 -> Container
```

### Service

Service, pod'lar üzerinde stabil bir network endpoint sağlar.

Pod IP'leri değişir. Service identity stabil kalır.

Örnek:

```yaml
port: 80
targetPort: 8080
```

Anlamı:

```text
Service trafiği 80 portundan alır
Service trafiği container'ın 8080 portuna iletir
```

Kritik hata:

```text
Service selector != Pod label
```

Sonuç:

```text
Trafik hiçbir pod'a ulaşmaz
```

### Ingress

Ingress host ve path routing yapar.

Örnek:

```text
api.example.com/payments -> payment-service
```

Ingress genellikle Service'lerin önünde durur ve external HTTP(S) trafiği cluster içine yönlendirir.

## Configuration ve Secrets

Config Docker image içine gömülürse:

```text
Her config değişikliğinde image rebuild gerekir
```

Kubernetes configuration'ı image'dan ayırır:

```text
ConfigMap -> normal config
Secret -> hassas veri
```

Önemli detay:

```text
ConfigMap veya Secret değişince pod her zaman otomatik restart olmaz
```

Çoğu durumda rollout restart gerekir:

```bash
kubectl rollout restart deployment payment-api
```

## Deployment Lifecycle

### Rolling Update

Rolling update bir version'dan diğerine kademeli geçiş yapar:

```text
v1 -> v2
```

Yaygın strategy parametreleri:

```yaml
maxSurge: 1
maxUnavailable: 0
```

Anlamı:

- `maxSurge: 1`: rollout sırasında desired replica sayısının üstüne bir ekstra pod çıkılabilir.
- `maxUnavailable: 0`: rollout sırasında desired replica'ların tamamı available tutulmaya çalışılır.

### Probes

Readiness probe:

```text
Pod trafik alabilir mi?
```

Liveness probe:

```text
Pod canlı mı?
```

Startup probe:

```text
Uygulama açıldı mı?
```

Kritik gerçek:

```text
Running != Ready
```

Pod running olabilir ama trafik almaya hazır olmayabilir.

## Production Debugging

İlk komutlar:

```bash
kubectl get pods
kubectl describe pod <pod-name>
kubectl logs <pod-name>
kubectl get svc
kubectl get endpoints
```

Yaygın hata durumları:

### CrashLoopBackOff

```text
Uygulama açılır, crash olur ve Kubernetes tekrar tekrar restart eder
```

### ImagePullBackOff

```text
Kubernetes container image'ını çekemez
```

### Pending

```text
Pod schedule edilemez
```

Yaygın sebepler: yetersiz resource, node selector, taint veya PVC problemleridir.

### OOMKilled

```text
Container memory limitini aşmıştır
```

En kritik debug zinciri:

```text
Ingress
 -> Service
 -> Endpoint
 -> Pod
 -> Container
```

Trafik çalışmıyorsa bu zincir sırayla incelenmelidir.

## Helm

### Helm Nedir?

Helm şudur:

```text
Kubernetes package manager + template engine
```

### Helm Neden Gerekli?

Environment başına YAML çoğaltmaktan kaçın:

```text
deployment-dev.yaml
deployment-prod.yaml
deployment-staging.yaml
```

Bunun yerine:

```text
Tek template + environment-specific values
```

### Chart Yapısı

```text
chart/
 ├── Chart.yaml
 ├── values.yaml
 ├── templates/
```

### values.yaml

```yaml
replicaCount: 4
image:
  tag: 1.4.7
```

### Template

```yaml
replicas: {{ .Values.replicaCount }}
```

Render akışı:

```text
Helm -> YAML üretir -> Kubernetes uygular
```

### Chart vs Release

```text
Chart = deployment tarifi
Release = bu tarifin çalışan instance'ı
```

Yaygın komutlar:

```bash
helm install
helm upgrade
helm rollback
```

Production best practice:

```text
latest tag kullanma
immutable image tag kullan
```

## Infra ve DevOps Perspektifi

Tipik pipeline:

```text
Code
 -> CI
 -> Docker image
 -> Registry
 -> Helm deploy
 -> Kubernetes
```

### Resource Yönetimi

Uygulamalar resource requests ve limits tanımlamalıdır:

```yaml
resources:
  requests:
    cpu: "250m"
    memory: "512Mi"
  limits:
    cpu: "500m"
    memory: "1Gi"
```

Requests, Kubernetes'in pod'u doğru node'a schedule etmesine yardım eder. Limits, izin verilen maksimum resource kullanımını belirler.

### Namespace

Namespace environment veya team ayrımı sağlar:

```text
dev / staging / prod
```

### Observability

Production sistemlerde şunlar gerekir:

```text
Logs
Metrics
Tracing
```

Observability yoksa Kubernetes hataları sadece daha dağıtık ve incelemesi daha zor hale getirir.

### GitOps

GitOps modeli:

```text
Git = source of truth
Cluster = Git'e sync olur
```

Argo CD veya Flux gibi araçlar Git'i izler ve cluster state'i reconcile eder.

## Modüler Monolit vs Mikroservis

### Modüler Monolit

```text
Tek uygulama
Tek port
Tek deployment
```

İletişim process içinde gerçekleşir:

```text
HTTP değil
-> method call / interface / internal event
```

Kubernetes şekli:

```text
1 Deployment
1 Service
1 Ingress
```

### Mikroservis

```text
Her servis ayrı Deployment
Her servis ayrı port
Service-to-service communication gerekir
```

Kubernetes iki stili de çalıştırmak için primitive sağlar, fakat distributed system karmaşıklığını ortadan kaldırmaz.

## En Kritik Mental Model

Production'daki ana ders şudur:

```text
Pod çalışıyor -> sistem çalışıyor
```

geçerli bir sonuç değildir.

Daha doğru soru:

```text
Trafik zinciri doğru mu?
```

Her zaman şunu doğrula:

```text
Ingress -> Service -> Endpoint -> Pod -> Container
```

## Teknik Terimler Sözlüğü

- Deployment: rollout ve ReplicaSet yönetimini yapan Kubernetes objesi.
- ReplicaSet: istenen sayıda pod replica'sını ayakta tutan obje.
- Pod: Kubernetes'in en küçük runtime birimi.
- Container: pod içinde çalışan paketlenmiş application process'i.
- Service: pod'lar üzerinde stabil network abstraction.
- Endpoint: Service arkasındaki gerçek pod IP ve port hedefleri.
- Ingress: cluster içine HTTP(S) routing sağlayan giriş noktası.
- ConfigMap: hassas olmayan configuration objesi.
- Secret: hassas configuration objesi.
- Readiness Probe: pod'un trafik alıp alamayacağını belirleyen check.
- Liveness Probe: pod'un restart edilip edilmeyeceğini belirleyen check.
- Startup Probe: yavaş açılan uygulamaları erken restart'tan koruyan check.
- Helm Chart: tekrar kullanılabilir Kubernetes deployment paketi.
- Helm Release: chart'ın cluster'a kurulmuş çalışan instance'ı.
- Namespace: Kubernetes içinde mantıksal izolasyon sınırı.
- GitOps: Git'in desired cluster state kaynağı olduğu operasyon modeli.

## Final Özet

```text
Deployment -> lifecycle
ReplicaSet -> replica garantisi
Pod -> runtime
Service -> network abstraction
Ingress -> dış trafik
ConfigMap -> config
Secret -> hassas veri
Helm -> deployment yönetimi
```

## Araştırma Keywordleri

- `kubernetes traffic flow ingress service pod`
- `kubernetes readiness vs liveness probe`
- `kubernetes debugging crashloopbackoff`
- `helm chart best practices`
- `kubernetes deployment rolling update strategy`
- `kubernetes service selector labels`
- `kubernetes production debugging checklist`
- `modular monolith vs microservices communication`

## Sonuç

Kubernetes sadece container'ların çalıştığı yer değildir. Kubernetes, actual cluster state'i declared desired state'e yaklaştırmaya çalışan bir reconciliation sistemidir.

Production Kubernetes mühendisliği; object ownership, traffic flow, rollout davranışı, resource limitleri, observability ve debugging zincirlerini anlamayı gerektirir. En faydalı alışkanlık, desired state'ten actual state'e doğru düşünmek ve trafiği Ingress'ten Container'a kadar takip etmektir.
