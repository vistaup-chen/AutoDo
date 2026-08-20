# AutoTask - 基于自然语言屏幕识别的 Android 自动化工具

## 功能特性

### 核心功能
- **自然语言驱动**：用日常语言描述操作流程，AI 自动解析为执行步骤
- **屏幕视觉识别**：结合视觉模型识图，支持 WebView 和复杂界面
- **多应用自动化**：支持配置多个应用的自动操作流程
- **双策略执行**：无障碍节点优先 + 视觉识图兜底，确保稳定执行
- **应用选择器**：可视化选择应用，支持搜索和分组

### 任务管理
- **纯文本创建**：一句话描述即可创建任务
- **AI 解析**：自动将自然语言解析为结构化步骤
- **引导教学**：边操作边教学，无需录制视频
- **编辑功能**：随时修改任务配置
- **单条执行**：支持单独执行某个任务

### 执行与调试
- **执行悬浮窗**：实时显示执行进度，可开关
- **停止按钮**：随时中断执行
- **调试模式**：执行失败时可手动修复
- **失败提示**：清晰的失败原因说明

### 模型配置
- **模型可切换**：支持 OpenAI 兼容格式
- **预设平台**：通义千问、OpenAI、DeepSeek、智谱等
- **自定义地址**：支持自定义 API 地址
- **双模型配置**：文本模型和视觉模型独立配置

## 技术架构

```
┌─────────────────────────────────────────────────────────┐
│                      APK (Android)                       │
│                                                         │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ 文本模型     │  │ 识图模型      │  │ 配置管理       │  │
│  │ (可切换)     │  │ (可切换)      │  │ JSON 文件     │  │
│  └──────┬──────┘  └──────┬──────┘  └───────┬───────┘  │
│         │                │                  │          │
│         └────────┬───────┘                  │          │
│                  ▼                          │          │
│         ┌────────────────┐                  │          │
│         │   决策引擎       │ ◄─── 任务配置 ──┘          │
│         │ (Orchestrator) │                            │
│         └───────┬────────┘                            │
│                 │                                     │
│        ┌────────┴────────┐                            │
│        ▼                 ▼                            │
│  ┌─────────────┐  ┌──────────────┐                    │
│  │ 策略A: 无障碍 │  │ 策略B: 视觉   │                   │
│  │ Node 查找    │  │ 截图→识图→坐标│                   │
│  └─────────────┘  └──────────────┘                    │
│        │                 │                            │
│        └────────┬────────┘                            │
│                 ▼                                     │
│        ┌────────────────┐                             │
│        │ Accessibility  │                             │
│        │ Service        │                             │
│        └────────────────┘                             │
└─────────────────────────────────────────────────────────┘
```

## 项目结构

```
AutoTask/
├── app/
│   ├── build.gradle.kts              # 应用构建配置
│   ├── libs/                         # 本地依赖库
│   │   └── tinypinyin-2.0.3.jar      # 中文转拼音库
│   └── src/main/
│       ├── AndroidManifest.xml       # 权限声明
│       ├── java/com/autotask/
│       │   ├── MainActivity.kt       # 主界面
│       │   ├── config/               # 配置管理
│       │   │   ├── AppConfig.kt      # 数据模型、配置管理
│       │   │   └── TaskRepository.kt # 任务存储
│       │   ├── model/                # 模型调用
│       │   │   └── ModelClient.kt    # 模型客户端
│       │   ├── service/              # 核心服务
│       │   │   ├── AutoTaskAccessibilityService.kt  # 无障碍服务
│       │   │   ├── FloatingWindowService.kt          # 悬浮窗服务
│       │   │   ├── ScreenshotService.kt              # 截屏服务
│       │   │   └── TaskExecutor.kt                   # 执行引擎
│       │   └── ui/                   # 界面
│       │       ├── AppChooserDialog.kt # 应用选择对话框
│       │       ├── TaskAdapter.kt    # 任务列表适配器
│       │       ├── TaskListManager.kt # 任务管理
│       │       └── SettingsActivity.kt # 设置页
│       └── res/                      # 资源文件
│           ├── layout/               # 布局文件
│           ├── drawable/             # drawable 资源
│           ├── values/               # 字符串/颜色/主题
│           ├── xml/                  # 无障碍配置
│           └── mipmap-anydpi-v26/    # 启动图标
├── build.gradle.kts                  # 项目构建配置
├── settings.gradle.kts               # 项目设置
├── gradle.properties                 # Gradle 属性
├── gradlew                           # Gradle Wrapper (Unix)
└── gradlew.bat                       # Gradle Wrapper (Windows)
```

## 环境要求

