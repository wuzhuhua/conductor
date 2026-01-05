package com.netflix.conductor.core.journeyInsight.kafka;

import com.netflix.conductor.core.journeyInsight.JourneyInsightEntry;
import com.netflix.conductor.core.journeyInsight.JourneyInsightWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

import javax.annotation.PreDestroy;

@Component
@ConditionalOnProperty(value = "journey-insight.db.type", havingValue = "kafka")
@Slf4j
public class KafkaWriter implements JourneyInsightWriter {

    @Autowired
    private KafkaTemplate<String, JourneyInsightEntry> kTemplate;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Override
    public void writeJourneyInsightEntryAsync(JourneyInsightEntry entry) {
        ListenableFuture<SendResult<String, JourneyInsightEntry>> future = kTemplate.send(kafkaProperties.getTopic(), entry);
        future.addCallback(new ListenableFutureCallback<SendResult<String, JourneyInsightEntry>>() {
            @Override
            public void onSuccess(SendResult<String, JourneyInsightEntry> result) {
            }

            @Override
            public void onFailure(Throwable ex) {
                log.error("Failed to send message ({}) to kafka", entry, ex);
            }
        });

    }

    @Override
    public void writeJourneyInsightEntry(JourneyInsightEntry entry) {
        kTemplate.send(kafkaProperties.getTopic(), entry);
    }

    @PreDestroy
    @Override
    public void close() {
        kTemplate.flush();
        kTemplate.destroy();
    }
}
