---
title: ModSecurity WAF
description: ModSecurity nedir, OWASP Core Rule Set, Kong plugin olarak kurulum, false-positive tuning, Lumix attack pattern listesi.
sidebar_position: 7
---

## Bu sayfa ne anlatıyor?

Lumix'in gateway katmanında **L7 saldırılarına karşı koruma** ModSecurity tarafından yapılır. Bu sayfa **WAF (Web Application Firewall)** kavramını sıfırdan açıklar, **ModSecurity**'i ve onun gücünü besleyen **OWASP Core Rule Set (CRS)**'i anlatır, ModSecurity'yi **Kong plugin** olarak nasıl entegre ettiğimizi gösterir, false-positive yönetimini detaylandırır ve Lumix'in **karşılaştığı/karşılaşacağı saldırı pattern'leri** listesini sunar. Hedef kitle: güvenlik temellerini bilen, Kong'a ([Kong API Gateway](./kong-api-gateway)) aşina mühendis.

## 1. Bu nedir? (Sıfırdan)

**WAF (Web Application Firewall)**, HTTP trafiğini **uygulama seviyesinde** inceleyen güvenlik katmanı. Network firewall (Layer 3/4) IP/port'a bakar; WAF Layer 7'de **istek içeriğine** bakar: URL, header, body, parametreler.

**ModSecurity** açık kaynak WAF motoru. Başlangıçta Apache modülü; bugün **Nginx, Kong, Envoy, IIS** üzerine entegre. Lumix Kong plugin olarak kullanır.

ModSecurity tek başına "rule engine"dir; **kurallar** ekleyerek anlam kazanır. Lumix endüstri standardı olan **OWASP Core Rule Set (CRS)** kullanır:
- SQL Injection
- Cross-Site Scripting (XSS)
- Local/Remote File Inclusion (LFI/RFI)
- Remote Code Execution (RCE)
- HTTP protokol anomaly
- Session fixation
- Bot detection
- Generic attack pattern

### Günlük hayattan analoji

AVM güvenliği: kapıda metal dedektör (network firewall — kaba kontrol). İçeride kameralarla davranış analizi (WAF — istek içeriği). Birisinin çantasında metal yoksa girer; ama içeride mağaza vitrinine elini sıkıp girmeye çalışırsa kamera + güvenlik gelir. WAF: HTTP isteğinin "içeriğine" bakar.

## 2. Hangi problemi çözüyor?

Uygulama-katmanı saldırıları **çok yaygın** ve **uygulamayı bypass eder**. Klasik örnek SQL Injection:

```
GET /api/v1/students?name=' OR 1=1 --
```

Eğer backend bu input'u parametrize etmediyse → veritabanı tüm öğrencileri döner. **Backend'i mükemmel yazmak gerekiyor** zaten, ama WAF **defense-in-depth** katmanı sağlar.

| Acı | WAF'sız | WAF'lı |
|---|---|---|
| Yeni keşfedilen CVE pattern'i | Tüm servisleri yamala | CRS güncellemesi tek noktada |
| Bot trafiği | Detect etmek zor | User-Agent + behavior heuristic |
| Path traversal (`../../../etc/passwd`) | Backend hataya tutulur | İstek WAF'ta düşer |
| Şüpheli payload boyutu | Backend parse eder + crash | İstek WAF'ta düşer |
| Bilinen exploit URL'leri (Log4Shell, etc.) | Patch beklerken risk | Tek kuralla engelle |
| Compliance kanıtı | "Bir gün yapacağız" | CRS audit log |

### Patlamış üretim hikayesi

Log4Shell (CVE-2021-44228) çıktı, organizasyon Java sürümlerini hızla yamamaya çalıştı. WAF kuralı 1 saat içinde yayınlandı: `${jndi:` pattern'i tüm header'larda engelle. Yamalanmamış servisler bile WAF arkasında **6 saatlik nefes alacak süre** buldu. WAF olmasaydı bu süre downtime + breach olabilirdi.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. ModSecurity'nin dünyası: phase ve rule

İstek 5 fazda işlenir:
1. `request_headers`: Header parsing.
2. `request_body`: Body parsing (form, JSON, multipart).
3. `response_headers`: Response header'lar (örn. data exfiltration detection).
4. `response_body`: Response body (örn. error message leak).
5. `logging`: Audit log.

Her phase'de SecRule çalışır:

