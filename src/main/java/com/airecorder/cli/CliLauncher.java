package com.airecorder.cli;

import com.airecorder.api.HttpApiServer;
import com.airecorder.config.RecorderConfig;
import com.airecorder.core.RecorderService;
import com.airecorder.core.ScreenRecorder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 命令行启动器（无界面模式）。
 *
 * 用法：
 *   java -jar recorder.jar start [--fps N] [--resolution WxH] [--output <path>] [--port N]
 *        启动后台录制进程，同时暴露 HTTP API，可被 stop 命令或外部模块调用停止。
 *   java -jar recorder.jar stop   [--port N]
 *        通过 HTTP API 停止本地正在运行的录制进程。
 *   java -jar recorder.jar status [--port N]
 *        查询本地录制进程状态。
 *   java -jar recorder.jar api    [--port N]
 *        仅启动 HTTP API 服务，不自动开始录制（由外部调用 start）。
 *   java -jar recorder.jar --help
 *        打印帮助。
 */
public class CliLauncher {

    /** 默认 HTTP API 端口，与配置默认值保持一致 */
    private static final int DEFAULT_PORT = RecorderConfig.DEFAULT_API_PORT;

    /**
     * 命令行入口：根据子命令分发到对应处理逻辑。
     *
     * @param args 命令行参数，args[0] 为子命令（start/stop/status/api/--help 等）
     * @return 进程退出码，0 成功，非 0 失败
     */
    public static int run(String[] args) {
        if (args.length == 0) {
            printHelp();
            return 1;
        }
        String cmd = args[0];
        try {
            switch (cmd) {
                case "start":
                    return cmdStart(args);
                case "stop":
                    return cmdStop(args);
                case "status":
                    return cmdStatus(args);
                case "api":
                    return cmdApi(args);
                case "--help":
                case "-h":
                case "help":
                    printHelp();
                    return 0;
                default:
                    System.err.println("未知命令: " + cmd);
                    printHelp();
                    return 1;
            }
        } catch (Throwable t) {
            System.err.println("[CLI] 执行失败: " + t.getMessage());
            t.printStackTrace();
            return 1;
        }
    }

