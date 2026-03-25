package com.qb6000.mock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class MockConfigLoader {
    private MockConfigLoader() {
    }

    public static MockConfig load(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(file)) {
            properties.load(inputStream);
        }

        int controllerCount = getInt(properties, "controller.count", 1);
        int workerThreads = getInt(properties, "worker-threads", 8);
        int readTimeoutMillis = getInt(properties, "read-timeout-ms", 3000);
        boolean rtuOverTcpEnabled = getBoolean(properties, "tcp.rtu-over-tcp.enabled", false);

        String defaultBindHost = getString(properties, "default.bind-host", "0.0.0.0");
        int defaultPort = getInt(properties, "default.port", 15020);
        int defaultUnitId = getInt(properties, "default.unit-id", 1);
        int defaultStartRegister = getInt(properties, "default.start-register", 101);
        int defaultProbeCount = getInt(properties, "default.probe-count", 4);
        int defaultRandomMin = getInt(properties, "default.random-min", 0);
        int defaultRandomMax = getInt(properties, "default.random-max", 1000);

        List<MockConfig.ControllerMockConfig> controllers = new ArrayList<>();
        for (int i = 1; i <= controllerCount; i++) {
            String prefix = "controller." + i + ".";
            String id = getString(properties, prefix + "id", "mock-ctrl-" + i);
            String bindHost = getString(properties, prefix + "bind-host", defaultBindHost);
            int port = getInt(properties, prefix + "port", defaultPort + i - 1);
            int unitId = getInt(properties, prefix + "unit-id", defaultUnitId);
            int startRegister = getInt(properties, prefix + "start-register", defaultStartRegister);
            int probeCount = getInt(properties, prefix + "probe-count", defaultProbeCount);
            int randomMin = getInt(properties, prefix + "random-min", defaultRandomMin);
            int randomMax = getInt(properties, prefix + "random-max", defaultRandomMax);

            controllers.add(new MockConfig.ControllerMockConfig(
                id,
                bindHost,
                port,
                unitId,
                startRegister,
                probeCount,
                randomMin,
                randomMax
            ));
        }

        return new MockConfig(workerThreads, readTimeoutMillis, rtuOverTcpEnabled, controllers);
    }

    private static String getString(Properties properties, String key, String defaultValue) {
        return properties.getProperty(key, defaultValue).trim();
    }

    private static int getInt(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }

    private static boolean getBoolean(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }
}
