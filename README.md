# SimpleComforKOOK 项目概览

## 项目简介

**SimpleComforKOOK** 是一个跨平台的 Minecraft 语音通讯集成系统，支持将 Minecraft 服务器的玩家语音与 KOOK（开黑啦）语音频道进行实时单向通讯。项目采用模块化架构，包含 Bukkit 插件、Fabric Mod、Forge Mod 三个平台的实现，共享一个纯 Java 的核心库。

## 项目结构

```
SimpleComforKOOK/
├── commen/                          # 纯 Java 核心库（所有平台共享）
│   ├── src/main/java/com/xiaofan/commen/
│   │   ├── SimpleComWsClient.java           # WebSocket 客户端（连接 SimpleCom 服务器）
│   │   ├── SimpleComforKOOKConfigBootstrap.java  # 配置加载
│   │   ├── KookVoiceApiClient.java          # KOOK 语音 API 客户端
│   │   ├── OpusRtpStreamer.java             # RTP 流发送器（Opus 编码）
│   │   ├── OpusMixingVoiceSink.java         # Opus 混音器（解码→混音→编码）
│   │   ├── PcmMixingVoiceSink.java          # PCM 混音器（混音→编码）
│   │   ├── KookVoiceKeepAliveService.java   # KOOK 语音保活服务
│   │   ├── VoiceSink.java                   # 语音输出接口
│   │   └── ...其他工具类
│   └── build.gradle
│
├── SimpleComforKOOK-bukkit/         # Bukkit 插件（Paper/Spigot 服务器）
│   ├── src/main/java/com/xiaofan/simpleComforKOOKBukkit/
│   │   └── SimpleComforKOOKBukkit.java      # 插件主类
│   ├── src/main/resources/
│   │   └── plugin.yml                       # 插件配置
│   └── build.gradle                         # 使用 Shadow Plugin 打包依赖
│
├── SimpleComforKOOK-Mod/            # Minecraft Mod（Fabric + Forge）
│   ├── common/                      # 共享代码（Architectury）
│   │   ├── src/main/java/com/xiaofan/simplecomforkookMod/
│   │   │   ├── SimplecomforkookMod.java     # Mod 初始化
│   │   │   └── SimpleComforKOOKCore.java    # 核心逻辑
│   │   └── build.gradle             # 包含 commen 和 concentus 依赖
│   ├── fabric/                      # Fabric 特定实现
│   ├── forge/                       # Forge 特定实现
│   ├── settings.gradle              # 包含 commen 的 includeBuild
│   └── build.gradle
│
├── settings.gradle                  # 根项目配置
├── build.gradle                     # 根项目构建配置
└── PROJECT_OVERVIEW.md              # 本文件
```

## 核心功能模块

### 1. 纯 Java 核心库 (`commen`)

**职责**：提供与 SimpleCom 服务器通讯、KOOK API 交互、语音处理的核心功能。

#### 关键类

| 类名 | 功能 |
|------|------|
| `SimpleComWsClient` | WebSocket 客户端，连接 SimpleCom 服务器接收语音数据 |
| `KookVoiceApiClient` | KOOK 语音 API 客户端，处理加入/离开频道、保活 |
| `OpusRtpStreamer` | RTP 流发送器，将 Opus 编码的语音通过 RTP 发送到 KOOK |
| `OpusMixingVoiceSink` | Opus 混音器，解码多用户 Opus 帧→混音→重新编码 |
| `PcmMixingVoiceSink` | PCM 混音器，混合多用户 PCM 数据→编码为 Opus |
| `KookVoiceKeepAliveService` | 定期发送保活请求，维持 KOOK 语音连接 |

#### 工作流程

```
SimpleCom 服务器
    ↓ (WebSocket)
SimpleComWsClient
    ↓ (语音数据)
PcmMixingVoiceSink 或 OpusMixingVoiceSink
    ↓ (混音后的 Opus)
OpusRtpStreamer
    ↓ (RTP 包)
KOOK 语音服务器
```

### 2. Bukkit 插件 (`SimpleComforKOOK-bukkit`)

**平台**：Paper/Spigot 1.16.5+

**职责**：在 Bukkit 服务器上运行，连接 SimpleCom 和 KOOK。

#### 初始化流程

1. 加载配置文件（`config.yml`）
2. 调用 KOOK API 加入语音频道
3. 启动 KOOK 保活服务
4. 创建 WebSocket 客户端连接 SimpleCom
5. 收到 `serverstatus` 消息后，根据 `compressionEncoder` 状态选择合适的混音器
6. 接收语音数据并转发到 KOOK

#### 依赖打包

使用 **Shadow Plugin** 将 `commen` 和 `concentus` 库打包进 jar，避免运行时缺少依赖。

### 3. Minecraft Mod (`SimpleComforKOOK-Mod`)

**平台**：Fabric 1.20.1 + Forge 1.20.1

**架构**：使用 **Architectury** 框架实现跨平台代码共享

#### 模块结构

- **common**：平台无关的核心代码
- **fabric**：Fabric 特定的加载器和事件处理
- **forge**：Forge 特定的加载器和事件处理

#### 依赖管理

- `common/build.gradle` 通过 `includeBuild` 引用根项目的 `commen` 模块
- 在 jar 任务中包含 `commen` 和 `concentus` 的所有类文件
- 最终产物（Fabric jar 和 Forge jar）包含完整的依赖

