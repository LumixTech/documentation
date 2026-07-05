package com.lumix.template.application.port.in;

import com.lumix.template.domain.model.SampleId;

/** Inbound port (use case): yeni Sample oluştur. */
public interface CreateSampleUseCase {

    SampleId create(CreateSampleCommand command);
}
