package com.qb6000.das;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qb6000.das.config.ConfigLoader;
import com.qb6000.das.config.ServiceConfig;
import com.qb6000.das.modbus.ModbusReader;
import com.qb6000.das.mqtt.MqttPublisher;
import com.qb6000.das.service.PollingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = args.length > 0 ? Path.of(args[0]) : Path.of("config", "application.properties");
        ServiceConfig config = ConfigLoader.load(configPath);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        MqttPublisher mqttPublisher = new MqttPublisher(config.mqtt());
        List<ModbusReader> modbusReaders = new ArrayList<>();
        List<PollingService> pollingServices = new ArrayList<>();

        for (ServiceConfig.ControllerConfig controller : config.controllers()) {
            ModbusReader modbusReader = new ModbusReader(controller.modbus(), config.concentration());
            PollingService pollingService = new PollingService(
                controller.controllerId(),
                controller.ipAddress(),
                config.pollInterval().toMillis(),
                modbusReader,
                mqttPublisher,
                objectMapper
            );

            modbusReaders.add(modbusReader);
            pollingServices.add(pollingService);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("接收到停机信号，正在停止服务...");
            closeAll(pollingServices);
            closeAll(modbusReaders);
            mqttPublisher.close();
            log.info("服务已停止。");
        }));

        pollingServices.forEach(PollingService::start);
        log.info("QB6000 采集服务已启动，控制器数量：{}，配置文件：{}",
            config.controllers().size(), configPath.toAbsolutePath());

        new CountDownLatch(1).await();
    }

    private static void closeAll(List<? extends AutoCloseable> closeables) {
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception ex) {
                log.warn("关闭资源时发生异常：{}", ex.getMessage());
            }
        }
    }
}
