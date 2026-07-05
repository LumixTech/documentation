---
title: Kubernetes Temelleri
description: Kubernetes nedir, Pod / Deployment / ReplicaSet / Service / Ingress nesneleri, control plane, reconciliation loop ve Lumix bağlamında trafik akışı.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Kubernetes (kısaca **K8s**) Lumix'in **işletim sisteminin işletim sistemi** gibidir: 10 mikroservisi, Kafka'yı, PostgreSQL'i, Redis'i, gözlemlenebilirlik yığınını ve daha fazlasını **container** olarak çalıştıran orkestrasyon platformudur. Bu sayfa K8s'in temel kavramlarını **sıfırdan** anlatır, bir Pod'un Service üzerinden Ingress'e kadar nasıl ulaştığını gösterir, **declarative + reconciliation** felsefesini açıklar ve Lumix kararlarına (K3s, Traefik, Kong, Helm, ArgoCD) hangi noktalardan bağlandığını gösterir. Hedef kitle: **K8s'i hiç görmemiş** Java/Spring geliştirici. K3s, Helm, Rancher gibi daha üst başlıklar ayrı sayfalarda anlatılır.

## 1. Bu nedir? (Sıfırdan)

Bir microservice mimari kurmak şu sorunları doğurur: "Bu container hangi sunucuda çalışsın? Bir sunucu çökerse otomatik nerede başlasın? 10 servisin 30 replica'sı arasında trafik nasıl yönlensin? Ortam değişkenleri, secret'lar, config'ler nereden gelsin? CPU şişerse otomatik kaç pod ekleneceğine kim karar verir?"

**Kubernetes** bu sorunlara **declarative** (bildirimsel) bir yanıt verir:

> "Ben **istediğin durumu** YAML olarak söyle, ben **gerçek durumun istediğine eşitlenmesini** sağlarım."

Sen YAML'da "academic-service'in 3 replica olsun" yazarsın. K8s controller'ları sürekli kontrol eder; 2 pod kaldıysa 1 tane daha başlatır. Bir node çökerse pod'ları başka node'a kaydırır. Bu kalbi sürekli atan "istenen vs gerçek durum karşılaştırması" **reconciliation loop** olarak bilinir.

### Günlük hayattan analoji

Termostat. "Oda sıcaklığı 22°C olsun" dersin. Sıcaklık 20'ye düşerse ısıtıcı açılır, 24'e çıkarsa kapanır. Termostat **mevcut durumu** ölçer, **istenen durumu** bilir, ikisi arasındaki farkı kapatır. Kubernetes 10000 termostatın aynı anda çalışmasıdır: her controller bir kavramın bekçisi.

### Container nedir?

