---
title: Domain-Driven Design (DDD)
description: DDD taktik (aggregate, entity, value object, invariant) ve stratejik (bounded context, ubiquitous language) kavramları ile Lumix'te uygulanışı.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Bu sayfa **Domain-Driven Design (DDD)** kavramlarını sıfırdan anlatıyor: stratejik tasarım (bounded context, ubiquitous language, context map) ve taktik tasarım (entity, value object, aggregate, domain event, invariant). Sonra Lumix'te bu kavramların **somut olarak nasıl uygulandığını** gösteriyor — gerçek kod parçaları ile. Sayfayı bitiren biri "Lumix'te neden `Attendance` bir aggregate, neden `Email` bir value object, neden `student_id`'yi başka servisten direkt almıyoruz" sorularını cevaplayabilmeli.

## 1. Bu nedir? (Sıfırdan)

**Domain-Driven Design (DDD)**, yazılımı **iş alanının (domain) gerçek kavramları etrafında** modelleyen bir yaklaşımdır. 2003'te Eric Evans'ın "Domain-Driven Design: Tackling Complexity in the Heart of Software" kitabıyla popülerleşti.

DDD'nin temel iddiası şudur: karmaşık yazılımlarda asıl zorluk **teknoloji** değil, **iş kurallarının doğru modellenmesi**dir. Eğer kodun iş diliyle aynı kelimeleri konuşmuyorsa, geliştirici ile domain expert (ürün müdürü, öğretmen, finans uzmanı) arasında kalıcı bir tercüme yükü oluşur — ve her tercüme bir bug fırsatıdır.

**Günlük hayattan analoji:**
Bir restoran düşün. Mutfakta üç kişi var:
- **Şef** — yemekleri biliyor ("ızgara somon, az pişmiş, limonsuz")
- **Garson** — müşteri ile konuşuyor ("Beyefendi somon sevmiyor")
- **Kasiyer** — hesabı kesiyor ("masa 5, somon = 150 TL")

Eğer üçü aynı dili konuşmuyorsa ("şef somon der, kasiyer balık der, garson seafood der") her sipariş yanlış anlamayla biter.

DDD'nin önerdiği şey: **bir yazılım takımı içinde domain expert + geliştirici + UX herkes aynı kelimeleri aynı anlamda kullansın**. Buna **ubiquitous language** (her yere yayılan dil) denir. Kodda da aynı kelimeler geçsin: `Order`, `Student`, `Attendance`, `Invoice`.

DDD ikiye ayrılır:

### Stratejik DDD (büyük resim)
- **Bounded Context** — modelin geçerli olduğu sınır
- **Ubiquitous Language** — sınır içinde geçerli ortak dil
- **Context Map** — bounded context'lerin birbirleriyle ilişkisi

### Taktik DDD (kod seviyesi)
- **Entity** — kimliği olan nesne
- **Value Object** — sadece değerinden ibaret nesne
- **Aggregate** — birlikte hareket eden nesne grubu
- **Aggregate Root** — aggregate'in dışa açılan tek kapısı
- **Domain Event** — olan iş olayı
- **Repository** — aggregate'i kalıcı tutan soyutlama
- **Domain Service** — bir aggregate'e ait olmayan business logic
- **Factory** — karmaşık aggregate yaratımı
- **Invariant** — aggregate'in her zaman doğru olması gereken kural

## 2. Hangi problemi çözüyor?

DDD olmadan üç tipik acı çekersin:

**Acı 1 — "Anemic Domain Model"**
Entity sınıfları sadece getter/setter'dan ibarettir. Tüm business logic "Service" sınıflarına dağılmıştır. Bir kuralı bulmak için 5 farklı servise bakman gerekir.

```java
// Anti-pattern: anemic model
@Entity
public class Attendance {
    private UUID id;
    private LocalDate date;
    private String status;
    // ...getter/setter, hiç davranış yok
}

@Service
public class AttendanceService {
    public void markAttendance(UUID classId, ...) {
        // 200 satır if-else, validation, side effect karışık
    }
}
```

Bir bug aramak için "yoklama 24 saat sonra revize edilebilir mi?" kuralının nerede tanımlandığını saatlerce ararsın.

**Acı 2 — Aynı kavramın farklı anlamı**
Bir sistemde 5 farklı `User` sınıfı vardır:
- identity'de: kimlik bilgileri (email, password hash)
- academic'te: öğrenci olarak rol (sınıf, devamsızlık)
- finance'te: müşteri olarak rol (bakiye, fatura adresi)

