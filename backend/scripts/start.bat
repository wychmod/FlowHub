@echo off
setlocal
chcp 65001 >nul
rem 本文件以 UTF-8（无 BOM）编码保存，此处切换代码页以保证中文显示正常。
rem 部分环境（如 Git Bash 派生的进程）会携带 NoDefaultCurrentDirectoryInExePath=1，
rem 禁止 cmd 从当前目录查找可执行文件，导致子窗口里 mvnw.cmd 报"不是内部或外部命令"。
rem 这里显式清除，保证本脚本及其子窗口始终可用。
set "NoDefaultCurrentDirectoryInExePath="
rem 本脚本位于 backend/scripts/ 下，仓库根目录为其上两级；统一换算为绝对路径，
rem 保证无论从哪里双击/调用本脚本，工作目录与子窗口路径都指向正确位置。
for %%I in ("%~dp0..\..") do set "ROOT=%%~fI"
cd /d "%ROOT%"

echo ================================================================
echo   ExportFlow 一键启动
echo   前端 http://localhost:5174    后端 http://localhost:8080
echo ================================================================
echo.

if not exist "frontend\node_modules" (
    echo [1/3] 首次运行：安装前端依赖 npm install ...
    pushd "frontend"
    call npm install
    if errorlevel 1 goto :install_failed
    popd
) else (
    echo [1/3] 前端依赖已就绪，跳过安装。
)
echo.

echo [2/3] 启动后端（Spring Boot，端口 8080）...
start "ExportFlow Backend 8080" /D "%ROOT%\backend" cmd /k .\mvnw.cmd spring-boot:run
echo.

echo [3/3] 启动前端（Vite，端口 5174）...
start "ExportFlow Frontend 5174" /D "%ROOT%\frontend" cmd /k npm run dev
echo.

echo 启动指令已发出：
echo   - 前端页面：  http://localhost:5174
echo   - 订单接口：  http://localhost:8080/api/v1/orders
echo   - 健康检查：  http://localhost:8080/actuator/health
echo.
echo 两个新窗口分别承载前后端日志，关闭窗口即停止对应服务。
echo 后端首次启动需下载 Maven 依赖，待窗口出现 "Started ExportFlowApplication" 即就绪。
echo.
pause
exit /b 0

:install_failed
popd 2>nul
echo.
echo 前端依赖安装失败，请检查网络（npm 镜像源）后重新运行本脚本。
pause
exit /b 1
