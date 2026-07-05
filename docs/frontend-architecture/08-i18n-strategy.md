---
title: i18n Stratejisi (react-i18next)
description: Lumix çok dilli destek — react-i18next setup, namespace, tenant timezone, date/number locale, fallback, dil değişimi.
sidebar_position: 8
---

## Bu sayfa ne anlatıyor?

Lumix **Türk + AB müşterilerine** hizmet veriyor. Bu sayfada şunları öğreneceksin:

- i18n (internationalization) ve l10n (localization) farkı
- react-i18next nasıl çalışır
- Namespace stratejisi (feature başına dosya)
- Lumix'in dil paketleri yapısı
- Tenant timezone ve user locale ayrımı
- Date/number/currency için Intl API kullanımı
- Dil değişimi UX'i ve fallback davranışı
- Backend mesajlarıyla (RFC 7807 detail) i18n birleşimi

Bu sayfa **tüm UI'da görünen metnin** standardını belirler.

## 1. i18n nedir? (Sıfırdan)

- **i18n (internationalization)**: yazılımı **çok dilli kullanılabilir** hale getirmek. Kod içinde sabit metin olmaması, her metnin "anahtar"la çağrılması.
- **l10n (localization)**: belirli bir dil/kültür için **çeviri + locale** uygulamak (TR, EN, AR, DE...).

i18n altyapı, l10n içerik.

### Günlük hayattan analoji

Bir restoran menüsü düşün:

- **i18n** = menünün şablonu: "öğe 1, öğe 2, öğe 3" yerleri belli. Yer numaralarıyla referans.
- **l10n** = o şablona Türkçe / İngilizce metin koymak. "Lahmacun" / "Turkish flatbread".
- **Locale** = dil + bölge. `tr-TR`, `en-US`, `en-GB`, `ar-SA`. Sadece dil değil; tarih, sayı, para formatı da değişir.

### Tenant timezone ile user locale farkı

| Kavram | Ne demek | Örnek |
|---|---|---|
| **User locale** | Kullanıcının arayüz dili tercihi | "Veli kullanıcısı arabesk Türkçe; öğretmen İngilizce arayüz" |
| **Tenant timezone** | Müşteri kurumun çalıştığı saat dilimi | "Ömer Okulları → `Europe/Istanbul`" |
| **Browser locale** | Tarayıcının default'u | `navigator.language` |

User locale **kullanıcı seçer**. Tenant timezone **kurum konfigürasyonu**, kullanıcı seçemez (yoklama saati hep kurum saatinde gözükmeli).

## 2. Hangi problemi çözüyor?

i18n yoksa:

- Kod içinde `<button>Save</button>` hardcoded → tek dil zorunlu
- Tarih formatı `2026-05-27` her yere yazılır → Türk kullanıcı için `27.05.2026` bekler
- Para `1234.56` → Türkçe'de `1.234,56 ₺`
- Yeni dil eklemek için tüm kodu taramak gerekir
- Çevirmen kodu okumak zorunda → çeviri kalitesi düşer

i18n ile:

- Tüm metin **JSON dosyalarında** (geliştirici değil, çevirmen düzenler)
- Tek `t('save')` çağrısı her dilde doğru kelimeyi getirir
- Locale değişince tüm tarih/sayı otomatik düzenlenir
- Yeni dil eklemek = bir dosya kopyala, çevir

## 3. Nasıl çözüyor? (Mekanizma)

### 3.1. react-i18next akışı

```
1. App boot: i18n.init({ resources, lng, fallbackLng })
   resources = { tr: { common: {...}, attendance: {...} }, en: {...} }
                ↓
2. Component: const { t } = useTranslation('attendance')
                ↓
3. JSX: <h1>{t('title')}</h1>
                ↓
4. i18next: o anki lng için `resources.tr.attendance.title` döner
                ↓
5. Locale değişti → i18n.changeLanguage('en')
                ↓
6. Tüm useTranslation hook'u re-render → yeni dil
```

### 3.2. Namespace

Tek `translation.json` 10.000 satıra çıkmasın diye **namespace** ile bölünür:

```
public/locales/
├── tr/
│   ├── common.json       # button, navigation, generic
│   ├── auth.json         # login, signup, reset
│   ├── attendance.json
│   ├── messages.json
│   ├── billing.json
│   └── admin.json
└── en/
    ├── common.json
    ├── auth.json
    ...
```

Her namespace **lazy-load** edilir → ilk yüklemede sadece `common` + aktif sayfa namespace'i bundle'a girer.

### 3.3. Pluralization ve interpolation

```json
{
  "studentCount_one": "{{count}} öğrenci",
  "studentCount_other": "{{count}} öğrenci"
}
```

```ts
t('studentCount', { count: 1 })   // "1 öğrenci"
t('studentCount', { count: 25 })  // "25 öğrenci"
```

Bazı diller (Arapça, Rusça) plural kuralları farklı; i18next ICU plural rules destekler.

### 3.4. Intl API ile tarih, sayı, para

```ts
new Intl.DateTimeFormat('tr-TR', { dateStyle: 'medium' }).format(new Date());
// "27 May 2026"

new Intl.NumberFormat('tr-TR', { style: 'currency', currency: 'TRY' }).format(1234.56);
// "1.234,56 ₺"

new Intl.RelativeTimeFormat('tr-TR', { numeric: 'auto' }).format(-3, 'day');
// "3 gün önce"
```

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. Kararlar

| Konu | Karar |
|---|---|
| Lib | **react-i18next** + **i18next-http-backend** + **i18next-browser-languagedetector** |
| Default lng | **`tr`** (Türk müşteri çoğunlukta) |
| Fallback lng | **`en`** |
| Diller (V1) | **`tr`, `en`** — sonra `de`, `ar` |
| Namespace strategy | **Feature başına** (`common`, `auth`, `attendance`, ...) |
| Source of truth | `apps/web/public/locales/{lng}/{ns}.json` |
| User locale persist | **`localStorage` (`lumix-lng`)** + backend `/me` preference |
| Tenant timezone | **Backend `/me`** → Redux `tenantSlice` |
| Date format | **Intl** API ile, `getTenantTimezone()` ile |
| Number/currency | **Intl** + tenant currency code |
| i18n hot reload (dev) | Aktif |

### 4.2. Setup

```ts
// shared/lib/i18n/index.ts
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import HttpBackend from 'i18next-http-backend';
import LanguageDetector from 'i18next-browser-languagedetector';

export const i18n = i18next
  .use(HttpBackend)
  .use(LanguageDetector)
  .use(initReactI18next);

await i18n.init({
  fallbackLng: 'en',
  supportedLngs: ['tr', 'en'],
  defaultNS: 'common',
  ns: ['common'],
  load: 'languageOnly', // 'tr-TR' yerine 'tr' yükle
  detection: {
    order: ['localStorage', 'navigator'],
    lookupLocalStorage: 'lumix-lng',
    caches: ['localStorage'],
  },
  backend: {
    loadPath: '/locales/{{lng}}/{{ns}}.json',
  },
  interpolation: { escapeValue: false }, // React zaten escape ediyor
  react: { useSuspense: true },
});
```

### 4.3. Locale JSON örnek

`public/locales/tr/attendance.json`:

```json
{
  "title": "{{className}} — Yoklama",
  "loading": "Yükleniyor",
  "submit": "Yoklamayı Kaydet",
  "status": {
    "present": "Var",
    "absent": "Yok",
    "late": "Geç"
  },
  "summary": "{{present}} var, {{absent}} yok, {{late}} geç",
  "studentCount_one": "{{count}} öğrenci",
  "studentCount_other": "{{count}} öğrenci",
  "errors": {
    "noDate": "Tarih seçmelisiniz",
    "alreadySubmitted": "Bu tarihe yoklama daha önce kaydedildi"
  }
}
```

`public/locales/en/attendance.json`:

```json
{
  "title": "{{className}} — Attendance",
  "loading": "Loading",
  "submit": "Save attendance",
  "status": {
    "present": "Present",
    "absent": "Absent",
    "late": "Late"
  },
  "summary": "{{present}} present, {{absent}} absent, {{late}} late",
  "studentCount_one": "{{count}} student",
  "studentCount_other": "{{count}} students",
  "errors": {
    "noDate": "Please choose a date",
    "alreadySubmitted": "Attendance already submitted for this date"
  }
}
```

