package com.qb6000.das.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.qb6000.das.model.ChannelData;
import com.qb6000.das.model.TelemetryMessage;
import com.qb6000.das.modbus.ModbusReader;
import com.qb6000.das.mqtt.MqttPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PollingService implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(PollingService.class);

    private final String controllerId;
    private final String controllerIp;
    private final Duration pollInterval;
    private final ModbusReader modbusReader;
    private final MqttPublisher mqttPublisher;
    private final ObjectWriter telemetryWriter;
    private final PollingRuntime pollingRuntime;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> scheduledTask;

    public PollingService(String controllerId,
                          String controllerIp,
                          Duration pollInterval,
                          ModbusReader modbusReader,
                          MqttPublisher mqttPublisher,
                          ObjectWriter telemetryWriter,
                          PollingRuntime pollingRuntime) {
        this.controllerId = controllerId;
        this.controllerIp = controllerIp;
        this.pollInterval = pollInterval;
        this.modbusReader = modbusReader;
        this.mqttPublisher = mqttPublisher;
        this.telemetryWriter = telemetryWriter;
        this.pollingRuntime = pollingRuntime;
    }

    public void start() {
        log.info("开始轮询，控制器：{}，IP：{}，周期：{}ms", controllerId, controllerIp, pollInterval.toMillis());
        scheduledTask = pollingRuntime.scheduleWithFixedDelay(this::triggerPoll, pollInterval);
    }

    private void triggerPoll() {
        if (!inFlight.compareAndSet(false, true)) {
            log.warn("上一轮轮询仍未完成，跳过本周期，控制器：{}，IP：{}", controllerId, controllerIp);
            return;
        }
        pollingRuntime.execute(() -> {
            try {
                pollAndPublish();
            } finally {
                inFlight.set(false);
            }
        });
    }

    private void pollAndPublish() {
        try {
            Instant now = Instant.now();
            List<ChannelData> channels = modbusReader.readChannels();
            if (channels.isEmpty()) {
                log.warn("本轮无有效通道数据，跳过 MQTT 上报，控制器：{}，IP：{}", controllerId, controllerIp);
                return;
            }

            TelemetryMessage telemetryMessage = new TelemetryMessage(controllerIp, now, channels);
            String payload = serialize(telemetryMessage);
            if (mqttPublisher.publish(payload)) {
                log.info("遥测数据发布成功，控制器：{}，IP：{}，通道数：{}", controllerId, controllerIp, channels.size());
            } else {
                log.warn("MQTT 不可用，已跳过本次上报，控制器：{}，IP：{}，通道数：{}", controllerId, controllerIp, channels.size());
            }
        } catch (IOException ex) {
            log.error("Modbus 读取失败，控制器：{}，IP：{}，原因：{}", controllerId, controllerIp, ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("轮询流程发生未预期异常，控制器：{}，IP：{}", controllerId, controllerIp, ex);
        }
    }

    private String serialize(TelemetryMessage telemetryMessage) throws JsonProcessingException {
        return telemetryWriter.writeValueAsString(telemetryMessage);
    }

    @Override
    public void close() {
        ScheduledFuture<?> currentTask = scheduledTask;
        if (currentTask != null) {
            currentTask.cancel(true);
        }
    }
}
