package com.merchanthub.order.job;

/** Where backlog numbers go besides the Micrometer gauges. In the cloud: CloudWatch ({@link CloudWatchBacklogSink}); locally: none */
public interface BacklogMetricsSink {

    void publish(OutboxBacklogMonitor.Snapshot snapshot);
}