Container, uygulamayı **kendi bağımlılıklarıyla birlikte paketleyen** hafif bir izolasyon birimidir. Lumix'te `academic-service-1.4.2.jar` + JRE 21 + minimum OS = bir OCI image. Çalıştırıldığında "container" olur. Linux çekirdeğinin **namespace** (proses/dosya/ağ izolasyonu) ve **cgroup** (CPU/RAM kotaları) özelliklerini kullanır. VM'den çok daha hafiftir (saniyeler içinde başlar, MB'larca disk kullanır).

K8s container çalıştırmaz; **runtime'a** çalıştırtır. Standart arayüz **CRI (Container Runtime Interface)**, gerçek runtime ise genellikle **containerd**'dir. K3s containerd'yi gömülü getirir.

## 2. Hangi problemi çözüyor?

Lumix gibi 10 microservice + ~12 altyapı bileşeni olan bir sistem **K8s olmadan** çalıştırılabilir mi? Teknik olarak evet, ama acılar şunlar olur:

| Acı | K8s'siz dünya | K8s'li dünya |
|---|---|---|
| **Node çökmesi** | Manuel SSH, manuel restart | Pod otomatik başka node'a kaydırılır |
| **Yatay scale** | El ile `docker run` × N | `kubectl scale --replicas=N` veya HPA |
| **Trafik yönlendirme** | Nginx config el ile düzenle | Service + Endpoint otomatik |
| **Service discovery** | DNS / sabit IP / Consul kur | Kubernetes DNS gömülü |
| **Rolling deploy** | Manuel sıralı restart | Deployment strategy: RollingUpdate |
| **Rollback** | Eski jar'ı tut, geri yükle | `kubectl rollout undo` |
| **Secret/Config** | `.env` dosyaları, dağıtık | Secret/ConfigMap nesneleri |
| **Sağlık kontrolü** | Cron + Slack alert | Liveness/Readiness probe gömülü |
| **Çoklu müşteri** | Müşteri başına ayrı VM, manuel | Müşteri başına ayrı cluster (Lumix kararı) |

Lumix'in **müşteri başına izole installation** kararı (bkz. [Installation/Tenant/Scope](../tenancy-and-domain-model/installation-tenant-scope)), bir müşterinin tüm yığınını **tek bir K8s cluster içine paketlemeyi** doğal kılar. Cluster = installation sınırı.

### Patlamış üretim hikayesi (anti-pattern)

Bir takım K8s'siz, 6 sunucuda systemd ile 8 microservice yönetiyordu. Cuma 22:00'de bir sunucu çöktü. O sunucudaki 3 servisin yedek replica'sı **başka sunucuda hazır değildi** (orchestrator yok). Manuel SSH, manuel taşıma, sertifika sorunları, DNS gecikmesi… 4 saatlik downtime. K8s olsaydı: pod'lar 30 saniyede başka node'a taşınırdı. Lumix bu acıyı tasarımla **dışlar**.

## 3. Nasıl çözüyor? (Çalışma prensibi)

K8s **iki katmanlı** bir sistemdir: **control plane** (beyin) ve **data plane** (kaslar = node'lar).

```
┌──────────────────────── CONTROL PLANE ────────────────────────┐
│                                                               │
│   ┌──────────────┐   ┌──────────────┐   ┌────────────────┐    │
│   │  kube-apiserver│  │  etcd (state)│   │ scheduler      │    │
│   │  (REST gateway)│  │  (KV store)  │   │ (pod -> node)  │    │
│   └──────┬─────────┘  └──────┬───────┘   └────────┬───────┘    │
│          │                   │                    │            │
│          ▼                   ▼                    ▼            │
│   ┌──────────────────────────────────────────────────┐         │
│   │ controller-manager (Deployment, ReplicaSet,      │         │
│   │ Endpoint, ServiceAccount, Node controller, ...)  │         │
│   └──────────────────────────────────────────────────┘         │
└───────────────────────────────────────────────────────────────┘
                            │
                            │ kubelet REST (CRI)
                            ▼
┌──────────────────────── DATA PLANE ────────────────────────────┐
│  Node-1                 Node-2                   Node-3        │
│  ┌─────────────┐        ┌─────────────┐         ┌─────────────┐│
│  │ kubelet     │        │ kubelet     │         │ kubelet     ││
│  │ kube-proxy  │        │ kube-proxy  │         │ kube-proxy  ││
│  │ containerd  │        │ containerd  │         │ containerd  ││
│  │             │        │             │         │             ││
│  │ [academic-1]│        │ [academic-2]│         │ [academic-3]││
│  │ [identity-1]│        │ [kafka-2]   │         │ [finance-1] ││
│  └─────────────┘        └─────────────┘         └─────────────┘│
└────────────────────────────────────────────────────────────────┘
```

### 3.1. Control plane bileşenleri

- **kube-apiserver**: tüm REST trafiğinin tek girişi. `kubectl`, controller'lar, kubelet — herkes buradan konuşur. Authentication + Authorization + Admission burada işler.
- **etcd**: dağıtık key-value veritabanı. Cluster'ın **tüm durumu** burada. (K3s etcd yerine SQLite veya gömülü etcd kullanır.)
- **scheduler**: yeni Pod'un hangi node'da çalışacağına karar verir. Filtreler (taints/tolerations, resource talebi) + skorlar (node balance).
- **controller-manager**: birçok küçük controller'ın toplandığı süreç. Her controller bir kavramı (Deployment, Job, Endpoint, Namespace) izler.

### 3.2. Node bileşenleri

- **kubelet**: node ajanı. apiserver'dan "bu pod'u çalıştır" emrini alır, containerd'ye iletir, sağlık raporlarını geri gönderir.
- **kube-proxy**: Service IP'sini iptables/IPVS kuralları olarak node'a yazar.
- **containerd**: gerçek container runtime (CRI üzerinden).

### 3.3. Reconciliation loop

Her controller şu döngüyü yaşar:

```
1. Watch:    apiserver'a "X kaynağı değişirse haber ver" subscribe ol.
2. Diff:     Mevcut durum (etcd) vs istenen durum (spec).
3. Reconcile: Farkı kapatacak aksiyonu üret (pod oluştur, sil, güncelle).
4. Status:   Sonucu apiserver'a yaz (status alanı).
```

Bu yüzden K8s'te **command vermezsin**, **istek belirtirsin**. "Reboot et" yok; "bu pod artık 3 değil 5 replica" var. Diğer her şey controller'ların işi.

### 3.4. Temel nesneler (resource kinds)

| Nesne | Sorumluluk | Lumix örneği |
|---|---|---|
| **Pod** | 1 veya daha fazla container'ın aynı network/storage namespace'inde çalışması | `academic-service` pod'u (1 container) |
| **ReplicaSet** | N adet Pod'u yaşatır | 3 academic-service replica |
| **Deployment** | ReplicaSet'leri versiyonlu yönetir (rolling update) | `academic-service` Deployment |
| **StatefulSet** | Stable identity + ordered start + stable PVC | `postgres-academic-0`, `kafka-0` |
| **DaemonSet** | Her node'da 1 pod | `promtail`, `node-exporter` |
| **Job / CronJob** | Tek seferlik / zamanlı görev | Flyway migration job, retention CronJob |
| **Service** | Pod'lara stable IP/DNS verir | `academic-service.default.svc.cluster.local` |
| **Endpoint** | Service'in hedef Pod IP listesi (otomatik) | — |
| **Ingress / IngressRoute** | L7 HTTP routing | Traefik IngressRoute (bkz. [Traefik](./traefik-ingress)) |
| **ConfigMap** | Plain config | `application.yml` parçaları |
| **Secret** | base64 encoded sırlar | DB şifresi (ama biz Vault + ESO kullanırız) |
| **PersistentVolume / PersistentVolumeClaim** | Disk talepleri | PostgreSQL veri diski |
| **Namespace** | Mantıksal cluster bölümlemesi | `lumix-app`, `lumix-data`, `lumix-observability` |
| **ServiceAccount + Role + RoleBinding** | Pod'ların cluster API'ye yetkisi | `argocd`, `cert-manager` SA'leri |

### 3.5. Pod'un yaşam döngüsü

```
Pending → ContainerCreating → Running → (Terminating) → Succeeded / Failed
```

Probe'lar:
- **livenessProbe**: "container ölü mü?" — başarısız olursa kubelet container'ı restart eder
- **readinessProbe**: "trafik alabilir mi?" — başarısız olursa Service Endpoint'inden çıkarılır
- **startupProbe**: yavaş başlayan uygulamalar için "henüz başlatma fazında" sinyali (Spring Boot ısınıyor → liveness'i pasif tutar)

### 3.6. Bir HTTP isteğinin Pod'a ulaşma yolu (Lumix)

```
Internet
   │
   ▼
[ Cloud LB / VPS public IP ]
   │
   ▼
[ Traefik Ingress (NodePort/HostPort) ]   ← TLS termination, host routing
   │
   ▼
[ Kong Gateway Service (ClusterIP) ]      ← rate-limit, JWT validate
   │
   ▼
[ academic-service Service (ClusterIP) ]  ← kube-proxy iptables DNAT
   │
   ▼
[ academic-service Pod (1 of N) ]         ← Spring Boot
```

`Service` aslında bir IP/DNS adıdır; arkasında **Endpoint controller** tarafından otomatik güncellenen Pod IP listesi vardır. `kube-proxy` her node'da bu IP'leri iptables/IPVS kurallarıyla yönlendirir. Pod ölüp yeniden doğsa bile Service IP **değişmez** — bu yüzden microservice'ler birbirini **DNS adıyla** çağırır (sabit IP değil).

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Hangi K8s dağıtımı?

Lumix'te **K3s** kullanılır. Production seviyesinde, sertifikalı, hafif (~80 MB binary) bir K8s dağıtımıdır. Detay için [K3s sayfasına](./k3s-lightweight-k8s) bakın. Karar gerekçesi: müşteri başına **VPS** kurulumunda full K8s (kubeadm) operasyonel ağırlık yaratır; K3s tek binary ile production-grade çalışır.

### 4.2. Cluster topolojisi (müşteri başına)

| Müşteri boyutu | Node sayısı | Kurulum |
|---|---|---|
| Küçük (&lt;5K kullanıcı) | 1 node (control plane + worker birleşik) | K3s single-node |
| Orta (5K-50K) | 3 node (1 control plane, 2 worker) | K3s HA |
| Büyük (&gt;50K) | 3 control plane + N worker | K3s HA + ayrı worker pool |

### 4.3. Namespace yapısı

```
lumix-app             # 10 microservice pod'u + Kong
lumix-data            # PostgreSQL, Kafka, Redis, Elasticsearch StatefulSet'leri
lumix-observability   # Prometheus, Loki, Tempo, Grafana
lumix-system          # cert-manager, Vault, External Secrets Operator
lumix-temporal        # Temporal cluster
kube-system           # K3s'in kendi sistem pod'ları (CoreDNS, Traefik)
```

Detaylı namespace politikası ve NetworkPolicy'ler [NetworkPolicy + mTLS](./networkpolicy-mtls) sayfasında.

### 4.4. Label / Selector standardı

Tüm Lumix kaynakları standart label set'i taşır:

```yaml
metadata:
  labels:
    app.kubernetes.io/name: academic-service
    app.kubernetes.io/instance: academic-service-prod
    app.kubernetes.io/version: "1.4.2"
    app.kubernetes.io/component: backend
    app.kubernetes.io/part-of: lumix
    app.kubernetes.io/managed-by: argocd
    lumix.io/tenant-scope: shared        # veya per-tenant kaynaklar için tenant-id
    lumix.io/installation-id: omer-okullari
```

Bu label'lar Prometheus relabel, ArgoCD pruning, NetworkPolicy seçim ve Grafana drill-down için kritik.

### 4.5. Resource request / limit politikası

Hiçbir Lumix pod'u **`resources` alanı boş** olarak deploy edilmez. Aksi takdirde:
- Scheduler "fit" değerlendirmesi yapamaz
- HPA (Horizontal Pod Autoscaler) çalışmaz
- Bir kötü pod node'u boğabilir

Standart:
```yaml
resources:
  requests:
    cpu: "250m"
    memory: "512Mi"
  limits:
    cpu: "1000m"
    memory: "1Gi"
```

JVM pod'larında `-XX:MaxRAMPercentage=75.0` ile container memory limit'ine uyumluluk sağlanır.

### 4.6. Probe standardı

Spring Boot Actuator endpoint'leri kullanılır:

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8081
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081
  failureThreshold: 30
  periodSeconds: 5
```

`failureThreshold * periodSeconds = 150s` boyunca startup'a tolerans → yavaş JVM ısınması.

### 4.7. Hangi durumda K8s'in kendi nesnesini kullanmıyoruz?

| K8s yerli | Lumix tercihi | Sebep |
|---|---|---|
| Secret | Vault + External Secrets Operator | base64 ≠ encryption; multi-cluster KMS |
| Ingress | Traefik IngressRoute CRD | middleware, daha güçlü L7 |
| CronJob | Temporal scheduled workflow | retry, audit, idempotency |
| Manuel `kubectl apply` | ArgoCD GitOps | Git = source of truth |

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Docker Compose** | Tek host; HA, scheduling, scale yok. Müşteri kurulumu için yetersiz. |
| **HashiCorp Nomad** | Daha basit, ama ekosistem (Helm, ArgoCD, cert-manager, Prometheus operator) küçük. |
| **Plain VMs + Ansible** | Reconciliation yok; scale manuel; ekip için yüksek operasyonel yük. |
| **AWS ECS / Fargate** | Bulut-kilit. Lumix self-host odaklı (KVKK, on-prem). |
| **Tam K8s (kubeadm)** | K3s ile aynı API; ama tek binary, küçük footprint, VPS uyumu daha iyi → K3s seçildi (detay: [K3s sayfası](./k3s-lightweight-k8s)). |
| **OpenShift** | Lisans + Red Hat ekosistem kilidi. Self-host basitliği bozar. |

### Kabul ettiğimiz trade-off'lar

- **Öğrenme eğrisi**: ekip K8s öğrenmek zorunda. Bu doc serisi onun için var.
- **Operasyonel kompleksite**: cluster bakımı, upgrade, etcd backup gerekir. Rancher Manager bu yükü hafifletir.
- **Resource overhead**: control plane ~500 MB RAM + ~0.5 CPU yer.

### Tekrar değerlendirme tetikleyicileri

- Müşteri sayısı 100+ olduğunda: tek dev sunucusu yerine **federation** veya **vCluster** modeli düşünülebilir.
- Mesh ihtiyacı doğarsa (mTLS, observability per-call): Istio veya Linkerd (bkz. [NetworkPolicy + mTLS](./networkpolicy-mtls)).

## 6. Pratik örnek

### 6.1. Minimal academic-service Deployment + Service

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: academic-service
  namespace: lumix-app
  labels:
    app.kubernetes.io/name: academic-service
    app.kubernetes.io/part-of: lumix
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      app.kubernetes.io/name: academic-service
  template:
    metadata:
      labels:
        app.kubernetes.io/name: academic-service
        app.kubernetes.io/version: "1.4.2"
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/path: "/actuator/prometheus"
        prometheus.io/port: "8081"
    spec:
      serviceAccountName: academic-service
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: app
          image: registry.lumix.io/lumix/academic-service:1.4.2
          imagePullPolicy: IfNotPresent
          ports:
            - name: http
              containerPort: 8080
            - name: mgmt
              containerPort: 8081
            - name: grpc
              containerPort: 9090
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "prod"
            - name: JAVA_TOOL_OPTIONS
              value: "-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
            - name: SPRING_DATASOURCE_URL
              valueFrom:
                secretKeyRef:
                  name: academic-db
                  key: jdbcUrl
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: academic-db
                  key: password
          resources:
            requests:
              cpu: "300m"
              memory: "768Mi"
            limits:
              cpu: "1500m"
              memory: "1536Mi"
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: mgmt
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: mgmt
            periodSeconds: 5
          startupProbe:
            httpGet:
              path: /actuator/health/liveness
              port: mgmt
            failureThreshold: 30
            periodSeconds: 5
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: academic-service
  namespace: lumix-app
spec:
  selector:
    app.kubernetes.io/name: academic-service
  ports:
    - name: http
      port: 80
      targetPort: http
    - name: grpc
      port: 9090
      targetPort: grpc
```

### 6.2. Kullanışlı `kubectl` komutları

```bash
# Cluster context değiştir (Rancher kubeconfig)
kubectl config use-context omer-okullari-prod

# Namespace içindeki pod'ları gör
kubectl -n lumix-app get pods -o wide

# Bir pod'un canlı log'unu izle
kubectl -n lumix-app logs -f deploy/academic-service --max-log-requests=3

# Bir pod'a gir
kubectl -n lumix-app exec -it deploy/academic-service -- /bin/sh

# Deployment ölçeklendir
kubectl -n lumix-app scale deploy/academic-service --replicas=5

# Rolling update durumu
kubectl -n lumix-app rollout status deploy/academic-service

# Rollback
kubectl -n lumix-app rollout undo deploy/academic-service

# Tüm event'leri zaman sırasıyla gör
kubectl -n lumix-app get events --sort-by=.lastTimestamp

# Pod neden Pending? Açıklama
kubectl -n lumix-app describe pod academic-service-6c8b4-xkj7p

# Endpoint listesi (Service hangi pod IP'lerini gösteriyor?)
kubectl -n lumix-app get endpoints academic-service

# Cluster-level node durumu
kubectl get nodes -o wide
kubectl top nodes
kubectl top pods -A
```

### 6.3. Tek namespace içinde HPA örneği

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: academic-service-hpa
  namespace: lumix-app
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: academic-service
  minReplicas: 3
  maxReplicas: 12
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Percent
          value: 25
          periodSeconds: 60
```

## 7. Dikkat edilecek tuzaklar

- **`resources` belirtmeden deploy etmek**: HPA çalışmaz, scheduler optimum dağıtım yapamaz, OOMKill kaçınılmaz. Lumix CI policy: `requests` ve `limits` zorunlu (Conftest/Gatekeeper ile kontrol).
- **`latest` tag kullanmak**: deterministik deploy yok, rollback zor. Lumix kuralı: SemVer tag, immutable image. Detay: [Helm versioning](../21-ci-cd/helm-versioning).
- **Probe'ları yanlış kurmak**:
  - liveness'i DB bağlantısına bağlamak → DB hıçkırığı pod'u sürekli restart eder. Cascade fail. Liveness sadece **process içi** sağlığı kontrol etmeli. DB sağlığı readiness'a.
  - startupProbe yoksa yavaş Spring Boot başlangıcı liveness'i tetikler → sonsuz restart loop.
- **Pod IP'sine bel bağlamak**: pod yeniden doğunca IP değişir. Servisler birbirini **Service DNS** ile çağırır.
- **Tek replica + emptyDir state**: pod ölünce veri gider. State varsa StatefulSet + PVC.
- **Privileged container**: Lumix politikası: `runAsNonRoot: true`, `readOnlyRootFilesystem: true`, `capabilities.drop: [ALL]`. Pod Security Standards "restricted" profili (admission controller ile zorunlu).
- **`kubectl edit` ile prod düzenlemek**: cluster Git'ten sapar (drift). Lumix kuralı: değişiklikler **sadece Git repository'den ArgoCD ile** uygulanır. Detay: [ArgoCD GitOps](../21-ci-cd/argocd-gitops).
- **Namespace karışıklığı**: `default` namespace'e kaynak deploy etmek yasak. Her uygulamanın kendi namespace'i var.
- **Çok büyük container image**: build cache yok, hızlı dağıtım yok. Lumix kuralı: &lt;250 MB image (distroless / Eclipse Temurin JRE base).
- **CrashLoopBackOff hatasını liveness ile karıştırmak**: CrashLoop = container exit kodu ≠ 0. Liveness fail değil. Önce `kubectl logs --previous` ile gerçek hatayı oku.
- **`imagePullPolicy: Always` + private registry imagePullSecret eksik**: pod ImagePullBackOff'ta takılır. Lumix kuralı: SA'ya bağlı `imagePullSecret`.

## 8. Diğer konularla ilişkisi

- [K3s — Lightweight K8s](./k3s-lightweight-k8s) — Lumix'in seçtiği dağıtım
- [Helm Charts](./helm-charts) — Lumix manifest paketleme
- [Rancher Multi-Cluster](./rancher-multi-cluster) — birden fazla installation cluster'ını yönetme
- [Traefik Ingress](./traefik-ingress) — cluster içine HTTP trafiği
- [Kong API Gateway](./kong-api-gateway) — uygulama-katmanı L7
- [NetworkPolicy + mTLS](./networkpolicy-mtls) — pod-to-pod izolasyonu
- [ArgoCD GitOps](../21-ci-cd/argocd-gitops) — manifest'leri Git'ten uygulamak
- [Tilt local dev](../23-local-development/tilt-multi-service-dev) — geliştirici makinesinde K8s deneyimi

## 9. Daha derine inmek için

- **Resmi doc**: [https://kubernetes.io/docs/concepts/](https://kubernetes.io/docs/concepts/)
- **The Kubernetes Book** — Nigel Poulton
- **Programming Kubernetes** — Michael Hausenblas (operator yazımı için)
- **Kubernetes Up & Running** — Burns/Beda/Hightower
- KubeCon konuşma kayıtları (YouTube CNCF kanalı)
- Search keyword'leri: *"reconciliation loop pattern"*, *"kubernetes admission controllers"*, *"pod security standards"*, *"kube-proxy iptables vs ipvs"*
- Lumix engineering-notes referansı: `21-kubernetes-helm-infra-devops-engineering-guide.md`

## 10. Sözlük

- **Pod**: Bir veya birden fazla container'ın aynı network/storage namespace'inde çalışan en küçük schedulable birim.
- **Deployment**: ReplicaSet'leri versiyonlu yöneten ve rolling update sunan controller.
- **ReplicaSet**: N adet aynı pod'un canlı kaldığını garanti eden controller.
- **Service**: Pod'lara stable virtual IP/DNS sunan abstraction.
- **Endpoint**: Service'in hedef Pod IP listesi (otomatik güncellenir).
- **Ingress / IngressRoute**: L7 HTTP routing; Lumix Traefik IngressRoute kullanır.
- **kubelet**: Her node'da çalışan ajan; apiserver komutlarını runtime'a iletir.
- **kube-proxy**: Service IP → Pod IP yönlendirmesi (iptables/IPVS).
- **etcd**: Cluster durumunun saklandığı KV store.
- **Reconciliation loop**: "İstenen vs gerçek" sürekli karşılaştırma + farkı kapatma döngüsü.
- **CRI (Container Runtime Interface)**: K8s ↔ container runtime standart arayüzü.
- **Namespace**: Aynı cluster içinde mantıksal bölümleme.
- **HPA (Horizontal Pod Autoscaler)**: Metric'e göre replica sayısını otomatik ayarlayan controller.
- **Taint / Toleration**: Node'un belirli pod'ları reddetmesi / pod'un buna uyum sağlaması mekanizması.
- **StatefulSet**: Stable identity ve PVC ile veri-stateful uygulamalar için Deployment alternatifi.
- **PVC (PersistentVolumeClaim)**: Pod'un disk talebi.
- **Probe (liveness/readiness/startup)**: kubelet'in pod sağlığını ölçtüğü mekanizmalar.
