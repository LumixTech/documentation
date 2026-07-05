package com.lumix.template.adapter.out.persistence;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.lumix.template.application.port.out.SampleRepository;
import com.lumix.template.domain.model.Sample;
import com.lumix.template.domain.model.SampleId;

/** Outbound port {@link SampleRepository}'nin JPA implementasyonu. */
@Component
public class SampleRepositoryAdapter implements SampleRepository {

    private final SampleJpaRepository jpaRepository;

    public SampleRepositoryAdapter(SampleJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Sample save(Sample sample) {
        SampleJpaEntity saved = jpaRepository.save(SampleEntityMapper.toEntity(sample));
        return SampleEntityMapper.toDomain(saved);
    }

    @Override
    public Optional<Sample> findById(SampleId id) {
        return jpaRepository.findById(id.value()).map(SampleEntityMapper::toDomain);
    }
}
