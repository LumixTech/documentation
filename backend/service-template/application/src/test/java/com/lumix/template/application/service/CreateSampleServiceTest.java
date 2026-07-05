package com.lumix.template.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.lumix.template.application.port.in.CreateSampleCommand;
import com.lumix.template.application.port.out.DomainEventPublisher;
import com.lumix.template.application.port.out.SampleRepository;
import com.lumix.template.domain.event.SampleCreatedEvent;
import com.lumix.template.domain.model.Sample;
import com.lumix.template.domain.model.SampleId;

@ExtendWith(MockitoExtension.class)
class CreateSampleServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private SampleRepository repository;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Test
    void olusturur_kaydeder_ve_event_yayinlar() {
        when(repository.save(any(Sample.class))).thenAnswer(inv -> inv.getArgument(0));
        CreateSampleService service = new CreateSampleService(repository, eventPublisher, clock);

        SampleId id = service.create(new CreateSampleCommand("Alpha"));

        assertThat(id).isNotNull();
        verify(repository).save(any(Sample.class));
        verify(eventPublisher).publish(any(SampleCreatedEvent.class));
    }
}
