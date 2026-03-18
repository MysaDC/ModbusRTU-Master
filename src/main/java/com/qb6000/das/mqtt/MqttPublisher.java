package com.qb6000.das.mqtt;

import com.qb6000.das.config.ServiceConfig;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;

public final class MqttPublisher implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(MqttPublisher.class);

    private final ServiceConfig.MqttConfig config;

    private MqttClient client;

    public MqttPublisher(ServiceConfig.MqttConfig config) {
        this.config = config;
    }

    public synchronized void publish(String payload) throws MqttException {
        connectIfNeeded();
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(config.qos());
        message.setRetained(config.retain());
        client.publish(config.topic(), message);
    }

    private void connectIfNeeded() throws MqttException {
        if (client != null && client.isConnected()) {
            return;
        }

        if (client == null) {
            client = new MqttClient(config.brokerUri(), config.clientId(), new MemoryPersistence());
        }

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(false);
        options.setConnectionTimeout(config.connectionTimeoutSeconds());
        if (!config.username().isBlank()) {
            options.setUserName(config.username());
            options.setPassword(config.password().toCharArray());
        }

        log.info("Connecting MQTT broker: {}", config.brokerUri());
        client.connect(options);
        log.info("MQTT connected, topic={}", config.topic());
    }

    @Override
    public synchronized void close() {
        if (client == null) {
            return;
        }

        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (MqttException ex) {
            log.warn("Error while disconnecting MQTT: {}", ex.getMessage());
        }

        try {
            client.close();
        } catch (MqttException ex) {
            log.warn("Error while closing MQTT client: {}", ex.getMessage());
        }

        client = null;
    }
}
