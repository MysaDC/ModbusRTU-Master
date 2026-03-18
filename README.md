# QB6000 Data Acquisition Service

基于文档《开发文档.txt》《QB6000控制器通信协议.txt》实现的 Java 轻量级采集服务：

- 通过 **Modbus TCP** 周期性读取多个 QB6000 控制器寄存器（功能码 0x03）
- 将寄存器值转换为气体浓度
- 组装 JSON 并发布到 MQTT Broker（同 topic）
- 提供重试、断线重连、日志追踪

## 1. 构建

运行环境要求：**JDK 21**。

```bash
mvn clean package
```

打包后可执行 Jar：

- `target/modbusmaster-1.0.0.jar`
- `target/modbusmaster-1.0.0-shaded.jar`（包含依赖，推荐）

## 2. 配置

默认读取 `config/application.properties`。

关键配置：

- `poll.interval.ms`：轮询周期
- `controllers.count`：控制器数量（可选，便于显式声明）
- `controllers.N.host/port/unit-id/start-register/channel-count`：多控制器
- `modbus.*`：控制器默认参数（可被 `controllers.N.*` 覆盖）
- `mqtt.broker-uri/topic/qos`
- `concentration.scale/offset`

> 协议文档寄存器从 `0x0065` 开始，对应十进制 `101`，已在默认配置中设置。

## 3. 启动

```bash
java -jar target/modbusmaster-1.0.0-shaded.jar
```

或指定配置文件：

```bash
java -jar target/modbusmaster-1.0.0-shaded.jar D:/deploy/application.properties
```

## 4. MQTT 报文示例

```json
{
  "controllerIp": "192.168.10.11",
  "timestamp": "2026-03-18T07:00:00Z",
  "channels": [
    {
      "channel": 1,             //探头通道号（从 1 开始的逻辑编号）
      "register": 101,          //该通道对应的 Modbus 寄存器地址（这里 101 即 0x0065）
      "rawValue": 35,           //从寄存器直接读出来的原始整数值（未换算）
      "concentration": 35.0     //按配置换算后的浓度值
    }
  ]
}
```

## 5. 说明

- 单次读取最多 125 个寄存器，程序已自动分包读取（兼容 128 通道）
- Modbus 读取失败会按 `modbus.max-retries` 重试
- MQTT 发布采用异步队列 + 自动重连
- 多控制器数据统一发布到同一 topic，通过 `controllerIp` 区分来源
