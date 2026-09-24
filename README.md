# 屏幕录像 (Screen Recorder)

基于 Java 的屏幕录像工具，支持有界面（GUI）和无界面（命令行/HTTP API）两种模式，输出 MP4 视频，可配置帧率、分辨率、输出路径、视频码率。对外提供 HTTP REST API 与 Java 编程式 API，便于其他模块集成调用。

## 功能特性

- **屏幕录像**：采集整屏或指定区域，鼠标光标与键盘操作自然入画
- **MP4 输出**：FFmpeg 编码（优先 H.264，不可用时回退 mpeg4），yuv420p 像素格式，播放器兼容性好
- **双模式运行**
  - 有界面模式：Swing 窗口，开始/暂停/停止按钮 + 配置面板
  - 无界面模式：命令行启动后台录制，同时暴露 HTTP API 供停止/状态查询
- **可配置项**：帧率、分辨率、输出路径、视频码率、HTTP API 端口、自动分段
- **外部 API**：
  - HTTP REST API（`start` / `pause` / `resume` / `stop` / `status`）
  - Java 编程式 API（`RecorderService` 接口，进程内直接调用）
- **颜色正确**：强制 `TYPE_3BYTE_BGR` 中转，避免 Windows 下 R/B 通道颠倒偏色
- **状态机**：`IDLE → RECORDING → PAUSED → IDLE`，线程安全
- **自动分段**：可按文件大小自动切片（默认 20MB），生成 `原名.mp4` / `原名_1.mp4` / `原名_2.mp4` ...，每段均可独立播放

## 技术栈

| 项 | 选型 |
|---|---|
| 语言/JDK | Java 1.8（建议 64 位） |
| 视频采集 | `java.awt.Robot`（JDK 标准库） |
| 视频编码 | JavaCV 1.5.10（封装 FFmpeg 6.1.1） |
| GUI | Swing（JDK 标准库） |
| HTTP API | `com.sun.net.httpserver.HttpServer`（JDK 标准库，无额外依赖） |
| 构建 | Maven 3.x，maven-shade-plugin 打 fat jar |

## 项目结构

```
recorder/
├── pom.xml                       # Maven 构建配置
├── settings.xml                  # 阿里云镜像 + 本地仓库（构建加速，可选）
├── README.md
└── src/main/java/com/airecorder/
    ├── RecorderApplication.java  # 主入口，按参数分发 GUI/CLI/API
    ├── config/RecorderConfig.java  # 录制配置（帧率/分辨率/码率/输出/端口）
    ├── core/
    │   ├── RecorderState.java    # 状态机枚举
    │   ├── RecorderService.java  # Java 编程式 API 接口
    │   └── ScreenRecorder.java   # 核心实现：Robot 截屏 + FFmpeg 编码
    ├── api/HttpApiServer.java    # HTTP REST API 服务
    ├── cli/CliLauncher.java      # 命令行解析与分发
    └── gui/RecorderGui.java      # Swing 界面
```

## 构建与打包

依赖体积较大（FFmpeg 原生库约 80MB），首次构建建议使用阿里云镜像加速：

```bash
mvn -s settings.xml clean package -DskipTests
```

产物：`target/recorder.jar`（约 29MB 的可执行 fat jar，已包含 Windows x86_64 原生库）。

> 注：`pom.xml` 当前仅引入 `windows-x86_64` 原生库以减小体积。如需全平台分发，将 `javacv` + `javacpp`/`ffmpeg` 的 classifier 依赖替换为 `javacv-platform` 即可。

## 运行方式

### GUI 有界面模式

```bash
java -jar target/recorder.jar
# 或
java -jar target/recorder.jar gui
```

打开 Swing 窗口，可设置帧率/分辨率/码率/输出路径，点击开始/暂停/停止。

### 无界面模式（命令行 + HTTP API）

```bash
# 后台开始录制（同时监听 HTTP API），启用 20MB 自动分段
java -jar target/recorder.jar start --fps 30 --resolution 1920x1080 --bitrate 8M --output out.mp4 --segment true --segment-size 20M

# 查询状态
java -jar target/recorder.jar status

# 停止录制（通过 HTTP 调用本地 API，停止后进程退出）
java -jar target/recorder.jar stop

# 仅启动 HTTP API 服务，不自动录制（由外部调用 start）
java -jar target/recorder.jar api
```

### 外部 HTTP API

无界面模式启动后默认监听 `http://localhost:8080`：

```bash
# 开始录制（参数均可选），启用 20MB 自动分段
curl -X POST "http://localhost:8080/api/recorder/start?fps=30&width=1920&height=1080&bitrate=8M&output=out.mp4&segment=true&segmentSize=20M"

# 暂停 / 恢复
curl -X POST http://localhost:8080/api/recorder/pause
curl -X POST http://localhost:8080/api/recorder/resume

# 停止
curl -X POST http://localhost:8080/api/recorder/stop

# 查询状态（返回 JSON：state/outputFile/config）
curl http://localhost:8080/api/recorder/status
```

