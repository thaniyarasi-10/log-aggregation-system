package com.kovanlabs.logcontroller.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class KafkaConsumerConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Value("${spring.kafka.bootstrap-servers:${kafka.bootstrap-servers:172.16.30.10:9092}}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:${kafka.group-id:log-group}}")
    private String groupId;

    @Value("${spring.kafka.listener.auto-startup:${kafka.listener.auto-startup:true}}")
    private boolean listenerAutoStartup;

    // Number of concurrent consumer threads. Must be <= number of topic partitions.
    // Increasing this is the primary lever for reducing consumer lag.
    // Matches spring.kafka.listener.concurrency in application.yml.
    @Value("${spring.kafka.listener.concurrency:3}")
    private int concurrency;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // auto.offset.reset=latest: on first start (no committed offset) begin from the
        // end of the topic. Prevents replaying the entire historical backlog on restart.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        // Fetch up to 200 records per poll. Default is 500 — reduced here because each
        // record triggers a synchronous DB lookup (service approval check) and an ES write.
        // Keeping batches smaller prevents max.poll.interval.ms violations when ES is slow.
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);

        // Wait up to 500ms for at least 1 byte before returning an empty poll.
        // Reduces CPU spin when the topic is idle.
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);

        // Heartbeat interval — must be < session.timeout.ms / 3.
        // session.timeout.ms is 30000ms (set in application.yml), so 8000ms is safe.
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 8000);

        // max.poll.interval.ms: how long between polls before the broker considers the
        // consumer dead and triggers a rebalance. 300s gives plenty of headroom for
        // slow ES writes without causing unnecessary rebalances.
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);

        LOGGER.info("Kafka consumer configured — brokers={} groupId={} concurrency={} maxPollRecords=200",
                bootstrapServers, groupId, concurrency);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory());
        factory.setAutoStartup(listenerAutoStartup);

        // Concurrency = number of consumer threads spawned per listener.
        // Each thread owns one or more partitions. With concurrency=3 and 3 partitions,
        // each thread processes one partition independently — tripling throughput.
        // NOTE: concurrency > partition count wastes threads (extra threads sit idle).
        factory.setConcurrency(concurrency);

        // BATCH_MODE disabled (default) — process one record at a time.
        // This keeps error handling simple: a single bad record doesn't block the batch.

        // AckMode.RECORD: commit offset after each record is successfully processed.
        // This ensures that if the consumer crashes mid-batch, only the committed records
        // are skipped on restart — no duplicate processing of already-indexed logs.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        return factory;
    }
}