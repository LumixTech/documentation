---
title: cert-manager — TLS Sertifika Otomasyonu
description: cert-manager nedir, Let's Encrypt (external) + internal CA, ClusterIssuer, Certificate CRD, auto-renewal, mTLS sertifika.
sidebar_position: 8
---

## Bu sayfa ne anlatıyor?

Lumix'in dış API'ları **public TLS** (Let's Encrypt) sertifikası taşır; servisler arası mTLS için **internal CA** ile imzalı sertifikalar gerekir. Bu sayfa **cert-manager**'ı sıfırdan anlatır, **Issuer / ClusterIssuer / Certificate** CRD'lerini gösterir, ACME (HTTP-01, DNS-01) challenge mekanizmalarını açıklar, Lumix'in **iki katmanlı** TLS yaklaşımını (external + internal CA) detaylandırır ve **auto-renewal** + **rotation** akışını tarif eder. Hedef kitle: K8s temellerini bilen, TLS/PKI'da temel kavramlara aşina mühendis.

## 1. Bu nedir? (Sıfırdan)

**cert-manager**, Kubernetes için **TLS sertifika lifecycle yönetimi** sağlayan controller. Sertifika **almak, yenilemek, K8s Secret olarak yazmak, rotation yapmak** otomatik.

Üç temel kavramı vardır:
- **Issuer / ClusterIssuer**: Sertifikayı kim verecek? (Let's Encrypt, internal CA, Vault, self-signed.) Issuer namespace-scoped, ClusterIssuer cluster-wide.
- **Certificate**: Bir DNS adı (veya birden fazla) için sertifika talebi.
- **CertificateRequest / Order / Challenge** (otomatik üretilen): ACME süreci için ara nesneler.

cert-manager arka planda:
1. Certificate CRD'sini okur.
2. Issuer'a "şu DNS için sertifika ver" der.
3. Let's Encrypt ise ACME challenge'ı çözer (HTTP-01 veya DNS-01).
4. Sertifikayı K8s `Secret` olarak yazar.
5. Renewal zamanı geldiğinde aynı süreci tekrarlar.

### Günlük hayattan analoji

Kimlik kartı bürosu: "şu DNS adının sahibi olduğunu kanıtla, sana resmi imzalı kimlik vereyim, süresi dolunca otomatik yenileyeceğim". Sen sadece "bu kimliği istiyorum" YAML'ı yazıyorsun; geri kalan otomatik.

## 2. Hangi problemi çözüyor?

TLS sertifika yönetiminin manuel hali:

| Acı | cert-manager'sız | cert-manager'lı |
|---|---|---|
| Yeni domain için cert | certbot SSH + dosya kopyala + reload | Certificate CRD apply |
| Renewal | Manuel cron + cert dosya değişimi | Otomatik (30 gün öncesinden) |
| Cert sızıntısı | Tüm cert'leri manuel yenile | Issuer rotation + Certificate reissue |
| Multi-domain SAN | certbot --cert-name management | DNS names listesi YAML'da |
| Wildcard | DNS-01 manuel TXT record | Provider plugin otomatik |
| Internal CA mTLS | OpenSSL + tar dağıtım | Internal CA Issuer + Certificate |
| Sertifika expiry alert | Manuel monitor | cert-manager Prometheus metric |
| Multi-cluster aynı domain | Manuel dağıtım | cert-manager + DNS-01 her cluster'a |

### Patlamış üretim hikayesi

Cuma günü 22:00, bir SaaS'ın **production sertifikası süresi geçti**. Renewal job çalışmamıştı (cron silinmiş). Tüm tarayıcılarda "Untrusted" uyarısı. Acil müdahale: ekip uyandırıldı, manuel certbot çalıştırıldı, ingress reload — 90 dakika downtime. cert-manager: 30 gün önceden uyarı + otomatik renewal + Secret değiştirince Traefik anında günceller. Lumix bu acıyı dışlar.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Genel akış

```
┌─────────────────────────────────────────────────────────────────┐
│   cert-manager pods (lumix-system namespace)                    │
│                                                                 │
│   ┌────────────┐  ┌──────────────┐  ┌────────────────────────┐  │
│   │ controller │  │ webhook      │  │ cainjector             │  │
│   │ (issuer,   │  │ (admission   │  │ (CA bundle injection   │  │
│   │  cert      │  │  validation) │  │  to MutatingWebhook)   │  │
│   │  reconcile)│  │              │  │                        │  │
│   └─────┬──────┘  └──────────────┘  └────────────────────────┘  │
└─────────┼───────────────────────────────────────────────────────┘
          │
          │ reconciliation loop
          ▼
  Certificate CRD ─▶ CertificateRequest ─▶ Order ─▶ Challenge
                                                      │
                                          ┌───────────┴───────────┐
                                          │                       │
                                          ▼                       ▼
                                   HTTP-01 challenge        DNS-01 challenge
                                   (HTTP solver pod)        (DNS API: TXT record)
                                          │                       │
                                          └───────────┬───────────┘
                                                      ▼
                                              Let's Encrypt verifies
                                                      │
                                                      ▼
                                              Cert issued → K8s Secret
```

### 3.2. ClusterIssuer örnekleri

**Let's Encrypt staging** (test):

```yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-staging
spec:
  acme:
    server: https://acme-staging-v02.api.letsencrypt.org/directory
    email: ops@lumix.io
    privateKeySecretRef:
      name: letsencrypt-staging-key
    solvers:
      - http01:
          ingress:
            class: traefik
```

**Let's Encrypt production**:

```yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: ops@lumix.io
    privateKeySecretRef:
      name: letsencrypt-prod-key
    solvers:
      - dns01:
          cloudflare:
            email: dns-admin@lumix.io
            apiTokenSecretRef:
              name: cloudflare-api-token
              key: api-token
        selector:
          dnsZones:
            - lumix.io
```

DNS-01 wildcard sertifika almak için zorunlu.

### 3.3. Internal CA (mTLS için)

Self-signed CA hierarchy:

```yaml
# 1. Root CA Issuer (self-signed)
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: lumix-selfsigned
spec:
  selfSigned: {}
---
# 2. Root CA Certificate
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: lumix-root-ca
  namespace: cert-manager
spec:
  isCA: true
  commonName: Lumix Internal Root CA
  secretName: lumix-root-ca-secret
  duration: 87600h    # 10 yıl
  privateKey:
    algorithm: ECDSA
    size: 384
  issuerRef:
    name: lumix-selfsigned
    kind: ClusterIssuer
---
# 3. CA Issuer (root'tan signed)
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: lumix-internal-ca
spec:
  ca:
    secretName: lumix-root-ca-secret
```

Bundan sonra `lumix-internal-ca` Issuer'ı ile sertifika alan her servis intra-cluster mTLS için kullanabilir.

### 3.4. Challenge mekanizmaları

| Challenge | Nasıl çalışır | Lumix kullanımı |
|---|---|---|
| **HTTP-01** | LE bir random URL'i HTTP üzerinden doğrular; cert-manager geçici Pod açar | Tekil domain (`api.omer-okullari.lumix.io`) |
| **DNS-01** | LE bir TXT record bekler; cert-manager DNS provider API'sini çağırır | Wildcard (`*.omer-okullari.lumix.io`) |
| **tls-alpn-01** | TLS handshake'inde özel ALPN ile doğrulama | Lumix kullanmaz (port 443 LE'ye açmak gerekir) |

### 3.5. Auto-renewal

Certificate CRD'sinde:
```yaml
spec:
  duration: 2160h       # 90 gün (LE default)
  renewBefore: 720h     # 30 gün önceden yenile
```

cert-manager reconciliation:
- Her 1 saatte certificate'ları kontrol et.
- `renewBefore` aşılmışsa renewal başlat.
- Yeni cert Secret'a yazılır (revision artar).
- Eski cert overlap süresi: kısa.

### 3.6. CertificateRequest

`Certificate` apply edilince cert-manager bir `CertificateRequest` üretir (CSR formatında); arka planda Issuer'a gönderir. Bu nesne **debug** için çok değerlidir:

```bash
kubectl describe certificaterequest -n lumix-system omer-okullari-tls-xxx
# Events bölümünde challenge sonuçları, Order durumu
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Kurulum

```bash
helm repo add jetstack https://charts.jetstack.io
helm repo update

kubectl create namespace cert-manager

helm install cert-manager jetstack/cert-manager \
  --namespace cert-manager \
  --version v1.15.0 \
  --set crds.enabled=true \
  --set webhook.timeoutSeconds=15 \
  --set replicaCount=2 \
  --set securityContext.runAsNonRoot=true
```

### 4.2. İki katmanlı sertifika modeli

```
┌─────────────────────────────────────────────────────────┐
│  PUBLIC TLS  (Let's Encrypt)                            │
│   • api.omer-okullari.lumix.io                          │
│   • admin.omer-okullari.lumix.io                        │
│   • *.omer-okullari.lumix.io (wildcard, DNS-01)         │
│   → Traefik Secret olarak                               │
└─────────────────────────────────────────────────────────┘
                          │
┌─────────────────────────┴───────────────────────────────┐
│  INTERNAL CA TLS  (Lumix Internal Root)                 │
│   • microservice-to-microservice gRPC mTLS              │
│   • Kafka broker TLS                                    │
│   • PostgreSQL client cert (Patroni'de)                 │
│   • Service mesh (eğer eklenirse)                       │
└─────────────────────────────────────────────────────────┘
```

### 4.3. Public cert Certificate manifesti

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: omer-okullari-public-tls
  namespace: lumix-system
spec:
  secretName: omer-okullari-public-tls
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
  commonName: api.omer-okullari.lumix.io
  dnsNames:
    - api.omer-okullari.lumix.io
    - admin.omer-okullari.lumix.io
    - "*.omer-okullari.lumix.io"
  duration: 2160h
  renewBefore: 720h
  privateKey:
    algorithm: ECDSA
    size: 256
    rotationPolicy: Always
  usages:
    - server auth
    - digital signature
    - key encipherment
```

`rotationPolicy: Always` — her renewal'da yeni private key (önerilen).

### 4.4. Internal mTLS Certificate örneği

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: academic-service-mtls
  namespace: lumix-app
spec:
  secretName: academic-service-mtls
  issuerRef:
    name: lumix-internal-ca
    kind: ClusterIssuer
  commonName: academic-service.lumix-app.svc.cluster.local
  dnsNames:
    - academic-service.lumix-app.svc.cluster.local
    - academic-service
  duration: 720h        # 30 gün (kısa, internal)
  renewBefore: 240h     # 10 gün
  privateKey:
    algorithm: ECDSA
    size: 256
  usages:
    - server auth
    - client auth
```

`client auth` da listelendiği için pod hem server hem client olarak mTLS kurabilir.

### 4.5. Sertifika kullanım örneği (Spring Boot)

`Secret` Pod'a mount edilir:

```yaml
volumes:
  - name: mtls-cert
    secret:
      secretName: academic-service-mtls
volumeMounts:
  - name: mtls-cert
    mountPath: /etc/mtls
    readOnly: true
```

Spring Boot:
```yaml
server:
  ssl:
    enabled: true
    key-store: /etc/mtls/tls.crt
    key-store-type: PEM
    key-store-key: /etc/mtls/tls.key
    trust-store: /etc/mtls/ca.crt
    client-auth: need
```

(Detay implementation varianta göre değişir.)

### 4.6. Reloader entegrasyonu

Stakater Reloader, ConfigMap/Secret değişince Deployment'ı otomatik restart eder. cert-manager renewal sonrası secret değişiminde podu yeniden başlatmak için:

```yaml
# Deployment annotation
metadata:
  annotations:
    secret.reloader.stakater.com/reload: "academic-service-mtls"
```

(Traefik tarafında secret değişimi `live-reload`'dur; Reloader gerekmez. Application pod'lar için gerekir.)

### 4.7. Sertifika rotation senaryosu (sızıntı)

```bash
# 1. Issuer'ın private key'ini rotate et (yeni LE account)
kubectl delete secret -n cert-manager letsencrypt-prod-key
kubectl apply -f letsencrypt-prod-issuer.yaml  # yeni issuer apply

# 2. Tüm Certificate'leri yeniden iste
kubectl get certificate -A -o name | xargs -I {} kubectl annotate {} cert-manager.io/issue-temporary-certificate=true
kubectl get certificate -A -o name | xargs -I {} kubectl delete {} --grace-period=0

kubectl apply -f certificates/   # tüm cert YAML'ları
```

Internal CA sızıntısı: root CA'nın bütününü değiştirmek = tüm cluster mTLS sertifikalarını yenilemek. Yıllık planlı CA rotation drill yapılır.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Manuel certbot + cron** | Tek host için OK; çoklu cluster + GitOps + auto-renewal için yetersiz. |
| **Traefik kendi ACME** | Tek instance, replicas: 2 olamaz (cert lock dosyası). Lumix replica > 1 ister. |
| **Vault PKI engine** | Çok güçlü ama LE entegrasyonu yok; internal CA için iyi. Karışım: Vault PKI + cert-manager Vault Issuer mümkün; Lumix şimdilik cert-manager'ın kendi CA'sını kullanır. |
| **HashiCorp Vault Agent** | Agent karmaşıklığı + LE yok. |
| **Step CA** | Hafif ama K8s native değil. |
| **AWS ACM** | Bulut-kilit. |

### Kabul ettiğimiz trade-off'lar

- **DNS-01 için provider API erişimi**: Cloudflare/Route53 token cluster'da; sızıntı riski. Vault'ta saklanıp ESO ile Secret olarak inject edilir.
- **Internal CA'yı cert-manager'ın yönetmesi**: root CA Secret olarak K8s'te. Vault'ta tutmak daha güvenli olabilir (gelecek). Şimdilik trade-off kabul, root CA Secret'a `automount: false` + RBAC sıkı.
- **LE rate limit**: haftada 50 cert per registered domain. Lumix wildcard kullandığı için OK.

### Tekrar değerlendirme tetikleyicileri

- Vault PKI ile entegrasyon daha güçlü oturursa internal CA Vault'a taşınır.
- ZeroSSL, Buypass gibi alternatif ACME CA'lar gerekirse Issuer ekleme.

## 6. Pratik örnek

### 6.1. Bootstrap chart sıralaması

```
1. cert-manager kurulumu (Helm)
2. CRD'lerin hazır olmasını bekle
3. ClusterIssuer apply (selfsigned + LE + internal CA)
4. Internal Root CA Certificate apply
5. Public ve Internal Certificate'ler servisler kuruldukça
```

ArgoCD `sync-wave` annotation ile sıralanır:

```yaml
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "-10"  # cert-manager önce
```

### 6.2. Issuer + Certificate komplet örnek (DNS-01 Cloudflare)

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: cloudflare-api-token
  namespace: cert-manager
type: Opaque
stringData:
  api-token: ${{CLOUDFLARE_API_TOKEN}}  # ESO ile Vault'tan gelir
---
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: ops@lumix.io
    privateKeySecretRef:
      name: letsencrypt-prod-key
    solvers:
      - dns01:
          cloudflare:
            apiTokenSecretRef:
              name: cloudflare-api-token
              key: api-token
        selector:
          dnsZones:
            - lumix.io
---
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
  dnsNames:
    - "*.omer-okullari.lumix.io"
  duration: 2160h
  renewBefore: 720h
  privateKey:
    algorithm: ECDSA
    size: 256
    rotationPolicy: Always
```

### 6.3. Cert durumunu kontrol etme

```bash
# Cert ne durumda?
kubectl get certificate -n lumix-system
# NAME                  READY   SECRET                AGE
# omer-okullari-tls     True    omer-okullari-tls     12d

# Detay
kubectl describe certificate omer-okullari-tls -n lumix-system

# Eğer Ready=False ise CertificateRequest'e in
kubectl get certificaterequest -n lumix-system
kubectl describe certificaterequest -n lumix-system omer-okullari-tls-xxx

# Order ve Challenge
kubectl get order,challenge -n lumix-system
kubectl describe challenge -n lumix-system omer-okullari-xxx
```

### 6.4. Secret içeriği

```bash
kubectl get secret -n lumix-system omer-okullari-tls -o json | \
  jq '.data["tls.crt"]' -r | base64 -d | openssl x509 -text -noout
```

### 6.5. Renewal'ı manuel tetikleme

```bash
kubectl cert-manager renew omer-okullari-tls -n lumix-system
# veya
kubectl annotate certificate omer-okullari-tls -n lumix-system \
  cert-manager.io/issue-temporary-certificate=true --overwrite
```

### 6.6. Prometheus alert örnek

```yaml
- alert: CertificateExpiringSoon
  expr: certmanager_certificate_expiration_timestamp_seconds - time() < 7 * 86400
  for: 1h
  labels:
    severity: warning
  annotations:
    summary: "Certificate {{ $labels.name }} expires in < 7 days"

- alert: CertificateRenewalFailing
  expr: increase(certmanager_certificate_renewal_failures_total[1h]) > 0
  for: 30m
  labels:
    severity: critical
```

## 7. Dikkat edilecek tuzaklar

- **LE production'ı staging'siz denemek**: rate limit (50/week/domain) çabuk dolar. **Her zaman önce staging Issuer ile test**.
- **DNS-01 secret'ını Git'e koymak**: Cloudflare token sızıntı = DNS alanını ele geçirme. ESO + Vault.
- **HTTP-01'i internal-only namespace'te kullanmak**: LE solver pod'una **dışarıdan erişilebilir** olması gerekir (LE 80 portuna gelir). Internal-only domain için HTTP-01 işe yaramaz; DNS-01 zorunlu.
- **`renewBefore` çok kısa (örn. 1 gün)**: renewal başarısızsa expiry öncesi tampon kalmaz. Lumix `30 gün`.
- **Aynı domain için ClusterIssuer + Issuer çakışması**: hangisinin sertifikasının kullanıldığı belirsiz. Tek kaynak: ClusterIssuer.
- **Wildcard cert'i farklı namespace'te kullanmamak için kopyalamak**: cert-manager `Replicator` veya `kubed` kullan. Manuel kopyalama drift.
- **Internal CA root'un automount=true ile çıplak Pod'a sızması**: SA token gibi düşün. RBAC + `automountServiceAccountToken: false`.
- **Issuer ACME private key'inin Git'e gitmesi**: helm chart `letsencrypt-prod-key` secret'ı **manuel veya ESO**; chart'a koyma.
- **cert-manager pod restart ile cache kaybetmesi**: zararsız ama kısa pause olabilir. Lumix replicaCount=2.
- **Yan etki: yanlış DNS provider role**: Cloudflare API token "Zone DNS edit" yetkili olmalı; "Zone Read" değil.
- **Renewal Secret değişti ama uygulama eski cert'le çalışmaya devam**: Spring Boot keystore'u memory'e yükler. Reloader veya app-level cert watch gerekir. Traefik bunu otomatik yapar.

## 8. Diğer konularla ilişkisi

- [Traefik Ingress](./traefik-ingress) — public cert Traefik secret'ında
- [Kong API Gateway](./kong-api-gateway) — gerekirse mTLS'le upstream'e
- [NetworkPolicy + mTLS](./networkpolicy-mtls) — mTLS implementasyonu
- [Helm Charts](./helm-charts) — Certificate manifest'lerinin yeri
- [Vault](../security-compliance) — DNS provider token ve ileride PKI engine
- [ArgoCD GitOps](../21-ci-cd/argocd-gitops) — sync-wave ile cert-manager bootstrap

## 9. Daha derine inmek için

- Resmi doc: [https://cert-manager.io/docs/](https://cert-manager.io/docs/)
- ACME RFC 8555 (Automatic Certificate Management Environment)
- "Bulletproof TLS and PKI" — Ivan Ristic
- LE rate limits: [https://letsencrypt.org/docs/rate-limits/](https://letsencrypt.org/docs/rate-limits/)
- Search keyword'leri: *"cert-manager dns01 cloudflare"*, *"cert-manager internal ca pki"*, *"cert-manager renewal troubleshooting"*, *"acme http01 vs dns01"*
- Lumix engineering-notes referansı: `21-kubernetes-helm-infra-devops-engineering-guide.md`

## 10. Sözlük

- **cert-manager**: K8s'te TLS sertifika lifecycle yöneten controller.
- **Issuer / ClusterIssuer**: Sertifika veren mercii (namespace / cluster scope).
- **Certificate (CRD)**: Belli DNS adı için sertifika talebi.
- **CertificateRequest**: cert-manager'ın iç olarak ürettiği CSR wrapper'ı.
- **Order / Challenge**: ACME süreç adımları.
- **ACME (Automatic Certificate Management Environment)**: Let's Encrypt protokolü.
- **HTTP-01 challenge**: LE'nin domain sahipliği kontrolü için HTTP üzerinden random URL doğrulama.
- **DNS-01 challenge**: LE'nin DNS TXT record yoluyla doğrulama (wildcard için zorunlu).
- **Internal CA**: Lumix'in kendi imzaladığı sertifika otoritesi (mTLS için).
- **mTLS (Mutual TLS)**: Her iki taraf birbirine sertifika gösterir.
- **`rotationPolicy: Always`**: Renewal'da yeni private key üret (eski kopya kalmaz).
- **`renewBefore`**: Sertifika expiry'sinden ne kadar önce yenilensin.
- **Reloader (Stakater)**: Secret/ConfigMap değişince Deployment restart eden controller.
- **CSR (Certificate Signing Request)**: Sertifika talep dosyası.
