package com.airecorder.config;

import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.io.File;

/**
 * 录制配置：帧率、分辨率、输出路径、API 端口。
 * 所有字段均有默认值，可通过 Builder 或 CLI 参数覆盖。
 */
public class RecorderConfig {

    /** 默认帧率 30fps */
    public static final int DEFAULT_FPS = 30;
    /** 默认 HTTP API 端口 */
    public static final int DEFAULT_API_PORT = 8080;
    /** 默认视频码率 8Mbps(8000000bps)，1080p 清晰 */
    public static final int DEFAULT_VIDEO_BITRATE = 8_000_000;
    /** 默认分段阈值 20MB(20*1024*1024 字节)，启用分段后单文件超过此大小自动切片 */
    public static final long DEFAULT_SEGMENT_SIZE_BYTES = 20L * 1024 * 1024;

    /** 帧率(fps) */
    private int fps = DEFAULT_FPS;
    /** 录制区域，null 表示整屏 */
    private Rectangle captureArea;
    /** 输出文件，默认当前目录下 recording_<时间戳>.mp4 */
    private File outputFile;
    /** HTTP API 端口 */
    private int apiPort = DEFAULT_API_PORT;
    /** 视频码率(bps) */
    private int videoBitrate = DEFAULT_VIDEO_BITRATE;
    /** 是否启用按大小自动分段，默认 false 需用户主动开启 */
    private boolean segmentEnabled = false;
    /** 分段阈值(字节)，segmentEnabled=true 时生效，默认 20MB */
    private long segmentSizeBytes = DEFAULT_SEGMENT_SIZE_BYTES;

    /** 默认构造器：输出文件采用带时间戳的默认路径 */
    public RecorderConfig() {
        this.outputFile = defaultOutputFile();
    }

    /** 获取帧率 */
    public int getFps() {
        return fps;
    }

    /**
     * 设置帧率。
     *
     * @param fps 帧率，必须大于 0
     * @return 当前配置对象（链式调用）
     * @throws IllegalArgumentException 当 fps 小于等于 0
     */
    public RecorderConfig setFps(int fps) {
        if (fps <= 0) {
            throw new IllegalArgumentException("fps 必须大于 0，实际: " + fps);
        }
        this.fps = fps;
        return this;
    }

    /** 获取录制区域，返回 null 表示使用整屏 */
    public Rectangle getCaptureArea() {
        return captureArea;
    }

    /**
     * 设置录制区域。
     *
     * @param captureArea 录制矩形区域，传 null 表示使用整屏
     * @return 当前配置对象（链式调用）
     */
    public RecorderConfig setCaptureArea(Rectangle captureArea) {
        this.captureArea = captureArea;
        return this;
    }

    /** 设置分辨率（宽x高），录制区域从屏幕左上角开始 */
    public RecorderConfig setResolution(int width, int height) {
        this.captureArea = new Rectangle(0, 0, width, height);
        return this;
    }

    /** 使用屏幕分辨率作为录制区域（默认） */
    public RecorderConfig useScreenResolution() {
        this.captureArea = null;
        return this;
    }

    /** 获取输出文件路径 */
    public File getOutputFile() {
        return outputFile;
    }

    /**
     * 设置输出文件路径。
     *
     * @param outputFile 输出 MP4 文件，若父目录不存在则会在 start 时自动创建
     * @return 当前配置对象（链式调用）
     */
    public RecorderConfig setOutputFile(File outputFile) {
        this.outputFile = outputFile;
        return this;
    }

    /** 获取 HTTP API 监听端口 */
    public int getApiPort() {
        return apiPort;
    }

    /**
     * 设置 HTTP API 监听端口。
     *
     * @param apiPort 端口号
     * @return 当前配置对象（链式调用）
     */
    public RecorderConfig setApiPort(int apiPort) {
        this.apiPort = apiPort;
        return this;
    }

    /** 获取视频码率（bps） */
    public int getVideoBitrate() {
        return videoBitrate;
    }

    /**
     * 设置视频码率。
     *
     * @param videoBitrate 视频码率，单位 bps，必须大于 0
     * @return 当前配置对象（链式调用）
     * @throws IllegalArgumentException 当码率小于等于 0
     */
    public RecorderConfig setVideoBitrate(int videoBitrate) {
        if (videoBitrate <= 0) {
            throw new IllegalArgumentException("videoBitrate 必须大于 0，实际: " + videoBitrate);
        }
        this.videoBitrate = videoBitrate;
        return this;
    }

    /** 是否启用按大小自动分段 */
    public boolean isSegmentEnabled() {
        return segmentEnabled;
    }

