package com.qb6000.das.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qb6000.das.model.ChannelData;
import com.qb6000.das.model.TelemetryMessage;
import com.qb6000.das.modbus.ModbusReader;
import com.qb6000.das.mqtt.MqttPublisher;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class PollingService implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(PollingService.class);

    private final String controllerId;
    private final String controllerIp;
    private final long pollIntervalMs;
    private final ModbusReader modbusReader;
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService scheduler;

    public PollingService(String controllerId,
                        String controllerIp,
                        long pollIntervalMs,
                        ModbusReader modbusReader,
                        MqttPublisher mqttPublisher,
                        ObjectMapper objectMapper) {
        this.controllerId = controllerId;
        this.controllerIp = controllerIp;
        this.pollIntervalMs = pollIntervalMs;
        this.modbusReader = modbusReader;
        this.mqttPublisher = mqttPublisher;
        this.objectMapper = objectMapper;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "polling-" + sanitize(controllerId));
            thread.setDaemon(false);
            return thread;
        });
    }

    public void start() {
        log.info("开始轮询，控制器：{}，IP：{}，周期：{}ms", controllerId, controllerIp, pollIntervalMs);
        scheduler.scheduleWithFixedDelay(this::pollAndPublish, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void pollAndPublish() {
        try {
            Instant now = Instant.now();
            List<ChannelData> channels = modbusReader.readChannels();
            if (channels == null || channels.isEmpty()) {
                log.warn("本轮无有效通道数据，跳过 MQTT 上报，控制器：{}，IP：{}", controllerId, controllerIp);
                return;
            }
            TelemetryMessage telemetryMessage = new TelemetryMessage(controllerIp, now, channels);
            String payload = serialize(telemetryMessage);
            mqttPublisher.publishAsync(payload).whenComplete((unused, throwable) -> {
                if (throwable != null) {
                    Throwable root = unwrap(throwable);
                    log.error("MQTT 发布失败，控制器：{}，IP：{}，原因：{}",
                        controllerId, controllerIp, buildErrorMessage(root), root);
                    return;
                }
                log.info("遥测数据发布成功，控制器：{}，IP：{}，通道数：{}",
                    controllerId, controllerIp, channels.size());
            });
        } catch (IOException ex) {
            log.error("Modbus 读取失败，控制器：{}，IP：{}，原因：{}", controllerId, controllerIp, ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("轮询流程发生未预期异常，控制器：{}，IP：{}", controllerId, controllerIp, ex);
        }
    }

    private String serialize(TelemetryMessage telemetryMessage) throws JsonProcessingException {
        return objectMapper.writeValueAsString(telemetryMessage);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("轮询线程未在超时时间内停止，控制器：{}，IP：{}", controllerId, controllerIp);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "controller";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String buildErrorMessage(Throwable throwable) {
        if (throwable instanceof MqttException mqttException) {
            Throwable cause = mqttException.getCause();
            String causeType = cause == null ? "未知" : cause.getClass().getSimpleName();
            return "MQTT 错误码：" + mqttException.getReasonCode() + "，根因：" + causeType;
        }
        return throwable.getMessage();
    }
}
