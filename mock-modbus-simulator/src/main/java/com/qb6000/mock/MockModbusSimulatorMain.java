package com.qb6000.mock;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class MockModbusSimulatorMain {
    static {
        initLogCharset();
    }

    private static final Logger log = LoggerFactory.getLogger(MockModbusSimulatorMain.class);

    private MockModbusSimulatorMain() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception ex) {
            log.error("Mock Modbus 启动失败：{}", ex.getMessage(), ex);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Path configPath = resolveConfigPath(args);

        MockConfig config = MockConfigLoader.load(configPath);
        List<RunningSlave> runningSlaves = new ArrayList<>();

        for (MockConfig.ControllerMockConfig controller : config.controllers()) {
            RunningSlave runningSlave = startController(controller, config.workerThreads(), config.readTimeoutMillis());
            runningSlaves.add(runningSlave);
            log.info("Mock 控制器已启动：id={}，监听地址={}:{}，unit-id={}，寄存器范围=[{}..{}]，探头数量={}，随机范围=[{}, {}]",
                controller.id(),
                controller.bindHost(),
                controller.port(),
                controller.unitId(),
                controller.startRegister(),
                controller.startRegister() + controller.probeCount() - 1,
                controller.probeCount(),
                controller.randomMin(),
                controller.randomMax());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("接收到停止信号，正在关闭 Mock 控制器...");
            for (RunningSlave runningSlave : runningSlaves) {
                try {
                    runningSlave.slave().close();
                    log.info("Mock 控制器已关闭：id={}，端口={}", runningSlave.id(), runningSlave.port());
                } catch (Exception ex) {
                    log.warn("关闭 Mock 控制器失败：id={}，原因：{}", runningSlave.id(), ex.getMessage());
                }
            }
            ModbusSlaveFactory.close();
            log.info("所有 Mock 控制器已停止。");
        }));

        log.info("Mock Modbus 串口服务器已全部就绪，配置文件：{}", configPath.toAbsolutePath());
        new CountDownLatch(1).await();
    }

    private static Path resolveConfigPath(String[] args) {
        Path defaultPath = Path.of("config", "mock-controllers.properties");
        if (args == null || args.length == 0) {
            return defaultPath;
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (arg.startsWith("-D")) {
                log.warn("检测到参数 '{}'。注意：-D 是 JVM 参数，应放在 -jar 之前。将忽略该参数。", arg);
                continue;
            }
            if (arg.startsWith("--config=")) {
                return Path.of(arg.substring("--config=".length()));
            }
            if ("--config".equals(arg) && i + 1 < args.length) {
                return Path.of(args[++i]);
            }
            return Path.of(arg);
        }
        return defaultPath;
    }

    private static RunningSlave startController(MockConfig.ControllerMockConfig controller,
                                                int workerThreads,
                                                int readTimeoutMillis) throws Exception {
        InetAddress bindAddress = InetAddress.getByName(controller.bindHost());
        ModbusSlave slave = ModbusSlaveFactory.createTCPSlave(
            bindAddress,
            controller.port(),
            workerThreads,
            false,
            readTimeoutMillis
        );

        SimpleProcessImage processImage = buildProcessImage(controller);
        slave.addProcessImage(controller.unitId(), processImage);
        slave.open();
        return new RunningSlave(controller.id(), controller.port(), slave);
    }

    private static SimpleProcessImage buildProcessImage(MockConfig.ControllerMockConfig controller) throws ModbusException {
        int maxRegister = controller.startRegister() + controller.probeCount() - 1;
        SimpleProcessImage processImage = new SimpleProcessImage(controller.unitId());

        for (int i = 0; i <= maxRegister; i++) {
            processImage.addRegister(new SimpleRegister(0));
        }

        for (int probe = 0; probe < controller.probeCount(); probe++) {
            int registerAddress = controller.startRegister() + probe;
            processImage.setRegister(registerAddress, new RandomRegister(controller.randomMin(), controller.randomMax()));
        }

        return processImage;
    }

    private static void initLogCharset() {
        if (hasText(System.getProperty("LOG_CHARSET"))) {
            return;
        }
        String charset = firstNonBlank(
            System.getProperty("stdout.encoding"),
            System.getProperty("sun.stdout.encoding"),
            System.getProperty("file.encoding"),
            "UTF-8"
        );
        System.setProperty("LOG_CHARSET", charset);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "UTF-8";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record RunningSlave(String id, int port, ModbusSlave slave) {
    }
}
