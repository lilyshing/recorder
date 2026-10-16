package com.airecorder.gui;

import com.airecorder.config.RecorderConfig;
import com.airecorder.config.VideoCodec;
import com.airecorder.core.CodecSupport;
import com.airecorder.core.RecorderService;
import com.airecorder.core.RecorderState;
import com.airecorder.core.ScreenRecorder;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.File;

/**
 * Swing 录制界面（有界面模式）。
 * 提供：开始/暂停/停止 按钮，帧率/分辨率/输出路径 配置，实时状态显示。
 * 内部直接持有 RecorderService 实例，进程内调用（无需 HTTP）。
 */
public class RecorderGui {

    /** 进程内直接持有的录制服务实例 */
    private final RecorderService service = new ScreenRecorder();

    /** 主窗口 */
    private JFrame frame;
    /** 帧率输入框 */
    private JTextField fpsField;
    /** 分辨率输入框（留空表示使用屏幕分辨率） */
    private JTextField resolutionField;
    /** 输出文件路径输入框 */
    private JTextField outputField;
    /** 视频码率输入框 */
    private JTextField bitrateField;
    /** 视频编码下拉框：H.264 / H.265 */
    private JComboBox<String> codecCombo;
    /** 自动分段开关(默认不勾选) */
    private JCheckBox segmentCheckbox;
    /** 分段阈值输入框(如 20M) */
    private JTextField segmentSizeField;
    /** 开始按钮 */
    private JButton startBtn;
    /** 暂停/恢复按钮 */
    private JButton pauseBtn;
    /** 停止按钮 */
    private JButton stopBtn;
    /** 状态显示标签 */
    private JLabel statusLabel;

    /**
     * 显示主窗口。
     * <p>在 EDT 上构建界面、设为可见并刷新按钮状态。线程安全：通过 SwingUtilities 调度。
     */
    public void show() {
        SwingUtilities.invokeLater(() -> {
            buildFrame();
            frame.setVisible(true);
            updateButtons();
        });
    }

    /** 构建主窗口及所有面板：配置面板、按钮面板、状态面板 */
    private void buildFrame() {
        frame = new JFrame("屏幕录像");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(520, 340);
        frame.setLocationRelativeTo(null);

        // 配置面板：帧率/编码/码率/分辨率/输出/分段 共 6 行
        JPanel configPanel = new JPanel(new GridLayout(6, 2, 8, 8));
        configPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        configPanel.add(new JLabel("帧率 (30fps):"));
        fpsField = new JTextField(String.valueOf(RecorderConfig.DEFAULT_FPS));
        configPanel.add(fpsField);

        configPanel.add(new JLabel("视频编码:"));
        // 下拉框顺序固定：下标 0=H.264，下标 1=H.265；H.265 不支持时在文案中注明
        codecCombo = new JComboBox<String>(new String[]{
                VideoCodec.H264.getDisplayName(),
                VideoCodec.H265.getDisplayName()
        });
        codecCombo.setSelectedIndex(0);
        codecCombo.addActionListener(e -> onCodecChanged());
        configPanel.add(codecCombo);
        // 后台预热编码能力探测（首次需加载 FFmpeg 原生库），完成后刷新 H.265 选项文案
        Thread warmup = new Thread(() -> {
            boolean h265Ok = CodecSupport.isSupported(VideoCodec.H265);
            SwingUtilities.invokeLater(() -> {
                if (codecCombo != null) {
                    // JDK8 无 setItemAt：先在位置 1 插入带标注的新文案，再删除被挤到位置 2 的旧项
                    String label = VideoCodec.H265.getDisplayName() + (h265Ok ? "" : " (本机不支持)");
                    javax.swing.MutableComboBoxModel<String> model =
                            (javax.swing.MutableComboBoxModel<String>) codecCombo.getModel();
                    model.insertElementAt(label, 1);
                    model.removeElementAt(2);
                }
            });
        }, "codec-probe");
        warmup.setDaemon(true);
        warmup.start();

        configPanel.add(new JLabel("码率 (如8M):"));
        bitrateField = new JTextField("2M");
        configPanel.add(bitrateField);

        configPanel.add(new JLabel("分辨率 (WxH，留空=屏幕):"));
        resolutionField = new JTextField("");
        configPanel.add(resolutionField);

        configPanel.add(new JLabel("输出文件:"));
        JPanel outPanel = new JPanel(new BorderLayout(4, 0));
        outputField = new JTextField(RecorderConfig.defaultOutputFile().getAbsolutePath());
        JButton browseBtn = new JButton("...");
        browseBtn.addActionListener(e -> browseOutput());
        outPanel.add(outputField, BorderLayout.CENTER);
        outPanel.add(browseBtn, BorderLayout.EAST);
        configPanel.add(outPanel);

        configPanel.add(new JLabel("自动分段 (按大小):"));
        JPanel segPanel = new JPanel(new BorderLayout(4, 0));
        // checkbox 默认不勾选，分段阈值输入框默认 20M
        segmentCheckbox = new JCheckBox("启用");
        segmentCheckbox.setSelected(false);
        segmentSizeField = new JTextField("20M");
        segPanel.add(segmentCheckbox, BorderLayout.WEST);
        segPanel.add(segmentSizeField, BorderLayout.CENTER);
        configPanel.add(segPanel);



        // 按钮面板
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        startBtn = new JButton("开始");
        pauseBtn = new JButton("暂停");
        stopBtn = new JButton("停止");
        startBtn.addActionListener(e -> doStart());
        pauseBtn.addActionListener(e -> doPause());
        stopBtn.addActionListener(e -> doStop());
        btnPanel.add(startBtn);
        btnPanel.add(pauseBtn);
        btnPanel.add(stopBtn);

        // 状态面板
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusLabel = new JLabel("状态: " + RecorderState.IDLE.name());
        statusPanel.add(statusLabel);

        frame.getContentPane().setLayout(new BorderLayout(8, 8));
        frame.getContentPane().add(configPanel, BorderLayout.CENTER);
        frame.getContentPane().add(btnPanel, BorderLayout.SOUTH);
        frame.getContentPane().add(statusPanel, BorderLayout.NORTH);
    }

