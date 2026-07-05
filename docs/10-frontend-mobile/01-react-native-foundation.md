---
title: React Native Temelleri (Bare Workflow)
description: Lumix mobile foundation — React Native, Expo vs bare karar, proje yapısı, navigation (React Navigation), platform farkları.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Lumix mobile uygulamasının (öğretmen / veli / öğrenci için iOS + Android) **temel taşları**. Bu sayfa şunları açıklar:

- React Native nedir, nasıl çalışır
- Expo (managed) ile bare workflow farkı
- Lumix neden bare workflow tercih ediyor
- Proje yapısı ve giriş noktaları
- React Navigation ile navigation kurma
- Platform-spesifik kod yazma (`.ios.tsx` / `.android.tsx`)
- Native module ihtiyacı (Keychain, push notification)
- Build ve dağıtım giriş kapısı

Bu sayfa **mobile için tüm diğer doc'ların altyapısı**: shared logic, push notifications, app store distribution.

## 1. React Native nedir? (Sıfırdan)

**React Native (RN)**, Facebook'un 2015'te açık kaynak yayınladığı **mobile uygulama framework'ü**. JavaScript ile yazıyorsun, çıktı **gerçek native** iOS ve Android uygulaması.

### Web React'ten farkı

| | React (web) | React Native (mobile) |
|---|---|---|
| Render hedefi | DOM (`<div>`, `<button>`) | Native view (`<View>`, `<Text>`, `<Pressable>`) |
| Styling | CSS | StyleSheet (CSS-like ama subset) |
| Layout | CSS flexbox/grid | Flexbox (varsayılan) |
| Routing | React Router (URL) | React Navigation (stack/tab) |
| Storage | localStorage | AsyncStorage / MMKV |
| Network | fetch | fetch (aynı) |
| Build | Vite/Webpack | Metro bundler + native build (Xcode / Gradle) |

### Günlük hayattan analoji

React Web bir aşçı düşün ki **tek tip mutfak ekipmanı** (tarayıcı) kullanıyor. React Native ise aşçı her ülkenin mutfak ekipmanını kullanabiliyor: New York'ta Amerikan ocağı, Tokyo'da Japon ocağı. Tarif aynı (React component), ama çıktı her ülkede o ülkenin malzemesiyle.

### React Native nasıl çalışır?

Eskiden (Old Architecture):

```
JavaScript thread (React kodu) ←→ Bridge (JSON) ←→ Native thread (iOS UIKit / Android Views)
```

Yeni mimari (Fabric + TurboModules, React Native 0.74+):

```
JavaScript thread ←→ JSI (synchronous bridge) ←→ Native modules / Fabric renderer
```

JSI sayesinde native ile JS arasında bridge serileştirme yok; performans artışı, async olmayan native çağrılar mümkün.

## 2. Hangi problemi çözüyor?

RN olmadan mobile dünyası:

- iOS için **Swift/Objective-C**, Android için **Kotlin/Java** → iki kod tabanı, iki ekip, iki sürüm
- Aynı feature iki kez yazılıyor → maliyet 2x, bug 2x, sync zorluğu
- Web ekibi mobile ekibinden ayrı, business logic paylaşımı yok

RN çözer:

- **Tek kod tabanı** (büyük çoğunluk) → iOS + Android
- **Web ekibi mobile yazabilir** → React bilen developer kolay onboard
- **Shared business logic** (Redux Toolkit + RTK Query) web ile ortak
- **Hot reload** geliştirme deneyimi

Trade-off:

- Heavy native (oyun, AR, video editing) için **gerçek native** hâlâ daha iyi
- 60 FPS animasyon için Reanimated / Skia gibi lib gerek
- Build pipeline iki platform için ayrı

## 3. Nasıl çözüyor? (Mekanizma)

### 3.1. Component örnek

```tsx
import { View, Text, Pressable, StyleSheet } from 'react-native';

export function HelloScreen() {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>Merhaba</Text>
      <Pressable style={styles.button} onPress={() => alert('Tıklandı')}>
        <Text style={styles.buttonText}>Tıkla</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 16 },
  title: { fontSize: 28, fontWeight: '700' },
  button: { marginTop: 16, padding: 12, backgroundColor: '#2563eb', borderRadius: 8 },
  buttonText: { color: 'white', fontWeight: '600' },
});
```

### 3.2. Expo (managed workflow) nedir?

**Expo**, RN üstüne kurulu **opinionated framework**. Üç şey sunar:

1. **Expo SDK**: hazır native API'ler (camera, location, push, image picker...)
2. **Expo Go / EAS Build**: native build'i bulut servisi yapar
3. **Expo Router**: file-system based navigation

**Managed workflow**: native kod yazmazsın; Expo SDK yetiyor → hızlı başlangıç. Ama:
- Custom native module ekleyemezsin (özel SDK, özel native logic)
- Bundle size genelde daha büyük

