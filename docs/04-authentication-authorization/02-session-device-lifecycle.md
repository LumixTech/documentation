---
title: Session & Device Lifecycle (Redis Key Tasarımı)
description: USER_SESSIONS, ACCESS_TOKENS, REFRESH_TOKENS Redis key tasarımı, idle vs absolute timeout, token rotation, replay detection, logout-all, device fingerprinting.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Bir kullanıcının login olmasından "tüm cihazlardan çıkış" anına kadar **Redis'te neler yaşanır?** Hangi key hangi TTL ile, hangi pattern'le tutulur? Idle timeout ile absolute timeout farkı nedir? Refresh token nasıl rotate edilir ve replay olduğunda ne olur? Device fingerprinting ne kadar ileri gider? Bu sayfa [Fully Stateful Token Modeli](./stateful-token-model) sayfasının devamı niteliğinde, **implementation detayına iner**.

## 1. Bu nedir? (Sıfırdan)

Bir **session** = bir kullanıcının belirli bir cihazda açtığı oturum. Aynı kullanıcı laptop + telefon + tablet ile login olduysa **3 ayrı session** vardır.

Her session'a bağlı olarak yaşayan iki "token" vardır:
- **Access token** — kısa ömürlü (15dk), her API isteğinde gider gelir
- **Refresh token** — uzun ömürlü (7-30gün), access token bitince yenisini almak için

Bunların **hepsi Redis'te** yaşar. Lumix bu state'i nasıl modeller, hangi key isimleriyle tutar — bu sayfa o.

### Günlük analoji

Otel zincirini düşün:
- **Session** = bir misafirin (user) belirli bir otelde tuttuğu oda kaydı
- **Access token** = kapı kartı (15dk geçerli, sürekli yenilenir resepsiyondan)
- **Refresh token** = kimliğine bağlı uzun süreli kayıt (kart kaybolursa yenisini almak için lobbye iner)
- **Idle timeout** = "30 dakika hareketsiz kalırsan otomatik logout"
- **Absolute timeout** = "30 gün sonra her halükarda logout"
- **Logout-all** = "tüm otel zinciri kayıtlarımı kapat"

## 2. Hangi problemi çözüyor?

### 2.1. Session karmaşası
Kullanıcı 5 cihazda açık. Birinden kötü amaçlı erişim olduğunu fark etti. **Sadece o cihazı** kapatmak istiyor; diğer 4'ünden devam edecek. Bunu yapmak için her cihaza ayrı **session id** vermek gerekir.