    /**
     * 设置是否启用按大小自动分段。
     *
     * @param segmentEnabled true 启用，false 关闭
     * @return 当前配置对象（链式调用）
     */
    public RecorderConfig setSegmentEnabled(boolean segmentEnabled) {
        this.segmentEnabled = segmentEnabled;
        return this;
    }

    /** 获取分段阈值(字节) */
    public long getSegmentSizeBytes() {
        return segmentSizeBytes;
    }

    /**
     * 设置分段阈值(字节)。
     *
     * @param segmentSizeBytes 阈值字节数，必须大于 0
     * @return 当前配置对象（链式调用）
     * @throws IllegalArgumentException 当阈值小于等于 0
     */
    public RecorderConfig setSegmentSizeBytes(long segmentSizeBytes) {
        if (segmentSizeBytes <= 0) {
            throw new IllegalArgumentException("segmentSizeBytes 必须大于 0，实际: " + segmentSizeBytes);
        }
        this.segmentSizeBytes = segmentSizeBytes;
        return this;
    }

    /** 计算实际录制区域：未指定则取主屏幕全屏 */
    public Rectangle resolveCaptureArea() {
        if (captureArea != null) {
            return captureArea;
        }
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        return new Rectangle(0, 0, screen.width, screen.height);
    }

    /** 默认输出到当前工作目录，文件名带时间戳 */
    public static File defaultOutputFile() {
        String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        String dir = System.getProperty("user.dir");
        return new File(dir, "recording_" + ts + ".mp4");
    }

    /** 解析 "WxH" 形式分辨率字符串，如 "1920x1080" */
    public static int[] parseResolution(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        String[] parts = s.toLowerCase().split("x");
        if (parts.length != 2) {
            throw new IllegalArgumentException("分辨率格式应为 WxH，如 1920x1080，实际: " + s);
        }
        try {
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            if (w <= 0 || h <= 0) {
                throw new IllegalArgumentException("分辨率宽高必须大于 0: " + s);
            }
            return new int[]{w, h};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("分辨率格式应为 WxH，如 1920x1080，实际: " + s);
        }
    }

    /** 解析码率字符串，支持 "8M"/"8000k"/"8000000"，单位 bps */
    public static int parseBitrate(String s) {
        if (s == null || s.trim().isEmpty()) {
            return DEFAULT_VIDEO_BITRATE;
        }
        String t = s.trim().toLowerCase();
        long mult = 1;
        if (t.endsWith("k")) {
            mult = 1000L;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            mult = 1_000_000L;
            t = t.substring(0, t.length() - 1);
        }
        try {
            long val = (long) Double.parseDouble(t) * mult;
            if (val <= 0 || val > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("码率数值无效: " + s);
            }
            return (int) val;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("码率格式错误，应为 8M/8000k/8000000，实际: " + s);
        }
    }

    /**
     * 解析文件大小字符串为字节数，支持 "20M"/"20480K"/"20971520"。
     * <p>注意：此处采用二进制单位——K=1024、M=1024×1024，符合"文件大小"习惯；
     * 与 {@link #parseBitrate(String)} 的十进制单位(1000/1_000_000)有意区分。
     *
     * @param s 大小字符串，null 或空返回默认分段阈值
     * @return 字节数
     * @throws IllegalArgumentException 格式错误或数值非正
     */
    public static long parseSize(String s) {
        if (s == null || s.trim().isEmpty()) {
            return DEFAULT_SEGMENT_SIZE_BYTES;
        }
        String t = s.trim().toLowerCase();
        long mult = 1;
        if (t.endsWith("k")) {
            mult = 1024L;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            mult = 1024L * 1024L;
            t = t.substring(0, t.length() - 1);
        }
        try {
            long val = (long) Double.parseDouble(t) * mult;
            if (val <= 0) {
                throw new IllegalArgumentException("大小数值无效: " + s);
            }
            return val;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("大小格式错误，应为 20M/20480K/20971520，实际: " + s);
        }
    }

    /** 输出配置摘要字符串，包含帧率/分辨率/码率/输出路径/端口/分段 */
    @Override
    public String toString() {
        Rectangle a = resolveCaptureArea();
        return "RecorderConfig{fps=" + fps
                + ", area=" + a.width + "x" + a.height
                + ", bitrate=" + videoBitrate + "bps"
                + ", output=" + (outputFile == null ? "null" : outputFile.getAbsolutePath())
                + ", apiPort=" + apiPort
                + ", segment=" + (segmentEnabled ? "enabled" : "off")
                + ", segmentSize=" + segmentSizeBytes + "B}";
    }
}
