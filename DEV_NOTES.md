# AutoTask 开发调试心得

> 给后续接手的人/模型看的交接笔记。重点记录“为什么这么改”和真机调试踩过的坑，避免重新摸索。

## 1. 工程是什么

AutoTask：用自然语言描述操作流程，文本模型解析成步骤，执行时优先用无障碍节点查找点击，找不到再用视觉模型识别截图坐标兜底。

核心链路：

```
MainActivity（入口/权限/一键执行）
  └─ TaskListManager（创建任务、AI 解析、步骤编辑、引导教学）
  └─ TaskExecutor（执行引擎：双策略、重试、页面就绪、模型切换、调试）
       ├─ AutoTaskAccessibilityService（节点查找、手势、截图 API、拉起 App）
       ├─ ModelClient（OpenAI 兼容 API：文本解析 / 视觉定位）
       └─ ScreenshotService（API < 30 的 MediaProjection 截图兜底）
  └─ FloatingWindowService（执行进度 / 教学 / 失败调试悬浮窗）
  └─ TaskRepository（SharedPreferences 存整份任务 JSON）
```

技术栈：Kotlin 1.9 / AGP 8.5 / Gradle 8.7 / JDK 17+，minSdk 26，targetSdk 34。

## 2. 构建与安装

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew.bat assembleDebug
adb -s <设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

- release 已开启 R8 + 资源压缩 + 仅保留中英文资源，产物约 2MB；debug 约 6.5MB。
- 混淆规则在 `app/proguard-rules.pro`，Gson 反射的 `com.autotask.config.**` / `com.autotask.model.**` 必须 keep。

## 3. 测试机与真机调试坑

当前测试机：一台 **Android 9 / API 28** 真机。不要用模拟器。

| 坑 | 说明 / 处理 |
|---|---|
| USB 调试反复掉线 | 换 USB 3.0 线/口后稳定；掉线时 `adb reconnect offline` |
| 首次安装弹窗 | MIUI 首次 `adb install` 需要在手机上点确认，之后正常 |
| 息屏 | `adb shell input keyevent KEYCODE_WAKEUP`，可临时 `settings put system screen_off_timeout 1800000` |
| `adb shell input text` 乱码 | MIUI 的搜狗/讯飞输入法会把注入文本当拼音组词（`glm-4.7-flash` → “改吗。7－”）。**不要用 input text 填 URL/模型名/Key**，用 App 设置页的预设菜单点选，Key 由人工输入 |
| uiautomator bounds 不准 | 部分 MaterialButton 报告 bounds 只有 9px，实际显示正常；以截图为准，点击用按钮文本中心 |
| 无障碍服务反复重连 | MIUI 会周期性 unbind/rebind，服务里 `serviceScope` 必须在 `onServiceConnected` 重建（已修） |
| 截图权限 | API 30+ 用 `AccessibilityService.takeScreenshot`；API < 30 必须走 MediaProjection：设置页/首次启动会引导授权，MIUI 弹“AutoTask 将开始截取…”点“立即开始” |

常用命令：

```bash
# 静默开无障碍（debug 包）
adb shell settings put secure enabled_accessibility_services com.autotask/com.autotask.service.AutoTaskAccessibilityService
adb shell settings put secure accessibility_enabled 1

# 日志
adb logcat -s AT-TaskExecutor:V AT-ModelClient:V AT-A11y:V AT-Screenshot:V AT-MainActivity:V AT-Settings:V
```

## 4. 模型选择（免费/低成本）

智谱开放平台目前（2026-10）明确免费的模型，OpenAI 兼容，国内直连：

| 用途 | 模型 | 说明 |
|---|---|---|
| 文本解析 | `glm-4.7-flash` | 200K 上下文，免费主力 |
| 文本备用 | `glm-4-flash-250414` | 128K，免费 |
| 视觉定位 | `glm-4v-flash` | 16K，轻量免费；真机实测稳定 |
| 视觉备用 | `glm-4.6v-flash` | 128K，视觉推理；实测容易 429，界面变化频繁时可重试 |

