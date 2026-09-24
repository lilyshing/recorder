package com.airecorder.api;

import com.airecorder.config.RecorderConfig;
import com.airecorder.core.RecorderService;
import com.airecorder.core.RecorderState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP REST API 服务，基于 JDK 内置 com.sun.net.httpserver.HttpServer（无额外依赖）。
 * 包装 RecorderService，对外暴露 start/pause/resume/stop/status。
 *
 * 路由：
 *   POST /api/recorder/start?fps=30&width=1920&height=1080&output=<path>
 *   POST /api/recorder/pause
 *   POST /api/recorder/resume
 *   POST /api/recorder/stop
 *   GET  /api/recorder/status
 *
 * 响应格式：JSON，如 {"ok":true,"state":"RECORDING","outputFile":"..."}
 */
public class HttpApiServer {

    /** 被包装的录制服务 */
    private final RecorderService service;
    /** HTTP 服务监听端口 */
    private final int port;
    /** JDK 内置 HttpServer 实例，start 后非 null */
    private HttpServer server;
    /** 关机钩子：stop 接口调用后通知主线程退出（仅 CLI 启动模式使用） */
    private Runnable onStopped;

    /**
     * 构造 HTTP API 服务。
     *
     * @param service 被包装的录制服务实例
     * @param port    HTTP 监听端口
     */
    public HttpApiServer(RecorderService service, int port) {
        this.service = service;
        this.port = port;
    }

    /**
     * 设置关机钩子。stop 接口被调用且响应发送完成后，会在新线程中触发该钩子，
     * 通常用于通知 CLI 主线程退出进程。
     *
     * @param onStopped 关机回调，传 null 表示不触发
     */
    public void setOnStopped(Runnable onStopped) {
        this.onStopped = onStopped;
    }

