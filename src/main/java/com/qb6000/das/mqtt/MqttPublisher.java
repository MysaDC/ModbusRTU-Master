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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MqttPublisher implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(MqttPublisher.class);

    private final ServiceConfig.MqttConfig config;
    private final ExecutorService publishExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mqtt-publisher");
        thread.setDaemon(false);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private MqttClient client;

    public MqttPublisher(ServiceConfig.MqttConfig config) {
        this.config = config;
    }

    public synchronized void connect() throws MqttException {
        if (closed.get()) {
            throw new IllegalStateException("MQTT 发布器已关闭");
        }
        connectIfNeeded();
    }

    public CompletableFuture<Void> publishAsync(String payload) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("MQTT 发布器已关闭"));
        }

        return CompletableFuture.runAsync(() -> {
            try {
                publishInternal(payload);
            } catch (MqttException ex) {
                throw new CompletionException(ex);
            }
        }, publishExecutor);
    }

    private synchronized void publishInternal(String payload) throws MqttException {
        connectIfNeeded();
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(config.qos());
        message.setRetained(config.retain());
        client.publish(config.topic(), message);
    }

    private synchronized void connectIfNeeded() throws MqttException {
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

        publishExecutor.shutdown();
        try {
            if (!publishExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("MQTT 发布线程池未在超时时间内停止，执行强制关闭");
                publishExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            publishExecutor.shutdownNow();
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
}