- API 地址（完整路径）：`https://open.bigmodel.cn/api/paas/v4/chat/completions`
- 设置页已内置“智谱 GLM（免费档）”预设，点 API 地址输入框右侧图标即可自动填地址和模型名。
- 其它可选：阿里 Qwen3.x-Flash（限时免费额度）、硅基流动免费模型、OpenRouter `:free`（需代理）、Gemini Flash（需代理）。
- **不要把 API Key 写进代码、文档或 git**。Key 通过 App 设置页填写，存在 SharedPreferences 里。
- 多模型自动切换：设置页可配置文本/视觉模型列表，失败 3 次自动切下一个。切换索引会写回配置，避免永远只切到同一个备用模型。

## 5. 本次优化改了什么

已提交：`c31c1ac 修复执行链路，补齐 Android 10 以下截屏能力`

- 调试模式接线：步骤失败弹悬浮窗，支持重试 / 修改描述后重试 / 手动完成 / 跳过 / 终止（原先回调从未赋值，失败直接终止）
- 模型轮换索引写回配置（原先永远只切到第二个模型）
- 视觉 API 异常单独计数（原先视觉失败记到文本模型上，会误切文本模型）
- 配置改动执行前热加载（原先要重启 App）
- 修复上滑变下滑（`up` 未识别）、步骤结果 `indexOf` 错位、截图/Bitmap/无障碍节点泄漏
- API < 30 接入 MediaProjection：授权流程 + 前台截图服务 + 常驻帧缓存 + 3 秒超时
- 视觉请求前缩放长边到 1440 并 JPEG 编码，坐标按比例映射回原图
- 无障碍服务重连重建协程作用域（修复“显示已开启却不动”）
- release 开启 R8 / 资源压缩

第二轮真机调试验证后追加：

- 设置页表单即当前模型：输入 Key 实时写回列表，点击列表切换时表单跟随（原先填了 Key 直接退出就丢）
- 修复策略单选保存 bug：RadioGroup 先选中新按钮再清除旧按钮，读 isChecked 会读到旧的“自动”，导致“仅视觉”永远存成 auto
- 节点匹配增强：支持“数字1按钮”→“1”、“加号”→“加”、“等号”→“等于”，可点击节点优先
- 视觉幻觉过滤：置信度 < 0.6 或 bbox 面积过小直接判未找到（实测模型会对不存在的元素编造坐标）
- 悬浮窗任务结束 5 秒自动收起（原先一直挡在屏幕上，连应用选择列表都点不到）
- 调试悬浮窗改为 85% 宽 + 2×2 按钮，避免文字被压没
- 429 重试改为 2s/4s/6s 并尊重 Retry-After，读超时 60 秒
- 视觉任务执行前若未授权，先请求截屏权限并中止本次执行，避免授权竞态
- 执行协程改挂 TaskExecutor 自己的作用域（不再跟随 Activity lifecycleScope），切应用/Activity 回收不中断任务
- 执行期间持有 SCREEN_DIM WakeLock，避免长任务息屏导致截图/手势失败
- 修复“自动切换模型”开关未绑定、maxRetries/stepTimeoutMs 配置不生效
- 解析 loading 对话框在页面退出时正确关闭；悬浮窗 Handler 在销毁时清理
- 抽取 SearchTerms 纯函数并补 6 个单元测试（`./gradlew testDebugUnitTest`）
- 权限引导改为链式（从系统设置返回后自动继续下一项），可选项只问一次
- 通知 ID 去重（无障碍 1001 / 悬浮窗 1002 / 截图 1003）
- MainActivity 固定竖屏，避免旋转重建导致执行中断
- 移除未使用的 activity-ktx / viewmodel-ktx 依赖
- 视觉连接测试改为真的发一张图（原先只测文本，测不出视觉能力）
- 任务 JSON 解析失败时备份原始数据（原先静默清空）

## 6. 执行引擎关键机制

