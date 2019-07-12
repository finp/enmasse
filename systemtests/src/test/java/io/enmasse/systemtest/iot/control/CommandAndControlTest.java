/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.iot.control;

import io.enmasse.systemtest.CustomLogger;
import io.enmasse.systemtest.Endpoint;
import io.enmasse.systemtest.amqp.AmqpClient;
import io.enmasse.systemtest.amqp.QueueTerminusFactory;
import io.enmasse.systemtest.bases.IoTTestBaseWithShared;
import io.enmasse.systemtest.iot.CredentialsRegistryClient;
import io.enmasse.systemtest.iot.DeviceRegistryClient;
import io.enmasse.systemtest.iot.HttpAdapterClient;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.proton.ProtonDelivery;

import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Data;
import org.apache.qpid.proton.amqp.messaging.Released;
import org.apache.qpid.proton.message.Message;
import org.hamcrest.core.Is;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.Assert.assertThat;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.enmasse.systemtest.TestTag.sharedIot;
import static io.enmasse.systemtest.apiclients.Predicates.is;
import static io.enmasse.systemtest.iot.MessageType.COMMAND_RESPONSE;
import static io.enmasse.systemtest.iot.MessageType.TELEMETRY;

@Tag(sharedIot)
class CommandAndControlTest extends IoTTestBaseWithShared {

    private static Logger log = CustomLogger.getLogger();

    private Endpoint deviceRegistryEndpoint;
    private Endpoint httpAdapterEndpoint;
    private DeviceRegistryClient registryClient;
    private CredentialsRegistryClient credentialsClient;

    private String deviceId;

    private String authId;

    private String password;

    private HttpAdapterClient httpClient;
    private AmqpClient messagingClient;
    private String commandPayload;
    private int ttd;

    @BeforeEach
    protected void initClient() {
        this.deviceRegistryEndpoint = kubernetes.getExternalEndpoint("device-registry");
        this.httpAdapterEndpoint = kubernetes.getExternalEndpoint("iot-http-adapter");
        this.registryClient = new DeviceRegistryClient(kubernetes, this.deviceRegistryEndpoint);
        this.credentialsClient = new CredentialsRegistryClient(kubernetes, this.deviceRegistryEndpoint);
    }

    @BeforeEach
    protected void initDevice() throws Exception {

        // setup device information
        this.deviceId = UUID.randomUUID().toString();
        this.authId = UUID.randomUUID().toString();
        this.password = UUID.randomUUID().toString();
        this.httpClient = new HttpAdapterClient(kubernetes, this.httpAdapterEndpoint, this.authId,  tenantId(), this.password);

        // set up new random device
        this.registryClient.registerDevice(tenantId(), this.deviceId);
        this.credentialsClient.addCredentials(tenantId(), this.deviceId, this.authId, this.password);

        // setup payload
        this.commandPayload = UUID.randomUUID().toString();
        this.ttd = 30;

    }

    @AfterEach
    protected void closeHttpClient() {
        if (this.httpClient != null) {
            this.httpClient.close();
            this.httpClient = null;
        }
    }

    @BeforeEach
    protected void setupMessagingClient() throws Exception {
        this.messagingClient = this.iotAmqpClientFactory.createQueueClient();
    }

    @AfterEach
    protected void disposeMessagingClient() throws Exception {
        if (this.messagingClient != null) {
            this.messagingClient.close();
            this.messagingClient = null;
        }
    }

    @Test
    void testOneShotCommand() throws Exception {

        final AtomicReference<Future<List<ProtonDelivery>>> sentFuture = new AtomicReference<>();

        var f1 = setupMessagingReceiver(sentFuture, null);

        waitForFirstSuccessOnTelemetry(httpClient);

        var response = sendTelemetryWithTtd();

        assertTelemetryResponse(response);

        assertCloudTelemetryMessage(f1);
        assertCommandMessageDeliveries(sentFuture.get());

    }

