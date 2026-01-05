package com.netflix.conductor.core.journeyInsight.constant;

public class WorkflowTaskCommonInputKey {
    public static final String NODE_TYPE = "nodeType";
    public static final String NODE_ID = "nodeId";
    public static final String REQUEST_UUID = "requestUuid";
    public static final String RECEIVED_AT = "receivedAt";
    public static final String TEMPLATE_ID = "templateId";
    public static final String MESSAGE_ID = "messageId";

    /**
     * for nodes typed as SMS/ Email/ Mobile in-app/ Push/ Webhook, a node would be converted to multiple workflow tasks
     * only the last one would do the real job like sending email/ calling webhook
     */
    public static final String IS_LAST_TASK_OF_CONVERTED_TASKS = "isLastTaskOfConvertedTasks";
    public static final String GOAL_STATE = "isGoalState";
    public static final String IS_LEAF_NODE = "isLeafNode";
    public static final String VERSION = "version";
    public static final String TENANT_NAME = "tenantName";
}