# Win7 离线部署检查清单

> 适用场景：将本屏幕录像工程迁移到一台**断网**的 Windows 7（64 位）电脑进行后续开发与运行。
> 已在本机验证：离线构建 `mvn -o -s settings.xml clean package` → `BUILD SUCCESS`，本地仓库依赖完整。

---

## 第一阶段：源机准备（联网机器上完成）

### 1.1 确认离线构建可行（必做，否则迁移后无法构建）

- [ ] 在源机执行离线构建验证：
      `mvn -o -s settings.xml clean package -DskipTests`
- [ ] 结果为 `BUILD SUCCESS`（无 `Could not resolve` / `Downloading`）—— 证明本地仓库完整，可迁移
- [ ] 若失败提示缺依赖：回到联网状态执行 `mvn -s settings.xml dependency:go-offline` 补全后重试

### 1.2 确认待复制项的实际路径与大小

| 复制项 | 源机路径 | 预计大小 | 用途 |
|---|---|---|---|
| JDK 1.8 x64 | `D:\devbase\jdk\jdk1.8\JDK1.8` | ~250MB | 编译 + 运行 |
| Maven 3.8.8 | `D:\devbase\apache-maven-3.8.8` | ~10MB | 构建 |
| 本地 Maven 仓库 | `D:\devbase\repository` | ~43MB | 全部依赖 + 插件（javacv/ffmpeg/shade 等） |
| 项目源码 | `d:\workspace\aipro\autoRecorder\recorder\` | 小 | 开发用 |
| recorder.jar（可选） | `recorder\target\recorder.jar` | ~30MB | 仅运行、不开发时用 |

- [ ] 确认上述路径存在（命令：`mvn -version` 看 Maven home 与 Java home；查 `D:\devbase\repository` 总大小约 43MB）
- [ ] 确认 `repository` 内 `org\bytedeco` 子目录存在（~28MB，JavaCV/FFmpeg 核心）

### 1.3 清理与打包

- [ ] 关闭所有运行中的录制进程（避免 jar 被锁）：`java -jar target\recorder.jar stop`，或结束残留 `java.exe`
- [ ] 排除不需要的目录：`target\`（构建产物，目标机重新生成）、`.idea\`/`.vscode\`（IDE 配置）、`*.mp4`（测试视频）
- [ ] 将 4 项 + 源码复制到 U盘/移动硬盘（建议保留原 `D:\devbase\...` 路径结构，省去改配置）

> 省心方案：直接整个 `D:\devbase\` 复制（含 JDK+Maven+仓库），再单独复制源码目录。

---

## 第二阶段：目标机（Win7）环境搭建

### 2.1 系统前提

- [ ] Win7 为 **64 位**系统（32 位系统会导致 FFmpeg 原生库加载失败）
- [ ] 已安装必要的系统补丁（Win7 SP1 + KB2999226 等 VC++ 运行库，避免 Java/Maven 启动异常）
- [ ] 目标机确实断网（用 `ping baidu.com` 验证不通 —— 确认需走离线流程）

### 2.2 复制到目标机

- [ ] JDK → 保持 `D:\devbase\jdk\jdk1.8\JDK1.8`（路径不变则无需改任何配置）
- [ ] Maven → 保持 `D:\devbase\apache-maven-3.8.8`
- [ ] 本地仓库 → 保持 `D:\devbase\repository`
- [ ] 源码 → 保持 `d:\workspace\aipro\autoRecorder\recorder\`

> 若必须改路径（如目标盘符为 E:）：所有路径统一替换后，还需修改 `recorder\settings.xml` 里的 `<localRepository>`。

### 2.3 配置环境变量（右键计算机→属性→高级系统设置→环境变量）

- [ ] 新建系统变量 `JAVA_HOME` = `D:\devbase\jdk\jdk1.8\JDK1.8`
- [ ] 新建系统变量 `MAVEN_HOME` = `D:\devbase\apache-maven-3.8.8`
- [ ] 修改系统变量 `PATH`，追加：`;%JAVA_HOME%\bin;%MAVEN_HOME%\bin`
- [ ] 重开命令行窗口使环境变量生效

### 2.4 环境变量验证

- [ ] `java -version` → 显示 `1.8.0_xxx`（64-Bit Server VM）
- [ ] `javac -version` → 显示 `1.8.0_xxx`（确认 JDK 非仅 JRE）
- [ ] `mvn -version` → 显示 `Apache Maven 3.8.8`，Maven home 指向目标机路径，Java home 指向 JDK

---

## 第三阶段：离线构建验证

### 3.1 执行离线构建（关键：必须带 `-o`）

- [ ] 进入源码目录：`cd d:\workspace\aipro\autoRecorder\recorder`
- [ ] 执行：`mvn -o -s settings.xml clean package -DskipTests`
- [ ] 结果：`BUILD SUCCESS`
- [ ] 产物存在：`target\recorder.jar`（约 29-30MB）

### 3.2 若构建失败的排查

| 报错关键字 | 原因 | 处理 |
|---|---|---|
| `Could not resolve dependencies` / `Could not find artifact` | 本地仓库缺依赖 | 回源机 `mvn dependency:go-offline` 补全仓库后重新复制 |
| `Failed to delete target\recorder.jar` | jar 被运行中的 java 进程占用 | 先 `taskkill /F /IM java.exe` 或停掉录制进程再构建 |
| `No compiler is provided` 或 `Unable to find javac` | 用的是 JRE 不是 JDK | 确认 `JAVA_HOME` 指向 JDK（含 `javac.exe`） |
| `mvn 不是内部或外部命令` | PATH 未生效 | 检查环境变量并重开命令行 |
| `UnsatisfiedLinkError` / 原生库加载失败 | 目标机 32 位 JDK | 换装 64 位 JDK 1.8 |

---

## 第四阶段：运行验证

### 4.1 GUI 有界面模式

- [ ] 执行：`java -jar target\recorder.jar`
- [ ] Swing 窗口正常弹出，开始/暂停/停止按钮可用
- [ ] 录制一段后停止，生成 mp4 文件
- [ ] 播放 mp4：**颜色正常**（无红蓝颠倒）、**画面清晰**（默认 8Mbps 码率）

### 4.2 无界面模式（CLI + HTTP API）

- [ ] 开始：`java -jar target\recorder.jar start --fps 15 --bitrate 8M --output out.mp4`
- [ ] 状态：`java -jar target\recorder.jar status` → 返回 JSON `state:RECORDING`
- [ ] 停止：`java -jar target\recorder.jar stop` → 返回 `state:IDLE`
- [ ] 文件：`out.mp4` 生成且可正常播放

### 4.3 外部 HTTP API

- [ ] 启动 API：`java -jar target\recorder.jar api`
- [ ] 调用：`curl -X POST "http://localhost:8080/api/recorder/start?fps=15&bitrate=8M"`
- [ ] 状态：`curl http://localhost:8080/api/recorder/status` → 返回 JSON
- [ ] 停止：`curl -X POST http://localhost:8080/api/recorder/stop`

