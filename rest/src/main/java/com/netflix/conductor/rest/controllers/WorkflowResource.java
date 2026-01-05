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
package com.netflix.conductor.rest.controllers;

import com.netflix.conductor.common.metadata.workflow.RerunWorkflowRequest;
import com.netflix.conductor.common.metadata.workflow.SkipTaskRequest;
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import com.netflix.conductor.common.model.BulkResponse;
import com.netflix.conductor.common.run.*;
import com.netflix.conductor.core.exception.NotFoundException;
import com.netflix.conductor.service.WorkflowBulkService;
import com.netflix.conductor.service.WorkflowService;
import com.netflix.conductor.service.WorkflowTestService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import javax.validation.constraints.Size;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.netflix.conductor.rest.config.RequestMappingConstants.WORKFLOW;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

@RestController
@RequestMapping(WORKFLOW)
public class WorkflowResource {

    private final WorkflowService workflowService;
    private final WorkflowBulkService workflowBulkService;

    private final WorkflowTestService workflowTestService;

    public WorkflowResource(
            WorkflowService workflowService, WorkflowBulkService workflowBulkService, WorkflowTestService workflowTestService) {
        this.workflowService = workflowService;
        this.workflowBulkService = workflowBulkService;
        this.workflowTestService = workflowTestService;
    }

    @PostMapping(produces = TEXT_PLAIN_VALUE)
    @Operation(
            summary =
                    "Start a new workflow with StartWorkflowRequest, which allows task to be executed in a domain")
    public String startWorkflow(@RequestBody StartWorkflowRequest request) {
        return workflowService.startWorkflow(request);
    }

    @PostMapping(value = "/{name}", produces = TEXT_PLAIN_VALUE)
    @Operation(
            summary =
                    "Start a new workflow. Returns the ID of the workflow instance that can be later used for tracking")
    public String startWorkflow(
            @PathVariable("name") String name,
            @RequestParam(value = "version", required = false) Integer version,
            @RequestParam(value = "correlationId", required = false) String correlationId,
            @RequestParam(value = "priority", defaultValue = "0", required = false) int priority,
            @RequestBody Map<String, Object> input) {
        return workflowService.startWorkflow(name, version, correlationId, priority, input);
    }

    @GetMapping("/{name}/correlated/{correlationId}")
    @Operation(summary = "Lists workflows for the given correlation id")
    public List<Workflow> getWorkflows(
            @PathVariable("name") String name,
            @PathVariable("correlationId") String correlationId,
            @RequestParam(value = "includeClosed", defaultValue = "false", required = false)
                    boolean includeClosed,
            @RequestParam(value = "includeTasks", defaultValue = "false", required = false)
                    boolean includeTasks) {
        return workflowService.getWorkflows(name, correlationId, includeClosed, includeTasks);
    }

    @PostMapping(value = "/{name}/correlated")
    @Operation(summary = "Lists workflows for the given correlation id list")
    public Map<String, List<Workflow>> getWorkflows(
            @PathVariable("name") String name,
            @RequestParam(value = "includeClosed", defaultValue = "false", required = false)
                    boolean includeClosed,
            @RequestParam(value = "includeTasks", defaultValue = "false", required = false)
                    boolean includeTasks,
            @RequestBody List<String> correlationIds) {
        return workflowService.getWorkflows(name, includeClosed, includeTasks, correlationIds);
    }

    @GetMapping("/{workflowId}")
    @Operation(summary = "Gets the workflow by workflow id")
    public Workflow getExecutionStatus(
            @PathVariable("workflowId") String workflowId,
            @RequestParam(value = "includeTasks", defaultValue = "true", required = false)
                    boolean includeTasks) {
        return workflowService.getExecutionStatus(workflowId, includeTasks);
    }

    @DeleteMapping("/{workflowId}/remove")
    @Operation(summary = "Removes the workflow from the system")
    public void delete(
            @PathVariable("workflowId") String workflowId,
            @RequestParam(value = "archiveWorkflow", defaultValue = "true", required = false)
                    boolean archiveWorkflow) {
        workflowService.deleteWorkflow(workflowId, archiveWorkflow);
    }

