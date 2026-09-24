package com.airecorder.core;

import com.airecorder.config.RecorderConfig;

/**
 * 录制服务接口（Java 编程式 API）。
 * 供本进程内的其他模块直接调用，例如 GUI、CLI、HTTP API 层。
 *
 * 线程安全：实现需保证可在任意线程调用，内部通过状态机保证流转合法。
 */
public interface RecorderService {

    /**
     * 以给定配置开始录制。若当前非 IDLE 则抛异常。
     *
     * @param config 录制配置（帧率、区域、输出路径、码率等），不可为 null
     * @throws Exception 录制启动失败（如 headless 环境、状态非法、编码器初始化失败）
     */
    void start(RecorderConfig config) throws Exception;

    /**
     * 暂停录制。仅 RECORDING 状态可调用。
     *
     * @throws IllegalStateException 当前状态不是 RECORDING
     */
    void pause();

    /**
     * 恢复录制。仅 PAUSED 状态可调用。
     *
     * @throws IllegalStateException 当前状态不是 PAUSED
     */
    void resume();

    /**
     * 停止录制并完成 MP4 文件封装。RECORDING/PAUSED 状态可调用，调用后回到 IDLE。
     *
     * @throws Exception 关闭编码器或封装失败
     */
    void stop() throws Exception;

    /**
     * 当前状态。
     *
     * @return 录制器当前状态，永不返回 null
     */
    RecorderState getState();

    /**
     * 当前录制使用的配置（未开始则为 null）。
     *
     * @return 当前配置，未开始录制时返回 null
     */
    RecorderConfig getCurrentConfig();

    /**
     * 最近一次输出文件路径（停止后可用，未录制过则为 null）。
     *
     * @return 最近一次输出文件，未录制过则返回 null
     */
    java.io.File getLastOutputFile();
}
