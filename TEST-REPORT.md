# DSHA 大众化版本 — 兼容性与测试报告

> 日期：2026-10-11
> 基线：`D:\DSHA-lite`（独立轻量 App，`com.dsh.assistant`）
> 纪律声明：本报告只写**真实执行过**的结果。未执行的项一律标「待验证」，不写成通过。

---

## 一、构建

| 项 | 内容 |
|---|---|
| 命令 | `cd D:\DSHA-lite; .\gradlew.bat --console=plain :app:assembleDebug` |
| 环境 | JDK 17.0.20.1 / Android SDK platforms;android-37.0 / build-tools 36.0.0 / Gradle 9.3.1 / AGP 9.1.1 |
| 结果 | **BUILD SUCCESSFUL**（末轮 45s，累计 22 轮迭代） |
| 失败原因（历史） | 见 §七「构建过程踩的坑」 |
| 日志 | `D:\DSHA-lite\build1.log` ~ `build22.log` |

---

## 二、安装

| 设备 | Android | 结果 |
|---|---|---|
| 真机 华为 SP100（GNT-AN00） | **11（SDK 30）/ EMUI 12** | **Success**（2026-10-11 08:44；08:40 首次装机） |
| 模拟器 emulator-5554 | **13（SDK 33）/ 720×1280 @320dpi** | **Success**（2026-10-11 09:47） |

APK：

```
路径   D:\DSHA-lite\app\build\outputs\apk\debug\app-debug.apk
体积   12.43 MB
签名   debug（debuggable=true）
SHA256 08ac5735967670ff629624c9a90083a8b4594ac691f9a09522c70aca1cfed28c
```

---

## 三、权限（逐项测试结果）

在**真机（Android 11）**上实测：

| 权限 | 授权方式 | 实测结果 |
|---|---|---|
| 无障碍服务 | `settings put secure enabled_accessibility_services` | ✅ 绑定成功；`Bound services` 有值、`Crashed` 空 |
| 麦克风 | `pm grant RECORD_AUDIO` | ✅ `granted=true`；唤醒服务能占麦克风 |
| 写安全设置（自愈用） | `pm grant WRITE_SECURE_SETTINGS` | ✅ `granted=true` |
| 存储（`MANAGE_EXTERNAL_STORAGE`） | **`appops set ... allow`**（不是 pm grant） | ✅ 授权后能读 `/sdcard/dsh/assistant-voice.json` |
| 通知 | 未单独测（EMUI 11 无此运行时权限） | ⚠️ 待验证 |
| 悬浮窗 | 未单独测 | ⚠️ 待验证 |
| 通知使用权 | 未测 | ⚠️ 待验证 |
| 电池优化白名单 | 未测 | ⚠️ 待验证 |

**踩到的大坑（已写进代码注释）**：
`MANAGE_EXTERNAL_STORAGE` 若漏授权 → 配置读不到 → **语音唤醒服务启动即退出、联网问答无 Key**。
且 `run-as ... cat` 会报 Permission denied **但那是假信号**（run-as 绕过 framework 层）。

**新增的权限管理页（`PermissionHubActivity`）**：
代码完成、编译通过。**未在设备上走完逐项授权流程** → 标「待验证」。

---

## 四、按钮定位

### 4.1 机制（本次新实现）

```
① 无障碍节点定位   uiDump → 按 viewId / contentDescription / text 匹配
② 预配置规则       /sdcard/dsh/action-targets.json（存特征，不存坐标）
③ AI 辅助识别      节点树认不出 → 交 AssistantTaskRunner 走截图+视觉
④ 用户辅助校准     learnRules() 从用户点中的节点反向提取特征
```

新增文件：
- `ActionTargetStore.java`（12.5KB）：配置读写、风险分级、`verifiedOn` 设备画像
- `ActionTargetLocator.java`（10.8KB）：dump 解析、规则匹配、最小面积选优、`learnRules`
- `DshaAccessibilityService.dumpNode` 增强：**新增 `id=` 与 `cd=` 输出**（原来只输出合并后的 label，viewId 与原始 contentDescription 看不见，规则无从写起）

### 4.2 实测结果

| 项 | 结果 |
|---|---|
| dump 解析 | ✅ 代码就绪，**未在真机上跑通端到端定位**（新增的 id/cd 字段需重装 APK 后 dump 才带上） → **待验证** |
| 配置读写 | ⚠️ 待验证（依赖存储权限已授，理论可用） |
| 已有定位链路（AI 驱动） | ✅ **此前真机验证过**：`AssistantTaskRunner` 曾成功在 Telegram 里发出一条消息（气泡实证 AM12:14） |

