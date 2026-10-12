# DSHA 大众化版本 — 方案与实施计划

> 版本：2026-10-11
> 基线：`D:\DSHA-lite`（独立轻量 App，`com.dsh.assistant`，12.39 MB，已真机验证）
> 原则：**复用已在真机跑通的引擎，只补产品化外壳**——不重写、不引入 rootfs。

---

## 一、现状盘点（阶段 A 结论）

### 1.1 工程状态

| 项 | 值 |
|---|---|
| 源码仓库 | `D:\DSHA`（git，分支工作区有未提交改动）；`D:\DSHA-lite` **独立轻量副本，无 git** |
| 构建方式 | `gradlew :app:assembleDebug`（DSHA-lite）／`build-sign-install.ps1`（原 DSHA） |
| minSdk / targetSdk | 30 / 37（DSHA-lite） |
| 构建环境 | JDK 17.0.20.1、Android SDK（platforms;android-37.0、build-tools 36.0.0）、Gradle 9.3.1、AGP 9.1.1 |
| 模拟器 | **无**（无 emulator / 无系统镜像 / 无 AVD）→ 正在装到 `E:\android-sdk-emu` |
| 真机 | 华为 UYQ9K23528900004 / EMUI 12 / Android 11 / 720×1600 |

### 1.2 已实现（**可直接复用，不重写**）

| 能力 | 实现 | 说明 |
|---|---|---|
| 无障碍节点读屏 | `DshaAccessibilityService.uiDump()` / `uiDumpUnderPanel()` | 输出"序号+文本+可点击+区域"的清单 |
| **按钮定位（节点树）** | `uiTapText(text)`（第 650 行） | 按文本 / contentDescription 在节点树里找并点击 |
| 按坐标点击 | `uiTap(x,y)` | 手势派发 |
| 滑动 / 输入 / 按键 | `uiSwipe` / `uiInput` / `uiKey` | — |
| 截屏 | `uiScreenshot()` | Android 11+ `takeScreenshot` |
| **AI 驱动定位决策** | `AssistantTaskRunner` | 把读屏结果喂模型 → 模型输出 `{"act":"tap","text":"发送"}` → 执行 + 校验 |
| 定位失败重试 | `AssistantTaskRunner` 候选点机制 | 宽容器取端点、点完校验、假成功识别 |
| 权限闸门 | `AssistantPermit` + `AssistantPermitActivity` | mic / flash / notify / battery / airplane，弹卡片引导 |
| 发送前确认 | `AssistantGuard` + `ScreenSessionGrant` | 敏感操作需授权 |
| 语音唤醒 | `AssistantWakeService` | VAD + 在线 ASR，冷启动自愈 |
| 悬浮球 / 面板 | `DshaPebbleView` / `AssistantPanel` | 常驻入口 |

### 1.3 缺失（本次要补）

| 缺口 | 用户要求 | 影响 |
|---|---|---|
| **统一权限管理页** | 四章："应用内增加清晰、统一的权限管理页面"，每项独立状态+按钮 | 普通用户不知道要开什么 |
| **可配置定位规则** | 三章："通过可扩展的配置机制管理常用应用和目标按钮，不要硬编码坐标" | 目前只能靠模型每次现读 |
| **首次使用引导** | 五章：9 步首次启动流程 | 普通用户装完不会用 |
| 兼容性适配层 | 六章：API 级别 / 屏幕 / 厂商 / 语言差异 | 只在华为 EMUI 12 验过 |
| 测试与报告 | 七/八章：模拟器 + 自动化测试 + 测试报告 | 无 |

---

## 二、最小可行版本（MVP）范围

### 2.1 做什么

