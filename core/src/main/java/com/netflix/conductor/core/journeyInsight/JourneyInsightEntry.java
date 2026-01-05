package com.netflix.conductor.core.journeyInsight;


import com.netflix.conductor.common.metadata.tasks.TaskType;
import com.netflix.conductor.core.journeyInsight.constant.WorkflowTaskCommonInputKey;
import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.netflix.conductor.core.journeyInsight.constant.WorkflowTaskCommonInputKey.*;


@Data
@Builder
public class JourneyInsightEntry {
    private String journeyId;
    private String nodeId;
    private String nodeType; //  email/time/start...
    private String nodeStatus; //  begin/end/fail
    private String userId;
    private String instanceId;
    private Boolean isGoalState;
    private String requestUuid;
    private String receivedAt;
    private String messageId;
    private String templateId;
    private String errorInfo;
    private Boolean isLeafNode;
    private Integer version;
    private OffsetDateTime createdAt;
    private String tenantName;
    private static final String DEFAULT_TENANT_NAME = "default";

    public static JourneyInsightEntry fromTaskModel(TaskModel taskModel) {
        String nodeType = (String) Optional.ofNullable(taskModel.getInputData()).orElse(new HashMap<>()).get(NODE_TYPE);
        Object templateIdObj = Optional.ofNullable(taskModel.getInputData()).orElse(new HashMap<>()).get(WorkflowTaskCommonInputKey.TEMPLATE_ID);
        Object messageIdObj = Optional.ofNullable(taskModel.getOutputData()).orElse(new HashMap<>()).get(WorkflowTaskCommonInputKey.MESSAGE_ID);
        return JourneyInsightEntry.builder()
                .journeyId(taskModel.getWorkflowType())
                .nodeId((String) Optional.ofNullable(taskModel.getInputData()).orElse(new HashMap<>()).get(NODE_ID))
                .nodeType(nodeType)
                .nodeStatus(getNodeStatus(taskModel))
                .userId(taskModel.getCorrelationId())
                .instanceId(taskModel.getWorkflowInstanceId())
                .templateId(templateIdObj == null ? null : String.valueOf(templateIdObj))
                .messageId(messageIdObj == null ? null : String.valueOf(messageIdObj))
//                .requestUuid(String.valueOf(Optional.ofNullable(taskModel.getInputData()).orElse(new HashMap<>()).get(REQUEST_UUID)))
                .errorInfo(taskModel.getReasonForIncompletion())
                .isGoalState((Boolean) Optional.ofNullable(taskModel.getInputData()).orElse(new HashMap<>()).get(GOAL_STATE))
                .isLeafNode((Boolean) Optional.ofNullable(taskModel.getInputData()).orElse(new HashMap<>()).get(IS_LEAF_NODE))
                .version((Integer) Optional.ofNullable(taskModel.getInputData()).orElse(new HashMap<>()).get(VERSION))
                .tenantName(Optional.ofNullable(taskModel.getInputData()).orElse(new HashMap<>()).getOrDefault(TENANT_NAME, DEFAULT_TENANT_NAME).toString())
                .createdAt(OffsetDateTime.now().withOffsetSameInstant(ZoneOffset.UTC))
                .build();
    }

    /**
     * only be called when workflow execution is created
     *
     * @param workflowModel
     * @return
     */
    public static JourneyInsightEntry fromWorkflowModel(WorkflowModel workflowModel) {
        JourneyInsightEntryBuilder builder = JourneyInsightEntry.builder()
                .journeyId(workflowModel.getWorkflowName())
                .nodeId(workflowModel.getWorkflowName())
                .nodeType("start")
                .nodeStatus("end")
                .userId(workflowModel.getCorrelationId())
                .instanceId(workflowModel.getWorkflowId())
                .isGoalState(false)
                .requestUuid(String.valueOf(Optional.ofNullable(workflowModel.getInput()).orElse(new HashMap<>()).get(REQUEST_UUID)))
                .receivedAt(String.valueOf(Optional.ofNullable(workflowModel.getInput()).orElse(new HashMap<>()).get(RECEIVED_AT)))
                .errorInfo(workflowModel.getReasonForIncompletion())
                .isLeafNode(false)
                .tenantName(workflowModel.getVariables().getOrDefault("tenantName", DEFAULT_TENANT_NAME).toString())
                .version(workflowModel.getWorkflowVersion())
                .createdAt(OffsetDateTime.now().withOffsetSameInstant(ZoneOffset.UTC));

        return builder.build();
    }

    /**
     * called when in terminal state
     *
     * @param workflowModel
     * @param taskModels
     * @return
     */
    public static JourneyInsightEntry fromWorkflowModel(WorkflowModel workflowModel, List<TaskModel> taskModels) {
        TaskModel lastTask = taskModels.isEmpty() ? new TaskModel() : taskModels.get(taskModels.size() - 1);
        return JourneyInsightEntry.builder()
                .journeyId(workflowModel.getWorkflowName())
                .userId(workflowModel.getCorrelationId())
                .instanceId(workflowModel.getWorkflowId())
                .nodeId((String) Optional.ofNullable(lastTask.getInputData()).orElse(new HashMap<>()).get(NODE_ID))
                .nodeType("end")
                .requestUuid(String.valueOf(Optional.ofNullable(workflowModel.getInput()).orElse(new HashMap<>()).get(REQUEST_UUID)))
                .receivedAt(String.valueOf(Optional.ofNullable(workflowModel.getInput()).orElse(new HashMap<>()).get(RECEIVED_AT)))
                .errorInfo(lastTask.getReasonForIncompletion())
                .nodeStatus(workflowModel.getStatus().isSuccessful() ? "end" : "fail")
                .isGoalState((Boolean) Optional.ofNullable(lastTask.getInputData()).orElse(new HashMap<>()).get(GOAL_STATE))
                .isLeafNode(true)
                .tenantName(workflowModel.getVariables().getOrDefault("tenantName", DEFAULT_TENANT_NAME).toString())
                .version(workflowModel.getWorkflowVersion())
                .createdAt(OffsetDateTime.now().withOffsetSameInstant(ZoneOffset.UTC))
                .build();
    }

