/*
 * Copyright 2020 Netflix, Inc.
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
package com.netflix.conductor.core.events;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;


import com.netflix.conductor.common.model.BulkResponse;
import com.netflix.conductor.common.run.TaskSummary;
import com.netflix.conductor.core.dal.ExecutionDAOFacade;
import com.netflix.conductor.service.WorkflowBulkService;
import net.minidev.json.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.netflix.conductor.common.metadata.events.EventHandler.Action;
import com.netflix.conductor.common.metadata.events.EventHandler.StartWorkflow;
import com.netflix.conductor.common.metadata.events.EventHandler.TaskDetails;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.conductor.common.metadata.tasks.TaskType;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.common.utils.TaskUtils;
import com.netflix.conductor.core.execution.StartWorkflowInput;
import com.netflix.conductor.core.execution.WorkflowExecutor;
import com.netflix.conductor.core.operation.StartWorkflowOperation;
import com.netflix.conductor.core.utils.JsonUtils;
import com.netflix.conductor.core.utils.ParametersUtils;
import com.netflix.conductor.metrics.Monitors;
import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;
import com.netflix.conductor.service.WorkflowService;

/**
 * Action Processor subscribes to the Event Actions queue and processes the actions (e.g. start
 * workflow etc)
 */
@Component
public class SimpleActionProcessor implements ActionProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleActionProcessor.class);

    private final WorkflowExecutor workflowExecutor;
    private final ParametersUtils parametersUtils;
    private final JsonUtils jsonUtils;
    private final StartWorkflowOperation startWorkflowOperation;
    private final WorkflowService workflowService;
    private final WorkflowBulkService workflowBulkService;
    private final ExecutionDAOFacade executionDAOFacade;
    public SimpleActionProcessor(
            WorkflowExecutor workflowExecutor,
            ParametersUtils parametersUtils,
            JsonUtils jsonUtils,
            StartWorkflowOperation startWorkflowOperation,
            WorkflowService workflowService, WorkflowBulkService workflowBulkService, ExecutionDAOFacade executionDAOFacade) {
        this.workflowExecutor = workflowExecutor;
        this.parametersUtils = parametersUtils;
        this.jsonUtils = jsonUtils;
        this.startWorkflowOperation = startWorkflowOperation;
        this.workflowService = workflowService;
        this.workflowBulkService = workflowBulkService;
        this.executionDAOFacade = executionDAOFacade;
    }

    public Map<String, Object> execute(
            Action action, Object payloadObject, String event, String messageId) {

        LOGGER.debug(
                "Executing action: {} for event: {} with messageId:{}",
                action.getAction(),
                event,
                messageId);

        Object jsonObject = payloadObject;
        if (action.isExpandInlineJSON()) {
            jsonObject = jsonUtils.expand(payloadObject);
        }

        switch (action.getAction()) {
            case start_workflow:
                return startWorkflow(action, jsonObject, event, messageId);
            case trigger_task:
            case complete_task:
                return completeTask(
                        action,
                        jsonObject,
                        action.getComplete_task(),
                        TaskModel.Status.COMPLETED,
                        event,
                        messageId);
            case fail_task:
                return completeTask(
                        action,
                        jsonObject,
                        action.getFail_task(),
                        TaskModel.Status.FAILED,
                        event,
                        messageId);
//            case trigger_task:
//                return completeTask(
//                        action,
//                        jsonObject,
//                        lookupForCorrespondingTask(action.getTrigger_task(), jsonObject),
//                        TaskModel.Status.COMPLETED,
//                        event,
//                        messageId);
            default:
                break;
        }
        throw new UnsupportedOperationException(
                "Action not supported " + action.getAction() + " for event " + event);
    }

    private TaskDetails lookupForCorrespondingTask(TaskDetails triggerTask, Object jsonObject) {

        // get correlationId from event
        Map<String, Object> input = new HashMap<>();
        input.put("uuid", "${entity_uuid}");
        input.put("entityType", "${entity_type}");
        input.put("requestUuid", "${requestUuid}");
        input.put("receivedAt", "${receivedAt}");
        input.put("taskRefName", triggerTask.getTaskRefName());
        input.put("workflowDef", triggerTask.getWorkflowDef());
        Map<String, Object> replaced = parametersUtils.replace(input, jsonObject);
        String correlationId = (String) replaced.get("uuid");
        TaskDetails taskDetails = new TaskDetails();
        String taskRefName = (String) replaced.get("taskRefName");
        String workflowDef = (String) replaced.get("workflowDef");
        taskDetails.setTaskRefName(taskRefName);
        Map<String, Object> outPut = triggerTask.getOutput();
        outPut.put("requestUuid", replaced.get("requestUuid"));
        taskDetails.setOutput(outPut);
        taskDetails.setOutputMessage(triggerTask.getOutputMessage());
        LOGGER.debug(
                "lookupForCorrespondingTask ----- wfDefName:{} ----- taskRefName:{}---- correlation id:{} ",
                taskRefName,
                workflowDef,
                correlationId);
        if (StringUtils.isNotEmpty(workflowDef) && StringUtils.isNotEmpty(correlationId)) {
            final TaskSummary TaskSummary = workflowService.getFirstRunningWorkflowIdByCorrelationId(workflowDef, correlationId, taskRefName);
            if (StringUtils.isNotEmpty(TaskSummary.getWorkflowId())) {
                taskDetails.setWorkflowId(TaskSummary.getWorkflowId());
                taskDetails.setTaskId(TaskSummary.getTaskId());
            }
//            else {
//                //TODO remove no use code
//                List<Workflow> workflows =
//                        workflowService.getWorkflows(workflowDef, correlationId, false, true);
//                if (!workflows.isEmpty()) {
//                    workflows.stream()
//                            .filter(workflow -> workflow.getTasks().stream()
//                                    .anyMatch(task -> taskRefName.equals(task.getReferenceTaskName())
//                                                      && Task.Status.IN_PROGRESS.equals(task.getStatus())
//                                                      && (TaskType.TASK_TYPE_HUMAN.equals(task.getTaskType())
//                                                          || TaskType.TASK_TYPE_WAIT.equals(task.getTaskType()))))
//                            .map(Workflow::getWorkflowId)
//                            .findFirst().ifPresent(w->{
//                                TaskSummary.setWorkflowId(w);
//                                taskDetails.setWorkflowId(w);
//                            });
//                } else {
//                    LOGGER.debug(
//                            "Error workflow not found with workflow def:{}, correlation id:{}, task reference name:{}",
//                            workflowDef,
//                            correlationId,
//                            taskRefName);
//                }
//            }

            if (StringUtils.isNotEmpty(TaskSummary.getWorkflowId())) {
                WorkflowModel workflow = workflowExecutor.getWorkflow(TaskSummary.getWorkflowId(), false);
                workflow.getInput().put("requestUuid", replaced.get("requestUuid"));
                workflow.getInput().put("receivedAt", replaced.get("receivedAt"));
                if (jsonObject instanceof Map) {
                    JSONObject json = new JSONObject((Map<String, Object>) jsonObject);
                    String eventName = json.getAsString("event_name");
                    if (StringUtils.isNotEmpty(eventName)) {
                        JSONObject eventPayload = new JSONObject();
                        eventPayload.put(eventName, jsonObject);
                        workflow.getInput().put("eventPayload", eventPayload);
                    }
                }
                workflowExecutor.updateWorkflow(workflow);
            }
        } else {
            LOGGER.error(
                    "Error workflowDef:{} or correlation id:{} is missing",
                    workflowDef,
                    correlationId);
        }

        LOGGER.debug("getWorkflowsByCorrelationId--workflows id:{}", taskDetails.getWorkflowId());

        return taskDetails;
    }

    private Map<String, Object> completeTask(
            Action action,
            Object payload,
            TaskDetails taskDetails,
            TaskModel.Status status,
            String event,
            String messageId) {

        Map<String, Object> input = new HashMap<>();
        input.put("workflowId", taskDetails.getWorkflowId());
        input.put("taskId", taskDetails.getTaskId());
        input.put("taskRefName", taskDetails.getTaskRefName());
        input.putAll(taskDetails.getOutput());

        Map<String, Object> replaced = parametersUtils.replace(input, payload);
        String workflowId = (String) replaced.get("workflowId");
        String taskId = (String) replaced.get("taskId");
        String taskRefName = (String) replaced.get("taskRefName");
        TaskModel taskModel = null;
        if (StringUtils.isNotEmpty(taskId)) {
            taskModel = workflowExecutor.getTask(taskId);
        } else if (StringUtils.isNotEmpty(workflowId) && StringUtils.isNotEmpty(taskRefName)) {
            WorkflowModel workflow = workflowExecutor.getWorkflow(workflowId, true);
            if (workflow == null) {
                replaced.put("error", "No workflow found with ID: " + workflowId);
                return replaced;
            }
            taskModel = workflow.getTaskByRefName(taskRefName);
            // Task can be loopover task.In such case find corresponding task and update
            List<TaskModel> loopOverTaskList = workflow.getTasks().stream().filter(t -> TaskUtils.removeIterationFromTaskRefName(t.getReferenceTaskName()).equals(taskRefName)).collect(Collectors.toList());
            if (!loopOverTaskList.isEmpty()) {
                // Find loopover task with the highest iteration value
                taskModel = loopOverTaskList.stream().max(Comparator.comparingInt(TaskModel::getIteration)).orElse(taskModel);
            }
        }

        if (taskModel == null) {
            replaced.put(
                    "error",
                    "No task found with taskId: "
                            + taskId
                            + ", reference name: "
                            + taskRefName
                            + ", workflowId: "
                            + workflowId);
            return replaced;
        }

        taskModel.setStatus(status);
        taskModel.setOutputData(replaced);
        taskModel.setOutputMessage(taskDetails.getOutputMessage());
        taskModel.addOutput("conductor.event.messageId", messageId);
        taskModel.addOutput("conductor.event.name", event);

        try {
            workflowExecutor.updateTask(new TaskResult(taskModel.toTask()));
            LOGGER.debug(
                    "Updated task: {} in workflow:{} with status: {} for event: {} for message:{}",
                    taskId,
                    workflowId,
                    status,
                    event,
                    messageId);
        } catch (RuntimeException e) {
            Monitors.recordEventActionError(
                    action.getAction().name(), taskModel.getTaskType(), event);
            LOGGER.error(
                    "Error updating task: {} in workflow: {} in action: {} for event: {} for message: {}",
                    taskDetails.getTaskRefName(),
                    taskDetails.getWorkflowId(),
                    action.getAction(),
                    event,
                    messageId,
                    e);
            replaced.put("error", e.getMessage());
            throw e;
        }
        return replaced;
    }

    private Map<String, Object> startWorkflow(
            Action action, Object payload, String event, String messageId) {
        StartWorkflow params = action.getStart_workflow();
        Map<String, Object> output = new HashMap<>();
        try {
            Map<String, Object> inputParams = params.getInput();
            inputParams.put("requestUuid", "${requestUuid}");
            inputParams.put("receivedAt", "${receivedAt}");
            Map<String, Object> workflowInput = parametersUtils.replace(inputParams, payload);

            Map<String, Object> paramsMap = new HashMap<>();
            Optional.ofNullable(params.getCorrelationId())
                    .ifPresent(value -> paramsMap.put("correlationId", value));
            Optional.ofNullable(params.getEntityType())
                    .ifPresent(value -> paramsMap.put("entityType", value));
            Map<String, Object> replaced = parametersUtils.replace(paramsMap, payload);

            workflowInput.put("conductor.event.messageId", messageId);
            workflowInput.put("conductor.event.name", event);
            if (payload instanceof Map) {
                Map<String, Object> payloadMap = (Map<String, Object>) payload;
                JSONObject json = new JSONObject(payloadMap);
                String eventName = json.getAsString("event_name");
                if (StringUtils.isNotEmpty(eventName)) {
                    JSONObject eventPayload = new JSONObject();
                    eventPayload.put(eventName, payload);
                    workflowInput.put("eventPayload", eventPayload);
                }
                if (payloadMap.containsKey("user_profile")) {
                    Map<String, Object> userProfile = (Map<String, Object>) payloadMap.get("user_profile");
                    if (userProfile.containsKey("email")) {
                        String email = (String) userProfile.get("email");
                        workflowInput.put("emailFromPayload", email);
                    }
                    if (userProfile.containsKey("push_token")) {
                        String pushToken = (String) userProfile.get("push_token");
                        String platform = (String) userProfile.get("platform");
                        String combinedPlatform = platform + "PUSH_PLATFORM" + pushToken;
                        workflowInput.put("pushTokenFromPayload", combinedPlatform);
                    }
                    if (userProfile.containsKey("phone")) {
                        String phone = (String) userProfile.get("phone");
                        workflowInput.put("phoneTokenFromPayload", phone);
                    }
                }
            }
            StartWorkflowInput startWorkflowInput = new StartWorkflowInput();
            startWorkflowInput.setName(params.getName());
            startWorkflowInput.setVersion(params.getVersion());
            startWorkflowInput.setCorrelationId(
                    Optional.ofNullable(replaced.get("correlationId"))
                            .map(Object::toString)
                            .orElse(params.getCorrelationId()));
            startWorkflowInput.setEntityType(
                    Optional.ofNullable(replaced.get("entityType"))
                            .map(Object::toString)
                            .orElse(params.getEntityType()));
            startWorkflowInput.setWorkflowInput(workflowInput);
            startWorkflowInput.setEvent(event);
            startWorkflowInput.setTaskToDomain(params.getTaskToDomain());
            startWorkflowInput.setEntityMaxEntryCount(params.getEntityMaxEntryCount());
            startWorkflowInput.setExecMode(params.getExecMode());
            startWorkflowInput.setExecLimitIntervalInSeconds(params.getExecLimitIntervalInSeconds());
            startWorkflowInput.setStopInEffectAfterEpochSecond(params.getStopInEffectAfterEpochSecond());
            startWorkflowInput.setFlushPreviousCorrIdRunningWf(params.getFlushPreviousCorrIdRunningWf());
            String populatedCorrId = startWorkflowInput.getCorrelationId();
            // 1. determine whether correlation ID is allowed to start workflow
            if (params.getEntityMaxEntryCount() != null && params.getEntityMaxEntryCount() > 0) {
                boolean exceedsExecCountLimitPerEntity = true;
                String workflowDefinitionName = params.getName();
                if (params.getExecLimitIntervalInSeconds() != null && params.getExecLimitIntervalInSeconds() > 0) {
                    exceedsExecCountLimitPerEntity = executionDAOFacade.exceedsExecCountLimitPerEntity(
                            populatedCorrId,
                            workflowDefinitionName,
                            params.getEntityMaxEntryCount(),
                            params.getExecLimitIntervalInSeconds(),
                            Optional.ofNullable(params.getExecMode()).orElse(0));
                    if (exceedsExecCountLimitPerEntity) {
                        LOGGER.info(
                                "Workflow execution count limit for workflow definition(name:{}) and entity: {}  exceeds limit: {}. Interval in seconds: {}",
                                workflowDefinitionName,
                                populatedCorrId,
                                params.getEntityMaxEntryCount(),
                                params.getExecLimitIntervalInSeconds());
                        output.put("exceedsExecCountLimitPerEntity", true);
                        return output;
                    }
                } else if (params.getStopInEffectAfterEpochSecond() > 0) {
                    exceedsExecCountLimitPerEntity = executionDAOFacade.exceedsExecCountLimitPerEntity(
                            populatedCorrId,
                            workflowDefinitionName,
                            params.getEntityMaxEntryCount(),
                            Instant.ofEpochSecond(params.getStopInEffectAfterEpochSecond()).atOffset(ZoneOffset.UTC),
                            Optional.ofNullable(params.getExecMode()).orElse(0));
                    if (exceedsExecCountLimitPerEntity) {
                        LOGGER.info(
                                "Workflow execution count limit for workflow definition(name:{}) and entity: {}  exceeds limit: {}. Stop in effect after epoch time: {}",
                                workflowDefinitionName,
                                populatedCorrId,
                                params.getEntityMaxEntryCount(),
                                params.getStopInEffectAfterEpochSecond());
                        output.put("exceedsExecCountLimitPerEntity", true);
                        return output;
                    }
                } else {
                    exceedsExecCountLimitPerEntity = executionDAOFacade.exceedsExecCountLimitPerEntity(
                            populatedCorrId,
                            workflowDefinitionName,
                            params.getEntityMaxEntryCount(),
                            Optional.ofNullable(params.getExecMode()).orElse(0));
                    if (exceedsExecCountLimitPerEntity) {
                        LOGGER.info(
                                "Workflow execution count limit for workflow definition(name:{}) and entity: {}  exceeds limit: {}. Base one",
                                workflowDefinitionName,
                                populatedCorrId,
                                params.getEntityMaxEntryCount());
                        output.put("exceedsExecCountLimitPerEntity", true);
                        return output;
                    }
                }
            }
            // 2. whether previous entries of that corrID is to be flushed

            try {
                if (params.getFlushPreviousCorrIdRunningWf() && populatedCorrId!= null) {
                    List<String> workflowIds = workflowService.getRunningWorkflows(params.getName(), populatedCorrId);
                    if (!workflowIds.isEmpty()) {
                        BulkResponse response = workflowBulkService.terminate(workflowIds, "Flushed out because of newer entry");
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error happens when flushing out current running workflows {} of correlation ID {}", params.getName(), populatedCorrId, e);
            }
            String workflowId = startWorkflowOperation.execute(startWorkflowInput);

            output.put("workflowId", workflowId);
            LOGGER.debug(
                    "Started workflow: {}/{}/{} for event: {} for message:{}",
                    params.getName(),
                    params.getVersion(),
                    workflowId,
                    event,
                    messageId);

        } catch (RuntimeException e) {
            Monitors.recordEventActionError(action.getAction().name(), params.getName(), event);
            LOGGER.error(
                    "Error starting workflow: {}, version: {}, for event: {} for message: {}",
                    params.getName(),
                    params.getVersion(),
                    event,
                    messageId,
                    e);
            output.put("error", e.getMessage());
            throw e;
        }
        return output;
    }
}
