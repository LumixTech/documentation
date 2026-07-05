package com.lumix.template.adapter.out.persistence;

import com.lumix.template.domain.model.Sample;
import com.lumix.template.domain.model.SampleId;
import com.lumix.template.domain.model.SampleStatus;

/** Domain ↔ JPA entity dönüşümü. */
final class SampleEntityMapper {

    private SampleEntityMapper() {}

    static SampleJpaEntity toEntity(Sample sample) {
        return new SampleJpaEntity(
                sample.id().value(), sample.name(), sample.status().name(), sample.createdAt());
    }

    static Sample toDomain(SampleJpaEntity entity) {
        return Sample.rehydrate(
                new SampleId(entity.getId()),
                entity.getName(),
                SampleStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt());
    }
}
