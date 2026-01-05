package com.netflix.conductor.core.journeyInsight.timestream;


import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(value = "aws.timestream")
@Component
@ConditionalOnProperty(value = "journey-insight.db.type", havingValue = "timestream")
@Data
public class TimestreamProperties {
    private String tableName;
    private String databaseName;
    private Integer batchSize = 10;
    private Integer batchFlushIntervalMillis = 5000;
    private Integer batchWriteMaxRecordLength = 100;
}
