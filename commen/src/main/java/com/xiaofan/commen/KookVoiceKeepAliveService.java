package com.xiaofan.commen;

import java.util.logging.Logger;

/**
 * KOOK 语音 keep-alive 服务（纯 Java 线程实现，与 Bukkit 无关）。
 * 周期性调用 KookVoiceApiClient.keepAlive，防止长时间无语音时被服务器回收端口。
 */
public final class KookVoiceKeepAliveService {
    private final String botToken;
    private final String channelId;
    private final Logger logger;
    private final long intervalMillis;

    private volatile boolean running;
    private Thread thread;

    /**
     * @param botToken      KOOK 机器人 token（"Bot xxx" 中的 xxx）
     * @param channelId     需要保持活跃的语音频道 ID
     * @param logger        日志记录器（可为空）
     * @param intervalMillis 调用间隔（毫秒），推荐 30000（30 秒）
     */
    public KookVoiceKeepAliveService(String botToken, String channelId, Logger logger, long intervalMillis) {
        this.botToken = botToken;
        this.channelId = channelId;
        this.logger = logger != null ? logger : Logger.getLogger("KookVoiceKeepAliveService");
        this.intervalMillis = intervalMillis > 0 ? intervalMillis : 30000L;
    }

    /** 启动保活线程（幂等，多次调用只会启动一次）。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop();
            }
        }, "KookVoiceKeepAliveService");
        thread.setDaemon(true);
        thread.start();
    }

    /** 停止保活线程（幂等）。 */
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void runLoop() {
        while (running) {
            try {
                boolean ok = KookVoiceApiClient.keepAlive(botToken, channelId, logger);
                if (ok) {
                    logger.info("[KOOK] voice/keep-alive OK for channel " + channelId);
                } else {
                    logger.warning("[KOOK] voice/keep-alive FAILED for channel " + channelId);
                }
            } catch (ConfigurationException e) {
                // 配置错误，直接停止服务，避免无限重试
                logger.warning("[KOOK] keep-alive configuration error: " + e.getMessage());
                return;
            } catch (Exception e) {
                logger.warning("[KOOK] keep-alive unexpected error: " + e.getMessage());
            }

            long sleepMs = intervalMillis;
            long deadline = System.currentTimeMillis() + sleepMs;
            while (running && sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ignored) {
                    // 如果被打断则重新计算剩余时间，或因 stop() 退出
                }
                sleepMs = deadline - System.currentTimeMillis();
            }
        }
    }
}