    @GetMapping("/running/{name}")
    @Operation(summary = "Retrieve all the running workflows")
    public List<String> getRunningWorkflow(
            @PathVariable("name") String workflowName,
            @RequestParam(value = "version", defaultValue = "1", required = false) int version,
            @RequestParam(value = "startTime", required = false) Long startTime,
            @RequestParam(value = "endTime", required = false) Long endTime) {
        return workflowService.getRunningWorkflows(workflowName, version, startTime, endTime);
    }

    @PutMapping("/decide/{workflowId}")
    @Operation(summary = "Starts the decision task for a workflow")
    public void decide(@PathVariable("workflowId") String workflowId) {
        workflowService.decideWorkflow(workflowId);
    }

    @PutMapping("/{workflowId}/pause")
    @Operation(summary = "Pauses the workflow")
    public void pauseWorkflow(@PathVariable("workflowId") String workflowId) {
        workflowService.pauseWorkflow(workflowId);
    }

    @PutMapping("/{workflowId}/resume")
    @Operation(summary = "Resumes the workflow")
    public void resumeWorkflow(@PathVariable("workflowId") String workflowId) {
        workflowService.resumeWorkflow(workflowId);
    }

    @PutMapping("/{workflowId}/skiptask/{taskReferenceName}")
    @Operation(summary = "Skips a given task from a current running workflow")
    public void skipTaskFromWorkflow(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("taskReferenceName") String taskReferenceName,
            SkipTaskRequest skipTaskRequest) {
        workflowService.skipTaskFromWorkflow(workflowId, taskReferenceName, skipTaskRequest);
    }

    @PostMapping(value = "/{workflowId}/rerun", produces = TEXT_PLAIN_VALUE)
    @Operation(summary = "Reruns the workflow from a specific task")
    public String rerun(
            @PathVariable("workflowId") String workflowId,
            @RequestBody RerunWorkflowRequest request) {
        return workflowService.rerunWorkflow(workflowId, request);
    }

    @PostMapping("/{workflowId}/restart")
    @Operation(summary = "Restarts a completed workflow")
    @ResponseStatus(
            value = HttpStatus.NO_CONTENT) // for backwards compatibility with 2.x client which
    // expects a 204 for this request
    public void restart(
            @PathVariable("workflowId") String workflowId,
            @RequestParam(value = "useLatestDefinitions", defaultValue = "false", required = false)
                    boolean useLatestDefinitions) {
        workflowService.restartWorkflow(workflowId, useLatestDefinitions);
    }

    @PostMapping("/{workflowId}/retry")
    @Operation(summary = "Retries the last failed task")
    @ResponseStatus(
            value = HttpStatus.NO_CONTENT) // for backwards compatibility with 2.x client which
    // expects a 204 for this request
    public void retry(
            @PathVariable("workflowId") String workflowId,
            @RequestParam(
                            value = "resumeSubworkflowTasks",
                            defaultValue = "false",
                            required = false)
                    boolean resumeSubworkflowTasks) {
        workflowService.retryWorkflow(workflowId, resumeSubworkflowTasks);
    }

    @PostMapping("/{workflowId}/resetcallbacks")
    @Operation(summary = "Resets callback times of all non-terminal SIMPLE tasks to 0")
    @ResponseStatus(
            value = HttpStatus.NO_CONTENT) // for backwards compatibility with 2.x client which
    // expects a 204 for this request
    public void resetWorkflow(@PathVariable("workflowId") String workflowId) {
        workflowService.resetWorkflow(workflowId);
    }

    @DeleteMapping("/{workflowId}")
    @Operation(summary = "Terminate workflow execution")
    public void terminate(
            @PathVariable("workflowId") String workflowId,
            @RequestParam(value = "reason", required = false) String reason) {
        workflowService.terminateWorkflow(workflowId, reason);
    }

