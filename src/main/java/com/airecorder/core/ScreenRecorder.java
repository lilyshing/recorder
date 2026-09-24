package com.airecorder.core;

import com.airecorder.config.RecorderConfig;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 屏幕录制核心实现。
 * 采集：java.awt.Robot 截屏
 * 编码：JavaCV 的 FFmpegFrameRecorder，输出 MP4(H.264)
 *
 * 状态机由 synchronized 块守护，录制在工作线程中循环采集+编码。
 */
public class ScreenRecorder implements RecorderService {

    /** 状态机与编码器共享的同步锁，守护所有状态流转与对 recorder 的访问 */
    private final Object lock = new Object();

    /** 当前录制状态，volatile 保证采集线程与调用线程间的可见性 */
    private volatile RecorderState state = RecorderState.IDLE;
    /** 当前录制使用的配置，未开始时为 null */
    private volatile RecorderConfig currentConfig;
    /** 最近一次输出文件，停止后可用于回显路径 */
    private volatile File lastOutputFile;

    /** 屏幕截图器，基于 java.awt.Robot */
    private Robot robot;
    /** FFmpeg 帧录制器，负责视频编码与 MP4 封装 */
    private FFmpegFrameRecorder recorder;
    /** BufferedImage 与 FFmpeg Frame 之间的转换器 */
    private Java2DFrameConverter converter;
    /** 复用的 BGR 缓冲，保证颜色空间一致 */
    private volatile BufferedImage bgrImage;
    /** 采集工作线程，按帧率循环截屏并编码 */
    private Thread captureThread;

    /** 采集循环运行标志，false 时退出循环 */
    private volatile boolean running = false;
    /** 暂停标志，true 时采集线程跳过编码 */
    private volatile boolean paused = false;

    /**
     * 以给定配置开始录制。
     * <p>流程：校验状态 -> 创建 Robot -> 初始化 FFmpeg 编码器 -> 启动采集线程。
     *
     * @param config 录制配置，不可为 null
     * @throws IllegalArgumentException 当 config 为 null
     * @throws IllegalStateException 当前状态不允许 start，或处于 headless 环境，或创建 Robot 失败
     * @throws org.bytedeco.javacv.FrameRecorder.Exception FFmpeg 编码器启动失败
     */
    @Override
    public void start(RecorderConfig config) throws Exception {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        synchronized (lock) {
            if (state != RecorderState.IDLE && state != RecorderState.ERROR) {
                throw new IllegalStateException("当前状态 " + state + " 不允许 start，需先 stop");
            }
            if (GraphicsEnvironment.isHeadless()) {
                throw new IllegalStateException("当前环境为 headless，无法进行屏幕截取");
            }
            try {
                robot = new Robot();
            } catch (AWTException e) {
                state = RecorderState.ERROR;
                throw new IllegalStateException("无法创建 Robot: " + e.getMessage(), e);
            }
            this.currentConfig = config;
            Rectangle area = config.resolveCaptureArea();
            File out = config.getOutputFile();
            if (out == null) {
                out = RecorderConfig.defaultOutputFile();
            }
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            // 初始化 FFmpeg 录制器
            recorder = new FFmpegFrameRecorder(out, area.width, area.height);
            recorder.setFormat("mp4");
            // 优先 H.264；若 ffmpeg 构建未含 libx264，FFmpeg 会自动回退 mpeg4
            recorder.setVideoCodecName("libx264");
            recorder.setFrameRate(config.getFps());
            // 设置码率(ABR)，对 libx264/mpeg4 均生效，保证清晰度
            recorder.setVideoBitrate(config.getVideoBitrate());
            // 输出 yuv420p 保证播放器兼容；preset 加速编码(libx264 生效，回退 mpeg4 时忽略)
            recorder.setVideoOption("pix_fmt", "yuv420p");
            recorder.setVideoOption("preset", "veryfast");
            recorder.start();

            converter = new Java2DFrameConverter();
            // 复用的 BGR 缓冲：Robot 在 Windows 截图多为 INT_RGB，
            // 直接 convert 会与 FFmpeg 期望的 BGR 字节序不匹配导致红蓝颠倒偏色
            bgrImage = new BufferedImage(area.width, area.height, BufferedImage.TYPE_3BYTE_BGR);
            lastOutputFile = out;

            running = true;
            paused = false;
            state = RecorderState.RECORDING;

            // 启动采集线程
            captureThread = new Thread(this::captureLoop, "screen-recorder-capture");
            captureThread.setDaemon(true);
            captureThread.start();

            System.out.println("[Recorder] 开始录制 -> " + out.getAbsolutePath()
                    + " (" + area.width + "x" + area.height + "@" + config.getFps() + "fps)");
        }
    }

