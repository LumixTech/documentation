package com.lumix.template.application.port.out;

import com.lumix.template.domain.event.DomainEvent;

/** Outbound port — domain event yayınlama. Implementasyon adapter-kafka'da (Outbox: bkz. 06-outbox). */
public interface DomainEventPublisher {

    void publish(DomainEvent event);
}