    /** start：后台录制 + 暴露 HTTP API，阻塞至收到 stop */
    private static int cmdStart(String[] args) throws Exception {
        Map<String, String> opts = parseOptions(args, 1);
        RecorderConfig config = buildConfig(opts);
        int port = parseInt(opts.get("port"), DEFAULT_PORT);

        RecorderService service = new ScreenRecorder();
        HttpApiServer api = new HttpApiServer(service, port);

        // 关机钩子：stop API 被调用后，等待录制停止，然后退出进程
        final Object lock = new Object();
        final boolean[] stopped = {false};
        api.setOnStopped(() -> {
            synchronized (lock) {
                stopped[0] = true;
                lock.notifyAll();
            }
        });

        // 注册 JVM 关机钩子：Ctrl+C 或 kill 时优雅停止录制
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                service.stop();
            } catch (Exception e) {
                // ignore
            }
            api.stop();
        }, "cli-shutdown"));

        service.start(config);
        api.start();
        System.out.println("[CLI] 录制中，输入 'java -jar recorder.jar stop' 或调用 "
                + "POST http://localhost:" + port + "/api/recorder/stop 停止。");

        // 阻塞主线程，直到 stop API 被调用
        synchronized (lock) {
            while (!stopped[0]) {
                lock.wait();
            }
        }
        // 让 stop 处理完成文件封装
        Thread.sleep(500);
        api.stop();
        System.out.println("[CLI] 进程退出。");
        return 0;
    }

    /** stop：向本地 HTTP API 发停止请求 */
    private static int cmdStop(String[] args) throws IOException {
        Map<String, String> opts = parseOptions(args, 1);
        int port = parseInt(opts.get("port"), DEFAULT_PORT);
        String resp = httpCall("POST", "http://localhost:" + port + "/api/recorder/stop", "");
        System.out.println("[CLI] 停止响应: " + resp);
        return 0;
    }

    /** status：向本地 HTTP API 发状态查询请求并打印响应 */
    private static int cmdStatus(String[] args) throws IOException {
        Map<String, String> opts = parseOptions(args, 1);
        int port = parseInt(opts.get("port"), DEFAULT_PORT);
        String resp = httpCall("GET", "http://localhost:" + port + "/api/recorder/status", null);
        System.out.println("[CLI] 状态: " + resp);
        return 0;
    }

    /** api：仅启动 HTTP API，不录制，等待外部 start 调用 */
    private static int cmdApi(String[] args) throws IOException, InterruptedException {
        Map<String, String> opts = parseOptions(args, 1);
        int port = parseInt(opts.get("port"), DEFAULT_PORT);
        RecorderService service = new ScreenRecorder();
        HttpApiServer api = new HttpApiServer(service, port);
        api.start();
        System.out.println("[CLI] 仅 API 模式，按 Ctrl+C 退出。");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { service.stop(); } catch (Exception e) { }
            api.stop();
        }, "cli-shutdown"));
        // 阻塞主线程
        Thread.currentThread().join();
        return 0;
    }

    /** 从命令参数构造录制配置 */
    private static RecorderConfig buildConfig(Map<String, String> opts) {
        RecorderConfig config = new RecorderConfig();
        if (opts.containsKey("fps")) {
            config.setFps(parseInt(opts.get("fps"), RecorderConfig.DEFAULT_FPS));
        }
        if (opts.containsKey("resolution")) {
            int[] wh = RecorderConfig.parseResolution(opts.get("resolution"));
            if (wh != null) {
                config.setResolution(wh[0], wh[1]);
            }
        }
        if (opts.containsKey("output")) {
            config.setOutputFile(new java.io.File(opts.get("output")));
        }
        if (opts.containsKey("bitrate")) {
            config.setVideoBitrate(RecorderConfig.parseBitrate(opts.get("bitrate")));
        }
        return config;
    }

    /** 解析 --key=value 或 --key value 形式参数 */
    private static Map<String, String> parseOptions(String[] args, int start) {
        Map<String, String> opts = new HashMap<String, String>();
        for (int i = start; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                int eq = a.indexOf('=');
                String k, v;
                if (eq >= 0) {
                    k = a.substring(2, eq);
                    v = a.substring(eq + 1);
                } else {
                    k = a.substring(2);
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        v = args[++i];
                    } else {
                        v = "true";
                    }
                }
                opts.put(k, v);
            }
        }
        return opts;
    }

    /** 安全解析整数，非法或为空时返回默认值 */
    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 发送 HTTP 请求并返回响应体 */
    private static String httpCall(String method, String url, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(10000);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) {
            return "{\"code\":" + code + "}";
        }
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        is.close();
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 打印命令行帮助信息到标准输出 */
    private static void printHelp() {
        System.out.println("屏幕录像工具 (Screen Recorder) - 用法:");
        System.out.println("  java -jar recorder.jar                启动 GUI 界面模式");
        System.out.println("  java -jar recorder.jar gui            启动 GUI 界面模式");
        System.out.println("  java -jar recorder.jar start [options] 无界面模式开始录制");
        System.out.println("  java -jar recorder.jar stop  [--port N] 停止本地录制进程");
        System.out.println("  java -jar recorder.jar status [--port N] 查询本地录制状态");
        System.out.println("  java -jar recorder.jar api    [--port N] 仅启动 HTTP API 服务");
        System.out.println("");
        System.out.println("start 选项:");
        System.out.println("  --fps <N>            帧率，默认 " + RecorderConfig.DEFAULT_FPS);
        System.out.println("  --resolution <WxH>   分辨率，如 1920x1080，默认屏幕分辨率");
        System.out.println("  --output <path>      输出 mp4 文件路径，默认当前目录");
        System.out.println("  --bitrate <V>        视频码率，如 8M/8000k/8000000，默认 8M");
        System.out.println("  --port <N>           HTTP API 端口，默认 " + DEFAULT_PORT);
        System.out.println("");
        System.out.println("HTTP API (无界面模式启动后可用):");
        System.out.println("  POST /api/recorder/start?fps=30&width=1920&height=1080&output=<path>");
        System.out.println("  POST /api/recorder/pause");
        System.out.println("  POST /api/recorder/resume");
        System.out.println("  POST /api/recorder/stop");
        System.out.println("  GET  /api/recorder/status");
    }
}