Hepsi "User" deyince zihinler karışır. Tek bir devasa `User` sınıfı yapmaya kalkarsan, hiç ilgisiz alanlar bir araya gelir, bağımlılık matrisi anlaşılmaz olur.

**Acı 3 — İş kuralı kod arasında kaybolur**
"Bir sınıfa 30'dan fazla öğrenci atanamaz" kuralı:
- Controller'da validation
- Service'te kontrol
- DB'de constraint
- Frontend'de UI restriction

Dört yerde, dört farklı tutarsızlıkla. Kural değiştiğinde dördünü de güncellemen gerekir, biri kaçırılır → bug.

DDD bu üç acıyı şu şekilde çözer:

| Acı | DDD çözümü |
|---|---|
| Anemic model | Behavior aggregate root'ta — `attendance.revise(...)` metodu |
| Aynı kavramın farklı anlamı | Bounded context = farklı microservice, farklı model |
| İş kuralı kayboluyor | Invariant aggregate içinde — tek yerde tanımlı |

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Stratejik DDD — bounded context

**Bounded context** = "bu kelimenin bu anlamı sadece bu sınırda geçerlidir" demek. Aynı `Student` kelimesi:
- `academic-service`'te = bir sınıfa kayıtlı, ders alan kişi (yoklama, ödev, sınav)
- `finance-service`'te = bir veliye bağlı, fatura kesilen kişi (borç, ödeme geçmişi)
- `counseling-service`'te = rehberlik kaydı olan birey (PDR notları, görüşmeler)

Üç bağlamda da `Student` ama **özellikleri ve davranışları farklı**. DDD diyor ki: bunları **aynı sınıf olmaya zorlama** — her bounded context kendi modeli olsun. Aralarındaki bağ sadece **bir ID** (`student_id`).

Lumix'te kural: **microservice = bounded context**. Her servisin kendi modeli, kendi dili, kendi DB'si.

**Context Map — bounded context'lerin ilişkisi:**

```
┌───────────────────┐    student_id    ┌──────────────────────┐
│ academic-service  │ ◄──────────────► │ organization-service │
│ (yoklama, ödev)   │   (Customer/     │ (master öğrenci      │
│                   │   Supplier)      │  registry)           │
└───────────────────┘                  └──────────────────────┘
         │
         │ event: attendance.marked
         ▼
┌───────────────────┐
│ performance-svc   │ (Conformist — academic'in dilini benimser)
└───────────────────┘
```

