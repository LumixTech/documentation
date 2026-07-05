package com.lumix.template.domain.model;

import com.lumix.template.domain.event.DomainEvent;
import com.lumix.template.domain.event.SampleCreatedEvent;
import com.lumix.template.domain.exception.SampleAlreadyActiveException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Örnek aggregate root. Şablon amaçlıdır — gerçek serviste kendi aggregate'inle değiştir.
 *
 * <p>İş kuralları (invariant'lar) burada yaşar; use case service yalnızca orkestrasyon yapar
 * (bkz. 02-architecture-patterns/03-hexagonal-architecture).
 */
public final class Sample {

    private final SampleId id;
    private final Instant createdAt;
    private String name;
    private SampleStatus status;
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private Sample(SampleId id, String name, SampleStatus status, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id null olamaz");
        this.name = requireValidName(name);
        this.status = Objects.requireNonNull(status, "status null olamaz");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt null olamaz");
    }

    /** Yeni Sample üretir ve bir {@link SampleCreatedEvent} biriktirir. */
    public static Sample create(SampleId id, String name, Instant createdAt) {
        Sample sample = new Sample(id, name, SampleStatus.DRAFT, createdAt);
        sample.domainEvents.add(new SampleCreatedEvent(id, createdAt));
        return sample;
    }

    /** Kalıcı depodan yeniden kurar — event üretmez. */
    public static Sample rehydrate(SampleId id, String name, SampleStatus status, Instant createdAt) {
        return new Sample(id, name, status, createdAt);
    }

    public void activate() {
        if (status == SampleStatus.ACTIVE) {
            throw new SampleAlreadyActiveException(id);
        }
        this.status = SampleStatus.ACTIVE;
    }

    public void rename(String newName) {
        this.name = requireValidName(newName);
    }

    private static String requireValidName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name boş olamaz");
        }
        return name.strip();
    }

    public SampleId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public SampleStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Biriken domain event'lerin salt-okunur görünümü. */
    public List<DomainEvent> domainEvents() {
        return List.copyOf(domainEvents);
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }
}
