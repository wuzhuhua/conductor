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
package com.netflix.conductor.core.dal;

import java.io.IOException;
import java.text.ParseException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import javax.annotation.PreDestroy;

import com.netflix.conductor.common.metadata.tasks.*;
import com.netflix.conductor.core.journeyInsight.JourneyInsightEntry;
import com.netflix.conductor.core.journeyInsight.JourneyInsightWriter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.netflix.conductor.common.metadata.events.EventExecution;
import com.netflix.conductor.common.run.SearchResult;
import com.netflix.conductor.common.run.TaskSummary;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.common.run.WorkflowSummary;
import com.netflix.conductor.common.utils.ExternalPayloadStorage;
import com.netflix.conductor.core.config.ConductorProperties;
import com.netflix.conductor.core.events.queue.Message;
import com.netflix.conductor.core.exception.NotFoundException;
import com.netflix.conductor.core.exception.TerminateWorkflowException;
import com.netflix.conductor.core.exception.TransientException;
import com.netflix.conductor.core.utils.ExternalPayloadStorageUtils;
import com.netflix.conductor.core.utils.QueueUtils;
import com.netflix.conductor.dao.*;
import com.netflix.conductor.metrics.Monitors;
import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.CollectionUtils;

import static com.netflix.conductor.core.execution.tasks.Wait.DURATION_INPUT;
import static com.netflix.conductor.core.execution.tasks.Wait.UNTIL_INPUT;
import static com.netflix.conductor.core.journeyInsight.constant.WorkflowTaskCommonInputKey.RECEIVED_AT;
import static com.netflix.conductor.core.journeyInsight.constant.WorkflowTaskCommonInputKey.REQUEST_UUID;
import static com.netflix.conductor.core.utils.DateTimeUtils.parseDate;
import static com.netflix.conductor.core.utils.DateTimeUtils.parseDuration;
import static com.netflix.conductor.core.utils.Utils.DECIDER_QUEUE;

