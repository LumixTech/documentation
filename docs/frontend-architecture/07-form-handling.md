---
title: Form Handling (React Hook Form + Zod)
description: Lumix form mimarisi — React Hook Form, Zod schema validation, RFC 7807 error mapping, Mantine/Ant Design entegrasyonu.
sidebar_position: 7
---

## Bu sayfa ne anlatıyor?

Lumix'te formlar nasıl yazılır? Bu sayfada şunları öğreneceksin:

- React'te form state yönetiminin temel zorlukları
- React Hook Form (RHF) ile uncontrolled component yaklaşımı
- Zod ile **schema-first validation**
- `@hookform/resolvers/zod` ile bağlama
- Backend RFC 7807 `Problem Details` response'unun form field error'larına eşlenmesi
- UI kit (Mantine ya da Ant Design) ile RHF entegrasyonu
- Lumix'in standart form pattern'i ve `<FormField />` wrapper

Bu sayfa **bütün veri girişi ekranlarının** (login, attendance form, mesaj gönder, kullanıcı oluştur, fatura kes...) temelidir.

## 1. Form nedir, neden zor? (Sıfırdan)

Form, kullanıcıdan veri toplayan UI bloğu: input, select, checkbox, textarea. Görünüşte basit; ama production'da:

- **State**: her input bir state'e bağlı (controlled) → her tuşa basışta re-render → yavaşlama
- **Validation**: hem client-side (anlık feedback) hem server-side (güvenlik) gerek
- **Hata gösterimi**: hangi field hatalı, mesaj ne, dile göre i18n
- **Submit lifecycle**: loading, success, error, retry
- **Dirty/touched tracking**: kullanıcı bir şey değiştirdi mi, hangisini?
- **Backend error mapping**: backend "email zaten kayıtlı" diyor → email field'a koy

### Günlük hayattan analoji

Bir vergi formu doldur:

- **State** = elindeki kağıdın o anki dolu hali
- **Validation** = "Bu alana sadece sayı, minimum 5 hane" gibi kurallar
- **Submit** = formu memura uzatmak; reddedilirse hangi satır yanlış işaretlenmiş
- **Schema** = formun şablonu (önceden basılmış, alan isimleri belli)

React Hook Form + Zod bu vergi formunu **temiz, hızlı, type-safe** yazma yolu.

## 2. Hangi problemi çözüyor?

Naive React form (controlled):

```tsx
const [email, setEmail] = useState('');
const [password, setPassword] = useState('');
const [errors, setErrors] = useState<{ email?: string; password?: string }>({});

<input value={email} onChange={(e) => setEmail(e.target.value)} />
// her tuşa basışta tüm form re-render
```

Sorunlar:

- **Performans**: 20 field'lık formda her keystroke 20 input'u re-render
- **Boilerplate**: her field için useState, handler, error tracking
- **Validation karmaşası**: nerede, ne zaman, ne mesaj
- **Type safety yok**: form state ne içeriyor, TypeScript bilmiyor

React Hook Form + Zod bu sorunları:

- **Uncontrolled** input → re-render minimal
- **`register`** ile tek satırda bağlama
- **Zod schema** ile type + validation tek yerde
- **`useForm<z.infer<typeof Schema>>()`** ile full TypeScript inference

## 3. Nasıl çözüyor? (Mekanizma)

### 3.1. React Hook Form'un yaklaşımı

RHF input'ları **uncontrolled** tutar — React state değil, native DOM input'unun kendi value'su. RHF sadece `ref` ile input'a tutunur, bir map'te tutar.

```
useForm() çağırır → form state internal context'e konur
   ↓
register('email') → input'a ref bağlar
   ↓
kullanıcı yazar → React state YOK → re-render YOK
   ↓
submit → RHF tüm ref'lerden value okur → onSubmit({ email, password })
   ↓
validation çalışır (varsa) → errors map'i güncellenir → sadece error UI re-render
```

### 3.2. Zod nedir?

**Zod**, TypeScript-first schema validation library. Şema yazarsın, hem runtime validation yapar hem TypeScript tip üretir.

```ts
import { z } from 'zod';

const LoginSchema = z.object({
  email: z.string().email('Geçerli e-posta girin'),
  password: z.string().min(8, 'En az 8 karakter'),
  remember: z.boolean().optional(),
});

type LoginValues = z.infer<typeof LoginSchema>;
// { email: string; password: string; remember?: boolean }
```

