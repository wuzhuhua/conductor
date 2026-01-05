package com.netflix.conductor.core.journeyInsight.timestream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.timestreamwrite.TimestreamWriteClient;

import java.time.Duration;


@Configuration
@ConditionalOnProperty(value = "journey-insight.db.type", havingValue = "timestream")
public class TimeStreamConfig {
    public static final Logger LOGGER = LoggerFactory.getLogger(TimeStreamWriter.class);
    @Bean
    public TimestreamWriteClient timestreamWriteClient() {
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder();
        httpClientBuilder.maxConnections(5000);

        RetryPolicy.Builder retryPolicy = RetryPolicy.builder();
        retryPolicy.numRetries(10);

        ClientOverrideConfiguration.Builder overrideConfig = ClientOverrideConfiguration.builder();
        overrideConfig.apiCallAttemptTimeout(Duration.ofSeconds(20));
        overrideConfig.retryPolicy(retryPolicy.build());
        TimestreamWriteClient client = TimestreamWriteClient.builder().httpClientBuilder(httpClientBuilder).overrideConfiguration(overrideConfig.build()).region(Region.US_WEST_2).build();
        LOGGER.info("Successfully initialize TimestreamWriteClient");
        return client ;
    }


}