> **诚实说明**：本次新增的「可配置规则」这条路径是**新写的、未在设备上验证**。
> 已验证的是它依赖的底层能力（`uiDump` / `uiTapText` / AI 决策链路）。

---

## 五、异常恢复

| 场景 | 结果 |
|---|---|
| 无障碍服务被判崩溃后不自愈 | ✅ **已修**：`AccessibilityHealer` 自动重绑（真机验证：Crashed 清空、服务恢复） |
| 进程被回收 | ✅ 已加 `AssistantKeepAliveReceiver` 系统级闹钟兜底（真机验证：闹钟 5 分钟触发并成功重排） |
| 开机自启 | ✅ `BootReceiver` 补拉唤醒服务 + 排闹钟（代码就绪） |
| 撤销权限 | ⚠️ **待验证**（`PermissionHub.onResume` 已实现返回前台重查状态） |
| 网络失败 | ⚠️ **待验证**（`AssistantVoiceInput.friendlyError` 有分类文案，未逐项实测） |
| API Key 错误 | ⚠️ 待验证 |
| 模拟器进程崩溃 | ✅ **实测到**：模拟器运行约 10 分钟后进程消失（见 §六） |

---

## 六、兼容性

### 已验证范围

| 维度 | 值 |
|---|---|
| Android 版本 | **11（真机）**、**13（模拟器，仅到"App 启动 + 引导页显示"）** |
| 厂商 ROM | 华为 EMUI 12 |
| 屏幕 | 720×1600 @ 真机；720×1280 @320dpi 模拟器 |
| APK 装机 | 两个环境都成功 |

### 模拟器测试的真实经过

| 步骤 | 结果 |
|---|---|
| 下载 emulator + system-image（android-33 google_apis x86_64） | ✅ 9.5G，装到 `E:\android-sdk-emu` |
| 创建 AVD | ✅ `E:\avd\dsha33` |
| 首次启动 | ❌ `Not enough space to create userdata partition`（需 7372MB，可用 6692MB） |
| 清理 Windows 传递优化缓存（官方 cmdlet） | ✅ 释放 1.2G |
| 再次启动 | ✅ `boot_completed=1`（90 秒） |
| 安装 APK | ✅ Success |
| 启动 App | ✅ **首次引导页正确弹出**（`Displayed OnboardingActivity: +2s736ms`） |
| 继续操作 | ❌ **模拟器进程消失**，adb 变 offline；userdata 占 3.0G |

**崩溃原因分析**：模拟器以 **swiftshader 软件渲染**运行（宿主 Intel Iris Xe 的 Vulkan 1.2.187 低于模拟器要求的 1.3.240，被降级），叠加 2G 内存与紧张磁盘（E 盘一度只剩 4.6G）。属**测试环境资源限制**，不是被测 App 的缺陷——但**尚未排除** App 侧因素。

### 未验证范围（明确列出）

- Android 12 / 14 / 15 / 16
- 小米 / OPPO / vivo / 三星 / 原生 Android
- 平板、横屏、大字体、显示缩放
- 非中文语言环境
- 无系统语音识别的设备
- 通知使用权 / 电池优化白名单的实际跳转
- `PermissionHubActivity` 全流程
- `ActionTargetLocator` 端到端定位

---

## 七、构建过程踩的坑（可复用）

| # | 现象 | 原因 / 修法 |
|---|---|---|
| 1 | `Dependency verification failed` | 从 DSHA 复制 `gradle/` 带上了 `verification-metadata.xml`（212KB 依赖校验锁）→ 删除 |
| 2 | `vscreen 包不存在` | 删 vscreen 目录 + 服务里的引用 |
| 3 | `ConfigStore` 缺 `KeyVault`/`Constants`/`PreferenceValue` | 重写简化版：org.json 读 `/sdcard/dsh/assistant-voice.json` |
| 4 | `resource style/Widget.DSHA.* not found` | themes.xml 引用了没复制过来的 style |
| 5 | `Duplicate resources` | 我补的 style 与 `styles.xml` 已有的重复 → 只补真正缺的 3 个 |
| 6 | `drawable/dsha_brand not found` | 它在 `drawable-nodpi/` 不在 `drawable/` |
| 7 | `bg_polished_card not found` | 它是**别名**，定义在 `values/drawable_aliases.xml` |
| 8 | `drawable-nodpi` 变成 368KB 的**文件** | `cp` 目标已存在为文件 → 删文件重建目录 |
| 9 | `Cannot create service of type BuildSessionActionExecutor` | Gradle daemon 冲突 → `gradlew --stop` 后重试 |
| 10 | 模拟器 `Unknown AVD name` | 在 Git Bash 里跑丢了 `ANDROID_AVD_HOME` → 用 PowerShell 并设全环境变量 |
| 11 | `adb pull /sdcard/x` 报 `E:\...\sdcard\x 不存在` | Git Bash 路径转换 → `MSYS_NO_PATHCONV=1` |
| 12 | `partition-size (2048) must be between 10 and 2047` | 模拟器参数上限 |

