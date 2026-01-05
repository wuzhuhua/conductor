package com.netflix.conductor.core.journeyInsight;

import com.netflix.conductor.core.journeyInsight.JourneyInsightEntry;

public interface JourneyInsightWriter extends AutoCloseable {
    public void writeJourneyInsightEntryAsync(JourneyInsightEntry entry);

    public void writeJourneyInsightEntry(JourneyInsightEntry entry);

    public void close();
}
