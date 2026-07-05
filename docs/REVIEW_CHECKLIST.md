# Lumix — Kod İnceleme Kontrol Listesi (AI + İnsan)

> Bu dosya hem **yapay zekâya verilecek prompt** hem de **insan reviewer'ın
> kontrol listesidir**. AI-agnostiktir: Claude Code kullananlar `/code-review`
> ile, başka bir AI kullananlar bu metni `scripts/review.sh` çıktısıyla birlikte
> kopyalayıp kullanabilir.

## Yapay zekâya talimat (kopyala-yapıştır)

Aşağıdaki kod değişikliklerini (diff) **kıdemli bir yazılım mühendisi** gibi
incele. Amacın hata bulmak değil, **kusursuz, sürdürülebilir kod** sağlamak.
Her bulguyu `dosya:satır` referansıyla, **önem derecesiyle** (kritik / önemli /
küçük / öneri) ve **somut düzeltme önerisiyle** raporla. Hiç sorun yoksa "temiz"
de. Türkçe yanıtla.

Şu eksenlerde değerlendir:

### 1. Task uyumu
- Değişiklik, ilgili ClickUp task'ının **kapsamı içinde mi**?
- Kapsam dışı, alakasız ("bu arada şunu da değiştirdim") değişiklik var mı?
- Task'ın gerektirdiği bir şey **eksik kalmış** mı?

### 2. Gereksiz / ölü kod ve yorumlar
- Açıklama katmayan, bariz olanı tekrarlayan **gereksiz yorum** var mı?
- **Yorum satırına alınmış ölü kod** bırakılmış mı?
- Hata ayıklama çıktıları (`println`, `console.log`, `dbg!`, `print`) kalmış mı?
- Task'a bağlanmamış başıboş `TODO`/`FIXME` var mı?

### 3. SOLID
- **S** — Her sınıf/fonksiyon tek bir sorumluluğa mı sahip?
- **O** — Mevcut kodu değiştirmeden genişletilebilir mi (gereksiz `if/else`/`switch` zincirleri yerine)?
- **L** — Alt tipler üst tipin sözleşmesini bozuyor mu?
- **I** — Arayüzler şişkin mi; kullanılmayan metotları zorluyor mu?
- **D** — Somut sınıflara değil soyutlamalara mı bağımlı (DI kullanılıyor mu)?

### 4. DRY
- **Tekrar eden mantık/kod blokları** var mı? Ortak bir yere çıkarılabilir mi?
- Aynı sabit/string birden çok yere kopyalanmış mı?

### 5. KISS & Optimizasyon
- Daha basit bir çözüm varken **gereksiz karmaşıklık** var mı?
- Erken/gereksiz soyutlama (over-engineering) var mı?
- Belirgin **performans sorunu** var mı? (N+1 sorgu, döngü içinde I/O, gereksiz
  kopyalama, gereksiz allokasyon, eksik index)

### 6. Proje standartları & güvenlik
- İsimlendirme, katman (hexagonal) ve dosya yapısı projeyle tutarlı mı?
- **Sır/anahtar** (token, parola, key) kod veya config'e gömülmüş mü?
- Girdi doğrulama / hata yönetimi yeterli mi? Tenant izolasyonu korunuyor mu?
- Yeni davranış için **test** eklenmiş mi?

---

## İnsan reviewer için hızlı liste
- [ ] Build yeşil, testler geçti
- [ ] Task ile uyumlu, kapsam dışı değişiklik yok
- [ ] Gereksiz yorum / ölü kod / debug çıktısı yok
- [ ] SOLID, DRY, KISS'e uygun
- [ ] Optimize ve standartlara uygun
- [ ] Sır sızması yok
- [ ] PR küçük ve gözden geçirilebilir