    public static String getNodeStatus(TaskModel taskModel) {
        TaskModel.Status status = taskModel.getStatus();
        String nodeType = (String) Optional.ofNullable(taskModel.getInputData()).orElse(new HashMap<>()).getOrDefault(WorkflowTaskCommonInputKey.NODE_TYPE, "");
        Map<String, Object> outputData = Optional.ofNullable(taskModel.getOutputData()).orElse(new HashMap<>());
        boolean isComplexNodeType = isComplexNodeType(taskModel);
        if (!status.isTerminal()) {
            return "start";
        }

        if (!status.isSuccessful()) {
            return "fail";
        }

        if (nodeType.equals(NodeType.NODE_TAG)) {
            if (outputData.get("isTagModified") != null && outputData.get("isTagModified") instanceof Boolean && Boolean.TRUE.equals(outputData.get("isTagModified"))) {
                return "modified";
            }
        }

        if(taskModel.getOutputData()!= null && Boolean.TRUE.equals(taskModel.getOutputData().get("isRateLimited"))){
            return "rateLimited";
        }

        if (!isComplexNodeType) {
            return "end";
        }
        if (isLastTaskOfConvertedTasks(taskModel)) {
            return "end";
        }

        return "start";

    }


    public static Boolean isRedundantEntry(TaskModel taskModel) {
        String taskType = taskModel.getWorkflowTask().getType();
        // swith tasks are converted from Presentation Layer edges
        if (TaskType.TASK_TYPE_SWITCH.equals(taskType)) {
            return true;
        }
        // only record terminal status of get attribute task and rendering task
        if (isComplexNodeType(taskModel) && !isLastTaskOfConvertedTasks(taskModel) && !taskModel.getStatus().isTerminal()) {
            return true;
        }
        // skip the `SCHEDULED` task status of task
        if (isComplexNodeType(taskModel) && isLastTaskOfConvertedTasks(taskModel) && taskModel.getStatus() == TaskModel.Status.SCHEDULED) {
            return true;
        }
        // SIMPLE, HTTP, WAIT task would emit two entries of TaskModel(Status: COMPLETED). One's `executed` flag is true, one is false. Drop  the true one because it shows up laters
        if (List.of(TaskType.TASK_TYPE_HTTP, TaskType.TASK_TYPE_WAIT, TaskType.TASK_TYPE_SIMPLE, TaskType.TASK_TYPE_INLINE).contains(taskType)
                && taskModel.getStatus() == TaskModel.Status.COMPLETED && taskModel.isExecuted()
        ) {
            return true;
        }
        // When in progress, WAIT task would be updated multiple times only keep the first time when it's updated
        if(TaskType.TASK_TYPE_WAIT.equals(taskType) && taskModel.getStatus() == TaskModel.Status.IN_PROGRESS  && taskModel.getUpdateTime()!=0){
            return true;
        }
        // When in progress, INLINE task would be updated multiple times only keep the first time when it's updated
        if(TaskType.TASK_TYPE_INLINE.equals(taskType) && taskModel.getStatus() == TaskModel.Status.IN_PROGRESS  && taskModel.getUpdateTime()!=0){
            return true;
        }

        return false;
    }

    private static boolean isComplexNodeType(TaskModel taskModel) {
        String nodeType = (String) Optional.ofNullable(taskModel.getInputData()).orElse(new HashMap<>()).get(WorkflowTaskCommonInputKey.NODE_TYPE);
        for (String type : complexNodeTypes) {
            if (type.equals(nodeType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLastTaskOfConvertedTasks(TaskModel taskModel) {
        String isLastTaskOfConvertedTasks = Optional.ofNullable(taskModel.getInputData()).orElse(new HashMap<>()).getOrDefault(IS_LAST_TASK_OF_CONVERTED_TASKS, "false").toString();
        return Boolean.parseBoolean(isLastTaskOfConvertedTasks);
    }


    public static List<String> complexNodeTypes = List.of(
            NodeType.NODE_EMAIL
            , NodeType.NODE_PUSH
            , NodeType.NODE_SMS
            , NodeType.NODE_MOBILE_IN_APP
            , NodeType.NODE_WEBHOOK);

    public static class NodeType {
        public static final String NODE_START = "start";
        public static final String NODE_EMAIL = "email";
        public static final String NODE_PUSH = "push";
        public static final String NODE_EVENT = "event";
        public static final String NODE_WAIT = "wait";
        public static final String NODE_SMS = "sms";
        public static final String NODE_MOBILE_IN_APP = "mobile_in_app";
        public static final String NODE_WEBHOOK = "webhook";
        public static final String NODE_TAG = "tag";
    }
}
