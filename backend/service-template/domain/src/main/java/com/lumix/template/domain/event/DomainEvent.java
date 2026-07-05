package com.lumix.template.domain.event;

import java.time.Instant;

/** Tüm domain event'lerinin ortak sözleşmesi (marker + metadata). */
public interface DomainEvent {

    Instant occurredAt();

    String eventType();
}
