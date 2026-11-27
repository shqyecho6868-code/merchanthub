package com.merchanthub.order.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.time.Instant;
import java.util.List;

/**
 * Pushes the backlog numbers to CloudWatch custom metrics (namespace MerchantHub/Order), where the Terraform alarm watches them.
 * <p>
 * Why push 4 numbers with PutMetricData instead of exporting all Micrometer metrics to CloudWatch: the alarm needs only these,
 * and CloudWatch bills per custom metric -- exporting hundreds of JVM / HTTP meters would cost far more than the signal is worth.
 * No dimensions: every order instance reports the same DB-wide counts, and the alarm uses Maximum, so duplicates don't matter.
 * Credentials and region come from the ECS task role and AWS_REGION (SDK default chain); the task role allows only this namespace.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "merchanthub.order.outbox.monitor.cloudwatch.enabled", havingValue = "true")
public class CloudWatchBacklogSink implements BacklogMetricsSink, DisposableBean {

    private final CloudWatchClient client;
    private final String namespace;

    public CloudWatchBacklogSink(@Value("${merchanthub.order.outbox.monitor.cloudwatch.namespace:MerchantHub/Order}") String namespace) {
        this.client = CloudWatchClient.builder().httpClient(UrlConnectionHttpClient.create()).build();
        this.namespace = namespace;
        log.info("Outbox backlog metrics will be pushed to CloudWatch namespace={}", namespace);
    }

    @Override
    public void publish(OutboxBacklogMonitor.Snapshot s) {
        Instant now = Instant.now();
        client.putMetricData(PutMetricDataRequest.builder()
                .namespace(namespace)
                .metricData(List.of(
                        datum("OutboxStalePending", s.stalePending(), now),
                        datum("OutboxPending", s.pending(), now),
                        datum("ExpiredPendingOrders", s.expiredPendingOrders(), now),
                        datum("OutboxFastPathDropped", s.fastPathDropped(), now)))
                .build());
    }

    private static MetricDatum datum(String name, long value, Instant ts) {
        return MetricDatum.builder().metricName(name).value((double) value).unit(StandardUnit.COUNT).timestamp(ts).build();
    }

    @Override
    public void destroy() {
        client.close();
    }
}