```
SecRule REQUEST_URI "@detectXSS" \
  "id:9001, phase:1, deny, log, msg:'XSS attempt'"
```

`REQUEST_URI` → değişken (variable); `@detectXSS` → operator; `id, phase, deny, log, msg` → action'lar.

### 3.2. OWASP Core Rule Set (CRS)

Hazır kural seti, kategorilere ayrılır:
- **REQUEST-901-INITIALIZATION** — değişken init
- **REQUEST-905-COMMON-EXCEPTIONS** — global istisnalar
- **REQUEST-911-METHOD-ENFORCEMENT** — allowed methods
- **REQUEST-913-SCANNER-DETECTION** — bilinen tarayıcı imzaları
- **REQUEST-920-PROTOCOL-ENFORCEMENT** — HTTP spec
- **REQUEST-921-PROTOCOL-ATTACK** — protocol-level (HTTP smuggling vb.)
- **REQUEST-922-MULTIPART-ATTACK** — multipart parsing edge case
- **REQUEST-930-APPLICATION-ATTACK-LFI**
- **REQUEST-931-APPLICATION-ATTACK-RFI**
- **REQUEST-932-APPLICATION-ATTACK-RCE**
- **REQUEST-933-APPLICATION-ATTACK-PHP**
- **REQUEST-934-APPLICATION-ATTACK-NODEJS**
- **REQUEST-941-APPLICATION-ATTACK-XSS**
- **REQUEST-942-APPLICATION-ATTACK-SQLI**
- **REQUEST-943-APPLICATION-ATTACK-SESSION-FIXATION**
- **REQUEST-944-APPLICATION-ATTACK-JAVA**
- **REQUEST-949-BLOCKING-EVALUATION** — anomaly score eşik kontrolü
- **RESPONSE-950..959** — response-side detection
- **RESPONSE-980-CORRELATION** — son uyarı

### 3.3. Anomaly scoring

CRS her kuralın katkıdığı bir **anomaly score** tutar. İstek bir kurala takıldığında skor artar; eşik (default 5) aşılırsa istek bloklanır.

```
örn:
  - SQL injection pattern match → +5
  - Suspicious user agent → +2
  - Unusual HTTP method → +3

Threshold: 5 → blok
```

Avantajı: **tek kuralla** false-positive riskine girmez; anlamlı kombinasyonlar gerekir.

### 3.4. Paranoia level

CRS 4 paranoia seviyesi (1 → 4):
- 1: Sadece çok güvenilir kurallar (false-positive minimum)
- 2: Standart
- 3: Hassas (false-positive artar)
- 4: Maksimum, çok katı

Lumix kararı: **Paranoia Level 2**, kritik endpoint'lerde (admin API) Level 3.

### 3.5. Lumix mimarisinde konum

```
Internet → Traefik (TLS) → Kong → [ModSecurity plugin] → microservice
```

ModSecurity Kong plugin'i olarak çalışır → her Kong worker'ında **libmodsecurity** library çağrılır.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Kurulum

Lumix Kong custom image'ı (`registry.lumix.io/lumix/kong:3.7-lumix-1`) şunları içerir:
- libmodsecurity 3.x
- OWASP CRS v4.x
- `kong-plugin-modsecurity` (Kong wrapper)

Dockerfile (özet):

```dockerfile
FROM kong:3.7-ubuntu

USER root
RUN apt-get update && apt-get install -y \
    libmodsecurity3 \
    modsecurity-crs \
 && rm -rf /var/lib/apt/lists/*

# CRS config
COPY crs-setup.conf /etc/modsecurity/crs-setup.conf
COPY rules/ /etc/modsecurity/owasp-crs/rules/

# Kong plugin
COPY kong-plugin-modsecurity /usr/local/share/lua/5.1/kong/plugins/modsecurity/

USER kong
```

### 4.2. Plugin konfigürasyonu

```yaml
# Kong declarative
plugins:
  - name: modsecurity
    config:
      modsecurity_rules_file: /etc/modsecurity/main.conf
      audit_log: /tmp/modsec_audit.log
      paranoia_level: 2
      anomaly_score_threshold: 5
      detection_only: false   # production'da false; staging'de true
```

`/etc/modsecurity/main.conf`:

```
Include /etc/modsecurity/crs-setup.conf
Include /etc/modsecurity/owasp-crs/rules/*.conf

SecRuleEngine On
SecRequestBodyAccess On
SecRequestBodyLimit 13107200          # 12.5 MB
SecRequestBodyNoFilesLimit 131072     # 128 KB form
SecResponseBodyAccess Off              # response body Lumix için inceleme yok (latency)

SecAuditEngine RelevantOnly
SecAuditLogParts ABIJDEFHZ
SecAuditLogType Serial
SecAuditLog /tmp/modsec_audit.log
SecAuditLogStorageDir /tmp/modsec_audit/

SecTmpDir /tmp
SecDataDir /tmp/modsec_data
```

### 4.3. Phased rollout: detection-only → block

Lumix kuralı: **Yeni CRS versiyonu ya da yeni route → 7 gün detection-only**. Audit log incelenir, false-positive tespit edilir, exclusion eklenir; sonra `detection_only: false`.

### 4.4. False-positive yönetimi

OWASP CRS bazen meşru request'leri engeller (kötü ünlü `942100` ve `941100` kuralları). Lumix yaklaşımı:

#### Exception örnekleri

```
# /api/v1/messages için SQL kelimesi içeren mesaj içeriği yanlışlıkla SQLi olarak işaretleniyor
SecRule REQUEST_URI "@beginsWith /api/v1/messages" \
  "id:1001, phase:1, pass, nolog, \
   ctl:ruleRemoveTargetById=942100;ARGS:body, \
   ctl:ruleRemoveTargetById=942190;ARGS:body"

# Admin export endpoint'i büyük JSON döner; gateway timeout
SecRule REQUEST_URI "@beginsWith /api/v1/admin/exports" \
  "id:1002, phase:1, pass, nolog, ctl:ruleEngine=DetectionOnly"
```

#### Tuning süreci

1. Audit log'da blocked istekleri bul (`SecRuleEngine=DetectionOnly` ile prod olmadan görülebilir).
2. **Gerçek saldırı mı yoksa meşru kullanım mı?** Manuel inceleme.
3. Meşru ise rule exclusion ekle; **business case** olarak dokümante et.
4. Aylık review: exclusion'lar hâlâ gerekli mi?

### 4.5. Lumix saldırı pattern listesi (öncelikli korumalar)

| Kategori | Pattern örneği | CRS kural ID |
|---|---|---|
| SQLi | `' OR '1'='1`, `UNION SELECT`, `;-- ` | 942100, 942190, 942240 |
| XSS | `<script>`, `javascript:`, `onerror=` | 941100, 941110, 941160 |
| LFI | `../etc/passwd`, `..\..\windows\system32` | 930100, 930110 |
| RCE | `; cat /etc/passwd`, `\| nc -e` | 932100, 932160 |
| Log4Shell | `${jndi:ldap://`, `${env:` | 944100, 944120 |
| HTTP smuggling | `Transfer-Encoding: chunked` + `Content-Length` | 921110, 921120 |
| Path traversal in JSON | `"file":"../../../"` | 930120 |
| Excessive request size | `>10 MB` body | (custom) 1010 |
| Suspicious User-Agent | `sqlmap`, `nikto`, `nmap` | 913100, 913110 |
| Known bad IP (Tor exit nodes) | (IP list) | (custom + threat intel feed) |
| Lumix-spesifik: brute force login | `>5 fail login in 1 min` | (Kong rate-limit + WAF correlation) |
| Lumix-spesifik: enumeration tenant IDs | `100+ unique tenant_id within 1 min` | (custom) 1101 |

### 4.6. Lumix-spesifik custom kurallar

```
# Cookie içinde JWT olmadan /api/v1/admin/* erişimi
SecRule REQUEST_URI "@beginsWith /api/v1/admin" \
  "id:1100, phase:1, deny, status:403, \
   msg:'Admin endpoint without admin scope', \
   chain"
  SecRule REQUEST_HEADERS:X-Admin-Token "!@rx ^[A-Za-z0-9-_=]+$" \
    "t:none"

# Tenant ID enumeration tespiti
SecRule ARGS:tenant_id|ARGS:tenantId "@rx ^[a-f0-9-]{36}$" \
  "id:1101, phase:1, pass, nolog, \
   setvar:ip.tenant_count=+1, expirevar:ip.tenant_count=60"

SecRule IP:tenant_count "@gt 50" \
  "id:1102, phase:1, deny, status:429, \
   msg:'Tenant ID enumeration detected'"
```

### 4.7. Observability

