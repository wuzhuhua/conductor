package com.netflix.conductor.core.journeyInsight.clickhouse;


import com.clickhouse.jdbc.ClickHouseDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.SQLException;

@Configuration
@Slf4j
@EnableConfigurationProperties(ChProperties.class)
@ConditionalOnProperty(value = "journey-insight.db.type", havingValue = "clickhouse")
public class ChConfig {

    final private ChProperties chProperties;

    @Autowired
    public ChConfig(ChProperties chProperties) {
        this.chProperties = chProperties;
    }

    @Bean
    public ClickHouseDataSource chDataSource() throws SQLException {
        log.info("Clickhouse data source has been created");
        return new ClickHouseDataSource(chProperties.getServerUrl());
    }

    @Bean
    public ClickHouseWriter clickHouseWriter(ClickHouseDataSource chDataSource) {
        return new ClickHouseWriter(chProperties, chDataSource);
    }
}
