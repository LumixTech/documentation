package com.lumix.template.adapter.in.rest.mapper;

import com.lumix.template.adapter.in.rest.dto.SampleResponse;
import com.lumix.template.domain.model.Sample;

/** Domain ↔ REST DTO dönüşümü. */
public final class SampleRestMapper {

    private SampleRestMapper() {}

    public static SampleResponse toResponse(Sample sample) {
        return new SampleResponse(
                sample.id().value(), sample.name(), sample.status().name());
    }
}