    @Test
    void testRequestResponseCommand() throws Exception {

        final var reqId = UUID.randomUUID().toString();
        final var replyToAddress = "control/" + tenantId() + "/" + UUID.randomUUID().toString();

        final AtomicReference<Future<List<ProtonDelivery>>> sentFuture = new AtomicReference<>();

        // set up command response consumer (before responding to telemetry)
        var f3 = this.messagingClient.recvMessages(replyToAddress, 1);

        var f1 = setupMessagingReceiver(sentFuture, commandMessage -> {
            commandMessage.setCorrelationId(reqId);
            commandMessage.setReplyTo(replyToAddress);
        });

        waitForFirstSuccessOnTelemetry(httpClient);

        var response = sendTelemetryWithTtd();

        assertTelemetryResponse(response);

        // also assert response id

        var responseId = response.getHeader("hono-cmd-req-id");
        assertThat(responseId, notNullValue());

        // send the reply to the command

        this.httpClient.send(COMMAND_RESPONSE, "/" + responseId, new JsonObject().put("foo", "bar"), is(HTTP_ACCEPTED), request -> {
            request.putHeader("hono-cmd-status", "202" /* accepted */);
        }, Duration.ofSeconds(5));

        assertCloudTelemetryMessage(f1);
        assertCommandMessageDeliveries(sentFuture.get());

        // assert command response message - cloud side

        var responses = f3.get(10, TimeUnit.SECONDS);
        assertThat(responses, hasSize(1));
        var responseMsg = responses.get(0);
        assertThat(responseMsg.getCorrelationId(), Is.is(reqId));
        assertThat(responseMsg.getBody(), instanceOf(Data.class));
        assertThat(new JsonObject(Buffer.buffer(((Data) responseMsg.getBody()).getValue().getArray())), Is.is(new JsonObject().put("foo", "bar")));
        assertThat(responseMsg.getApplicationProperties().getValue().get("status"), Is.is(202) /* accepted */);

    }

    private HttpResponse<?> sendTelemetryWithTtd() throws Exception {

        // consumer link should be ready now ... send telemetry with "ttd"

        var response = this.httpClient.send(TELEMETRY, null, is(HTTP_OK /* OK for command responses */), request -> {
            // set "time to disconnect"
            request.putHeader("hono-ttd", Integer.toString(this.ttd));
        }, Duration.ofSeconds(this.ttd + 5));

        return response;

    }

    private Future<List<Message>> setupMessagingReceiver(final AtomicReference<Future<List<ProtonDelivery>>> sentFuture, final Consumer<Message> messageCustomizer) {

        // setup telemetry consumer

        var f1 = this.messagingClient.recvMessages(new QueueTerminusFactory().getSource("telemetry/" + tenantId()), msg -> {

            var ttdValue = msg.getApplicationProperties().getValue().get("ttd");

            if (ttdValue == null) {
                // this was the initial message, without waiting for commands
                return false;
            }

            var deviceId = msg.getApplicationProperties().getValue().get("device_id").toString();

            // prepare message

            var commandMessage = Message.Factory.create();
            commandMessage.setSubject("CMD1");
            commandMessage.setMessageId(UUID.randomUUID().toString());

            commandMessage.setContentType("application/octet-stream");
            commandMessage.setBody(new Data(Binary.create(ByteBuffer.wrap(this.commandPayload.getBytes()))));

            if (messageCustomizer != null) {
                messageCustomizer.accept(commandMessage);
            }

            // send request command

            log.info("Sending out command message");
            var f2 = this.messagingClient.sendMessage("control/" + tenantId() + "/" + deviceId, commandMessage)
                    .whenComplete((res, err) -> {
                        String strres = null;
                        if (res != null) {
                            strres = res.stream().map(ProtonDelivery::getRemoteState).map(Object::toString).collect(Collectors.joining(", "));
                        }
                        log.info("Message result - res: {}, err:", // no need for final {}, as this is an exception
                                strres, err);
                    });
            sentFuture.set(f2);
            log.info("Message underway");

            // stop listening for more messages

            return true;

        }, Optional.empty()).getResult();
        return f1;

    }

    private void assertTelemetryResponse(final HttpResponse<?> response) {

        // assert message - device side

        final var actualCommand = response.bodyAsString();
        assertThat(response.getHeader("hono-command"), Is.is("CMD1"));
        assertThat(actualCommand, Is.is(this.commandPayload));

    }

    private void assertCloudTelemetryMessage(Future<List<Message>> f1) throws InterruptedException, ExecutionException, TimeoutException {

        // assert message - cloud side

        // wait for the future of the sent message

        var m1 = f1.get(10, TimeUnit.SECONDS);
        assertThat(m1, hasSize(2));
        var msg = m1.get(1);

        // message must have "ttd" set

        var ttdValue = msg.getApplicationProperties().getValue().get("ttd");
        assertThat(ttdValue, instanceOf(Number.class));
        assertThat(ttdValue, Is.is(30));

    }

    private void assertCommandMessageDeliveries(Future<List<ProtonDelivery>> messageFuture) throws InterruptedException, ExecutionException, TimeoutException {

        assertThat(messageFuture, notNullValue());

        // assert command message deliveries - cloud side

        final List<ProtonDelivery> deliveries = messageFuture.get(10, TimeUnit.SECONDS);
        assertThat(deliveries, hasSize(1));
        assertThat(deliveries.stream().map(ProtonDelivery::getRemoteState).collect(Collectors.toList()),
                contains(
                        anyOf(
                                instanceOf(Released.class), // remove once issue eclipse/hono#1149 is fixed
                                instanceOf(Accepted.class))));

    }

}
