package com.airecorder.core;

/**
 * 录制器状态机。
 * 合法流转：
 *   IDLE      -> RECORDING  (start)
 *   RECORDING -> PAUSED     (pause)
 *   PAUSED    -> RECORDING  (resume)
 *   RECORDING -> IDLE       (stop)
 *   PAUSED    -> IDLE       (stop)
 *   *         -> ERROR      (异常)
 */
public enum RecorderState {
    /** 空闲，未录制 */
    IDLE,
    /** 录制中 */
    RECORDING,
    /** 暂停 */
    PAUSED,
    /** 错误终态 */
    ERROR
}