İlişki tipleri (DDD jargonu):
- **Customer/Supplier** — A, B'den hizmet alıyor; B müşterinin ihtiyaçlarını biliyor
- **Conformist** — A, B'nin modelini olduğu gibi kabul ediyor
- **Anti-Corruption Layer (ACL)** — A, B'nin modelini A'nın diline tercüme eden bir katman tutuyor
- **Shared Kernel** — iki context bir parça modeli paylaşıyor (Lumix'te tercih edilmez — shared lib yok)
- **Published Language** — ortak public schema (Lumix'te Kafka event'leri Protobuf ile)
- **Open Host Service** — iyi tanımlanmış public API (Lumix'te gRPC + Protobuf)

### 3.2. Taktik DDD — entity vs value object

**Entity** = kimliği olan nesne. İki entity aynı verilere sahip olsa bile farklıdır çünkü ID'leri farklı.

```java
// Entity: aynı isim, farklı kişi olabilir
public class Student {
    private final UUID id;   // KIMLIK
    private FullName name;   // Value object
    // ...
}
```

İki `Student` objesi `name="Ali Veli"` olsa bile, `id`'leri farklıysa **iki farklı öğrencidir**.

**Value Object** = sadece değerinden ibaret nesne, kimliği yok. İki value object aynı değerlere sahipse **aynı** kabul edilir.

```java
// Value object: aynı email'ler aynı şeydir
public record Email(String value) {
    public Email {
        if (!value.contains("@")) {
            throw new IllegalArgumentException("Geçersiz email");
        }
    }
}

// new Email("a@b.com").equals(new Email("a@b.com")) → true
```

**Value object özellikleri:**
- Immutable (değiştirilemez)
- Equality value-based (`equals`/`hashCode` field bazlı)
- Self-validating (constructor'da invariant kontrol)
- No identity (DB'de PK gerektirmez, embedded olabilir)

Java 16+ `record` value object için ideal.

### 3.3. Aggregate ve Aggregate Root

**Aggregate** = birlikte hareket eden, tutarlılığı bir arada korunan domain nesneleri grubu.

**Aggregate Root** = aggregate'in dışarıya açılan tek kapısı. Tüm değişiklikler aggregate root üzerinden yapılır.

Örnek: `Attendance` aggregate'i.

```
Attendance (Aggregate Root)
├── AttendanceId (Value Object)
├── ClassId (Value Object)
├── Date (Value Object)
├── List<StudentMark> (Entity'ler, child)
│   ├── StudentId
│   └── PresenceStatus (Enum/VO: PRESENT, ABSENT, LATE, EXCUSED)
└── Status (Enum: DRAFT, SUBMITTED, REVISED)
```

Kurallar:
- Dışarıdan **doğrudan** `StudentMark` değiştirilemez — `attendance.markStudent(studentId, PRESENT)` çağrılır
- Aggregate root **invariant'ı korur** (örn. "aynı öğrenci iki kez işaretlenemez")
- Persistence aggregate root seviyesinde olur (`attendanceRepository.save(attendance)`)

**Aggregate sınırı nasıl çizilir?**
- **Transactional sınır:** bir aggregate tek transaction'da kaydedilir. Çok büyük aggregate = uzun lock + concurrency sorunu.
- **Consistency sınırı:** aggregate içindeki invariant'lar her zaman doğru. Aggregate dışı = eventual consistency.
- **Reference by ID:** bir aggregate başka bir aggregate'i referans alırsa, **direkt object reference değil, ID** ile referans alır.

```java
// YANLIŞ:
public class Attendance {
    private Class class;  // Başka aggregate'i direkt referans
}

// DOĞRU:
public class Attendance {
    private ClassId classId;  // Sadece ID
}
```

### 3.4. Invariant

**Invariant** = aggregate'in her zaman doğru olması gereken kural.

Örnekler:
- "Aynı tarih için aynı sınıfa iki yoklama olamaz"
- "Devamsızlık 24 saat sonra revize edilemez"
- "Refund tutarı orijinal payment'tan büyük olamaz"
- "Bir sınıfta 30'dan fazla öğrenci olamaz"

Invariant **aggregate root'un metodlarında kontrol edilir**:

```java
public void revise(LocalDateTime now, RevisionReason reason) {
    if (Duration.between(submittedAt, now).toHours() > 24) {
        throw new AttendanceRevisionWindowExpiredException();
    }
    if (status != Status.SUBMITTED) {
        throw new IllegalStateException("Sadece SUBMITTED durumdaki yoklama revize edilebilir");
    }
    // ... revize işlemi
}
```

### 3.5. Domain Event

**Domain Event** = bounded context içinde olan, iş anlamı taşıyan olay.

Örnekler: `AttendanceMarked`, `GradeRecorded`, `PaymentCaptured`, `StudentEnrolled`.

Domain event:
- **Past tense** (olmuş bir şey): `OrderShipped`, `AttendanceMarked` ✓ — `ShipOrder`, `MarkAttendance` ✗ (bunlar command)
- **Immutable** — bir kez yaratıldı mı değişmez
- **Self-contained** — okuyan için yeterli context içerir
- **Aggregate'in içinde yaratılır** ve dışarı yayılır

```java
public class Attendance {
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    public void submit() {
        // ... business logic
        domainEvents.add(new AttendanceMarkedEvent(this.id, this.classId, ...));
    }

    public List<DomainEvent> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }
}
```

Repository save ederken bu event'ler:
1. Aynı transaction'da **outbox** tablosuna yazılır
2. Outbox Relay tarafından Kafka'ya yayılır (integration event olarak)

Detay: [Event-Driven Architecture](./04-event-driven-architecture.md), [Outbox Pattern](./06-outbox-pattern.md).

### 3.6. Repository ve Domain Service

**Repository** = aggregate'i persistence layer'dan soyutlayan interface.

```java
// Port (interface) — domain'de tanımlı
public interface AttendanceRepository {
    void save(Attendance attendance);
    Optional<Attendance> findById(AttendanceId id);
    Optional<Attendance> findByClassAndDate(ClassId classId, LocalDate date);
}
```

Implementation (adapter) `adapter/out/persistence/` altında, JPA ile.

**Domain Service** = bir aggregate'e ait olmayan business logic. Birden fazla aggregate'le konuşmak gerekiyorsa.

```java
@Service
public class AttendanceCalculationService {
    // Birden fazla aggregate'e dokunan logic
    public AttendanceSummary calculateSemester(StudentId student, TermId term, ...) {
        // ...
    }
}
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Lumix'te DDD kararı

Lumix DDD'yi **hem stratejik hem taktik seviyede** uyguluyor:

**Stratejik:**
- Her microservice = bir bounded context (10 ana servis + 2 cross-cutting)
- Aynı kavram (User, Student, Class) farklı servislerde farklı model olabilir
- Servisler arası iletişim sözleşmeli (gRPC + Kafka event Protobuf)
- Context map [Domain Servisleri](../01-tenancy-and-domain-model/02-domain-services-overview.md) sayfasında

**Taktik:**
- Her servis `domain/`, `application/`, `adapter/` katmanları kullanır (Hexagonal Architecture)
- Aggregate root'lar `domain/model/` altında
- Domain event'ler `domain/event/` altında
- Repository **port (interface)** `application/port/out/` altında, implementation `adapter/out/persistence/` altında

### 4.2. Ubiquitous Language örnekleri

| Lumix dili | Kod sınıfı |
|---|---|
| Yoklama almak | `Attendance.mark()` |
| Yoklamayı düzeltmek | `Attendance.revise()` |
| Devamsızlık | `PresenceStatus.ABSENT` |
| Öğretmen sınıfa atandı | `ClassAssignmentGrantedEvent` |
| Veliye bilgi gitti | `NotificationDispatchedEvent` |
| Karne oluşturuldu | `ReportCardGeneratedEvent` |
| KVKK silme talebi | `DSARDeleteRequestedEvent` |

İş tarafının kullandığı her kelime → kodda eşdeğeri olmalı. "Yoklama" diyene "attendance" deme; ya iş tarafına `Attendance` öğret, ya da kodda `Yoklama` kullan (Türkçe domain dilini tercih edenler için geçerli yaklaşım).

> **Lumix kararı:** İş kavramları İngilizce (`Attendance`, `Grade`, `Invoice`) ama UI'da Türkçe gösterilir. Sebep: kod uluslararası standartla yazılır, ekibe yeni katılan İngilizce öğrenmiş geliştirici kolay adapte olur.

### 4.3. Aggregate örnekleri — Lumix'ten

| Servis | Aggregate Root | Aggregate içeriği |
|---|---|---|
| identity | `User` | UserId, Email (VO), HashedPassword (VO), `List<UserRole>` |
| organization | `Class` | ClassId, TenantId, ClassName (VO), Capacity (VO), `List<Enrollment>` |
| academic | `Attendance` | AttendanceId, ClassId, Date, `List<StudentMark>`, Status |
| academic | `Homework` | HomeworkId, ClassId, Title, DueDate, `List<Submission>` |
| assessment | `Exam` | ExamId, ClassId, ExamDefinition, `List<Grade>`, ScalingPolicy |
| finance | `Invoice` | InvoiceId, TenantId, StudentId, `List<LineItem>`, Status |
| finance | `Payment` | PaymentId, InvoiceId, Amount (Money VO), Status, `List<PaymentAttempt>` |
| communication | `Conversation` | ConversationId, `List<Participant>`, `List<Message>` (snapshot/projection) |
| counseling | `CounselingSession` | SessionId, StudentId, EncryptedNotes (VO), `List<Attachment>` |

### 4.4. Lumix'te Value Object örnekleri

```java
// Email — identity-service
public record Email(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");

    public Email {
        Objects.requireNonNull(value);
        if (!PATTERN.matcher(value).matches()) {
            throw new InvalidEmailException(value);
        }
    }

    public String domain() {
        return value.substring(value.indexOf('@') + 1);
    }
}

// Money — finance-service
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
        if (amount.scale() > currency.getDefaultFractionDigits()) {
            throw new IllegalArgumentException("Para birimi için ondalık hassasiyeti aşıldı");
        }
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new CurrencyMismatchException(this.currency, other.currency);
        }
    }
}

// PresenceStatus — academic-service
public enum PresenceStatus {
    PRESENT, ABSENT, LATE, EXCUSED;

    public boolean countsAsAbsence() {
        return this == ABSENT;
    }
}

// ClassCapacity — organization-service
public record ClassCapacity(int value) {
    public ClassCapacity {
        if (value < 1 || value > 50) {
            throw new IllegalArgumentException("Sınıf kapasitesi 1-50 arası olmalı: " + value);
        }
    }
}
```

### 4.5. Aggregate persistence kuralı

Lumix'te aggregate **JPA entity** olarak modellenir ama **persistence detayı domain'i bozmaz**:

- Aggregate root metodları **iş diline uygun** (`attendance.revise()`)
- JPA annotation'ları domain class'ında kabul edilir (pragmatik) ama setter yok
- Repository **port interface** (`AttendanceRepository`) `application/port/out/` altında
- JPA implementation `adapter/out/persistence/` altında
- Çoğunlukla `JpaAttendanceRepository implements AttendanceRepository` şeklinde

> Pure DDD `domain/` katmanını framework'ten tamamen ayırmayı önerir. Lumix **pragmatik** seçim yaptı: JPA annotation domain'de olabilir, ama domain davranışları framework'e bağımlı olmaz.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

**Alternatif 1 — Anemic CRUD modeli**
Entity'ler sadece veri taşır, tüm logic "Service" sınıflarında.

Niye elendi:
- Karmaşıklık büyüdükçe service'ler patlar
- Aynı kuralın 3-4 yerde tekrar yazılması yaygın
- Domain dili kodda kaybolur

**Alternatif 2 — Transaction Script pattern**
Her use case'i tek bir prosedürel fonksiyon olarak yaz, OOP minimum.

Niye elendi:
- Kısa vadede hızlı ama yeniden kullanım sıfır
- Karmaşık iş kuralları için sürdürülebilir değil
- Test edilebilirlik düşük

**Alternatif 3 — Active Record pattern**
Entity'nin kendisi persistence'tan haberdar (`student.save()`).

Niye elendi:
- Persistence ile domain birbirine çok bağlanır
- Test için DB lazım, mock zor
- Hexagonal Architecture ilkeleriyle çelişir

### 5.2. Kabul edilen trade-off'lar

| Trade-off | Maliyet | Lumix'te kabul edilme sebebi |
|---|---|---|
| **Öğrenme eğrisi** | Yeni geliştirici DDD kavramlarına alışmalı | Karmaşık domain için zorunlu |
| **Boilerplate** | Value object, factory, repository interface vs. | Açıklığı artırır, sürdürülebilir |
| **Performans** | Aggregate yüklemek bazen fazla veri çeker | Lazy loading ve aggregate sınır seçimi ile yönetilir |
| **JPA ile gerilim** | "Pure" DDD JPA'ya direkt entegre değil | Pragmatik tercih: JPA annotation kabul ediyoruz |
| **Aggregate sınırı sanattır** | Yanlış sınır = sürekli refactor | DDD pratiği zamanla öğrenilir, yanlış sınır da tolerans |

### 5.3. Ne zaman DDD overkill?

DDD her proje için doğru değil. Şu durumlarda anemic model + service yeterli:
- Tipik CRUD uygulaması (form → DB → liste)
- Domain karmaşıklığı düşük (basit kurallar)
- Takım küçük, kavramsal yük taşıyamıyor
- Proje kısa ömürlü (POC, throwaway)

Lumix **karmaşık domain** + **uzun ömür** + **multi-stakeholder iş kuralları** + **KVKK gibi compliance gereksinimleri** içerdiği için DDD kararı net.

## 6. Pratik örnek

### 6.1. Attendance aggregate — academic-service

```java
// domain/model/Attendance.java
package com.lumix.academic.domain.model;

import com.lumix.academic.domain.event.AttendanceMarkedEvent;
import com.lumix.academic.domain.event.AttendanceRevisedEvent;
import com.lumix.academic.domain.event.DomainEvent;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "attendances")
public class Attendance {

