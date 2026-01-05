package com.netflix.conductor.core.journeyInsight.clickhouse;


import com.clickhouse.jdbc.ClickHouseDataSource;
import com.netflix.conductor.core.journeyInsight.JourneyInsightEntry;
import com.netflix.conductor.core.journeyInsight.JourneyInsightWriter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.NotImplementedException;

import javax.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;


@Slf4j
public class ClickHouseWriter implements JourneyInsightWriter {


    private final BlockingQueue<JourneyInsightEntry> buffer;
    private final ExecutorService executorService;
    private volatile boolean running;
    private ChProperties chProperties;
    private ClickHouseDataSource chDataSource;

    public ClickHouseWriter(ChProperties chProperties, ClickHouseDataSource chDataSource) {
        this.chProperties = chProperties;
        this.chDataSource = chDataSource;
        this.buffer = new LinkedBlockingQueue<>();
        this.executorService = Executors.newSingleThreadExecutor();
        this.running = true;
        this.executorService.submit(this::entryBufferConsumer);
    }


    @Override
    public void writeJourneyInsightEntryAsync(JourneyInsightEntry entry) {
        try {
            buffer.put(entry);
        } catch (InterruptedException e) {
            log.error("Clickhouse put buffer error", e);
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void writeJourneyInsightEntry(JourneyInsightEntry entry) {
        throw new NotImplementedException("Not implemented");
    }


    void insertBatch(List<JourneyInsightEntry> entries) {
        log.info("Clickhouse insertBatch Size: {}", entries.size());
        String sqlTemplate = "insert into %s.%s (`uuid`, `journeyId`, `time`, `instanceId`, `nodeStatus`, `nodeType`, `nodeId`, `userId`, `isGoalState`, `version`, `isLeafNode`)" + " values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        int retryCount = 0;
        Connection conn;
        while (true) {
            try {
                conn = chDataSource.getConnection(chProperties.getUserName(), chProperties.getPassword());
                break;
            } catch (SQLException e) {
                log.error("Error happens when getting connection", e);
                retryCount++;
                if (retryCount >= 3) {
                    return;
                }
            }
        }
        Map<String, List<JourneyInsightEntry>> entriesGroupByTenantName = entries.stream().collect(java.util.stream.Collectors.groupingBy(JourneyInsightEntry::getTenantName));
        for (Map.Entry<String, List<JourneyInsightEntry>> mEntry : entriesGroupByTenantName.entrySet()) {
            String tenantName = mEntry.getKey();
            String sql = String.format(sqlTemplate, tenantName, chProperties.getTableName());
            log.info("Clickhouse Run Sql : {}", sql);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                log.info("Clickhouse tenant Size: {}", mEntry.getValue().size());
                for (JourneyInsightEntry entry : mEntry.getValue()) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, entry.getJourneyId() != null ? entry.getJourneyId() : "");
                    ps.setObject(3, entry.getCreatedAt().withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
                    ps.setString(4, entry.getInstanceId() != null ? entry.getInstanceId() : "");
                    ps.setString(5, entry.getNodeStatus() != null ? entry.getNodeStatus() : "");
                    ps.setString(6, entry.getNodeType() != null ? entry.getNodeType() : "");
                    ps.setString(7, entry.getNodeId() != null ? entry.getNodeId() : "");
                    ps.setString(8, entry.getUserId() != null ? entry.getUserId() : "");
                    ps.setBoolean(9, entry.getIsGoalState() == null ? false : entry.getIsGoalState());
                    ps.setInt(10, entry.getVersion() != null ? entry.getVersion() : -1);
                    ps.setBoolean(11, entry.getIsLeafNode() == null ? false : entry.getIsLeafNode());
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (Exception e) {
                log.error("Error happens when inserting batch", e);
            }
        }

    }

    public void entryBufferConsumer() {
        List<JourneyInsightEntry> batch = new LinkedList<>();
        long lastFlushTime = System.currentTimeMillis();
        while (running) {
            try {
                JourneyInsightEntry entry = buffer.poll(chProperties.getBatchFlushIntervalMillis(), MILLISECONDS);
                if (entry != null) {
                    batch.add(entry);
                    if (batch.size() >= chProperties.getBatchWriteMaxRecordLength() || System.currentTimeMillis() - lastFlushTime >= chProperties.getBatchFlushIntervalMillis()) {
                        insertBatch(batch);
                        batch.clear();
                        lastFlushTime = System.currentTimeMillis();
                    }
                } else {
                    // Time has elapsed, flush the batch if not empty
                    if (!batch.isEmpty()) {
                        insertBatch(batch);
                        batch.clear();
                        lastFlushTime = System.currentTimeMillis();
                    }
                }
            } catch (Exception e) {
                log.error("Buffer consumer gets interrupted", e);
//            Thread.currentThread().interrupt();
            }
        }

    }

    @Override
    @PreDestroy
    public void close() {
        running = false; // Stop the worker thread
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, SECONDS)) {
                log.warn("Executor did not terminate in time.");
                List<Runnable> droppedTasks = executorService.shutdownNow();
                log.warn("Executor was abruptly shut down. {} tasks will not be executed.", droppedTasks.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


}