### 3.3. RHF + Zod birleşimi

```tsx
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';

const LoginSchema = z.object({
  email: z.string().email(),
  password: z.string().min(8),
});
type LoginValues = z.infer<typeof LoginSchema>;

function LoginForm() {
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({
    resolver: zodResolver(LoginSchema),
    defaultValues: { email: '', password: '' },
  });

  const onSubmit = (values: LoginValues) => {
    // values type-safe, validation geçmiş
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)}>
      <input {...register('email')} />
      {errors.email && <span>{errors.email.message}</span>}
      <input type="password" {...register('password')} />
      {errors.password && <span>{errors.password.message}</span>}
      <button disabled={isSubmitting}>Giriş</button>
    </form>
  );
}
```

### 3.4. Backend error mapping (RFC 7807)

Backend Spring `@Valid` ile field error'ları RFC 7807 `Problem Details` formatında döner:

```json
{
  "type": "https://lumix.io/errors/validation",
  "title": "Validation Error",
  "status": 400,
  "detail": "İstek geçersiz alanlar içeriyor",
  "instance": "/api/v1/users",
  "errors": [
    { "field": "email", "code": "ALREADY_EXISTS", "message": "Bu e-posta zaten kayıtlı" },
    { "field": "password", "code": "TOO_WEAK", "message": "Şifre güçlü değil" }
  ]
}
```

Frontend bunu RHF'in `setError` ile field'lara koyar:

```ts
import { useForm } from 'react-hook-form';

function CreateUserForm() {
  const { setError, handleSubmit, register, formState } = useForm<FormValues>({
    resolver: zodResolver(Schema),
  });

  const onSubmit = async (values: FormValues) => {
    try {
      await createUser(values).unwrap();
    } catch (err: any) {
      const problem = err?.data;
      if (problem?.errors) {
        problem.errors.forEach((e: { field: string; message: string }) => {
          setError(e.field as keyof FormValues, {
            type: 'server',
            message: e.message,
          });
        });
      } else {
        setError('root.serverError', {
          type: 'server',
          message: problem?.detail ?? 'Beklenmeyen hata',
        });
      }
    }
  };
  // ...
}
```

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. Kararlar

| Konu | Karar |
|---|---|
| Form library | **React Hook Form 7+** |
| Validation | **Zod 3+** |
| Resolver | **`@hookform/resolvers/zod`** |
| UI kit | **Mantine** veya **Ant Design** (implementasyonda netleşir) — Lumix wrapper ile soyutlu |
| Error mapping | **RFC 7807 → `setError`** |
| i18n | Schema mesajları i18n key olarak; render'da `t()` |
| Schema lokasyonu | `features/<feature>/model/schema.ts` veya `entities/<entity>/model/schema.ts` |
| Defaults | `defaultValues` her zaman set edilir |

### 4.2. Lumix `<FormField />` wrapper

UI kit bağımsız tek bir wrapper:

```tsx
// shared/ui/FormField.tsx
import { Controller, FieldValues, Control, Path } from 'react-hook-form';
import { TextInput, type TextInputProps } from '@mantine/core'; // veya Ant Design

type Props<T extends FieldValues> = {
  name: Path<T>;
  control: Control<T>;
  label: string;
} & Omit<TextInputProps, 'value' | 'onChange' | 'error'>;

export function FormField<T extends FieldValues>({ name, control, label, ...rest }: Props<T>) {
  return (
    <Controller
      name={name}
      control={control}
      render={({ field, fieldState }) => (
        <TextInput
          {...rest}
          label={label}
          value={field.value ?? ''}
          onChange={(e) => field.onChange(e.currentTarget.value)}
          onBlur={field.onBlur}
          error={fieldState.error?.message}
        />
      )}
    />
  );
}
```

Bu sayede UI kit değişirse tek dosya değiştirilir.

### 4.3. Schema örnek (kullanıcı oluşturma)

```ts
// features/user/model/createUserSchema.ts
import { z } from 'zod';

export const CreateUserSchema = z.object({
  email: z.string().email('form.errors.email.invalid'),
  fullName: z
    .string()
    .min(2, 'form.errors.fullName.tooShort')
    .max(120, 'form.errors.fullName.tooLong'),
  role: z.enum(['admin', 'teacher', 'parent', 'student']),
  tenantId: z.string().uuid(),
  sendInvite: z.boolean().default(true),
  birthDate: z
    .string()
    .regex(/^\d{4}-\d{2}-\d{2}$/, 'form.errors.birthDate.format')
    .optional(),
});

export type CreateUserValues = z.infer<typeof CreateUserSchema>;
```