    @EmbeddedId
    private AttendanceId id;

    @AttributeOverride(name = "value", column = @Column(name = "class_id"))
    private ClassId classId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @ElementCollection
    @CollectionTable(name = "attendance_marks", joinColumns = @JoinColumn(name = "attendance_id"))
    private List<StudentMark> marks = new ArrayList<>();

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Transient
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    protected Attendance() {} // JPA için

    public static Attendance create(ClassId classId, LocalDate date, UUID tenantId) {
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Gelecek tarihe yoklama alınamaz");
        }
        Attendance a = new Attendance();
        a.id = AttendanceId.newId();
        a.classId = classId;
        a.date = date;
        a.tenantId = tenantId;
        a.status = Status.DRAFT;
        return a;
    }

    public void mark(StudentId studentId, PresenceStatus presence) {
        if (status != Status.DRAFT) {
            throw new AttendanceAlreadySubmittedException(id);
        }
        if (marks.stream().anyMatch(m -> m.studentId().equals(studentId))) {
            throw new DuplicateStudentMarkException(studentId);
        }
        marks.add(new StudentMark(studentId, presence));
    }

    public void submit(Clock clock) {
        if (status != Status.DRAFT) {
            throw new IllegalStateException("Sadece DRAFT durumdaki yoklama submit edilebilir");
        }
        if (marks.isEmpty()) {
            throw new IllegalStateException("Hiç öğrenci işaretlenmeden submit edilemez");
        }
        this.status = Status.SUBMITTED;
        this.submittedAt = LocalDateTime.now(clock);
        domainEvents.add(new AttendanceMarkedEvent(id, classId, tenantId, marks, submittedAt));
    }

    public void revise(StudentId studentId, PresenceStatus newPresence, Clock clock) {
        if (status != Status.SUBMITTED) {
            throw new IllegalStateException("Sadece SUBMITTED durumdaki yoklama revize edilebilir");
        }
        Duration elapsed = Duration.between(submittedAt, LocalDateTime.now(clock));
        if (elapsed.toHours() >= 24) {
            throw new AttendanceRevisionWindowExpiredException(id);
        }
        StudentMark current = marks.stream()
            .filter(m -> m.studentId().equals(studentId))
            .findFirst()
            .orElseThrow(() -> new StudentNotInAttendanceException(studentId));

        marks.remove(current);
        marks.add(new StudentMark(studentId, newPresence));
        domainEvents.add(new AttendanceRevisedEvent(id, studentId, current.presence(), newPresence));
    }

    public List<DomainEvent> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }

    // Getters (immutable view), no setter
    public AttendanceId id() { return id; }
    public ClassId classId() { return classId; }
    public LocalDate date() { return date; }
    public Status status() { return status; }
    public List<StudentMark> marks() { return List.copyOf(marks); }

    public enum Status { DRAFT, SUBMITTED }
}
```

### 6.2. Value Object'ler

```java
// domain/model/AttendanceId.java
@Embeddable
public record AttendanceId(UUID value) {
    public static AttendanceId newId() {
        return new AttendanceId(UuidCreator.getTimeOrderedEpoch()); // UUID v7
    }
}

