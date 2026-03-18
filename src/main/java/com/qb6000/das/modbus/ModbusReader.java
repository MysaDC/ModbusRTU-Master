package com.qb6000.das.modbus;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.qb6000.das.config.ServiceConfig;
import com.qb6000.das.model.ChannelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ModbusReader implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(ModbusReader.class);
    private static final int MAX_READ_REGISTERS_PER_REQUEST = 125;

    private final ServiceConfig.ModbusConfig config;
    private final ServiceConfig.ConcentrationConfig concentrationConfig;
    private final String endpoint;

    private ModbusTCPMaster master;

    public ModbusReader(ServiceConfig.ModbusConfig config,
                        ServiceConfig.ConcentrationConfig concentrationConfig) {
        this.config = config;
        this.concentrationConfig = concentrationConfig;
        this.endpoint = config.host() + ":" + config.port();
    }

    public synchronized List<ChannelData> readChannels() throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= config.maxRetries(); attempt++) {
            try {
                connectIfNeeded();
                return doReadChannels();
            } catch (Exception ex) {
                lastError = new IOException("Modbus 读取失败，第 " + attempt + " 次重试", ex);
                log.warn("Modbus 读取失败，端点：{}，重试次数：{}/{}，原因：{}",
                    endpoint, attempt, config.maxRetries(), ex.getMessage());
                disconnectQuietly();
                sleepBackoffIfNeeded(attempt);
            }
        }

        throw lastError == null ? new IOException("Modbus 读取失败") : lastError;
    }

    private List<ChannelData> doReadChannels() throws ModbusException {
        int totalChannels = config.channelCount();
        int startRegister = config.startRegister();
        int currentChannel = 1;
        int offset = 0;
        List<ChannelData> result = new ArrayList<>(totalChannels);

        while (offset < totalChannels) {
            int blockSize = Math.min(MAX_READ_REGISTERS_PER_REQUEST, totalChannels - offset);
            int readRegister = startRegister + offset;
            Register[] block = master.readMultipleRegisters(config.unitId(), readRegister, blockSize);
            if (block == null || block.length != blockSize) {
                throw new ModbusException("Modbus 响应长度无效");
            }

            for (int i = 0; i < block.length; i++) {
                int raw = block[i].getValue() & 0xFFFF;
                int register = readRegister + i;
                double concentration = raw * concentrationConfig.scale() + concentrationConfig.offset();
                result.add(new ChannelData(currentChannel++, register, raw, concentration));
            }
            offset += blockSize;
        }
        return result;
    }

    private void connectIfNeeded() throws Exception {
        if (master != null && master.isConnected()) {
            return;
        }
        log.info("正在连接 Modbus TCP，端点：{}，单元ID：{}", endpoint, config.unitId());
        master = new ModbusTCPMaster(config.host(), config.port());
        master.setTimeout(config.timeoutMillis());
        master.connect();
        log.info("Modbus TCP 连接成功，端点：{}", endpoint);
    }

    private void sleepBackoffIfNeeded(int attempt) {
        if (attempt >= config.maxRetries()) {
            return;
        }
        long millis = config.retryBackoff().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private void disconnectQuietly() {
        if (master != null) {
            try {
                master.disconnect();
            } catch (Exception ignored) {
                // ignored
            }
            master = null;
        }
    }

    @Override
    public synchronized void close() {
        disconnectQuietly();
    }
}