---

## 八、已知限制与下一阶段

### 限制

1. **模拟器不稳定**（软件渲染 + 资源受限），不能作为主要验证环境。要稳定需要：宿主 GPU 驱动升级到 Vulkan ≥1.3.240，或换一台有独立显卡 / 更多磁盘的机器。
2. **新功能未在设备上跑通**：`PermissionHubActivity` / `OnboardingActivity` / `ActionTargetLocator` 只有"编译通过 + 引导页能弹出来"，完整交互未验证。
3. **签名是 debug**：要发布需换正式 keystore（需口令）。
4. **`MANAGE_EXTERNAL_STORAGE` 只能 ADB 授**：普通用户装完**读不到配置**，除非改用应用内配置（引导第 4 步已提供输入框，但两条路径的关系需要理顺）。

### 下一阶段（按优先级）

| 优先级 | 任务 |
|---|---|
| P0 | 连真机跑通 `PermissionHubActivity` 逐项授权 → 修实现问题 |
| P0 | 跑通 `ActionTargetLocator` 端到端（在 Telegram/微信里定位"发送"并**只高亮不点**） |
| P0 | 重装 APK 后验证 dump 新增的 `id=` / `cd=` 字段真的输出 |
| P1 | `OnboardingActivity` 9 步全流程走一遍，修文案与跳转 |
| P1 | 撤销权限 / 断网 / 杀进程 三类异常恢复实测 |
| P1 | 把配置读取从 `/sdcard` 改为「应用内配置优先 + sdcard 兜底」，让普通用户无需 ADB |
| P2 | 补 Android 12/14 模拟器（需先解决宿主 Vulkan 与磁盘） |
| P2 | 正式签名 + 发布流程 |
| P3 | 自动化测试（Espresso/UiAutomator）覆盖权限状态机与定位降级路径 |

---

## 九、结论

- **构建**：✅ 成功，APK 12.43 MB（原 DSHA 262 MB）
- **装机**：✅ 真机 Android 11 + 模拟器 Android 13 均成功
- **首次启动引导**：✅ 模拟器上确认能弹出（2736ms）
- **原有助手功能**：✅ 真机验证通过（悬浮球 80ms、点图标 240ms 弹面板、问答 378ms、语音唤醒）
- **本次新增的三个产品化模块**：⚠️ **代码完成、编译通过，但完整功能未在设备上验证**
- **模拟器**：⚠️ 搭建成功但运行不稳定，未能完成自动化测试

> 一句话：**能装的装上了、原来能用的仍然能用；这次新写的产品化功能还需要在真机上过一遍才算数。**

---

# 第二阶段补充（2026-10-11 下午）

## A. 配置存储改造 —— 已完成并有单元测试证据

| 项 | 状态 | 证据 |
|---|---|---|
| 内部存储优先（零权限） | ✅ 已实现 | `ConfigStoreLite.read()` → `filesDir/dsha/<name>` |
| 旧位置单向迁移 | ✅ 已实现 + 已单测 | 「内部没有_旧位置有且可读_应迁移」等 4 个用例 |
| **内部已存在时不被旧位置覆盖** | ✅ 已实现 + 已单测 | 「内部有配置时_即使旧位置存在也不迁移」 |
| 首次启动创建默认配置 | ✅ 已实现 | `ensureDefault` + `defaultJsonTemplate()`（空字段，不预填 Key） |
| 原子写入 | ✅ 已实现 + 已单测 | 4 个用例（新文件/覆盖/自建目录/不留 tmp） |
| JSON 损坏处理 | ✅ 已实现 | 记日志（含来源）+ 退回默认 + **不改用户文件** |
| 写入失败要报错 | ✅ 已实现 + 已单测 | 「原子写入_写入失败要抛而不是静默成功」 |

**改动的文件**：`ConfigStoreLite.java`(新增) / `AssistantConfig.java` / `ActionTargetStore.java` / `core/ConfigStore.java`

## B. 单元测试 —— 56 个，全部通过

命令：`.\gradlew.bat :app:testDebugUnitTest` → **BUILD SUCCESSFUL**

