---
title: "ADR-007: Merkezi config repo (GitOps)"
description: Tüm ortam/müşteri konfigürasyonu tek bir lumix-config Git deposunda katmanlı values ile; Spring Cloud Config runtime sunucusu değerlendirildi ve elendi.
sidebar_position: 7
---

# ADR-007: Merkezi config repo (GitOps)

| Alan | Değer |
|---|---|
| **Status** | Accepted |
| **Tarih** | 2026-07-11 |
| **Deciders** | Lumix çekirdek ekip |
| **Sprint** | Sprint 0 |

## Context

`application.yml` her serviste **image içine gömülü bir şablon**dur; gerçek değerler
dışarıdan gelir ([Konfigürasyon & Çalıştırma](../24-codebase-guide/04-konfigurasyon-ve-calistirma.md)).
Ancak bu değerler bugün dağınık: chart `values.yaml` default'u, `values-base`,
`values-tier-*`, müşteri overlay'i ve ArgoCD override'ı ayrı yerlerde. Multi-tenant,
on-prem, müşteri-başına-cluster mimaride ([ADR-001](./0001-mono-repo.md)) her müşteri ×
her ortam × ~11 servis için config yönetimi net bir **tek kaynak** olmadan sürdürülemez.

Ölçütler: **tek gerçek kaynak (source of truth)**, **denetlenebilirlik (audit/rollback)**,
**ortak değer tekrarını önleme**, **sır güvenliği**, mevcut **Helm + ArgoCD + Vault/ESO**
kararlarıyla ([ADR yok — bkz. Helm/ArgoCD doc'ları]) uyum ve **operasyonel sadelik**.

## Decision

Tüm sırsız ortam/müşteri konfigürasyonu **tek bir `lumix-config` Git deposunda**,
**katmanlı Helm values** (`base` → `tiers` → `services` → `installations`) olarak tutulur;
**config-as-code / GitOps** modeli benimsenir. ArgoCD bu repo'yu **multi-source** ile
render eder, ConfigMap üretir; içerik değişince **checksum/Reloader** ilgili pod'ları
yeniden başlatır. **Sırlar bu repo'ya girmez** — yalnızca Vault + ExternalSecret.
Uygulama koduna hiçbir ortam değeri gömülmez.

Runtime config sunucusu (**Spring Cloud Config**) bilinçli olarak **reddedilir**; canlı
refresh gerçek bir ihtiyaç hâline gelirse yalnızca o katman için ayrıca değerlendirilir.

Detay ve mekanizma: [Merkezi Config Repo (GitOps)](../21-ci-cd/06-central-config-repo-gitops.md).

## Consequences

- **Olumlu:** Tek kaynak → "doğru değer nerede?" belirsizliği biter; `git log`/PR ile
  audit; `git revert` ile rollback; katmanlı overlay ortak değer tekrarını keser; sır/sırsız
  ayrımı güvenliği netleştirir; mevcut Helm/ArgoCD/Vault yatırımıyla %100 uyum.
- **Olumsuz / bedel:** Redeploy'suz canlı refresh yok → config değişimi rolling restart
  ister; iki repo (`argocd-apps` + `lumix-config`) + multi-source kurulum karmaşıklığı;
  doğru katmana yazma insan disiplinine bağlı.
- **Azaltıcı önlemler:** `maxUnavailable: 0` ile kesintisiz rollout; net repo sorumluluk
  sınırı + `README` karar tablosu; CODEOWNERS review; `gitleaks` CI taraması sır sızıntısına karşı.

## Alternatives Considered

- **Spring Cloud Config Server** — Git-backed merkezi config sunucusu; `@RefreshScope` ile
  redeploy'suz refresh. → **Elendi:** Her müşteri cluster'ında ayrı HA servis (yeni SPOF +
  bakım); ConfigMap/GitOps ile işlev çakışması; K8s-native dünyada geriliyor. Tek gerçek
  avantajı (canlı refresh) bugün gereksinim değil.
- **Consul KV / etcd (dinamik config)** — Çok-dilli dinamik config için güçlü. → **Elendi:**
  Tek-dilli (JVM) + immutable deploy tercihimizde gereksiz stateful bileşen; feature flag
  ihtiyacı doğarsa **Unleash** daha hedefli.
- **Config'i servis kodunda (`application-prod.yml`) gerçek değerlerle tutmak** → **Elendi:**
  Ortam bilgisi koda sızar; her değişiklik yeni image; multi-tenant'ta imkânsız.
- **Merkezileştirmemek (servis × müşteri başına ayrı dosya/repo)** → **Elendi:** N×M dosya
  patlaması, ortak değer tekrarı, "doğru değer nerede?" belirsizliği.
- **Config'i deployment repo'suyla (`argocd-apps`) tam birleştirmek** → **Elendi:** Application
  CRD değişiklikleri ile gündelik values değişiklikleri aynı PR akışında karışır; ayrı repo +
  multi-source daha temiz.

## References

- [Merkezi Config Repo (GitOps)](../21-ci-cd/06-central-config-repo-gitops.md) — mekanizma, repo yapısı, örnekler
- [Konfigürasyon & Çalıştırma](../24-codebase-guide/04-konfigurasyon-ve-calistirma.md) — `application.yml` şablonu
- [Helm Charts](../infra-devops/03-helm-charts.md) — values overlay + ConfigMap/ExternalSecret template'leri
- [ArgoCD GitOps](../21-ci-cd/04-argocd-gitops.md) — multi-source, ApplicationSet, selfHeal
- [ADR-001: Mono-repo](./0001-mono-repo.md), [ADR-002: Java 25 / Spring Boot 4](./0002-java-25-spring-boot-4.md)
