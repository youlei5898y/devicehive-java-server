package com.devicehive.shim.kafka.test;

import com.devicehive.shim.api.Request;
import com.devicehive.shim.api.Response;
import com.devicehive.shim.api.client.RpcClient;
import com.devicehive.shim.api.server.RequestHandler;
import com.devicehive.shim.api.server.RpcServer;
import com.devicehive.shim.kafka.builder.ClientBuilder;
import com.devicehive.shim.kafka.builder.ServerBuilder;
import com.devicehive.shim.kafka.rule.KafkaEmbeddedRule;
import com.devicehive.shim.kafka.rule.RequestHandlerWrapper;
import com.devicehive.shim.kafka.serializer.RequestSerializer;
import com.devicehive.shim.kafka.serializer.ResponseSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class KafkaRpcClientServerCommunicationTest {

    private static final String REQUEST_TOPIC = "request_topic";
    private static final String RESPONSE_TOPIC = "response_topic";

    @ClassRule
    public static KafkaEmbeddedRule kafkaRule = new KafkaEmbeddedRule(true, 1, REQUEST_TOPIC, RESPONSE_TOPIC);

    private static RpcServer server;
    private static RpcClient client;

    private static RequestHandlerWrapper handlerWrapper = new RequestHandlerWrapper();

    @BeforeClass
    public static void setUp() throws Exception {
        Properties serverConsumerProps = kafkaRule.getConsumerProperties();
        serverConsumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, RequestSerializer.class.getName());
        Properties serverProducerProps = kafkaRule.getProducerProperties();
        serverProducerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ResponseSerializer.class.getName());

        Properties clientProducerProps = kafkaRule.getProducerProperties();
        clientProducerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, RequestSerializer.class.getName());
        Properties clientConsumerProps = kafkaRule.getConsumerProperties();
        clientConsumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ResponseSerializer.class.getName());

        server = new ServerBuilder()
                .withConsumerProps(serverConsumerProps)
                .withProducerProps(serverProducerProps)
                .withConsumerThreads(1)
                .withWorkerThreads(1)
                .withRequestHandler(handlerWrapper)
                .withTopic(REQUEST_TOPIC)
                .build();
        server.start();

        client = new ClientBuilder()
                .withProducerProps(clientProducerProps)
                .withConsumerProps(clientConsumerProps)
                .withReplyTopic(RESPONSE_TOPIC)
                .withRequestTopic(REQUEST_TOPIC)
                .withConsumerThreads(1)
                .build();
        client.start();
        TimeUnit.SECONDS.sleep(10);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        if (client != null) {
            client.shutdown();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    public void shouldSendRequestToServer() throws Exception {
        CompletableFuture<Request> future = new CompletableFuture<>();
        RequestHandler handler = request -> {
            future.complete(request);
            return Response.newBuilder()
                    .withCorrelationId(request.getCorrelationId())
                    .withBody("Response".getBytes())
                    .withLast(true)
                    .buildSuccess();
        };
        handlerWrapper.setDelegate(handler);

        Request request = Request.newBuilder()
                .withCorrelationId(UUID.randomUUID().toString())
                .withSingleReply(true)
                .withBody("RequestResponseTest".getBytes())
                .build();

        client.push(request);

        Request receivedRequest = future.get(10, TimeUnit.SECONDS);
        assertEquals(request, receivedRequest);
    }

    @Test
    public void shouldSuccessfullyReplyToRequest() throws Exception {
        RequestHandler handler = request -> Response.newBuilder()
                .withCorrelationId(request.getCorrelationId())
                .withBody("ResponseFromServer".getBytes())
                .withLast(true)
                .buildSuccess();
        handlerWrapper.setDelegate(handler);

        Request request = Request.newBuilder()
                .withCorrelationId(UUID.randomUUID().toString())
                .withSingleReply(true)
                .withBody("RequestResponseTest".getBytes())
                .build();

        CompletableFuture<Response> future = new CompletableFuture<>();
        client.call(request, future::complete);

        Response response = future.get(10, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(request.getCorrelationId(), response.getCorrelationId());
        assertEquals("ResponseFromServer", new String(response.getBody()));
        assertTrue(response.isLast());
        assertFalse(response.isFailed());
    }

    @Test
    public void shouldSendErrorToClient() throws Exception {
        RequestHandler handler = request -> {
            throw new RuntimeException("Something went wrong");
        };
        handlerWrapper.setDelegate(handler);

        Request request = Request.newBuilder()
                .withCorrelationId(UUID.randomUUID().toString())
                .withSingleReply(true)
                .withBody("RequestResponseTest".getBytes())
                .build();

        CompletableFuture<Response> future = new CompletableFuture<>();
        client.call(request, future::complete);

        Response response = future.get(10, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(request.getCorrelationId(), response.getCorrelationId());
        assertTrue(response.isLast());
        assertTrue(response.isFailed());
        assertTrue(new String(response.getBody()).contains(RuntimeException.class.getName() + ": Something went wrong"));
    }

    @Test
    public void shouldSendMultipleResponsesToClient() throws Exception {
        RequestHandler handler = request -> Response.newBuilder()
                .withCorrelationId(request.getCorrelationId())
                .withBody("ResponseFromServer".getBytes())
                .withLast(request.isSingleReplyExpected())
                .buildSuccess();
        handlerWrapper.setDelegate(handler);

        Request request = Request.newBuilder()
                .withCorrelationId(UUID.randomUUID().toString())
                .withSingleReply(false)
                .withBody("RequestResponseTest".getBytes())
                .build();

        CountDownLatch latch = new CountDownLatch(10);
        List<Response> responses = new LinkedList<>();
        Consumer<Response> func = response -> {
            latch.countDown();
            responses.add(response);
        };

        client.call(request, func);

        Executor executor = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 9; i++) {
            final int number = i;
            executor.execute(() -> {
                Response response = Response.newBuilder()
                        .withCorrelationId(request.getCorrelationId())
                        .withLast(false)
                        .withBody((number + "-response").getBytes())
                        .buildSuccess();
                server.getDispatcher().send(RESPONSE_TOPIC, response);
            });
        }

        latch.await();
        assertEquals(10, responses.size());

        Set<String> correlationIds = responses.stream()
                .map(Response::getCorrelationId).collect(Collectors.toSet());
        assertEquals(1, correlationIds.size());
        assertTrue(correlationIds.contains(request.getCorrelationId()));

        Set<String> bodies = responses.stream()
                .map(Response::getBody)
                .map(String::new)
                .collect(Collectors.toSet());
        assertEquals(10, bodies.size());
        assertTrue(bodies.contains("ResponseFromServer"));
        for (int i = 0; i < 9; i++) {
            assertTrue(bodies.contains(i + "-response"));
        }
    }

}