### 4.4 仅运行模式（不开发）

- [ ] 只需复制：JDK 1.8 x64 + `recorder.jar`（两样）
- [ ] 执行：`java -jar recorder.jar` 即可，无需 Maven/本地仓库

---

## 第五阶段：收尾

- [ ] 清理测试产生的 `*.mp4` 文件（如 `color_test.mp4`、`bitrate_test.mp4`、`out.mp4`）
- [ ] 确认 `settings.xml` 的 `<localRepository>` 路径与目标机实际仓库路径一致
- [ ] 记录目标机 Java/Maven 实际安装路径备查
- [ ] 如需团队复用：将 `D:\devbase` 压缩成 zip 作为"离线开发基座"存档

---

## 关键命令速查

```bash
# 离线构建（断网必加 -o）
cd d:\workspace\aipro\autoRecorder\recorder
mvn -o -s settings.xml clean package -DskipTests

# 运行
java -jar target\recorder.jar                      # GUI
java -jar target\recorder.jar start --fps 30 --bitrate 8M --output out.mp4   # 无界面录制
java -jar target\recorder.jar stop                 # 停止
java -jar target\recorder.jar status               # 状态

# 进程占用导致无法构建时
taskkill /F /IM java.exe      # 谨慎：会结束所有 java 进程
```

## 默认参数

| 参数 | 默认值 |
|---|---|
| 帧率 | 30 fps |
| 分辨率 | 屏幕分辨率 |
| 视频码率 | 8 Mbps |
| 输出路径 | 当前目录 `recording_<时间戳>.mp4` |
| HTTP API 端口 | 8080 |

## 注意事项

1. **必须 64 位**：JDK 与 Win7 系统都要 64 位，否则 FFmpeg 原生库加载失败。
2. **`-o` 不能少**：断网构建时缺 `-o` 会尝试联网下载而失败。
3. **jar 被锁**：构建/复制前确保无 java 进程占用 `recorder.jar`。
4. **路径一致性**：保持 `D:\devbase\...` 原路径最省心；改路径需同步改 `settings.xml`。
5. **JavaCV 缓存**：若运行时报 `.javacpp` 不可写，加 JVM 参数 `-Djavacpp.cachedir=D:\devbase\.javacpp` 指向可写目录。
