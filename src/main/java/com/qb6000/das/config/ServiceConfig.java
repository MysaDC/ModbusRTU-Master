package com.qb6000.das.config;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record ServiceConfig(
    Duration pollInterval,
    List<ControllerConfig> controllers,
    MqttConfig mqtt,
    ConcentrationConfig concentration
) {
    public ServiceConfig {
        Objects.requireNonNull(pollInterval, "poll.interval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("poll.interval.ms must be greater than 0");
        }

        controllers = List.copyOf(Objects.requireNonNull(controllers, "controllers"));
        if (controllers.isEmpty()) {
            throw new IllegalArgumentException("At least one controller config is required");
        }

        Objects.requireNonNull(mqtt, "mqtt");
        Objects.requireNonNull(concentration, "concentration");
    }

    public record ControllerConfig(String controllerId, ModbusConfig modbus) {
        public ControllerConfig {
            controllerId = requireNonBlank(controllerId, "controller.id");
            Objects.requireNonNull(modbus, "modbus");
        }

        public String ipAddress() {
            return modbus.host();
        }
    }

    public record ModbusConfig(
        String host,
        int port,
        int unitId,
        int startRegister,
        int channelCount,
        int timeoutMillis,
        int maxRetries,
        Duration retryBackoff,
        boolean hexLogEnabled,
        boolean crcCheckEnabled,
        boolean crcFailureImmediateRetryEnabled,
        int crcFailureMaxRetriesUntilNextPoll
    ) {
        public ModbusConfig {
            host = requireNonBlank(host, "modbus.host");
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("modbus.port must be between 1 and 65535");
            }
            if (unitId < 0 || unitId > 255) {
                throw new IllegalArgumentException("modbus.unit-id must be between 0 and 255");
            }
            if (startRegister < 0 || startRegister > 0xFFFF) {
                throw new IllegalArgumentException("modbus.start-register must be between 0 and 65535");
            }
            if (channelCount < 1 || channelCount > 128) {
                throw new IllegalArgumentException("modbus.channel-count must be between 1 and 128");
            }
            if (timeoutMillis < 100) {
                throw new IllegalArgumentException("modbus.timeout.ms must be >= 100");
            }
            if (maxRetries < 1) {
                throw new IllegalArgumentException("modbus.max-retries must be >= 1");
            }
            if (retryBackoff == null || retryBackoff.isNegative()) {
                throw new IllegalArgumentException("modbus.retry-backoff.ms must be >= 0");
            }
            if (crcFailureMaxRetriesUntilNextPoll < 1) {
                throw new IllegalArgumentException("modbus.crc-failure.max-retries-until-next-poll must be >= 1");
            }
        }
    }

    public record MqttConfig(
        String brokerUri,
        String clientId,
        String topic,
        int qos,
        boolean retain,
        String username,
        String password,
        int connectionTimeoutSeconds
    ) {
        public MqttConfig {
            brokerUri = requireNonBlank(brokerUri, "mqtt.broker-uri");
            clientId = requireNonBlank(clientId, "mqtt.client-id");
            topic = requireNonBlank(topic, "mqtt.topic");
            if (qos < 0 || qos > 2) {
                throw new IllegalArgumentException("mqtt.qos must be 0, 1 or 2");
            }
            if (connectionTimeoutSeconds < 1) {
                throw new IllegalArgumentException("mqtt.connection-timeout-seconds must be >= 1");
            }
            username = username == null ? "" : username;
            password = password == null ? "" : password;
        }
    }

    public record ConcentrationConfig(double scale, double offset) {
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