- Audit log JSON formatında → Promtail → Loki → "Security" dashboard.
- Metric: ModSecurity blocked request count → Prometheus → Grafana alert.
- Alert: 1 dakikada > 50 blocked req → security-team Slack.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **AWS WAF / Cloudflare** | Bulut-kilit, on-prem yok. KVKK çerçevesinde dış servis riski. |
| **Coraza** (Go-tabanlı, ModSec uyumlu) | Genç ama umut verici. ModSec olgunluğu, CRS uyumu, Kong plugin desteği için ModSecurity tercih. |
| **NAXSI** (Nginx WAF) | Whitelist-based, ekosistem küçük. |
| **AppArmor / SELinux** | OS-level, HTTP semantik yok. |
| **Custom Lua kuralları** | CRS'in yıllardır birikmiş bilgisi tekrardan üretilir. |

### Kabul ettiğimiz trade-off'lar

- **Latency**: ModSec her isteği ~1-5 ms incelmesi ekler. Lumix p95 latency bütçesinin küçük kısmı.
- **False-positive bakım yükü**: ekibin düzenli exception eklemesi gerekir.
- **CRS güncellemesi disiplin gerektirir**: yeni versiyon = false-positive risk. Phased rollout zorunlu.
- **Body inspection cost**: büyük JSON/multipart için CPU. Limit + skip stratejisi.

### Tekrar değerlendirme tetikleyicileri

- Coraza (Go) projesi olgunlaşırsa (Kong Coraza plugin gelişimi).
- Çok yüksek QPS'de ModSec overhead'i problem olursa → daha düşük seviye Envoy/WAF.

## 6. Pratik örnek

### 6.1. CRS exclusion verify

```bash
# Pod içinde test
kubectl -n lumix-system exec -it deploy/kong -- /bin/bash

# Sahte istek ile dene
curl -i -X POST \
  -H "Content-Type: application/json" \
  -d '{"name":"Ahmet","note":"Öğrenci derste ` OR 1=1 ` ifadesi anlatıldı"}' \
  http://localhost:8000/api/v1/messages

# Audit log incele
tail /tmp/modsec_audit.log
```

### 6.2. Detection-only ile yeni endpoint

```yaml
plugins:
  - name: modsecurity
    route: academic-new-endpoint
    config:
      detection_only: true     # 7 gün
      paranoia_level: 2
```

### 6.3. Lumix custom rule yükleme

`/etc/modsecurity/lumix-rules.conf`:

```
# Lumix custom rule prefix: 1xxx
SecRule REQUEST_HEADERS:User-Agent "@rx (?i)(sqlmap|nikto|nmap|masscan|nessus)" \
  "id:1200, phase:1, deny, status:403, \
   msg:'Known scanner User-Agent'"

# Lumix: only allow GET/POST/PUT/PATCH/DELETE on /api/*
SecRule REQUEST_URI "@beginsWith /api/" \
  "id:1201, phase:1, chain, deny, status:405, msg:'Unsupported method'"
  SecRule REQUEST_METHOD "!@within GET POST PUT PATCH DELETE OPTIONS"

# Maximum JSON depth limit
SecRule REQUEST_BODY "@rx \{.{20000,}\}" \
  "id:1202, phase:2, deny, status:413, msg:'Oversized JSON body'"
```

`/etc/modsecurity/main.conf` sonuna:

```
Include /etc/modsecurity/lumix-rules.conf
```

### 6.4. Anomaly score eşiği ayarlama

CRS `crs-setup.conf`:

```
SecAction \
 "id:900110, phase:1, pass, nolog, \
  setvar:tx.inbound_anomaly_score_threshold=5, \
  setvar:tx.outbound_anomaly_score_threshold=4"

SecAction \
 "id:900000, phase:1, pass, nolog, \
  setvar:tx.paranoia_level=2"
```

### 6.5. Audit log JSON formatı (Loki)

```
SecAuditLogFormat JSON
```