    @Operation(
            summary = "Search for workflows based on payload and other parameters",
            description =
                    "use sort options as sort=<field>:ASC|DESC e.g. sort=name&sort=workflowId:DESC."
                            + " If order is not specified, defaults to ASC.")
    @GetMapping(value = "/search")
    public SearchResult<WorkflowSummary> search(
            @RequestParam(value = "start", defaultValue = "0", required = false) int start,
            @RequestParam(value = "size", defaultValue = "100", required = false) int size,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "freeText", defaultValue = "*", required = false) String freeText,
            @RequestParam(value = "query", required = false) String query) {
        return workflowService.searchWorkflows(start, size, sort, freeText, query);
    }

    @Operation(
            summary = "Search for workflows based on payload and other parameters",
            description =
                    "use sort options as sort=<field>:ASC|DESC e.g. sort=name&sort=workflowId:DESC."
                            + " If order is not specified, defaults to ASC.")
    @GetMapping(value = "/search-v2")
    public SearchResult<Workflow> searchV2(
            @RequestParam(value = "start", defaultValue = "0", required = false) int start,
            @RequestParam(value = "size", defaultValue = "100", required = false) int size,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "freeText", defaultValue = "*", required = false) String freeText,
            @RequestParam(value = "query", required = false) String query) {
        return workflowService.searchWorkflowsV2(start, size, sort, freeText, query);
    }

    @Operation(
            summary = "Search for workflows based on task parameters",
            description =
                    "use sort options as sort=<field>:ASC|DESC e.g. sort=name&sort=workflowId:DESC."
                            + " If order is not specified, defaults to ASC")
    @GetMapping(value = "/search-by-tasks")
    public SearchResult<WorkflowSummary> searchWorkflowsByTasks(
            @RequestParam(value = "start", defaultValue = "0", required = false) int start,
            @RequestParam(value = "size", defaultValue = "100", required = false) int size,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "freeText", defaultValue = "*", required = false) String freeText,
            @RequestParam(value = "query", required = false) String query) {
        return workflowService.searchWorkflowsByTasks(start, size, sort, freeText, query);
    }

    @Operation(
            summary = "Search for workflows based on task parameters",
            description =
                    "use sort options as sort=<field>:ASC|DESC e.g. sort=name&sort=workflowId:DESC."
                            + " If order is not specified, defaults to ASC")
    @GetMapping(value = "/search-by-tasks-v2")
    public SearchResult<Workflow> searchWorkflowsByTasksV2(
            @RequestParam(value = "start", defaultValue = "0", required = false) int start,
            @RequestParam(value = "size", defaultValue = "100", required = false) int size,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "freeText", defaultValue = "*", required = false) String freeText,
            @RequestParam(value = "query", required = false) String query) {
        return workflowService.searchWorkflowsByTasksV2(start, size, sort, freeText, query);
    }

    @Operation(
            summary =
                    "Get the uri and path of the external storage where the workflow payload is to be stored")
    @GetMapping("/externalstoragelocation")
    public ExternalStorageLocation getExternalStorageLocation(
            @RequestParam("path") String path,
            @RequestParam("operation") String operation,
            @RequestParam("payloadType") String payloadType) {
        return workflowService.getExternalStorageLocation(path, operation, payloadType);
    }

    @PostMapping(value = "test", produces = APPLICATION_JSON_VALUE)
    @Operation(summary = "Test workflow execution using mock data")
    public Workflow testWorkflow(@RequestBody WorkflowTestRequest request) {
        return workflowTestService.testWorkflow(request);
    }

    @GetMapping("/latest-wf-finish-iso-time")
    public Map<String, Object> getLatestWorkflowFinishDatetimeByCorrelationId(
            @RequestParam("correlationId") String correlationId) {
        try {
            long epochTimestampMilliseconds = workflowService.getLatestFinishTimestampByCorrelationId(correlationId);

            // Convert epoch timestamp to Instant
            Instant instant = Instant.ofEpochMilli(epochTimestampMilliseconds);

            // Create a DateTimeFormatter for ISO 8601 format with UTC timezone
            DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

            // Format the Instant to ISO 8601 string
            String iso8601String = formatter.format(instant);
            return Map.of("latest-wf-finish-iso-time", iso8601String);
        } catch (NotFoundException e) {
            return Map.of("latest-wf-finish-iso-time", "1970-01-01T00:00:00.000Z");
        }

    }

    @GetMapping("/running-wf-ids")
    public List<String> getRunningWorkflowIds(
            @RequestParam(value = "correlationId", required = true) String correlationId) {
        return workflowService.runningWfIdsByCorrelationId(correlationId);
    }

    @GetMapping("/corr-id-running-wf-count")
    public Map<String, Object> isCorrIdInRunningWf(
            @RequestParam(value = "correlationId", required = true) String correlationId
    ) {
        List<String> runningWfIds = workflowService.runningWfIdsByCorrelationId(correlationId);
        return Map.of("corr-id-running-wf-count", runningWfIds != null ? runningWfIds.size() : 0);
    }

    @DeleteMapping("/removePendingWorkflowExecution/{workflowName}")
    public void removePendingWorkflowExecution(
            @PathVariable(value = "workflowName", required = true) String workflowName) {
        // will remove all the version of workflow name since all the same workflowName in the same key
        //  if it has too much workflow id ,maybe should use time to filter
        workflowService.removePendingWorkflowExecution(workflowName);
    }

    @PostMapping("/terminatePendingWorkflowExecutions/{workflowName}")
    public BulkResponse terminatePendingWorkflowExecutions(
            @PathVariable(value = "workflowName", required = true) String workflowName,
            @RequestParam(value = "version", required = false) Integer version,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "batchSize", required = false, defaultValue = "1000") @Size(
                    max = 1000,
                    message =
                            "Cannot process more than {max} workflows in one batch. Please use multiple requests.")Integer batchSize,
            @RequestParam(value = "batchIntervalInMs", required = false, defaultValue = "100") @Size(min = 10) Integer batchIntervalInMs) {
        List<String> workflowIds = workflowService.getRunningWorkflows(workflowName, version, null, null);
        List<BulkResponse> mergedRsp = new ArrayList<>();
        for (int i = 0; i < workflowIds.size(); i += batchSize) {
            int end = Math.min(i + batchSize, workflowIds.size());
            List<String> batch = workflowIds.subList(i, end);
            BulkResponse response = workflowBulkService.terminate(batch, reason);
            mergedRsp.add(response);
            try {
                Thread.sleep(batchIntervalInMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        return BulkResponse.mergeResponses(mergedRsp);
    }

    @GetMapping("/getWorkflowFromRedis/{workflowId}")
    public Workflow getWorkflowFromRedis(@PathVariable("workflowId") String workflowId,
                                              @RequestParam(value = "includeTasks", defaultValue = "true", required = false) boolean includeTasks){
        return workflowService.getExecutionStatusFromRedis(workflowId,includeTasks);
    }


    @PostMapping("/removeDeciderQueue/{workflowId}")
    public void removeDeciderQueue(@PathVariable("workflowId") String workflowId){
         workflowService.removeWorkflowFromDeciderQueue(workflowId);
    }

    @PostMapping("/updateESIndex/{workflowId}")
    public void updateESIndex(@PathVariable("workflowId") String workflowId){
         workflowService.updateIndexStatus(workflowId);
    }

    @GetMapping("/getRedisByKey/{key}")
    public Object getRedisByKey(@PathVariable("key") String key){
        return workflowService.getRedisByKey(key);
    }

    @PostMapping("/removeRedis/{taskId}")
    public boolean removeRedis(@PathVariable("taskId") String taskId, @RequestParam(value = "workflowId", required = false)String workflowId){
        return workflowService.removeRedis(taskId,workflowId);
    }

    @PostMapping("/cleanupRedisByKey/{key}/{value}")
    public void cleanupRedisByKey(@PathVariable("key") String key,@PathVariable(value = "value") String value,
                                  @RequestParam(value = "isHash", defaultValue = "true", required = false) boolean isHash,
                                  @RequestParam(value = "isSet", defaultValue = "true", required = false) boolean isSet){
        workflowService.cleanupRedisByKey(key,value,isHash,isSet);
    }

}

