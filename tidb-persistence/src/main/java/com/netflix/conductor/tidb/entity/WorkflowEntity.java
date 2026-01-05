package com.netflix.conductor.tidb.entity;

import com.baomidou.mybatisplus.annotations.TableField;
import com.baomidou.mybatisplus.annotations.TableId;
import com.baomidou.mybatisplus.annotations.TableName;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.Date;
import java.util.Set;

@TableName("workflow")
public class WorkflowEntity {

    @TableField("workflowType")
    private String workflowType;

    @TableField("version")
    private Integer version;

    @TableId("workflowId")
    private String workflowId;

    @TableField("correlationId")
    private String correlationId;

    @TableField("startTime")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    @TableField("updateTime")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;

    @TableField("endTime")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    @TableField("status")
    private String status;

    @TableField("input")
    private String input;

    @TableField("output")
    private String output;

    @TableField("reasonForIncompletion")
    private String reasonForIncompletion;

    @TableField("executionTime")
    private Long executionTime;

    @TableField("inputSize")
    private Long inputSize;

    @TableField("outputSize")
    private Long outputSize;

    @TableField("event")
    private String event;

    @TableField("failedReferenceTaskNames")
    private String failedReferenceTaskNames;

    @TableField("externalInputPayloadStoragePath")
    private String externalInputPayloadStoragePath;

    @TableField("externalOutputPayloadStoragePath")
    private String externalOutputPayloadStoragePath;

    @TableField("rawJSON")
    private String rawJSON;

    @TableField("archived")
    private Boolean archived;

    @TableField("priority")
    private Integer priority;

    @TableField("failedTaskNames")
    private Set<String> failedTaskNames;

    public String getWorkflowType() {
        return workflowType;
    }

    public void setWorkflowType(String workflowType) {
        this.workflowType = workflowType;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    public void setEndTime(Date endTime) {
        this.endTime = endTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getReasonForIncompletion() {
        return reasonForIncompletion;
    }

    public void setReasonForIncompletion(String reasonForIncompletion) {
        this.reasonForIncompletion = reasonForIncompletion;
    }

    public Long getExecutionTime() {
        return executionTime;
    }

    public void setExecutionTime(Long executionTime) {
        this.executionTime = executionTime;
    }

    public Long getInputSize() {
        return inputSize;
    }

    public void setInputSize(Long inputSize) {
        this.inputSize = inputSize;
    }

    public Long getOutputSize() {
        return outputSize;
    }

    public void setOutputSize(Long outputSize) {
        this.outputSize = outputSize;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getFailedReferenceTaskNames() {
        return failedReferenceTaskNames;
    }

    public void setFailedReferenceTaskNames(String failedReferenceTaskNames) {
        this.failedReferenceTaskNames = failedReferenceTaskNames;
    }

    public String getExternalInputPayloadStoragePath() {
        return externalInputPayloadStoragePath;
    }

    public void setExternalInputPayloadStoragePath(String externalInputPayloadStoragePath) {
        this.externalInputPayloadStoragePath = externalInputPayloadStoragePath;
    }

    public String getExternalOutputPayloadStoragePath() {
        return externalOutputPayloadStoragePath;
    }

    public void setExternalOutputPayloadStoragePath(String externalOutputPayloadStoragePath) {
        this.externalOutputPayloadStoragePath = externalOutputPayloadStoragePath;
    }

    public String getRawJSON() {
        return rawJSON;
    }

    public void setRawJSON(String rawJSON) {
        this.rawJSON = rawJSON;
    }

    public Boolean getArchived() {
        return archived;
    }

    public void setArchived(Boolean archived) {
        this.archived = archived;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Set<String> getFailedTaskNames() {
        return failedTaskNames;
    }

    public void setFailedTaskNames(Set<String> failedTaskNames) {
        this.failedTaskNames = failedTaskNames;
    }

    @Override
    public String toString() {
        return "WorkflowEntity{" +
                "workflowType='" + workflowType + '\'' +
                ", version=" + version +
                ", workflowId='" + workflowId + '\'' +
                ", correlationId='" + correlationId + '\'' +
                ", startTime=" + startTime +
                ", updateTime=" + updateTime +
                ", endTime=" + endTime +
                ", status='" + status + '\'' +
                ", input='" + input + '\'' +
                ", output='" + output + '\'' +
                ", reasonForIncompletion='" + reasonForIncompletion + '\'' +
                ", executionTime=" + executionTime +
                ", inputSize=" + inputSize +
                ", outputSize=" + outputSize +
                ", event='" + event + '\'' +
                ", failedReferenceTaskNames='" + failedReferenceTaskNames + '\'' +
                ", externalInputPayloadStoragePath='" + externalInputPayloadStoragePath + '\'' +
                ", externalOutputPayloadStoragePath='" + externalOutputPayloadStoragePath + '\'' +
                ", rawJSON='" + rawJSON + '\'' +
                ", archived=" + archived +
                ", priority=" + priority +
                ", failedTaskNames='" + failedTaskNames + '\'' +
                '}';
    }
}
