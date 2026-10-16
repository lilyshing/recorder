package com.airecorder.core;

import com.airecorder.config.VideoCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.javacpp.PointerPointer;

import java.util.HashMap;
import java.util.Map;

import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_close;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

/**
 * FFmpeg 编码器运行期能力探测与选择。
 * <p>仅用 avcodec_find_encoder_by_name 做静态查找并不可靠：
 * <ul>
 *   <li>编码器名未编入原生库时返回 null；此时 FFmpegFrameRecorder 会静默回退到其它编码
 *       （实测缺失 libx264/libx265 时会回退成 mpeg4，产出"看似 H.264/H.265 实则 MPEG-4"的文件）</li>
 *   <li>hevc_mf/hevc_nvenc/hevc_qsv 等系统/硬件编码器虽被编入，但运行期可能因缺少 HEVC 视频扩展、
 *       无对应 GPU/驱动而在 avcodec_open2 阶段失败</li>
 * </ul>
 * 因此本类对候选编码器执行真实的 avcodec_open2 试打开，选出当前环境真正可用者。
 * 探测结果按编码器名缓存，仅首次调用产生开销。
 */
public final class CodecSupport {

    /** 探测用分辨率（偶数边长，对 MF/NVENC/QSV 及软件编码器均安全） */
    private static final int PROBE_WIDTH = 320;
    private static final int PROBE_HEIGHT = 240;
    /** 探测用帧率与码率，仅需让编码器成功 open，参数取值无实际意义 */
    private static final int PROBE_FPS = 30;
    private static final int PROBE_BITRATE = 1_000_000;

    /** 编码器试打开结果缓存：编码器名 -> 是否可在当前环境成功打开 */
    private static final Map<String, Boolean> OPEN_RESULT_CACHE = new HashMap<String, Boolean>();
    /** 每种视频格式最终选中的编码器名缓存：格式名 -> 编码器名（null 表示无可用编码器） */
    private static final Map<VideoCodec, String> SELECTED_CACHE = new HashMap<VideoCodec, String>();

    private CodecSupport() {
    }

    /**
     * 查询某视频编码格式在当前环境是否可用（候选链中至少一个编码器能成功打开）。
     *
     * @param codec 视频编码枚举，null 返回 false
     * @return true 存在可用编码器
     */
    public static boolean isSupported(VideoCodec codec) {
        return codec != null && resolveEncoder(codec) != null;
    }

    /**
     * 按候选优先级选出当前环境真正可用的 FFmpeg 编码器名。
     * <p>结果会缓存；首次调用会对候选编码器依次试打开（静态缺失的跳过，硬件/系统编码器打开失败的跳过）。
     *
     * @param codec 视频编码枚举
     * @return 可用编码器名（如 libopenh264/libx265/hevc_mf）；全部不可用时返回 null
     */
    public static String resolveEncoder(VideoCodec codec) {
        if (codec == null) {
            return null;
        }
        synchronized (SELECTED_CACHE) {
            if (SELECTED_CACHE.containsKey(codec)) {
                return SELECTED_CACHE.get(codec);
            }
        }
        String selected = null;
        for (String name : codec.getCandidates()) {
            if (canOpenEncoder(name)) {
                selected = name;
                break;
            }
        }
        synchronized (SELECTED_CACHE) {
            SELECTED_CACHE.put(codec, selected);
        }
        return selected;
    }

    /**
     * 静态查询指定编码器是否编入当前 FFmpeg 原生库（不做试打开）。
     *
     * @param ffmpegEncoderName FFmpeg 编码器名
     * @return true 编码器存在（不代表运行期一定能成功打开）
     */
    public static boolean isEncoderAvailable(String ffmpegEncoderName) {
        if (ffmpegEncoderName == null || ffmpegEncoderName.isEmpty()) {
            return false;
        }
        try {
            AVCodec codec = avcodec_find_encoder_by_name(ffmpegEncoderName);
            return codec != null && !codec.isNull();
        } catch (Throwable t) {
            System.err.println("[Codec] 查找编码器 " + ffmpegEncoderName
                    + " 失败: " + t.getMessage());
            return false;
        }
    }

    /**
     * 试打开编码器：先静态查找，再用最小合法参数 avcodec_open2，验证系统/硬件类编码器真实可用。
     *
     * @param name FFmpeg 编码器名
     * @return true 可成功打开（随后立即关闭释放）；任何环节失败返回 false
     */
    private static boolean canOpenEncoder(String name) {
        synchronized (OPEN_RESULT_CACHE) {
            if (OPEN_RESULT_CACHE.containsKey(name)) {
                return OPEN_RESULT_CACHE.get(name);
            }
        }
        boolean ok = false;
        AVCodecContext ctx = null;
        PointerPointer<AVCodecContext> freeHolder = null;
        try {
            AVCodec codec = avcodec_find_encoder_by_name(name);
            if (codec == null || codec.isNull()) {
                // 未编入原生库，直接判定不可用（避免后续无意义尝试）
                return cacheAndReturn(name, false);
            }
            ctx = avcodec_alloc_context3(codec);
            if (ctx == null || ctx.isNull()) {
                return cacheAndReturn(name, false);
            }
            ctx.width(PROBE_WIDTH);
            ctx.height(PROBE_HEIGHT);
            ctx.pix_fmt(AV_PIX_FMT_YUV420P);
            ctx.time_base(new AVRational().num(1).den(PROBE_FPS));
            ctx.framerate(new AVRational().num(PROBE_FPS).den(1));
            ctx.bit_rate(PROBE_BITRATE);
            // options 传 null：不附加任何私有参数，仅验证编码器本身能否初始化
            int ret = avcodec_open2(ctx, codec, (PointerPointer<?>) null);
            if (ret >= 0) {
                ok = true;
                avcodec_close(ctx);
            } else {
                // 常见：-1 通用失败(如无 N 卡)、-22 参数/设备不支持(qsv)、-40 功能未实现(如缺 HEVC 扩展)
                System.out.println("[Codec] 编码器 " + name + " 试打开失败，ffmpeg 返回码: " + ret);
            }
        } catch (Throwable t) {
            // 原生库加载失败、MF 子系统异常等一律视为不可用，由上层提示用户改用 H.264
            System.err.println("[Codec] 编码器 " + name + " 试打开异常: " + t.getMessage());
            ok = false;
        } finally {
            if (ctx != null && !ctx.isNull()) {
                try {
                    // avcodec_free_context 需要 AVCodecContext* 的二级指针：
                    // 分配容量为 1 的指针数组并放入 ctx，调用后由 FFmpeg 将其置空并释放
                    freeHolder = new PointerPointer<AVCodecContext>(1L);
                    freeHolder.put(0, ctx);
                    avcodec_free_context(freeHolder);
                } catch (Throwable ignore) {
                    // 释放失败不影响判定结果
                }
            }
            if (freeHolder != null) {
                freeHolder.deallocate();
            }
        }
        return cacheAndReturn(name, ok);
    }

    /** 写入试打开结果缓存并返回 */
    private static boolean cacheAndReturn(String name, boolean ok) {
        synchronized (OPEN_RESULT_CACHE) {
            OPEN_RESULT_CACHE.put(name, ok);
        }
        return ok;
    }
}
