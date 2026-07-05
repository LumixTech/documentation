---
title: GitLab CI Pipelines
description: "`.gitlab-ci.yml` syntax, stages (build/test/scan/deploy), runner types, cache, artifacts, Lumix microservice template (10 servis aynı pipeline pattern), tag-based deploy."
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Lumix 10 microservice + infrastructure repository'leri **standartlaştırılmış GitLab CI pipeline** ile build/test/scan/publish/deploy edilir. Bu sayfa GitLab CI'nin `.gitlab-ci.yml` syntax'ını sıfırdan anlatır, **stages / jobs / needs / rules / cache / artifacts** kavramlarını gösterir, Lumix'in **microservice template pipeline**'ını detayıyla anlatır (Java 25 Gradle build → JUnit → Testcontainers → Pact contract → Trivy scan → Docker buildx → Helm package → ArgoCD trigger), **tag-based deploy** ve **environment** kavramlarını açıklar. Hedef kitle: Spring Boot geliştirici, ilk pipeline yazıyor.

## 1. Bu nedir? (Sıfırdan)

**GitLab CI**, GitLab içine gömülü CI/CD motoru. Her project'in kök dizinindeki `.gitlab-ci.yml` dosyası **pipeline** tanımlar. Bir commit/push olduğunda GitLab pipeline'ı tetikler; **GitLab Runner**'lar **job**'ları çalıştırır.

Anahtar kavramlar:
- **Pipeline**: Bir commit/MR/tag için çalışan tüm job'ların grubu.
- **Stage**: Pipeline'da sıralı aşama (build → test → deploy).
- **Job**: Tek bir görev (örn. "unit-test", "build-image").
- **Runner**: Job'u çalıştıran agent.
- **Cache**: Job'lar arası paylaşılan dosyalar (`.gradle/`, `node_modules/`).
- **Artifacts**: Job çıktısı (rapor, jar, image hash); sonraki job'lara veya UI'da indirilebilir.
- **Variables**: Project/group/pipeline scope'unda env vars.
- **Environment**: Deploy hedefi (dev, staging, prod) tracking.
- **Rules / when**: Job ne zaman çalışacak (push, merge_request, tag, manual).
- **needs (DAG)**: Job bağımlılığı; klasik stage sırası yerine paralel mümkün.

### Günlük hayattan analoji

Üretim hattı: hammadde → eritme → kalıp → boyama → paketleme → sevkıyat. Her istasyon (stage) öncekini bekler. Bazı istasyonlar paralel olabilir (kalite test + paketleme). Hata varsa bant durur, alarm çalar.

## 2. Hangi problemi çözüyor?

Manuel build/test/deploy:
- Geliştirici lokal'de build eder; sunucuya manuel scp eder.
- Test'leri kim, ne zaman çalıştırdı belirsiz.
- Production deploy'unda tutarsızlık.
- CVE scan unutulur.

| Acı | CI yok | CI var |
|---|---|---|
| Her MR'da test'lerin geçtiğini doğrulama | Manuel "ben çalıştırdım" | Otomatik gate |
| Build artifact reproducible | Geliştirici makinesinde farklı | Runner sandbox |
| Image tag tutarlılığı | Manuel `docker tag` | `$CI_COMMIT_TAG` otomatik |
| Security scan | "Hatırlarsak yaparız" | Pipeline gate (Trivy) |
| Deploy traceability | Slack mesajları | Pipeline log + environment history |
| Rollback | Manuel | Pipeline'dan "deploy previous" |
| Coverage | Belirsiz | JUnit XML + GitLab UI |

### Patlamış üretim hikayesi

Bir takım CI olmadan deploy yapıyordu. Cuma sürümü: geliştirici lokal build'i + manuel deploy. Lokal'de Java 17, prod'da Java 11 → ClassNotFoundException. 2 saatlik downtime. CI olsaydı: image build container'da, Java sürümü explicit, fark hemen yakalanırdı.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Temel `.gitlab-ci.yml`

