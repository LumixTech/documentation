---
title: NetworkPolicy ve mTLS Düşünceleri
description: K8s NetworkPolicy (Calico), pod-to-pod izolasyon, ileride mTLS (Istio veya Linkerd), şimdilik Network Policy yeterli.
sidebar_position: 11
---

## Bu sayfa ne anlatıyor?

K8s'te default olarak **tüm pod'lar birbirine konuşabilir**. Bu, "side car compromise" senaryosunda **lateral movement**'ı kolaylaştırır. Lumix bu açığı **NetworkPolicy** (Calico CNI ile) ile kapatır; daha ileri savunma (kimlik-bazlı **mTLS**) ihtiyacı doğduğunda **Istio veya Linkerd** ekleme alanını saklar. Bu sayfa NetworkPolicy'yi sıfırdan anlatır, Calico'nun rolünü gösterir, Lumix'in **namespace + label tabanlı policy seti**'ni detaylandırır, ve **mTLS** kararının neden ertelendiğini açıklar. Hedef kitle: K8s temellerini bilen ([Kubernetes Temelleri](./01-kubernetes-fundamentals.md)), CNI ve service mesh kavramlarına aşina mühendis.

## 1. Bu nedir? (Sıfırdan)

**NetworkPolicy**, K8s standart kaynak türlerinden biri. Mantığı:

> "Bu pod set'i (selector) **sadece şu pod set'lerinden gelen** trafiği ve **sadece şu pod set'lerine giden** trafiği kabul eder."

K8s NetworkPolicy bir spec'tir; **işleyen tarafı CNI plugin'idir**:
- **Calico**, **Cilium**, **Antrea**: NetworkPolicy implementasyonu.
- **Flannel (K3s default)**: NetworkPolicy desteklemez. Lumix bu yüzden Calico veya kvisr CNI'ye geçer.

NetworkPolicy iki yön içerir:
- **Ingress**: hangi kaynaktan trafik kabul edilir.
- **Egress**: hangi hedefe trafik izinli.

**Default deny**: bir namespace'te NetworkPolicy yoksa "her şey serbest". Lumix kuralı: her namespace **default deny + explicit allow**.

### mTLS nedir?