### 4.4. Component'te kullanım

```tsx
import { useTranslation } from 'react-i18next';
import { useAppSelector } from '@/app/store/hooks';
import { formatDate, formatCurrency } from '@/shared/lib/i18n/format';

export function AttendanceSummary({ classroomName, present, absent, late, date }: Props) {
  const { t, i18n } = useTranslation('attendance');
  const tenantTz = useAppSelector((s) => s.tenant.tenantConfig?.timezone ?? 'UTC');

  return (
    <div>
      <h2>{t('title', { className: classroomName })}</h2>
      <p>{formatDate(date, { locale: i18n.language, timeZone: tenantTz })}</p>
      <p>{t('summary', { present, absent, late })}</p>
    </div>
  );
}
```

`shared/lib/i18n/format.ts`:

```ts
export function formatDate(
  date: Date | string,
  opts: { locale: string; timeZone: string; style?: 'short' | 'medium' | 'long' },
) {
  const d = typeof date === 'string' ? new Date(date) : date;
  return new Intl.DateTimeFormat(opts.locale, {
    dateStyle: opts.style ?? 'medium',
    timeZone: opts.timeZone,
  }).format(d);
}

export function formatCurrency(amount: number, locale: string, currency: string) {
  return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(amount);
}

export function formatNumber(n: number, locale: string) {
  return new Intl.NumberFormat(locale).format(n);
}
```

### 4.5. Dil değişimi UX

```tsx
import { useTranslation } from 'react-i18next';

export function LanguageSwitcher() {
  const { i18n } = useTranslation();
  const change = async (lng: 'tr' | 'en') => {
    await i18n.changeLanguage(lng);
    // backend'e user preference olarak da yaz
    fetch('/api/v1/users/me/preferences', {
      method: 'PATCH',
      credentials: 'include',
      body: JSON.stringify({ locale: lng }),
    });
  };
  return (
    <select value={i18n.language} onChange={(e) => change(e.target.value as any)}>
      <option value="tr">Türkçe</option>
      <option value="en">English</option>
    </select>
  );
}
```

### 4.6. Backend RFC 7807 ile i18n

Backend hata mesajları **i18n key** olarak dönebilir:

```json
{
  "errors": [
    { "field": "email", "code": "ALREADY_EXISTS", "messageKey": "errors.user.emailAlreadyExists" }
  ]
}
```