```yaml
stages:
  - build
  - test
  - deploy

build-job:
  stage: build
  image: eclipse-temurin:21-jdk
  script:
    - ./gradlew build -x test
  artifacts:
    paths:
      - build/libs/*.jar
    expire_in: 1 day

test-job:
  stage: test
  image: eclipse-temurin:21-jdk
  needs: [build-job]
  script:
    - ./gradlew test
  artifacts:
    reports:
      junit: build/test-results/test/*.xml

deploy-job:
  stage: deploy
  needs: [test-job]
  script:
    - echo "Deploying..."
  rules:
    - if: $CI_COMMIT_TAG
  environment:
    name: production
```

### 3.2. Stage vs needs (DAG)

Klasik stage: tüm stage1 job'ları bitmeden stage2 başlamaz.
DAG (`needs:`): bir job sadece açıkça belirttiği job'ları bekler. Pipeline paralelleşir.

Lumix tercihi: **DAG-style** (needs ile). Daha hızlı pipeline.

### 3.3. Cache

```yaml
cache:
  key:
    files: [build.gradle.kts, gradle/wrapper/gradle-wrapper.properties]
  paths:
    - .gradle/wrapper
    - .gradle/caches
```

- `key`: hangi cache bucket'ı kullanılacak; aynı `build.gradle` ise cache hit.
- `paths`: cache'lenecek dizinler.

### 3.4. Artifacts

```yaml
artifacts:
  paths:
    - build/libs/
  reports:
    junit: build/test-results/test/*.xml
    coverage_report:
      coverage_format: jacoco
      path: build/reports/jacoco/test/jacocoTestReport.xml
  expire_in: 1 week
```

Reports: GitLab UI'da görselleştirilir (test failures, coverage diff).

### 3.5. Rules

```yaml
rules:
  - if: $CI_PIPELINE_SOURCE == "merge_request_event"
    when: always
  - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
    when: always
  - if: $CI_COMMIT_TAG
    when: manual
  - when: never
```

İlk eşleşen kural geçerli. `when: never` → atla.

### 3.6. Include

Pipeline parçalarını başka dosyadan dahil:
```yaml
include:
  - project: platform/templates/ci-templates
    ref: v1.4.0
    file:
      - /java-microservice.yml
      - /helm-package.yml
      - /trivy-scan.yml
```

Lumix template'leri tek yerden dağıtım.

### 3.7. Environment

```yaml
deploy-prod:
  environment:
    name: production
    url: https://api.lumix.io
    deployment_tier: production
```

GitLab UI'da Environment view: hangi commit deploy'lu, son N deploy, rollback button.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Microservice template pipeline

Her servis (`identity-service`, `academic-service` …) aynı `.gitlab-ci.yml` template'ini kullanır:

```yaml
# .gitlab-ci.yml (microservice repo'da)
include:
  - project: platform/templates/ci-templates
    ref: v1.4.0
    file:
      - /java-microservice.yml

variables:
  SERVICE_NAME: academic-service
  CHART_NAME: academic-service
```

`platform/templates/ci-templates/java-microservice.yml`:

```yaml
stages:
  - prepare
  - test
  - build
  - scan
  - package
  - publish
  - deploy-dev
  - deploy-staging
  - deploy-prod

default:
  image: registry.lumix.io/platform/ci-runners/java21-builder:1.2.0
  tags: [internal]
  interruptible: true

variables:
  GRADLE_OPTS: "-Dorg.gradle.daemon=false"
  DOCKER_BUILDKIT: "1"
  TRIVY_NO_PROGRESS: "true"
  CHART_NAME: "${SERVICE_NAME}"

# ─── PREPARE ──────────────────────────────────────
lint:
  stage: prepare
  script:
    - ./gradlew spotlessCheck checkstyleMain
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"

# ─── TEST ─────────────────────────────────────────
unit-test:
  stage: test
  cache:
    key: gradle-{{ .files }}
    paths: [.gradle/]
  script:
    - ./gradlew test jacocoTestReport
  artifacts:
    when: always
    reports:
      junit: build/test-results/test/*.xml
      coverage_report:
        coverage_format: jacoco
        path: build/reports/jacoco/test/jacocoTestReport.xml
    paths:
      - build/reports/
    expire_in: 1 week
  coverage: '/Total.*?([0-9]{1,3})%/'

integration-test:
  stage: test
  services:
    - name: docker:24-dind
      alias: docker
  variables:
    DOCKER_HOST: tcp://docker:2375
    DOCKER_TLS_CERTDIR: ""
  script:
    - ./gradlew integrationTest
  artifacts:
    when: always
    reports:
      junit: build/test-results/integrationTest/*.xml
    expire_in: 1 week

contract-test:
  stage: test
  script:
    - ./gradlew pactTest pactPublish
  variables:
    PACT_BROKER_BASE_URL: https://pact.lumix.io
    PACT_BROKER_TOKEN: ${PACT_BROKER_TOKEN}
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"

# ─── BUILD ────────────────────────────────────────
build-jar:
  stage: build
  needs: [unit-test]
  script:
    - ./gradlew bootJar -x test
  artifacts:
    paths:
      - build/libs/*.jar
    expire_in: 1 day

build-image:
  stage: build
  needs: [build-jar]
  image:
    name: gcr.io/kaniko-project/executor:v1.22.0-debug
    entrypoint: [""]
  script:
    - /kaniko/executor
        --context "${CI_PROJECT_DIR}"
        --dockerfile "${CI_PROJECT_DIR}/Dockerfile"
        --destination "${CI_REGISTRY_IMAGE}:${CI_COMMIT_SHA}"
        --destination "${CI_REGISTRY_IMAGE}:${CI_COMMIT_REF_SLUG}"
        ${CI_COMMIT_TAG:+--destination "${CI_REGISTRY_IMAGE}:${CI_COMMIT_TAG}"}
        --cache=true
        --cache-ttl=24h

# ─── SCAN ─────────────────────────────────────────
trivy-image-scan:
  stage: scan
  needs: [build-image]
  image: aquasec/trivy:0.50.0
  script:
    - trivy image
        --severity CRITICAL,HIGH
        --exit-code 1
        --no-progress
        --format template
        --template "@contrib/junit.tpl"
        -o trivy-report.xml
        "${CI_REGISTRY_IMAGE}:${CI_COMMIT_SHA}"
    - trivy image
        --format cyclonedx
        -o sbom.json
        "${CI_REGISTRY_IMAGE}:${CI_COMMIT_SHA}"
  artifacts:
    when: always
    reports:
      junit: trivy-report.xml
    paths:
      - trivy-report.xml
      - sbom.json
    expire_in: 1 month

trivy-config-scan:
  stage: scan
  image: aquasec/trivy:0.50.0
  script:
    - trivy config --severity CRITICAL,HIGH --exit-code 1 .

# ─── PACKAGE (Helm chart) ─────────────────────────
helm-package:
  stage: package
  needs: [trivy-image-scan]
  image: alpine/helm:3.15.0
  script:
    - helm dependency update charts/${CHART_NAME}
    - helm lint charts/${CHART_NAME}
    - |
      if [[ -n "$CI_COMMIT_TAG" ]]; then
        CHART_VERSION="${CI_COMMIT_TAG#v}"
        APP_VERSION="${CI_COMMIT_TAG#v}"
      else
        CHART_VERSION="0.0.0-${CI_COMMIT_SHORT_SHA}"
        APP_VERSION="0.0.0-${CI_COMMIT_SHORT_SHA}"
      fi
    - helm package charts/${CHART_NAME} --version ${CHART_VERSION} --app-version ${APP_VERSION}
  artifacts:
    paths:
      - ${CHART_NAME}-*.tgz
    expire_in: 1 day
  rules:
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
    - if: $CI_COMMIT_TAG

# ─── PUBLISH ──────────────────────────────────────
publish-chart:
  stage: publish
  needs: [helm-package]
  image: alpine/helm:3.15.0
  script:
    - helm registry login -u $CI_REGISTRY_USER -p $CI_REGISTRY_PASSWORD $CI_REGISTRY
    - helm push ${CHART_NAME}-*.tgz oci://${CI_REGISTRY}/platform/charts
  rules:
    - if: $CI_COMMIT_TAG
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH

# ─── DEPLOY ───────────────────────────────────────
.deploy-template: &deploy-template
  image: argoproj/argocd:v2.11
  script:
    - argocd login argocd.lumix.io --auth-token $ARGOCD_AUTH_TOKEN --grpc-web
    - argocd app set ${APP_NAME} -p ${CHART_NAME}.image.tag=${CI_COMMIT_TAG:-${CI_COMMIT_SHA}}
    - argocd app sync ${APP_NAME} --timeout 600
    - argocd app wait ${APP_NAME} --health --timeout 600

deploy-dev:
  <<: *deploy-template
  stage: deploy-dev
  needs: [publish-chart]
  variables:
    APP_NAME: lumix-platform-dev
  environment:
    name: dev
    url: https://api.dev.lumix.io
  rules:
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH

deploy-staging:
  <<: *deploy-template
  stage: deploy-staging
  needs: [publish-chart]
  variables:
    APP_NAME: lumix-platform-staging
  environment:
    name: staging
    url: https://api.staging.lumix.io
  rules:
    - if: $CI_COMMIT_TAG =~ /^v.*-rc\..*$/

deploy-prod:
  <<: *deploy-template
  stage: deploy-prod
  needs: [publish-chart]
  variables:
    APP_NAME: lumix-platform-prod
  environment:
    name: production
    url: https://api.lumix.io
    deployment_tier: production
  rules:
    - if: $CI_COMMIT_TAG =~ /^v[0-9]+\.[0-9]+\.[0-9]+$/
      when: manual
```