- **Android Studio**：最新版（推荐 Hedgehog 或更高）
- **JDK**：17 或更高（Android Studio 内置 JBR 也可）
- **Android SDK**：API 34
- **Gradle**：8.7（通过 Wrapper 自动下载）

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/你的用户名/AutoTask.git
cd AutoTask
```

### 2. 打开项目

用 Android Studio 打开项目根目录，等待 Gradle 同步完成。

### 3. 编译运行

```bash
./gradlew assembleDebug
```

输出 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`

### 4. 安装到设备

- 通过 Android Studio 直接运行（连接设备或模拟器）
- 或手动安装 APK 文件

## 使用说明

### 首次使用

1. **开启无障碍服务**：应用会提示开启，点击跳转设置
2. **授权悬浮窗权限**：在系统设置中允许"显示在其他应用上层"
3. **配置模型**：进入设置页面，选择或填写 API 配置

### 配置模型

进入设置页面，可选择预设平台或自定义：

| 提供商 | 文本模型 | API 地址 | 免费 |
|--------|----------|----------|------|
| V1 (本地) | Qwen2.5-3B | http://127.0.0.1:8080/v1 | ✅ |
| 通义千问 | qwen-max | https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions | 有额度 |
| OpenAI | gpt-4o-mini | https://api.openai.com/v1/chat/completions | ❌ |
| DeepSeek | deepseek-chat | https://api.deepseek.com/v1/chat/completions | 有额度 |
| 智谱 GLM | glm-4 | https://open.bigmodel.cn/api/paas/v4/chat/completions | 有额度 |
| Moonshot | moonshot-v1-8k | https://api.moonshot.cn/v1/chat/completions | 有额度 |
| 字节豆包 | ep-xxxxxxxx | https://ark.cn-beijing.volces.com/api/v3/chat/completions | 有额度 |

### 创建任务

#### 方式一：文本描述 + AI 解析（推荐）

1. 点击"添加任务"
2. 选择要操作的应用（可视化选择器）
3. 填写任务名称
4. 输入自然语言描述，如："等3秒，点击我的，再点击设置"
5. 点击"创建"，AI 自动解析为执行步骤

#### 方式二：引导教学

1. 点击"添加任务"，选择"引导教学"标签
2. 选择应用，填写任务名称
3. 系统打开目标应用，弹出悬浮窗引导
4. 逐步描述每个页面的操作
5. 完成后自动保存配置

### 执行任务

- **单条执行**：点击任务卡片上的"执行"按钮
- **批量执行**：点击底部"一键执行"按钮执行所有启用任务
- **悬浮窗**：执行时显示进度，可随时停止

### 编辑任务

点击任务卡片上的"编辑"按钮，可修改：
- 任务名称
- 应用包名
- 执行描述（支持 AI 重新解析）

## 配置说明

### 操作策略

- **自动（推荐）**：无障碍优先，找不到时用视觉兜底
- **仅无障碍**：不调用视觉模型，速度快但 WebView 不兼容
- **仅视觉**：每次都截图识别，覆盖所有场景但较慢

### 执行参数

- **操作延迟**：步骤之间的等待时间（毫秒）
- **步骤超时**：单步骤最大执行时间（毫秒）
- **最大重试次数**：失败时的重试次数
- **启动后等待**：应用启动后的等待时间（毫秒）

## 权限说明

| 权限 | 用途 |
|------|------|
| 无障碍服务 | 识别界面元素并执行操作 |
| 悬浮窗 | 显示执行进度 |
| 截屏 | 视觉识别界面内容 |
| 网络 | 调用 AI 模型 API |
| 通知 | 显示服务运行状态 |

## 开发说明

### 关键类说明

- `ModelClient`：模型调用客户端，支持 OpenAI 兼容格式
- `TaskExecutor`：任务执行引擎，双策略执行
- `AppChooserDialog`：应用选择对话框，支持分组和搜索
- `FloatingWindowService`：悬浮窗服务，显示执行进度

### 添加新的预设平台

在 `SettingsActivity.kt` 中的 `textPresets` 或 `visionPresets` 列表添加：

```kotlin
ApiPreset("平台名称", "API地址", "文本模型名", "视觉模型名", 是否需要Key)
```

### 添加新的操作策略

1. 在 `StepAction` 枚举中添加新类型
2. 在 `TaskExecutor.executeStep()` 中添加处理逻辑
3. 在 `getStepDescription()` 中添加描述文本

## 常见问题

### Q: 悬浮窗不显示？
A: 请检查是否开启了"显示在其他应用上层"权限。

### Q: AI 解析失败？
A: 请检查 API 地址和 Key 是否正确，网络是否通畅。

### Q: 执行失败？
A: 查看悬浮窗或 Toast 提示的失败原因，常见原因：
- 界面元素已变化
- 页面未加载完成
- 权限不足

### Q: 如何调试？
A: 查看 Android Studio Logcat，过滤 `TaskExecutor` 或 `SettingsActivity` 标签。

## 许可证

MIT License
