package org.testcontainers.containers;

import org.testcontainers.utility.Base58;
import org.testcontainers.utility.TestcontainersConfiguration;

import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * This container wraps Confluent Kafka and Zookeeper (optionally)
 *
 */
public class KafkaContainer extends GenericContainer<KafkaContainer> {

    public static final int KAFKA_PORT = 9092;

    public static final int ZOOKEEPER_PORT = 2181;

    private Consumer<Builder> builderConsumer;

    public static class Builder extends GenericContainer.Builder {

        private final KafkaContainer container;

        public Builder(KafkaContainer container) {
            super(container);
            this.container = container;
        }

        public void withEmbeddedZookeeper() {
            container.externalZookeeperConnect = null;
        }

        public void withExternalZookeeper(String connectString) {
            container.externalZookeeperConnect = connectString;
        }
    }

    protected String externalZookeeperConnect = null;

    protected SocatContainer proxy;

    public KafkaContainer(Consumer<Builder> builderConsumer) {
        this();

        this.builderConsumer = b -> {
            b.withNetwork(Network.newNetwork());
            String networkAlias = "kafka-" + Base58.randomString(6);
            b.withNetworkAliases(networkAlias);
            b.withExposedPorts(KAFKA_PORT);

            // Use two listeners with different names, it will force Kafka to communicate with itself via internal
            // listener when KAFKA_INTER_BROKER_LISTENER_NAME is set, otherwise Kafka will try to use the advertised listener
            b.withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,BROKER://" + networkAlias + ":9093");
            b.withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "BROKER:PLAINTEXT,PLAINTEXT:PLAINTEXT");
            b.withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "BROKER");

            b.withEnv("KAFKA_BROKER_ID", "1");
            b.withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1");
            b.withEnv("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", "1");
            b.withEnv("KAFKA_LOG_FLUSH_INTERVAL_MESSAGES", Long.MAX_VALUE + "");

            builderConsumer.accept(b);
        };
    }

    public KafkaContainer() {
        this("4.0.0");
    }

    public KafkaContainer(String confluentPlatformVersion) {
        super(TestcontainersConfiguration.getInstance().getKafkaImage() + ":" + confluentPlatformVersion);

        builderConsumer = b -> {};
        withNetwork(Network.newNetwork());
        String networkAlias = "kafka-" + Base58.randomString(6);
        withNetworkAliases(networkAlias);
        withExposedPorts(KAFKA_PORT);

        // Use two listeners with different names, it will force Kafka to communicate with itself via internal
        // listener when KAFKA_INTER_BROKER_LISTENER_NAME is set, otherwise Kafka will try to use the advertised listener
        withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,BROKER://" + networkAlias + ":9093");
        withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "BROKER:PLAINTEXT,PLAINTEXT:PLAINTEXT");
        withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "BROKER");

        withEnv("KAFKA_BROKER_ID", "1");
        withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1");
        withEnv("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", "1");
        withEnv("KAFKA_LOG_FLUSH_INTERVAL_MESSAGES", Long.MAX_VALUE + "");
    }

    @Deprecated
    public KafkaContainer withEmbeddedZookeeper() {
        builderConsumer = builderConsumer.andThen(b -> b.withEmbeddedZookeeper());
        return self();
    }

    @Deprecated
    public KafkaContainer withExternalZookeeper(String connectString) {
        builderConsumer = builderConsumer.andThen(b -> b.withExternalZookeeper(connectString));
        return self();
    }

    public String getBootstrapServers() {
        return String.format("PLAINTEXT://%s:%s", proxy.getContainerIpAddress(), proxy.getFirstMappedPort());
    }

    @Override
    public void start() {
        if (builderConsumer != null) {
            builderConsumer.accept(new Builder(this));
        }

        String networkAlias = getNetworkAliases().get(0);
        proxy = new SocatContainer()
            .withNetwork(getNetwork())
            .withTarget(9092, networkAlias)
            .withTarget(2181, networkAlias);

        proxy.start();
        withEnv("KAFKA_ADVERTISED_LISTENERS", "BROKER://" + networkAlias + ":9093,PLAINTEXT://" + proxy.getContainerIpAddress() + ":" + proxy.getFirstMappedPort());

        if (externalZookeeperConnect != null) {
            withEnv("KAFKA_ZOOKEEPER_CONNECT", externalZookeeperConnect);
        } else {
            addExposedPort(ZOOKEEPER_PORT);
            withEnv("KAFKA_ZOOKEEPER_CONNECT", "localhost:2181");
            withClasspathResourceMapping("tc-zookeeper.properties", "/zookeeper.properties", BindMode.READ_ONLY);
            withCommand("sh", "-c", "zookeeper-server-start /zookeeper.properties & /etc/confluent/docker/run");
        }

        super.start();
    }

    @Override
    public void stop() {
        Stream.<Runnable>of(super::stop, proxy::stop).parallel().forEach(Runnable::run);
    }
}