/**
 * Service that acts as a facade for accessing execution data from the {@link ExecutionDAO}, {@link
 * RateLimitingDAO} and {@link IndexDAO} storage layers
 */
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
@Component
public class ExecutionDAOFacade {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionDAOFacade.class);

    private static final String STATUS_FIELD = "status";
    private static final String ARCHIVED_FIELD = "archived";
    private static final String RAW_JSON_FIELD = "rawJSON";

    private final ExecutionDAO executionDAO;
    private final QueueDAO queueDAO;
    private final IndexDAO indexDAO;
    private final RateLimitingDAO rateLimitingDao;
    private final CountLimitingDAO countLimitingDAO;

    private final ConcurrentExecutionLimitDAO concurrentExecutionLimitDAO;
    private final PollDataDAO pollDataDAO;
    private final ObjectMapper objectMapper;
    private final ConductorProperties properties;
    private final ExternalPayloadStorageUtils externalPayloadStorageUtils;

    private final ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;
    private final JourneyInsightWriter jiWriter;

    public ExecutionDAOFacade(
            ExecutionDAO executionDAO,
            QueueDAO queueDAO,
            IndexDAO indexDAO,
            RateLimitingDAO rateLimitingDao,
            CountLimitingDAO countLimitingDAO,
            ConcurrentExecutionLimitDAO concurrentExecutionLimitDAO,
            PollDataDAO pollDataDAO,
            ObjectMapper objectMapper,
            ConductorProperties properties,
            ExternalPayloadStorageUtils externalPayloadStorageUtils, JourneyInsightWriter jiWriter) {
        this.executionDAO = executionDAO;
        this.queueDAO = queueDAO;
        this.indexDAO = indexDAO;
        this.rateLimitingDao = rateLimitingDao;
        this.countLimitingDAO = countLimitingDAO;
        this.concurrentExecutionLimitDAO = concurrentExecutionLimitDAO;
        this.pollDataDAO = pollDataDAO;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.externalPayloadStorageUtils = externalPayloadStorageUtils;
        this.jiWriter = jiWriter;
        this.scheduledThreadPoolExecutor =
                new ScheduledThreadPoolExecutor(
                        4,
                        (runnable, executor) -> {
                            LOGGER.warn(
                                    "Request {} to delay updating index dropped in executor {}",
                                    runnable,
                                    executor);
                            Monitors.recordDiscardedIndexingCount("delayQueue");
                        });
        this.scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);
    }

    @PreDestroy
    public void shutdownExecutorService() {
        try {
            LOGGER.info("Gracefully shutdown executor service");
            scheduledThreadPoolExecutor.shutdown();
            if (scheduledThreadPoolExecutor.awaitTermination(
                    properties.getAsyncUpdateDelay().getSeconds(), TimeUnit.SECONDS)) {
                LOGGER.debug("tasks completed, shutting down");
            } else {
                LOGGER.warn(
                        "Forcing shutdown after waiting for {} seconds",
                        properties.getAsyncUpdateDelay());
                scheduledThreadPoolExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            LOGGER.warn(
                    "Shutdown interrupted, invoking shutdownNow on scheduledThreadPoolExecutor for delay queue");
            scheduledThreadPoolExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public WorkflowModel getWorkflowModel(String workflowId, boolean includeTasks) {
        WorkflowModel workflowModel = getWorkflowModelFromDataStore(workflowId, includeTasks);
//        if (workflowModel == null) {
//            queueDAO.remove(DECIDER_QUEUE, workflowId);
//        }
        populateWorkflowAndTaskPayloadData(workflowModel);
        return workflowModel;
    }

    /**
     * Fetches the {@link Workflow} object from the data store given the id. Attempts to fetch from
     * {@link ExecutionDAO} first, if not found, attempts to fetch from {@link IndexDAO}.
     *
     * @param workflowId   the id of the workflow to be fetched
     * @param includeTasks if true, fetches the {@link Task} data in the workflow.
     * @return the {@link Workflow} object
     * @throws NotFoundException  no such {@link Workflow} is found.
     * @throws TransientException parsing the {@link Workflow} object fails.
     */
    public Workflow getWorkflow(String workflowId, boolean includeTasks) {
        return getWorkflowModelFromDataStore(workflowId, includeTasks).toWorkflow();
    }

    private WorkflowModel getWorkflowModelFromDataStore(String workflowId, boolean includeTasks) {
        WorkflowModel workflow = executionDAO.getWorkflow(workflowId, includeTasks);
        if (workflow == null) {
            LOGGER.debug("Workflow {} not found in executionDAO, checking indexDAO", workflowId);
            String json = indexDAO.get(workflowId, RAW_JSON_FIELD);
            if (json == null) {
                String errorMsg = String.format("No such workflow found by id: %s", workflowId);
                LOGGER.error(errorMsg);
                throw new NotFoundException(errorMsg);
            }

            try {
                workflow = objectMapper.readValue(json, WorkflowModel.class);
                if (!includeTasks) {
                    workflow.getTasks().clear();
                } else if (!workflow.getStatus().isTerminal() && properties.getEnableRemoveRedisKey() && workflow.getIsArchived()) {
                    List<String> taskIds = executionDAO.getTasksIdForWorkflow(workflowId);
                    if (!CollectionUtils.isEmpty(taskIds)) {
                        List<TaskModel> taskModels = taskIds.stream().map(this::getTaskModel)
                                .filter(Objects::nonNull)
                                .sorted(Comparator.comparingInt(TaskModel::getSeq)).collect(Collectors.toList());
                        workflow.setTasks(taskModels);
                    }
                }
            } catch (Exception e) {
                String errorMsg = String.format("Error reading workflow: %s", workflowId);
                LOGGER.error(errorMsg);
                throw new TransientException(errorMsg, e);
            }
        } else if (workflow.getIsArchived() && properties.getEnableRemoveRedisKey()) {
            List<String> taskIds = executionDAO.getTasksIdForWorkflow(workflowId);
            if (!CollectionUtils.isEmpty(taskIds)) {
                List<TaskModel> taskModels = taskIds.stream().map(this::getTaskModel).filter(Objects::nonNull).sorted(Comparator.comparingInt(TaskModel::getSeq)).collect(Collectors.toList());
                workflow.setTasks(taskModels);
            }
        }
        return workflow;
    }

    /**
     * Retrieve all running workflow IDs with the given correlation ID and workflow name.
     *
     * @param workflowName  the name of the workflow type to be queried
     * @param correlationId the correlation ID to search for
     * @return a list of running workflow IDs that match the correlation ID and workflow name
     */
    public List<String> getRunningWorkflowIdsByCorrelationId(String workflowName, String correlationId) {
        if (!executionDAO.canSearchAcrossWorkflows()) {
            String query = "correlationId='" + correlationId + "' AND workflowType='" + workflowName + "' AND status IN (RUNNING)";
            return indexDAO.searchWorkflowSummary(query, "*", 0, 1000, null).getResults().stream()
                    .filter(Objects::nonNull)
                    .map(WorkflowSummary::getWorkflowId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        throw new UnsupportedOperationException(
                "Not implemented yet");

    }

    /**
     * Retrieve all workflow executions with the given correlationId and workflow type Uses the
     * {@link IndexDAO} to search across workflows if the {@link ExecutionDAO} cannot perform
     * searches across workflows.
     *
     * @param workflowName, workflow type to be queried
     * @param correlationId the correlation id to be queried
     * @param includeTasks  if true, fetches the {@link Task} data within the workflows
     * @return the list of {@link Workflow} executions matching the correlationId
     */
    public List<Workflow> getWorkflowsByCorrelationId(
            String workflowName, String correlationId, boolean includeTasks) {
        if (!executionDAO.canSearchAcrossWorkflows()) {
            String query =
                    "correlationId='" + correlationId + "' AND workflowType='" + workflowName + "'";

            return indexDAO.searchWorkflowSummary(query, "*", 0, 1000, null).getResults().stream()
                    .filter(Objects::nonNull).map(workflowSummary -> workflowSummary.getWorkflowId()).filter(wfId -> wfId != null && !wfId.isEmpty())
                    .parallel()
                    .map(
                            workflowId -> {
                                try {
                                    return getWorkflow(workflowId, includeTasks);
                                } catch (NotFoundException e) {
                                    // This might happen when the workflow archival failed and the
                                    // workflow was removed from primary datastore
                                    LOGGER.error(
                                            "Error getting the workflow: {}  for correlationId: {} from datastore/index",
                                            workflowId,
                                            correlationId,
                                            e);
                                    return null;
                                }
                            })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        return executionDAO
                .getWorkflowsByCorrelationId(workflowName, correlationId, includeTasks)
                .stream()
                .map(WorkflowModel::toWorkflow)
                .collect(Collectors.toList());
    }

    public TaskSummary getFirstRunningWorkflowIdByCorrelationId(String workflowName, String correlationId, String referenceTaskName) {
        String[] workFlowTaskId=executionDAO.getTaskCorrIdMappings(workflowName,referenceTaskName,correlationId);
        TaskSummary taskSummary=new TaskSummary();
        if(workFlowTaskId !=null && workFlowTaskId.length>1){
            taskSummary.setTaskId(workFlowTaskId[0]);
            taskSummary.setWorkflowId(workFlowTaskId[1]);
        }
//        else{
//            String query = "taskType IN (HUMAN,WAIT) AND status='IN_PROGRESS' AND correlationId='" + correlationId + "' AND workflowType='" + workflowName + "' AND taskRefName='" + referenceTaskName + "'";
//            List<String> sort = new ArrayList<>();
//            sort.add("startTime:ASC");
//            List<TaskSummary> results = indexDAO.searchTaskSummary(query, "*", 0, 1, sort).getResults();
//            if (results == null || results.isEmpty()) {
//                return new TaskSummary();
//            }
//            TaskSummary firstResult = results.get(0);
//            if (firstResult == null || firstResult.getWorkflowId() == null) {
//                return new TaskSummary();
//            }
//            return firstResult;
//        }
        return taskSummary;
    }

    public List<Workflow> getWorkflowsByName(String workflowName, Long startTime, Long endTime) {
        return executionDAO.getWorkflowsByType(workflowName, startTime, endTime).stream()
                .map(WorkflowModel::toWorkflow)
                .collect(Collectors.toList());
    }

    public List<Workflow> getPendingWorkflowsByName(String workflowName, int version) {
        return executionDAO.getPendingWorkflowsByType(workflowName, version).stream()
                .map(WorkflowModel::toWorkflow)
                .collect(Collectors.toList());
    }

    public List<String> getRunningWorkflowIds(String workflowName, int version) {
        return executionDAO.getRunningWorkflowIds(workflowName, version);
    }


    public void removePendingWorkflowAsync(String workflowName) {
        List<String> runningWorkflowIds = executionDAO.getRunningWorkflowIds(workflowName, 0);
        for (String runningWorkflowId : runningWorkflowIds) {
            removeWorkflow(runningWorkflowId, true);
        }
    }

    public long getPendingWorkflowCount(String workflowName) {
        return executionDAO.getPendingWorkflowCount(workflowName);
    }

    /**
     * Creates a new workflow in the data store
     *
     * @param workflowModel the workflow to be created
     * @return the id of the created workflow
     */
    public String createWorkflow(WorkflowModel workflowModel) {
        externalizeWorkflowData(workflowModel);
        executionDAO.createWorkflow(workflowModel);
        // Add to decider queue
        queueDAO.push(
                DECIDER_QUEUE,
                workflowModel.getWorkflowId(),
                workflowModel.getPriority(),
                properties.getWorkflowOffsetTimeout().getSeconds());
        try {
            JourneyInsightEntry entry = JourneyInsightEntry.fromWorkflowModel(workflowModel);
            jiWriter.writeJourneyInsightEntryAsync(entry);
        }catch (Exception e){
            LOGGER.error("Error happens when write journey insight. WorkflowModel: {}", workflowModel, e);
        }
        if (properties.isAsyncIndexingEnabled()) {
            indexDAO.asyncIndexWorkflow(new WorkflowSummary(workflowModel.toWorkflow()));
        } else {
            indexDAO.indexWorkflow(new WorkflowSummary(workflowModel.toWorkflow()));
        }
        return workflowModel.getWorkflowId();
    }

    private void scheduledRemoveRedisWhenUpdateTask(TaskModel task) {
        if (TaskType.TASK_TYPE_WAIT.equals(task.getTaskType()) && TaskModel.Status.IN_PROGRESS.equals(task.getStatus())) {
            String duration = Optional.ofNullable(task.getInputData().get(DURATION_INPUT)).orElse("").toString();
            String until = Optional.ofNullable(task.getInputData().get(UNTIL_INPUT)).orElse("").toString();
            long seconds = 0;
            if (StringUtils.isNotBlank(duration)) {
                Duration timeDuration = parseDuration(duration);
                seconds = timeDuration.getSeconds();
            } else if (StringUtils.isNotBlank(until)) {
                try {
                    Date expiryDate = parseDate(until);
                    long timeInMS = expiryDate.getTime();
                    long now = System.currentTimeMillis();
                    seconds = (timeInMS - now) / 1000;
                } catch (ParseException parseException) {
                    LOGGER.error("Error parse task input until:{}", until, parseException);
                }
            }
            if (seconds > properties.getTtlRedisKeyExpire().getSeconds()) {
                try {
                    WorkflowModel workflow = executionDAO.getWorkflow(task.getWorkflowInstanceId());
                    if (workflow != null) {
                        workflow.setIsArchived(true);
                        indexDAO.updateWorkflow(
                                workflow.getWorkflowId(),
                                new String[]{RAW_JSON_FIELD, ARCHIVED_FIELD},
                                new Object[]{objectMapper.writeValueAsString(workflow), true});
                    }
                    final List<String> taskIds = executionDAO.getTasksIdForWorkflow(task.getWorkflowInstanceId());
                    if (!taskIds.isEmpty()) {
                        List<TaskModel> tasks = taskIds.stream().map(this::getTaskModelFromRedis).filter(Objects::nonNull).collect(Collectors.toList());
                        tasks.forEach(taskModel -> {
                            try {
                                taskModel.setIsArchived(true);
                                indexDAO.updateTask(
                                        task.getWorkflowInstanceId(),
                                        taskModel.getTaskId(),
                                        new String[]{RAW_JSON_FIELD, ARCHIVED_FIELD},
                                        new Object[]{objectMapper.writeValueAsString(taskModel), true});
                            } catch (Exception e) {
                                taskIds.remove(taskModel.getTaskId());
                                LOGGER.error(String.format(
                                                "Task %s of workflow %s can not be serialized to json",
                                                taskModel.getTaskId(), task.getWorkflowInstanceId()),
                                        e);
                            }
                        });
                    }
                    executionDAO.removeWorkflowAndTask(task.getWorkflowInstanceId(),taskIds, (int) properties.getTtlRedisKeyExpire().getSeconds());
                } catch (JsonProcessingException e) {
                    LOGGER.error("Error Archiving workflow: {} ", task.getWorkflowInstanceId(), e);
                }

            }
        }
    }

    private void externalizeTaskData(TaskModel taskModel) {
        externalPayloadStorageUtils.verifyAndUpload(
                taskModel, ExternalPayloadStorage.PayloadType.TASK_INPUT);
        externalPayloadStorageUtils.verifyAndUpload(
                taskModel, ExternalPayloadStorage.PayloadType.TASK_OUTPUT);
    }

    private void externalizeWorkflowData(WorkflowModel workflowModel) {
        externalPayloadStorageUtils.verifyAndUpload(
                workflowModel, ExternalPayloadStorage.PayloadType.WORKFLOW_INPUT);
        externalPayloadStorageUtils.verifyAndUpload(
                workflowModel, ExternalPayloadStorage.PayloadType.WORKFLOW_OUTPUT);
    }

    /**
     * Updates the given workflow in the data store
     *
     * @param workflowModel the workflow tp be updated
     * @return the id of the updated workflow
     */
    public String updateWorkflow(WorkflowModel workflowModel) {
        workflowModel.setUpdatedTime(System.currentTimeMillis());
        if (workflowModel.getStatus().isTerminal()) {
            workflowModel.setEndTime(System.currentTimeMillis());
        }
        externalizeWorkflowData(workflowModel);
        WorkflowModel workflowModelNew=getWorkflowModelFromDataStore(workflowModel.getWorkflowId(),false);
        if(workflowModel.getStatus().isTerminal()){
            List<TaskModel> taskModels = getWorkflowModelFromDataStore(workflowModel.getWorkflowId(),true).getTasks();
            try {
                JourneyInsightEntry entry = JourneyInsightEntry.fromWorkflowModel(workflowModel, taskModels);
                jiWriter.writeJourneyInsightEntryAsync(entry);
            }catch (Exception e){
                LOGGER.error("Error happens when write journey insight. WorkflowModel: {}", workflowModel, e);
            }
        }
        if(workflowModel.getIsArchived() || workflowModelNew ==null || workflowModelNew.getIsArchived()){
            try {
                workflowModel.setIsArchived(true);
                indexDAO.updateWorkflow(
                        workflowModel.getWorkflowId(),
                        new String[]{RAW_JSON_FIELD, ARCHIVED_FIELD,"status","output"},
                        new Object[]{objectMapper.writeValueAsString(workflowModel), true,workflowModel.getStatus(),objectMapper.writeValueAsString(workflowModel.getOutput())});
            } catch (JsonProcessingException e) {
                LOGGER.error("Error Archiving workflow: {} ", workflowModel.getWorkflowId(), e);
            }
            return workflowModel.getWorkflowId();
        }
        executionDAO.updateWorkflow(workflowModel);
        if (properties.isAsyncIndexingEnabled()) {
            if (workflowModel.getStatus().isTerminal()
                    && workflowModel.getEndTime() - workflowModel.getCreateTime()
                    < properties.getAsyncUpdateShortRunningWorkflowDuration().toMillis()) {
                final String workflowId = workflowModel.getWorkflowId();
                DelayWorkflowUpdate delayWorkflowUpdate = new DelayWorkflowUpdate(workflowId);
                LOGGER.debug(
                        "Delayed updating workflow: {} in the index by {} seconds",
                        workflowId,
                        properties.getAsyncUpdateDelay());
                scheduledThreadPoolExecutor.schedule(
                        delayWorkflowUpdate,
                        properties.getAsyncUpdateDelay().getSeconds(),
                        TimeUnit.SECONDS);
                Monitors.recordWorkerQueueSize(
                        "delayQueue", scheduledThreadPoolExecutor.getQueue().size());
            } else {
                indexDAO.asyncIndexWorkflow(new WorkflowSummary(workflowModel.toWorkflow()));
            }
            if (workflowModel.getStatus().isTerminal()) {
                workflowModel
                        .getTasks()
                        .forEach(
                                taskModel ->
                                        indexDAO.asyncIndexTask(
                                                new TaskSummary(taskModel.toTask())));
            }
        } else {
            indexDAO.indexWorkflow(new WorkflowSummary(workflowModel.toWorkflow()));
        }
        return workflowModel.getWorkflowId();
    }

    public void updateWorkflowIndex(WorkflowModel workflowModel) {
        WorkflowModel w = executionDAO.getWorkflow(workflowModel.getWorkflowId());
        if (w == null) {
            try {
                indexDAO.updateWorkflow(
                        workflowModel.getWorkflowId(),
                        new String[]{RAW_JSON_FIELD, ARCHIVED_FIELD},
                        new Object[]{objectMapper.writeValueAsString(workflowModel), true});
            } catch (JsonProcessingException e) {
                LOGGER.info("Error parse task input until:{}", workflowModel.getWorkflowId(), e);
            }
        } else {
            executionDAO.updateWorkflow(workflowModel);
        }
    }

    public boolean exceedsExecCountLimitPerEntity(
            String entityID, String workflowDefName, Integer maxExecCount, Integer mode) {
        return countLimitingDAO.exceedsExecCountLimit(entityID, workflowDefName, maxExecCount, mode);
    }

    public boolean exceedsExecCountLimitPerEntity(
            String entityID, String workflowDefName, Integer maxExecCount, OffsetDateTime stopInEffectAfter, Integer mode) {
        return countLimitingDAO.exceedsExecCountLimit(entityID, workflowDefName, maxExecCount, stopInEffectAfter, mode);
    }

    public boolean exceedsExecCountLimitPerEntity(
            String entityID, String workflowDefName, Integer maxExecCount, Integer execLimitIntervalInSeconds, Integer mode) {
        return countLimitingDAO.exceedsExecCountLimit(entityID, workflowDefName, maxExecCount, execLimitIntervalInSeconds, mode);
    }

    public boolean resetAllExecCountLimit(String entityID, String workflowDefName) {
        return countLimitingDAO.resetAllExecCountLimit(entityID, workflowDefName);
    }

    public void removeFromPendingWorkflow(String workflowType, String workflowId) {
        executionDAO.removeFromPendingWorkflow(workflowType, workflowId);
    }

    public void removeWorkflowFromDeciderQueue(String workflowId) {
        queueDAO.remove(DECIDER_QUEUE, workflowId);
    }

    public void updateWorkflowIndex(String workflowId) {
        try {
            WorkflowModel workflow = getWorkflowModelFromDataStore(workflowId, false);
            if (workflow == null || workflow.getStatus().isTerminal()) {
                //update index
                indexDAO.updateWorkflow(
                        workflowId,
                        new String[]{STATUS_FIELD},
                        new Object[]{"COMPLETED"});
            }
        } catch (Exception e) {
            indexDAO.updateWorkflow(
                    workflowId,
                    new String[]{STATUS_FIELD},
                    new Object[]{"COMPLETED"});
        }

    }

    public Workflow getWorkflowFromRedis(String workflowId, boolean includeTasks) {
        WorkflowModel workflow = executionDAO.getWorkflow(workflowId, includeTasks);
        if (workflow != null) {
            return workflow.toWorkflow();
        }
        return null;
    }

    /**
     * Removes the workflow from the data store.
     *
     * @param workflowId      the id of the workflow to be removed
     * @param archiveWorkflow if true, the workflow and associated tasks will be archived in the
     *                        {@link IndexDAO} after removal from {@link ExecutionDAO}.
     */
    public void removeWorkflow(String workflowId, boolean archiveWorkflow) {
        WorkflowModel workflow = getWorkflowModelFromDataStore(workflowId, true);
        //get all task not only in progress here has issue
        if(!executionDAO.removeWorkflow(workflowId)){
            executionDAO.removeWorkflowRef(workflow);
            for (TaskModel task : workflow.getTasks()) {
                if(!executionDAO.removeTask(task.getTaskId())){
                    executionDAO.removeTaskMappings(task);
                }
            }
        }
        try {
            removeWorkflowIndex(workflow, archiveWorkflow);
        } catch (JsonProcessingException e) {
            throw new TransientException("Workflow can not be serialized to json", e);
        }

        workflow.getTasks()
                .forEach(
                        task -> {
                            try {
                                removeTaskIndex(workflow, task, archiveWorkflow);
                            } catch (JsonProcessingException e) {
                                throw new TransientException(
                                        String.format(
                                                "Task %s of workflow %s can not be serialized to json",
                                                task.getTaskId(), workflow.getWorkflowId()),
                                        e);
                            }

                            try {
                                queueDAO.remove(QueueUtils.getQueueName(task), task.getTaskId());
                            } catch (Exception e) {
                                LOGGER.info(
                                        "Error removing task: {} of workflow: {} from {} queue",
                                        workflowId,
                                        task.getTaskId(),
                                        QueueUtils.getQueueName(task),
                                        e);
                            }
                        });

        try {
            queueDAO.remove(DECIDER_QUEUE, workflowId);
        } catch (Exception e) {
            LOGGER.info("Error removing workflow: {} from decider queue", workflowId, e);
        }
    }

    private void removeWorkflowIndex(WorkflowModel workflow, boolean archiveWorkflow)
            throws JsonProcessingException {
        if (archiveWorkflow) {
            if (workflow.getStatus().isTerminal()) {
                // Only allow archival if workflow is in terminal state
                // DO NOT archive async, since if archival errors out, workflow data will be lost
                indexDAO.updateWorkflow(
                        workflow.getWorkflowId(),
                        new String[]{RAW_JSON_FIELD, ARCHIVED_FIELD},
                        new Object[]{objectMapper.writeValueAsString(workflow), true});
            } else {
                throw new IllegalArgumentException(
                        String.format(
                                "Cannot archive workflow: %s with status: %s",
                                workflow.getWorkflowId(), workflow.getStatus()));
            }
        } else {
            // Not archiving, also remove workflow from index
            indexDAO.asyncRemoveWorkflow(workflow.getWorkflowId());
        }
    }

    public void removeWorkflowWithExpiry(
            String workflowId, boolean archiveWorkflow, int ttlSeconds) {
        try {
            WorkflowModel workflow = getWorkflowModelFromDataStore(workflowId, true);

            removeWorkflowIndex(workflow, archiveWorkflow);
            // remove workflow from DAO with TTL
            executionDAO.removeWorkflowWithExpiry(workflowId, ttlSeconds);
        } catch (Exception e) {
            Monitors.recordDaoError("executionDao", "removeWorkflow");
            throw new TransientException("Error removing workflow: " + workflowId, e);
        }
    }

    /**
     * Reset the workflow state by removing from the {@link ExecutionDAO} and removing this workflow
     * from the {@link IndexDAO}.
     *
     * @param workflowId the workflow id to be reset
     */
    public void resetWorkflow(String workflowId) {
        if(!executionDAO.removeWorkflow(workflowId)){
            WorkflowModel workflow=getWorkflowModelFromDataStore(workflowId, true);
            executionDAO.removeWorkflowRef(workflow);
            for (TaskModel task : workflow.getTasks()) {
                if(!executionDAO.removeTask(task.getTaskId())){
                    executionDAO.removeTaskMappings(task);
                }
            }
        }
        try {
            if (properties.isAsyncIndexingEnabled()) {
                indexDAO.asyncRemoveWorkflow(workflowId);
            } else {
                indexDAO.removeWorkflow(workflowId);
            }
        } catch (Exception e) {
            throw new TransientException("Error resetting workflow state: " + workflowId, e);
        }
    }

    public List<TaskModel> createTasks(List<TaskModel> tasks) {
        tasks.forEach(this::externalizeTaskData);
        tasks.forEach(this::addInsights);
        return executionDAO.createTasks(tasks);
    }

    public List<Task> getTasksForWorkflow(String workflowId) {
        List<Task> tasks = executionDAO.getTasksForWorkflow(workflowId).stream()
                .map(TaskModel::toTask)
                .collect(Collectors.toList());
        //TODO null for task because redis del
        if (tasks.isEmpty() && properties.getEnableRemoveRedisKey()) {
            WorkflowModel workflowModel = getWorkflowModelFromDataStore(workflowId, true);
            tasks = workflowModel.toWorkflow().getTasks();
        }
        return tasks;
    }

    public TaskModel getTaskModel(String taskId) {
        TaskModel taskModel = getTaskFromDatastore(taskId);
        if (taskModel != null) {
            populateTaskData(taskModel);
        }
        return taskModel;
    }

    private TaskModel getTaskModelFromRedis(String taskId) {
        TaskModel taskModel = executionDAO.getTask(taskId);
        if (taskModel != null) {
            populateTaskData(taskModel);
        }
        return taskModel;
    }

    public Task getTask(String taskId) {
        TaskModel taskModel = getTaskFromDatastore(taskId);
        if (taskModel != null) {
            return taskModel.toTask();
        }
        return null;
    }

    private TaskModel getTaskFromDatastore(String taskId) {
        TaskModel taskModel = executionDAO.getTask(taskId);
        if (taskModel == null && properties.getEnableRemoveRedisKey()) {
            LOGGER.debug("Task {} not found in executionDAO, checking indexDAO", taskId);
            String json = indexDAO.getTask(taskId, RAW_JSON_FIELD);
            if (json == null) {
                return null;
            }
            try {
                taskModel = objectMapper.readValue(json, TaskModel.class);
            } catch (IOException e) {
                return null;
            }
        }
        return taskModel;
    }

    public List<Task> getTasksByName(String taskName, String startKey, int count) {
        List<Task> tasks = executionDAO.getTasks(taskName, startKey, count).stream()
                .map(TaskModel::toTask)
                .collect(Collectors.toList());
        if (tasks.isEmpty() && properties.getEnableRemoveRedisKey()) {
            tasks = getTaskFromIndex(taskName, startKey, count, "taskDefName");
        }

        return tasks;
    }

    public List<Task> getPendingTasksForTaskType(String taskType) {
        List<Task> tasks = executionDAO.getPendingTasksForTaskType(taskType).stream()
                .map(TaskModel::toTask)
                .collect(Collectors.toList());
        if (tasks.isEmpty() && properties.getEnableRemoveRedisKey()) {
            tasks = getTaskFromIndex(taskType, "*", 1000, "taskType");
        }
        return tasks;
    }

    private List<Task> getTaskFromIndex(String taskName, String startKey, int count, String searchKey) {
        String query = searchKey + "='" + taskName + "'";
        List<String> results = indexDAO.searchTasks(query, startKey, 0, count, null).getResults();
        return results.stream()
                .parallel()
                .map(
                        task -> {
                            try {
                                return getTask(task);
                            } catch (Exception e) {
                                LOGGER.error("Error fetching task by id: {}", task, e);
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public long getInProgressTaskCount(String taskDefName) {
        return executionDAO.getInProgressTaskCount(taskDefName);
    }

    /**
     * Sets the update time for the task. Sets the end time for the task (if task is in terminal
     * state and end time is not set). Updates the task in the {@link ExecutionDAO} first, then
     * stores it in the {@link IndexDAO}.
     *
     * @param taskModel the task to be updated in the data store
     * @throws TransientException                                         if the {@link IndexDAO} or {@link ExecutionDAO} operations fail.
     * @throws com.netflix.conductor.core.exception.NonTransientException if the externalization of
     *                                                                    payload fails.
     */
    public void updateTask(TaskModel taskModel) {
        if (taskModel.getStatus() != null) {
            if (!taskModel.getStatus().isTerminal()) {
                taskModel.setUpdateTime(System.currentTimeMillis());
                //TODO add redis
                if (TaskType.HUMAN.toString().equals(taskModel.getTaskType()) || TaskType.WAIT.toString().equals(taskModel.getTaskType())) {
                    executionDAO.addTaskCorrIdMappings(taskModel);
                }
            }
            if (taskModel.getStatus().isTerminal()) {
                if (taskModel.getEndTime() == 0) {
                    taskModel.setEndTime(System.currentTimeMillis());
                }
                if (taskModel.getUpdateTime() == 0) {
                    taskModel.setUpdateTime(System.currentTimeMillis());
                }
                //TODO remove redis
                if (TaskType.HUMAN.toString().equals(taskModel.getTaskType()) || TaskType.WAIT.toString().equals(taskModel.getTaskType())) {
                    executionDAO.removeTaskCorrIdMappings(taskModel);
                }
            }
        }
        externalizeTaskData(taskModel);
        addInsights(taskModel);
        TaskModel t=getTaskFromDatastore(taskModel.getTaskId());
        if(taskModel.getIsArchived() || (t!=null && t.getIsArchived())){
//            if(taskModel.getStatus().isTerminal()){
//                executionDAO.removeTaskMappings(taskModel);
//            }
            try {
                taskModel.setIsArchived(true);
                indexDAO.updateTask(
                        taskModel.getWorkflowInstanceId(),
                        taskModel.getTaskId(),
                        new String[]{RAW_JSON_FIELD, ARCHIVED_FIELD,"status","output"},
                        new Object[]{objectMapper.writeValueAsString(taskModel), true,"COMPLETED",objectMapper.writeValueAsString(taskModel.getOutputData())});
            } catch (JsonProcessingException e) {
                LOGGER.error(String.format(
                                "Task %s of workflow %s can not be serialized to json",
                                taskModel.getTaskId(), taskModel.getWorkflowInstanceId()),
                        e);
            }
            return;
        }
        executionDAO.updateTask(taskModel);
        try {
            /*
             * Indexing a task for every update adds a lot of volume. That is ok but if async indexing
             * is enabled and tasks are stored in memory until a block has completed, we would lose a lot
             * of tasks on a system failure. So only index for each update if async indexing is not enabled.
             * If it *is* enabled, tasks will be indexed only when a workflow is in terminal state.
             */
            if (!properties.isAsyncIndexingEnabled()) {
                indexDAO.indexTask(new TaskSummary(taskModel.toTask()));
            }
        } catch (TerminateWorkflowException e) {
            // re-throw it so we can terminate the workflow
            throw e;
        } catch (Exception e) {
            String errorMsg =
                    String.format(
                            "Error updating task: %s in workflow: %s",
                            taskModel.getTaskId(), taskModel.getWorkflowInstanceId());
            LOGGER.error(errorMsg, e);
            throw new TransientException(errorMsg, e);
        }
        if (properties.getEnableRemoveRedisKey()) {
            scheduledRemoveRedisWhenUpdateTask(taskModel);
        }
    }

    private void addInsights(TaskModel taskModel) {
        try {
            if (!JourneyInsightEntry.isRedundantEntry(taskModel)) {
                JourneyInsightEntry entry = JourneyInsightEntry.fromTaskModel(taskModel);
                WorkflowModel workflowModel = getWorkflowModelFromDataStore(taskModel.getWorkflowInstanceId(), false);
                if (workflowModel != null) {
                    String requestUuid = String.valueOf(Optional.ofNullable(workflowModel.getInput()).orElse(new HashMap<>()).get(REQUEST_UUID));
                    String receivedAt = String.valueOf(Optional.ofNullable(workflowModel.getInput()).orElse(new HashMap<>()).get(RECEIVED_AT));
                    entry.setRequestUuid(requestUuid);
                    entry.setReceivedAt(receivedAt);
                }
                jiWriter.writeJourneyInsightEntryAsync(entry);
            }
        } catch (Exception e) {
            LOGGER.error("Error happens when write journey insight to AWS Timestream. TaskModel: {}", taskModel, e);
        }
    }

    public void updateTasks(List<TaskModel> tasks) {
        tasks.forEach(this::updateTask);
    }

    public void removeTask(String taskId) {
        executionDAO.removeTask(taskId);
    }

    private void removeTaskIndex(WorkflowModel workflow, TaskModel task, boolean archiveTask)
            throws JsonProcessingException {
        if (archiveTask) {
            if (task.getStatus().isTerminal()) {
                // Only allow archival if task is in terminal state
                // DO NOT archive async, since if archival errors out, task data will be lost
                indexDAO.updateTask(
                        workflow.getWorkflowId(),
                        task.getTaskId(),
                        new String[]{ARCHIVED_FIELD},
                        new Object[]{true});
            } else {
                throw new IllegalArgumentException(
                        String.format(
                                "Cannot archive task: %s of workflow: %s with status: %s",
                                task.getTaskId(), workflow.getWorkflowId(), task.getStatus()));
            }
        } else {
            // Not archiving, remove task from index
            indexDAO.asyncRemoveTask(workflow.getWorkflowId(), task.getTaskId());
        }
    }

    public void extendLease(TaskModel taskModel) {
        taskModel.setUpdateTime(System.currentTimeMillis());
        executionDAO.updateTask(taskModel);
    }

    public List<PollData> getTaskPollData(String taskName) {
        return pollDataDAO.getPollData(taskName);
    }

    public List<PollData> getAllPollData() {
        return pollDataDAO.getAllPollData();
    }

    public PollData getTaskPollDataByDomain(String taskName, String domain) {
        try {
            return pollDataDAO.getPollData(taskName, domain);
        } catch (Exception e) {
            LOGGER.error(
                    "Error fetching pollData for task: '{}', domain: '{}'", taskName, domain, e);
            return null;
        }
    }

    public void updateTaskLastPoll(String taskName, String domain, String workerId) {
        try {
            pollDataDAO.updateLastPollData(taskName, domain, workerId);
        } catch (Exception e) {
            LOGGER.error(
                    "Error updating PollData for task: {} in domain: {} from worker: {}",
                    taskName,
                    domain,
                    workerId,
                    e);
            Monitors.error(this.getClass().getCanonicalName(), "updateTaskLastPoll");
        }
    }

    /**
     * Save the {@link EventExecution} to the data store Saves to {@link ExecutionDAO} first, if
     * this succeeds then saves to the {@link IndexDAO}.
     *
     * @param eventExecution the {@link EventExecution} to be saved
     * @return true if save succeeds, false otherwise.
     */
    public boolean addEventExecution(EventExecution eventExecution) {
        boolean added = executionDAO.addEventExecution(eventExecution);

        if (added) {
            indexEventExecution(eventExecution);
        }

        return added;
    }

    public void updateEventExecution(EventExecution eventExecution) {
        executionDAO.updateEventExecution(eventExecution);
        indexEventExecution(eventExecution);
    }

    private void indexEventExecution(EventExecution eventExecution) {
        if (properties.isEventExecutionIndexingEnabled()) {
            if (properties.isAsyncIndexingEnabled()) {
                indexDAO.asyncAddEventExecution(eventExecution);
            } else {
                indexDAO.addEventExecution(eventExecution);
            }
        }
    }

    public void removeEventExecution(EventExecution eventExecution) {
        executionDAO.removeEventExecution(eventExecution);
    }

    public boolean exceedsInProgressLimit(TaskModel task) {
        return concurrentExecutionLimitDAO.exceedsLimit(task);
    }

    public boolean exceedsRateLimitPerFrequency(TaskModel task, TaskDef taskDef) {
        return rateLimitingDao.exceedsRateLimitPerFrequency(task, taskDef);
    }

    public void addTaskExecLog(List<TaskExecLog> logs) {
        if (properties.isTaskExecLogIndexingEnabled() && !logs.isEmpty()) {
            Monitors.recordTaskExecLogSize(logs.size());
            int taskExecLogSizeLimit = properties.getTaskExecLogSizeLimit();
            if (logs.size() > taskExecLogSizeLimit) {
                LOGGER.warn(
                        "Task Execution log size: {} for taskId: {} exceeds the limit: {}",
                        logs.size(),
                        logs.get(0).getTaskId(),
                        taskExecLogSizeLimit);
                logs = logs.stream().limit(taskExecLogSizeLimit).collect(Collectors.toList());
            }
            if (properties.isAsyncIndexingEnabled()) {
                indexDAO.asyncAddTaskExecutionLogs(logs);
            } else {
                indexDAO.addTaskExecutionLogs(logs);
            }
        }
    }

    public void addMessage(String queue, Message message) {
        if (properties.isAsyncIndexingEnabled()) {
            indexDAO.asyncAddMessage(queue, message);
        } else {
            indexDAO.addMessage(queue, message);
        }
    }

    public SearchResult<String> searchWorkflows(
            String query, String freeText, int start, int count, List<String> sort) {
        return indexDAO.searchWorkflows(query, freeText, start, count, sort);
    }

    public SearchResult<WorkflowSummary> searchWorkflowSummary(
            String query, String freeText, int start, int count, List<String> sort) {
        return indexDAO.searchWorkflowSummary(query, freeText, start, count, sort);
    }

    public SearchResult<String> searchTasks(
            String query, String freeText, int start, int count, List<String> sort) {
        return indexDAO.searchTasks(query, freeText, start, count, sort);
    }

    public SearchResult<TaskSummary> searchTaskSummary(
            String query, String freeText, int start, int count, List<String> sort) {
        return indexDAO.searchTaskSummary(query, freeText, start, count, sort);
    }

    public List<TaskExecLog> getTaskExecutionLogs(String taskId) {
        return properties.isTaskExecLogIndexingEnabled()
                ? indexDAO.getTaskExecutionLogs(taskId)
                : Collections.emptyList();
    }

    /**
     * Populates the workflow input data and the tasks input/output data if stored in external
     * payload storage.
     *
     * @param workflowModel the workflowModel for which the payload data needs to be populated from
     *                      external storage (if applicable)
     */
    public void populateWorkflowAndTaskPayloadData(WorkflowModel workflowModel) {
        if (StringUtils.isNotBlank(workflowModel.getExternalInputPayloadStoragePath())) {
            Map<String, Object> workflowInputParams =
                    externalPayloadStorageUtils.downloadPayload(
                            workflowModel.getExternalInputPayloadStoragePath());
            Monitors.recordExternalPayloadStorageUsage(
                    workflowModel.getWorkflowName(),
                    ExternalPayloadStorage.Operation.READ.toString(),
                    ExternalPayloadStorage.PayloadType.WORKFLOW_INPUT.toString());
            workflowModel.internalizeInput(workflowInputParams);
        }

        if (StringUtils.isNotBlank(workflowModel.getExternalOutputPayloadStoragePath())) {
            Map<String, Object> workflowOutputParams =
                    externalPayloadStorageUtils.downloadPayload(
                            workflowModel.getExternalOutputPayloadStoragePath());
            Monitors.recordExternalPayloadStorageUsage(
                    workflowModel.getWorkflowName(),
                    ExternalPayloadStorage.Operation.READ.toString(),
                    ExternalPayloadStorage.PayloadType.WORKFLOW_OUTPUT.toString());
            workflowModel.internalizeOutput(workflowOutputParams);
        }

        workflowModel.getTasks().forEach(this::populateTaskData);
    }

    public void populateTaskData(TaskModel taskModel) {
        if (StringUtils.isNotBlank(taskModel.getExternalOutputPayloadStoragePath())) {
            Map<String, Object> outputData =
                    externalPayloadStorageUtils.downloadPayload(
                            taskModel.getExternalOutputPayloadStoragePath());
            taskModel.internalizeOutput(outputData);
            Monitors.recordExternalPayloadStorageUsage(
                    taskModel.getTaskDefName(),
                    ExternalPayloadStorage.Operation.READ.toString(),
                    ExternalPayloadStorage.PayloadType.TASK_OUTPUT.toString());
        }

        if (StringUtils.isNotBlank(taskModel.getExternalInputPayloadStoragePath())) {
            Map<String, Object> inputData =
                    externalPayloadStorageUtils.downloadPayload(
                            taskModel.getExternalInputPayloadStoragePath());
            taskModel.internalizeInput(inputData);
            Monitors.recordExternalPayloadStorageUsage(
                    taskModel.getTaskDefName(),
                    ExternalPayloadStorage.Operation.READ.toString(),
                    ExternalPayloadStorage.PayloadType.TASK_INPUT.toString());
        }
    }

    public long getLatestFinishTimestampByCorrelationId(String correlationId) {
        return executionDAO.getLatestFinishTimestampByCorrelationId(correlationId);
    }

    public List<String> getWfIdsByCorrelationId(String correlationId) {
        return executionDAO.getWfIdsByCorrelationId(correlationId, false);
    }

    public void  cleanupRedisByKey(String key,String value,Boolean isHash,Boolean isSet){
        executionDAO.cleanupRedisByKey(key,value,isHash,isSet);
    }

    public Object getRedisByKey(String key){
        return executionDAO.getRedisByKey(key);
    }

    public boolean removeRedis(String taskId,String workflowId){
        if(StringUtils.isNotEmpty(workflowId)){
            return executionDAO.removeWorkflow(workflowId);
        }else{
            return  executionDAO.removeTask(taskId);
        }
    }

    class DelayWorkflowUpdate implements Runnable {

        private final String workflowId;

        DelayWorkflowUpdate(String workflowId) {
            this.workflowId = workflowId;
        }

        @Override
        public void run() {
            try {
                WorkflowModel workflowModel = executionDAO.getWorkflow(workflowId, false);
                indexDAO.asyncIndexWorkflow(new WorkflowSummary(workflowModel.toWorkflow()));
            } catch (Exception e) {
                LOGGER.error("Unable to update workflow: {}", workflowId, e);
            }
        }
    }
}
