package com.xiaofan.simpleComforKOOKBukkit;

import com.xiaofan.commen.CommenLogs;
import com.xiaofan.commen.KookVoiceApiClient;
import com.xiaofan.commen.OpusRtpStreamer;
import com.xiaofan.commen.PcmMixingVoiceSink;
import com.xiaofan.commen.SimpleComforKOOKConfigBootstrap;
import com.xiaofan.commen.SimpleComWsClient;
import com.xiaofan.commen.VoiceSink;
import com.xiaofan.commen.KookVoiceKeepAliveService;
import com.xiaofan.commen.OpusMixingVoiceSink;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Map;


public final class SimpleComforKOOKBukkit extends JavaPlugin {
    private SimpleComWsClient wsClient;
    private VoiceSink voiceSink;
    private KookVoiceKeepAliveService keepAliveService;
    private String kookBotToken;
    private String channelId;
    private KookVoiceApiClient.JoinResult joinResult;

    @Override
    public void onEnable() {
        Path configFile = SimpleComforKOOKConfigBootstrap.ensureDefaultConfig(getFile().toPath(), getLogger());
        SimpleComforKOOKConfigBootstrap.validateRequiredFields(configFile);

        Map<String, String> cfg = SimpleComforKOOKConfigBootstrap.loadConfig(configFile);
        String voiceApiHost = SimpleComforKOOKConfigBootstrap.getRequiredValue(cfg, "voiceAPIHOST");
        String token = SimpleComforKOOKConfigBootstrap.getRequiredValue(cfg, "token");
        this.kookBotToken = SimpleComforKOOKConfigBootstrap.getRequiredValue(cfg, "KOOKBOTtoken");
        this.channelId = SimpleComforKOOKConfigBootstrap.getRequiredValue(cfg, "channel_id");

        this.joinResult = KookVoiceApiClient.joinVoiceChannel(kookBotToken, channelId, getLogger());
        if (joinResult != null) {
            // 启动 KOOK 语音保活（纯 Java 线程，与 Bukkit 解耦）
            keepAliveService = new KookVoiceKeepAliveService(kookBotToken, channelId, getLogger(), 30000L);
            keepAliveService.start();
        } else {
            getLogger().severe("[KOOK] voice/join failed, streaming will be disabled.");
        }

        // 创建 WS 客户端，直接传入初始化器
        wsClient = new SimpleComWsClient(getLogger(), null, this::initializeVoiceSink);
        wsClient.start(voiceApiHost, token);

        CommenLogs.logCurrentJarLocation(getLogger(), getFile().toPath());
    }

    /** 根据 compressionEncoder 状态初始化合适的 VoiceSink */
    private void initializeVoiceSink(boolean compressionEnabled) {
        getLogger().info("[VoiceSink] Initializing with compressionEnabled=" + compressionEnabled);
        
        if (joinResult == null) {
            getLogger().severe("[VoiceSink] joinResult is null, cannot initialize.");
            return;
        }

        try {
            VoiceSink rtpSink = new OpusRtpStreamer(joinResult, getLogger());
            getLogger().info("[VoiceSink] OpusRtpStreamer created");
            
            if (compressionEnabled) {
                // 客户端发 Opus → 混音 Opus
                voiceSink = new OpusMixingVoiceSink(rtpSink, getLogger());
                getLogger().info("[VoiceSink] Using OpusMixingVoiceSink (compressionEncoder=true)");
            } else {
                // 客户端发 PCM → 混音 PCM → 编码 Opus
                voiceSink = new PcmMixingVoiceSink(rtpSink, getLogger());
                getLogger().info("[VoiceSink] Using PcmMixingVoiceSink (compressionEncoder=false)");
            }
            
            // 更新 WS 客户端的 voiceSink
            if (wsClient != null) {
                wsClient.setVoiceSink(voiceSink);
                getLogger().info("[VoiceSink] VoiceSink set to WS client");
            }
        } catch (Exception e) {
            getLogger().severe("[VoiceSink] Failed to initialize: " + e.getMessage());
            e.printStackTrace();
            voiceSink = null;
        }
    }

    @Override
    public void onDisable() {
        // 先请求离开 KOOK 语音频道
        if (kookBotToken != null && channelId != null) {
            boolean ok = KookVoiceApiClient.leaveChannel(kookBotToken, channelId, getLogger());
            if (ok) {
                getLogger().info("[KOOK] voice/leave OK for channel " + channelId);
            } else {
                getLogger().warning("[KOOK] voice/leave FAILED for channel " + channelId);
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
}