| 测试类 | 用例数 | 失败 |
|---|---|---|
| `ConfigStoreLiteTest` | 13 | **0** |
| `ActionTargetStoreTest` | 22 | **0** |
| `ActionTargetLocatorTest` | 21 | **0** |
| **合计** | **56** | **0** |

结果文件：`app/build/test-results/testDebugUnitTest/*.xml`

### 测试过程中真实发现并修复的 2 个问题（这就是单测的价值）

1. **`bounds` 解析失败会让"取面积最小"整体失效**
   测试数据误写成带括号的 `区域=(0,0),(320,800)`，而真实 dump 是 `区域=0,0,320,800`。
   解析不到 → `area()` 恒为 0 → 候选排序失效 → 可能点到大容器的空白中心。
   **已修**：解析失败时用中心点构造 1×1 退化区域，保证面积非 0（不再静默退化成 0）。

2. **"找到了但不可点击"时自相矛盾**
   原实现既设置了坐标、又让 `found()` 返回 false（因为 `reason != null`）。
   上层若只看坐标就可能真去点一个文字标签。
   **已修**：不可点击时**一个坐标都不给**（`bounds=null`），只报"看见了却不能点"+ 建议 AI 复核。
   语义收敛为：**要么给可用坐标并说找到了，要么一个坐标都不给。**

## C. APK

```
路径   D:/DSHA-lite/app/build/outputs/apk/debug/app-debug.apk
体积   12.44 MB
SHA256 c091ac549752d9ab92a97ee08c8cc525442d4c92ba2a4c899413085e13d76ae6
签名   debug
```

## D. 本阶段仍未验证的（诚实标注）

| 项 | 状态 |
|---|---|
| 新 APK 的真机/模拟器装机 | ❌ **未执行**（两处设备此刻都未连接） |
| 配置改造在真实设备上的行为（内部读写、迁移、重启后保留） | ❌ 仅经 JVM 单测，**未在设备验证** |
| 模拟器恢复 | ❌ **未执行**：E 盘剩 4.6G，启动需 7.37G，空间不足 |
| 权限页 / 定位器 / 引导页的端到端交互 | ❌ 仍未验证 |

> 按纪律：**单元测试通过 ≠ 设备上能用**。JVM 单测覆盖的是纯逻辑分支，
> 文件系统行为、`Context` 相关路径、真实 Intent 跳转都必须在设备上再验一遍。

---

# 第四阶段补充（2026-10-11）：权限流程设备验证 + 定位器主流程审查

## A. 权限与引导流程 —— 设备验证结果

### A.1 验证方法说明（避免"只看页面"的陷阱）

每项都做**双向交叉验证**：
1. 读页面显示的状态（uiautomator dump）
2. 读系统真实状态（`settings` / `appops` / `dumpsys`）
3. 两者对比
4. **改变系统状态，重进页面，看是否跟着变**

### A.2 逐项结果

| # | 权限 | 页面显示 | 系统真实状态 | 一致 | 判定 |
|---|---|---|---|---|---|
| 1 | 无障碍服务 | ● 已开启 | `enabled_accessibility_services` 含本包 | ✅ | **PASS** |
| 2 | 悬浮窗 | ● 未授权 | `SYSTEM_ALERT_WINDOW: default`（非 allow） | ✅ | **PASS** |
| 3 | 通知 | ● 未授权 | `POST_NOTIFICATIONS: granted=false` | ✅ | **PASS** |
| 4 | 麦克风 | （屏幕外） | `RECORD_AUDIO: granted=true` | — | 部分（计数已覆盖） |
| 5 | 通知使用权 | （屏幕外） | 列表无本包 | — | 部分 |
| 6 | 后台常驻 | （屏幕外） | `deviceidle whitelist` 无本包 | — | 部分 |
| 7 | 存储访问 | （屏幕外） | `MANAGE_EXTERNAL_STORAGE: allow` | — | 部分 |

**交叉验证的关键证据**：页面显示 `已就绪 3 / 7 项`。
按系统真实状态推算：已就绪 = 无障碍 ✅ + 麦克风 ✅ + 存储 ✅ = **3**；未就绪 = 悬浮窗 + 通知 + 通知使用权 + 电池 = **4**。
**3 + 4 = 7，与页面完全吻合** —— 说明计数逻辑正确。

### A.3 状态更新验证（最强证据）

撤销无障碍服务 → 返回引导页 → 再进权限页：

