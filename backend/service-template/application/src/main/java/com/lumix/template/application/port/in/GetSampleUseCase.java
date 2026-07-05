package com.lumix.template.application.port.in;

import com.lumix.template.domain.model.Sample;
import com.lumix.template.domain.model.SampleId;
import java.util.Optional;

/** Inbound port (use case): Sample'ı kimliğiyle getir. */
public interface GetSampleUseCase {

    Optional<Sample> byId(SampleId id);
}
