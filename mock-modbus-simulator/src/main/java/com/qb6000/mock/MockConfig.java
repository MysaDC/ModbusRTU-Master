package com.qb6000.mock;

import java.util.List;

public record MockConfig(
    int workerThreads,
    int readTimeoutMillis,
    List<ControllerMockConfig> controllers
) {
    public MockConfig {
        if (workerThreads < 1) {
            throw new IllegalArgumentException("worker-threads 必须 >= 1");
        }
        if (readTimeoutMillis < 100) {
            throw new IllegalArgumentException("read-timeout-ms 必须 >= 100");
        }
        if (controllers == null || controllers.isEmpty()) {
            throw new IllegalArgumentException("至少需要 1 个控制器配置");
        }
        controllers = List.copyOf(controllers);
    }

    public record ControllerMockConfig(
        String id,
        String bindHost,
        int port,
        int unitId,
        int startRegister,
        int probeCount,
        int randomMin,
        int randomMax
    ) {
        public ControllerMockConfig {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("controller.id 不能为空");
            }
            if (bindHost == null || bindHost.isBlank()) {
                throw new IllegalArgumentException("controller.bind-host 不能为空");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("controller.port 范围必须是 1~65535");
            }
            if (unitId < 0 || unitId > 255) {
                throw new IllegalArgumentException("controller.unit-id 范围必须是 0~255");
            }
            if (startRegister < 0 || startRegister > 65535) {
                throw new IllegalArgumentException("controller.start-register 范围必须是 0~65535");
            }
            if (probeCount < 1 || probeCount > 128) {
                throw new IllegalArgumentException("controller.probe-count 范围必须是 1~128");
            }
            if (randomMin < 0 || randomMin > 65535 || randomMax < 0 || randomMax > 65535 || randomMin > randomMax) {
                throw new IllegalArgumentException("controller.random-min/random-max 配置非法");
            }
            if (startRegister + probeCount - 1 > 65535) {
                throw new IllegalArgumentException("controller.start-register + probe-count 超出 65535");
            }
        }
    }
}