Örnek satır (Loki dashboard'da):

```json
{
  "transaction": {
    "client_ip": "203.0.113.42",
    "time_stamp": "Mon May 27 14:33:11 2026",
    "request": {
      "method": "POST",
      "uri": "/api/v1/auth/login",
      "headers": { "User-Agent": "Mozilla/5.0" }
    },
    "messages": [
      {
        "message": "SQL Injection Attack Detected via libinjection",
        "rule": { "id": 942100, "severity": 2, "tags": ["OWASP_CRS"] }
      }
    ],
    "anomaly_score": 7
  }
}
```

## 7. Dikkat edilecek tuzaklar

- **Production'da detection_only kalmak**: rapor üretir ama saldırı geçer. Phased rollout sonunda **mutlaka** block.
- **Tüm `2xxxxx` exclusion rule'larıyla CRS'i etkisizleştirmek**: takım "false-positive yorduk" deyip kuralları kapatır. Lumix kuralı: her exclusion **business case** + 6 ayda review.
- **`SecResponseBodyAccess On` + büyük response'lar**: latency artışı + CPU. Lumix `Off` (genelde response inspection gerekmez).
- **Tek satırda binlerce CRS update geçmek**: aniden 100 false-positive. Phased: paranoia 1 → 2; route-by-route.
- **Audit log'un /tmp'de tutulması ve pod restart'la kaybedilmesi**: PVC veya hostPath ile kalıcı; veya direkt syslog/Promtail'e push.
- **Body limit'i çok yüksek tutmak**: oversized payload + WAF body parse → DoS yüzeyi. Lumix `SecRequestBodyLimit 12.5 MB` (file upload presigned URL üzerinden gider, gateway'den geçmez).
- **WebSocket trafiğinde WAF aktif**: WS frame'leri HTTP semantiği değil; ModSec yanıltıcı. Route bazlı bypass: `SecRuleEngine Off` for `/ws/*`.
- **Internal API'larda da WAF**: pod-to-pod gRPC trafiğine ModSec uygulamak overhead. Lumix kuralı: WAF sadece **dış trafik** üzerinde (Kong public route'ları).
- **CRS güncellemesini otomatize etmemek**: CVE pattern'leri eski kalır. Lumix: aylık CRS bump + staging 7 gün test + prod.
- **Kong worker memory taşması**: çok karmaşık regex + büyük body. Worker_processes + memory limit gözlemi.

## 8. Diğer konularla ilişkisi

- [Kong API Gateway](./kong-api-gateway) — ModSecurity'nin çalıştığı yer
- [Traefik Ingress](./traefik-ingress) — WAF'tan önceki edge
- [Authentication](../04-authentication-authorization) — WAF auth yerine geçmez; tamamlayıcı
- [Audit Log](../security-compliance) — WAF event'lerinin audit'i
- [Observability](../observability-qa) — WAF metric ve log

## 9. Daha derine inmek için

- ModSecurity: [https://modsecurity.org/](https://modsecurity.org/)
- OWASP CRS: [https://coreruleset.org/](https://coreruleset.org/)
- CRS Tuning Guide: [https://coreruleset.org/docs/concepts/false_positives_tuning/](https://coreruleset.org/docs/concepts/false_positives_tuning/)
- Kong ModSecurity plugin: [https://github.com/Kong/kong-plugin-modsecurity](https://github.com/Kong/kong-plugin-modsecurity)
- **ModSecurity Handbook** — Ivan Ristic (klasik kitap)
- Search keyword'leri: *"owasp crs paranoia level"*, *"modsecurity exclusion rule"*, *"modsecurity anomaly scoring"*, *"libmodsecurity nginx"*

## 10. Sözlük

- **WAF (Web Application Firewall)**: HTTP isteği içeriğine bakan L7 firewall.
- **ModSecurity**: Açık kaynak WAF rule engine (libmodsecurity3).
- **OWASP Core Rule Set (CRS)**: ModSecurity için endüstri standardı kural seti.
- **Paranoia Level**: CRS'in saldırgan tespiti vs false-positive denge ayarı (1-4).
- **Anomaly Score**: İsteğin biriken risk puanı; eşik aşılırsa block.
- **SecRule**: ModSecurity tek kural tanım syntax'ı.
- **Phase**: Kuralın hangi HTTP işleme aşamasında çalışacağı (1-5).
- **Detection-only mode**: Bloklamadan sadece raporlayan mod (rollout için).
- **False positive**: Meşru isteği saldırı olarak işaretlemek.
- **Exclusion / `ctl:ruleRemoveTargetById`**: Belli endpoint/parametre için kuralı atlama.
- **Audit log**: ModSec'in kural tetiklenmelerini ayrıntılı kaydettiği log.
- **SQLi/XSS/LFI/RFI/RCE**: SQL Injection, Cross-Site Scripting, Local/Remote File Inclusion, Remote Code Execution.
