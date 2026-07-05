package com.lumix.template.domain.event;

import java.time.Instant;

import com.lumix.template.domain.model.SampleId;

/** Yeni bir Sample oluşturulduğunda yayılan domain event. */
public record SampleCreatedEvent(SampleId sampleId, Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "sample.created";
    }
}