```
撤销前：已就绪 3 / 7 项 · 无障碍服务 → ● 已开启
撤销后：已就绪 2 / 7 项 · 无障碍服务 → ● 未授权 [去授权]
恢复后：已就绪 3 / 7 项 · 无障碍服务 → ● 已开启
```

**结论**：页面状态是**实时读取**的（`onResume` 刷新生效），不是缓存或硬编码。**PASS**

### A.4 引导流程验证

| 步骤 | 验证方式 | 结果 |
|---|---|---|
| 第 1 步 介绍 | 页面 dump | ✅ 文案/按钮正确 |
| 第 2 步 兼容检查 | 点击"开始设置"进入 | ✅ PASS |
| 第 3 步 配置 AI | 点击"继续"进入 | ✅ **页面显示 `来源：内部存储(filesDir/dsha)`** —— 反向验证了配置改造生效 |
| 第 4 步 授权 | 点击"下一步"进入；点"打开权限页" | ✅ 成功进入 `PermissionHubActivity` |
| 第 5-7 步 | — | **NOT TESTED**（未继续走完） |

**附带的链路验证**：点桌面图标（launcher）→ 因为引导未完成 → **正确进入引导页**（`Displayed OnboardingActivity +886ms`）。

### A.5 发现的问题：两个页面 `exported=false` 导致无法从 ADB 直接启动

```
Permission Denial: starting Intent { ... PermissionHubActivity } from null (uid=2000) not exported
```

- **这是正确的安全设计**（不导出防止其他 App 拉起）
- 代价：测试必须走 App 内部入口（已通过引导页绕通）
- 建议：**保留 `exported=false`**，测试改用内部入口或 debug 变体

---

## B. 定位器主流程审查（接入前）

### B.1 当前调用链（实测）

```
AssistantPanel（读屏 + 守卫）
   └─ 敏感 App 判定 isProtected → 不读屏、不给模型
        ↓ dump 文本
AssistantTaskRunner.run()  [第 298 行 单入口]
   └─ execute(host, act, a, dump)  [第 451 行]
        ├─ case "tap"     → centerFromDump(dump,text) 或 uiTapText(text)
        ├─ case "tap_xy"  → uiTap(a.optInt("x",-1), a.optInt("y",-1))
        ├─ case "input"   → uiInput / 先点输入框
        └─ case "key"     → uiKey
             ↓
   DshaAccessibilityService.uiTap(x,y)  [第 679 行] → dispatchGesture
```

**决策者**：模型。模型看 dump 后输出 `{"act":"tap","text":"..."}` 或 `{"act":"tap_xy","x":..,"y":..}`。

### B.2 七个问题的结论

| # | 问题 | 结论 | 证据 |
|---|---|---|---|
| 1 | 当前主流程在哪决定目标控件？ | `AssistantTaskRunner.execute()`，决策来自**模型输出**；辅助定位是 `centerFromDump()`（子串匹配取第一个）和 `uiTapText()`（节点点击） | 代码 451-475 行 |
| 2 | 定位器接入后原逻辑会被绕过吗？ | **取决于接法**。建议在 `centerFromDump` **之前**插一层（配置优先、未命中回落），则不会绕过；若直接替换则会失去模型按文字灵活搜索的能力 | 代码结构 |
| 3 | 包名不匹配时可靠拒绝吗？ | **新定位器：可靠**（设备实测 `原因=当前界面是 X，不是目标应用`）；**现有主流程：无包名校验**（`centerFromDump` 只比文字） | 实测 + 代码 728-742 |
| 4 | 不可点击节点会传出有效坐标吗？ | **新定位器：不会**（实测 `bounds=null` + 坐标 `(0,0)`）；**现有 `centerFromDump`：会**（不判可点击，匹配到文字就给坐标） | 实测 + 代码 |
| 5 | 定位失败会错误点击默认坐标吗？ | **有风险**：`case "tap_xy"` 用 `optInt("x",-1)` 兜底，而 `uiTap()` **没有坐标范围校验**。`(-1,-1)` 在屏幕外系统通常忽略，但**缺一道显式防线** | 代码 572、679 |
| 6 | needsAi/置信度/bounds/坐标在链路中被正确处理吗？ | **目前无人消费** —— `ActionTargetLocator` **尚未接入**（`AssistantTaskRunner` 未 import） | grep 结果 |
| 7 | 存在未授权就点击的路径吗？ | **"读"有防护，"操作"无独立守卫**。`AssistantPanel` 对敏感 App 不读屏（`screenText=""`、不给模型）；但 `AssistantTaskRunner` 里**没有任何 `isProtected` 检查** → 依赖"上游不喂敏感 dump"的**链式保护**，属纵深防御缺口 | grep：`isProtected` 只出现在 `AssistantPanel` / `DshaNotificationListener` |

