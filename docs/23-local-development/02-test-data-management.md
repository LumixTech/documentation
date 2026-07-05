---
title: Test Data Management
description: Seed data stratejisi — Java Faker + Flyway repeatable migrations + idempotent script. Anonymized prod copy KESİNLİKLE değil (KVKK). Synthetic data generator.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Lumix'in local dev, integration test, demo, UAT ortamları için **anlamlı, tutarlı, gerçekçi** test verisi gerekir. **Prod kopyası asla kabul edilmez** (KVKK + öğrenci verisi özel kategori). Bu sayfa Lumix'in **synthetic data generation** stratejisini sıfırdan anlatır: **Java Faker + Flyway repeatable migrations + idempotent script + Temporal scheduled data refresh**. **Domain-aware** test verisi (öğretmenler-sınıflar-öğrenciler-veliler tutarlı), **boyut tier'ları (XS/S/M/L)** ve **GDPR-compliant generation** yaklaşımını gösterir. Hedef kitle: backend geliştirici, QA mühendisi, demo veren satış mühendisi.

## 1. Bu nedir? (Sıfırdan)

Test verisi üç kategoriye ayrılır:

| Tip | Açıklama | Lumix'te kullanım |
|---|---|---|
| **Synthetic** | Sıfırdan üretilmiş; gerçek kişiye bağlı değil | ✅ Default |
| **Anonymized prod copy** | Prod verisinden PII çıkarılmış kopya | ❌ KVKK + risk |
| **Manuel curated** | Geliştiricinin elle yazdığı küçük örnekler | ✅ Smoke test, e2e |

Synthetic data üretmek için iki ana araç:
- **Java Faker** (com.github.javafaker:javafaker) → realistic name, address, email, date.
- **Locale-aware**: Türkçe isim, Türkiye il/ilçe, +90 telefon, T.C. kimlik validate edilmemiş.

### Günlük hayattan analoji

Otomobil çarpışma testi: gerçek insan kullanmazsın (dummy). Dummy gerçek insana benzer hareket eder, ölçümler güvenilir, ama ahlaki/legal yük yok. Synthetic test data = dummy: gerçek davranışı simüle eder, ama gerçek kişi değil.

## 2. Hangi problemi çözüyor?

| Acı | Synthetic yok | Synthetic var |
|---|---|---|
| Local'de boş DB | UI'da hiçbir şey yok | Realistic veri |
| Tutarlı integration test | Her test farklı veri | Deterministic seed |
| Demo | Mock screenshot | Canlı gerçek-gibi |
| Production'a benzer scale | "Bir-iki kayıt" → bug yakalanmaz | 100K kayıt ile load test |
| PII güvenliği | Prod kopyası sızdığında felaket | Synthetic = sızsa risk yok |
| Yeni feature için data shape değişimi | DB sıfırla + tekrar gir | Seed script regenerate |

### Patlamış geliştirici hikayesi

Bir takım staging'i prod-anonymized snapshot ile dolduruyordu. Bir geliştirici kazara staging URL'i public Slack'e attı → erişen kişi "anonymized" verinin pseudonymized olduğunu fark etti → bazı kayıtların geri çözülmesi mümkün. Synthetic data olsaydı: sızma anlamsız.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Üç katmanlı seed mimarisi

```
Layer 1: Schema (Flyway versioned migrations)
   V1__init.sql, V2__add_*.sql ...

Layer 2: Reference data (Flyway repeatable migrations)
   R__roles.sql, R__permissions.sql, R__cities.sql
   (idempotent INSERT ... ON CONFLICT DO NOTHING)

Layer 3: Synthetic data (programmatic, Java)
   - Java Faker
   - Domain-aware generator
   - Idempotent: aynı seed = aynı veri
```

### 3.2. Reference vs synthetic ayrımı

- **Reference data**: domain'in olmazsa olmaz lookup'ları. Ör: roller (öğretmen, veli, yönetici), izinler, şehirler. **Flyway repeatable** ile her migration'da tekrarlanır.
- **Synthetic data**: değişken örnek veri. Ör: 200 öğrenci, 50 öğretmen, 12 sınıf. **Java seed script** ile.

### 3.3. Idempotency

