package com.netflix.conductor.core.journeyInsight.clickhouse;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("journey-insight.clickhouse")
@Data
public class ChProperties {
    private String tableName = "user_journey_log";
    private String serverUrl;
    private String userName;
    private String password;
    private Integer batchFlushIntervalMillis = 5000;
    private Integer batchWriteMaxRecordLength = 100;
}