// domain/model/StudentMark.java
@Embeddable
public record StudentMark(StudentId studentId, PresenceStatus presence) {
    public StudentMark {
        Objects.requireNonNull(studentId);
        Objects.requireNonNull(presence);
    }
}

// domain/model/PresenceStatus.java
public enum PresenceStatus {
    PRESENT, ABSENT, LATE, EXCUSED;

    public boolean isAbsence() {
        return this == ABSENT || this == LATE;
    }
}
```

### 6.3. Domain Event

```java
// domain/event/AttendanceMarkedEvent.java
public record AttendanceMarkedEvent(
    AttendanceId attendanceId,
    ClassId classId,
    UUID tenantId,
    List<StudentMark> marks,
    LocalDateTime submittedAt
) implements DomainEvent {

    @Override
    public Instant occurredAt() {
        return submittedAt.atZone(ZoneOffset.UTC).toInstant();
    }

    @Override
    public String eventType() {
        return "academic.attendance.marked.v1";
    }
}
```

### 6.4. Use Case — application layer

```java
// application/service/MarkAttendanceUseCase.java
@Service
@RequiredArgsConstructor
@Transactional
public class MarkAttendanceUseCase {

    private final AttendanceRepository attendanceRepo;
    private final OutboxEventPublisher outboxPublisher;
    private final Clock clock;

