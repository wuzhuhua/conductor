/*
 * Copyright 2024 Netflix, Inc.
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
package com.netflix.conductor.redis.dao;

import com.netflix.conductor.service.ExecutionLockService;
import lombok.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import com.netflix.conductor.core.config.ConductorProperties;
import com.netflix.conductor.dao.CountLimitingDAO;
import com.netflix.conductor.redis.config.AnyRedisCondition;
import com.netflix.conductor.redis.config.RedisProperties;
import com.netflix.conductor.redis.jedis.JedisProxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Response;

import java.time.OffsetDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(name = "conductor.db.type", havingValue = "redis_standalone")
public class RedisCountLimitingDAO extends BaseDynoDAO implements CountLimitingDAO {

    private static final String EXEC_COUNT_LIMIT_PER_ENTITY_PER_WORKFLOW_DEF =
            "EXEC_COUNT_LIMIT_PER_ENTITY_PER_WORKFLOW_DEF";
    private static final String EXEC_COUNT_LIMIT_PER_ENTITY_PER_WORKFLOW_DEF_M1 =
            "EXEC_COUNT_LIMIT_PER_ENTITY_PER_WORKFLOW_DEF_M1";

    protected RedisCountLimitingDAO(
            JedisProxy jedisProxy,
            ObjectMapper objectMapper,
            ConductorProperties conductorProperties,
            RedisProperties properties) {
        super(jedisProxy, objectMapper, conductorProperties, properties);
    }

    /**
     * Checks if the execution count exceeds the limit and increments the counter.
     *
     * @param entityID The entity ID.
     * @param workflowDefName The workflow definition name.
     * @param maxExecCount The maximum allowed execution count.
     * @return true if the execution count exceeds the limit, false otherwise.
     */
    @Override
    public boolean exceedsExecCountLimit(
            String entityID, String workflowDefName, Integer maxExecCount, Integer mode) {
        String key = getExecCountLimitNsKey(entityID, workflowDefName, mode);
        Long count = jedisProxy.incr(key);
        return count > maxExecCount;
    }

    private String getExecCountLimitNsKey(String entityID, String workflowDefName, Integer mode) {
        String key = null;
        switch (mode){
            case 0:{
                key = nsKey(EXEC_COUNT_LIMIT_PER_ENTITY_PER_WORKFLOW_DEF, entityID, workflowDefName);
                break;
            }

            case 1:{
                key = nsKey(EXEC_COUNT_LIMIT_PER_ENTITY_PER_WORKFLOW_DEF_M1, entityID, workflowDefName);
                break;
            }
            default:{
                throw new IllegalArgumentException("Not supported mode: "+ mode);
            }
        } return key;
    }


    @Override
    public boolean exceedsExecCountLimit(String entityID, String workflowDefName, Integer maxExecCount, @NonNull OffsetDateTime stopInEffectAfter, @NonNull Integer mode) {
        String key = getExecCountLimitNsKey(entityID, workflowDefName, mode);
        if (OffsetDateTime.now().isAfter(stopInEffectAfter)) {
            jedisProxy.del(key);
            return false;
        }
        Long count = jedisProxy.incr(key);
        return count > maxExecCount;
    }

    @Override
    public boolean exceedsExecCountLimit(String entityID, String workflowDefName, Integer maxExecCount, @NonNull Integer execLimitIntervalInSeconds, @NonNull Integer mode) {
        String key = getExecCountLimitNsKey(entityID, workflowDefName, mode);
        Long count = jedisProxy.incr(key);
        if(count == 1){
            jedisProxy.expire(key, execLimitIntervalInSeconds);
        }else {
            long ttlInSeconds = jedisProxy.ttl(key);
            if(ttlInSeconds == -1 || ttlInSeconds > execLimitIntervalInSeconds){
                jedisProxy.expire(key, execLimitIntervalInSeconds);
            }
        }
        return count > maxExecCount;
    }



    @Override
    public boolean resetAllExecCountLimit(String entityID, String workflowDefName) {
        String key = getExecCountLimitNsKey(entityID, workflowDefName,0);
        String m1Key = getExecCountLimitNsKey(entityID, workflowDefName,1);
        jedisProxy.del(key);
        jedisProxy.del(m1Key);
        return false;
    }
}
