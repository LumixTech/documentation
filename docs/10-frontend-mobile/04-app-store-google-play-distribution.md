---
title: App Store + Google Play Distribution
description: Lumix mobile dağıtım — App Store + Google Play yayın akışı, code signing, beta dağıtım (TestFlight + Internal Track), versioning, OTA update opsiyonu.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

Lumix mobile uygulaması son kullanıcıya **nasıl ulaşır**? Bu sayfa şunları açıklar:

- App Store ve Google Play yayın temel akışları
- iOS code signing (sertifika, provisioning profile, App Store Connect API)
- Android code signing (keystore, Play Console)
- Beta dağıtım: TestFlight (iOS) ve Internal Testing Track (Android)
- Versioning stratejisi (`semver` + buildNumber)
- CI/CD ile otomatik build + submit (Fastlane + GitLab CI)
- OTA update opsiyonu (Expo Updates / CodePush)
- Apple App Review ve Google Play Review pratik notları

Bu sayfa **production'a gitmek isteyen herkesin** mecburi okumasıdır.

## 1. App Store ve Google Play (Sıfırdan)

Mobile app'i kullanıcı **mağazadan** indirir. İki büyük mağaza:

- **Apple App Store** (iOS) — Apple Developer Program ($99/yıl). Her sürüm Apple review'undan geçmek zorunda (1-3 gün). Sıkı kurallar.
- **Google Play** (Android) — Google Play Developer Account ($25 tek seferlik). Review daha hızlı (saatler ila 1 gün), kurallar görece esnek.

### Günlük hayattan analoji

Kitap yazdın → kitabı kitapçılara koyacaksın:

- **App Store** = Apple Kitabevi. Editörü çok titiz; tüm kitabı okuyor; uygun bulursa rafına koyuyor.
- **Google Play** = Google Kitabevi. Daha hızlı kabul; ama "şikayet" mekanizması güçlü; problem çıkarsa raftan alır.
- **TestFlight** = "bu kitabın taslağını 100 kişiye dağıtıyorum, görüş alıyorum" mekanizması (iOS).
- **Internal Testing** = aynısının Android tarafı.

## 2. Hangi problemi çözüyor?

Mağaza dağıtımı olmadan:

- Kullanıcı APK indirip yan yükleyemez (Android'de mümkün ama enterprise mode dışında ağır)
- iOS'ta zaten **mecburi** App Store (enterprise dağıtım haricinde)
- Update mekanizması yok → kullanıcı eski versiyonda kalır
- Trust yok → "bu app'i indir" demek için brand ve mağaza onayı

Mağaza ile:

- Otomatik update
- Trust seal (Apple/Google review)
- Geniş dağıtım
- Crash reporting + telemetry built-in

### Lumix senaryosu

- Müşteri kurumlar app'i kullanıcılarına (öğretmen, veli, öğrenci) önerir
- App Store/Play'den public install
- Customer-specific config login sonrası backend'den gelir (`installation_id`, branding)
- White-label gerek: mağazada **"Lumix"** tek app — installation'a göre login akışı

(Müşteri kendi branding ile **ayrı app** istiyorsa enterprise mode veya kendi app rebuild — bu doc'ta out-of-scope; gelecekte ayrı doc.)

## 3. Nasıl çözüyor? (Mekanizma)

### 3.1. iOS yayın akışı

```
1. Apple Developer Account hesabını oluştur ($99/yıl)
2. App Store Connect'te app kaydı: bundle id (com.lumix.app), name, screenshots, description
3. Xcode'da:
   - Signing & Capabilities: Team seçili, automatic veya manual signing
   - Push, Background, Sign In with Apple gibi capability'ler aktif
4. Archive build:
   - Xcode → Product → Archive → IPA üretir
5. Validate → App Store Connect'e upload
6. TestFlight'a yüklenir → internal/external tester'lara dağıtılır
7. "Submit for Review" → Apple review (1-3 gün)
8. Onaylandı → "Manual release" veya "Auto release" seçeneği → store'da görünür
```

### 3.2. Android yayın akışı

```
1. Google Play Developer Account ($25 tek sefer)
2. Play Console'da app kaydı: package name (com.lumix.app), Store listing, içerik derecelendirme
3. Signing key oluştur (App Signing by Google Play önerilir):
   - Upload key (local) + App signing key (Play)
4. Build:
   - ./gradlew bundleRelease → AAB (Android App Bundle) üretir
5. Internal testing track'e yükle → ekibe dağıt
6. Closed testing (alpha/beta) → seçili kullanıcılara
7. Production release → ne kadar % rollout (örn. 10% → 50% → 100%)
8. Review (saatler-1 gün) → store'da görünür
```

### 3.3. Code signing (kritik kavram)

App'in **gerçekten Lumix tarafından** üretildiğini ispatlar.

**iOS**:
- **Distribution certificate**: Apple Developer team'de yaratılan sertifika (1 yıl)
- **Provisioning profile**: bundle id + sertifika + capability'leri eşleyen dosya
- App Store Connect API key ile programatik signing (Fastlane Match için)

**Android**:
- **Keystore (`.jks` veya `.keystore`)**: anahtar çifti içerir; APK'yı imzalar
- **Upload key**: Play Console'a yüklediğin imza; Play kendi App signing key ile yeniden imzalar
- Keystore **kaybolursa app güncellenemez** → Vault'ta yedek!

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. Kararlar

| Konu | Karar |
|---|---|
| Bundle/Package id | **`com.lumix.app`** |
| iOS signing | **Fastlane Match** (Git repo'da şifreli sertifika) |
| Android signing | **App Signing by Google Play** + upload key Vault'ta |
| CI/CD | **GitLab CI** + **Fastlane** |
| iOS build agent | **Self-hosted Mac mini** (GitLab Runner) |
| Android build agent | **Linux runner** (Docker) |
| Beta dağıtım iOS | **TestFlight** (internal + external) |
| Beta dağıtım Android | **Internal Testing Track** |
| Versioning | `semver` (`1.4.0`) + `buildNumber` (CI auto-increment) |
| Release cadence | **2 haftada bir** + hotfix kuralı |
| Rollback stratejisi | Android: staged rollout halt + previous version. iOS: yeni version + force update |
| OTA update | **Şimdilik yok**; sadece native build (Apple policy + güvenlik) |

### 4.2. Versioning kuralı

```
Version (semver):     MAJOR.MINOR.PATCH
                      1.4.0
Build number:         CI build counter (auto-increment)
                      2451
```

- **MAJOR** — Breaking change (büyük redesign, eski backend incompatibility)
- **MINOR** — Yeni feature
- **PATCH** — Bug fix

Lumix'in store'lardaki versiyon her platformda **aynı major.minor** olmalı; patch farkı tolere edilir.

### 4.3. Fastlane konfigürasyonu (iOS örnek)

`apps/mobile/ios/fastlane/Fastfile`:

```ruby
default_platform(:ios)

platform :ios do
  desc "Build and upload to TestFlight"
  lane :beta do
    setup_ci if is_ci

    match(
      type: "appstore",
      git_url: ENV["MATCH_GIT_URL"],
      readonly: is_ci
    )

    increment_build_number(
      xcodeproj: "Lumix.xcodeproj",
      build_number: ENV["CI_PIPELINE_IID"]
    )

    build_app(
      workspace: "Lumix.xcworkspace",
      scheme: "Lumix",
      export_method: "app-store",
      export_options: {
        provisioningProfiles: {
          "com.lumix.app" => "match AppStore com.lumix.app"
        }
      }
    )

    upload_to_testflight(
      skip_waiting_for_build_processing: true,
      api_key_path: ENV["APP_STORE_CONNECT_API_KEY_PATH"]
    )
  end

  desc "Submit to App Store"
  lane :release do
    # benzer + upload_to_app_store
  end
end
```

### 4.4. Fastlane (Android örnek)

`apps/mobile/android/fastlane/Fastfile`:

```ruby
default_platform(:android)

platform :android do
  desc "Build AAB and upload to Internal Track"
  lane :beta do
    gradle(task: "clean")
    gradle(
      task: "bundle",
      build_type: "Release",
      properties: {
        "android.injected.signing.store.file" => ENV["KEYSTORE_PATH"],
        "android.injected.signing.store.password" => ENV["KEYSTORE_PASSWORD"],
        "android.injected.signing.key.alias" => ENV["KEY_ALIAS"],
        "android.injected.signing.key.password" => ENV["KEY_PASSWORD"]
      }
    )
    upload_to_play_store(
      track: "internal",
      release_status: "draft",
      aab: "app/build/outputs/bundle/release/app-release.aab",
      json_key: ENV["PLAY_CONSOLE_JSON_KEY_PATH"]
    )
  end

  lane :production do
    # benzer + track: "production", rollout: "0.1"
  end
end
```

### 4.5. GitLab CI pipeline

`.gitlab-ci.yml` (özet):

```yaml
stages: [build, beta, production]

mobile-ios-beta:
  stage: beta
  tags: [macos]  # self-hosted Mac runner
  rules:
    - if: $CI_COMMIT_TAG =~ /^mobile-v.*-beta$/
  before_script:
    - cd apps/mobile/ios
    - bundle install
    - pod install
  script:
    - bundle exec fastlane beta

mobile-android-beta:
  stage: beta
  image: registry.gitlab.com/fastlane/android:latest
  rules:
    - if: $CI_COMMIT_TAG =~ /^mobile-v.*-beta$/
  before_script:
    - cd apps/mobile
    - pnpm install --frozen-lockfile
    - cd android
    - bundle install
  script:
    - bundle exec fastlane beta
  variables:
    KEYSTORE_PATH: $CI_PROJECT_DIR/secrets/lumix-upload.jks
```

### 4.6. Sertifika ve sırlar yönetimi

- **iOS sertifikaları**: Vault → Match private repo (şifreli git)
- **Android keystore**: Vault'ta encrypted; pipeline'da decrypt → temp file
- **App Store Connect API key**: Vault → Pipeline env
- **Play Console JSON service account**: Vault → Pipeline env

GitLab CI Vault entegrasyonu: External Secrets Operator + GitLab CI Vault integration (`vault:`).

### 4.7. Apple App Review hazırlık

Lumix gibi B2B SaaS app'lerin review'da takıldığı yerler:

1. **Test hesabı**: App Store Connect'te review için aktif test hesabı (öğretmen rol) ver.
2. **Demo data**: Login sonrası boş ekran gözükmesin; örnek mesaj, sınıf, yoklama olsun.
3. **In-app purchase**: Yoksa "Sign in with Apple" zorunluluğu (4.8 guideline) için bizde **yok** çünkü app login backend-based, sosyal login yok.
4. **Privacy policy + EULA**: app içinden link verilmeli.
5. **Push notification permission rationale**: Soft prompt'la açıkla.

### 4.8. Google Play Review hazırlık

1. **Data safety formu**: Hangi data toplandığı, paylaşıldığı net.
2. **Target API level**: Yıllık güncellenir; geride kalma.
3. **Sensitive permissions**: `READ_CONTACTS`, `READ_PHONE_STATE` vs kullanmıyorsak deklare etme.
4. **Internal testing aktif**: Production'a göndermeden önce minimum 14 gün internal testing iyi pratik.

## 5. Neden bu seçim? (Alternatifler)

| Alternatif | Neden elendi |
|---|---|
| **EAS Build (Expo)** | Bare workflow + self-hosted CI ile daha kontrollü; EAS pricing tier |
| **Bitrise / CircleCI** | Hazır ama maliyet; GitLab CI zaten kurulu |
| **Manuel Xcode/Android Studio** | Reproducibility yok; bug riski; tek geliştirici darboğazı |
| **Microsoft App Center** | OTA güzeldi ama EOL ediliyor |
| **Codemagic** | Hazır ama ek vendor |
| **Fastlane + GitLab CI + self-hosted runner** ✅ | Tam kontrol, ek vendor yok, mevcut CI ile entegre |

### Trade-off

- **Mac runner mecbur** (iOS için): Mac mini sahiplenmek veya MacStadium kiralamak gerekiyor.
- **Setup yorucu**: İlk Fastlane konfigürasyonu ~2 hafta.
- **Manual signing edge case'leri**: capabilities değiştiğinde provisioning profile yenileme.

### Ne zaman gözden geçiririz?

- Ekip büyürse + paralel mobile dev ekip eklenirse EAS Build'in caching ve queue avantajları cazip hale gelebilir
- White-label per-customer app gerekirse pipeline çok daha karmaşıklaşır → o zaman re-design

## 6. Pratik örnek — release süreci

```
1. Feature branch'te develop, MR aç → develop'a merge
2. develop'tan release/1.4.0 branch
3. Version bump:
   - apps/mobile/package.json: "version": "1.4.0"
   - ios/Lumix/Info.plist (xcode) veya release.config.cjs ile auto
   - android/app/build.gradle: versionName "1.4.0"
4. Tag: `mobile-v1.4.0-beta` → GitLab pipeline tetiklenir → TestFlight + Internal Track
5. QA test
6. Tag: `mobile-v1.4.0` → production pipeline
   - iOS: App Store Connect'e submit → Apple review
   - Android: production track'e %10 rollout
7. Apple onayladı → manual release veya auto
8. Android: 1 gün izle → %50 → %100
9. Crash/error rate Sentry'de kontrol → eşik aşıldıysa Android rollout halt veya iOS yeni patch (1.4.1)
```

### Hotfix akışı

```
1. main/production'da bug çıktı
2. release/1.4.1 branch açıp fix
3. Tag mobile-v1.4.1
4. Expedited App Review iste (Apple) → 1 gün
5. Android: phased rollout sıfırdan başlat
```

## 7. Tuzaklar

- **Keystore kaybetmek (Android)**: App yenilenemez; yeni package name + yeni app şart. Vault yedek + bilenler 2 kişi (Bus Factor).
- **iOS sertifika 1 yılda expire**: Reminder kur; sertifika değişince Match repo'su update.
- **Bundle id sonradan değiştirme**: Yeni app sayılır; install base kaybolur.
- **App Store screenshot her cihaz boyutu için**: 6.7", 6.5", 5.5" hep gerekli (en azından bir tanesi); Google Play daha esnek.
- **Privacy nutrition label (iOS)**: Hangi data topluyorsun? Yanıltıcı doldurma; rejection sebebi.
- **OTA update Apple policy ihlali**: JavaScript bundle remote yenileme **temel app davranışını** değiştiremez (4.7 guideline). Lumix kararı: native build sadece.
- **iOS version race**: TestFlight'ta build number aynı olamaz; CI auto-increment şart.
- **Android version code monotonic artmalı**: Düşüş upload reject.
- **APK upload yerine AAB**: Google Play artık AAB zorunlu (yeni app'ler için).
- **Local pod install eskidi**: `Podfile.lock` commit edilmeli; CI'da pod cache.
- **Push token TestFlight'ta sandbox APNs, prod App Store'da production APNs**: Backend hangi environment olduğunu bilmeli (header veya bundle id postfix); yanlışsa push gelmez.
- **App Tracking Transparency (ATT, iOS)**: 3rd party analytics tracking varsa permission gerekli; Lumix'te yok ise yine de Info.plist'te açıklama yaz.
- **Sentry symbol upload**: Crash report'larda satır numarası için symbol upload (`fastlane` plugin ile otomatik).
- **App background process**: iOS background limitleri sıkı; Background Task framework veya silent push çözüm.

## 8. Diğer konularla ilişkisi

- [React Native Foundation](./01-react-native-foundation.md) — build artifact üretimi
- [Push Notifications](./03-push-notifications.md) — APNs sertifikası dağıtım pipeline'ında
- [Shared Business Logic](./02-shared-business-logic.md) — versiyon her uygulamada
- [CI/CD (Backend)](../00-overview/02-technology-stack-decisions.md) — GitLab CI orchestration
- [Compliance & Privacy](../00-overview/02-technology-stack-decisions.md) — KVKK/GDPR app store form

## 9. Daha derine

- Fastlane: https://fastlane.tools/
- Fastlane Match: https://docs.fastlane.tools/actions/match/
- App Store Review Guidelines: https://developer.apple.com/app-store/review/guidelines/
- Google Play Console: https://developer.android.com/distribute/console
- App Signing by Google Play: https://support.google.com/googleplay/android-developer/answer/9842756
- Search keywords:
  - `fastlane match ios certificates git repo`
  - `google play app signing upload key keystore`
  - `react native ci/cd gitlab fastlane`
  - `testflight internal external testers limit`
  - `android app bundle aab vs apk`
  - `app store privacy nutrition label`

## 10. Sözlük

- **App Store Connect** — Apple'ın iOS app'leri yönetim arayüzü.
- **Google Play Console** — Google'ın Android app'leri yönetim arayüzü.
- **Bundle ID / Package Name** — App'in unique kimliği (`com.lumix.app`).
- **Provisioning profile** — iOS sertifika + bundle id + capabilities eşlemesi.
- **Distribution certificate** — App Store dağıtımı için Apple sertifikası.
- **Keystore** — Android'in imzalama anahtarı (`.jks`).
- **AAB (Android App Bundle)** — Google Play'in yeni format; cihaz başına optimize APK üretir.
- **TestFlight** — Apple'ın iOS beta dağıtım servisi.
- **Internal Testing Track** — Google Play'in iç ekip beta kanalı.
- **Phased rollout** — Yeni versiyonu kullanıcıların %X'ine kademeli verme.
- **Code signing** — App'in üreticisini doğrulayan kriptografik imza.
- **Fastlane** — iOS + Android build/sign/upload otomasyon aracı.
- **Match (Fastlane)** — Sertifika ve provisioning profile'ı git'te senkronize tutma aracı.
- **OTA update** — App'i mağaza geçmeden JavaScript bundle ile güncelleme (Apple kısıtları var).
- **Expedited Review** — Apple'a "acil bug fix, hızlı incele" talebi.
