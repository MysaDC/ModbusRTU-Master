# Mock Modbus 串口服务器（测试项目）

用于联调主项目：一次启动多个 Modbus TCP Mock 控制器。
每个控制器下挂若干“探头”寄存器，收到轮询读取时返回随机值。

## 构建

```bash
cd mock-modbus-simulator
mvn clean package
```

## 启动

默认配置文件：`config/mock-controllers.properties`

```bash
java -DLOG_CHARSET=UTF-8 -jar target/mock-modbus-simulator-1.0.0.jar
```

或指定配置文件：

```bash
java -jar target/mock-modbus-simulator-1.0.0.jar D:/mock/mock-controllers.properties
```

## 配置说明

- `controller.count`：控制器数量
- `controller.N.port`：第 N 个控制器监听端口
- `controller.N.unit-id`：Modbus 从站地址
- `controller.N.start-register`：探头起始寄存器（默认 101）
- `controller.N.probe-count`：探头数量
- `controller.N.random-min/random-max`：随机值范围

## 对接主项目示例

假设本 mock 在本机运行，则主项目可以配置：

```properties
controllers.count=4
controllers.1.host=127.0.0.1
controllers.1.port=15021
controllers.1.channel-count=4

controllers.2.host=127.0.0.1
controllers.2.port=15022
controllers.2.channel-count=2

controllers.3.host=127.0.0.1
controllers.3.port=15023
controllers.3.channel-count=4

controllers.4.host=127.0.0.1
controllers.4.port=15024
controllers.4.channel-count=7
```