### 4.2. Versiyon stratejisi

| Branch / Tag | Trigger | Image tag | Chart version |
|---|---|---|---|
| Feature branch (`feature/*`) | MR pipeline | `<branch-slug>` | — |
| `main` (push) | Full pipeline + dev deploy | `<short-sha>` + `main` | `0.0.0-<short-sha>` |
| Tag `v1.4.2-rc.1` | Staging deploy | `1.4.2-rc.1` | `1.4.2-rc.1` |
| Tag `v1.4.2` | Manual prod deploy | `1.4.2` | `1.4.2` |

### 4.3. Runner tags

Runner'lara tag verilir; job `tags:` ile seçer.

| Tag | Runner | Kullanım |
|---|---|---|
| `internal` | gitlab-runners-internal | Build, test, scan |
| `deploy-prod` | gitlab-runners-deploy (RBAC sıkı) | Production deploy |
| `large` | Daha yüksek CPU/RAM runner | Heavy build (UI bundling, e2e) |

### 4.4. Cache stratejisi

- Gradle: `~/.gradle/caches` cache key = `build.gradle.kts` SHA → değişince invalidate.
- Docker layer cache: Kaniko `--cache=true` + registry layer cache.
- Node: `node_modules/` cache key = `package-lock.json` SHA.

### 4.5. Secret yönetimi

