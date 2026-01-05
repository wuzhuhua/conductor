package com.netflix.conductor.core.journeyInsight.kafka;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "journey-insight.kafka")
@ConditionalOnProperty(value = "journey-insight.db.type", havingValue = "kafka")
public class KafkaProperties {
    private String bootstrapServers = "localhost:9092";
    private String topic = "journey-insight";
}