Her seed script "n kere çalıştırırsam aynı sonucu" verir:
- INSERT öncesi count kontrolü
- `ON CONFLICT` clause
- `random.seed(42)` ile deterministic faker

```java
Faker faker = new Faker(new Locale("tr-TR"), new Random(42));
```

### 3.4. Domain-aware generator

Tutarlı veri üretimi için **graph-aware**:
- 50 öğretmen oluşur.
- 12 sınıf oluşur; her sınıfa 1 sınıf öğretmeni atanır (öğretmenlerden seç).
- 300 öğrenci; her birine sınıf atanır.
- Her öğrenciye 1-2 veli; iletişim kuruyor.
- Her sınıf için ders programı; öğretmenler dersler arasında dağılır.

Naif yaklaşım: rastgele tüm tablo → tutarsız. Domain-aware: ilişkileri korur.

### 3.5. Boyut tier'ları

| Tier | Tenant | Öğretmen | Öğrenci | Veli | Sınıf | Toplam kayıt |
|---|---|---|---|---|---|---|
| **XS** (smoke test) | 1 | 5 | 30 | 50 | 3 | ~200 |
| **S** (default local) | 1 | 50 | 300 | 500 | 12 | ~2K |
| **M** (UAT) | 3 | 200 | 2K | 3K | 80 | ~10K |
| **L** (load test) | 5 | 1K | 50K | 70K | 800 | ~500K |

Tier'lar config ile:
```bash
SEED_TIER=L ./gradlew :tools:seed:run
```

### 3.6. Refresh stratejisi

- Local: her `tilt up` → seed.
- CI integration test: Testcontainers → seed → test → drop.
- Staging: günlük cron (eski veriyi sil, yeniden seed).
- Demo: önceden hazırlanmış "demo tenant"; manuel refresh.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Tooling stack

