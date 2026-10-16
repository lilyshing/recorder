# 屏幕录像 (Screen Recorder)

基于 Java 的屏幕录像工具，支持有界面（GUI）和无界面（命令行/HTTP API）两种模式，输出 MP4 视频，可配置视频编码（H.264/H.265）、帧率、分辨率、输出路径、视频码率。对外提供 HTTP REST API 与 Java 编程式 API，便于其他模块集成调用。

## 功能特性

- **屏幕录像**：采集整屏或指定区域，鼠标光标与键盘操作自然入画
- **MP4 输出**：H.264 / H.265 可配；启动前对候选编码器做运行期实测，选出当前环境真实可用者，**不会静默回退成 mpeg4**；yuv420p 像素格式，播放器兼容性好
- **H.265 能力探测**：选择 H.265 时自动检测 libx265 / hevc_nvenc / hevc_qsv / hevc_mf，当前环境无可用编码器时弹窗/命令行/API 明确提示
- **双模式运行**
  - 有界面模式：Swing 窗口，开始/暂停/停止按钮 + 配置面板
  - 无界面模式：命令行启动后台录制，同时暴露 HTTP API 供停止/状态查询
- **可配置项**：视频编码、帧率、分辨率、输出路径、视频码率、HTTP API 端口、自动分段
- **外部 API**：
  - HTTP REST API（`start` / `pause` / `resume` / `stop` / `status` / `capabilities`）
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
    ├── config/
    │   ├── RecorderConfig.java  # 录制配置（编码/帧率/分辨率/码率/输出/端口/分段）
    │   └── VideoCodec.java      # 视频编码枚举 H.264/H.265 及候选编码器链
    ├── core/
    │   ├── RecorderState.java    # 状态机枚举
    │   ├── RecorderService.java  # Java 编程式 API 接口
    │   ├── CodecSupport.java     # 编码器运行期能力探测（avcodec_open2 试打开）
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

打开 Swing 窗口，可设置视频编码（H.264/H.265 下拉框）/帧率/分辨率/码率/输出路径，点击开始/暂停/停止。选择 H.265 时若本机无可用 HEVC 编码器，会立即弹窗提示并自动回退到 H.264。

### 无界面模式（命令行 + HTTP API）

```bash
# 后台开始录制（同时监听 HTTP API），H.265 编码 + 20MB 自动分段
java -jar target/recorder.jar start --codec h265 --fps 30 --resolution 1920x1080 --bitrate 8M --output out.mp4 --segment true --segment-size 20M

# 若当前环境不支持 H.265，命令会以退出码 2 拒绝启动并打印已尝试的编码器，改用 H.264 即可：
java -jar target/recorder.jar start --codec h264 --output out.mp4

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
# 开始录制（参数均可选），H.265 编码 + 20MB 自动分段
curl -X POST "http://localhost:8080/api/recorder/start?codec=h265&fps=30&width=1920&height=1080&bitrate=8M&output=out.mp4&segment=true&segmentSize=20M"

# 查询本机支持的视频编码（返回每种格式是否可用、实际选中的编码器、候选链）
curl http://localhost:8080/api/recorder/capabilities

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
{"ok":true,"state":"RECORDING","outputFile":"D:\\...\\out.mp4","config":{"codec":"H265","fps":30,"width":1920,"height":1080,"bitrate":8000000,"segmentEnabled":true,"segmentSize":20971520}}
```

capabilities 响应示例（当前环境无可用 H.265 编码器）：
```json
{"ok":true,"codecs":[{"name":"H264","displayName":"H.264","supported":true,"encoder":"libopenh264","candidates":["libx264","libopenh264","h264_mf","h264_nvenc","h264_qsv"]},{"name":"H265","displayName":"H.265","supported":false,"encoder":"","candidates":["libx265","hevc_nvenc","hevc_qsv","hevc_mf"]}]}
```

当选择了本机不支持的编码时，`start` 返回 HTTP 500：
```json
{"ok":false,"error":"当前运行环境没有可用的 H.265 编码器，已尝试: libx265, hevc_nvenc, hevc_qsv, hevc_mf。请改用 H.264；..."}
```

### Java 编程式 API（进程内调用）

```java
RecorderService service = new ScreenRecorder();
RecorderConfig config = new RecorderConfig()
        .setVideoCodec(VideoCodec.H265)             // H.265(HEVC)，默认 H264；不支持时 start 抛 IllegalStateException
        .setFps(30)
        .setResolution(1920, 1080)
        .setVideoBitrate(8_000_000)
        .setOutputFile(new File("out.mp4"))
        .setSegmentEnabled(true)                  // 启用自动分段
        .setSegmentSizeBytes(20L * 1024 * 1024);  // 阈值 20MB
// 录制前也可自行探测：CodecSupport.resolveEncoder(VideoCodec.H265) 返回可用编码器名或 null
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
| 视频编码 | `--codec <h264\|h265>` | `codec` | h264（兼容 h.264/h.265/hevc/libx265 等写法） |
| 帧率 | `--fps <N>` | `fps` | 30 |
| 分辨率 | `--resolution <WxH>` | `width`+`height` | 屏幕分辨率 |
| 视频码率 | `--bitrate <V>` | `bitrate` | CLI/API 默认 4M；GUI 输入框默认 2M |
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
4. **编码器选择与能力探测**：录制开始前会对候选编码器做真实的 `avcodec_open2` 试打开，选出当前环境可用者并缓存结果，避免"名义上选了 H.264/H.265，实际静默回退 mpeg4"。
   - H.264 候选链：`libx264` → `libopenh264`（随包分发的纯软件编码器，离线/Win7 可用）→ `h264_mf` → `h264_nvenc` → `h264_qsv`
   - H.265 候选链：`libx265` → `hevc_nvenc` → `hevc_qsv` → `hevc_mf`（后三者依赖 NVIDIA/Intel 硬件或 Windows HEVC 视频扩展，Win7 不提供）
   - 当前随包 FFmpeg 6.1.1（Windows x86_64）为非 GPL 构建：不含 libx264/libx265，H.264 实际使用 libopenh264；若无任何可用 H.265 编码器会明确拒绝并提示，不会产出错误编码的文件。
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

**Q: 选择 H.265 后提示"当前运行环境没有可用的 H.265 编码器"？**
A: 程序在录制前会依次试打开 `libx265`/`hevc_nvenc`/`hevc_qsv`/`hevc_mf`，全部失败才会提示。解决办法：
- 直接改用 H.264（GUI 会自动回退；CLI 用 `--codec h264`）；
- 在带 NVIDIA 显卡（较新驱动）或 Intel 核显的机器上运行，以使用 NVENC/QSV 硬编码；
- Windows 10/11 可在"应用商店"安装"HEVC 视频扩展"以启用 `hevc_mf`（Win7 不支持该组件）；
- 或更换内置 libx265 的 GPL 版 FFmpeg 原生包重新打包。
- 可先调用 `GET /api/recorder/capabilities` 查看本机编码能力。

**Q: 怎么确认录出来的确实是 H.264 而不是别的编码？**
A: 程序只使用能力探测通过的编码器名，不再静默回退。录制启动日志会打印实际编码器，如 `H.264/libopenh264`；状态/能力接口也会返回 `encoder` 字段。
