package com.netflix.conductor.dao;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
@ConditionalOnProperty(name = "conductor.db.type", havingValue = "memory")
public class InMemoryDummyCountLimitingDAO implements CountLimitingDAO{
    @Override
    public boolean exceedsExecCountLimit(String entityID, String workflowDefName, Integer maxExecCount, Integer mode) {
        return false;
    }

    @Override
    public boolean exceedsExecCountLimit(String entityID, String workflowDefName, Integer maxExecCount, Integer execLimitIntervalInSeconds, Integer mode) {
        return false;
    }

    @Override
    public boolean exceedsExecCountLimit(String entityID, String workflowDefName, Integer maxExecCount, OffsetDateTime stopInEffectAfter, Integer mode) {
        return false;
    }

    @Override
    public boolean resetAllExecCountLimit(String entityID, String workflowDefName) {
        return false;
    }
}
