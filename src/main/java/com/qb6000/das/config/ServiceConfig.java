package com.qb6000.das.config;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class ServiceConfig {
    private final Duration pollInterval;
    private final List<ControllerConfig> controllers;
    private final MqttConfig mqtt;
    private final ConcentrationConfig concentration;

    public ServiceConfig(Duration pollInterval,
                         List<ControllerConfig> controllers,
                         MqttConfig mqtt,
                         ConcentrationConfig concentration) {
        this.pollInterval = Objects.requireNonNull(pollInterval, "poll.interval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("poll.interval.ms must be greater than 0");
        }

        this.controllers = List.copyOf(Objects.requireNonNull(controllers, "controllers"));
        if (this.controllers.isEmpty()) {
            throw new IllegalArgumentException("At least one controller config is required");
        }

        this.mqtt = Objects.requireNonNull(mqtt, "mqtt");
        this.concentration = Objects.requireNonNull(concentration, "concentration");
    }

    public Duration pollInterval() {
        return pollInterval;
    }

    public List<ControllerConfig> controllers() {
        return controllers;
    }

    public MqttConfig mqtt() {
        return mqtt;
    }

    public ConcentrationConfig concentration() {
        return concentration;
    }

    public static final class ControllerConfig {
        private final String controllerId;
        private final ModbusConfig modbus;

        public ControllerConfig(String controllerId, ModbusConfig modbus) {
            this.controllerId = requireNonBlank(controllerId, "controller.id");
            this.modbus = Objects.requireNonNull(modbus, "modbus");
        }

        public String controllerId() {
            return controllerId;
        }

        public ModbusConfig modbus() {
            return modbus;
        }

        public String ipAddress() {
            return modbus.host();
        }
    }

    public static final class ModbusConfig {
        private final String host;
        private final int port;
        private final int unitId;
        private final int startRegister;
        private final int channelCount;
        private final int timeoutMillis;
        private final int maxRetries;
        private final Duration retryBackoff;
        private final boolean crcCheckEnabled;
        private final boolean crcFailureImmediateRetryEnabled;
        private final int crcFailureMaxRetriesUntilNextPoll;

        public ModbusConfig(String host,
                            int port,
                            int unitId,
                            int startRegister,
                            int channelCount,
                            int timeoutMillis,
                            int maxRetries,
                            Duration retryBackoff,
                            boolean crcCheckEnabled,
                            boolean crcFailureImmediateRetryEnabled,
                            int crcFailureMaxRetriesUntilNextPoll) {
            this.host = requireNonBlank(host, "modbus.host");
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
            this.port = port;
            this.unitId = unitId;
            this.startRegister = startRegister;
            this.channelCount = channelCount;
            this.timeoutMillis = timeoutMillis;
            this.maxRetries = maxRetries;
            this.retryBackoff = retryBackoff;
            this.crcCheckEnabled = crcCheckEnabled;
            this.crcFailureImmediateRetryEnabled = crcFailureImmediateRetryEnabled;
            this.crcFailureMaxRetriesUntilNextPoll = crcFailureMaxRetriesUntilNextPoll;
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        public int unitId() {
            return unitId;
        }

        public int startRegister() {
            return startRegister;
        }

        public int channelCount() {
            return channelCount;
        }

        public int timeoutMillis() {
            return timeoutMillis;
        }

        public int maxRetries() {
            return maxRetries;
        }

        public Duration retryBackoff() {
            return retryBackoff;
        }

        public boolean crcCheckEnabled() {
            return crcCheckEnabled;
        }

        public boolean crcFailureImmediateRetryEnabled() {
            return crcFailureImmediateRetryEnabled;
        }

        public int crcFailureMaxRetriesUntilNextPoll() {
            return crcFailureMaxRetriesUntilNextPoll;
        }
    }

    public static final class MqttConfig {
        private final String brokerUri;
        private final String clientId;
        private final String topic;
        private final int qos;
        private final boolean retain;
        private final String username;
        private final String password;
        private final int connectionTimeoutSeconds;

        public MqttConfig(String brokerUri,
                          String clientId,
                          String topic,
                          int qos,
                          boolean retain,
                          String username,
                          String password,
                          int connectionTimeoutSeconds) {
            this.brokerUri = requireNonBlank(brokerUri, "mqtt.broker-uri");
            this.clientId = requireNonBlank(clientId, "mqtt.client-id");
            this.topic = requireNonBlank(topic, "mqtt.topic");
            if (qos < 0 || qos > 2) {
                throw new IllegalArgumentException("mqtt.qos must be 0, 1 or 2");
            }
            if (connectionTimeoutSeconds < 1) {
                throw new IllegalArgumentException("mqtt.connection-timeout-seconds must be >= 1");
            }
            this.qos = qos;
            this.retain = retain;
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
            this.connectionTimeoutSeconds = connectionTimeoutSeconds;
        }

        public String brokerUri() {
            return brokerUri;
        }

        public String clientId() {
            return clientId;
        }

        public String topic() {
            return topic;
        }

        public int qos() {
            return qos;
        }

        public boolean retain() {
            return retain;
        }

        public String username() {
            return username;
        }

        public String password() {
            return password;
        }

        public int connectionTimeoutSeconds() {
            return connectionTimeoutSeconds;
        }
    }

    public static final class ConcentrationConfig {
        private final double scale;
        private final double offset;

        public ConcentrationConfig(double scale, double offset) {
            this.scale = scale;
            this.offset = offset;
        }

        public double scale() {
            return scale;
        }

        public double offset() {
            return offset;
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
