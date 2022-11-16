package org.openl.itest;

import static org.apache.kafka.clients.consumer.ConsumerConfig.METADATA_MAX_AGE_CONFIG;
import static org.junit.Assert.assertEquals;
import static org.openl.rules.ruleservice.kafka.KafkaHeaders.CORRELATION_ID;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.ImmutableMap;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openl.itest.core.HttpClient;
import org.openl.itest.core.JettyServer;
import org.openl.rules.ruleservice.kafka.KafkaHeaders;

import org.rnorth.ducttape.unreliables.Unreliables;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;


public class RunKafkaSmokeITest {
    private static JettyServer server;
    private static HttpClient client;

    private static final DockerImageName KAFKA_TEST_IMAGE = DockerImageName.parse("confluentinc/cp-kafka:7.3.0");
    private static final KafkaContainer KAFKA_CONTAINER = new KafkaContainer(KAFKA_TEST_IMAGE).withReuse(true);

    private static final String KAFKA_SERVER_ADDRESS = System.getProperty("ruleservice.kafka.bootstrap.servers");

    @BeforeClass
    public static void setUp() throws Exception {
        KAFKA_CONTAINER.start();
        // TODO consider to use application.properties or servlet context instead of direct setting with System.setProperty
        System.setProperty("ruleservice.kafka.bootstrap.servers", "localhost:" + KAFKA_CONTAINER.getBootstrapServers().split(":")[2]);

        server = JettyServer.start();
        client = server.client();
    }

    @Test
    public void testRest() {
        client.send("simple1");
    }

    @Test
    public void methodSimpleOk() {
        testKafka("hello-in-topic", "hello-out-topic", "key1", "{\"hour\": 5}", "Good Morning");
    }

