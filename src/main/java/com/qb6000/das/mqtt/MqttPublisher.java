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
import java.util.concurrent.atomic.AtomicBoolean;

public final class MqttPublisher implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(MqttPublisher.class);

    private final ServiceConfig.MqttConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private MqttClient client;

    public MqttPublisher(ServiceConfig.MqttConfig config) {
        this.config = config;
    }

    public synchronized boolean connect() {
        if (closed.get()) {
            log.warn("MQTT 发布器已关闭，无法建立连接");
            return false;
        }
        try {
            connectIfNeeded();
            return true;
        } catch (MqttException ex) {
            log.warn("{}", formatMqttError(ex), ex);
            closeClient();
            return false;
        }
    }

    public synchronized boolean publish(String payload) {
        if (closed.get()) {
            log.warn("MQTT 发布器已关闭，跳过本次上报");
            return false;
        }

        try {
            connectIfNeeded();
            publishInternal(payload);
            return true;
        } catch (MqttException ex) {
            log.warn("MQTT 发布失败：{}", formatMqttError(ex), ex);
            closeClient();
            return false;
        }
    }

    private void publishInternal(String payload) throws MqttException {
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

        log.info("正在连接 MQTT Broker：{}", config.brokerUri());
        client.connect(options);
        log.info("MQTT 连接成功，发布主题：{}", config.topic());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeClient();
    }

    private synchronized void closeClient() {
        if (client == null) {
            return;
        }

        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (MqttException ex) {
            log.warn("断开 MQTT 连接时发生异常：{}", ex.getMessage());
        }

        try {
            client.close();
        } catch (MqttException ex) {
            log.warn("关闭 MQTT 客户端时发生异常：{}", ex.getMessage());
        }

        client = null;
    }

    private static String formatMqttError(MqttException ex) {
        Throwable cause = ex.getCause();
        String causeType = cause == null ? "未知" : cause.getClass().getSimpleName();
        return "MQTT 连接失败，错误码：" + ex.getReasonCode() + "，根因：" + causeType;
    }
}
