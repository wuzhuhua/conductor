package com.netflix.conductor.core.journeyInsight;


import com.netflix.conductor.core.journeyInsight.JourneyInsightEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;

@ConditionalOnProperty(value = "journey-insight.db.type", havingValue = "stub", matchIfMissing = true)
@Slf4j
@Component
public class JourneyInsightWriterStub implements JourneyInsightWriter {
    @Override
    public void writeJourneyInsightEntryAsync(JourneyInsightEntry entry) {
        log.debug("Journey Insight Entry: {}", entry);
    }

    @Override
    public void writeJourneyInsightEntry(JourneyInsightEntry entry) {
        log.debug("Journey Insight Entry: {}", entry);
    }

    @Override
    @PreDestroy
    public void close() {
        log.info("Closing Journey Insight Writer");
    }
}
