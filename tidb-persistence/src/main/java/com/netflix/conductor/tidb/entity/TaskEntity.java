package com.netflix.conductor.tidb.entity;

import com.baomidou.mybatisplus.annotations.TableField;
import com.baomidou.mybatisplus.annotations.TableId;
import com.baomidou.mybatisplus.annotations.TableName;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.Date;

@TableName("task")
public class TaskEntity {

    @TableField("workflowId")
    private String workflowId;

    @TableField("correlationId")
    private String correlationId;

    @TableField("endTime")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    @TableField("executionTime")
    private Long executionTime;

    @TableField("input")
    private String input;

    @TableField("output")
    private String output;

    @TableField("queueWaitTime")
    private Long queueWaitTime;

    @TableField("reasonForIncompletion")
    private String reasonForIncompletion;

    @TableField("scheduledTime")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date scheduledTime;

    @TableField("startTime")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    @TableField("status")
    private String status;

    @TableField("taskDefName")
    private String taskDefName;

    @TableId("taskId")
    private String taskId;

    @TableField("taskType")
    private String taskType;

    @TableField("updateTime")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;

    @TableField("workflowType")
    private String workflowType;

    @TableField("domain")
    private String domain;

    @TableField("taskRefName")
    private String taskRefName;

    @TableField("workflowPriority")
    private Integer workflowPriority;

    @TableField("rawJSON")
    private String rawJSON;

    @TableField("archived")
    private Boolean archived;

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

    public Date getEndTime() {
        return endTime;
    }

    public void setEndTime(Date endTime) {
        this.endTime = endTime;
    }

    public Long getExecutionTime() {
        return executionTime;
    }

    public void setExecutionTime(Long executionTime) {
        this.executionTime = executionTime;
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

    public Long getQueueWaitTime() {
        return queueWaitTime;
    }

    public void setQueueWaitTime(Long queueWaitTime) {
        this.queueWaitTime = queueWaitTime;
    }

    public String getReasonForIncompletion() {
        return reasonForIncompletion;
    }

    public void setReasonForIncompletion(String reasonForIncompletion) {
        this.reasonForIncompletion = reasonForIncompletion;
    }

    public Date getScheduledTime() {
        return scheduledTime;
    }

    public void setScheduledTime(Date scheduledTime) {
        this.scheduledTime = scheduledTime;
    }

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTaskDefName() {
        return taskDefName;
    }

    public void setTaskDefName(String taskDefName) {
        this.taskDefName = taskDefName;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getWorkflowType() {
        return workflowType;
    }

    public void setWorkflowType(String workflowType) {
        this.workflowType = workflowType;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getTaskRefName() {
        return taskRefName;
    }

    public void setTaskRefName(String taskRefName) {
        this.taskRefName = taskRefName;
    }

    public Integer getWorkflowPriority() {
        return workflowPriority;
    }

    public void setWorkflowPriority(Integer workflowPriority) {
        this.workflowPriority = workflowPriority;
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

    @Override
    public String toString() {
        return "TaskEntity{" +
                "workflowId='" + workflowId + '\'' +
                ", correlationId='" + correlationId + '\'' +
                ", endTime=" + endTime +
                ", executionTime=" + executionTime +
                ", input='" + input + '\'' +
                ", output='" + output + '\'' +
                ", queueWaitTime=" + queueWaitTime +
                ", reasonForIncompletion='" + reasonForIncompletion + '\'' +
                ", scheduledTime=" + scheduledTime +
                ", startTime=" + startTime +
                ", status='" + status + '\'' +
                ", taskDefName='" + taskDefName + '\'' +
                ", taskId='" + taskId + '\'' +
                ", taskType='" + taskType + '\'' +
                ", updateTime=" + updateTime +
                ", workflowType='" + workflowType + '\'' +
                ", domain='" + domain + '\'' +
                ", taskRefName='" + taskRefName + '\'' +
                ", workflowPriority=" + workflowPriority +
                ", rawJSON='" + rawJSON + '\'' +
                ", archived=" + archived +
                '}';
    }
}