### B.3 定位器 vs 现有 `centerFromDump` 的差距

| 维度 | `centerFromDump`（现有） | `ActionTargetLocator`（新） |
|---|---|---|
| 匹配方式 | 子串 `contains` | by=text/viewId/contentDescription + exact 选项 |
| 多候选处理 | **取第一个**（任意） | **可点击优先 + 面积最小优先** |
| 可点击校验 | ❌ 无 | ✅ 有（不可点→不给坐标） |
| 包名校验 | ❌ 无 | ✅ 有 |
| 置信度 | ❌ 无 | ✅ 0.4 / 0.7 / 1.0 |
| 降级信号 | ❌ 无 | ✅ `needsAi` |
| 诊断信息 | ❌ 无 | ✅ `via` / `tried` / `reason` |

---

## C. 交付分类（本轮）

### C.1 已通过的设备测试

- 权限页 7 项中 3 项**逐一对比系统状态一致**
- **已就绪计数 3/7 与系统状态推算完全吻合**
- **状态更新双向验证**（撤销→2/7，恢复→3/7）
- 引导流程第 1~4 步 + launcher→引导页链路
- 第 3 步显示 `来源：内部存储(filesDir/dsha)`
- 定位器 5 个场景（上一轮，仍有效）

### C.2 仅通过代码审查

- `uiTap()` 缺少坐标范围校验
- `centerFromDump()` 子串匹配取第一个、不判可点击
- `AssistantTaskRunner` 无 `isProtected` 守卫
- 定位器四个字段（needsAi/置信度/bounds/坐标）**无人消费**

### C.3 失败项目

- **无**（本轮未出现功能失败；`exported=false` 导致 adb 启动被拒是**预期安全行为**，已绕过）

### C.4 未测试项目

- 引导第 5~7 步
- 权限页第 4~7 项的实际"去授权"跳转（悬浮窗/通知/通知使用权/电池优化）
- 语音与联网端到端（缺真实凭据）
- 真机 Android 11 / EMUI 12 复验

### C.5 定位器主流程集成风险

| 风险 | 等级 | 说明 |
|---|---|---|
| **点击链路无独立守卫** | **高** | 依赖上游读屏守卫，纵深防御不足 |
| `tap_xy` 无坐标校验 | 中 | 缺显式防线 |
| 接入方式若为"替换"会削弱灵活性 | 中 | 建议"前置 + 回落"而非替换 |
| 四个语义字段无消费方 | 中 | 接入时必须明确 `needsAi` 该触发什么 |
| `centerFromDump` 与新定位器行为不一致 | 低 | 两者并存时需明确优先级 |

### C.6 下一步最小可行修改方案

**改动 1（安全，优先）**：`uiTap()` 加坐标范围校验
```java
int[] sz = Compat.screenSize(ctx);
if (x < 0 || y < 0 || x >= sz[0] || y >= sz[1]) return "[ERR] 坐标越界，已拒绝";
```
配套：`case "tap_xy"` 改为 `optInt("x", -1)` 后**先判 x/y > 0** 再调用。

**改动 2（纵深防御）**：`AssistantTaskRunner.execute()` 开头加独立守卫
```java
String pkg = ...; // 从 dump 第一行取
if (AssistantGuard.isProtected(pkg, cfg.allowedPackages)) return false;
```

**改动 3（接入定位器，前置 + 回落）**：
```java
// 在 centerFromDump 之前：
ActionTargetStore.Target t = matchConfiguredTarget(pkg, text);
if (t != null) {
    Result r = ActionTargetLocator.locate(dump, t, pkg);
    if (r.found() && r.confidence >= 0.7f) { uiTap(r.cx, r.cy); return true; }
    // 未命中或置信度不足 → 落回原有逻辑，并把 r.reason 记进日志
}
```

**改动 4**：日志补 `via/confidence/tried`，便于线上排查。

> 以上均**未实施**，本轮只做审查。

---

# 第五阶段补充（2026-10-11）：P0 安全闭环

## A. 缺陷修复