**mTLS (Mutual TLS)**: hem client hem server TLS sertifikası gösterir. Avantajı:
- **Identity-bound**: paket sahibi belli (sertifika subject'i = service identity).
- **Encrypted in flight**: pasif dinleme imkansız.
- **Authentication + Authorization birleşik**.

NetworkPolicy IP/port'a bakar; mTLS **kimliğe** bakar. NetworkPolicy "X namespace'inden Y namespace'ine" demek için iyi; "X servis identity'sinden Y servis identity'sine" demek için zayıf (her IP değişiminde policy update).

### Günlük hayattan analoji

Apartman: NetworkPolicy = kat kapıları (sadece o katın sakinleri girer). mTLS = içeri girmiş herkesin **kimlik kartını** kontrol etmek (kapıdan değil de salonda da). Beraber kullanıldığında: tam savunma.

## 2. Hangi problemi çözüyor?

K8s default ağda: bir pod compromise edilirse → **tüm pod'lara erişebilir**. Senaryolar:

| Acı | NetworkPolicy yok | NetworkPolicy var |
|---|---|---|
| Compromise pod yan servislere erişir | Lateral movement serbest | Sadece izinli pod'lara |
| Database pod doğrudan internete | Egress serbest | Egress block (sadece allowed) |
| Test namespace'i prod'a sızar | Erişim açık | Cross-namespace deny |
| Sızdırılan token ile başka pod taklit | Tüm endpoint'lere erişir | NetworkPolicy + (ideal: mTLS) bunu sınırlar |
| Compliance (KVKK) "ağ izolasyonu" | "Yok" | Policy + audit kanıt |

### Patlamış üretim hikayesi

Bir takım K8s'i Calico ile kurdu ama NetworkPolicy yazmadı. Bir geliştirici test microservice'ine eski log4j paketi koydu (test/staging). Log4Shell payload'ı geldi, pod compromise oldu. Saldırgan aynı namespace'teki PostgreSQL pod'una bağlandı, veri çekti. NetworkPolicy "academic-service → finance-db erişimi yok" deseydi: sızıntı engellenmezdi ama lateral movement sınırlanırdı. Lumix bu acıyı default-deny ile dışlar.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. NetworkPolicy spec

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: academic-allow
  namespace: lumix-app
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: academic-service
  policyTypes: [Ingress, Egress]
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              name: lumix-system
          podSelector:
            matchLabels:
              app.kubernetes.io/name: kong
      ports:
        - port: 8080
          protocol: TCP
  egress:
    - to:
        - podSelector:
            matchLabels:
              app: postgresql
              service: academic
      ports:
        - port: 5432
          protocol: TCP
    - to:
        - podSelector:
            matchLabels:
              app: kafka
      ports:
        - port: 9092
          protocol: TCP
    - to: []   # DNS için tüm cluster ama port:53
      ports:
        - port: 53
          protocol: UDP
        - port: 53
          protocol: TCP
```

### 3.2. Calico'nun rolü

Calico bir **CNI plugin** (Container Network Interface). Yapar:
- Pod IP allocation (IPAM)
- BGP routing (veya VXLAN/IPIP overlay)
- NetworkPolicy enforcement (iptables/eBPF datapath)
- Calico'nun kendi `GlobalNetworkPolicy` CRD'si (cluster-wide, K8s NP'sinin ötesi)
- IP set, FQDN-based egress (Calico Enterprise; OSS'de sınırlı)

K3s default flannel'i kapatıp Calico kuruyoruz (`--flannel-backend=none --disable-network-policy`).

### 3.3. Egress NetworkPolicy ve DNS

DNS unutmak yaygın: pod DNS resolve edemezse "ECONNREFUSED" gibi belirsiz hatalar. Egress policy'ye kube-system'in CoreDNS'ine 53 portu **her zaman** ekle.

### 3.4. Policy önceliği

Birden fazla NetworkPolicy aynı pod'a uygulanırsa: **OR**.
- Pod selector eşleşen tüm NP'lerin allow kuralları birleşir.
- Bir kuralın bir paketi izin vermesi → izin.
- Hiçbir NP eşleşmiyorsa: default allow (NP olmayan namespace).
- Bir NP eşleşip ingress allow vermiyorsa: default deny (o yöne).

### 3.5. Default-deny namespace

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
  namespace: lumix-app
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
```

Bu NP eşleşen tüm pod'larda hem ingress hem egress için "allow yok" demektir → her şey kapanır. Sonra explicit allow NP'leri eklenir.

### 3.6. mTLS — neden ertelenmiş?

mTLS implementasyonu için iki yol:
- **Service mesh** (Istio, Linkerd, Consul Connect, Cilium ServiceMesh): sidecar/eBPF ile otomatik mTLS.
- **Manuel mTLS**: her servise cert dağıt + client/server TLS config.

Lumix kararı: **şimdilik manuel mTLS'i sadece KRİTİK servislerde** (örn. Vault, Kafka). Tüm servisler arası mTLS yok. Sebepler:
- Operasyonel ek yük yüksek (sidecar yönetimi, debug zorluğu).
- NetworkPolicy + Calico + sıkı RBAC + her pod kendi SA = makul savunma.
- Servis sayısı 10 → mesh'in yarattığı kompleksite şu an haklı değil.
- Mesh ekleme gelecekte mümkün — chart'lar etiketli, hazırız.

Tekrar değerlendirme tetikleyicileri:
- Servis sayısı 30+ olunca veya
- Compliance KVKK/PDPL "all in-cluster traffic encrypted" şartı koyarsa
- → Linkerd (basit, hafif) öncelikli aday; Istio karşılaştırma.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. CNI: Calico kurulum

K3s install:
```bash
--flannel-backend=none --disable-network-policy
```

Sonra Calico operator:
```bash
kubectl create -f https://raw.githubusercontent.com/projectcalico/calico/v3.28.0/manifests/tigera-operator.yaml

cat <<EOF | kubectl apply -f -
apiVersion: operator.tigera.io/v1
kind: Installation
metadata:
  name: default
spec:
  calicoNetwork:
    ipPools:
      - blockSize: 26
        cidr: 10.42.0.0/16
        encapsulation: VXLANCrossSubnet
        natOutgoing: Enabled
EOF
```

### 4.2. Namespace standartları

Her Lumix namespace'inde `name` label'ı (kubernetes-sigs/network-policy-recipes standardı):

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: lumix-app
  labels:
    name: lumix-app
    lumix.io/zone: app
    pod-security.kubernetes.io/enforce: restricted
```

NetworkPolicy `namespaceSelector` bu label'a bakar.

### 4.3. Lumix NetworkPolicy seti

Her namespace için **default-deny**, sonra explicit allow:

```
lumix-app/
├── 00-default-deny.yaml
├── 10-allow-dns.yaml                    (tüm pod'ların DNS'i)
├── 20-kong-to-microservices.yaml        (Kong → app pods)
├── 30-microservice-to-postgres.yaml     (her servis kendi DB'sine)
├── 40-microservice-to-kafka.yaml
├── 50-microservice-to-redis.yaml
├── 60-microservice-to-temporal.yaml
├── 70-prometheus-scrape.yaml            (Prometheus → her pod metrics port)
└── 80-allow-egress-vault.yaml           (Vault'a HTTPS)

lumix-data/
├── 00-default-deny.yaml
├── 10-allow-dns.yaml
├── 20-postgres-from-app.yaml
├── 30-kafka-from-app.yaml
├── 40-redis-from-app.yaml
├── 50-prometheus-scrape.yaml
└── 60-backup-egress.yaml                (Velero RustFS'e push)
```

### 4.4. Pod label standardı

NetworkPolicy seçim için kullanılan label'lar:

```yaml
labels:
  app.kubernetes.io/name: academic-service     # NP podSelector için ana label
  app.kubernetes.io/part-of: lumix
  lumix.io/component: backend                  # backend | datastore | gateway
  lumix.io/needs-db: "true"                    # DB egress NP eşleşmesi
  lumix.io/needs-kafka: "true"
```

### 4.5. Çıkış (egress) Internet trafiği

Lumix kuralı: **microservice pod'ları doğrudan internet'e çıkamaz**. Outbound HTTP gerekiyorsa (örn. ödeme provider API, push notification):
- Bir egress gateway pod'u (Squid/Envoy) → policy'de allow.
- Lokal Vault → policy'de allow.

Bu, hem güvenlik (compromise pod → C2 sunucusu) hem cost (egress traffic gözlemi) hem audit için kritik.

### 4.6. mTLS — bugün hangi yerlerde?

| Yer | mTLS aktif mi? | Nasıl |
|---|---|---|
| Vault → consumer | Evet | Vault PKI, cert-manager destek |
| Kafka broker ↔ client | Evet | TLS + SASL/SCRAM; internal CA sertifikaları |
| PostgreSQL (replica/Patroni) | Evet (replication için) | internal CA |
| gRPC microservice-to-microservice | **Hayır (şimdilik)** | NetworkPolicy yeterli |
| Kong ↔ microservice | Hayır | cluster içi |
| WebSocket browser ↔ backend | Hayır (TLS Traefik'te biter) | normal TLS, mTLS yok |

### 4.7. Observability

Calico'nun **flow logs** özelliği (Enterprise) yerine Lumix OSS'de:
- iptables log + journald
- Calico `felix` log
- Pod-level connection metrics (`pod_network_receive_bytes_total` Prometheus)
- NetworkPolicy hits Grafana dashboard

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi (şu an için) |
|---|---|
| **Flannel (NetworkPolicy yok)** | Lumix izolasyon istiyor; flannel'i Calico ile değiştirdik. |
| **Cilium** | eBPF-based, çok güçlü, identity-aware policy + L7. Mükemmel ama Lumix şu anki gereksinimi Calico ile karşılanıyor; Cilium öğrenme eğrisi ve eBPF kernel sürüm bağımlılığı ek karmaşıklık. **Tekrar değerlendirme aday**. |
| **Antrea** | OVS-based; daha az yaygın. |
| **Istio (service mesh)** | mTLS + L7 policy + observability güçlü. Sidecar başına ek pod + envoy karmaşıklık. Bugün gerekli değil. |
| **Linkerd (service mesh)** | Daha hafif Istio alternatifi. Rust-based proxy, otomatik mTLS. **Lumix mesh adı zorunlu olunca en muhtemel seçim**. |
| **Consul Connect** | HashiCorp ekosistemi; Vault ile birlikte gelse de service mesh bağlam'ı ayrı operasyonel yatırım. |
| **Kuma** | CNCF projesi; topluluk küçük. |

### Kabul ettiğimiz trade-off'lar

- **Kimlik-bazlı policy yok**: NetworkPolicy IP/label ile çalışır; pod IP değişimi sırasında geçici izinsizlik (Calico saniyeler içinde günceller).
- **Cluster içi trafik şifrelenmemiş**: pod-to-pod trafiği plain. NetworkPolicy ile lateral movement engellenir; ama bir node compromise olursa içeriği okunabilir.
- **mTLS olmaması incident response'da debug avantajı**: trafik wireshark/tcpdump ile okunabilir.

### Tekrar değerlendirme tetikleyicileri

- Servis sayısı 30+ olduğunda (mesh konuşması yeniden başlar).
- KVKK/PDPL düzenlemesi "in-cluster encryption" şart koşarsa.
- Multi-tenant model değişip aynı cluster'da birden fazla müşteri yaşarsa (mesh zorunlu).

## 6. Pratik örnek

### 6.1. lumix-app default-deny

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
  namespace: lumix-app
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
```

### 6.2. DNS allow (tüm pod'lar için)

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-dns
  namespace: lumix-app
spec:
  podSelector: {}
  policyTypes: [Egress]
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              name: kube-system
          podSelector:
            matchLabels:
              k8s-app: kube-dns
      ports:
        - port: 53
          protocol: UDP
        - port: 53
          protocol: TCP
```

### 6.3. Kong → academic-service allow

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-kong-to-academic
  namespace: lumix-app
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: academic-service
  policyTypes: [Ingress]
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              name: lumix-system
          podSelector:
            matchLabels:
              app.kubernetes.io/name: kong
      ports:
        - port: 8080
          protocol: TCP
```

### 6.4. academic-service → postgres-academic egress

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: academic-to-postgres
  namespace: lumix-app
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: academic-service
  policyTypes: [Egress]
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              name: lumix-data
          podSelector:
            matchLabels:
              app: postgresql
              service: academic
      ports:
        - port: 5432
          protocol: TCP
```

### 6.5. Prometheus scrape allow

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-prometheus-scrape
  namespace: lumix-app
spec:
  podSelector: {}
  policyTypes: [Ingress]
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              name: lumix-observability
          podSelector:
            matchLabels:
              app.kubernetes.io/name: prometheus
      ports:
        - port: 8081
          protocol: TCP
        - port: 9100
          protocol: TCP
```

### 6.6. Calico GlobalNetworkPolicy örneği

Calico cluster-wide policy CRD'si:

```yaml
apiVersion: projectcalico.org/v3
kind: GlobalNetworkPolicy
metadata:
  name: deny-egress-to-cloud-metadata
spec:
  selector: all()
  types: [Egress]
  egress:
    - action: Deny
      destination:
        nets: [169.254.169.254/32]   # AWS/GCP metadata service
    - action: Allow
      destination:
        notNets: [169.254.169.254/32]
```

Lateral cloud metadata service erişimini engeller (SSRF saldırısı için kritik).

### 6.7. Test komutu

```bash
# Pod içinden
kubectl -n lumix-app exec -it deploy/academic-service -- /bin/sh
> nc -zv postgres-academic.lumix-data 5432
# Beklenen: succeeded

> nc -zv 8.8.8.8 443
# Beklenen: refused (egress NP yok)

> nslookup kafka.lumix-data
# Beklenen: resolve (DNS NP açık)
```

### 6.8. Policy görselleştirme

`Cilium Hubble` veya `netshoot` + `calicoctl` ile akış görselleştirilir. Daha basit: 

```bash
kubectl describe networkpolicy -n lumix-app
calicoctl get networkpolicy -n lumix-app -o yaml
```

## 7. Dikkat edilecek tuzaklar

- **NetworkPolicy yazıp DNS'i unutmak**: pod DNS resolve edemez, `UnknownHostException`. DNS egress allow her namespace'te zorunlu.
- **`podSelector: {}` ile default deny + sonra unutmak**: tüm pod'lar kilitli, yeni servis eklendiğinde sebebi bulmak zor. CI'da NetworkPolicy lint zorunlu.
- **`namespaceSelector` ve `podSelector` aynı `from` içinde "AND" mantığı**: iki ayrı `from` item "OR" demektir. Dikkat:

```yaml
ingress:
  - from:
      - namespaceSelector: { name: foo }
        podSelector: { name: bar }      # foo namespace VE bar pod
ingress:
  - from:
      - namespaceSelector: { name: foo }
      - podSelector: { name: bar }      # foo namespace VEYA bar pod
```

- **Calico ile flannel çakışması**: K3s install'da `--flannel-backend=none` ve `--disable-network-policy` zorunlu.
- **`hostNetwork: true` pod'lar NetworkPolicy'i atlar**: prometheus node-exporter gibi pod'lar host network kullanır; NetworkPolicy onlara işlemez. Host firewall (UFW) ek koruma.
- **MetalLB / LoadBalancer trafik NetworkPolicy'i atlamaz ama doğru source IP'yi göremeyebilir**: `externalTrafficPolicy: Local` ile orijinal IP korunur.
- **Çıkış (egress) policy'i çok katı + bir feature için internet ister**: ödeme provider için bir gün hata. Egress gateway pattern + audit.
- **NetworkPolicy'i runtime'da `kubectl edit`**: drift. ArgoCD + Git zorunlu.
- **mTLS'in NetworkPolicy yerine geçtiğini sanmak**: mTLS kimlik kontrolü, ama hala "kim kime erişebilir" sorusu NP'siz cevapsız. İkisi tamamlayıcı.
- **Test cluster'ında NetworkPolicy yok diye prod'a benzer şekilde test yapmamak**: prod'a deploy edince sürpriz. Lumix kuralı: dev cluster'da da NP açık.
- **CIDR aralığı yanlış**: 10.42.0.0/16 K3s pod CIDR; servis CIDR farklı. NP'de ipBlock yazarken doğru aralık.

## 8. Diğer konularla ilişkisi

- [Kubernetes Temelleri](./01-kubernetes-fundamentals.md) — Pod/Service/Network kavramları
- [K3s](./02-k3s-lightweight-k8s.md) — flannel kapatma + Calico kurma
- [Ubuntu Hardening](./10-ubuntu-server-hardening.md) — node-level firewall (UFW) NP ile katmanlı
- [cert-manager TLS](./08-cert-manager-tls.md) — mTLS sertifikaları (Vault/Kafka için bugün)
- [Helm Charts](./03-helm-charts.md) — NetworkPolicy chart'lara nasıl gömülür
- [Authentication](../authentication-authorization) — kimlik kontrolü uygulama-level
- [Observability](../observability-qa) — NP hit metric, Calico flow log

## 9. Daha derine inmek için

- K8s NetworkPolicy doc: [https://kubernetes.io/docs/concepts/services-networking/network-policies/](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
- Calico docs: [https://docs.tigera.io/calico/latest/](https://docs.tigera.io/calico/latest/)
- network-policy-recipes: [https://github.com/ahmetb/kubernetes-network-policy-recipes](https://github.com/ahmetb/kubernetes-network-policy-recipes)
- Linkerd: [https://linkerd.io/](https://linkerd.io/)
- Istio: [https://istio.io/](https://istio.io/)
- "Service Mesh Patterns" — Lee Calcote
- Search keyword'leri: *"kubernetes networkpolicy default deny"*, *"calico vs cilium"*, *"linkerd mtls automatic"*, *"calico globalnetworkpolicy"*, *"k8s policy podselector and"*

## 10. Sözlük

- **CNI (Container Network Interface)**: K8s'in pod network'ünü kuran plugin standardı.
- **Calico**: BGP/VXLAN tabanlı CNI; NetworkPolicy enforcement.
- **Cilium**: eBPF tabanlı CNI; identity-aware policy.
- **Flannel**: K3s default CNI; NetworkPolicy desteklemez.
- **NetworkPolicy (CRD)**: K8s'te pod-to-pod trafik kuralı tanımı.
- **GlobalNetworkPolicy**: Calico'nun cluster-wide NetworkPolicy CRD'si.
- **podSelector / namespaceSelector**: NP'nin etkilediği pod/namespace seçimi.
- **default-deny**: NP eşleşen pod'lar için "hiçbir yön açık değil" başlangıcı.
- **Ingress / Egress (NP bağlamında)**: NP'nin gelen / giden trafik kuralları.
- **mTLS (Mutual TLS)**: İki taraflı TLS authentication.
- **Service Mesh**: Hizmetler arası iletişimi katmanlı yöneten dağıtık proxy mimarisi (Istio, Linkerd, vb.).
- **Sidecar (mesh)**: Her pod'a eklenen ek proxy container; mTLS, observability sağlar.
- **eBPF**: Linux kernel içinde safe program çalıştırma teknolojisi; modern CNI/observability tabanı.
- **BGP (Border Gateway Protocol)**: Calico'nun pod IP route'larını yayın için kullandığı protokol.
- **Egress gateway**: Tüm dış çıkış trafiğinin geçtiği merkezi pod (audit, policy).
