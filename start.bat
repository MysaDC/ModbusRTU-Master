@echo off
setlocal enabledelayedexpansion

:: 1. 获取脚本所在目录 (去除末尾可能的反斜杠，方便后续拼接)
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

:: 2. 定义路径 (注意：增加了引号以防止空格问题)
set "JAR_PATH=%SCRIPT_DIR%\modbusmaster-1.0.0-shaded.jar"
set "CONFIG_PATH=%SCRIPT_DIR%\application.properties"
set "JAVA_EXE=%SCRIPT_DIR%\zulu25.32.21-ca-jdk25.0.2-win_x64\bin\java.exe"

:: 3. 参数覆盖逻辑 (支持外部传入 jar 和 config 路径)
if not "%~1"=="" set "JAR_PATH=%~1"
if not "%~2"=="" set "CONFIG_PATH=%~2"

:: 4. 设置默认 JVM 参数
if "%JAVA_OPTS%"=="" set "JAVA_OPTS=-Xms512m -Xmx1024m -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -DLOG_CHARSET=UTF-8"

:: 5. 检查 Java 是否存在
if not exist "%JAVA_EXE%" (
    echo [ERROR] Java not found at: "%JAVA_EXE%"
    echo Please check the JDK folder name.
    pause
    exit /b 1
)

:: 6. 检查 Jar 是否存在
if not exist "%JAR_PATH%" (
    echo [ERROR] Jar not found: "%JAR_PATH%"
    echo Build first: mvn clean package
    pause
    exit /b 1
)

:: 7. 检查 Config 是否存在
if not exist "%CONFIG_PATH%" (
    echo [ERROR] Config not found: "%CONFIG_PATH%"
    pause
    exit /b 1
)

:: 8. 启动服务
echo ==========================================
echo Starting service...
echo Java:  "%JAVA_EXE%"
echo Jar:   "%JAR_PATH%"
echo Config:"%CONFIG_PATH%"
echo ==========================================

:: 执行启动命令
chcp 65001
"%JAVA_EXE%" %JAVA_OPTS% -jar "%JAR_PATH%" "%CONFIG_PATH%"

:: 9. 捕获退出码
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
    echo.
    echo [ERROR] Service exited with code %EXIT_CODE%
    echo Check logs for details.
) else (
    echo.
    echo [INFO] Service stopped normally.
)

:: 如果是双击运行，出错时暂停以便查看日志
if "%EXIT_CODE%" neq "0" pause
exit /b %EXIT_CODE%