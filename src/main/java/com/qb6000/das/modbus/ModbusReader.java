package com.qb6000.das.modbus;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.qb6000.das.config.ServiceConfig;
import com.qb6000.das.model.ChannelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public final class ModbusReader implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(ModbusReader.class);
    private static final int MAX_READ_REGISTERS_PER_REQUEST = 125;
    private static final int READ_HOLDING_REGISTERS_FUNCTION = 0x03;

    private final ServiceConfig.ModbusConfig config;
    private final ServiceConfig.ConcentrationConfig concentrationConfig;
    private final String endpoint;

    private ModbusTCPMaster master;
    private Socket rtuSocket;
    private DataInputStream rtuInput;
    private DataOutputStream rtuOutput;

    public ModbusReader(ServiceConfig.ModbusConfig config,
                        ServiceConfig.ConcentrationConfig concentrationConfig) {
        this.config = config;
        this.concentrationConfig = concentrationConfig;
        this.endpoint = config.host() + ":" + config.port();
    }

    public synchronized List<ChannelData> readChannels() throws IOException {
        if (config.crcCheckEnabled()) {
            return readChannelsWithCrcFilter();
        }
        return readChannelsByBlockWithRetry();
    }

    private List<ChannelData> readChannelsByBlockWithRetry() throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= config.maxRetries(); attempt++) {
            try {
                connectTcpMasterIfNeeded();
                return doReadChannelsByBlock();
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

    private List<ChannelData> doReadChannelsByBlock() throws ModbusException {
        int totalChannels = config.channelCount();
        int startRegister = config.startRegister();
        int currentChannel = 1;
        int offset = 0;
        List<ChannelData> result = new ArrayList<>(totalChannels);

        while (offset < totalChannels) {
            int blockSize = Math.min(MAX_READ_REGISTERS_PER_REQUEST, totalChannels - offset);
            int readRegister = startRegister + offset;
            logTcpFrameRequest(readRegister, blockSize);
            Register[] block = master.readMultipleRegisters(config.unitId(), readRegister, blockSize);
            if (block == null || block.length != blockSize) {
                throw new ModbusException("Modbus 响应长度无效");
            }
            logTcpFrameResponse(block);

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

    private List<ChannelData> readChannelsWithCrcFilter() throws IOException {
        int totalChannels = config.channelCount();
        int startRegister = config.startRegister();
        int offset = 0;
        List<ChannelData> result = new ArrayList<>(totalChannels);

        while (offset < totalChannels) {
            int blockSize = Math.min(MAX_READ_REGISTERS_PER_REQUEST, totalChannels - offset);
            int readRegister = startRegister + offset;
            int[] block = readBlockWithCrcPolicy(readRegister, blockSize);
            if (block == null) {
                offset += blockSize;
                continue;
            }

            for (int i = 0; i < block.length; i++) {
                int raw = block[i];
                int register = readRegister + i;
                int channel = offset + i + 1;
                double concentration = raw * concentrationConfig.scale() + concentrationConfig.offset();
                result.add(new ChannelData(channel, register, raw, concentration));
            }
            offset += blockSize;
        }

        if (result.size() < totalChannels) {
            log.warn(
                "本轮轮询存在被丢弃的整帧数据，端点：{}，成功通道数：{}，配置通道数：{}",
                endpoint,
                result.size(),
                totalChannels
            );
        }
        return result;
    }

    private int[] readBlockWithCrcPolicy(int startRegister, int quantity) {
        int maxAttempts = config.crcFailureImmediateRetryEnabled()
            ? config.crcFailureMaxRetriesUntilNextPoll()
            : 1;

        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                connectRtuSocketIfNeeded();
                int[] block = readRegistersBlockWithCrc(startRegister, quantity);
                if (attempt > 1) {
                    log.info(
                        "整帧 CRC/请求失败后重试成功，端点：{}，起始寄存器：{}，数量：{}，尝试次数：{}",
                        endpoint,
                        startRegister,
                        quantity,
                        attempt
                    );
                }
                return block;
            } catch (CrcValidationException ex) {
                lastError = ex;
                log.warn(
                    "整帧 CRC 校验失败，已丢弃该帧，端点：{}，起始寄存器：{}，数量：{}，尝试次数：{}/{}",
                    endpoint,
                    startRegister,
                    quantity,
                    attempt,
                    maxAttempts
                );
            } catch (IOException ex) {
                lastError = ex;
                log.warn(
                    "读取整帧失败，端点：{}，起始寄存器：{}，数量：{}，尝试次数：{}/{}，原因：{}",
                    endpoint,
                    startRegister,
                    quantity,
                    attempt,
                    maxAttempts,
                    ex.getMessage()
                );
            }
            disconnectRtuQuietly();
            sleepRetryBackoffIfNeeded(attempt, maxAttempts);
        }

        if (lastError != null) {
            log.error(
                "整帧数据在本轮已达最大重试并被丢弃，端点：{}，起始寄存器：{}，数量：{}，最大重试：{}，原因：{}",
                endpoint,
                startRegister,
                quantity,
                maxAttempts,
                lastError.getMessage()
            );
        }
        return null;
    }

    private int[] readRegistersBlockWithCrc(int startRegister, int quantity) throws IOException {
        byte[] request = buildReadRegistersRequest(config.unitId(), startRegister, quantity);
        logRtuFrame("发送", request);
        rtuOutput.write(request);
        rtuOutput.flush();

        byte[] response = readRtuResponseFrame();
        validateResponseFrame(response, startRegister, quantity);

        int[] values = new int[quantity];
        for (int i = 0; i < quantity; i++) {
            int high = response[3 + i * 2] & 0xFF;
            int low = response[4 + i * 2] & 0xFF;
            values[i] = (high << 8) | low;
        }
        return values;
    }

    private byte[] readRtuResponseFrame() throws IOException {
        int unitId = readUnsignedByteOrThrow("读取从站地址失败");
        int function = readUnsignedByteOrThrow("读取功能码失败");

        if ((function & 0x80) != 0) {
            int exceptionCode = readUnsignedByteOrThrow("读取异常码失败");
            byte[] frame = new byte[] {
                (byte) unitId,
                (byte) function,
                (byte) exceptionCode,
                (byte) readUnsignedByteOrThrow("读取异常响应 CRC 低字节失败"),
                (byte) readUnsignedByteOrThrow("读取异常响应 CRC 高字节失败")
            };
            logRtuFrame("接收", frame);
            validateFrameCrc(frame);
            throw new IOException(String.format("从站异常响应，功能码=0x%02X，异常码=0x%02X", function, exceptionCode));
        }

        int byteCount = readUnsignedByteOrThrow("读取字节计数失败");
        byte[] frame = new byte[3 + byteCount + 2];
        frame[0] = (byte) unitId;
        frame[1] = (byte) function;
        frame[2] = (byte) byteCount;
        rtuInput.readFully(frame, 3, byteCount + 2);

        logRtuFrame("接收", frame);
        validateFrameCrc(frame);
        return frame;
    }

    private void validateResponseFrame(byte[] response, int startRegister, int quantity) throws IOException {
        int unitId = response[0] & 0xFF;
        int function = response[1] & 0xFF;
        int byteCount = response[2] & 0xFF;

        if (unitId != config.unitId()) {
            throw new IOException(
                String.format(
                    "响应从站地址不匹配，期望=%d，实际=%d，起始寄存器=%d，数量=%d",
                    config.unitId(),
                    unitId,
                    startRegister,
                    quantity
                )
            );
        }
        if (function != READ_HOLDING_REGISTERS_FUNCTION) {
            throw new IOException(
                String.format(
                    "响应功能码不匹配，期望=0x03，实际=0x%02X，起始寄存器=%d，数量=%d",
                    function,
                    startRegister,
                    quantity
                )
            );
        }
        int expectedBytes = quantity * 2;
        if (byteCount != expectedBytes) {
            throw new IOException(
                String.format(
                    "响应数据长度异常，期望=%d，实际=%d，起始寄存器=%d，数量=%d",
                    expectedBytes,
                    byteCount,
                    startRegister,
                    quantity
                )
            );
        }
    }

    private int readUnsignedByteOrThrow(String message) throws IOException {
        try {
            return rtuInput.readUnsignedByte();
        } catch (EOFException ex) {
            throw new IOException(message, ex);
        }
    }

    private void validateFrameCrc(byte[] frame) throws CrcValidationException {
        if (frame.length < 4) {
            throw new CrcValidationException("响应帧长度不足，无法进行 CRC 校验");
        }

        int expected = ((frame[frame.length - 1] & 0xFF) << 8) | (frame[frame.length - 2] & 0xFF);
        int actual = calculateCrc16(frame, 0, frame.length - 2);
        if (expected != actual) {
            throw new CrcValidationException(
                String.format(
                    "CRC 校验失败，期望=0x%04X，实际=0x%04X，原始帧=%s",
                    expected,
                    actual,
                    toHex(frame)
                )
            );
        }
    }

    private static byte[] buildReadRegistersRequest(int unitId, int startRegister, int quantity) {
        byte[] request = new byte[8];
        request[0] = (byte) unitId;
        request[1] = (byte) READ_HOLDING_REGISTERS_FUNCTION;
        request[2] = (byte) ((startRegister >>> 8) & 0xFF);
        request[3] = (byte) (startRegister & 0xFF);
        request[4] = (byte) ((quantity >>> 8) & 0xFF);
        request[5] = (byte) (quantity & 0xFF);

        int crc = calculateCrc16(request, 0, 6);
        request[6] = (byte) (crc & 0xFF);
        request[7] = (byte) ((crc >>> 8) & 0xFF);
        return request;
    }

    private static int calculateCrc16(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= data[i] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(String.format("%02X", data[i] & 0xFF));
        }
        return builder.toString();
    }

    private void logTcpFrameRequest(int startRegister, int quantity) {
        if (!log.isDebugEnabled()) {
            return;
        }
        byte[] requestPdu = buildReadRegistersPdu(config.unitId(), startRegister, quantity);
        log.debug("Modbus 原始指令[发送][TCP]，端点：{}，hex={}", endpoint, toHex(requestPdu));
    }

    private void logTcpFrameResponse(Register[] block) {
        if (!log.isDebugEnabled()) {
            return;
        }
        byte[] responsePdu = buildReadRegistersResponsePdu(config.unitId(), block);
        log.debug("Modbus 原始指令[接收][TCP]，端点：{}，hex={}", endpoint, toHex(responsePdu));
    }

    private void logRtuFrame(String direction, byte[] frame) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("Modbus 原始指令[{}][RTU-over-TCP]，端点：{}，hex={}", direction, endpoint, toHex(frame));
    }

    private static byte[] buildReadRegistersPdu(int unitId, int startRegister, int quantity) {
        return new byte[] {
            (byte) unitId,
            (byte) READ_HOLDING_REGISTERS_FUNCTION,
            (byte) ((startRegister >>> 8) & 0xFF),
            (byte) (startRegister & 0xFF),
            (byte) ((quantity >>> 8) & 0xFF),
            (byte) (quantity & 0xFF)
        };
    }

    private static byte[] buildReadRegistersResponsePdu(int unitId, Register[] block) {
        byte[] response = new byte[3 + block.length * 2];
        response[0] = (byte) unitId;
        response[1] = (byte) READ_HOLDING_REGISTERS_FUNCTION;
        response[2] = (byte) (block.length * 2);
        for (int i = 0; i < block.length; i++) {
            int value = block[i].getValue() & 0xFFFF;
            response[3 + i * 2] = (byte) ((value >>> 8) & 0xFF);
            response[4 + i * 2] = (byte) (value & 0xFF);
        }
        return response;
    }

    private void connectTcpMasterIfNeeded() throws Exception {
        if (master != null && master.isConnected()) {
            return;
        }
        log.info("正在连接 Modbus TCP，端点：{}，单元ID：{}", endpoint, config.unitId());
        master = new ModbusTCPMaster(config.host(), config.port());
        master.setTimeout(config.timeoutMillis());
        master.connect();
        log.info("Modbus TCP 连接成功，端点：{}", endpoint);
    }

    private void connectRtuSocketIfNeeded() throws IOException {
        if (rtuSocket != null
            && rtuSocket.isConnected()
            && !rtuSocket.isClosed()
            && !rtuSocket.isInputShutdown()
            && !rtuSocket.isOutputShutdown()) {
            return;
        }

        disconnectRtuQuietly();

        Socket socket = new Socket();
        socket.setReuseAddress(true);
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(config.host(), config.port()), config.timeoutMillis());
        socket.setSoTimeout(config.timeoutMillis());

        rtuSocket = socket;
        rtuInput = new DataInputStream(socket.getInputStream());
        rtuOutput = new DataOutputStream(socket.getOutputStream());
        log.info("Modbus RTU over TCP 连接成功，端点：{}，单元ID：{}", endpoint, config.unitId());
    }

    private void sleepBackoffIfNeeded(int attempt) {
        if (attempt >= config.maxRetries()) {
            return;
        }
        sleepRetryBackoff(config.retryBackoff().toMillis());
    }

    private void sleepRetryBackoffIfNeeded(int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) {
            return;
        }
        sleepRetryBackoff(config.retryBackoff().toMillis());
    }

    private static void sleepRetryBackoff(long millis) {
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
        disconnectRtuQuietly();
        if (master != null) {
            try {
                master.disconnect();
            } catch (Exception ignored) {
                // ignored
            }
            master = null;
        }
    }

    private void disconnectRtuQuietly() {
        if (rtuInput != null) {
            try {
                rtuInput.close();
            } catch (Exception ignored) {
                // ignored
            }
            rtuInput = null;
        }
        if (rtuOutput != null) {
            try {
                rtuOutput.close();
            } catch (Exception ignored) {
                // ignored
            }
            rtuOutput = null;
        }
        if (rtuSocket != null) {
            try {
                rtuSocket.close();
            } catch (Exception ignored) {
                // ignored
            }
            rtuSocket = null;
        }
    }

    @Override
    public synchronized void close() {
        disconnectQuietly();
    }

    private static final class CrcValidationException extends IOException {
        CrcValidationException(String message) {
            super(message);
        }
    }
}