| 编号 | 交付物 | 说明 |
|---|---|---|
| **M1** | `PermissionHubActivity` 统一权限管理页 | 逐项：状态 / 用途 / 去授权按钮 / 不支持时的说明 |
| **M2** | `ActionTargetStore` + `ActionTargetLocator` | 可扩展的"常用应用+目标按钮"配置（JSON），四级定位策略 |
| **M3** | `OnboardingActivity` 首次使用引导 | 9 步流程，含自检与定位测试 |
| **M4** | `Compat` 兼容层 | API 级别判断、厂商跳转容错、屏幕尺寸/密度适配 |
| **M5** | 测试 + 报告 | 模拟器 + 真机，自动化测试，测试报告（含 APK 哈希） |

### 2.2 不做什么（明确边界）

- ❌ 不做 rootfs / proot / Node / 终端（那是原 DSHA 的重能力，与"大众化"相悖）
- ❌ 不改原 DSHA（`D:\DSHA`）的生产逻辑，只在 DSHA-lite 上做
- ❌ 不绕过任何系统授权流程、不诱导授权、不静默提权
- ❌ 不把个人 API Key 写进源码 / APK / 测试脚本 / 公开仓库

---

## 三、核心设计

### 3.1 按钮定位：四级策略（用户第三章要求）

```
① 无障碍节点定位（首选，最可靠）
     文本 / contentDescription / viewIdResourceName / 可点击 / 层级
     → ActionTargetLocator.findByNode()
     
② 预配置定位规则（已验证过的签名，可复用）
     存"控件特征"而非"屏幕坐标"
     → ActionTargetStore 里的 rule（见 3.2）
     
③ AI 辅助识别（节点树不可靠时）
     截图 → 视觉模型 → 候选位置
     → 已有 AssistantTaskRunner 的 chatVision 通道
     
④ 用户辅助校准（兜底）
     明确告知失败原因 + 重试 / 校准 / 取消
     → 绝不盲点、绝不用未验证的固定坐标
```

**关键约束（用户明确要求）**：
- 坐标只在"当前诊断画像"内有效，**跨设备/分辨率/版本/语言一律作废**
- 规则里存的是**特征**（文本、描述、层级路径、相对位置），不是绝对坐标
- 置信度不足 → 触发重识别或用户校准

### 3.2 配置结构（可扩展，非硬编码）

`/sdcard/dsh/action-targets.json`：

```json
{
  "schema": 1,
  "targets": [
    {
      "id": "telegram.send",
      "appLabel": "Telegram",
      "packages": ["org.telegram.messenger.web", "org.telegram.messenger"],
      "name": "发送消息",
      "risk": "send",
      "rules": [
        { "by": "contentDescription", "value": "发送", "exact": true },
        { "by": "viewId", "value": "send_button" },
        { "by": "text", "value": "发送", "exact": true }
      ],
      "confirm": true,
      "verifiedOn": [{ "model": "SP100", "android": 11, "appVer": "11.x" }]
    }
  ]
}
```

**要点**：
- `rules` 是**有序候选**，逐个尝试，全失败才升级到 AI/校准
- `risk` 决定是否需要确认（`send` / `pay` / `delete` → `confirm: true`）
- `verifiedOn` 记录验证过的机型/系统/应用版本；**不匹配时提示可能失效**，不静默沿用
- 用户可在 App 内添加自己的应用与目标（M2 的 UI 部分）

### 3.3 权限管理页（用户第四章要求）