    /**
     * 启动 HTTP API 服务，注册所有路由并开始监听。
     *
     * @throws IOException 端口被占用或创建 HttpServer 失败
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/recorder/start", new StartHandler());
        server.createContext("/api/recorder/pause", wrap(ex -> {
            service.pause();
            return null;
        }));
        server.createContext("/api/recorder/resume", wrap(ex -> {
            service.resume();
            return null;
        }));
        server.createContext("/api/recorder/stop", wrap(ex -> {
            service.stop();
            return null;
        }));
        server.createContext("/api/recorder/status", wrap(ex -> null));
        server.setExecutor(null);
        server.start();
        System.out.println("[API] HTTP API 监听 http://localhost:" + port + "/api/recorder/*");
    }

    /** 停止 HTTP API 服务并释放监听端口。 */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * 包装处理 checked 异常的 handler。
     * <p>统一捕获 Throwable 转为 500 错误响应；对 stop/status 路径特殊处理：
     * stop 响应后异步触发关机钩子，status 直接返回当前状态 JSON。
     *
     * @param h 业务处理逻辑（可抛出任意异常）
     * @return 包装后的 HttpHandler
     */
    private HttpHandler wrap(ThrowingHandler h) {
        return exchange -> {
            Object result;
            try {
                result = h.handle(exchange);
            } catch (Throwable t) {
                sendJson(exchange, 500, errorJson(t.getMessage()));
                return;
            }
            // stop 后触发关机钩子（在响应发送后由主线程退出）
            if ("/api/recorder/stop".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 200, statusJson());
                if (onStopped != null) {
                    final Runnable r = onStopped;
                    new Thread(r, "api-shutdown").start();
                }
                return;
            }
            if ("/api/recorder/status".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 200, statusJson());
                return;
            }
            sendJson(exchange, 200, result != null ? result.toString() : statusJson());
        };
    }

    /** 可抛出任意异常的函数式接口，供 wrap 包装使用 */
    @FunctionalInterface
    private interface ThrowingHandler {
        /** 处理一次 HTTP 交换，返回业务结果对象（其 toString 作为响应体） */
        Object handle(HttpExchange exchange) throws Throwable;
    }

    /** start 处理器：从 query 解析参数构造配置 */
    private class StartHandler implements HttpHandler {
        /**
         * 处理 start 请求。
         * <p>从 query 解析 fps/width/height/output/bitrate，构造 {@link RecorderConfig}
         * 调用 service.start，成功返回状态 JSON，失败返回 500 错误。
         *
         * @param exchange HTTP 交换对象
         * @throws IOException 写响应时发生 I/O 错误
         */
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorJson("仅支持 POST"));
                return;
            }
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            try {
                RecorderConfig config = new RecorderConfig();
                if (params.containsKey("fps")) {
                    config.setFps(Integer.parseInt(params.get("fps")));
                }
                if (params.containsKey("width") && params.containsKey("height")) {
                    config.setResolution(Integer.parseInt(params.get("width")),
                            Integer.parseInt(params.get("height")));
                }
                if (params.containsKey("output")) {
                    config.setOutputFile(new java.io.File(params.get("output")));
                }
                if (params.containsKey("bitrate")) {
                    config.setVideoBitrate(RecorderConfig.parseBitrate(params.get("bitrate")));
                }
                // 自动分段：segment=true&segmentSize=20M
                if (params.containsKey("segment")) {
                    config.setSegmentEnabled(Boolean.parseBoolean(params.get("segment")));
                }
                if (params.containsKey("segmentSize")) {
                    config.setSegmentSizeBytes(RecorderConfig.parseSize(params.get("segmentSize")));
                }
                service.start(config);
                sendJson(exchange, 200, statusJson());
            } catch (Throwable t) {
                sendJson(exchange, 500, errorJson(t.getMessage()));
            }
        }
    }

    /** 构造当前状态 JSON 字符串，包含 state/outputFile/config 等字段 */
    private String statusJson() {
        RecorderState s = service.getState();
        RecorderConfig c = service.getCurrentConfig();
        java.io.File out = service.getLastOutputFile();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"state\":\"").append(s.name()).append("\"");
        if (out != null) {
            sb.append(",\"outputFile\":\"").append(escape(out.getAbsolutePath())).append("\"");
        }
        if (c != null) {
            java.awt.Rectangle a = c.resolveCaptureArea();
            sb.append(",\"config\":{\"fps\":").append(c.getFps())
                    .append(",\"width\":").append(a.width)
                    .append(",\"height\":").append(a.height)
                    .append(",\"bitrate\":").append(c.getVideoBitrate())
                    .append(",\"segmentEnabled\":").append(c.isSegmentEnabled())
                    .append(",\"segmentSize\":").append(c.getSegmentSizeBytes())
                    .append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    /** 构造错误响应 JSON 字符串，msg 经 JSON 转义后写入 error 字段 */
    private String errorJson(String msg) {
        return "{\"ok\":false,\"error\":\"" + escape(msg == null ? "" : msg) + "\"}";
    }

    /** JSON 字符串转义：转义引号、反斜杠与控制字符，保证可安全嵌入 JSON 字符串字面量 */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * 发送 JSON 响应。设置 Content-Type 与 CORS 头，写入响应体后关闭流。
     *
     * @param exchange HTTP 交换对象
     * @param code     HTTP 状态码
     * @param body     响应体字符串（UTF-8 编码）
     * @throws IOException 写响应时发生 I/O 错误
     */
    private void sendJson(HttpExchange exchange, int code, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    /** 解析 query string 为 map，值 URL 解码 */
    private static Map<String, String> parseQuery(String query) throws java.io.UnsupportedEncodingException {
        Map<String, String> map = new HashMap<String, String>();
        if (query == null || query.isEmpty()) {
            return map;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            String k, v;
            if (idx < 0) {
                k = pair;
                v = "";
            } else {
                k = pair.substring(0, idx);
                v = pair.substring(idx + 1);
            }
            map.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
        }
        return map;
    }
}
