package com.lumix.template.domain.event;

import com.lumix.template.domain.model.SampleId;
import java.time.Instant;

/** Yeni bir Sample oluşturulduğunda yayılan domain event. */
public record SampleCreatedEvent(SampleId sampleId, Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "sample.created";
    }
}
