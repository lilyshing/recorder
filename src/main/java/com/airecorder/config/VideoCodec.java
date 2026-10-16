package com.airecorder.config;

/**
 * 视频编码格式枚举：H.264 / H.265。
 * <p>每个枚举值持有一组按优先级排列的 FFmpeg 候选编码器名，运行期由
 * {@link com.airecorder.core.CodecSupport#resolveEncoder(VideoCodec)} 实测后选出第一个真正可用者，
 * 避免编码器缺失时 FFmpegFrameRecorder 静默回退到 mpeg4 等非预期编码。
 * <ul>
 *   <li>{@link #H264}：兼容性最好、速度快，默认格式；纯软件优先 libx264/libopenh264</li>
 *   <li>{@link #H265}：同画质体积约小 30%~50%，但编码较慢、老设备兼容性差，
 *       且依赖运行环境具备 libx265 或 HEVC 硬件/系统编码器，使用前必须做能力探测</li>
 * </ul>
 */
public enum VideoCodec {

    /**
     * H.264/AVC，候选编码器按优先级排列：
     * libx264(GPL 构建常用) -> libopenh264(纯软件、随包分发、离线/Win7 可用)
     * -> h264_mf/h264_nvenc/h264_qsv(系统或硬件编码器)
     */
    H264("H.264", new String[]{"libx264", "libopenh264", "h264_mf", "h264_nvenc", "h264_qsv"}),
    /**
     * H.265/HEVC，候选编码器按优先级排列：
     * libx265(纯软件，需 FFmpeg 内置) -> hevc_nvenc/hevc_qsv(显卡硬编码) -> hevc_mf(Windows MediaFoundation，
     * 需安装 HEVC 视频扩展，Win7 不可用)
     */
    H265("H.265", new String[]{"libx265", "hevc_nvenc", "hevc_qsv", "hevc_mf"});

    /** 面向用户的展示名称，如 H.264/H.265 */
    private final String displayName;
    /** FFmpeg 候选编码器名（按优先级），运行期取第一个实测可打开者 */
    private final String[] candidates;

    VideoCodec(String displayName, String[] candidates) {
        this.displayName = displayName;
        this.candidates = candidates;
    }

    /** 获取面向用户的展示名称 */
    public String getDisplayName() {
        return displayName;
    }

    /** 获取 FFmpeg 候选编码器名列表（按优先级），供运行期探测选择 */
    public String[] getCandidates() {
        return candidates.clone();
    }

    /**
     * 将用户输入解析为编码格式枚举，容错常见写法（大小写不敏感，忽略 ./-/_ 分隔符）。
     * <p>支持：h264 / h.264 / avc / libx264 -&gt; H264；
     * h265 / h.265 / hevc / libx265 -&gt; H265。
     *
     * @param s 用户输入字符串，null 或空返回默认值 H264
     * @return 对应枚举值
     * @throws IllegalArgumentException 无法识别的编码名称
     */
    public static VideoCodec parse(String s) {
        if (s == null || s.trim().isEmpty()) {
            return H264;
        }
        // 归一化：小写并去掉 . - _ 分隔符，h.265 -> h265，lib-x265 -> libx265
        String t = s.trim().toLowerCase()
                .replace(".", "").replace("-", "").replace("_", "");
        if (t.contains("265") || t.contains("hevc")) {
            return H265;
        }
        if (t.contains("264") || t.contains("avc")) {
            return H264;
        }
        throw new IllegalArgumentException("不支持的视频编码: " + s + "，可选值: h264 / h265");
    }
}
