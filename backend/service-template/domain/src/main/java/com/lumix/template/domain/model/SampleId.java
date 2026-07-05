package com.lumix.template.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Sample aggregate kimliği (value object). */
public record SampleId(UUID value) {

    public SampleId {
        Objects.requireNonNull(value, "value null olamaz");
    }

    public static SampleId newId() {
        return new SampleId(UUID.randomUUID());
    }

    public static SampleId of(String raw) {
        return new SampleId(UUID.fromString(raw));
    }
}