    /** 打开文件保存对话框，选择输出 MP4 文件路径并回填到输入框 */
    private void browseOutput() {
        JFileChooser fc = new JFileChooser(outputField.getText());
        fc.setSelectedFile(new File(outputField.getText()));
        fc.setDialogTitle("选择输出 MP4 文件");
        if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".mp4")) {
                f = new File(f.getParentFile(), f.getName() + ".mp4");
            }
            outputField.setText(f.getAbsolutePath());
        }
    }

    /**
     * 编码下拉框切换回调：用户选择 H.265 时做运行期能力探测，
     * 若当前 FFmpeg 环境不含 libx265，弹窗提示并自动回退到 H.264。
     */
    private void onCodecChanged() {
        // 下标 1 固定对应 H.265
        if (codecCombo.getSelectedIndex() != 1) {
            return;
        }
        if (CodecSupport.isSupported(VideoCodec.H265)) {
            return;
        }
        JOptionPane.showMessageDialog(frame,
                "当前运行环境没有可用的 H.265(HEVC) 编码器。\n\n"
                        + "已尝试: " + String.join("、", VideoCodec.H265.getCandidates()) + "\n"
                        + "可能原因：FFmpeg 未内置 libx265、无 NVIDIA/Intel 硬件编码，\n"
                        + "或系统未安装 HEVC 视频扩展（Win7 不提供该组件）。\n\n"
                        + "请继续使用 H.264，或在支持 H.265 的环境中运行。",
                "H.265 不可用", JOptionPane.WARNING_MESSAGE);
        // 回退到 H.264（setSelectedIndex 会再次触发本回调，下标 0 直接返回，无递归风险）
        codecCombo.setSelectedIndex(0);
    }

    /** 从表单输入构造录制配置：解析帧率/编码/分辨率/输出路径/码率，失败时抛出 IllegalArgumentException */
    private RecorderConfig buildConfigFromForm() {
        RecorderConfig config = new RecorderConfig();
        try {
            String fps = fpsField.getText().trim();
            if (!fps.isEmpty()) {
                config.setFps(Integer.parseInt(fps));
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("帧率必须为整数");
        }
        // 下拉框下标：0=H.264，1=H.265（文案可能带"本机不支持"后缀，故只用下标映射）
        config.setVideoCodec(codecCombo.getSelectedIndex() == 1
                ? VideoCodec.H265 : VideoCodec.H264);
        String res = resolutionField.getText().trim();
        if (!res.isEmpty()) {
            int[] wh = RecorderConfig.parseResolution(res);
            if (wh != null) {
                config.setResolution(wh[0], wh[1]);
            }
        }
        String out = outputField.getText().trim();
        if (!out.isEmpty()) {
            config.setOutputFile(new File(out));
        }
        String br = bitrateField.getText().trim();
        if (!br.isEmpty()) {
            config.setVideoBitrate(RecorderConfig.parseBitrate(br));
        }
        // 自动分段：checkbox 开关 + 大小输入框
        config.setSegmentEnabled(segmentCheckbox.isSelected());
        String segSize = segmentSizeField.getText().trim();
        if (!segSize.isEmpty()) {
            config.setSegmentSizeBytes(RecorderConfig.parseSize(segSize));
        }
        return config;
    }

    /** 开始按钮回调：从表单构造配置并启动录制，失败弹出错误对话框 */
    private void doStart() {
        try {
            RecorderConfig config = buildConfigFromForm();
            service.start(config);
            updateButtons();
        } catch (Throwable t) {
            JOptionPane.showMessageDialog(frame, "开始失败: " + t.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 暂停/恢复按钮回调：根据当前状态在 PAUSED 与 RECORDING 间切换，失败弹出错误对话框 */
    private void doPause() {
        try {
            if (service.getState() == RecorderState.PAUSED) {
                service.resume();
            } else {
                service.pause();
            }
            updateButtons();
        } catch (Throwable t) {
            JOptionPane.showMessageDialog(frame, "操作失败: " + t.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 停止按钮回调：停止录制并在状态标签回显输出文件路径，失败弹出错误对话框 */
    private void doStop() {
        try {
            service.stop();
            updateButtons();
            File out = service.getLastOutputFile();
            if (out != null) {
                statusLabel.setText("状态: " + service.getState() + "  输出: " + out.getAbsolutePath());
            }
        } catch (Throwable t) {
            JOptionPane.showMessageDialog(frame, "停止失败: " + t.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 根据当前录制状态刷新各按钮的可用性与文本，并更新状态标签 */
    private void updateButtons() {
        RecorderState s = service.getState();
        startBtn.setEnabled(s == RecorderState.IDLE || s == RecorderState.ERROR);
        pauseBtn.setEnabled(s == RecorderState.RECORDING || s == RecorderState.PAUSED);
        pauseBtn.setText(s == RecorderState.PAUSED ? "恢复" : "暂停");
        stopBtn.setEnabled(s == RecorderState.RECORDING || s == RecorderState.PAUSED);
        statusLabel.setText("状态: " + s.name());
    }
}
