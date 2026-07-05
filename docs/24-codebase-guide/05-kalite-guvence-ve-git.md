---
title: "5 · Kalite Güvencesi & Git Otomasyonu"
description: "Spotless, Checkstyle, ArchUnit, test piramidi; hook'lar, scriptler ve CI'ın commit'ten merge'e kurduğu kapı zinciri."
sidebar_position: 5
---

# Kalite Güvencesi & Git Otomasyonu

## Bu sayfa ne anlatıyor?

"Kod kalitesi" bu repoda dilek değil, **otomatik kapılar zinciridir**: yazdığın satır
merge olana kadar 6 ayrı kapıdan geçer. Bu sayfa her kapının ne denetlediğini, nerede
yaşadığını ve takıldığında ne yapacağını öğretir.

## 1. Kapı zinciri — büyük resim

```
kod yaz → [Spotless+Checkstyle: her build]
commit  → [commit-msg: format + CU ref] [pre-commit: sır/çakışma/boyut]
push    → [pre-push: ZORUNLU build]
MR      → [CI: build+test] [CI: commit-lint] [CI: buf proto denetimi]
merge   → [1 onay (diğer kişi) + protected branch]
```

Felsefe: kural **insana hatırlatılmaz, makineye gömülür**. Lokal hook atlanabilir
(`--no-verify`), ama CI aynı denetimi sunucuda tekrarlar — kapı atlanamaz.

## 2. Format: Spotless (Palantir Java Format)

- **Ne yapar:** girinti, boşluk, import sırası, satır kırma — formatın TAMAMI otomatik.
- **Nerede:** kök `build.gradle.kts` → tüm modüllere uygulanır; her `check`'te doğrular.
- **Takıldın mı:** `./gradlew spotlessApply` → düzeltir, biter. Formatı elle düzeltme;
  format tartışması da yok — makine ne diyorsa o.
- Üretilen kod (`build/generated/`) kapsam dışı.

## 3. Statik analiz: Checkstyle

Formatla **çakışmayan**, yapısal hijyen kuralları (`backend/config/checkstyle/checkstyle.xml`):

| Kural grubu | Örnekler |
|---|---|
| Import hijyeni | yıldız import yasak, kullanılmayan/tekrarlı import yasak |
| İsimlendirme | paket küçük harf, `TypeName`/`MethodName`/`ConstantName` standartları |
| Yaygın hatalar | `equals` varsa `hashCode` da olmalı, `==` ile String karşılaştırma yasak, `switch`'te `default` zorunlu, fall-through yakalanır |
| Yapı | `if`'te süslü parantez zorunlu, boş blok yasak, satır ≤ 120 |

Ayarlar sert: `maxWarnings = 0` — uyarı da birikemez. Tek gevşetme
`suppressions.xml`'de: **test** kaynaklarında Türkçe snake_case metod adlarına izin
(`bos_isim_reddedilir()` gibi BDD adlandırma).

## 4. Mimari denetim: ArchUnit

Hexagonal bağımlılık yönünü **test olarak** kodlar
(`bootstrap/.../HexagonalArchitectureTest.java`, 3 kural):

```java
noClasses().that().resideInAPackage("..domain..")
    .should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..");
// + application adapter'ı bilmez
// + domain, org.springframework../jakarta../javax.. görmez (framework bağımsızlık)
```

Yanlış yönde import ekleyen PR, testte kırmızı yanar — mimari, code review'daki
dikkat gücüne emanet değildir. Yeni servis türetince bu test kopyayla birlikte gelir.

## 5. Test piramidi — bu repoda

| Katman | Örnek | Araç | Hız | Ne yakalar |
|---|---|---|---|---|
| Domain unit | `SampleTest` | saf JUnit + AssertJ | ms | iş kuralı/invariant hataları |
| Application unit | `CreateSampleServiceTest` | Mockito (port mock) | ms | orkestrasyon hataları |
| Mimari | `HexagonalArchitectureTest` | ArchUnit | sn | bağımlılık yönü ihlali |
| Entegrasyon | `SmokeIntegrationTest` | Testcontainers + gerçek Postgres | ~10 sn | **DI/otokonfig/şema** hataları |

Entegrasyon testleri `@Tag("integration")` taşır ve varsayılan `check`'te **atlanır**
(Docker'sız makinede build kırılmasın). Koşturmak: `./gradlew check -Pintegration`.

:::tip Ne zaman hangisi?
İş kuralı eklerken domain testi; use case eklerken Mockito testi; yeni
bean/starter/konfig eklerken MUTLAKA `-Pintegration` — unit testler Spring
context'ini hiç açmadığı için DI hatalarına kördür (yaşandı: Flyway sessizce hiç
koşmuyordu, yalnızca smoke test yakaladı).
:::

## 6. Git hook'ları — commit ve push kapıları

`bash scripts/setup-git.sh` bir kez çalıştırılır; `core.hooksPath=.githooks` ayarlar.

### `commit-msg` — mesaj formatı + ClickUp bağı

1. Konu satırını `<tip>(<kapsam>): <konu>` regex'iyle doğrular
   (tipler: `feat fix docs style refactor perf test build ci chore revert`;
   72 karakter önerisi, 100 sert sınır, sonda nokta yasak).
2. Branch adındaki `CU-<id>`'yi bulup commit'e `Refs: CU-<id>` footer'ı ekler —
   her commit ClickUp task'ına otomatik bağlanır.
3. Kaçış kapısı: mesaj `!` ile başlarsa kontrol atlanır (iz bırakır; acil durum için).

### `pre-commit` — hızlı hijyen (build yok, saniyeler)

