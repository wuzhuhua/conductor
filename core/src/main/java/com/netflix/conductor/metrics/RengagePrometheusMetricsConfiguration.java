package com.netflix.conductor.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import com.netflix.spectator.api.Spectator;
import com.netflix.spectator.micrometer.MicrometerRegistry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusRenameFilter;


@Configuration
public class RengagePrometheusMetricsConfiguration {
    public static final Logger LOGGER = LoggerFactory.getLogger("RengagePrometheusMetricsConfiguration");

    public RengagePrometheusMetricsConfiguration(MeterRegistry meterRegistry) {
        LOGGER.info("Prometheus metrics module initialized");
        final MicrometerRegistry metricsRegistry = new MicrometerRegistry(meterRegistry);
        meterRegistry.config().meterFilter(new PrometheusRenameFilter());
        Spectator.globalRegistry().add(metricsRegistry);
    }

}