| 编号 | 严重度 | 状态 | 修复内容 | 验证方式 |
|---|---|---|---|---|
| **D-01** | 中 | **已修复** | 任务开始抓 `ScreenTarget` 授权上下文；每步执行包进 `runAuthorized(target, () -> !stale(gen), ...)`；捕获 `SCREEN_TARGET_CHANGED` 停手重评估；系统设置明确停手策略 | 编译 + 单测（`ScreenTargetTest` 9 项）+ 回归（定位器/坐标校验）；**端到端 BLOCKED** |
| **D-06** | 低 | **已修复** | 读屏留痕从"dump 前 200~240 字符正文"改为结构性指标（包名/节点行数/带 viewId 数/字符数），不含屏幕文字 | 编译通过；**设备端到端 BLOCKED**（需任务运行） |
| D-02 | 高 | **已修复并加固** | 代次逻辑抽成 `TaskGeneration`（可测）；`AssistantTaskRunner` 保留 `stale(gen)` 签名，调用点不变 | **73 项单测全过**，含竞态复现 + 对照实验 |
| D-03 | 中 | **已修复** | `validateTapCoords()` | 设备回归 PASS |
| D-04 | 高 | **已修复** | `runLoop` 每步独立敏感 App 守卫 | 编译 + 逻辑审查 |
| D-05 | 中 | **已修复** | `tap_xy` 参数校验 | 编译通过 |

## B. 单元测试（73 项全过，从 56 增至 73）

| 测试类 | 用例 | 新增 | 覆盖 |
|---|---|---|---|
| `ActionTargetLocatorTest` | 21 | — | 四级策略/降级/多候选/包名校验/learnRules |
| `ActionTargetStoreTest` | 22 | — | 序列化/风险分级/坏数据容错 |
| `ConfigStoreLiteTest` | 13 | — | 迁移决策/原子写/失败要抛 |
| **`ScreenTargetTest`** | **9** | **✓** | 包名变化/窗口变化/显示器变化/无效输入/**能力边界钉进测试** |
| **`TaskGenerationTest`** | **8** | **✓** | 代次隔离/**竞态复现**/**布尔版对照实验**/16线程并发检查点 |

### 竞态可复现性（用户核心要求）
> "并发测试必须能够稳定复现竞态，而不能仅凭连续触发几次就宣称竞态已经修复。"

两条测试合起来满足：
1. `竞态复现_旧任务在两步之间被顶替后不得恢复` —— 用 `CountDownLatch` 钉死时序，代次版**未恢复** ✅
2. `对照_布尔版在同一时序下会出错` —— 把修复前逻辑复刻进测试，**同一时序下确实出错** ✅

→ 不是"跑几次没崩"，而是**同一编排时序下，新旧实现表现相反**。

## C. 设备回归（模拟器 Android 13）

| 编号 | 项目 | 结果 | 证据 |
|---|---|---|---|
| R-01 | 定位器自检 | **PASS** | `命中 坐标=(360,453) 置信度=1.0` |
| R-02 | 坐标校验 | **PASS** | `[ERR] 坐标 (-1,-1) 超出屏幕范围 720x1280，已拒绝` |
| R-03 | 装机 | **PASS** | `Success` |
| R-04 | 无障碍 | **PASS** | `Bound services` count=1 |
| R-05 | D-01/D-06 任务路径 | **BLOCKED** | 模拟器无网（ping 100% 丢包）+ 无真实 Key → TaskRunner 无法启动 |
| R-06 | 主动点击 | **NOT TESTED** | 按用户明确要求本轮不启用真实界面自动点击 |

## D. 诚实的边界声明

**已修复但未端到端验证的**：D-01 的 `SCREEN_TARGET_CHANGED` 实际拦截效果。
代码路径已通、单测覆盖了 `ScreenTarget.matches` 的判定逻辑，但
"真机上界面切换时确实抛出并停手"这一步**没有跑过**（需要能跑起来的任务）。

**能力边界（不包装成更强保证）**：`ScreenTarget` 只能判「显示器+窗口+包名」，
**判不了页面、判不了控件**；且对键盘/弹窗会保守误判。页面级判断依赖 `runLoop` 每步重新 dump。

**已知副作用**：`runAuthorized` 作用域生效后，键盘弹出导致 windowId 变化会让任务**停手**
（而不是"继续做"）。方向安全，但可能造成"明明还在同一个 App 却停了"的体验。
若实际使用中过于频繁，应改为"重试读窗口一次再判"（原 DSHA 注释里也提到过这个策略）。

---

# 第六阶段补充（2026-10-11）：离线安全验证闭环

## A. 本轮缺陷与修复

