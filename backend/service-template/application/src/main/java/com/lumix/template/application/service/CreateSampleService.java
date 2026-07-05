package com.lumix.template.application.service;

import com.lumix.template.application.port.in.CreateSampleCommand;
import com.lumix.template.application.port.in.CreateSampleUseCase;
import com.lumix.template.application.port.out.DomainEventPublisher;
import com.lumix.template.application.port.out.SampleRepository;
import com.lumix.template.domain.model.Sample;
import com.lumix.template.domain.model.SampleId;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case orkestrasyonu: aggregate yükle/oluştur → kaydet → event yayınla.
 * İş kuralı yoktur (o domain'de); yalnızca transaction sınırı ve port koordinasyonu.
 */
@Service
@Transactional
public class CreateSampleService implements CreateSampleUseCase {

    private final SampleRepository repository;
    private final DomainEventPublisher eventPublisher;
    private final Clock clock;

    public CreateSampleService(SampleRepository repository, DomainEventPublisher eventPublisher, Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    public SampleId create(CreateSampleCommand command) {
        Sample sample = Sample.create(SampleId.newId(), command.name(), clock.instant());
        repository.save(sample);
        sample.domainEvents().forEach(eventPublisher::publish);
        sample.clearDomainEvents();
        return sample.id();
    }
}
