---
title: "Playwright E2E, RBAC UI Testleri ve Form Akışları"
description: Admin, öğretmen ve veli için RBAC, form, happy path ve sad path odaklı browser-level test senaryoları.
sidebar_position: 4
---

## Giriş

Playwright E2E testleri kritik kullanıcı workflow'larını gerçek browser ortamında doğrular. Role-based UI, navigation, form, authentication ve cross-screen davranış için özellikle değerlidir.

E2E testler her kuralı kapsamamalıdır. Kullanıcı güveni, permission ve ürün değerinin kesiştiği workflow'ları korumalıdır.

## Neden Önemli

Frontend güveni sadece component render etmek değildir.

Kritik hatalar çoğu zaman layer'lar birleştiğinde görünür:

- bir role yasak button'ı görür,
- form invalid data submit eder,
- auth refresh yanlış zamanda redirect eder,
- teacher flow admin-only data'ya bağımlı olur,
- parent başka tenant route'una erişir,
- validation error görünmez veya yanıltıcıdır.

Playwright bu akışları kullanıcının bakışından doğrular.

## Temel Kavramlar

- E2E test: tam workflow'u browser üzerinden çalıştıran test.
- RBAC UI test: role-based visibility ve access behavior doğrulaması.
- Happy path: beklenen başarılı kullanıcı akışı.
- Sad path: failure veya validation senaryosu.
- Fixture user: bilinen role ve permission'a sahip stabil test account.
- Page object: tekrar eden UI interaction'lar için test abstraction.
- Test isolation: her testin tahmin edilebilir state ile başlaması.

## Gerçek Ürün Geliştirmede Problem

Her şeyi browser üzerinden test etme isteği CI'ı yavaş ve kırılgan yapar.

Daha doğru yaklaşım:

- rule'lar için unit/integration test,
- API shape için contract test,
- kritik user journey ve role-visible davranış için Playwright.

Bu yaklaşım [Test Piramidi: Unit, Integration, Contract ve E2E Stratejisi](../observability-qa/test-pyramid-unit-integration-contract-e2e) ile uyumludur.

## Yaklaşım / Tasarım

### Senaryo 1: Admin Permission Management

Hedef:

```text
Admin user role/permission güncelleyebilir; forbidden user aynı kontrollere erişemez.
```

Happy path:

```text
1. Admin olarak login ol.
2. User management aç.
3. Target user ara.
4. Role veya permission değiştir.
5. Save.
6. Success state gör.
7. Reload edip persisted value doğrula.
```

Sad path:

```text
1. Teacher veya parent olarak login ol.
2. Admin user management route'una direct navigation dene.
3. Access denied veya redirect doğrula.
4. Admin-only control'lerin görünmediğini doğrula.
```

### Senaryo 2: Teacher Attendance Flow

Hedef:

```text
Teacher assigned classroom için attendance işaretler; validation incomplete invalid submit'i engeller.
```

Happy path:

```text
1. Teacher olarak login ol.
2. Attendance screen aç.
3. Assigned classroom/date seç.
4. Student'ları mark et.
5. Attendance submit et.
6. Submitted state ve summary doğrula.
```

Sad path:

```text
1. Required selection olmadan submit dene.
2. Validation message doğrula.
3. Başka öğretmenin classroom route'una erişmeyi dene.
4. Forbidden veya not found behavior doğrula.
```

### Senaryo 3: Parent Message/Form Flow

Hedef:

```text
Parent izinli message'ları görür ve valid form submit eder; tenant/role boundary korunur.
```

Happy path:

```text
1. Parent olarak login ol.
2. Messages ekranını aç.
3. Allowed conversation seç.
4. Message gönder veya gerekli formu submit et.
5. Message/form allowed context içinde görünür.
```

Sad path:

```text
1. Başka conversation için direct URL dene.
2. Access denied veya safe empty state doğrula.
3. Invalid form submit et.
4. Validation error ve success toast olmadığını doğrula.
```

## Sektör Standardı / Best Practice

- Stable selector kullan; role/name veya gerekli yerde test ID tercih et.
- Test data deterministik seed edilmeli.
- E2E testleri küçük ve kritik workflow odaklı tut.
- Role visibility ve direct-route authorization behavior birlikte test edilmeli.
- Test order'a güvenme.
- CI'da failure için trace/video/screenshot kaydet.
- API setup shortcut'larını user-facing assertion'lardan ayır.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
e2e_role_flow(role, scenario):
  seed_user(role)
  login_as(role)
  navigate_to_scenario_page()
  assert_visible_controls_match_role(role)
  perform_happy_path()
  assert_persisted_result()
  perform_sad_path()
  assert_safe_failure()
```

Playwright skeleton:

```typescript
test('teacher marks attendance', async ({ page }) => {
  await loginAs(page, 'teacher');
  await page.goto('/attendance');

  await page.getByRole('combobox', { name: /classroom/i }).selectOption('class-a');
  await page.getByRole('checkbox', { name: /present/i }).first().check();
  await page.getByRole('button', { name: /submit/i }).click();

  await expect(page.getByText(/attendance submitted/i)).toBeVisible();
});
```

## Bizim Notlarımız / Takım Kararları

- Admin, teacher ve parent için birer kritik E2E scenario gerekir.
- RBAC UI testleri hem hidden control hem direct route protection doğrulamalıdır.
- Form flow'ları happy path ve sad path içermelidir.
- E2E scope focused kalmalı ve test pyramid'in alt katmanlarıyla desteklenmelidir.

## Harici Referanslar

- Playwright: [Documentation](https://playwright.dev/)

## Sözlük

- E2E test: browser-level workflow test.
- RBAC UI test: role-based UI doğrulaması.
- Fixture user: stabil role ve permission'a sahip test user.
- Happy path: beklenen başarılı akış.
- Sad path: beklenen failure veya validation akışı.
- Page object: page/workflow için reusable test abstraction.

## Araştırma Keywordleri

- `playwright e2e rbac ui tests forms auth flow`
- `sad path happy path playwright`
- `role based UI testing playwright`
- `playwright test data seeding`
- `playwright trace on failure ci`

## Sonuç

Playwright ürünün kritik browser workflow'larını korumalı, test piramidinin tamamının yerine geçmemelidir. Admin, teacher ve parent senaryoları role-level güven verirken alt seviye testler kural kapsamını hızlı ve net tutar.
