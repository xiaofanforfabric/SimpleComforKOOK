package com.xiaofan.simplecomforkookMod;

import com.xiaofan.commen.CommenLogs;
import com.xiaofan.commen.KookVoiceApiClient;
import com.xiaofan.commen.KookVoiceKeepAliveService;
import com.xiaofan.commen.OpusMixingVoiceSink;
import com.xiaofan.commen.OpusRtpStreamer;
import com.xiaofan.commen.PcmMixingVoiceSink;
import com.xiaofan.commen.SimpleComWsClient;
import com.xiaofan.commen.SimpleComforKOOKConfigBootstrap;
import com.xiaofan.commen.VoiceSink;

import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 纯 Java 的核心逻辑封装，不依赖 Bukkit / Minecraft / Fabric / Forge。
 *
 * 生命周期对应：
 * - {@link #start()}  等价于 Bukkit 插件的 onEnable()
 * - {@link #stop()}   等价于 Bukkit 插件的 onDisable()
 *
 * 宿主只需要提供：
 * - 一个 {@link Logger}
 * - 插件 / 模块 jar 的路径（用于确定配置目录）
 */
public final class SimpleComforKOOKCore {
    private final Logger logger;
    private final Path pluginJarPath;

    private SimpleComWsClient wsClient;
    private VoiceSink voiceSink;
    private KookVoiceKeepAliveService keepAliveService;
    private String kookBotToken;
    private String channelId;
    private KookVoiceApiClient.JoinResult joinResult;

    public SimpleComforKOOKCore(Logger logger, Path pluginJarPath) {
        if (logger == null) {
            throw new IllegalArgumentException("logger cannot be null");
        }
        if (pluginJarPath == null) {
            throw new IllegalArgumentException("pluginJarPath cannot be null");
        }
        this.logger = logger;
        this.pluginJarPath = pluginJarPath;
    }

    /**
     * 启动核心逻辑：加载配置、加入 KOOK 语音频道、启动 WS 客户端与保活线程。
     */
    public synchronized void start() {
        // 初始化 / 校验配置
        Path configFile = SimpleComforKOOKConfigBootstrap.ensureDefaultConfig(pluginJarPath, logger);
        SimpleComforKOOKConfigBootstrap.validateRequiredFields(configFile);

        Map<String, String> cfg = SimpleComforKOOKConfigBootstrap.loadConfig(configFile);
        String voiceApiHost = SimpleComforKOOKConfigBootstrap.getRequiredValue(cfg, "voiceAPIHOST");
        String token = SimpleComforKOOKConfigBootstrap.getRequiredValue(cfg, "token");
        this.kookBotToken = SimpleComforKOOKConfigBootstrap.getRequiredValue(cfg, "KOOKBOTtoken");
        this.channelId = SimpleComforKOOKConfigBootstrap.getRequiredValue(cfg, "channel_id");

        // 加入 KOOK 语音频道
        this.joinResult = KookVoiceApiClient.joinVoiceChannel(kookBotToken, channelId, logger);
        if (joinResult != null) {
            keepAliveService = new KookVoiceKeepAliveService(kookBotToken, channelId, logger, 30000L);
            keepAliveService.start();
        } else {
            logger.severe("[KOOK] voice/join failed, streaming will be disabled.");
        }

        // 创建 WS 客户端，使用回调初始化 VoiceSink
        wsClient = new SimpleComWsClient(logger, null, this::initializeVoiceSink);
        wsClient.start(voiceApiHost, token);

        // 记录当前 jar 位置，便于排查问题
        CommenLogs.logCurrentJarLocation(logger, pluginJarPath);
    }

    /**
     * 停止核心逻辑：离开 KOOK 语音频道、停止保活 / WS / 音频流。
     */
    public synchronized void stop() {
        // 请求离开 KOOK 语音频道
        if (kookBotToken != null && channelId != null) {
            boolean ok = KookVoiceApiClient.leaveChannel(kookBotToken, channelId, logger);
            if (ok) {
                logger.info("[KOOK] voice/leave OK for channel " + channelId);
            } else {
                logger.warning("[KOOK] voice/leave FAILED for channel " + channelId);
            }
        }

        if (keepAliveService != null) {
            keepAliveService.stop();
            keepAliveService = null;
        }
        if (wsClient != null) {
            wsClient.stop();
            wsClient = null;
        }
        if (voiceSink != null) {
            voiceSink.close();
            voiceSink = null;
        }
    }

    /**
     * 根据 compressionEncoder 状态初始化合适的 VoiceSink。
     * 该方法由 {@link SimpleComWsClient} 在收到 serverstatus 时回调。
     */
    private void initializeVoiceSink(boolean compressionEnabled) {
        logger.info("[VoiceSink] Initializing with compressionEnabled=" + compressionEnabled);

        if (joinResult == null) {
            logger.severe("[VoiceSink] joinResult is null, cannot initialize.");
            return;
        }

        try {
            VoiceSink rtpSink = new OpusRtpStreamer(joinResult, logger);
            logger.info("[VoiceSink] OpusRtpStreamer created");

            if (compressionEnabled) {
                // 客户端发 Opus → 混音 Opus
                voiceSink = new OpusMixingVoiceSink(rtpSink, logger);
                logger.info("[VoiceSink] Using OpusMixingVoiceSink (compressionEncoder=true)");
            } else {
                // 客户端发 PCM → 混音 PCM → 编码 Opus
                voiceSink = new PcmMixingVoiceSink(rtpSink, logger);
                logger.info("[VoiceSink] Using PcmMixingVoiceSink (compressionEncoder=false)");
            }

            if (wsClient != null) {
                wsClient.setVoiceSink(voiceSink);
                logger.info("[VoiceSink] VoiceSink set to WS client");
            }
        } catch (Exception e) {
            logger.severe("[VoiceSink] Failed to initialize: " + e.getMessage());
            e.printStackTrace();
            voiceSink = null;
        }
    }
}

