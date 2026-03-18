package com.qb6000.das.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigLoader {
    private static final Pattern CONTROLLER_HOST_KEY_PATTERN = Pattern.compile("^controllers\\.(\\d+)\\.host$");

    private ConfigLoader() {
    }

    public static ServiceConfig load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(file)) {
            properties.load(inputStream);
        }

        Duration pollInterval = Duration.ofMillis(getInt(properties, "poll.interval.ms", 5000));

        ServiceConfig.MqttConfig mqtt = new ServiceConfig.MqttConfig(
            getString(properties, "mqtt.broker-uri", "tcp://127.0.0.1:1883"),
            getString(properties, "mqtt.client-id", "qb6000-das"),
            getString(properties, "mqtt.topic", "emergency/qb6000/telemetry"),
            getInt(properties, "mqtt.qos", 2),
            getBoolean(properties, "mqtt.retain", false),
            getString(properties, "mqtt.username", ""),
            getString(properties, "mqtt.password", ""),
            getInt(properties, "mqtt.connection-timeout-seconds", 10)
        );

        ServiceConfig.ConcentrationConfig concentration = new ServiceConfig.ConcentrationConfig(
            getDouble(properties, "concentration.scale", 1.0),
            getDouble(properties, "concentration.offset", 0.0)
        );

        List<ServiceConfig.ControllerConfig> controllers = loadControllers(properties);

        return new ServiceConfig(pollInterval, controllers, mqtt, concentration);
    }

    private static List<ServiceConfig.ControllerConfig> loadControllers(Properties properties) {
        int defaultPort = getInt(properties, "modbus.port", 502);
        int defaultUnitId = getInt(properties, "modbus.unit-id", 1);
        int defaultStartRegister = getInt(properties, "modbus.start-register", 0x0065);
        int defaultChannelCount = getInt(properties, "modbus.channel-count", 128);
        int defaultTimeoutMs = getInt(properties, "modbus.timeout.ms", 3000);
        int defaultMaxRetries = getInt(properties, "modbus.max-retries", 3);
        int defaultRetryBackoffMs = getInt(properties, "modbus.retry-backoff.ms", 1000);

        Set<Integer> controllerIndexes = resolveControllerIndexes(properties);
        List<ServiceConfig.ControllerConfig> controllers = new ArrayList<>();

        if (controllerIndexes.isEmpty()) {
            ServiceConfig.ModbusConfig modbusConfig = new ServiceConfig.ModbusConfig(
                getString(properties, "modbus.host", "127.0.0.1"),
                defaultPort,
                defaultUnitId,
                defaultStartRegister,
                defaultChannelCount,
                defaultTimeoutMs,
                defaultMaxRetries,
                Duration.ofMillis(defaultRetryBackoffMs)
            );

            controllers.add(new ServiceConfig.ControllerConfig(
                getString(properties, "modbus.controller-id", modbusConfig.host()),
                modbusConfig
            ));
            return controllers;
        }

        for (Integer index : controllerIndexes) {
            String prefix = "controllers." + index + ".";

            String host = getRequiredString(properties, prefix + "host");
            int port = getInt(properties, prefix + "port", defaultPort);
            int unitId = getInt(properties, prefix + "unit-id", defaultUnitId);
            int startRegister = getInt(properties, prefix + "start-register", defaultStartRegister);
            int channelCount = getInt(properties, prefix + "channel-count", defaultChannelCount);
            int timeoutMs = getInt(properties, prefix + "timeout.ms", defaultTimeoutMs);
            int maxRetries = getInt(properties, prefix + "max-retries", defaultMaxRetries);
            int retryBackoffMs = getInt(properties, prefix + "retry-backoff.ms", defaultRetryBackoffMs);

            ServiceConfig.ModbusConfig modbusConfig = new ServiceConfig.ModbusConfig(
                host,
                port,
                unitId,
                startRegister,
                channelCount,
                timeoutMs,
                maxRetries,
                Duration.ofMillis(retryBackoffMs)
            );

            String controllerId = getString(properties, prefix + "id", host);
            controllers.add(new ServiceConfig.ControllerConfig(controllerId, modbusConfig));
        }

        return controllers;
    }

    private static Set<Integer> resolveControllerIndexes(Properties properties) {
        Set<Integer> indexes = new TreeSet<>();
        for (String key : properties.stringPropertyNames()) {
            Matcher matcher = CONTROLLER_HOST_KEY_PATTERN.matcher(key);
            if (matcher.matches()) {
                indexes.add(Integer.parseInt(matcher.group(1)));
            }
        }
        if (indexes.isEmpty()) {
            int controllerCount = getInt(properties, "controllers.count", 0);
            for (int i = 1; i <= controllerCount; i++) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static String getString(Properties properties, String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    private static String getRequiredString(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required config: " + key);
        }
        return value.trim();
    }

    private static int getInt(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }

    private static double getDouble(Properties properties, String key, double defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Double.parseDouble(value.trim());
    }

    private static boolean getBoolean(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }
}