Render sırasında i18n key'i mesaja çevrilir:

```tsx
{errors.email && <span>{t(errors.email.message ?? '')}</span>}
```

### 4.4. RTK Query mutation ile birlikte

```tsx
// features/user/ui/CreateUserForm.tsx
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useTranslation } from 'react-i18next';

import { CreateUserSchema, CreateUserValues } from '../model/createUserSchema';
import { useCreateUserMutation } from '../api/userApi';
import { FormField } from '@/shared/ui/FormField';

export function CreateUserForm({ onSuccess }: { onSuccess: () => void }) {
  const { t } = useTranslation('user');
  const { control, handleSubmit, setError, formState } = useForm<CreateUserValues>({
    resolver: zodResolver(CreateUserSchema),
    defaultValues: {
      email: '',
      fullName: '',
      role: 'teacher',
      sendInvite: true,
    },
  });
  const [createUser, { isLoading }] = useCreateUserMutation();

  const onSubmit = async (values: CreateUserValues) => {
    try {
      await createUser(values).unwrap();
      onSuccess();
    } catch (err: any) {
      const problem = err?.data;
      if (problem?.errors) {
        problem.errors.forEach((e: any) =>
          setError(e.field as keyof CreateUserValues, {
            type: 'server',
            message: e.message,
          }),
        );
      } else {
        setError('root.serverError', {
          type: 'server',
          message: problem?.detail ?? t('form.errors.unknown'),
        });
      }
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate>
      <FormField name="email" control={control} label={t('form.email')} />
      <FormField name="fullName" control={control} label={t('form.fullName')} />
      {/* role select için ayrı wrapper */}
      <FormField name="birthDate" control={control} label={t('form.birthDate')} />

      {formState.errors.root?.serverError && (
        <div role="alert" className="form-error">
          {formState.errors.root.serverError.message}
        </div>
      )}

      <button type="submit" disabled={isLoading || formState.isSubmitting}>
        {t('form.submit')}
      </button>
    </form>
  );
}
```

## 5. Neden bu seçim? (Alternatifler)

| Alternatif | Neden elendi |
|---|---|
| **Formik** | Controlled component'lere dayalı, yavaş, dev experience daha az; bakım yavaş |
| **Final Form** | Stabil ama RHF kadar hızlı değil; ekosistem küçük |
| **Native + custom hook** | Boilerplate çok, validation lib şart |
| **Yup (Zod yerine)** | Yup TypeScript inference yetersiz; Zod TS-first |
| **Joi** | Backend için yaygın, frontend'de ağır |
| **Native HTML5 validation** | Hızlı ama özelleştirme sınırı düşük (i18n, async, custom rule zor) |
| **React Hook Form + Zod** ✅ | Hızlı, type-safe, ekosistem, Lumix backend RFC 7807 ile temiz mapping |

### Trade-off

- **Uncontrolled karmaşıklığı**: Conditional render, dynamic field array gibi senaryolarda RHF spesifik API (`useFieldArray`) öğrenilmeli.
- **Mantine vs Ant Design**: Karar gelecek; ama `<FormField />` wrapper'ımız bizi bağımsız tutar.

### Ne zaman gözden geçiririz?

- React 19+ Form Actions stable olursa
- RSC (React Server Components) yaygınlaşırsa (Lumix CSR olduğu için yakın zamanda etkisi yok)

## 6. Pratik örnek — dynamic field array (yoklama)

