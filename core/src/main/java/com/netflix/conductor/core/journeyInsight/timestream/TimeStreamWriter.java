package com.netflix.conductor.core.journeyInsight.timestream;

import com.netflix.conductor.core.journeyInsight.JourneyInsightEntry;
import com.netflix.conductor.core.journeyInsight.JourneyInsightWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.timestreamwrite.TimestreamWriteClient;
import software.amazon.awssdk.services.timestreamwrite.model.*;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import static com.netflix.conductor.core.journeyInsight.constant.TSJourneyMetricsColumns.*;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

@Component
@ConditionalOnProperty(value = "journey-insight.db.type", havingValue = "timestream")
public class TimeStreamWriter implements JourneyInsightWriter {
    public static final Logger LOGGER = LoggerFactory.getLogger(TimeStreamWriter.class);
    private TimestreamWriteClient writeClient;

    private TimestreamProperties timestreamProperties;

    private final BlockingQueue<JourneyInsightEntry> buffer;
    private final ExecutorService executorService;
    private volatile boolean running;


    @Autowired
    public TimeStreamWriter(TimestreamWriteClient writeClient, TimestreamProperties timestreamProperties) {
        this.writeClient = writeClient;
        this.timestreamProperties = timestreamProperties;
        this.buffer = new LinkedBlockingQueue<>();
        this.executorService = Executors.newSingleThreadExecutor();
        this.running = true;
        executorService.submit(this::batchWriteWorker);
    }

    public void writeJourneyInsightEntryAsync(JourneyInsightEntry entry) {
        writeJourneyInsightEntry(entry);
    }