### 2.2. "Hangi cihazlardan girilmiş?" listesi
Modern uygulamalar (Google, GitHub, banka app'leri) "şu cihazlarda aktif oturumun var" diye gösterir. Bunu yapabilmek için her session'ın **cihaz, IP, son aktivite** bilgisini saklaman gerekir.

### 2.3. Idle timeout ihtiyacı
Bir kullanıcı sekmeyi açık bırakıp 8 saat gitti. Sonra geri döndü ve hassas işlem yaptı. **Otomatik logout** olmalıydı. Sadece access token TTL'i yeterli değil — refresh ile hep canlı kalır. Server-side bir "idle timer" gerekir.

### 2.4. Absolute timeout
"En fazla 30 gün, ne olursa olsun." Bunu refresh rotation ile sağlayamazsın; çünkü kullanıcı her refresh'te yeni 7 günlük token alır. Onun üzerinde **sabit bir kesinti tarihi** lazım.

### 2.5. Refresh token replay detection
Saldırgan refresh token'ı çaldı, kullandı. Kullanıcı bir sonraki refresh'inde "bu token yok ki" hatası alır. Lumix'in burada yapması gereken: **tüm "ailenin"** token'larını invalidate etmek. Çünkü "victim de saldırgan da aynı kökten türemiş" demektir.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Redis key matrix

```
┌────────────────────────────────────────────────────────────────────────┐
│              LUMIX AUTH REDIS — KEY MATRIX                             │
│              (cluster: auth-redis-sentinel, AOF + noeviction)          │
└────────────────────────────────────────────────────────────────────────┘

Key pattern                     │ Tip   │ TTL          │ İçerik
─────────────────────────────── │ ────  │ ──────────── │ ──────────────────
access:{jti}                    │ Hash  │ 15 dk        │ status, sid, uid,
                                │       │ (=access exp)│ tenant_id, exp
                                │       │              │
refresh:{sha512}                │ Hash  │ 7 gün        │ sid, uid, family_id,
                                │       │ (=refresh    │ created_at, parent_jti
                                │       │   exp)       │
                                │       │              │
refresh:family:{family_id}      │ Set   │ 7 gün        │ Hash listesi (rotation
                                │       │              │ ailesinin tüm
                                │       │              │ üyeleri)
                                │       │              │
session:{sid}                   │ Hash  │ Sliding +    │ uid, status, ip,
                                │       │ absolute     │ ua, fingerprint,
                                │       │ (idle: 30dk, │ created_at,
                                │       │ absolute:    │ last_seen_at,
                                │       │ 30gün)       │ absolute_exp
                                │       │              │
user:sessions:{uid}             │ Set   │ — (manuel    │ {sid1, sid2, ...}
                                │       │ yönetim)     │ logout-all için
                                │       │              │
user:tokens:{uid}               │ Set   │ — (manuel)   │ {jti1, jti2, ...}
                                │       │              │ permission revoke için
                                │       │              │
device:fingerprint:{fp_hash}    │ Hash  │ 90 gün       │ İlk görülen, son
                                │       │              │ görülen, uid'ler
                                │       │              │ (anomaly detection)
                                │       │              │
auth:lock:{uid}                 │ String│ Sliding 15dk │ Failed attempt
                                │       │              │ counter (brute-force)
```

### 3.2. Idle vs Absolute timeout

```
                    │← idle reset her API call'da →│
                    │                              │
        ┌───────────┴──────────────────────────────┴───────────┐
        │                                                      │
        │   session.created_at                                 │
        │     │                                                │
        │     ▼                                                │
        │   ┌──────────────────────────────────────────────┐  │
        │   │     SESSION (max 30 gün — absolute)          │  │
        │   │                                              │  │
        │   │   API call → last_seen_at = now              │  │
        │   │              session TTL = max(              │  │
        │   │                  idle (30dk),                │  │
        │   │                  absolute_exp - now          │  │
        │   │              )                               │  │
        │   │                                              │  │
        │   │   Hareket yoksa → 30dk sonra Redis silinir  │  │
        │   │   30 gün dolarsa → mecburen logout           │  │
        │   └──────────────────────────────────────────────┘  │
        │                                                      │
        └──────────────────────────────────────────────────────┘
```

Mantık:
- Her API çağrısında `session:{sid}`'in TTL'i 30 dakikaya **yeniden set edilir** (idle reset).
- Ama session yaratıldığında `absolute_exp = created_at + 30gün` field'ı yazılır.
- Filter'da kontrol: `if (now > absolute_exp) → revoke`. Bu **idle reset'le aşılamaz**.
- Pratikte session TTL'i Redis'te **min(30dk-idle, kalan-absolute)** kadar olur.

### 3.3. Refresh rotation + replay detection

```
Login                                  Refresh #1                       Refresh #2
  │                                         │                                │
  ▼                                         ▼                                ▼
RT1 ────┬──────────────────────────►  RT1 DEL                                │
        │                              RT2 yarat                             │
        │                              family.add(RT2_hash)                  ▼
        │                                                              RT2 DEL
        │                                                              RT3 yarat
        │                                                              family.add(RT3_hash)
        │
        │  family_id = X (sabit)
        │  family üyeleri: {RT1_hash} → {RT2_hash} → {RT3_hash}
        │
        └─►  Eğer eski RT1 ikinci kez gelirse:
             GET refresh:{RT1_hash} → boş (silinmiş) AMA
             family_id'ye bakan ek kontrol: "bu hash family'de mi geçmişti?"

             Lumix yaklaşımı: family kayıt tut, replay tespit edince:
             - Tüm family üyelerini DEL
             - session:{sid} → REVOKED
             - user:sessions:{uid}'ten çıkar
             - SecurityAlert.publish (Kafka audit topic)
```

Yani family-tracking, "rotation chain'i sürekliliği"ni kontrol eder. Detay implementation #6'da.

### 3.4. Logout-all akışı

```
POST /auth/sessions DELETE (logout-all)
            │
            ▼
1. uid = JWT.sub
2. SMEMBERS user:sessions:{uid}
   → [sid1, sid2, sid3]
3. Her sid için:
   a. session:{sid} → status: REVOKED, DEL
   b. session'a ait refresh:* → SCAN by sid → DEL
4. SMEMBERS user:tokens:{uid}
   → [jti1, jti2, ...]
5. Her jti için access:{jti} → DEL
6. DEL user:sessions:{uid}, user:tokens:{uid}
7. Kafka publish: UserSessionsRevoked (audit)
```

### 3.5. Device fingerprinting

Lumix **soft fingerprinting** uygular (privacy-balanced):

| Sinyal | Kaynak | Kullanım |
|---|---|---|
| User-Agent | HTTP header | Tarayıcı + OS dedüksiyonu |
| Accept-Language | HTTP header | Şüpheli dil değişimi |
| IP + ASN | TCP + GeoIP | Coğrafi anomali |
| Client-side hash | `X-Device-Fingerprint` (frontend hesaplar: canvas + audio + timezone) | Cihaz devamlılığı |
| Screen size | Frontend gönderir | Cihaz sınıfı |

Fingerprint **SHA-256 hash'lenip** saklanır; raw veri DB'ye yazılmaz. Anomaly detection: yeni fingerprint + farklı country + farklı ASN → "yeni cihazdan giriş" notification.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Default değerler

| Parametre | Lumix değeri | Sebep |
|---|---|---|
| Access token TTL | 15 dakika | Çalınırsa kısa süre içinde işe yaramaz |
| Refresh token TTL | 7 gün (web), 30 gün (mobile) | Web sık logout, mobile uzun süreli |
| Idle timeout | 30 dakika | Bankacılık standardı; eğitim/yönetim için yeterli |
| Absolute timeout | 30 gün | KVKK + "kullanıcıyı tekrar doğrula" gereği |
| Concurrent session limit | 5 cihaz | Aşılırsa en eskisi otomatik çıkış |
| Failed login attempt | 5 / 15dk | 5. denemede 15dk kilit |

### 4.2. Servis yerleşimi

- **identity-service** Redis key'lerini yazma/silme yetkisine sahip TEK servistir.
- Diğer microservice'ler sadece **read-only** olarak `access:*` ve `session:*` key'lerini okur.
- Bu izolasyon Redis ACL ile zorlanır (`identity-service` user'ı write, diğer servisler `+@read +@connection`).

### 4.3. TTL refresh stratejisi

Session sliding TTL için her API çağrısında **EXPIRE komutu** çalışır. Bunu hot path'te yapmak iki taktikle pahalı olmaz:

1. **Lua script ile atomic:** GET status + EXPIRE tek round-trip.
2. **Throttle:** Her istekte değil, son `EXPIRE`'dan 60 saniyeden fazla geçmişse yenile (jitter ile thundering-herd engelleme).

### 4.4. Family tracking implementation

Refresh token'ın `family_id` ile gruplanması:
- Login'de family_id = UUID v7 üretilir.
- Her rotation'da yeni hash bu set'e eklenir.
- Replay olduğunda set'in tüm üyeleri tek seferde DEL.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **Sliding-only (idle TTL, absolute yok)** | Saldırgan token'ı sürekli kullanırsa süresiz açık kalır. **Elendi.** |
| **Absolute-only (idle yok)** | "Sekme açık unuttum" senaryosu güvensiz. **Elendi.** |
| **Sliding + Absolute (Lumix)** | ✓ İki garantiyi de sağlar. |
| **DB-backed session (PostgreSQL)** | Her istek 1-2ms DB sorgusu. Redis'e göre 5x yavaş, gereksiz DB yükü. **Elendi.** |
| **Refresh rotation YOK** | Çalınan token sonsuza kadar geçerli; replay detection imkansız. **Elendi.** |
| **Refresh rotation + family tracking (Lumix)** | ✓ Hem rotation hem cascade revoke. |
| **Hard device fingerprinting (canvas/WebGL hash)** | Privacy ihlal sinyali; bazı yargı bölgelerinde sorunlu. **Elendi.** |
| **Soft fingerprinting (UA + IP + client hash)** | ✓ Anomaly tespiti için yeterli, abuse zor. |

### Kabul ettiğimiz trade-off'lar

- Sliding TTL refresh işlemi Redis'e ekstra `EXPIRE` çağrısı ekler → throttling ile maliyet düşürüldü.
- Family tracking storage'ı +20-30 byte/session; ihmal edilebilir.

## 6. Pratik örnek

### 6.1. SessionStore (Spring + Lettuce)

```java
@Component
@RequiredArgsConstructor
public class SessionStore {

    private final StringRedisTemplate redis;
    private final Clock clock;

    private static final Duration IDLE_TTL = Duration.ofMinutes(30);
    private static final Duration ABSOLUTE_TTL = Duration.ofDays(30);

    public void create(SessionRecord rec) {
        String key = "session:" + rec.sid();
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("uid", rec.userId().toString());
        hash.put("status", "ACTIVE");
        hash.put("ip", rec.device().ip());
        hash.put("ua", rec.device().userAgent());
        hash.put("fingerprint", rec.device().fingerprint());
        hash.put("created_at", rec.createdAt().toString());
        hash.put("last_seen_at", rec.createdAt().toString());
        hash.put("absolute_exp", rec.absoluteExpiresAt().toString());

        // Atomic: HSET + EXPIRE + SADD user:sessions
        redis.executePipelined((RedisCallback<Object>) conn -> {
            conn.hashCommands().hMSet(key.getBytes(), toBytes(hash));
            conn.keyCommands().expire(key.getBytes(), IDLE_TTL.toSeconds());
            conn.setCommands().sAdd(("user:sessions:" + rec.userId()).getBytes(),
                                     rec.sid().toString().getBytes());
            return null;
        });
    }

    /**
     * Lua script: status okuma + idle TTL reset + absolute kontrol — tek round-trip.
     */
    private static final DefaultRedisScript<String> TOUCH_SCRIPT = new DefaultRedisScript<>("""
        local status = redis.call('HGET', KEYS[1], 'status')
        if status == false then return 'NOT_FOUND' end
        if status ~= 'ACTIVE' then return status end

        local absExp = redis.call('HGET', KEYS[1], 'absolute_exp')
        if absExp and absExp < ARGV[1] then
          redis.call('HSET', KEYS[1], 'status', 'EXPIRED')
          return 'ABSOLUTE_EXPIRED'
        end

        redis.call('HSET', KEYS[1], 'last_seen_at', ARGV[1])
        redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
        return 'ACTIVE'
        """, String.class);

    public SessionStatus touchAndGetStatus(UUID sid) {
        String result = redis.execute(TOUCH_SCRIPT,
            List.of("session:" + sid),
            Instant.now(clock).toString(),
            String.valueOf(IDLE_TTL.toSeconds()));
        return SessionStatus.valueOf(result);
    }

    public void revoke(UUID sid) {
        redis.opsForHash().put("session:" + sid, "status", "REVOKED");
        redis.expire("session:" + sid, Duration.ofMinutes(5));  // grace for audit
    }

    public Set<String> listSessions(UUID uid) {
        return redis.opsForSet().members("user:sessions:" + uid);
    }

    public void logoutAll(UUID uid) {
        Set<String> sids = listSessions(uid);
        for (String sid : sids) {
            revoke(UUID.fromString(sid));
        }
        redis.delete("user:sessions:" + uid);
    }
}
```

### 6.2. RefreshTokenService (rotation + family)

```java
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final StringRedisTemplate redis;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenPair rotate(String oldOpaque, UUID sid, UUID uid) {
        String oldHash = sha512(oldOpaque);
        String oldKey = "refresh:" + oldHash;

        // Atomicity: Lua script
        String familyId = (String) redis.execute(new DefaultRedisScript<>("""
            local data = redis.call('HGETALL', KEYS[1])
            if #data == 0 then return nil end
            local familyId = nil
            for i=1,#data,2 do
              if data[i] == 'family_id' then familyId = data[i+1] end
            end
            redis.call('DEL', KEYS[1])
            return familyId
            """, String.class), List.of(oldKey));

        if (familyId == null) {
            // REPLAY!! veya hiç olmamış
            handleReplay(oldHash, sid, uid);
            throw new TokenReplayException();
        }

        // Yeni refresh + access
        String newOpaque = generateOpaque();
        String newHash = sha512(newOpaque);
        UUID newJti = UuidV7.generate();
        Instant now = Instant.now();
        Instant refreshExp = now.plus(Duration.ofDays(7));

        // Yaz
        Map<String, String> newRefresh = Map.of(
            "sid", sid.toString(),
            "uid", uid.toString(),
            "family_id", familyId,
            "created_at", now.toString());
        redis.opsForHash().putAll("refresh:" + newHash, newRefresh);
        redis.expireAt("refresh:" + newHash, refreshExp);

        // Family'e ekle
        redis.opsForSet().add("refresh:family:" + familyId, newHash);
        redis.expireAt("refresh:family:" + familyId, refreshExp);

        // Yeni access token (jwtSigner servisten)
        String accessJwt = signAccess(uid, sid, newJti);

        return new TokenPair(accessJwt, newOpaque, 900);
    }

    private void handleReplay(String oldHash, UUID sid, UUID uid) {
        // Hash hangi family'e aitti? Reverse index (opsiyonel) yoksa
        // direkt session'ı kapat:
        sessionStore.revoke(sid);
        // Audit:
        auditPublisher.publish(SecurityEvent.refreshTokenReplay(uid, sid));
    }
}
```

### 6.3. Concurrent session limit

```java
public void enforceSessionLimit(UUID uid, int maxSessions) {
    Set<String> sids = redis.opsForSet().members("user:sessions:" + uid);
    if (sids.size() < maxSessions) return;

    // En eski session'ı bul
    Optional<SessionRecord> oldest = sids.stream()
        .map(sid -> readSession(UUID.fromString(sid)))
        .min(Comparator.comparing(SessionRecord::createdAt));

    oldest.ifPresent(s -> sessionStore.revoke(s.sid()));
}
```

### 6.4. application.yml

```yaml
lumix:
  session:
    idle-timeout: PT30M
    absolute-timeout: P30D
    max-concurrent: 5
    sliding-throttle: PT1M    # son EXPIRE'dan 1dk geçmediyse skip
  refresh:
    ttl-web: P7D
    ttl-mobile: P30D
    rotation: true
    family-tracking: true
  device:
    fingerprint-header: X-Device-Fingerprint
    fingerprint-required: true
  brute-force:
    max-attempts: 5
    window: PT15M
    lock-duration: PT15M
```

## 7. Dikkat edilecek tuzaklar

- **Idle timeout'u uzun yapmak.** 30dk endüstri standardı; 8 saat = "lockscreen yok" demek. Bankacılık değil ama Lumix finans modülü için 30dk uygundur.
- **Absolute timeout'u unutmak.** Sadece idle bırakırsan saldırgan elindeki token'ı düzenli "ping"leyerek sonsuza kadar açık tutabilir.
- **Session sliding her istekte EXPIRE — Redis CPU spike.** 10k aktif kullanıcı × 50 req/dk = 500k EXPIRE/dk. **Çözüm:** throttle (1dk pencere içinde son yazma varsa skip).
- **Refresh hash'ini düz tutmak.** Lumix SHA-512 zorunlu. SHA-256 kabul edilebilir ama 512 tercih ediliyor (saldırı surface azaltma).
- **Family tracking yok.** Replay detection olmaz. Çalınan refresh ile yeni refresh türetilir, victim yeni gelen refresh'i kaybeder ama saldırgan kullanır.
- **`user:sessions:{uid}` set'ini temizlememek.** Süresi dolan session id'ler set'te birikir. **Çözüm:** session DEL'de SREM, ek olarak haftalık housekeeping job.
- **Logout'ta sadece access token revoke.** Refresh token'ı silmezsen, frontend hemen `/auth/refresh` ile yeni access alır. **Kural:** logout = access + refresh + session, üçü birden.
- **Concurrent session limit'i client'a bildirmemek.** "Başka cihazınızdan çıkış yapıldı" notification yoksa kullanıcı şaşırır.
- **Device fingerprint'i raw saklamak.** Privacy ihlali. **Kural:** SHA-256 hash'le.
- **Brute-force lock'unu IP başına yapmak.** NAT arkasındaki tüm kullanıcılar etkilenir. **Kural:** (uid, IP) tuple veya progressive delay.

## 8. Diğer konularla ilişkisi

- [Fully Stateful Token Modeli](./stateful-token-model) — bu sayfanın üst seviye karşılığı
- [Permission Change & Revoke Flow](./permission-change-revoke-flow) — `user:tokens:{uid}` set'i nasıl kullanılır
- [httpOnly Cookie Storage](./httponly-cookie-storage) — refresh token nereye saklanır
- [Redis Sentinel Topology](../08-caching-redis/redis-sentinel-topology) — auth-redis cluster (AOF + noeviction)
- [Distributed Lock — Redisson](../08-caching-redis/distributed-lock-redisson) — concurrent session limit için lock pattern

## 9. Daha derine inmek için

- OWASP: [Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
- IETF: [OAuth 2.0 Security Best Current Practice (RFC 9700)](https://datatracker.ietf.org/doc/rfc9700/) — refresh token rotation önerileri
- Auth0 blog: "Refresh Token Rotation"
- Cloudflare blog: "What is device fingerprinting?"
- Search keywords:
  - `refresh token rotation reuse detection`
  - `sliding session vs absolute timeout`
  - `redis session store pattern`
  - `concurrent session limit implementation`
  - `device fingerprinting privacy tradeoffs`

## 10. Sözlük

- **Session** — Kullanıcının belirli bir cihaz/client üzerinde açtığı oturumu temsil eden, server-side kayıt.
- **sid** — Session id; `session:{sid}` Redis key'inin parçası.
- **Idle timeout** — Hareket olmazsa session'ın kapanma süresi; her istekte sıfırlanır.
- **Absolute timeout** — Idle reset'le aşılamayan, session yaratıldığında sabitlenen kesinti tarihi.
- **Token rotation** — Her refresh isteğinde eski refresh token'ı geçersiz kılıp yeni üretme.
- **Family / Family ID** — Aynı login'den türeyen tüm rotation halkasını birlikte takip etmeye yarayan grup id'si.
- **Replay** — Daha önce kullanılmış (geçersiz) refresh token'ın tekrar gelmesi durumu; saldırı varsayılır.
- **Logout-all** — Bir kullanıcının tüm aktif session'larını eş zamanlı sonlandıran işlem.
- **Device fingerprint** — Cihazı (kişiyi değil) tanımakta kullanılan, frontend-üretimli, hash'lenmiş sinyaller bütünü.
- **Brute-force lock** — Failed login attempt eşiği aşıldığında kullanıcı/IP'yi geçici kilitleyen mekanizma.