**Bare workflow**: native iOS (Xcode) ve Android (Android Studio) projeleri elinde; Expo libraries opsiyonel kullanabilirsin.

### 3.3. Metro bundler

Metro, RN'nin Webpack/Vite muadili. JS bundle'ını oluşturur, hot reload, source map yönetir.

```
metro start → JS bundle serve eder (8081 portu)
              ↓
iOS simulator / Android emulator app açılır
              ↓
Native shell bundle'ı yükler → JS thread başlar → React render
```

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. Kararlar

| Konu | Karar |
|---|---|
| RN versiyon | **0.74+** (New Architecture, Fabric + TurboModules) |
| Workflow | **Bare workflow** (custom native module ihtiyaçları için) |
| Dil | **TypeScript 5+** (strict) |
| Navigation | **React Navigation 6+** (native-stack) |
| State | **Redux Toolkit** (web ile paylaşımlı) |
| Server state | **RTK Query** (web ile paylaşımlı) |
| Storage | **MMKV** (AsyncStorage'dan 30x hızlı) — non-sensitive |
| Secret storage | **react-native-keychain** (refresh token) |
| Push | **Firebase Cloud Messaging (FCM)** Android + **APNs** iOS, **@notifee/react-native** |
| HTTP | **fetch** (web ile aynı kod) |
| Build | **EAS Build (opsiyonel)** veya **Fastlane** |
| Distribution | **App Store + Google Play** standart kanal |
| Min iOS | **15.0** |
| Min Android | **API 26 (Android 8)** |

### 4.2. Neden bare workflow?

Expo managed avantajları çok ama Lumix'te:

- **Native module ihtiyacı yüksek**: Keychain özel kullanım, custom push handler, gerekirse native crypto
- **Bundle size kontrol**: managed workflow size ekstra Expo runtime taşır
- **Build hızı**: kendi CI'da daha hızlı (GitLab CI + Fastlane)
- **iOS sertifika yönetimi**: kurumsal müşteri-spesifik signing için bare daha esnek

Ama **Expo modules (bare workflow içinde)** kullanmaya devam edebiliriz: `expo-modules-core` + ihtiyacımız olan tek tek modüller.

### 4.3. Monorepo yapısı (web + mobile paylaşım için)

```
lumix/
├── apps/
│   ├── web/                  # React + Vite
│   └── mobile/               # React Native (bare)
├── packages/
│   ├── core/                 # Redux slices, RTK Query, types, business logic
│   ├── ui-shared/            # Platform-agnostic helpers (date format, i18n)
│   └── eslint-config/
└── pnpm-workspace.yaml
```

Paylaşım: [Shared Business Logic](./shared-business-logic) doc'unda detay.

### 4.4. Mobile proje iskeleti

```
apps/mobile/
├── android/                  # native Android (Gradle)
├── ios/                      # native iOS (Xcode)
├── src/
│   ├── app/
│   │   ├── App.tsx           # giriş component
│   │   ├── navigation/       # navigators
│   │   ├── providers/        # Redux, I18n, SafeArea
│   │   └── theme/
│   ├── screens/              # ekran component'leri (web "pages" karşılığı)
│   ├── widgets/
│   ├── features/
│   ├── shared/
│   │   ├── api/              # baseQuery ile uyum
│   │   ├── ui/               # Button, Card, Input native versiyon
│   │   └── lib/              # storage, secure-storage, deep-link
├── index.js                  # registerComponent('LumixApp', App)
├── metro.config.js
├── package.json
└── app.json
```

### 4.5. `App.tsx` (giriş)

```tsx
import 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { Provider as ReduxProvider } from 'react-redux';
import { NavigationContainer } from '@react-navigation/native';
import { I18nextProvider } from 'react-i18next';

import { store } from '@lumix/core/store';
import { i18n } from '@/shared/lib/i18n';
import { RootNavigator } from '@/app/navigation/RootNavigator';

export function App() {
  return (
    <ReduxProvider store={store}>
      <I18nextProvider i18n={i18n}>
        <SafeAreaProvider>
          <NavigationContainer>
            <RootNavigator />
          </NavigationContainer>
        </SafeAreaProvider>
      </I18nextProvider>
    </ReduxProvider>
  );
}
```

### 4.6. React Navigation kurulumu

```tsx
// app/navigation/RootNavigator.tsx
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { useAppSelector } from '@lumix/core/store/hooks';
import { selectIsAuthenticated } from '@lumix/core/auth';

import { LoginScreen } from '@/screens/login/LoginScreen';
import { HomeTabs } from './HomeTabs';
import { MessageThreadScreen } from '@/screens/messages/MessageThreadScreen';
import { AttendanceScreen } from '@/screens/attendance/AttendanceScreen';

export type RootStackParamList = {
  Login: undefined;
  Home: undefined;
  MessageThread: { conversationId: string };
  Attendance: { classroomId: string; date?: string };
};

const Stack = createNativeStackNavigator<RootStackParamList>();

export function RootNavigator() {
  const isAuthed = useAppSelector(selectIsAuthenticated);
  return (
    <Stack.Navigator>
      {!isAuthed ? (
        <Stack.Screen name="Login" component={LoginScreen} options={{ headerShown: false }} />
      ) : (
        <>
          <Stack.Screen name="Home" component={HomeTabs} options={{ headerShown: false }} />
          <Stack.Screen name="MessageThread" component={MessageThreadScreen} />
          <Stack.Screen name="Attendance" component={AttendanceScreen} />
        </>
      )}
    </Stack.Navigator>
  );
}
```

```tsx
// app/navigation/HomeTabs.tsx
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';

import { DashboardScreen } from '@/screens/dashboard/DashboardScreen';
import { MessagesScreen } from '@/screens/messages/MessagesScreen';
import { ProfileScreen } from '@/screens/profile/ProfileScreen';

const Tab = createBottomTabNavigator();

export function HomeTabs() {
  return (
    <Tab.Navigator>
      <Tab.Screen name="Dashboard" component={DashboardScreen} />
      <Tab.Screen name="Messages" component={MessagesScreen} />
      <Tab.Screen name="Profile" component={ProfileScreen} />
    </Tab.Navigator>
  );
}
```

### 4.7. Platform-spesifik kod

```
Button.tsx         # platform-agnostic
Button.ios.tsx     # iOS-spesifik (Metro otomatik seçer iOS build'de)
Button.android.tsx # Android-spesifik
```

veya inline:

```tsx
import { Platform } from 'react-native';

const padding = Platform.select({ ios: 12, android: 14, default: 12 });
```

## 5. Neden bu seçim? (Alternatifler)

| Alternatif | Neden elendi |
|---|---|
| **Flutter** | Dart dilini ekibin öğrenmesi gerek; web ekibiyle paylaşım yok |
| **Native (Swift + Kotlin)** | İki kod tabanı, iki ekip → maliyet çok |
| **Capacitor + React** | WebView based, performans daha düşük; native UX zayıf |
| **PWA** | iOS Safari kısıtlamaları (push, install), kurumsal kullanıcı için yetersiz |
| **Expo managed** | Custom native module gerekecek senaryolar için fazla kısıtlayıcı |
| **React Native bare** ✅ | Web kod paylaşımı, native esneklik, kurumsal CI/CD esnekliği |

### Kabul ettiğimiz trade-off

- **Build pipeline karmaşası**: iOS için macOS build agent (Lumix GitLab Runner'da self-hosted Mac mini veya MacStadium)
- **Native debugging**: Bazı bug'lar JS değil native; Xcode/Android Studio'da debug
- **Yeni RN versiyonuna upgrade**: Üçüncü parti lib'lerin uyumu — düzenli effort

### Ne zaman gözden geçiririz?

- Flutter ekosistemi backend ekibinin diliyle uyumlu hale gelirse (uzak)
- Apple/Google policy değişikliği RN'yi etkilerse
- Performans gereksinimi heavy hale gelirse (video editing, AR)

## 6. Pratik örnek — Login screen

```tsx
// screens/login/LoginScreen.tsx
import { useState } from 'react';
import { View, Text, TextInput, Pressable, StyleSheet, Alert } from 'react-native';
import { useTranslation } from 'react-i18next';

import { useAppDispatch } from '@lumix/core/store/hooks';
import { loggedIn } from '@lumix/core/auth';
import { useLoginMutation } from '@lumix/core/auth/api';
import { saveRefreshToken } from '@/shared/lib/secure-storage';

export function LoginScreen() {
  const { t } = useTranslation('auth');
  const dispatch = useAppDispatch();
  const [login, { isLoading }] = useLoginMutation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const onSubmit = async () => {
    try {
      const r = await login({ email, password }).unwrap();
      await saveRefreshToken(r.refreshToken); // Keychain'e
      dispatch(loggedIn({
        user: r.user,
        accessToken: r.accessToken,
        permissions: r.permissions,
      }));
    } catch (e: any) {
      Alert.alert(t('errors.title'), e?.data?.detail ?? t('errors.unknown'));
    }
  };

  return (
    <View style={s.container}>
      <Text style={s.title}>{t('login.title')}</Text>
      <TextInput
        style={s.input}
        placeholder={t('login.email')}
        value={email}
        onChangeText={setEmail}
        autoCapitalize="none"
        keyboardType="email-address"
      />
      <TextInput
        style={s.input}
        placeholder={t('login.password')}
        value={password}
        onChangeText={setPassword}
        secureTextEntry
      />
      <Pressable style={s.button} onPress={onSubmit} disabled={isLoading}>
        <Text style={s.buttonText}>{t('login.submit')}</Text>
      </Pressable>
    </View>
  );
}

const s = StyleSheet.create({
  container: { flex: 1, padding: 24, justifyContent: 'center' },
  title: { fontSize: 28, fontWeight: '700', marginBottom: 24 },
  input: { borderWidth: 1, borderColor: '#ccc', borderRadius: 8, padding: 12, marginBottom: 12 },
  button: { backgroundColor: '#2563eb', padding: 14, borderRadius: 8, alignItems: 'center' },
  buttonText: { color: 'white', fontWeight: '600' },
});
```

### Dev experience komutları

```bash
# Metro start
pnpm --filter mobile start

# iOS simulator
pnpm --filter mobile ios

# Android emulator
pnpm --filter mobile android

# Native build (release)
cd apps/mobile/ios && fastlane ios build
cd apps/mobile/android && fastlane android build
```

## 7. Tuzaklar

- **`<Text>` zorunluluğu**: RN'de raw string render edilemez; `<Text>` içine sarmazsan kırmızı ekran.
- **CSS olmayan style**: `display: flex` default; `position: absolute` farklı davranır; px birim yok (number).
- **`onClick` yerine `onPress`**: Web alışkanlığı.
- **`StyleSheet.create` yerine inline style**: Performans için `create` kullan (referans cache'leniyor).
- **Native module değişince Metro reload yetmez**: Native build (Xcode/Gradle) yeniden gerekir.
- **iOS Info.plist + Android AndroidManifest izinleri**: Kamera, location, push için manuel native config.
- **Android `usesCleartextTraffic`**: Dev için aktif, prod'da kapat.
- **`SafeAreaView` unutmak**: iPhone X+ notch / Android status bar üstüne içerik bindirir.
- **`KeyboardAvoidingView`**: Form'larda klavye input'u örter; bu component'i sar.
- **`AsyncStorage` token saklamak**: Şifrelenmemiş; Keychain/Keystore kullan.
- **Web fetch CORS davranışı yok**: Native'de CORS yok, daha rahat ama backend de farklı davranabilir.
- **`react-native-` lib'leri pod install / autolinking**: iOS için `cd ios && pod install` her yeni lib'de.
- **Hermes mi V8 mi**: Hermes default (RN 0.70+); küçük bundle, hızlı start.
- **Bridge mesaj sayısını minimize et**: Çok sık `setState` veya çok büyük prop → JS thread tıkanması. `useMemo`, `Reanimated`.

## 8. Diğer konularla ilişkisi

- [Shared Business Logic](./shared-business-logic) — web ile kod paylaşımı
- [Push Notifications](./push-notifications) — FCM + APNs
- [App Store + Google Play Distribution](./app-store-google-play-distribution) — yayın
- [React CSR Temelleri (web)](../frontend-architecture/react-csr-foundation) — web mimari karşılaştırma
- [Token Storage](../frontend-architecture/frontend-token-storage) — mobile Keychain farkı

## 9. Daha derine

- React Native: https://reactnative.dev/
- React Navigation: https://reactnavigation.org/
- New Architecture: https://reactnative.dev/architecture/landing-page
- Expo (bare workflow): https://docs.expo.dev/bare/overview/
- Search keywords:
  - `react native new architecture fabric turbo modules`
  - `react native expo vs bare workflow`
  - `react navigation native stack typescript`
  - `react native monorepo pnpm workspace`
  - `react native ios android platform specific`

## 10. Sözlük

- **React Native (RN)** — React mantığıyla native iOS/Android app yazma framework'ü.
- **Bare workflow** — Native iOS + Android proje dosyalarına tam erişimle çalışan RN modu.
- **Expo managed** — Expo SDK + EAS Build ile native dosyaları görmeden çalışma modu.
- **Metro** — RN'nin JS bundler'ı.
- **Hermes** — RN'nin optimized JS engine'i.
- **JSI (JavaScript Interface)** — RN New Architecture'da JS-native arası senkron köprü.
- **Fabric** — RN'nin yeni renderer'ı (concurrent rendering uyumlu).
- **TurboModules** — RN'nin yeni native module sistemi.
- **React Navigation** — RN için navigation library (stack, tab, drawer).
- **AsyncStorage** — Basit key-value persistent storage.
- **MMKV** — Tencent'in hızlı key-value storage'ı.
- **Keychain / Keystore** — iOS / Android'in güvenli secret saklama servisleri.
- **APNs** — Apple Push Notification service.
- **FCM** — Firebase Cloud Messaging (Android push).
- **EAS** — Expo Application Services (build, submit, OTA update bulut servisi).
