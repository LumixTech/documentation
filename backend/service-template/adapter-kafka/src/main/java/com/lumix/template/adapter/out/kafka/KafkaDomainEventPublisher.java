package com.lumix.template.adapter.out.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.lumix.template.application.port.out.DomainEventPublisher;
import com.lumix.template.domain.event.DomainEvent;

/**
 * Outbound port {@link DomainEventPublisher}'nin Kafka implementasyonu.
 *
 * <p>ŞABLON UYARISI: Doğrudan publish tutarlılık garantisi vermez. Gerçek serviste
 * <b>Outbox pattern</b> kullan (bkz. 02-architecture-patterns/06-outbox-pattern):
 * event aynı transaction'da outbox tablosuna yazılır, arka plan relay Kafka'ya iletir.
 */
@Component
public class KafkaDomainEventPublisher implements DomainEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaDomainEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public KafkaDomainEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${lumix.kafka.domain-events-topic:sample.domain-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(DomainEvent event) {
        LOG.info("Domain event yayınlanıyor type={} occurredAt={}", event.eventType(), event.occurredAt());
        kafkaTemplate.send(topic, event.eventType(), event.occurredAt().toString());
    }
}
