# SimpleComforKOOK - Minecraft 语音桥接到 KOOK
# SimpleComforKOOK - Bridge Minecraft Voice to KOOK

将你的 Minecraft 服务器语音实时转发到 KOOK（开黑啦）语音频道！

Bridge your Minecraft server voice to KOOK voice channels in real-time!

## 🎙️ 功能特性 | Features

- **实时语音转发 | Real-time Voice Forwarding**：将 Minecraft 玩家的语音自动转发到 KOOK 语音频道 | Automatically forward Minecraft players' voice to KOOK voice channels
- **多人混音 | Multi-user Mixing**：支持多个玩家同时说话，自动混音处理 | Support multiple players speaking simultaneously with automatic mixing
- **跨平台支持 | Cross-platform Support**：提供 Fabric、Forge 和 Bukkit/Spigot 三个版本 | Available for Fabric, Forge, and Bukkit/Spigot
- **低延迟 | Low Latency**：优化的音频处理流程，确保语音传输流畅 | Optimized audio processing for smooth voice transmission
- **自动保活 | Auto Keep-alive**：自动维持 KOOK 语音连接，无需手动重连 | Automatically maintain KOOK voice connection

## 📋 使用前准备 | Prerequisites

### 1. 安装 SimpleCom 客户端 | Install SimpleCom Client

玩家需要在本地安装 [SimpleCom](https://github.com/xiaofanforfabric/SimpleCom-core) 语音客户端，用于采集和传输语音。

Players need to install the [SimpleCom](https://github.com/xiaofanforfabric/SimpleCom-core) voice client locally for voice capture and transmission.

### 2. 配置 KOOK Bot | Configure KOOK Bot

服务器管理员需要 | Server administrators need to:
1. 在 KOOK 开发者平台创建一个 Bot | Create a Bot on KOOK Developer Platform
2. 获取 Bot Token | Get the Bot Token
3. 将 Bot 添加到你的 KOOK 服务器 | Add the Bot to your KOOK server
4. 获取目标语音频道的 ID | Get the target voice channel ID

### 3. 安装 SimpleCom 服务器 | Install SimpleCom Server

服务器需要运行 SimpleCom 服务器端，用于接收玩家语音数据。

The server needs to run SimpleCom server to receive player voice data.

## 🚀 快速开始 | Quick Start

### Fabric/Forge 用户 | Fabric/Forge Users

1. 下载对应版本的 Mod 文件 | Download the corresponding Mod file
2. 将 Mod 放入 `mods` 文件夹 | Place the Mod in the `mods` folder
3. 启动服务器，会自动生成配置文件 | Start the server, config file will be generated automatically
4. 编辑配置文件填入必要信息 | Edit the config file with necessary information
5. 重启服务器 | Restart the server

### Bukkit/Spigot 用户 | Bukkit/Spigot Users

1. 下载 Bukkit 插件 | Download the Bukkit plugin
2. 将插件放入 `plugins` 文件夹 | Place the plugin in the `plugins` folder
3. 启动服务器，会自动生成配置文件 | Start the server, config file will be generated automatically
4. 编辑 `plugins/SimpleComforKOOKConfig/config.yml` | Edit `plugins/SimpleComforKOOKConfig/config.yml`
5. 重启服务器 | Restart the server

## ⚙️ 配置说明 | Configuration

配置文件示例 | Configuration example:

```yaml
voiceAPIHOST: 127.0.0.1:3003          # SimpleCom 服务器地址 | SimpleCom server address
token: 123456                          # SimpleCom 认证令牌 | SimpleCom authentication token
KOOKBOTtoken: your_kook_bot_token     # KOOK Bot 令牌 | KOOK Bot token
channel_id: your_channel_id            # KOOK 语音频道 ID | KOOK voice channel ID
```

### 获取 KOOK 频道 ID | Get KOOK Channel ID

1. 在 KOOK 中右键点击语音频道 | Right-click the voice channel in KOOK
2. 选择"复制频道 ID" | Select "Copy Channel ID"
3. 粘贴到配置文件中 | Paste it into the config file

## 🎮 玩家使用指南 | Player Guide

1. **安装 SimpleCom 客户端 | Install SimpleCom Client**：从官方仓库下载并安装 | Download and install from the official repository
2. **连接到服务器 | Connect to Server**：使用 SimpleCom 客户端连接到 Minecraft 服务器 | Use SimpleCom client to connect to the Minecraft server
3. **开始说话 | Start Speaking**：按住 PTT（Push-to-Talk）键说话，你的声音会自动转发到 KOOK | Hold the PTT (Push-to-Talk) key to speak, your voice will be automatically forwarded to KOOK

## ⚠️ 注意事项 | Important Notes

- **单向通信 | One-way Communication**：目前仅支持 Minecraft → KOOK 的语音转发，KOOK 频道的语音不会被转发回 Minecraft | Currently only supports Minecraft → KOOK voice forwarding, KOOK channel voice will not be forwarded back to Minecraft
- **网络要求 | Network Requirements**：需要稳定的网络连接以确保语音质量 | Stable network connection required for voice quality
- **性能影响 | Performance Impact**：语音处理会占用一定的服务器资源，建议在配置较好的服务器上使用 | Voice processing consumes server resources, recommended for well-configured servers

## 🔧 技术细节 | Technical Details

- **音频格式 | Audio Format**：48kHz, Mono, 20ms 帧 | 48kHz, Mono, 20ms frames
- **编解码器 | Codec**：Opus
- **传输协议 | Transport Protocol**：RTP over UDP
- **混音算法 | Mixing Algorithm**：实时 PCM 混音，支持多人同时说话 | Real-time PCM mixing, supports multiple speakers

## 📦 版本支持 | Version Support

### Fabric
- Minecraft 1.20.1
- Fabric Loader 0.14.x+

### Forge
- Minecraft 1.20.1
- Forge 47.x.x+

### Bukkit/Spigot
- Minecraft 1.16.5+
- Paper/Spigot

## 🐛 问题反馈 | Bug Reports

遇到问题？请在 [GitHub Issues](https://github.com/xiaofanforfabric/SimpleComforKOOK/issues) 提交反馈。

Found a bug? Please submit feedback on [GitHub Issues](https://github.com/xiaofanforfabric/SimpleComforKOOK/issues).

## 📄 开源协议 | License

本项目采用 GNU General Public License v3.0 开源协议。

This project is licensed under GNU General Public License v3.0.

## 🔗 相关链接 | Related Links

- **GitHub 仓库 | Repository**：https://github.com/xiaofanforfabric/SimpleComforKOOK
- **SimpleCom 客户端 | Client**：https://github.com/xiaofanforfabric/SimpleCom-core
- **KOOK 开发者平台 | Developer Platform**：https://developer.kookapp.cn/

---

**作者 | Author**：xiaofan  
**支持 | Support**：如果这个 Mod 对你有帮助，请给个 Star ⭐ | If this Mod helps you, please give it a Star ⭐