- **双策略**：`auto` 先无障碍查找（系统 `findAccessibilityNodeInfosByText` + 去掉“按钮/图标”等后缀 + 滚动查找 + 可点击祖先），失败再截图走视觉。视觉坐标落在可点击节点上时用节点中心校准。
- **页面就绪**：节点树 root 有子节点 + 连续两张截图差异 < 0.5% 才继续，最多等 15 秒；截图不可用（未授权）时连续 3 次失败直接放行，避免每次冷启动白等。
- **开屏广告**：启动后先找“跳过/跳过广告”点掉（只点跳过，不点关闭），签到类 App 基本都有开屏广告。
- **签到验证**：描述里带“确认签到成功”时，AI 会追加 verify 步骤（视觉判断结果）。
- **截图前**会临时隐藏悬浮窗，避免遮挡和文字干扰识别。
- **视觉输入**：长边缩到 1440、JPEG 90，模型返回坐标按 `1/scale` 还原到原始截图坐标。
- **模型失败**：视觉 API 抛异常才计失败，业务性“没找到元素”不计入切换阈值，避免误切。
- **调试等待**：失败后最多挂起 120 秒等用户操作，超时按终止处理；任务结束后悬浮窗 5 秒自动收起。
- **视觉防幻觉**：模型找不到目标时可能编造坐标，解析时 confidence < 0.6 或 bbox 面积小于画面千分之一直接判无效。
- **视觉防误点**：目标是数字/符号按钮时，校准到的节点文本必须与目标一致，不一致直接判失败（GLM-4V 实测会把“数字1按钮”定位到相邻的“6”，宁可失败也不能点错）。
- **优先用 auto 策略**：普通原生界面无障碍又快又准；视觉兜底更适合 WebView/自绘界面，且免费视觉模型对密集数字键盘的定位精度有限。

## 7. 排查清单

- 任务失败先看 Logcat 的 `AT-TaskExecutor` 和失败步骤的 reason。
- 视觉失败时会把截图存到 App `files/screenshots/`（仅失败时保存，成功不落盘）。
- 截图返回错误码 3（API 30+）：Android 14 需要用户确认截图权限，打开 App 时已主动触发一次。
- 无障碍“已开启但不动”：检查服务是否刚重连、`instance` 是否为 null；已修 scope 重建。
- MIUI 后台杀服务：确认电池优化白名单 + 自启动权限，无障碍服务本身有前台通知保活。
- 任务 JSON 损坏：原数据备份在 SharedPreferences key `tasks_data_broken`。
- 点不动应用列表/对话框：先确认 AutoTask 执行悬浮窗是否还挂在屏幕上（现在任务结束后 5 秒自动收起，旧版本需点“停止”）。

## 7.5 已知能力边界

- **输入**：只支持无障碍树里有可编辑节点的输入框（原生 EditText）。自绘/WebView 输入框（无障碍里只有 TextView）只能视觉点到位置，无法注入文字。
- **权限弹窗**：系统定位/存储等权限弹窗会挡住页面导致后续步骤失败。涉及隐私的权限不会自动点允许，建议在跑任务前手动授权或拒绝一次。
- **自定义控件点击**：文字在子节点、点击事件在父容器时，`performAction` 可能返回成功但页面没反应；现在改为手势点击文字中心（`3a85a5c`）。仍有个别自定义控件需要人工确认。
- **点击结果校验**：点击成功但页面没变化时任务仍会标成功，没有页面变化/结果校验。需要严格确认时，在任务里加 verify 步骤。
- **登录/协议**：首次启动的隐私协议、短信验证码登录必须人工完成，自动化不代点。

## 8. 签到类 App 适配流程

适用于电力、车企、银行等有每日签到入口的 App。这类 App 通常带加固壳，环境检测较严，先在目标设备上确认能正常登录再开始自动化。

1. 手动打开 App，dump 页面节点，确认签到入口的准确文本（如“签到”“立即签到”）。
2. 用文本描述创建任务：“打开 App，点击我的，点击签到，等待 3 秒，确认签到成功”。
3. 执行后看悬浮窗/日志；签到入口在二级页面时增加点击步骤。
4. 把验证过的步骤模板记录到本节（不要记录账号、手机号等隐私信息）。

已做的通用支持：启动后自动跳过开屏广告、AI 支持 verify 步骤、SearchTerms 对“立即签到”等长文本做包含匹配。

## 9. 还可以继续做的

- 更彻底的前台 Service 执行（当前协程已脱离 Activity，但进程被系统杀掉仍会中断）
- 视觉定位加缓存/差异检测，减少重复截图请求
- 按屏幕区域裁剪视觉输入（只发下半屏/目标区域），进一步降 token
- 多任务执行失败后的跳过策略可配置（无人值守时不想每次等 120 秒调试窗）
- 继续补单元测试（坐标映射、JSON 解析、方向解析）