    private void batchWriteWorker() {
        List<software.amazon.awssdk.services.timestreamwrite.model.Record> batch = new ArrayList<>();
        long lastFlushTime = System.currentTimeMillis();
        try {
            while (running) {
                JourneyInsightEntry entry = buffer.poll(timestreamProperties.getBatchFlushIntervalMillis(), MILLISECONDS);
                if (entry != null) {
                    software.amazon.awssdk.services.timestreamwrite.model.Record record = buildTimeStreamRecord(entry);
                    batch.add(record);
                    if (batch.size() >= timestreamProperties.getBatchWriteMaxRecordLength() ||
                            System.currentTimeMillis() - lastFlushTime >= timestreamProperties.getBatchFlushIntervalMillis()) {
                        performBatchWrite(batch);
                        batch.clear();
                        lastFlushTime = System.currentTimeMillis();
                    }
                } else {
                    // Time has elapsed, flush the batch if not empty
                    if (!batch.isEmpty()) {
                        performBatchWrite(batch);
                        batch.clear();
                        lastFlushTime = System.currentTimeMillis();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Ensure any remaining records are written before shutting down
            if (!batch.isEmpty()) {
                performBatchWrite(batch);
            }
        }
    }

    public void writeJourneyInsightEntry(JourneyInsightEntry entry){
        try {
            buffer.put(entry);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    @PreDestroy
    public void close() {
        running = false; // Stop the worker thread
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5,
                    SECONDS)) {
                LOGGER.warn("Executor did not terminate in time.");
                List<Runnable> droppedTasks = executorService.shutdownNow();
                LOGGER.warn("Executor was abruptly shut down. {} tasks will not be executed.", droppedTasks.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void performBatchWrite(List<software.amazon.awssdk.services.timestreamwrite.model.Record> batch) {
        WriteRecordsRequest writeRecordsRequest = WriteRecordsRequest.builder()
                .databaseName(timestreamProperties.getDatabaseName())
                .tableName(timestreamProperties.getTableName())
                .records(batch)
                .build();
        insertBatch(writeRecordsRequest);
    }

    private void insertBatch(WriteRecordsRequest writeRecordsRequest) {
        try {
            WriteRecordsResponse response = writeClient.writeRecords(writeRecordsRequest);
        } catch (RejectedRecordsException e) {
            for (RejectedRecord rejectedRecord : e.rejectedRecords()) {
                LOGGER.error("Rejected record index {}, reason {}, content {}", rejectedRecord.recordIndex(), rejectedRecord.reason()
                        , writeRecordsRequest.records().get(rejectedRecord.recordIndex()));
            }
        } catch (Exception e) {
            LOGGER.error("Exception happens during inserting batch. WriteRecordsRequest: {} ", writeRecordsRequest, e);
        }
    }

    private software.amazon.awssdk.services.timestreamwrite.model.Record buildTimeStreamRecord(JourneyInsightEntry entry) {
        List<Dimension> dimensions = new ArrayList<>();
        dimensions.add(Dimension.builder()
                .name(JOURNEY_ID)
                .value(entry.getJourneyId())
                .build());
        List<MeasureValue> measureValues = new ArrayList<>();
        if (isValidTimeStreamMeasureValue(entry.getNodeId())) {
            measureValues.add(MeasureValue.builder()
                    .name(NODE_ID)
                    .type(MeasureValueType.VARCHAR)
                    .value(entry.getNodeId())
                    .build());
        }

        if (isValidTimeStreamMeasureValue(entry.getUserId())) {
            measureValues.add(MeasureValue.builder()
                    .name(USER_ID)
                    .type(MeasureValueType.VARCHAR)
                    .value(entry.getUserId())
                    .build());
        }

        if (isValidTimeStreamMeasureValue(entry.getNodeType())) {
            measureValues.add(MeasureValue.builder()
                    .name(NODE_TYPE)
                    .type(MeasureValueType.VARCHAR)
                    .value(entry.getNodeType())
                    .build());
        }

        if (isValidTimeStreamMeasureValue(entry.getNodeStatus())) {
            measureValues.add(MeasureValue.builder()
                    .name(NODE_STATUS)
                    .type(MeasureValueType.VARCHAR)
                    .value(entry.getNodeStatus())
                    .build());
        }

        if (isValidTimeStreamMeasureValue(entry.getInstanceId())) {
            measureValues.add(MeasureValue.builder()
                    .name(INSTANCE_ID)
                    .type(MeasureValueType.VARCHAR)
                    .value(entry.getInstanceId())
                    .build());
        }

        if (isValidTimeStreamMeasureValue(entry.getIsGoalState())) {
            measureValues.add(MeasureValue.builder()
                    .name(IS_GOAL_STATE)
                    .type(MeasureValueType.BOOLEAN)
                    .value(entry.getIsGoalState() ? "True" : "False")
                    .build());
        }

        if (isValidTimeStreamMeasureValue(entry.getIsLeafNode())) {
            measureValues.add(MeasureValue.builder()
                    .name(IS_LEAF_NODE)
                    .type(MeasureValueType.BOOLEAN)
                    .value(entry.getIsLeafNode() ? "True" : "False")
                    .build());
        }

        if (isValidTimeStreamMeasureValue(entry.getVersion())) {
            measureValues.add(MeasureValue.builder()
                    .name(VERSION)
                    .type(MeasureValueType.BIGINT)
                    .value(entry.getVersion().toString())
                    .build());
        }
        long currentTimeMillis = System.currentTimeMillis();
        long nanoTime = System.nanoTime();

        long nanosecondsSinceEpoch = (currentTimeMillis * 1_000_000) + (nanoTime % 1_000_000);
        software.amazon.awssdk.services.timestreamwrite.model.Record record = software.amazon.awssdk.services.timestreamwrite.model.Record.builder()
                .dimensions(dimensions)
                .time(String.valueOf(nanosecondsSinceEpoch))
                .timeUnit(TimeUnit.NANOSECONDS)
                .measureName("metrics")
                .measureValueType(MeasureValueType.MULTI)
                .measureValues(measureValues)
                .build();
        return record;
    }

    private boolean isValidTimeStreamMeasureValue(Object o) {
        return o != null && !o.toString().isBlank();
    }


}