    public AttendanceId execute(MarkAttendanceCommand cmd) {
        Attendance attendance = attendanceRepo
            .findByClassAndDate(cmd.classId(), cmd.date())
            .orElseGet(() -> Attendance.create(cmd.classId(), cmd.date(), cmd.tenantId()));

        for (var mark : cmd.marks()) {
            attendance.mark(mark.studentId(), mark.presence());
        }
        attendance.submit(clock);

        attendanceRepo.save(attendance);

        for (DomainEvent event : attendance.domainEvents()) {
            outboxPublisher.publish(event);
        }
        attendance.clearDomainEvents();

        return attendance.id();
    }
}
```

### 6.5. Repository port + adapter

```java
// application/port/out/AttendanceRepository.java
public interface AttendanceRepository {
    void save(Attendance attendance);
    Optional<Attendance> findById(AttendanceId id);
    Optional<Attendance> findByClassAndDate(ClassId classId, LocalDate date);
}

// adapter/out/persistence/JpaAttendanceRepository.java
@Repository
@RequiredArgsConstructor
public class JpaAttendanceRepository implements AttendanceRepository {

    private final EntityManager em;

    @Override
    public void save(Attendance attendance) {
        if (em.contains(attendance)) {
            em.merge(attendance);
        } else {
            em.persist(attendance);
        }
    }