- **Java 25 + Spring Boot**: seed servisi gibi davranır (sıkı SDK ile microservice'lere benzer).
- **com.github.javafaker:javafaker:1.0.2** (Türkçe locale + custom locale extension).
- **Spring Data JPA** (entity'leri prod ile aynı).
- **PostgreSQL JDBC**.
- **Flyway repeatable migrations** (R__).

### 4.2. Repository

```
tools/seed/
├── src/main/java/com/lumix/seed/
│   ├── SeedApplication.java
│   ├── generators/
│   │   ├── TenantGenerator.java
│   │   ├── UserGenerator.java
│   │   ├── TeacherGenerator.java
│   │   ├── StudentGenerator.java
│   │   ├── ParentGenerator.java
│   │   ├── ClassGenerator.java
│   │   ├── EnrollmentGenerator.java
│   │   ├── AttendanceGenerator.java
│   │   └── ...
│   ├── config/
│   │   ├── SeedTier.java
│   │   └── SeedConfig.java
│   └── faker/
│       ├── TurkishLocale.java
│       └── EducationFaker.java
├── src/main/resources/
│   └── application.yml
└── build.gradle.kts
```

### 4.3. Faker locale customization

```java
public class TurkishLocale {

    public static Faker turkish(long seed) {
        // Java Faker'ın resmi tr_TR locale yetersiz; custom override
        var faker = new Faker(new Locale("tr"), new Random(seed));
        return faker;
    }

    private static final List<String> FIRST_NAMES_MALE = List.of(
        "Ahmet", "Mehmet", "Mustafa", "Ali", "Hüseyin", "Hasan", "İbrahim",
        "Murat", "Emre", "Yusuf", "Ömer", "Berk", "Can", "Burak", /* ... */);
    private static final List<String> FIRST_NAMES_FEMALE = List.of(
        "Ayşe", "Fatma", "Emine", "Hatice", "Zeynep", "Elif", "Selin",
        "Esra", "Ebru", "Cansu", "Buse", "Pınar", /* ... */);
    private static final List<String> LAST_NAMES = List.of(
        "Yılmaz", "Kaya", "Demir", "Çelik", "Şahin", "Yıldız", "Yıldırım",
        "Öztürk", "Aydın", "Özdemir", /* ... */);

    public static String firstName(Faker f, Gender g) {
        var list = g == Gender.MALE ? FIRST_NAMES_MALE : FIRST_NAMES_FEMALE;
        return list.get(f.random().nextInt(list.size()));
    }

    public static String lastName(Faker f) {
        return LAST_NAMES.get(f.random().nextInt(LAST_NAMES.size()));
    }

    public static String email(Faker f, String firstName, String lastName) {
        return normalize(firstName) + "." + normalize(lastName) + f.random().nextInt(1000)
            + "@" + f.options().option("test-okul.k12.tr", "demo-okul.com.tr");
    }
}
```

### 4.4. Idempotent seed örneği

```java
@Service
public class StudentGenerator {

    @Autowired StudentRepository repo;
    @Autowired ClassRepository classRepo;
    @Autowired SeedTier tier;

    @Transactional
    public void seed(Tenant tenant, long seed) {
        var existing = repo.countByTenantId(tenant.getId());
        if (existing >= tier.studentCount()) {
            log.info("Already seeded: {} students for tenant {}", existing, tenant.getName());
            return;
        }

        var faker = TurkishLocale.turkish(seed);
        var classes = classRepo.findByTenantId(tenant.getId());

        for (int i = 0; i < tier.studentCount(); i++) {
            var gender = i % 2 == 0 ? Gender.MALE : Gender.FEMALE;
            var firstName = TurkishLocale.firstName(faker, gender);
            var lastName = TurkishLocale.lastName(faker);
            var clazz = classes.get(faker.random().nextInt(classes.size()));

            var student = Student.builder()
                .id(deterministicUuid(tenant.getId(), i))
                .tenantId(tenant.getId())
                .firstName(firstName)
                .lastName(lastName)
                .gender(gender)
                .birthDate(faker.date().birthday(6, 18).toInstant().atZone(ZoneOffset.UTC).toLocalDate())
                .email(TurkishLocale.email(faker, firstName, lastName))
                .nationalId(generateValidTcNo(faker))
                .classId(clazz.getId())
                .enrollmentDate(LocalDate.of(2024, 9, 1))
                .build();

            repo.save(student);
        }
    }

    private UUID deterministicUuid(UUID tenantId, int index) {
        return UUID.nameUUIDFromBytes(("student-" + tenantId + "-" + index).getBytes());
    }
}
```

`deterministicUuid` ile aynı seed = aynı UUID → idempotent.

### 4.5. T.C. kimlik üretimi (test için)

```java
public static String generateValidTcNo(Faker faker) {
    int[] digits = new int[11];
    digits[0] = 1 + faker.random().nextInt(9);
    for (int i = 1; i < 9; i++) digits[i] = faker.random().nextInt(10);

    int oddSum = digits[0] + digits[2] + digits[4] + digits[6] + digits[8];
    int evenSum = digits[1] + digits[3] + digits[5] + digits[7];
    digits[9] = ((oddSum * 7) - evenSum) % 10;
    int allSum = Arrays.stream(digits, 0, 10).sum();
    digits[10] = allSum % 10;

    var sb = new StringBuilder();
    for (int d : digits) sb.append(d);
    return sb.toString();
}
```

Bu üretilen "11 haneli, algoritmik geçerli" değer **gerçek kişiye karşılık gelmediği** için risk yok. Demo'da "test verisi" olarak işaretlenir.

### 4.6. Çalıştırma — local

```bash
# Tüm tier=S seed (default)
./gradlew :tools:seed:run

# Belirli boyut
SEED_TIER=L ./gradlew :tools:seed:run

# Belirli servis
SEED_TARGET=academic ./gradlew :tools:seed:run

# Belirli tenant
SEED_TENANT_ID=00000000-0000-0000-0000-000000000001 \
SEED_TIER=M ./gradlew :tools:seed:run
```

Tilt entegrasyonu:
```python
local_resource(
    'seed-data',
    cmd='SEED_TIER=S ./gradlew :tools:seed:run --no-daemon',
    deps=['./tools/seed/src/**/*'],
    resource_deps=[
        'postgres-identity', 'postgres-academic', 'postgres-finance',
        'identity-service', 'academic-service'
    ],
    labels=['ops']
)
```

### 4.7. CI integration test seed

Testcontainers ile:
```java
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcademicIntegrationTest {

    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
        .withDatabaseName("academic")
        .withUsername("test")
        .withPassword("test");

    @BeforeAll
    void seedData() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "test", "test")
            .locations("classpath:db/migration", "classpath:db/seed")
            .load()
            .migrate();
    }
}
```

`db/seed/R__sample_data.sql` repeatable migration ile küçük (XS) seed.

### 4.8. Staging günlük refresh

Temporal scheduled workflow:
```java
public class StagingSeedRefreshWorkflowImpl implements StagingSeedRefreshWorkflow {
    @Override
    public void execute() {
        seedActivities.truncateNonSystemTables(STAGING_TENANT_ID);
        seedActivities.runSeed(SeedTier.M, STAGING_TENANT_ID, fixedSeed());
        seedActivities.notifyTeam("Staging seed refreshed: tier=M");
    }
}
```

Schedule: `0 4 * * *` (her gece 04:00).

### 4.9. Demo tenant kataloğu

Sales/demo için pre-built tenant'lar:
- `demo-istanbul-koleji`: full data, 1 yıllık geçmiş, gerçekçi senaryolar.
- `demo-anadolu-lisesi`: minimal, hızlı navigation.
- `demo-anaokul`: özel use case (anaokul attendance + sağlık modülleri).

Demo cluster'ında pre-seeded; URL ile satış mühendisleri direkt giriş yapar.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Anonymized prod copy** | KVKK riski + pseudonymization güvensiz + öğrenci verisi özel kategori. Yasak. |
| **SQL dump + restore** | Statik; her schema değişiminde elle güncellenecek. |
| **Manuel fixture JSON** | Küçük setup için OK; büyük scale imkansız. |
| **Python Faker** | İyi ama Lumix Java ekosistemi içinde Java Faker entegrasyonu kolay. |
| **factory_bot / FactoryBoy** | Ruby/Python; Java ekosistem değil. |
| **Snaplet (declarative data gen)** | Genç; topluluk küçük. |
| **GitOps-managed test fixtures** | Aynı manuel fixture problemi. |

### Kabul ettiğimiz trade-off'lar

- **Synthetic gerçekçi değil**: edge case'leri yakalamayabilir. Lumix kuralı: chaos test + manual curated edge case fixtures.
- **Performance**: 500K kayıt seed 10-20 dakika sürer. Local'de tier=S yeterli; L sadece load test.
- **Bakım**: schema değişince seed güncelle. Test team responsibility.

### Tekrar değerlendirme tetikleyicileri

- "Production'da X bug var, local'de tekrar edemiyoruz" sık ise → daha karmaşık synthetic veya **chaos data injection**.
- Anonymized prod copy'ye **gerçekten ihtiyaç** doğarsa → güçlü pseudonymization framework (Vault Transit + k-anonymity); ama mevcut karar: ASLA.

## 6. Pratik örnek

### 6.1. SeedTier enum

```java
public enum SeedTier {
    XS(1,   5,  30,   50,   3,  100),
    S(1,    50, 300,  500,  12, 2000),
    M(3,    200, 2000, 3000, 80, 10000),
    L(5,    1000, 50000, 70000, 800, 500000);

    public final int tenantCount;
    public final int teacherCount;
    public final int studentCount;
    public final int parentCount;
    public final int classCount;
    public final int attendanceRecordCount;

    SeedTier(int t, int tc, int sc, int pc, int cc, int ac) {
        this.tenantCount = t; this.teacherCount = tc; this.studentCount = sc;
        this.parentCount = pc; this.classCount = cc; this.attendanceRecordCount = ac;
    }

    public static SeedTier fromEnv() {
        var v = System.getenv("SEED_TIER");
        return v == null ? S : SeedTier.valueOf(v);
    }
}
```

### 6.2. Class generator (sınıf + öğretmen ataması)

```java
@Service
public class ClassGenerator {

    @Autowired ClassRepository classRepo;
    @Autowired TeacherRepository teacherRepo;
    @Autowired SeedTier tier;

    @Transactional
    public void seed(Tenant tenant, long seed) {
        if (classRepo.countByTenantId(tenant.getId()) >= tier.classCount) return;

        var faker = TurkishLocale.turkish(seed);
        var teachers = teacherRepo.findByTenantId(tenant.getId());
        var teacherIter = teachers.iterator();

        var grades = List.of(9, 10, 11, 12);
        var sections = List.of("A", "B", "C", "D");
        int i = 0;

        for (int grade : grades) {
            for (String section : sections) {
                if (i++ >= tier.classCount) return;
                if (!teacherIter.hasNext()) teacherIter = teachers.iterator();
                var teacher = teacherIter.next();

                var clazz = Class.builder()
                    .id(deterministicUuid(tenant.getId(), grade, section))
                    .tenantId(tenant.getId())
                    .name(grade + "-" + section)
                    .grade(grade)
                    .section(section)
                    .homeRoomTeacherId(teacher.getId())
                    .academicYear("2024-2025")
                    .build();
                classRepo.save(clazz);
            }
        }
    }
}
```

### 6.3. AttendanceGenerator (büyük dataset)

```java
@Service
public class AttendanceGenerator {

    @Autowired AttendanceRepository repo;
    @Autowired StudentRepository studentRepo;
    @Autowired SeedTier tier;

    @Transactional
    public void seed(Tenant tenant, long seed) {
        var faker = TurkishLocale.turkish(seed);
        var students = studentRepo.findByTenantId(tenant.getId());
        var startDate = LocalDate.of(2024, 9, 1);
        var endDate = LocalDate.of(2024, 12, 31);

        var batch = new ArrayList<Attendance>(1000);
        for (var student : students) {
            for (var date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;

                var status = faker.random().nextInt(100) < 95 ? PRESENT : ABSENT;
                var rec = Attendance.builder()
                    .id(deterministicUuid(student.getId(), date))
                    .tenantId(tenant.getId())
                    .studentId(student.getId())
                    .date(date)
                    .status(status)
                    .build();
                batch.add(rec);

                if (batch.size() == 1000) {
                    repo.saveAll(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) repo.saveAll(batch);
    }
}
```

### 6.4. SeedApplication entry point

```java
@SpringBootApplication
public class SeedApplication implements CommandLineRunner {

    @Autowired List<DataGenerator> generators;
    @Autowired SeedTier tier;

    public static void main(String[] args) {
        SpringApplication.run(SeedApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        var seed = Long.parseLong(System.getProperty("seed", "42"));
        log.info("Seeding tier={}, seed={}", tier, seed);

        for (var gen : generators) {
            log.info("Running generator: {}", gen.getClass().getSimpleName());
            gen.seed(seed);
        }

        log.info("Seed complete.");
    }
}
```

### 6.5. Demo veri "anlatılabilir" hale getirme

Demo için ek öneri:
- "**Ahmet Yılmaz**" öğretmen — sales mühendisi "Ahmet öğretmenin yoklamasını görüyoruz" diyebilir.
- "**11-A**" sınıfı sabit.
- Belli ödeme örnekleri (bazı geciken, bazı taksitli).

`config/demo-personas.yaml`:
```yaml
personas:
  teachers:
    - name: "Ahmet Yılmaz"
      email: "ahmet.yilmaz@demo-okul.com.tr"
      subject: "Matematik"
    - name: "Ayşe Demir"
      email: "ayse.demir@demo-okul.com.tr"
      subject: "Türkçe"
  students:
    - name: "Mehmet Kaya"
      class: "11-A"
      parent: "Hatice Kaya"
```

### 6.6. Snapshot baseline (regression için)

Integration test'lerde:
```java
@Test
void student_count_matches_baseline() {
    assertThat(studentRepo.count()).isEqualTo(SeedTier.S.studentCount);
}
```

### 6.7. Truncate + reseed komut

```bash
./gradlew :tools:seed:truncate -PtenantId=demo-tenant-1
./gradlew :tools:seed:run -PtenantId=demo-tenant-1 -Ptier=M
```

`truncate` task: tüm tenant-scoped tabloları temizler (system/reference data dokunmaz).

## 7. Dikkat edilecek tuzaklar

- **Prod copy "anonymized" diye dahil etmek**: yasal risk + her senaryoda pseudonymization eksik. KESİNLİKLE YASAK.
- **Faker locale yanlış**: ABD ismi Türk veliye atanmış. Türkçe locale custom.
- **Random seed sabit değil**: idempotency bozulur, test'ler flaky. `new Random(42)` zorunlu.
- **Insert tek tek (saveAndFlush)**: 50K kayıt için 1 saat. Batch insert (`saveAll` + `hibernate.jdbc.batch_size`).
- **FK constraint violations**: önce öğretmen sonra sınıf sonra öğrenci sırası. Generator listesi `@Order` veya `dependsOn`.
- **Demo tenant'a prod test trafik**: demo'da prod ucuna istek atılır. Demo cluster izole.
- **Seed çalışırken Tilt diğer servisleri başlatması**: seed yarım kalır, app fail. `resource_deps` + `auto_init=True`.
- **Çok büyük tier**: laptop'ta L tier 30 dk. Default S.
- **DB volume kalıcılığı + idempotency yanlış**: ikinci çalıştırmada duplicate. `existing >= target then skip`.
- **Türkçe karakter encoding**: UTF-8 her yerde + DB collation `tr_TR.UTF-8`.
- **Test data'ya gerçek prod credentials**: `oner@onerbilisim.com` gibi. Test domain'lerine sabitle: `*.demo-okul.com.tr`, `*.test-okul.k12.tr`.
- **PII benzeri sahte veri Slack/log'a sızması**: synthetic olsa bile dikkat; "test data" prefix.
- **Sahte veri demo'da "gerçek müşteriye" gönderme**: e-posta provider'ın demo modda olduğunu doğrula.

## 8. Diğer konularla ilişkisi

- [Tilt Multi-Service Dev](./tilt-multi-service-dev) — seed Tilt resource
- [Database Architecture](../database-architecture) — Flyway migration sırası
- [Anonymization / DSAR](../security-compliance) — synthetic data, anonymized prod copy'nin neden olmadığı
- [Privacy / KVKK](../security-compliance) — yasal arka plan
- [GitLab CI Pipelines](../21-ci-cd/gitlab-ci-pipelines) — CI'da Testcontainers seed
- [Background Jobs](../22-workflow-temporal/background-jobs) — scheduled staging refresh

## 9. Daha derine inmek için

- Java Faker: [https://github.com/DiUS/java-faker](https://github.com/DiUS/java-faker)
- Faker.js: [https://fakerjs.dev/](https://fakerjs.dev/) (frontend için)
- Testcontainers: [https://testcontainers.com/](https://testcontainers.com/)
- "Effective Software Testing" — Maurício Aniche
- "Database Reliability Engineering" — Laine Campbell (seed pattern bölümü)
- Search keyword'leri: *"synthetic test data generation"*, *"java faker turkish locale"*, *"idempotent database seed"*, *"flyway repeatable migrations"*, *"deterministic uuid generation"*

## 10. Sözlük

- **Synthetic data**: Sıfırdan üretilmiş, gerçek kişiye karşılık gelmeyen veri.
- **Anonymized data**: Gerçek veriden PII çıkarılmış kopya (Lumix yasak).
- **Pseudonymization**: Geri dönüştürülebilir anonimleştirme (gerçek değildir).
- **Faker (Java Faker / Faker.js)**: Sahte veri üretim kitaplığı.
- **Locale**: Dil ve bölge ayarı (tr_TR, en_US).
- **Idempotent seed**: Aynı çalıştırma sonucu üreten seed script.
- **Deterministic seed**: Aynı random seed ile aynı output.
- **Tier (XS/S/M/L)**: Test verisi boyut sınıfı.
- **Reference data**: Domain'in olmazsa olmaz lookup'ları (rol, izin, şehir).
- **Repeatable migration (Flyway `R__`)**: Her migration'da idempotent olarak tekrar çalışan SQL.
- **Testcontainers**: Test'lerde Docker container ile gerçek DB/Kafka.
- **Demo persona**: Demo'da sabit anlatılabilir karakter (örn. "Ahmet öğretmen").
- **Domain-aware generator**: İlişkileri tutarlı üreten veri jeneratörü.
- **`@Order` (Spring)**: Bean'lerin yürütme sırası.
- **Snapshot baseline**: Bilinen seed sonucunu beklenen değer olarak kabul eden test.