```tsx
import { useForm, useFieldArray } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';

const AttendanceSchema = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  records: z.array(z.object({
    studentId: z.string().uuid(),
    status: z.enum(['present', 'absent', 'late']),
    note: z.string().max(200).optional(),
  })).min(1),
});
type AttendanceValues = z.infer<typeof AttendanceSchema>;

export function AttendanceBatchForm({ initialRecords, classroomId }: Props) {
  const { control, handleSubmit, register, formState } = useForm<AttendanceValues>({
    resolver: zodResolver(AttendanceSchema),
    defaultValues: {
      date: new Date().toISOString().slice(0, 10),
      records: initialRecords,
    },
  });
  const { fields } = useFieldArray({ control, name: 'records' });
  const [submit] = useSubmitAttendanceMutation();

  const onSubmit = async (values: AttendanceValues) => {
    await submit({ classroomId, ...values }).unwrap();
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)}>
      <input type="date" {...register('date')} />
      <ul>
        {fields.map((f, i) => (
          <li key={f.id}>
            <span>{f.studentName /* defaultValues içinde geldi */}</span>
            <select {...register(`records.${i}.status`)}>
              <option value="present">Var</option>
              <option value="absent">Yok</option>
              <option value="late">Geç</option>
            </select>
            <input {...register(`records.${i}.note`)} placeholder="Not" />
            {formState.errors.records?.[i]?.note && (
              <span>{formState.errors.records[i]!.note!.message}</span>
            )}
          </li>
        ))}
      </ul>
      <button type="submit">Kaydet</button>
    </form>
  );
}
```

## 7. Tuzaklar

- **`defaultValues` set etmemek**: Field controlled/uncontrolled karışıklığı; "changing from uncontrolled" warning.
- **`register` ve `Controller` karıştırmak**: Native input için `register`, third-party UI component için `Controller`.
- **Async validation infinite loop**: `useEffect` ile değer izleyip `setValue` çağırmak → loop. `watch` veya proper subscription.
- **`reset` formu unutmak**: Submit success sonrası reset etmezsen kullanıcı tekrar gönderir.
- **Server error'ı toast'a yazıp setError yapmamak**: Field-level hata field'a yapışmalı.
- **Zod schema'da `.parse()` kullanıp throw bekletmek**: RHF için `safeParse` kullan veya `zodResolver` ile bağla.
- **Hidden field validation atlama**: `disabled` field RHF'e dahil değil; `disabled` yerine `readOnly` veya `register` ile manage et.
- **Performans regression**: Çok büyük form'da `mode: 'onChange'` kullanma → her keystroke validation. Default `mode: 'onSubmit'` veya `onBlur`.
- **`react-hook-form` v6 vs v7 API**: Eski tutoriallar v6, biz v7. `errors` → `formState.errors`, vs.
- **i18n key olarak validate mesajı koymak ama anahtarın i18n'de olmaması**: Test ve fallback gerekli.
- **`Controller` içinde `field.onChange(e)` yerine `field.onChange(e.target.value)` unutmak** — UI kit event objesi mi yoksa value mu döner, kontrol et.

## 8. Diğer konularla ilişkisi

- [RTK Query](./33-rtk-query.md) — formdan submit edilen mutation
- [Permission Cache](./09-frontend-permission-cache.md) — form action visibility
- [i18n Stratejisi](./08-i18n-strategy.md) — error mesajları
- [Token Storage](./06-frontend-token-storage.md) — login formu
- [Backend: RFC 7807 Problem Details](../00-overview/02-technology-stack-decisions.md) — error format kararı

## 9. Daha derine

- React Hook Form: https://react-hook-form.com/
- Zod: https://zod.dev/
- `@hookform/resolvers`: https://github.com/react-hook-form/resolvers
- RFC 7807: https://datatracker.ietf.org/doc/html/rfc7807
- Search keywords:
  - `react hook form zod resolver typescript`
  - `react hook form server error setError`
  - `react hook form useFieldArray dynamic forms`
  - `zod schema validation typescript`
  - `rfc 7807 problem details react form mapping`

## 10. Sözlük

- **Controlled component** — Input değeri React state'inden gelir; her keystroke re-render.
- **Uncontrolled component** — Input değeri DOM'da; React ref ile okur.
- **`register`** — RHF'in uncontrolled input'a ref bağladığı fonksiyon.
- **`Controller`** — Controlled UI component'leri RHF'e bağlayan wrapper.
- **Schema** — Verinin yapısını ve doğrulama kurallarını tanımlayan deklaratif obje.
- **Resolver** — RHF'i bir validation lib'e bağlayan adaptör.
- **`setError`** — Programatik field-level hata setleme.
- **Dirty** — Kullanıcı bir field'ı değiştirmiş mi?
- **Touched** — Kullanıcı bir field'a girip çıkmış mı?
- **RFC 7807 Problem Details** — HTTP API hata response standardı.
- **`useFieldArray`** — Dynamic listeli form alanları için RHF hook'u.