响应示例：
```json
{"ok":true,"state":"RECORDING","outputFile":"D:\\...\\out.mp4","config":{"fps":30,"width":1920,"height":1080,"bitrate":8000000,"segmentEnabled":true,"segmentSize":20971520}}
```

### Java 编程式 API（进程内调用）

```java
RecorderService service = new ScreenRecorder();
RecorderConfig config = new RecorderConfig()
        .setFps(30)
        .setResolution(1920, 1080)
        .setVideoBitrate(8_000_000)
        .setOutputFile(new File("out.mp4"))
        .setSegmentEnabled(true)                  // 启用自动分段
        .setSegmentSizeBytes(20L * 1024 * 1024);  // 阈值 20MB
service.start(config);   // 开始
service.pause();        // 暂停
service.resume();       // 恢复
service.stop();         // 停止并完成 MP4 封装
service.getState();     // 获取状态
service.getLastOutputFile(); // 获取当前段输出文件
```

## 配置参数

| 参数 | CLI 选项 | HTTP 参数 | 默认值 |
|---|---|---|---|
| 帧率 | `--fps <N>` | `fps` | 30 |
| 分辨率 | `--resolution <WxH>` | `width`+`height` | 屏幕分辨率 |
| 视频码率 | `--bitrate <V>` | `bitrate` | 8M (8000000 bps) |
| 输出路径 | `--output <path>` | `output` | 当前目录 `recording_<时间戳>.mp4` |
| 自动分段开关 | `--segment <bool>` | `segment` | false |
| 分段阈值 | `--segment-size <V>` | `segmentSize` | 20M (20971520 字节) |
| API 端口 | `--port <N>` | — | 8080 |

码率格式支持：`8M` / `8000k` / `8000000`（单位 bps）。
分段大小格式支持：`20M` / `20480K` / `20971520`（单位字节，K=1024、M=1024×1024）。

## 命令一览

```bash
java -jar recorder.jar                # 启动 GUI
java -jar recorder.jar gui            # 启动 GUI
java -jar recorder.jar start [opts]   # 无界面开始录制
java -jar recorder.jar stop  [--port N]
java -jar recorder.jar status [--port N]
java -jar recorder.jar api    [--port N]
java -jar recorder.jar --help        # 帮助
```

## 注意事项

1. **JDK 版本/架构**：需 64 位 JDK 1.8 运行。32 位 JDK 可能缺少对应原生库导致 `UnsatisfiedLinkError`。
2. **构建加速**：Maven 中央仓库直连国内较慢，使用项目内 `settings.xml`（阿里云镜像）可显著提速。
3. **`.javacpp` 缓存**：JavaCV 默认在 `~/.javacpp` 缓存原生库。若该目录不可写，可通过 `-Djavacpp.cachedir=<可写目录>` 指定。
4. **编码器**：优先使用 `libx264`（H.264）；若当前 FFmpeg 构建未内置 libx264，自动回退 `mpeg4`，仍为标准 MP4。可通过提高码率保证清晰度。
5. **颜色**：已通过 `TYPE_3BYTE_BGR` 中转修复 Windows 下红蓝颠倒偏色问题。
6. **无界面停止**：`start` 命令启动的进程需通过 `stop` 命令或 `POST /api/recorder/stop` 停止后才会完成 MP4 封装并退出；强制结束进程可能导致 MP4 损坏。

## 常见问题

**Q: 录制视频偏色（红蓝互换）？**
A: 已修复。若仍出现，确认运行的是最新 jar，且 `ScreenRecorder` 中 `bgrImage` 中转逻辑存在。

**Q: 视频模糊？**
A: 提高码率，如 `--bitrate 12M` 或 `--bitrate 16M`。

**Q: 打包时 `Failed to delete target/recorder.jar`？**
A: 有录制进程仍在运行并持有 jar 文件锁。先执行 `java -jar target/recorder.jar stop` 或结束残留 java 进程再打包。

**Q: 录制帧率达不到设定值？**
A: 高分辨率 + 高码率 + 高帧率对 CPU 压力大。可降低分辨率/帧率，或在更快的机器上运行。

**Q: 如何按大小自动分段（避免单文件过大）？**
A: 启用分段后，单文件超过阈值（默认 20MB）会自动切换到新文件继续录制，旧文件完成 MP4 封装仍可独立播放。
- GUI：勾选"自动分段"，输入阈值（如 `20M`）
- CLI：`--segment true --segment-size 20M`
- HTTP：`?segment=true&segmentSize=20M`
- 文件命名：`out.mp4` → `out_1.mp4` → `out_2.mp4` ...
- 编程式：`config.setSegmentEnabled(true).setSegmentSizeBytes(20L * 1024 * 1024)`
