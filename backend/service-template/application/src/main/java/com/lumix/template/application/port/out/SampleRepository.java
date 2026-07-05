package com.lumix.template.application.port.out;

import java.util.Optional;

import com.lumix.template.domain.model.Sample;
import com.lumix.template.domain.model.SampleId;

/** Outbound port — kalıcılık. Implementasyon adapter-persistence'ta. */
public interface SampleRepository {

    Sample save(Sample sample);

    Optional<Sample> findById(SampleId id);
}