    /**
     * 采集循环：按帧率截屏并送入编码器。
     * <p>工作线程主体：循环读取 paused 标志，未暂停时截图 -> 转 BGR -> 编码 -> 帧率控制 sleep。
     * 异常或 running=false 时退出，并在 finally 中关闭编码器完成 MP4 封装。
     */
    private void captureLoop() {
        try {
            Rectangle area = currentConfig.resolveCaptureArea();
            long frameIntervalNanos = 1_000_000_000L / currentConfig.getFps();
            long nextFrameTime = System.nanoTime();

            while (running) {
                boolean localPaused = paused;
                if (!localPaused) {
                    BufferedImage img = robot.createScreenCapture(area);
                    // 先拷贝到 TYPE_3BYTE_BGR 缓冲，确保颜色通道顺序(BGR)与 FFmpeg 一致
                    Graphics2D g = bgrImage.createGraphics();
                    g.drawImage(img, 0, 0, null);
                    g.dispose();
                    Frame frame = converter.convert(bgrImage);
                    if (frame != null) {
                        synchronized (lock) {
                            if (recorder != null && running && !paused) {
                                recorder.record(frame);
                            }
                        }
                    }
                }
                // 帧率控制：计算下一帧目标时间并 sleep
                nextFrameTime += frameIntervalNanos;
                long sleepNanos = nextFrameTime - System.nanoTime();
                if (sleepNanos > 0) {
                    long sleepMillis = sleepNanos / 1_000_000;
                    int sleepNanosRem = (int) (sleepNanos % 1_000_000);
                    if (sleepMillis > 0 || sleepNanosRem > 0) {
                        try {
                            Thread.sleep(sleepMillis, sleepNanosRem);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } else {
                    // 已落后，重置基准避免追帧
                    nextFrameTime = System.nanoTime();
                }
            }
        } catch (Throwable t) {
            System.err.println("[Recorder] 采集线程异常: " + t.getMessage());
            t.printStackTrace();
            synchronized (lock) {
                state = RecorderState.ERROR;
            }
        } finally {
            closeRecorder();
        }
    }

    /**
     * 暂停录制。仅 RECORDING 状态可调用。
     *
     * @throws IllegalStateException 当前状态不是 RECORDING
     */
    @Override
    public void pause() {
        synchronized (lock) {
            if (state != RecorderState.RECORDING) {
                throw new IllegalStateException("当前状态 " + state + " 不允许 pause");
            }
            paused = true;
            state = RecorderState.PAUSED;
            System.out.println("[Recorder] 已暂停");
        }
    }

    /**
     * 恢复录制。仅 PAUSED 状态可调用。
     *
     * @throws IllegalStateException 当前状态不是 PAUSED
     */
    @Override
    public void resume() {
        synchronized (lock) {
            if (state != RecorderState.PAUSED) {
                throw new IllegalStateException("当前状态 " + state + " 不允许 resume");
            }
            paused = false;
            state = RecorderState.RECORDING;
            System.out.println("[Recorder] 已恢复");
        }
    }

    /**
     * 停止录制并完成 MP4 文件封装。RECORDING/PAUSED/ERROR 状态可调用，调用后回到 IDLE。
     * <p>流程：置 running=false -> join 采集线程（由其 finally 关闭编码器完成封装）。
     *
     * @throws Exception 此实现不主动抛出，仅声明以匹配接口契约
     */
    @Override
    public void stop() throws Exception {
        Thread t;
        synchronized (lock) {
            if (state != RecorderState.RECORDING && state != RecorderState.PAUSED
                    && state != RecorderState.ERROR) {
                return; // 已经停止
            }
            running = false;
            paused = false;
            t = captureThread;
            state = RecorderState.IDLE;
        }
        if (t != null) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("[Recorder] 已停止，输出文件: "
                + (lastOutputFile != null ? lastOutputFile.getAbsolutePath() : "无"));
    }

    /**
     * 关闭 FFmpeg 录制器，完成 MP4 封装。
     * <p>在采集线程的 finally 中调用，stop() 与 release() 完成 MP4 头尾封装与资源释放。
     */
    private void closeRecorder() {
        FFmpegFrameRecorder r;
        synchronized (lock) {
            r = recorder;
            recorder = null;
        }
        if (r != null) {
            try {
                r.stop();
                r.release();
            } catch (org.bytedeco.javacv.FrameRecorder.Exception e) {
                System.err.println("[Recorder] 关闭编码器失败: " + e.getMessage());
            }
        }
    }

    /**
     * 当前状态。
     *
     * @return 录制器当前状态，永不返回 null
     */
    @Override
    public RecorderState getState() {
        return state;
    }

    /**
     * 当前录制使用的配置（未开始则为 null）。
     *
     * @return 当前配置，未开始录制时返回 null
     */
    @Override
    public RecorderConfig getCurrentConfig() {
        return currentConfig;
    }

    /**
     * 最近一次输出文件路径（停止后可用，未录制过则为 null）。
     *
     * @return 最近一次输出文件，未录制过则返回 null
     */
    @Override
    public File getLastOutputFile() {
        return lastOutputFile;
    }
}
