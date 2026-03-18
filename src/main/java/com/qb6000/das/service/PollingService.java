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
        log.info("Starting polling service, controllerId={}, ip={}, interval={}ms", controllerId, controllerIp, pollIntervalMs);
        scheduler.scheduleWithFixedDelay(this::pollAndPublish, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void pollAndPublish() {
        try {
            Instant now = Instant.now();
            List<ChannelData> channels = modbusReader.readChannels();
            TelemetryMessage telemetryMessage = new TelemetryMessage(controllerIp, now, channels);
            String payload = serialize(telemetryMessage);
            mqttPublisher.publish(payload);
            log.info("Telemetry published, controllerId={}, ip={}, channelCount={}", controllerId, controllerIp, channels.size());
        } catch (IOException ex) {
            log.error("Failed to read Modbus data, controllerId={}, ip={}: {}", controllerId, controllerIp, ex.getMessage(), ex);
        } catch (MqttException ex) {
            log.error("Failed to publish MQTT message, controllerId={}, ip={}: {}", controllerId, controllerIp, ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("Unexpected error in polling workflow, controllerId={}, ip={}", controllerId, controllerIp, ex);
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
                log.warn("Polling scheduler did not stop within timeout, controllerId={}, ip={}", controllerId, controllerIp);
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
}