| 权限 | 用途 | 授权方式 | 不支持时 |
|---|---|---|---|
| 无障碍服务 | 读屏与点击（核心） | 跳 `ACTION_ACCESSIBILITY_SETTINGS` | 提示无此能力，助手不可用 |
| 悬浮窗 | 悬浮球 / 面板 | `ACTION_MANAGE_OVERLAY_PERMISSION` | 降级为通知入口 |
| 通知 | 唤醒常驻通知、失败提示 | `requestPermissions`（Android 13+） | 静默降级 |
| 麦克风 | 语音唤醒 / 语音输入 | `requestPermissions` | 提示语音不可用，文字仍可用 |
| 通知使用权 | 读通知（可选功能） | 跳 `ACTION_NOTIFICATION_LISTENER_SETTINGS` | 该功能不可用 |
| 电池优化白名单 | 后台常驻 | `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | 提示可能被杀后台 |
| 存储（MANAGE_EXTERNAL_STORAGE） | 读配置文件 | `appops`（ADB）或设置页 | 提示需 ADB 或改用应用内配置 |

**每项显示**：未授权 → "去授权"；已授权 → "已开启"；拒绝过 → 说明原因 + 重新入口；
不可用 → 明确原因 + 替代方案。

**Android 版本差异处理（Compat）**：
- 通知权限仅 Android 13+ 存在 → 低版本不显示该项
- `MANAGE_EXTERNAL_STORAGE` 仅 Android 11+ → 低版本走 `READ/WRITE_EXTERNAL_STORAGE`
- 电池优化页在部分 ROM 上不存在 → try-catch 后降级到"应用详情页"
- 悬浮窗权限页在部分 ROM 上路径不同 → 失败则跳应用详情页

### 3.4 首次使用流程（用户第五章 9 步）

```
1 启动 App
2 一句话介绍（这玩意能干什么）
3 兼容性检查（Android 版本 / 屏幕 / 是否支持无障碍）
4 配置 AI 服务（填 endpoint + model + key，或从已有配置导入）
5 逐项授权（复用 M1 的 PermissionHub，只列必需项）
6 测试按钮 → 验证授权与网络是否正常（发一句"你好"看有没有回答）
7 添加一个常用应用与目标按钮（M2 的 UI）
8 执行一次安全的定位测试（在目标应用里找按钮，只高亮不点击）
9 全部通过 → 显示"可以用了"
```

**降低配置成本**：
- 第 4 步提供"从 `/sdcard/dsh/assistant-voice.json` 导入"（老用户零输入）
- 第 6 步失败时明确指出是"网络 / Key / 权限"哪一类问题
- **不假装开箱即用**：没配 AI 就明说"配好 Key 才能用"，不伪装

---

## 四、技术风险（阶段 B 结论）

| 风险 | 等级 | 应对 |
|---|---|---|
| 定位规则跨设备失效 | 高 | 存特征不存坐标；置信度不足→校准；`verifiedOn` 标记 |
| 无障碍节点缺描述（很多 App 不标） | 高 | 四级策略逐级降级；AI 视觉兜底；最后请用户校准 |
| 不同 ROM 权限页路径不同 | 中 | Compat 层 try-catch + 多候选 Intent + 降级到应用详情页 |
| 模拟器与真机权限行为不同 | 中 | 测试报告里明确标注差异，不把模拟器结果当通用结论 |
| 磁盘紧张（D 剩 5.7G / C 剩 4.6G / E 剩 19G） | 中 | 模拟器装 E 盘；必要时清构建产物（1.1G，可重建） |
| 误触发送/支付 | **高** | `risk` 分级 + 强制确认；默认不自动发送；失败不盲点 |
| 用户无 API Key | 中 | 明确提示为前置条件，不伪装可用 |

---

## 五、验收标准

1. 可构建的完整源码（`D:\DSHA-lite`）
2. 实际生成的 APK + 路径 + SHA-256
3. 安装与启动验证记录（真机 + 模拟器）
4. 兼容性与测试报告
5. 权限与首次使用流程说明
6. AI 按钮定位机制说明
7. 已知限制与下一阶段清单
8. 代码变更记录（DSHA-lite 无 git → 用文件清单 + 改动摘要代替 diff）

**测试报告必含**（用户第八章表格）：构建 / 安装 / 权限 / 按钮定位 / 异常恢复 / 兼容性 / APK。

> 纪律：**代码写完 ≠ 构建成功；构建成功 ≠ 功能正常；单机通过 ≠ 兼容大部分安卓。**

---

## 六、执行顺序

```
阶段 C（进行中）模拟器装到 E:\android-sdk-emu，建 AVD，验证可装机
阶段 D 实现 M1→M2→M3→M4（按依赖顺序：Compat → Store/Locator → Hub → Onboarding）
阶段 E 构建 APK，模拟器 + 真机跑测试，修实际问题
阶段 F 出报告 + 后续清单
```

---

## 七、硬约束（全程遵守）

- 不改 `D:\DSHA` 生产逻辑（除已修的 2 个 bug：NPE 崩溃、ASR 引号）
- 不删用户文件；不动 `C:\Temp\dsha\dsha-0.1.7-rc2-base.apk`
- 不泄露密钥：文档一律用 `<KS_PASS>` / `<API_KEY>` 占位
- 不把未验证结果写成通过

---

# 第二阶段（2026-10-11 下午）：配置存储方案

## 为什么必须改

第一阶段的配置读取**第一优先级是 `/sdcard/dsh/*.json`**，而 Android 11+ 读那里需要
`MANAGE_EXTERNAL_STORAGE` —— 这个权限**系统不给普通 App 弹框、只能用电脑 ADB 授予**。
后果：普通用户装完 APK → 读不到配置 → 联网问答和语音唤醒全废。与"大众化"直接冲突。

## 新方案：内部存储优先 + 单向迁移

新增 `ConfigStoreLite`，统一所有配置读写：

```
读顺序
  1) filesDir/dsha/<name>      ← 主存储，零权限
  2) /sdcard/dsh/<name>        ← 仅当 1 不存在时才读；读到就【一次性迁移】进 1
  3) 老的 getExternalFilesDir / SharedPreferences（兼容更早版本）
  4) 内置默认（并在首次启动时把默认模板落到 1，用户有可编辑的底子）
```

### 迁移纪律（用户要求"不要默默覆盖已有用户数据"）

| 规则 | 实现 |
|---|---|
| 只在「内部不存在 且 旧位置存在且**可读**」时迁移 | `decide(internal, legacy, readable)` |
| **内部已存在时绝不被旧位置覆盖** | 同上：第一优先判 `internalExists` |
| 迁移后**不删**旧文件 | 只写内部，旧文件留着可回滚/取证 |
| 旧位置存在但读不到 | 退回默认 + 明确原因（不假装读到了） |

### 原子写入

`atomicWrite()`：写同目录 `.tmp` → `fsync` → `rename` 覆盖。
避免"写一半被杀"导致 JSON 半截、下次启动配置全丢。

### 其他处理（用户逐条要求）

| 要求 | 实现 |
|---|---|
| 首次启动自动创建默认配置 | `ensureDefault(ctx, name, 模板)`；模板**只留空字段**，不预填任何 Key |
| JSON 格式错误 | 记明确日志（含来源），退回内置默认；**不改动用户文件**（留证据给人工修） |
| 字段缺失 | `optString/optBoolean` 逐个取，缺就用默认值 |
| 写入失败 | 返回 false，调用方必须提示用户，不假装成功 |
| 外部配置 | 不再强制要宽权限；应用内配置为唯一写入目标 |

## 改动的文件

| 文件 | 改动 |
|---|---|
| `assistant/ConfigStoreLite.java` | **新增**，统一配置层（决策逻辑为纯函数，可单测） |
| `assistant/AssistantConfig.java` | `load()` 改为内部优先；加默认模板创建；JSON 损坏明确处理 |
| `assistant/ActionTargetStore.java` | 读写改走 ConfigStoreLite（原来直写 /sdcard） |
| `core/ConfigStore.java` | `readChatApiKey()` 改为内部优先，旧位置降为兼容 |

> **待办（下一阶段）**：`AssistantPanel` 里仍有"改 /sdcard/... 的 chatApiKey"这类
> 面向用户的文案，需要同步改成"应用内配置"的说法；以及给 PermissionHub 加一个
> 「打开配置」入口 + SAF 文件选择器导入（用户要求"优先使用系统文件选择器"）。
