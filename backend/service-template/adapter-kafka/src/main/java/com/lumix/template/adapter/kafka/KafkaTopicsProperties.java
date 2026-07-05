package com.lumix.template.adapter.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code lumix.kafka.*} ayarlarının tip-güvenli karşılığı. Bootstrap'taki
 * {@code @ConfigurationPropertiesScan} bu record'u bulur ve application.yml'den bağlar.
 *
 * <p>Yeni Kafka ayarı eklerken: buraya bileşen ekle + application.yml'e varsayılanını yaz —
 * dağınık {@code @Value} yerine tüm topic isimleri tek yerde toplanır.
 *
 * @param domainEventsTopic outbound domain event'lerinin yayınlandığı topic
 * @param inboundTopic inbound consumer'ın dinlediği topic
 */
@ConfigurationProperties(prefix = "lumix.kafka")
public record KafkaTopicsProperties(String domainEventsTopic, String inboundTopic) {}
