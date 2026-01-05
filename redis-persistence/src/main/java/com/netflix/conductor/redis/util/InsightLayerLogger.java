/*
 * Copyright 2023 Netflix, Inc.
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
package com.netflix.conductor.redis.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InsightLayerLogger {
    private static final Logger logger = LoggerFactory.getLogger(InsightLayerLogger.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String createTaskLogEntry(TaskModel taskModel) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode fields = objectMapper.createObjectNode();

            // Get data from TaskModel and generate Json string.
            // Optional fields currently not in use: Executed, Retried, RetryCount, InputPayload, OutputPayload, InputData, OutputData
            fields.put("ReferenceTaskName", taskModel.getReferenceTaskName());
            fields.put("Status", taskModel.getStatus().name());
            fields.put("TaskDefName", taskModel.getTaskDefName());
            fields.put("TaskType", taskModel.getTaskType());
            fields.put("WorkflowType", taskModel.getWorkflowType());
            fields.put("WorkflowInstanceId", taskModel.getWorkflowInstanceId());
            fields.put("EntityId", taskModel.getCorrelationId());
            fields.put("ReasonForIncompletion", taskModel.getReasonForIncompletion());
            fields.put("TimeStamp", convertTimeStamp());
            root.put("InfoType", "Task");
            root.set("fields", fields);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            String errorMsg = "Error serializing task model to JSON: " + taskModel.toString();
            logger.error(errorMsg, e);
            return "{}";
        }
    }

    public static String createWorkflowLogEntry(WorkflowModel workflowModel) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode fields = objectMapper.createObjectNode();

            // Get data from WorkflowModel and generate Json string.
            fields.put("WorkflowInstanceId", workflowModel.getWorkflowId());
            fields.put("WorkflowType", workflowModel.getWorkflowDefinition().getName());
            fields.put("Status", workflowModel.getStatus().name());
            fields.put("EntityType", workflowModel.getEntityType());
            fields.put("EntityId", workflowModel.getCorrelationId());
            fields.put("ReasonForIncompletion", workflowModel.getReasonForIncompletion());
            // Handle failedReferenceTaskNames
            if (workflowModel.getFailedReferenceTaskNames().isEmpty()) {
                fields.putArray("failedReferenceTaskNames");
            } else {
                ArrayNode failedNamesArray = fields.putArray("failedReferenceTaskNames");
                for (String failedTaskRefName : workflowModel.getFailedReferenceTaskNames()) {
                    failedNamesArray.add(failedTaskRefName);
                }
            }
            fields.put("TimeStamp", convertTimeStamp());
            root.put("InfoType", "Workflow");
            root.set("fields", fields);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            String errorMsg = "Error serializing workflow model to JSON: " + workflowModel.toString();
            logger.warn(errorMsg, e);
            return "{}";
        }
    }

    private static String convertTimeStamp() {
        // Get current time
        long currentTimeMillis = System.currentTimeMillis();
        // Time format: ISO 8601
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(
                java.time.Instant.ofEpochMilli(currentTimeMillis));
    }
}
