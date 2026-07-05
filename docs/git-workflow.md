# Lumix — Git & Branch Yönetimi Playbook

İki kişilik takım (biri **Windows + Claude Code**, biri **Linux + başka AI**) için
trunk-based, Conventional Commits tabanlı, **kusursuz** git iş akışı. Tüm
enforcement git hook'larıyla **otomatik** ve **her iki işletim sisteminde** çalışır.

---

## 0. Tek seferlik kurulum

> **Mono-repo kökü: `campus/`** (`c:\Lumix\campus`). Bu klasör backend + frontend +
> infra'yı birlikte barındırır ve git deposu burada açılır. `documentation/` ise
> ayrı bir projedir (`c:\Lumix\documentation`, kendi repo'su) — bu mono-repo'ya
> dahil değildir. Tüm komutlar `campus/` içinden çalıştırılır.

```bash
# 0) campus/ klasorune girin
cd campus

# 1) Depoyu başlat (bir kişi yapar, sonra remote'a push eder)
git init -b main

# 2) Hook'ları ve ayarları aktive et
#    Windows (PowerShell):
pwsh scripts/setup-git.ps1
#    Linux/macOS/Git Bash:
bash scripts/setup-git.sh

# 3) İlk commit
git add .
# Hook/script'lerin Linux'ta da çalıştırılabilir olması için exec-bit'i repoya yaz
# (özellikle Windows'tan ilk commit yapan kişi için kritik):
git update-index --chmod=+x .githooks/commit-msg .githooks/pre-commit .githooks/pre-push scripts/*.sh
git commit -m "chore: repo iskeleti ve git yonetimi kuruldu"
git remote add origin <repo-url>
git push -u origin main
```

Depoyu **klonlayan ikinci kişi** sadece kurulum scriptini çalıştırır:
```bash
bash scripts/setup-git.sh      # veya: pwsh scripts/setup-git.ps1
```

> ✅ **Yapı kuruldu:** `backend/` artık `campus/` mono-repo'sunun bir alt klasörüdür
> (eski bozuk `.git`'i kaldırıldı, commit/geçmiş kaybı yok). `frontend/` ve `infra/`
> da buraya, `backend/` ile aynı repo içinde eklenecek (mono-repo mantığı). Böylece
> kök `.githooks`, `.gitignore` (sır kuralları) ve `.gitattributes` **tüm koda**
> uygulanır — tek `git init` ile her şey korunur.
>
> 📁 `documentation/` bilerek **ayrı** tutuldu (`c:\Lumix\documentation`); kendi
> repo'su, kendi yaşam döngüsü. Bu mono-repo onu kapsamaz.

---

## 1. Branch stratejisi (trunk-based)

- **`main`** her zaman yeşil (build geçer, dağıtılabilir). Doğrudan push **yok**.
- Her iş için **kısa ömürlü** branch (ideal: 1-2 gün, küçük PR).
- Branch adı: `<tip>/CU-<taskID>-<kısa-ad>`
  - Örnek: `feature/CU-86abc123-kullanici-giris-akisi`
  - Tipler: `feature`, `fix`, `chore`, `docs`, `refactor`, `hotfix`, `experiment`
- ClickUp task ID'si branch adından **commit footer'ına otomatik** eklenir (`Refs: CU-...`).

Branch açmak için:
```bash
# Linux/macOS:
bash scripts/new-branch.sh feature 86abc123 "kullanici giris akisi"
# Windows:
pwsh scripts/new-branch.ps1 feature 86abc123 "kullanici giris akisi"
```
Bu script main'i günceller, slug üretir ve doğru isimde branch açar.

---

## 2. Commit (Conventional Commits, Türkçe)

Format: `<tip>(<kapsam>): <konu>` — konu Türkçe, ~72 karakter, sonunda nokta yok.

```
feat(auth): kullanici giris akisi eklendi
fix(payment): iade tutari yanlis hesaplaniyordu
refactor(tenant): kapsam cozumleyici sadelestirildi
docs: README kurulum adimlari guncellendi
```

**Commit öncesi kalite kapısı** (kullanıcının isteği): build başarılı olmalı +
kod incelemesi. Akıllı commit helper'ı build'i commit'ten **önce** çalıştırır:

```bash
git add <dosyalar>
# Linux/macOS:
bash scripts/commit.sh feat auth "kullanici giris akisi eklendi"
# Windows:
pwsh scripts/commit.ps1 feat auth "kullanici giris akisi eklendi"
# kapsamsız commit için kapsam yerine "-" verin:
bash scripts/commit.sh fix - "null kontrolu eklendi"
```

Helper'ı kullanmasanız bile **`commit-msg` hook** her commit'te formatı doğrular
ve ClickUp footer'ını ekler; **`pre-commit` hook** merge işareti / büyük dosya /
sır sızması kontrolü yapar.

> En katı mod: her commit'te build istiyorsanız `git config lumix.buildOnCommit true`.
> Varsayılan kapalıdır (trunk-based'de sık küçük commit'ler için pratik); build
> zaten push'ta **zorunlu** olarak çalışır.

**Acil kaçış kapısı (`!`):** Commit mesajının en başına `!` koyarsanız format
kontrolü **tamamen atlanır** ve commit doğrudan kabul edilir (örn. `!acil hotfix`).
`!` işareti mesajda kalır; hem lokal `commit-msg` hook'u hem de CI bu commit'i bypass
eder (lokal/sunucu tutarlı, denetlenebilir iz). Yalnızca gerçekten gerektiğinde kullanın.

**Otomatik Türkçe mesaj (AI):** Mesajı elle yazmak yerine AI'ya bıraktırabilirsiniz.
Claude Code kullananlar için Claude `git diff --staged`'i okuyup uygun
`<tip>(<kapsam>): <konu>` Türkçe mesajı üretir (bkz. [CLAUDE.md](../CLAUDE.md)).
Diğer AI kullananlar `bash scripts/review.sh` çıktısıyla AI'dan mesaj önerisi ister.

**ClickUp ID yedeği:** Branch adında `CU-...` yoksa (detached HEAD, main üzerinde
commit vb.) footer eklenmez ama **uyarı** verilir. Yedek olarak
`LUMIX_CU_ID=86abc123 git commit ...` ile ID'yi elle geçebilir; zorunlu kılmak için
`git config lumix.requireClickUpId true` ayarlayabilirsiniz.

---

## 3. Kod incelemesi (AI + insan) — push'tan önce

Kullanıcının istediği denetimler: **task uyumu, gereksiz yorum, optimizasyon,
SOLID/DRY/KISS**. Bunlar `docs/REVIEW_CHECKLIST.md`'de tanımlı.

- **Windows / Claude Code:** `/code-review` komutunu çalıştırın.
- **Linux / diğer AI:** `bash scripts/review.sh` çıktısını AI'nıza verin
  (Windows'ta `pwsh scripts/review.ps1` çıktıyı panoya da kopyalar).

Bulguları giderin, gerekiyorsa yeni commit atın.

> **Ölçülebilir kısmı otomatikleştirin:** SOLID/DRY/KISS ve kod standardının makineyle
> ölçülebilen kısmı için backend'e **ktlint + detekt** (Gradle plugin), frontend'e
> **ESLint** ekleyin. `./gradlew build` zaten `check`'i çalıştırdığından bu kontroller
> CI'da ve `build-check.sh`'te otomatik koşar; frontend için CI `pnpm run lint`'i çağırır.
> Böylece insan/AI incelemesi yalnızca makinenin yakalayamadığı (task uyumu, tasarım,
> gereksiz yorum) kısımlara odaklanır.

---

## 4. Senkron tutma (rebase) ve push

Branch'i taze tutmak için (merge gürültüsü yerine rebase):
```bash
bash scripts/sync.sh        # veya: pwsh scripts/sync.ps1
```

Push:
```bash
git push -u origin <branch>           # ilk push
git push --force-with-lease           # rebase sonrası (güvenli zorlama)
```

> **`pre-push` hook** push'tan önce **build'i zorunlu** çalıştırır. Build
> kırmızıysa push iptal olur. Gerçek acil durumda: `LUMIX_SKIP_BUILD=1 git push`
> (sorumluluk sizde — sunucudaki CI yine de yakalar).

---

## 5. Pull Request / Merge Request

> **Asıl remote GitLab'dır** (`gitlab.hsoylu.dev/lumix/campus`) — orada terim "Merge
> Request (MR)"dir ve sunucu doğrulamasını [.gitlab-ci.yml](../.gitlab-ci.yml) yapar
> (`backend:build`, `commit-lint`, `schema:validate`). Aşağıdaki GitHub adımları
> GitHub mirror kullanılırsa geçerlidir; kurallar birebir aynıdır.

- `.github/pull_request_template.md` otomatik açılır; kalite listesini doldurun.
- **1 onay zorunlu** — PR sahibi kendi PR'ını onaylayamadığından bu onay zorunlu olarak
  *diğer* kişiden gelir. (İki kişilik takımda "2 approval" mekanik olarak **imkânsızdır**;
  bkz. bölüm 6.) Küçük PR teşvik edilir.
- CI (`.github/workflows/ci.yml`) build + commit formatını sunucuda doğrular.
- Merge stratejisi: **Squash & merge** önerilir (main'de temiz, doğrusal geçmiş).
- Merge sonrası branch'i silin.

---

## 6. Branch protection (bir kez ayarlayın)

**GitLab (asıl remote):** Settings → Repository → *Protected branches*: `main` için
"Allowed to push = No one", "Allowed to merge = Developers+"; Settings → Merge requests:
"Pipelines must succeed" + approval sayısı **1**. Squash önerilir ("Encourage").

**GitHub mirror kullanılıyorsa** `main` için (Settings → Branches → Add rule):
- [x] Require a pull request before merging
- [x] Require approvals: **1**  ⚠️ İki kişilik takımda **2 yapmayın**: GitHub'da PR
      sahibi kendi PR'ını onaylayamaz, dolayısıyla 2 onaya asla ulaşılamaz ve tüm PR'lar
      kilitlenir. **1 onay + Code Owners** zaten "diğer kişi onaylamalı" demektir.
- [x] Require review from Code Owners  → önce `.github/CODEOWNERS`'taki
      `@oner-lumix @arkadas-lumix` placeholder'larını **gerçek GitHub handle'larıyla**
      değiştirin; aksi halde kural sessizce devre dışı kalır.
- [x] Require status checks to pass: **`Build & Test`**, **`Commit mesaj formati`**
- [x] Require branches to be up to date before merging
- [x] Require linear history
- [x] Do not allow bypassing the above settings

> Elle yerine `gh` CLI ile tek seferde de kurabilirsiniz:
> ```bash
> gh api -X PUT repos/<org>/<repo>/branches/main/protection --input - <<'JSON'
> {
>   "required_status_checks": { "strict": true, "contexts": ["Build & Test", "Commit mesaj formati"] },
>   "enforce_admins": true,
>   "required_pull_request_reviews": { "required_approving_review_count": 1, "require_code_owner_reviews": true },
>   "required_linear_history": true,
>   "restrictions": null
> }
> JSON
> ```

Bu ayarlar, lokal hook atlansa bile kuralları **sunucuda zorunlu** kılar.

---

## Araç sürümleri (sabitlenmiş)

Geliştirici makineleri ve CI aynı sürümleri kullansın diye sürümler dosyalara pinlendi:

| Dosya | Araç | İçerik |
|-------|------|--------|
| [.nvmrc](../.nvmrc) | nvm (Node) | `24` |
| [.sdkmanrc](../.sdkmanrc) | SDKMAN (Java) | `25.0.1-tem` (Java 25 LTS, ADR-002) |
| [.tool-versions](../.tool-versions) | asdf / **mise** | Java + Node birlikte |

- **Linux/macOS:** `nvm use` (Node) + `sdk env` (Java). Ya da tek araç: `mise install`.
- **Windows:** `nvm-windows` `.nvmrc`'yi **okumaz**, SDKMAN ancak Git Bash'te çalışır.
  Bu yüzden Windows'ta **[mise](https://mise.jdx.dev)** önerilir — native çalışır, hem
  `.tool-versions` hem `.nvmrc`'yi okur: `mise install` yeterli.
- **Backend build JDK'sı** ayrıca Gradle Java toolchain ile zorlanmalı (build zamanı
  otoritesi): `java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }`.
- ⚠️ **Sürüm değişiminde dört yeri birlikte güncelleyin:** `.nvmrc`, `.sdkmanrc`,
  `.tool-versions` ve `.github/workflows/ci.yml` (Java). Node sürümünü CI zaten
  `.nvmrc`'den okur, orası otomatik senkron.

## Hızlı referans

| İş | Linux/macOS | Windows |
|----|-------------|---------|
| Kurulum | `bash scripts/setup-git.sh` | `pwsh scripts/setup-git.ps1` |
| Yeni branch | `bash scripts/new-branch.sh feature 86abc "ad"` | `pwsh scripts/new-branch.ps1 feature 86abc "ad"` |
| Commit | `bash scripts/commit.sh feat auth "konu"` | `pwsh scripts/commit.ps1 feat auth "konu"` |
| İnceleme | `bash scripts/review.sh` | `/code-review` veya `pwsh scripts/review.ps1` |
| Senkron | `bash scripts/sync.sh` | `pwsh scripts/sync.ps1` |
| Build | `bash scripts/build-check.sh` | aynı (Git Bash) |

## Otomatik kapılar (özet)

| Aşama | Kontrol | Nerede |
|-------|---------|--------|
| commit | format + ClickUp footer | `.githooks/commit-msg` |
| commit | merge işareti, büyük dosya, sır | `.githooks/pre-commit` |
| push | **build zorunlu** | `.githooks/pre-push` |
| PR | build + test + commit formatı | `.github/workflows/ci.yml` |
| PR | 1 onay (diğer kişi) + CODEOWNERS | branch protection |
| inceleme | task uyumu / yorum / SOLID-DRY-KISS | `docs/REVIEW_CHECKLIST.md` |
