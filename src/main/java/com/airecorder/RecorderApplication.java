package com.airecorder;

import com.airecorder.cli.CliLauncher;
import com.airecorder.gui.RecorderGui;

/**
 * 屏幕录像应用主入口。
 *
 * 默认（无参数）启动 GUI 界面模式。
 * 子命令分发到 CLI 无界面模式 / HTTP API 模式。
 *
 *   java -jar recorder.jar                 -> GUI
 *   java -jar recorder.jar gui             -> GUI
 *   java -jar recorder.jar start [options] -> 后台录制
 *   java -jar recorder.jar stop [--port N]  -> 停止
 *   java -jar recorder.jar status [--port N]
 *   java -jar recorder.jar api [--port N]   -> 仅 HTTP API
 */
public class RecorderApplication {

    /**
     * 应用程序入口方法。
     * <p>根据命令行参数决定启动模式：
     * <ul>
     *   <li>无参数或参数为 "gui"：启动 Swing 图形界面</li>
     *   <li>其他参数：交给 {@link CliLauncher} 处理（start/stop/status/api 等子命令）</li>
     * </ul>
     *
     * @param args 命令行参数数组，可为空
     */
    public static void main(String[] args) {
        // 无参数或 gui -> 启动 GUI
        if (args.length == 0 || "gui".equalsIgnoreCase(args[0])) {
            new RecorderGui().show();
            return;
        }
        // 其他命令交给 CLI 处理
        int code = CliLauncher.run(args);
        if (code != 0) {
            // CLI 执行失败时以非零退出码结束 JVM，便于脚本判断
            System.exit(code);
        }
    }
}