    @Test
    public void methodSimpleFail() {
        try (KafkaProducer<String, String> producer = createKafkaProducer(KAFKA_CONTAINER.getBootstrapServers());
             KafkaConsumer<String, String> consumer = createKafkaConsumer(KAFKA_CONTAINER.getBootstrapServers())) {
            consumer.subscribe(Collections.singletonList("hello-dlt-topic"));

            producer.send(new ProducerRecord<>("hello-in-topic", "key1", "5"));

            checkKafkaResponse(consumer, "key1", "5");

            producer.send(new ProducerRecord<>("hello-in-topic", "key1", "{\"hour\": 22}"));
            Unreliables.retryUntilTrue(
                    20,
                    TimeUnit.SECONDS,
                    () -> {
                        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                        if (records.isEmpty()) {
                            return false;
                        }
                        assertEquals(1, records.count());
                        ConsumerRecord<String, String> response = records.iterator().next();
                        assertEquals(response.value(), "{\"hour\": 22}");
                        assertEquals(response.key(), "key1");
                        Assert.assertEquals("fail", getHeaderValue(response, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
                        return true;
                    }
            );

            consumer.unsubscribe();
        }
    }

    @Test
    public void serviceSimpleOk() {
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>("hello-in-topic-2", "key1", "{\"hour\": 5}");
        addHeader(producerRecord, KafkaHeaders.METHOD_NAME, "Hello");
        testKafka(producerRecord, "hello-out-topic-2", "Good Morning");
    }

    @Test
    public void serviceSimpleFail() {
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>("hello-in-topic-2", "key1", "5");
        addHeader(producerRecord, KafkaHeaders.METHOD_NAME, "Hello");
        testKafka(producerRecord, "hello-dlt-topic-2", "5");
    }

    @Test
    public void methodSimpleOkWithReplyTopic() {
        final String replyTopic = UUID.randomUUID().toString();
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>("hello-in-topic", "key1", "{\"hour\": 5}");
        addHeader(producerRecord, KafkaHeaders.REPLY_TOPIC, replyTopic);
        testKafka(producerRecord, replyTopic, "Good Morning");
    }

    @Test
    public void serviceSimpleOkWithReplyTopic() {
        final String replyTopic = UUID.randomUUID().toString();
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>("hello-in-topic-2", "key1", "{\"hour\": 5}");
        addHeader(producerRecord, KafkaHeaders.METHOD_NAME, "Hello");
        addHeader(producerRecord, KafkaHeaders.REPLY_TOPIC, replyTopic);
        testKafka(producerRecord, replyTopic, "Good Morning");
    }

    @Test
    public void methodSimpleOkWithCorrelationId() {
        final String replyTopic = UUID.randomUUID().toString();
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>("hello-in-topic", "key1", "{\"hour\": 5}");
        addHeader(producerRecord, KafkaHeaders.REPLY_TOPIC, replyTopic);
        addHeader(producerRecord, CORRELATION_ID, "42");
        testKafka(producerRecord, replyTopic, "Good Morning");
    }

    @Test
    public void serviceSimpleOkWithCorrelationId() {
        final String replyTopic = UUID.randomUUID().toString();
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>("hello-in-topic-2", "key1", "{\"hour\": 5}");
        addHeader(producerRecord, KafkaHeaders.METHOD_NAME, "Hello");
        addHeader(producerRecord, KafkaHeaders.REPLY_TOPIC, replyTopic);
        addHeader(producerRecord, CORRELATION_ID, "42");
        testKafka(producerRecord, replyTopic, "Good Morning");
    }

    private static final String HELLO_REPLY_DLT_TOPIC = "hello-replydlt-topic";

    @Test
    public void testDltHeaders() throws Exception {
        try (KafkaProducer<String, String> producer = createKafkaProducer(KAFKA_CONTAINER.getBootstrapServers());
             KafkaConsumer<String, String> consumer = createKafkaConsumer(KAFKA_CONTAINER.getBootstrapServers())) {

            AdminClient adminClient = AdminClient.create(
                    ImmutableMap.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers())
            );
            Collection<NewTopic> topics = Collections.singletonList(new NewTopic(HELLO_REPLY_DLT_TOPIC, 10, (short) 1));
            adminClient.createTopics(topics).all().get(30, TimeUnit.SECONDS);

            final String replyTopic = UUID.randomUUID().toString();
            ProducerRecord<String, String> producerRecord = new ProducerRecord<>("hello-in-topic-2", null, "5");
            addHeader(producerRecord, KafkaHeaders.METHOD_NAME, "Hello");
            addHeader(producerRecord, KafkaHeaders.REPLY_TOPIC, replyTopic);
            addHeader(producerRecord, KafkaHeaders.REPLY_PARTITION, "891");
            addHeader(producerRecord, KafkaHeaders.REPLY_DLT_PARTITION, "5");
            addHeader(producerRecord, KafkaHeaders.REPLY_DLT_TOPIC, HELLO_REPLY_DLT_TOPIC);
            addHeader(producerRecord, KafkaHeaders.CORRELATION_ID, "42");
            consumer.subscribe(Collections.singletonList(HELLO_REPLY_DLT_TOPIC));
            producer.send(producerRecord);

            Unreliables.retryUntilTrue(
                    20,
                    TimeUnit.SECONDS,
                    () -> {
                        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                        if (records.isEmpty()) {
                            return false;
                        }
                        assertEquals(1, records.count());
                        ConsumerRecord<String, String> response = records.iterator().next();
                        assertEquals(response.value(), "5");

                        Assert.assertEquals("42", getHeaderValue(response, KafkaHeaders.CORRELATION_ID));
                        Assert.assertEquals("Hello", getHeaderValue(response, KafkaHeaders.METHOD_NAME));
                        Assert.assertEquals(replyTopic, getHeaderValue(response, KafkaHeaders.REPLY_TOPIC));
                        Assert.assertEquals("891", getHeaderValue(response, KafkaHeaders.REPLY_PARTITION));
                        Assert.assertEquals("org.openl.rules.ruleservice.kafka.ser.RequestMessageFormatException",
                                getHeaderValue(response, KafkaHeaders.DLT_EXCEPTION_FQCN));
                        Assert.assertEquals("Invalid message format.", getHeaderValue(response, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
                        Assert.assertNotNull(response.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET));
                        Assert.assertNotNull(response.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION));
                        Assert.assertNotNull(response.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE));
                        Assert.assertEquals("hello-in-topic-2", getHeaderValue(response, KafkaHeaders.DLT_ORIGINAL_TOPIC));
                        return true;
                    }
            );
            consumer.unsubscribe();
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        // TODO consider to use application.properties or servlet context instead of direct setting with System.setProperty
        // return previous values
        Optional.ofNullable(KAFKA_SERVER_ADDRESS).ifPresentOrElse(
                address -> System.setProperty("ruleservice.kafka.bootstrap.servers", address),
                () -> System.getProperties().remove("ruleservice.kafka.bootstrap.servers"));

        server.stop();
        KAFKA_CONTAINER.stop();
    }

    private static void addHeader(ProducerRecord<String, String> producerRecord, String key, String value) {
        producerRecord.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void testKafka(String inTopic, String outTopic, String key, String value, String expectedValue) {
        testKafka(new ProducerRecord<>(inTopic, key, value), outTopic,expectedValue);
    }

    private static void testKafka(ProducerRecord<String, String> producerRecord, String outTopic, String expectedValue) {
        try (KafkaProducer<String, String> producer = createKafkaProducer(KAFKA_CONTAINER.getBootstrapServers());
             KafkaConsumer<String, String> consumer = createKafkaConsumer(KAFKA_CONTAINER.getBootstrapServers())) {
            consumer.subscribe(Collections.singletonList(outTopic));
            producer.send(producerRecord);

            checkKafkaResponse(consumer, producerRecord.key(), expectedValue);
            consumer.unsubscribe();
        }
    }

    private static KafkaProducer<String, String> createKafkaProducer(String bootstrapServers) {
        return new KafkaProducer<>(
                ImmutableMap.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        bootstrapServers,
                        ProducerConfig.CLIENT_ID_CONFIG,
                        UUID.randomUUID().toString()
                ),
                new StringSerializer(),
                new StringSerializer()
        );
    }

    private static KafkaConsumer<String, String> createKafkaConsumer(String bootstrapServers) {
        return new KafkaConsumer<>(
                ImmutableMap.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                        ConsumerConfig.GROUP_ID_CONFIG, "junit",
                        METADATA_MAX_AGE_CONFIG, 1000,
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
                ),
                new StringDeserializer(),
                new StringDeserializer()
        );
    }

    private static void checkKafkaResponse(KafkaConsumer<String, String> consumer, String expectedKey, String expectedValue) {
        Unreliables.retryUntilTrue(
                20,
                TimeUnit.SECONDS,
                () -> {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                    if (records.isEmpty()) {
                        return false;
                    }
                    assertEquals(1, records.count());
                    ConsumerRecord<String, String> response = records.iterator().next();
                    assertEquals(response.value(), expectedValue);
                    assertEquals(response.key(), expectedKey);
                    return true;
                }
        );
    }

    private static String getHeaderValue(ConsumerRecord<String, String> response, String key) {
        if (response.headers().lastHeader(key) != null) {
            Header h = response.headers().lastHeader(key);
            if (h != null && h.value() != null) {
                return new String(h.value(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

}