## 语音处理流程

### 场景 1：SimpleCom 发送 Opus 编码的语音（`compressionEncoder=true`）

```
SimpleCom 客户端
    ↓ (Opus 帧)
SimpleComWsClient.handleBinary()
    ↓ (提取单个 Opus 帧)
OpusMixingVoiceSink.writeOpusFrame()
    ↓ (解码为 PCM)
混音线程 (每 20ms 一次)
    ↓ (混合多用户 PCM)
OpusEncoder.encode()
    ↓ (编码为 Opus)
OpusRtpStreamer.writeOpusFrame()
    ↓ (RTP 包)
KOOK 语音服务器
```

### 场景 2：SimpleCom 发送裸 PCM 数据（`compressionEncoder=false`）

```
SimpleCom 客户端
    ↓ (PCM 数据，9600 字节 = 5 个 20ms 帧)
SimpleComWsClient.handleBinary()
    ↓ (直接传递，不拆分)
PcmMixingVoiceSink.writeOpusFrame()
    ↓ (转换为 short[] 数组)
混音线程 (每 20ms 一次)
    ↓ (混合多用户 PCM)
OpusEncoder.encode()
    ↓ (编码为 Opus)
OpusRtpStreamer.writeOpusFrame()
    ↓ (RTP 包)
KOOK 语音服务器
```

**注意**：这是单向通信流程。Minecraft 玩家的语音被接收、混音后转发到 KOOK 语音频道，但 KOOK 频道的语音不会被转发回 Minecraft。

## 关键设计决策

### 1. 动态 VoiceSink 选择

**问题**：SimpleCom 可能以 Opus 或 PCM 格式发送语音，需要在运行时决定处理方式。

**解决方案**：
- `SimpleComWsClient` 在收到 `serverstatus` 消息时获取 `compressionEncoder` 状态
- 通过 `VoiceSinkInitializer` 回调通知 Bukkit 插件
- 插件根据状态创建 `OpusMixingVoiceSink` 或 `PcmMixingVoiceSink`
- 动态设置到 `SimpleComWsClient` 中

### 2. 混音线程设计

**问题**：多用户语音需要同步混音，但 WebSocket 接收是异步的。

**解决方案**：
- 每个用户维护一个 PCM 帧队列
- 后台混音线程每 20ms 检查一次队列
- 从每个用户队列取出一帧，逐样本相加并限幅
- 编码后通过 RTP 发送

### 3. 跨平台代码共享

**问题**：Bukkit、Fabric、Forge 三个平台需要共享核心逻辑。

**解决方案**：
- 将所有平台无关的代码放在 `commen` 模块
- Bukkit 直接依赖 `commen`
- Mod 通过 Architectury 框架共享代码
- 在构建时将 `commen` 和 `concentus` 打包进最终 jar

### 4. 依赖打包

**问题**：Concentus 库在运行时必须可用，但不能依赖外部 jar。

**解决方案**：
- Bukkit：使用 Shadow Plugin 打包所有依赖
- Mod：在 jar 任务中使用 `from(zipTree(...))` 包含依赖的类文件

## 配置文件

### Bukkit 配置 (`plugins/SimpleComforKOOKConfig/config.yml`)

```yaml
voiceAPIHOST: 127.0.0.1:3003          # SimpleCom 服务器地址
token: 123456                          # SimpleCom 认证令牌
KOOKBOTtoken: xxx                      # KOOK Bot 令牌
channel_id: 2751784425559253           # KOOK 语音频道 ID
```

## 构建和部署

### 构建 Bukkit 插件

```bash
gradlew :SimpleComforKOOK-bukkit:build
# 输出：SimpleComforKOOK-bukkit-1.0.jar
```

### 构建 Fabric Mod

```bash
gradlew :SimpleComforKOOK-Mod:fabric:build
# 输出：simplecomforkook-mod-fabric-1.0.jar
```

### 构建 Forge Mod

```bash
gradlew :SimpleComforKOOK-Mod:forge:build
# 输出：simplecomforkook-mod-forge-1.0.jar
```

### 构建所有

```bash
gradlew build
```

## 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Java | 8/17 | 编程语言 |
| Gradle | 8.13 | 构建工具 |
| Bukkit API | 1.16.5 | Bukkit 插件开发 |
| Architectury | 3.4-SNAPSHOT | Mod 跨平台框架 |
| Fabric Loader | 0.14.x | Fabric 加载器 |
| Forge | 1.20.1 | Forge 加载器 |
| Concentus | 1.0.2 | Opus 编解码库 |
| Shadow Plugin | 8.1.1 | 依赖打包 |

## 已知限制

1. **Java 版本**：Bukkit 使用 Java 8，Mod 使用 Java 17
2. **Minecraft 版本**：Bukkit 支持 1.16.5+，Mod 支持 1.20.1
3. **SimpleCom 依赖**：必须有可用的 SimpleCom 服务器
4. **KOOK 依赖**：必须有有效的 KOOK Bot 令牌和频道 ID
5. **单向通信**：仅支持 Minecraft → KOOK 的语音转发

## 许可证

本项目采用 **GNU General Public License v3.0** 许可证。详见 `LICENSE` 文件。

## 联系方式

- 作者：xiaofan
- GitHub：https://github.com/xiaofanforfabric/SimpleComforKOOK