- Çözülmemiş merge conflict işareti (`<<<<<<<`) var mı?
- 2 MB'tan büyük dosya eklenmiş mi?
- **Sır sızıntısı**: eklenen satırlarda private key / AWS / GitHub / Google / Slack
  token desenleri taranır. (Yalnızca *eklenen* satırlar — sırrı silen commit engellenmez.)

### `pre-push` — zorunlu build

`scripts/build-check.sh`'i çalıştırır; kırmızıysa **push iptal**. Gerçek acil durum:
`LUMIX_SKIP_BUILD=1 git push` (CI yine yakalar). Commit hızlı kalsın diye build
push'a konmuştur; her commit'te de istersen: `git config lumix.buildOnCommit true`.

## 7. Scriptler — günlük iş akışı

Hepsinin `.ps1` ikizi vardır (Windows). Tipik gün:

```bash
bash scripts/new-branch.sh feature 86abc123 "kullanici giris akisi"
#  → main'i fetch + fast-forward eder, temiz ağaç ister,
#    feature/CU-86abc123-kullanici-giris-akisi açar (Türkçe karakterleri slug'lar)

# ... kod ...

bash scripts/commit.sh feat auth "kullanici giris akisi eklendi"
#  → önce build-check --changed (sadece değişen bileşen), sonra commit
#  → kapsamsız: commit.sh fix - "..."   · build'siz: --no-build

bash scripts/review.sh          # diff'i AI incelemesi için hazırlar (checklist ile)
bash scripts/sync.sh            # uzun süren branch'i main ile rebase'ler
git push -u origin HEAD         # pre-push build kapısı → MR aç
```

`build-check.sh` **tek kaynak** ilkesiyle yazıldı: hook, commit helper ve CI hepsi
aynı scripti çağırır — "lokalde geçti CI'da kırıldı" sürprizi minimize edilir.
Bileşenleri otomatik algılar (backend varsa Gradle, frontend gelince pnpm) ve
`--changed` modunda yalnızca değişeni build eder.

## 8. Kod incelemesi — insan + AI

Push'tan önce inceleme zorunlu; eksenler `docs/REVIEW_CHECKLIST.md`'de (hem insan
listesi hem AI prompt'u): **task uyumu** (kapsam dışı değişiklik var mı?), **ölü
kod/gereksiz yorum/debug çıktısı**, **SOLID · DRY · KISS**, **optimizasyon** (N+1,
döngüde I/O), **sır sızması**, **test eklenmiş mi**.

- Claude Code kullanan: `/code-review`
- Diğer AI kullanan: `bash scripts/review.sh` çıktısını checklist'le birlikte AI'ya ver

MR'da **1 onay zorunlu** ve onay mekanik olarak diğer kişiden gelir (kendi MR'ını
onaylayamazsın). İki kişilik takımda "2 onay" ayarlamayın — kilitlenir
(ayrıntı: `docs/git-workflow.md` §6).

## 9. CI — sunucudaki son bekçi (`.gitlab-ci.yml`)

| Job | Ne zaman | Ne yapar |
|---|---|---|
| `backend:build` | MR'da `backend/**` değişince; main'de her zaman | `gradle check --profile`; JUnit raporu MR'a düşer, süre metriği trend olur |
| `commit-lint` | her MR | tüm commit konularını Conventional regex'iyle denetler (lokal hook'un sunucu kopyası) |
| `schema:validate` | `.proto` değişince | `buf lint` + `buf breaking` (main'e göre wire uyumluluğu) |
| `backend:dependency-check` | main'de manuel | OWASP CVE taraması (yavaş, NVD indirir) |
| `backend:image` | main + tag | Kaniko ile distroless imaj → GitLab Container Registry |
| `deploy:staging` | main'de manuel | placeholder — K8s/Helm gelince dolacak (Sprint 14) |

Her job süresini OpenMetrics olarak raporlar → MR widget'ında **CI süresi trendi**
görünür; pipeline yavaşlaması veriye dayanarak yakalanır.

:::note
`.github/workflows/ci.yml` yalnızca GitHub **mirror** için aynı kuralların yedeğidir;
asıl pipeline GitLab'dır (`gitlab.hsoylu.dev/lumix/campus`).
:::

## 10. Tuzaklar

- **MR'daki TÜM commit'ler** Conventional formatında olmalı — geçmişteki tek bozuk
  commit `commit-lint`'i kırar. Düzeltme: `git rebase` ile reword (main'e dokunmadan).
- **`--no-verify` alışkanlık yapmasın** — CI aynı kapıyı sunucuda kapatır; kazanılan
  tek şey geç kalan kırmızıdır.
- **Format ihlalinde elle düzeltme** — `spotlessApply` varken elle düzeltmek hem yavaş
  hem eksik kalır.
- **Sır taraması yakalarsa** panik yok: sır gerçekse commit'lemeden değiştir/iptal et;
  yanlış alarmsa satırı gözden geçirip yeniden dene.
- **Checkstyle'ı susturma** — `@SuppressWarnings`/suppression eklemeden önce kuralın
  neden var olduğunu sor; gevşetme yalnızca `suppressions.xml`'de, gerekçeyle.

## 11. Serinin sonu — nereden devam?

Bu beş sayfayla repo'nun tamamını gezdin. Devamı için:

- Sprint'lerde fiilen ne yapıldığının kaydı: [Sprint İmplementasyonları](../sprint-implementations/sprint-0-hazirlik-ve-toolchain.md)
- Kalıcı mimari kararlar ve gerekçeleri: [ADR'ler](../adr/0001-mono-repo.md)
- Derin kavramsal konular: [Hexagonal](../02-architecture-patterns/03-hexagonal-architecture.md) ·
  [DDD](../02-architecture-patterns/02-domain-driven-design.md) ·
  [Event-Driven](../02-architecture-patterns/04-event-driven-architecture.md)
