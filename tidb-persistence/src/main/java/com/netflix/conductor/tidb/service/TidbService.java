/*
 * Copyright 2022 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.tidb.service;

import com.baomidou.mybatisplus.mapper.EntityWrapper;
import com.baomidou.mybatisplus.plugins.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.common.metadata.events.EventExecution;
import com.netflix.conductor.common.metadata.tasks.TaskExecLog;
import com.netflix.conductor.common.run.SearchResult;
import com.netflix.conductor.common.run.TaskSummary;
import com.netflix.conductor.common.run.WorkflowSummary;
import com.netflix.conductor.core.events.queue.Message;
import com.netflix.conductor.core.exception.TransientException;
import com.netflix.conductor.dao.IndexDAO;
import com.netflix.conductor.metrics.Monitors;
import com.netflix.conductor.tidb.entity.TaskEntity;
import com.netflix.conductor.tidb.entity.WorkflowEntity;
import com.netflix.conductor.tidb.service.query.parser.internal.ComparisonOp;
import com.netflix.conductor.tidb.service.query.parser.internal.ParserException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


@Component
public class TidbService implements IndexDAO {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskService taskService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private ObjectMapper objectMapper;

    private static final Logger LOGGER = LoggerFactory.getLogger(TidbService.class);

    private static final int CORE_POOL_SIZE = 6;
    private static final long KEEP_ALIVE_TIME = 1L;

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");
    private static final SimpleDateFormat SIMPLE_DATE_FORMAT = new SimpleDateFormat("yyyyMMWW");


    private static final String className = TidbService.class.getSimpleName();


    private ExecutorService executorService = new ThreadPoolExecutor(CORE_POOL_SIZE, 12, KEEP_ALIVE_TIME, TimeUnit.MINUTES, new LinkedBlockingQueue<>(100), (runnable, executor) -> {
        LOGGER.warn("Request  {} to async mapper discarded in executor {}", runnable, executor);
        Monitors.recordDiscardedIndexingCount("indexQueue");
    });

    static {
        SIMPLE_DATE_FORMAT.setTimeZone(GMT);
    }


    @PreDestroy
    private void shutdown() {
        LOGGER.info("Gracefully shutdown executor service");
        shutdownExecutorService(executorService);
    }

    private void shutdownExecutorService(ExecutorService execService) {
        try {
            execService.shutdown();
            if (execService.awaitTermination(30, TimeUnit.SECONDS)) {
                LOGGER.debug("tasks completed, shutting down");
            } else {
                LOGGER.warn("Forcing shutdown after waiting for 30 seconds");
                execService.shutdownNow();
            }
        } catch (InterruptedException ie) {
            LOGGER.warn("Shutdown interrupted, invoking shutdownNow on scheduledThreadPoolExecutor for delay queue");
            execService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    @PostConstruct
    public void setup() throws Exception {
        List<Map<String, Object>> schemeList = jdbcTemplate.queryForList("SHOW DATABASES");
        boolean schemeExist = schemeList.stream().anyMatch(item -> ((String) item.get("Database")).equalsIgnoreCase("conductor"));
        if (!schemeExist) {
            jdbcTemplate.execute("CREATE DATABASE conductor\n" +
                    "CHARACTER SET utf8mb4\n" +
                    "COLLATE utf8mb4_bin;\n");
        }


        List<Map<String, Object>> tableList = jdbcTemplate.queryForList("SELECT TABLE_NAME\n" +
                "FROM INFORMATION_SCHEMA.TABLES\n" +
                "WHERE TABLE_SCHEMA = 'conductor';");
        boolean tableExist = tableList.stream().anyMatch(item -> ((String) item.get("TABLE_NAME")).equalsIgnoreCase("workflow"));
        if (!tableExist) {
            jdbcTemplate.execute("CREATE TABLE `task` (\n" +
                    "    `workflowId` VARCHAR(128),\n" +
                    "    `correlationId` VARCHAR(255),\n" +
                    "    `endTime` DATETIME(3),\n" +
                    "    `executionTime` BIGINT,\n" +
                    "    `input` TEXT,\n" +
                    "    `output` TEXT,\n" +
                    "    `queueWaitTime` BIGINT,\n" +
                    "    `reasonForIncompletion` TEXT,\n" +
                    "    `scheduledTime` DATETIME(3),\n" +
                    "    `startTime` DATETIME(3),\n" +
                    "    `status` VARCHAR(32),\n" +
                    "    `taskDefName` VARCHAR(255),\n" +
                    "    `taskId` VARCHAR(128) PRIMARY KEY,\n" +
                    "    `taskType` VARCHAR(255),\n" +
                    "    `updateTime` DATETIME(3),\n" +
                    "    `workflowType` VARCHAR(255),\n" +
                    "    `domain` VARCHAR(255),\n" +
                    "    `taskRefName` VARCHAR(255),\n" +
                    "    `workflowPriority` INT,\n" +
                    "    `rawJSON` MEDIUMTEXT,\n" +
                    "    `archived` TINYINT\n" +
                    ") DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;\n");

            jdbcTemplate.execute("CREATE TABLE workflow (\n" +
                    "    workflowType VARCHAR(255),\n" +
                    "    version INT,\n" +
                    "    workflowId VARCHAR(128) PRIMARY KEY,\n" +
                    "    correlationId VARCHAR(255),\n" +
                    "    startTime datetime(3),\n" +
                    "    updateTime datetime(3),\n" +
                    "    endTime datetime(3),\n" +
                    "    status VARCHAR(32),\n" +
                    "    input TEXT,\n" +
                    "    output TEXT,\n" +
                    "    reasonForIncompletion TEXT,\n" +
                    "    executionTime BIGINT,\n" +
                    "    inputSize BIGINT,\n" +
                    "    outputSize BIGINT,\n" +
                    "    event VARCHAR(255),\n" +
                    "    failedReferenceTaskNames TEXT,\n" +
                    "    externalInputPayloadStoragePath TEXT,\n" +
                    "    externalOutputPayloadStoragePath TEXT,\n" +
                    "    rawJSON MEDIUMTEXT,\n" +
                    "    archived tinyint,\n" +
                    "    priority INT,\n" +
                    "    failedTaskNames TEXT\n" +
                    ") DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;\n");

            jdbcTemplate.execute("create index workflow_correlationId_workflowType_status_index\n" +
                    "    on workflow (correlationId,workflowType,status);");

            jdbcTemplate.execute("create index workflow_startTime_status_index\n" +
                    "    on workflow (startTime,status);");

            jdbcTemplate.execute("create index workflow_startTime_workflowType_status_index\n" +
                    "    on workflow (startTime,workflowType,status);");
        }
    }


    @Override
    public void indexWorkflow(WorkflowSummary workflow) {
        try {
            WorkflowEntity workflowEntity = objectMapper.convertValue(workflow, WorkflowEntity.class);
            long startTime = Instant.now().toEpochMilli();
            LOGGER.debug("indexWorkflow: source: {} target: {}", objectMapper.writeValueAsString(workflow), objectMapper.writeValueAsString(workflowEntity));
            fillWorkflowDate(workflow, workflowEntity);
            workflowService.insertOrUpdate(workflowEntity);
            long endTime = Instant.now().toEpochMilli();
            LOGGER.debug("Time taken {} for indexing workflow: {}", endTime - startTime, workflow.getWorkflowId());
        } catch (Exception e) {
            Monitors.error(className, "indexWorkflow");
            LOGGER.error("Failed to index workflow: {}", workflow.getWorkflowId(), e);
        }
    }

    private void fillWorkflowDate(WorkflowSummary workflowSummary, WorkflowEntity workflowEntity) {
        String endTime = workflowSummary.getEndTime();
        String startTime = workflowSummary.getStartTime();
        String updateTime = workflowSummary.getUpdateTime();
        workflowEntity.setStartTime(parseDate(startTime));
        workflowEntity.setEndTime(parseDate(endTime));
        workflowEntity.setUpdateTime(parseDate(updateTime));
    }

    private void fillTaskDate(TaskSummary taskSummary, TaskEntity taskEntity) {
        String endTime = taskSummary.getEndTime();
        String startTime = taskSummary.getStartTime();
        String updateTime = taskSummary.getUpdateTime();
        String scheduledTime = taskSummary.getScheduledTime();
        taskEntity.setStartTime(parseDate(startTime));
        taskEntity.setEndTime(parseDate(endTime));
        taskEntity.setUpdateTime(parseDate(updateTime));
        taskEntity.setScheduledTime(parseDate(scheduledTime));
    }

    private Date parseDate(String iso) {
        if (StringUtils.isNotEmpty(iso)) {
            Instant instant = Instant.parse(iso);
            return Date.from(instant);
        }
        return null;
    }

    @Override
    public CompletableFuture<Void> asyncIndexWorkflow(WorkflowSummary workflow) {
        return CompletableFuture.runAsync(() -> indexWorkflow(workflow), executorService);
    }

    @Override
    public void indexTask(TaskSummary task) {
        try {
            long startTime = Instant.now().toEpochMilli();
            String taskId = task.getTaskId();
            TaskEntity taskEntity = objectMapper.convertValue(task, TaskEntity.class);
            LOGGER.debug("indexTask: source: {} target: {}", objectMapper.writeValueAsString(task), objectMapper.writeValueAsString(taskEntity));
            fillTaskDate(task, taskEntity);
            taskService.insertOrUpdate(taskEntity);

            long endTime = Instant.now().toEpochMilli();
            LOGGER.debug("Time taken {} for  indexing task:{} in workflow: {}", endTime - startTime, taskId, task.getWorkflowId());
        } catch (Exception e) {
            LOGGER.error("Failed to index task: {}", task.getTaskId(), e);
        }
    }

    @Override
    public CompletableFuture<Void> asyncIndexTask(TaskSummary task) {
        return CompletableFuture.runAsync(() -> indexTask(task), executorService);
    }

    @Override
    public void addTaskExecutionLogs(List<TaskExecLog> taskExecLogs) {
    }

    @Override
    public CompletableFuture<Void> asyncAddTaskExecutionLogs(List<TaskExecLog> logs) {
        return null;
    }

    @Override
    public List<TaskExecLog> getTaskExecutionLogs(String taskId) {
        return null;
    }


    @Override
    public List<Message> getMessages(String queue) {
        return null;
    }

    @Override
    public List<EventExecution> getEventExecutions(String event) {
        return null;
    }


    @Override
    public void addMessage(String queue, Message message) {
    }

    @Override
    public CompletableFuture<Void> asyncAddMessage(String queue, Message message) {
        return CompletableFuture.runAsync(() -> addMessage(queue, message), executorService);
    }

    @Override
    public void addEventExecution(EventExecution eventExecution) {
    }

    @Override
    public CompletableFuture<Void> asyncAddEventExecution(EventExecution eventExecution) {
        return null;
    }

    @Override
    public SearchResult<String> searchWorkflows(String query, String freeText, int start, int count, List<String> sort) {
        try {
            SearchResult<WorkflowEntity> workflowSearchResult = searchObjectsViaExpression(query, start, count, sort, freeText, true, WorkflowEntity.class);

            List<String> collect = new ArrayList<>();
            for (WorkflowEntity result : workflowSearchResult.getResults()) {
                collect.add(result.getWorkflowId());
            }

            return new SearchResult<>(workflowSearchResult.getTotalHits(), collect);
        } catch (Exception e) {
            throw new TransientException(e.getMessage(), e);
        }
    }

    @Override
    public SearchResult<WorkflowSummary> searchWorkflowSummary(String query, String freeText, int start, int count, List<String> sort) {
        try {
            SearchResult<WorkflowEntity> workflowSearchResult = searchObjectsViaExpression(query, start, count, sort, freeText, false, WorkflowEntity.class);
            List<WorkflowSummary> workflowSummaries = workflowSearchResult.getResults().stream()
                    .map(workflowEntity -> {
                        WorkflowSummary summary = objectMapper.convertValue(workflowEntity, WorkflowSummary.class);
                        Date startTime = workflowEntity.getStartTime();
                        Date updateTime = workflowEntity.getUpdateTime();
                        Date endTime = workflowEntity.getEndTime();

                        summary.setStartTime(tidbDateToConductorDate(startTime));
                        summary.setEndTime(tidbDateToConductorDate(endTime));
                        summary.setUpdateTime(tidbDateToConductorDate(updateTime));
                        return summary;
                    })
                    .collect(Collectors.toList());
            return new SearchResult<>(workflowSearchResult.getTotalHits(), workflowSummaries);
        } catch (Exception e) {
            throw new TransientException(e.getMessage(), e);
        }
    }

    private String tidbDateToConductorDate(Date date) {
        if (date != null) {

            return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneOffset.UTC)
                    .format(date.toInstant());
        }
        return null;
    }

    @Override
    public SearchResult<String> searchTasks(String query, String freeText, int start, int count, List<String> sort) {
        try {
            SearchResult<TaskEntity> taskSearchResult = searchObjectsViaExpression(query, start, count, sort, freeText, true, TaskEntity.class);
            List<String> collect = taskSearchResult.getResults().stream().map(TaskEntity::getTaskId).collect(Collectors.toList());
            return new SearchResult<>(taskSearchResult.getTotalHits(), collect);
        } catch (Exception e) {
            throw new TransientException(e.getMessage(), e);
        }
    }

    @Override
    public SearchResult<TaskSummary> searchTaskSummary(String query, String freeText, int start, int count, List<String> sort) {
        try {
            SearchResult<TaskEntity> taskSearchResult = searchObjectsViaExpression(query, start, count, sort, freeText, false, TaskEntity.class);
            List<TaskSummary> collect = taskSearchResult.getResults().stream()
                    .map(taskEntity -> {
                        TaskSummary summary = objectMapper.convertValue(taskEntity, TaskSummary.class);
                        Date endTime = taskEntity.getEndTime();
                        Date scheduledTime = taskEntity.getScheduledTime();
                        Date startTime = taskEntity.getStartTime();
                        Date updateTime = taskEntity.getUpdateTime();
                        summary.setStartTime(tidbDateToConductorDate(startTime));
                        summary.setScheduledTime(tidbDateToConductorDate(scheduledTime));
                        summary.setEndTime(tidbDateToConductorDate(endTime));
                        summary.setUpdateTime(tidbDateToConductorDate(updateTime));
                        return summary;
                    })
                    .collect(Collectors.toList());
            return new SearchResult<>(taskSearchResult.getTotalHits(), collect);
        } catch (Exception e) {
            throw new TransientException(e.getMessage(), e);
        }
    }


    @Override
    public void removeWorkflow(String workflowId) {
        long startTime = Instant.now().toEpochMilli();
        try {
            workflowService.deleteById(workflowId);

            long endTime = Instant.now().toEpochMilli();
            LOGGER.debug("Time taken {} for removing workflow: {}", endTime - startTime, workflowId);
        } catch (Exception e) {
            LOGGER.error("Failed to remove workflow {} from index", workflowId, e);
        }
    }

    @Override
    public CompletableFuture<Void> asyncRemoveWorkflow(String workflowId) {
        return CompletableFuture.runAsync(() -> removeWorkflow(workflowId), executorService);
    }

    @Override
    public void updateWorkflow(String workflowInstanceId, String[] keys, Object[] values) {
        try {
            if (keys.length == 0) {
                return;
            }
            if (keys.length != values.length) {
                throw new IllegalArgumentException("Number of keys and values do not match");
            }

            long startTime = Instant.now().toEpochMilli();

            Map<String, Object> source = IntStream.range(0, keys.length).boxed().collect(Collectors.toMap(i -> keys[i], i -> values[i]));
            WorkflowEntity workflowEntity = objectMapper.convertValue(source, WorkflowEntity.class);
            LOGGER.debug("updateWorkflow----keys: {}  values:  {}, workflowEntity: {}", keys, values, workflowEntity.toString());
            workflowEntity.setWorkflowId(workflowInstanceId);
            long endTime = Instant.now().toEpochMilli();
            LOGGER.debug("Time taken {} for updating workflowEntity: {}", endTime - startTime, workflowInstanceId);
        } catch (Exception e) {
            LOGGER.error("Failed to update workflow {}", workflowInstanceId, e);
        }

    }

    @Override
    public CompletableFuture<Void> asyncUpdateWorkflow(String workflowInstanceId, String[] keys, Object[] values) {
        return CompletableFuture.runAsync(() -> updateWorkflow(workflowInstanceId, keys, values), executorService);
    }

    @Override
    public void removeTask(String workflowId, String taskId) {
        long startTime = Instant.now().toEpochMilli();

        try {
            taskService.deleteById(taskId);
            long endTime = Instant.now().toEpochMilli();
            LOGGER.debug("Time taken {} for removing task:{} of workflow: {}", endTime - startTime, taskId, workflowId);
        } catch (Exception e) {
            LOGGER.error("Failed to remove task {} of workflow: {} from index", taskId, workflowId, e);
        }
    }

    @Override
    public CompletableFuture<Void> asyncRemoveTask(String workflowId, String taskId) {
        return CompletableFuture.runAsync(() -> removeTask(workflowId, taskId), executorService);
    }

    @Override
    public void updateTask(String workflowId, String taskId, String[] keys, Object[] values) {
        try {

            if (keys.length == 0) {
                return;
            }
            if (keys.length != values.length) {
                throw new IllegalArgumentException("Number of keys and values do not match");
            }

            long startTime = Instant.now().toEpochMilli();

            Map<String, Object> source = IntStream.range(0, keys.length).boxed().collect(Collectors.toMap(i -> keys[i], i -> values[i]));
            TaskEntity taskEntity = objectMapper.convertValue(source, TaskEntity.class);
            LOGGER.debug("updateTask----keys: {}  values:  {}  taskEntity: {}", keys, values, taskEntity.toString());
            taskEntity.setWorkflowId(workflowId);
            taskEntity.setTaskId(taskId);
            boolean updated = taskService.updateById(taskEntity);
            long endTime = Instant.now().toEpochMilli();
            LOGGER.debug("Time taken {} for updating taskEntity: {} of workflow: {}", endTime - startTime, taskId, workflowId);
        } catch (Exception e) {
            LOGGER.error("Failed to update task: {} of workflow: {}", taskId, workflowId, e);
        }

    }

    @Override
    public CompletableFuture<Void> asyncUpdateTask(String workflowId, String taskId, String[] keys, Object[] values) {
        return CompletableFuture.runAsync(() -> updateTask(workflowId, taskId, keys, values), executorService);
    }

    @Override
    public String get(String workflowInstanceId, String fieldToGet) {
        try {
            WorkflowEntity workflowEntity = workflowService.selectById(workflowInstanceId);
            if (workflowEntity != null) {
                return workflowEntity.getRawJSON();
            }
            return null;

        } catch (Exception e) {
            LOGGER.error("Unable to get WorkflowEntity: {} from Tidb", workflowInstanceId, e);
            return null;
        }
    }

    @Override
    public String getTask(String taskId, String fieldToGet) {
        try {
            TaskEntity taskEntity = taskService.selectById(taskId);
            if (taskEntity != null) {
                return taskEntity.getRawJSON();
            }
            return null;

        } catch (Exception e) {
            LOGGER.error("Unable to get TaskEntity: {} from Tidb", taskId, e);
            return null;
        }
    }

    private <T> void buildSort(List<String> sortOptions, EntityWrapper<T> wrapper) {
        for (String sortOption : sortOptions) {
            String field;
            int index = sortOption.indexOf(":");
            if (index > 0) {
                field = sortOption.substring(0, index);
                String value = sortOption.substring(index + 1).toLowerCase();
                wrapper.orderBy(field, "asc".equals(value.toLowerCase()));
            } else {
                wrapper.orderBy(sortOption, true);
            }
        }
    }

    private <T> EntityWrapper<T> buildQuery(String query, String freeTextQuery, EntityWrapper<T> wrapper) {
        if (StringUtils.isNotEmpty(freeTextQuery) && !"*".equals(freeTextQuery)) {
            wrapper.eq("correlationId", freeTextQuery);
        }
        String[] queryList = query.split(" AND ");
        for (String queryItem : queryList) {
            queryItem = queryItem.trim();

            if (queryItem.contains(ComparisonOp.Operators.GREATER_THAN.value())) {
                String[] split = queryItem.split(ComparisonOp.Operators.GREATER_THAN.value());
                String field = split[0].trim();
                String value = split[1].trim();
                if ("startTime".equals(field) || "endTime".equals(field) || "updateTime".equals(field) || "scheduledTime".equals(field)) {
                    value = convertToTidbDate(value);
                }
                wrapper.ge(field, value);
            } else if (queryItem.contains(ComparisonOp.Operators.LESS_THAN.value())) {
                String[] split = queryItem.split(ComparisonOp.Operators.LESS_THAN.value());
                String field = split[0].trim();
                String value = split[1].trim();
                if ("startTime".equals(field) || "endTime".equals(field) || "updateTime".equals(field) || "scheduledTime".equals(field)) {
                    value = convertToTidbDate(value);
                }
                wrapper.le(field, value);
            } else if (queryItem.contains(ComparisonOp.Operators.IN.value())) {
                String[] inList = queryItem.split(" " + ComparisonOp.Operators.IN.value() + " ");
                String[] split = inList[1].trim().replace("(", "").replace(")", "").split(",");
                wrapper.in(inList[0].trim(), split);
            } else if (queryItem.contains(ComparisonOp.Operators.EQUALS.value())) {
                String[] split = queryItem.split(ComparisonOp.Operators.EQUALS.value());
                wrapper.eq(split[0].trim(), split[1].trim().replace("'", "").replace("\"", ""));
            }
        }
        return wrapper;
    }

    private String convertToTidbDate(String date) {
        if (StringUtils.isNotEmpty(date)) {
            LocalDateTime dateTime = Instant.ofEpochMilli(Long.parseLong(date))
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();

            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        }
        return null;
    }


    private <T> SearchResult<T> searchObjectsViaExpression(String structuredQuery, int start, int size, List<String> sortOptions, String freeTextQuery, boolean idOnly, Class<T> clazz) throws ParserException, IOException {
        EntityWrapper<T> wrapper = new EntityWrapper<>();

        wrapper = buildQuery(structuredQuery, freeTextQuery, wrapper);
        if (!CollectionUtils.isEmpty(sortOptions)) {
            buildSort(sortOptions, wrapper);
        }
        List<T> result;
        int pageNum = start / size + 1;
        long total = 0;
        if (clazz == WorkflowEntity.class) {
            Page<WorkflowEntity> page = new Page<>(pageNum, size);
            Page<WorkflowEntity> workflowEntityPage = workflowService.selectPage(page, (EntityWrapper<WorkflowEntity>) wrapper);
            result = (List<T>) workflowEntityPage.getRecords();
            total = workflowEntityPage.getTotal();
        } else {
            Page<TaskEntity> page = new Page<>(pageNum, size);
            Page<TaskEntity> taskEntityPage = taskService.selectPage(page, (EntityWrapper<TaskEntity>) wrapper);
            result = (List<T>) taskEntityPage.getRecords();
            total = taskEntityPage.getTotal();
        }
        return new SearchResult<T>(total, result);
    }


    @Override
    public List<String> searchArchivableWorkflows(String indexName, long archiveTtlDays) {
        return new ArrayList<>();
    }

    @Override
    public long getWorkflowCount(String query, String freeText) {
        return 0;
    }


}