Frontend `t(error.messageKey)` ile çevirir. **Fallback** mesajı backend hep verir (i18n çevirisi yoksa `message` field'ı kullanılır).

## 5. Neden bu seçim? (Alternatifler)

| Alternatif | Neden elendi |
|---|---|
| **react-intl (FormatJS)** | ICU MessageFormat güçlü ama JSX `<FormattedMessage />` boilerplate'i fazla |
| **LinguiJS** | Macro tabanlı; build complexity, dev tooling ekstra |
| **i18n-js (basic)** | Çok minimal; lazy-load, namespace, suspense yok |
| **Kendi i18n** | Boş yere icat |
| **react-i18next** ✅ | Olgun, geniş ekosistem, Suspense desteği, backend lazy-load, çok dilli ekibe uygun |

### Trade-off

- **ICU pluralization syntax**: FormatJS'in `{count, plural, one {# item} other {# items}}` syntax'ı daha güçlü; i18next plural simpler. Bizim ihtiyaçlar için yeterli.
- **Bundle size**: ~30KB. Lazy-load namespace ile bandwidth korunur.

## 6. Pratik örnek — Suspense ile lazy load

```tsx
// app/App.tsx
import { Suspense } from 'react';
import { Spinner } from '@/shared/ui/Spinner';

export function App() {
  return (
    <Suspense fallback={<Spinner />}>
      <Routes />
    </Suspense>
  );
}
```

```tsx
// pages/attendance/AttendancePage.tsx
import { useTranslation } from 'react-i18next';

export function AttendancePage() {
  // namespace 'attendance' lazy yükleniyor; yüklenirken Suspense fallback
  const { t } = useTranslation('attendance');
  return <h1>{t('title', { className: '11-A' })}</h1>;
}
```

### Type-safe i18n (opsiyonel)

```ts
// shared/lib/i18n/types.ts
import 'i18next';
import attendance from '../../../public/locales/tr/attendance.json';
import common from '../../../public/locales/tr/common.json';

declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'common';
    resources: {
      common: typeof common;
      attendance: typeof attendance;
    };
  }
}
```

Bundan sonra `t('xyz')` TS hatası vermez/verir — typo yakalanır.

## 7. Tuzaklar

- **Sabit string unutmak**: Hardcoded `"Kaydet"` kaldı → ESLint kuralı (`react/jsx-no-literals` veya custom) ile yakala.
- **Dil değişiminde re-render unutmak**: `useTranslation` Hook'u re-render eder; `t()` plain çağrı (örn. utility fonksiyonda) re-render etmez.
- **Plural'ı string concat ile yapmak**: `count + ' öğrenci'` yerine `t('studentCount', { count })`.
- **Date'i `new Date().toLocaleString()` ile**: Browser locale kullanır; tenant timezone'u kaçırırsın. Hep `Intl.DateTimeFormat({ timeZone })`.
- **Currency hardcode**: `${amount}₺` yazma; tenant currency neyse onunla `formatCurrency`.
- **Long key syntax karmaşası**: `t('a.b.c.d.e.f')` — namespace'i akıllı seç, derinliği azalt.
- **Interpolasyon escape**: `escapeValue: false` (React zaten escape ediyor); başka renderer'da `true` yap.
- **Backend message mı, frontend i18n mi?**: İkisi de olabilir. Karar: kullanıcıya gösterilecek tüm mesaj için i18n key + fallback `message` zorunlu.
- **RTL diller**: Arapça eklediğinizde `dir="rtl"` HTML attribute'u + CSS logical properties (`margin-inline-start` vs.) — şimdiden alışkanlık edin.
- **Lazy load Suspense unutmak**: İlk dil değişiminde `useTranslation` cevap vermez; `Suspense` wrap.
- **Çeviri eksik anahtar production'da kırmızı**: i18next `missingKeyHandler` ile dev'de uyar, prod'da fallback dile geç.

## 8. Diğer konularla ilişkisi

- [Form Handling](./07-form-handling.md) — schema mesajları i18n key
- [Redux Toolkit](./02-redux-toolkit.md) — `uiSlice` locale tutuyor
- [Token Storage](./06-frontend-token-storage.md) — login formu i18n
- [Installation/Tenant/Scope](../01-tenancy-and-domain-model/01-installation-tenant-scope.md) — tenant timezone kavramı
- [Mobile Shared Logic](../10-frontend-mobile/02-shared-business-logic.md) — i18n paketleri paylaşımı

## 9. Daha derine

- react-i18next: https://react.i18next.com/
- i18next: https://www.i18next.com/
- MDN Intl: https://developer.mozilla.org/docs/Web/JavaScript/Reference/Global_Objects/Intl
- ICU MessageFormat: https://unicode-org.github.io/icu/userguide/format_parse/messages/
- Search keywords:
  - `react i18next namespace lazy load suspense`
  - `intl datetimeformat timezone`
  - `react i18next typescript type safe keys`
  - `i18next pluralization rules languages`
  - `multi tenant timezone vs user locale separation`

## 10. Sözlük

- **i18n** — Internationalization. Yazılımı çok dilli yapma altyapısı.
- **l10n** — Localization. Belirli bir dil/kültür için içerik üretme.
- **Locale** — Dil + bölge kombinasyonu (`tr-TR`, `en-US`).
- **Namespace** — i18n key'lerini gruplama yapısı (feature başına dosya).
- **Interpolation** — Çeviri içine değişken yerleştirme (`{{name}}`).
- **Pluralization** — Sayıya göre çoğul ekleri (`one`, `few`, `many`, `other`).
- **Fallback language** — Aktif dilde anahtar yoksa kullanılacak dil.
- **Intl API** — JavaScript'in built-in locale-aware formatting kütüphanesi.
- **Timezone** — Saat dilimi (`Europe/Istanbul`).
- **Suspense** — React'in async loading orchestration mekanizması.
- **MessageFormat (ICU)** — Karmaşık plural/gender mesaj formatı standardı.
