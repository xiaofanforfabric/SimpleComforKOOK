package com.xiaofan.commen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class SimpleComforKOOKConfigBootstrap {
    private SimpleComforKOOKConfigBootstrap() {}

    public static Path ensureDefaultConfig(Path pluginJarPath, Logger logger) {
        if (pluginJarPath == null) {
            if (logger != null) {
                logger.severe("Config init failed: pluginJarPath is null.");
            }
            return null;
        }

        Path parentDir = pluginJarPath.toAbsolutePath().normalize().getParent();
        if (parentDir == null) {
            if (logger != null) {
                logger.severe("Config init failed: cannot determine plugin jar parent directory.");
            }
            return null;
        }

        Path configDir = parentDir.resolve("SimpleComforKOOKConfig");
        Path configFile = configDir.resolve("config.yml");

        try {
            Files.createDirectories(configDir);
            if (Files.notExists(configFile)) {
                Files.write(
                        configFile,
                        defaultConfigContent().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE_NEW
                );
                if (logger != null) {
                    logger.info("Created default config: " + configFile);
                }
            } else if (logger != null) {
                logger.info("Config exists: " + configFile);
            }
        } catch (IOException e) {
            if (logger != null) {
                logger.severe("Config init failed: " + e.getMessage());
            }
        }

        return configFile;
    }

    public static Map<String, String> loadConfig(Path configFile) {
        return loadKeyValueConfig(configFile);
    }

    public static void validateRequiredFields(Path configFile) {
        Map<String, String> kv = loadKeyValueConfig(configFile);

        String token = normalizeValue(kv.get("token"));
        String kookBotToken = normalizeValue(kv.get("KOOKBOTtoken"));
        String channelId = normalizeValue(kv.get("channel_id"));

        if (isBlank(token) || isBlank(kookBotToken) || isBlank(channelId)) {
            throw new ConfigurationException(
                    "ConfigurationException!配置错误！你是不是忘记填token或频道号了\n"
                            + "ConfigurationException! Configuration error! Did you forget to fill in the token or channel id?"
            );
        }
    }

    public static String getRequiredValue(Map<String, String> kv, String key) {
        String value = normalizeValue(kv.get(key));
        if (isBlank(value)) {
            throw new ConfigurationException("ConfigurationException! Missing config: " + key);
        }
        return value;
    }

    public static String getOptionalValue(Map<String, String> kv, String key) {
        return normalizeValue(kv.get(key));
    }

    private static String defaultConfigContent() {
        return ""
                + "voiceAPIHOST = 127.0.0.1:25500\n"
                + "token = ''\n"
                + "#SimpleCom的语音API地址和token，务必认真填写，一个错误整个插件都会停止\n"
                + "#The SimpleCom voice API address and token. Fill in carefully; any mistake will stop the entire plugin.\n"
                + "KOOKBOTtoken = ''\n"
                + "#KOOK机器人的token，我们使用websocket进行连接推流，请确保你机器人是websocket模式\n"
                + "#The KOOK bot token. We connect and stream via WebSocket; make sure your bot is in WebSocket mode.\n"
                + "channel_id = \n"
                + "#KOOK机器人要加入的语音频道号，只能加入一个\n"
                + "#The voice channel ID that the KOOK bot should join. Only one channel is supported.\n";
    }

    private static Map<String, String> loadKeyValueConfig(Path configFile) {
        if (configFile == null) {
            throw new ConfigurationException("ConfigurationException! config.yml path is null.");
        }
        if (Files.notExists(configFile)) {
            throw new ConfigurationException("ConfigurationException! config.yml not found: " + configFile);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(configFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigurationException("ConfigurationException! Failed to read config.yml: " + e.getMessage(), e);
        }

        Map<String, String> kv = new HashMap<String, String>();
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                kv.put(key, value);
            }
        }
        return kv;
    }

    private static String normalizeValue(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return "";
        }
        if ("''".equals(v) || "\"\"".equals(v)) {
            return "";
        }
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                v = v.substring(1, v.length() - 1).trim();
            }
        }
        return v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