Pipeline'da secret'lar:
- **Project CI variables** (sadece protected branch'lerde görünür)
- **Vault entegrasyonu**: `gitlab-vault` integration veya `vault` CLI ile JWT auth

```yaml
get-secret:
  script:
    - export VAULT_TOKEN=$(vault write -field=token auth/jwt/login role=gitlab-ci jwt=$CI_JOB_JWT)
    - export DB_PASSWORD=$(vault kv get -field=password secret/lumix/internal/db)
```

Hiçbir secret kod base'inde clear text değil.

### 4.6. Interruptible job'lar

```yaml
default:
  interruptible: true
```

Aynı MR'a yeni push gelirse eski pipeline cancel olur. Kaynak tasarrufu.

### 4.7. Parent-child pipelines

Büyük monorepo için: ana pipeline → değişen modüllere göre child pipeline trigger.

```yaml
trigger-academic:
  trigger:
    include: services/academic/.gitlab-ci.yml
  rules:
    - changes: ["services/academic/**/*"]
```

Lumix microservice'leri **ayrı repo**'larda olduğu için child pipeline kullanmıyor; ama monorepo gelecekse hazır.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Jenkins** | Plugin ekosistemi geniş ama plugin hell, UI eski. |
| **GitHub Actions** | GitHub.com ile birlikte gelir; self-host runner mümkün ama UI GitLab kadar entegre değil. |
| **CircleCI / Travis CI** | SaaS; Lumix self-host odaklı. |
| **Tekton** | K8s-native, çok güçlü; öğrenme eğrisi yüksek, GitLab kadar polished değil. |
| **Argo Workflows** | Workflow engine; CI için overkill. |
| **Drone CI** | Hafif ama topluluk ve özellik GitLab kadar değil. |

### Kabul ettiğimiz trade-off'lar

- **YAML kompleksitesi**: 200+ satır .gitlab-ci.yml normal. Template + include disiplinli olunca yönetilebilir.
- **Job sayısı çoğalınca pipeline süresi artar**: `needs:` ile DAG paralelleştirme + cache + Kaniko cache.
- **Runner cluster bakımı**: bizim cluster'da. Karşılığında: bulut maliyeti yok.

### Tekrar değerlendirme tetikleyicileri

- Monorepo'ya geçilirse Tekton/Argo Workflows yeniden değerlendirme.
- Build süresi 30dk+ olursa cache stratejisi yeniden tasarlama.

## 6. Pratik örnek

### 6.1. Dockerfile (multi-stage, distroless)

```dockerfile
# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# Layered jar extraction (Spring Boot)
WORKDIR /extracted
RUN java -Djarmode=layertools -jar /app/build/libs/*.jar extract

# Runtime stage (distroless)
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER nonroot
EXPOSE 8080 8081 9090
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

### 6.2. Hızlı pipeline çalıştırma testleri

```bash
# Lint .gitlab-ci.yml
gitlab-ci-lint .gitlab-ci.yml

# Lokal job çalıştırma (mock runner)
gitlab-runner exec docker unit-test

# Pipeline manuel tetikleme
curl -X POST -F "token=$TRIGGER_TOKEN" -F "ref=main" \
  https://gitlab.lumix.io/api/v4/projects/123/trigger/pipeline
```

### 6.3. Job zincirleme akış

```
[ lint, unit-test, integration-test, contract-test ]   (paralel)
                  │
                  ▼
              [ build-jar ]
                  │
                  ▼
              [ build-image ]
                  │
                  ▼
         [ trivy-image-scan ]
                  │
                  ▼
              [ helm-package ]
                  │
                  ▼
              [ publish-chart ]
                  │
                  ├──── deploy-dev (auto)
                  ├──── deploy-staging (tag rc.*)
                  └──── deploy-prod (tag v*.*.* + manual)
```

### 6.4. Rules detaylı örnek

```yaml
deploy-prod:
  rules:
    - if: $CI_COMMIT_TAG =~ /^v[0-9]+\.[0-9]+\.[0-9]+$/
      when: manual
      allow_failure: false
    - if: $CI_COMMIT_BRANCH == "hotfix"
      when: manual
    - when: never
```

### 6.5. Approval workflow (manual gate)

```yaml
deploy-prod:
  when: manual
  environment:
    name: production
    deployment_tier: production
  allow_failure: false
  needs:
    - publish-chart
    - job: smoke-test-staging
      artifacts: false
```

UI'da "Play" button + audit trail.

### 6.6. Artifact'i sonraki job'a aktarma

```yaml
build-jar:
  artifacts:
    paths: [build/libs/*.jar]
    expire_in: 1 day

build-image:
  needs:
    - job: build-jar
      artifacts: true  # default true; artifact download'lanır
  script:
    - ls build/libs/
    - docker build .
```

### 6.7. Coverage threshold (manuel gate)

```yaml
unit-test:
  coverage: '/Total.*?([0-9]{1,3})%/'
  script:
    - ./gradlew test jacocoTestReport
    - |
      COV=$(grep -oP 'Total.*?\K[0-9]+(?=%)' build/reports/jacoco/test/index.html | head -1)
      if [ "$COV" -lt 70 ]; then
        echo "Coverage $COV% below 70%"
        exit 1
      fi
```

## 7. Dikkat edilecek tuzaklar

- **`script:` içinde shell variable expansion**: `$VAR` shell tarafından; `$$VAR` GitLab CI variable. Karıştırma sık hata.
- **Cache key olmadan paths**: tüm pipeline'lar aynı cache → invalidation karmaşası. `key: { files: [...] }` kullan.
- **Cache'i artifact ile karıştırmak**: cache hızlandırma; artifact iletişim. Cache restore olmayabilir; build artifact kesin.
- **`when: always` artifacts olmayınca**: job fail olunca cache de upload olmaz. `artifacts: when: always` kuralı.
- **Runner privileged Docker-in-Docker**: güvenlik delik. Lumix: Kaniko + rootless build.
- **Pipeline 30+ dakika**: cache miss veya seri job. DAG (`needs:`) ile paralelleştir.
- **Secret'ı script'te log'a basmak**: Slack alert'lerine düşer. `set -x` etrafında dikkat; secret değişkenleri **masked** olarak işaretle.
- **`if: $CI_COMMIT_BRANCH == "main"` ama default branch `master`**: `$CI_DEFAULT_BRANCH` kullan.
- **Interruptible kapatmak**: yeni push'la önceki cancel olmaz → runner queue dolu. `interruptible: true` default tut.
- **`environment` belirtmemek**: GitLab Environment view'de yer almaz; rollback button yok. Her deploy job'unda environment ekle.
- **Auto deploy prod**: tag push → otomatik prod = istenmeyen sürpriz. Lumix kuralı: `when: manual`.
- **Helm chart version'ı her commit'te değiştirmemek**: ArgoCD cache'leyebilir. SHA-based versioning otomatik.
- **Pact contract test'i optional yapmak**: schema bozulur. `allow_failure: false`.

## 8. Diğer konularla ilişkisi

- [GitLab CE Self-Hosted](./01-gitlab-ce-self-hosted.md) — pipeline'ın altyapısı
- [Trivy Image Scanning](./03-trivy-image-scanning.md) — `trivy-image-scan` job detayı
- [ArgoCD GitOps](./04-argocd-gitops.md) — deploy job'un targetı
- [Helm Versioning](./05-helm-versioning.md) — chart/app version stratejisi
- [Customer Onboarding Pipeline](../20-iac-provisioning/03-customer-onboarding-pipeline.md) — özelleşmiş pipeline
- [Tilt Local Dev](../23-local-development/01-tilt-multi-service-dev.md) — geliştirici makinesinde aynı build'i tetikleme

## 9. Daha derine inmek için

- Resmi doc: [https://docs.gitlab.com/ee/ci/](https://docs.gitlab.com/ee/ci/)
- CI/CD YAML reference: [https://docs.gitlab.com/ee/ci/yaml/](https://docs.gitlab.com/ee/ci/yaml/)
- "Continuous Delivery" — Jez Humble, David Farley
- "Modern DevOps Practices"
- Search keyword'leri: *"gitlab ci dag needs"*, *"gitlab ci kaniko build"*, *"gitlab ci environment deployment_tier"*, *"gitlab ci pact contract test"*

## 10. Sözlük

- **Pipeline**: Bir commit/MR/tag için çalışan job grubu.
- **Stage**: Sıralı pipeline aşaması (build, test, deploy).
- **Job**: Tek bir görev tanımı.
- **Runner**: Job'u çalıştıran agent.
- **Executor**: Runner'ın iş yapma şekli (shell, docker, kubernetes).
- **`needs:`**: Job-level bağımlılık; DAG-style pipeline.
- **`rules:`**: Job'un ne zaman tetikleneceği kuralları.
- **`when: manual`**: Job sadece insan tarafından "Play" ile çalışır.
- **`artifacts`**: Job çıktısı; sonraki job'a veya kullanıcıya iletilir.
- **`reports`**: Özel artifact (junit, coverage, sast); UI'da görselleşir.
- **`cache`**: Job'lar arası paylaşılan, hızlandırıcı dosyalar.
- **`environment`**: Deploy hedefi tracking (dev/staging/prod).
- **`include`**: Başka YAML dosyasını dahil etme.
- **`interruptible`**: Yeni pipeline gelince eski cancel.
- **`CI_COMMIT_TAG`**: Git tag ile tetiklenince dolu.
- **`CI_JOB_TOKEN`**: Job içinde registry/API erişimi için kısa süreli token.
- **Kaniko**: Daemonless Docker image build aracı.
- **Pact**: Consumer-driven contract testing framework.
- **Jacoco**: Java code coverage kitaplığı.