| 编号 | 严重度 | 发现方式 | 修复 | 回归测试 |
|---|---|---|---|---|
| **D-07** | **高** | 审计 | 未知身份 fail-closed（原为"条件短路后放行"） | `未知身份必须停手_这是D07的回归` |
| **D-08** | 低 | **单测抓到** | 系统设置前缀精确化 | `名字相近但不是系统设置的_不误伤` |
| **D-09** | **中** | **单测抓到** | 空白包名用 `trim()` 判空 | `未知身份必须停手_这是D07的回归` |
| **D-10** | 中 | 日志审查 | 用户消息改结构化（3 处 + finish + 异常） | `LogPrivacyTest` + `LogFormatTest` |
| **D-11** | 中 | **单测抓到** | 提醒内容改结构化 | `D10回归_历史泄漏点不得复发` |
| D-01 | 中 | — | `verifyActionTarget` 改走 `ActionGate`（fail-closed） | `ActionGateTest` 16 项 |

**D-08/D-09/D-11 都是单元测试跑出来的** —— 不是审查看出来的。这三个缺陷在"只看代码"时都不显眼。

## B. 测试总表（134 项全过）

```
命令    .\gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleDebug
退出码  0
结果    BUILD SUCCESSFUL
```

| 测试类 | 用例 | 失败 | 覆盖重点 |
|---|---|---|---|
| `ActionGateTest` | 16 | 0 | **假派发器调用次数**；拒绝场景 `count == 0`，允许场景 `count == 1` |
| `GuardDecisionTest` | 23 | 0 | 敏感/未知/系统设置裁决；D-08 相似包名；D-09 空白包名 |
| `LogFormatTest` | 13 | 0 | **行为级**：喂虚构内容，断言输出不含 |
| `LogPrivacyTest` | 9 | 0 | **静态回归**：源码形状扫描 + `stripComment` 限制记录 |
| `ScreenTargetTest` | 9 | 0 | 包名/窗口/显示器变化；能力边界 |
| `TaskGenerationTest` | 8 | 0 | 竞态复现 + 布尔版对照实验 |
| `ActionTargetLocatorTest` | 21 | 0 | 四级定位策略 |
| `ActionTargetStoreTest` | 22 | 0 | 配置序列化与容错 |
| `ConfigStoreLiteTest` | 13 | 0 | 迁移决策与原子写 |
| **合计** | **134** | **0** | （73 → 134，新增 61） |

## C. 结果分级（严格按用户定义）

| 项目 | 状态 | 依据 |
|---|---|---|
| D-01 派发次数断言（JVM） | **PASS** | `ActionGateTest` 拒绝场景 0 派发、允许场景 1 派发 |
| D-01 设备端实际拦截效果 | **NOT TESTED** | 需能跑起来的任务（网+Key） |
| D-02 竞态复现 | **PASS** | `TaskGenerationTest` latch 钉死时序 + 布尔版对照出错 |
| D-03 坐标校验 | **PASS** | JVM + 设备只读（`[ERR] 超出屏幕范围`） |
| D-04/D-07 守卫裁决 | **PASS**（JVM） / **STATIC REVIEW ONLY**（设备） | 裁决逻辑 JVM 全覆盖；设备端拦截未触发 |
| D-06 日志隐私（行为级） | **PASS** | `LogFormatTest` 喂虚构内容断言不漏 |
| D-06 日志隐私（静态） | **PASS** | `LogPrivacyTest` 扫描通过（限制已记录） |
| D-06 运行时日志 | **NOT TESTED** | 需真实触发任务产生 logcat |
| D-11 + D-10 修复 | **PASS** | 源码核实实际语句 + 行为测试 |
| 设备只读回归 | **PASS** | 装机/无障碍/定位器/坐标校验 |
| 真实点击验证 | **NOT TESTED** | 按用户要求未启用 |

## D. 明确的能力边界（不夸大）

1. **`LogPrivacyTest` 是静态回归，不是运行时日志测试** —— 由类注释与测试名明确标注。
   它的 `stripComment` 有已知限制（误截字符串里的 `//`、跳过 `*` 开头行），
   已由 `stripComment的已知限制_被明确记录` 钉住。
2. **`ScreenTarget` 判不了页面/控件** —— 只能判「显示器+窗口+包名」。
3. **`verifyActionTarget` 的 fail-closed 效果只在 JVM 层验证过** ——
   设备端"真要派发时被拦住"这一步**没有跑过**。
4. **`uiKey`（全局动作）用宽松版校验** —— 有意保留，避免误伤本地"返回"命令。

## E. 是否有证据支持启用真实自动点击

**没有。**

理由：
- 派发前的判定链（`ActionGate`）在 JVM 层证据充分，但**设备端从未在真实任务里触发过**
- 守卫（`GuardDecision`）的**拒绝分支在设备上从未被真实触发**
- 本轮全程未启用真实点击（用户要求）

**结论**：在补上"设备端任务路径验证"之前，**不应启用真实自动点击**。
