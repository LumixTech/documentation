package com.lumix.template.adapter.in.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka adapter (şablon). Gerçek serviste mesajı bir inbound port'a (use case)
 * çevir; burada yalnızca log'lar.
 */
@Component
public class SampleEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(SampleEventConsumer.class);

    @KafkaListener(
            topics = "${lumix.kafka.inbound-topic:sample.commands}",
            groupId = "${spring.application.name:service-template}")
    public void onMessage(String payload) {
        LOG.info("Kafka mesajı alındı: {}", payload);
    }
}