    @Override
    public Optional<Attendance> findById(AttendanceId id) {
        return Optional.ofNullable(em.find(Attendance.class, id));
    }

    @Override
    public Optional<Attendance> findByClassAndDate(ClassId classId, LocalDate date) {
        return em.createQuery(
            "SELECT a FROM Attendance a WHERE a.classId = :classId AND a.date = :date",
            Attendance.class
        )
        .setParameter("classId", classId)
        .setParameter("date", date)
        .getResultList()
        .stream()
        .findFirst();
    }
}
```

### 6.6. Unit test — domain logic

```java
class AttendanceTest {

    Clock fixedClock = Clock.fixed(Instant.parse("2026-05-27T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldRejectFutureDate() {
        assertThatThrownBy(() ->
            Attendance.create(ClassId.of("c1"), LocalDate.now().plusDays(1), UUID.randomUUID())
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectRevisionAfter24Hours() {
        Attendance a = Attendance.create(ClassId.of("c1"), LocalDate.now(), UUID.randomUUID());
        a.mark(StudentId.of("s1"), PresenceStatus.PRESENT);
        a.submit(fixedClock);

        Clock later = Clock.fixed(Instant.parse("2026-05-28T11:00:00Z"), ZoneOffset.UTC);

        assertThatThrownBy(() ->
            a.revise(StudentId.of("s1"), PresenceStatus.ABSENT, later)
        ).isInstanceOf(AttendanceRevisionWindowExpiredException.class);
    }

    @Test
    void shouldEmitMarkedEventOnSubmit() {
        Attendance a = Attendance.create(ClassId.of("c1"), LocalDate.now(), UUID.randomUUID());
        a.mark(StudentId.of("s1"), PresenceStatus.PRESENT);
        a.submit(fixedClock);

        assertThat(a.domainEvents())
            .hasSize(1)
            .first()
            .isInstanceOf(AttendanceMarkedEvent.class);
    }
}
```

Domain logic'i test etmek için **DB veya Spring context lazım değil**. Plain JUnit + AssertJ.

## 7. Dikkat edilecek tuzaklar

**Tuzak 1 — Anemic Aggregate.**
`Attendance` sınıfında setter'lar, public field'lar, hiç davranış yok; tüm logic service'te. Bu DDD değil, anemic CRUD.
**Önleme:** Aggregate metodları iş diliyle yazılır (`attendance.revise(...)`, `attendance.submit(...)`).

**Tuzak 2 — Aggregate'in çok büyümesi.**
Bir aggregate root'a 100 child entity bağlanır. Save edilirken hepsi yüklenir, lock alınır, performans çöker.
**Önleme:** Aggregate sınırını dar tut. Eğer iki şey aynı transaction'da tutarlı olmak zorunda değilse, ayrı aggregate yap.

**Tuzak 3 — Cross-aggregate object reference.**
`Attendance.class` (yani Class objesini direkt referans) yerine `Attendance.classId` (sadece ID).
**Önleme:** Aggregate'ler arasında **sadece ID referansı**. Cross-aggregate ihtiyacı varsa: domain service veya event.

**Tuzak 4 — Bounded context'i yanlış çizmek.**
"Tek bir Student modeli yeter" denir, identity + academic + finance hepsi aynı model. 6 ay sonra Student sınıfında 50 alan + 200 metod var.
**Önleme:** Aynı kavramın **farklı bağlamlarda farklı modelinin** olmasını kabul et. ID ile bağla.

**Tuzak 5 — Domain event'in çok teknik olması.**
`AttendanceRowUpdatedEvent` — bu domain event değil, persistence event. İş anlamı yok.
**Önleme:** Domain event'ler iş diliyle isimlendirilir, past tense: `AttendanceMarked`, `AttendanceRevised`.

**Tuzak 6 — Repository'yi CRUD'a indirgemek.**
`save`, `findById`, `findAll`, `delete` — repository sadece bunlardan oluşur. Domain dilini taşımaz.
**Önleme:** Repository iş diline uyumlu olabilir: `findByClassAndDate`, `findActiveByTenant`.

**Tuzak 7 — Value object'i entity gibi kullanmak.**
`Email` sınıfına id verir, DB'de ayrı tablo yapar, equality'yi `id`'ye bağlar.
**Önleme:** Value object **immutable + value-based equality**. ID yok, embedded olabilir.

**Tuzak 8 — DDD'yi her şeye uygulamak.**
Basit lookup tablosu (`countries`, `languages`) için bile aggregate root, repository, factory yazılır.
**Önleme:** DDD karmaşık domain için. CRUD lookup'lar için sade JPA yeter.

**Tuzak 9 — Domain logic'in framework'e bağlanması.**
Aggregate Spring annotation'larıyla dolu, business kural Spring lifecycle'a bağımlı.
**Önleme:** Aggregate framework'e bağımlı olmasın (JPA annotation pragmatik kabul, ama Spring `@Component` yasak).

**Tuzak 10 — Ubiquitous language'i atlamak.**
Geliştirici "isteğin durumu" der ama domain expert "talep durumu" der. Kodda `request.state`, dokümanda `talep durumu`. Kafa karışıklığı sürekli.
**Önleme:** Sözlüğü ([Glossary](../glossary/glossary)) güncel tut, kod review'da terim tutarlılığı denetlensin.

## 8. Diğer konularla ilişkisi

- [Microservices Architecture](./01-microservices-architecture.md) — bounded context ≈ microservice
- [Hexagonal Architecture](./03-hexagonal-architecture.md) — domain/application/adapter katmanları
- [Event-Driven Architecture](./04-event-driven-architecture.md) — domain event'ten integration event'e
- [Outbox Pattern](./06-outbox-pattern.md) — domain event'lerin atomic publish'i
- [Domain Servisleri](../01-tenancy-and-domain-model/02-domain-services-overview.md) — Lumix'in 10 bounded context'i
- [Validation Strategy](../03-backend/04-validation-strategy.md) — invariant nerede kontrol edilir

## 9. Daha derine inmek için

**Kitaplar:**
- Eric Evans, "Domain-Driven Design: Tackling Complexity in the Heart of Software" (2003) — orijinal kaynak
- Vaughn Vernon, "Implementing Domain-Driven Design" (2013) — pratik uygulama
- Vaughn Vernon, "Domain-Driven Design Distilled" (2016) — özet versiyon
- Scott Millett, "Patterns, Principles, and Practices of Domain-Driven Design" (2015)

**Online kaynaklar:**
- domainlanguage.com — Eric Evans'ın sitesi
- vaughnvernon.com/?page_id=168 — VV'nin DDD makaleleri
- learning.oreilly.com — DDD eğitim videoları

**Spring + DDD:**
- Spring Modulith — modüler monolit için
- Spring Data JPA + value object (`@Embeddable`)

**Search keywords (İngilizce):**
- "domain-driven design tactical patterns"
- "aggregate design rules"
- "ubiquitous language"
- "bounded context examples"
- "domain event vs integration event"
- "anemic domain model antipattern"
- "value object java record"

## 10. Sözlük

- **Aggregate** — Birlikte hareket eden, tutarlılığı bir arada korunan domain nesneleri grubu.
- **Aggregate Root** — Aggregate'in dışa açılan tek kapısı. State değişimi sadece buradan.
- **Bounded Context** — Bir modelin/dilin geçerli olduğu sınır. Lumix'te ≈ microservice.
- **Context Map** — Bounded context'lerin birbirleriyle ilişkisinin haritası.
- **Domain Event** — İş anlamı taşıyan, olmuş olay. Past tense.
- **Domain Service** — Tek aggregate'e ait olmayan business logic.
- **Entity** — Kimliği olan nesne. Aynı verilerle bile ID farklıysa farklı entity.
- **Factory** — Karmaşık aggregate yaratımı için yardımcı.
- **Invariant** — Aggregate'in her zaman doğru olması gereken kural.
- **Repository** — Aggregate'i persistence'tan soyutlayan interface.
- **Ubiquitous Language** — Domain expert + geliştirici arasında ortak kullanılan dil.
- **Value Object** — Sadece değerinden ibaret, immutable, value-based equality olan nesne.
- **Anti-Corruption Layer (ACL)** — Bir bounded context'in başka birinin modelini kendi diline tercüme katmanı.
- **Anemic Domain Model** — Sadece getter/setter olan, davranışsız entity'ler (anti-pattern).
