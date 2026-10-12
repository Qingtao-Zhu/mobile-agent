# DSHA-lite 模拟器恢复与验证报告

> 日期：2026-10-11（第三阶段）
> 纪律：每个 PASS 都有真实命令输出支撑；未执行的一律标 NOT TESTED，不伪装。

---

## 总表

| 项目 | 结果 |
|---|---|
| **初始环境** | E 盘可用 4.61G；AVD `E:\avd\dsha33.avd` 占 **3.0G**（非 7.37G）；`userdata-qemu.img` 标称 6G 但是**稀疏文件**，实际只占 875MB；有 `bootcompleted.ini`（证明曾启动成功）；模拟器 37.2.12.0 / ADB 1.0.41 |
| **模拟器故障原因** | **已证实**：`need 7372.80 MB = dataPartition(6G) × 1.2` 的**保守空间检查**，不是实际占用。E 盘只剩 6.69G → 差 680MB 就拒绝启动。**与图形/内存无关**（虚拟化、GPU 检查全部 Ok） |
| **恢复方案** | 备份原配置 → 把 `disk.dataPartition.size` 从 6G 改为 **3G**（`config.ini` + `hardware-qemu.ini` 两处）→ 需求随之降到 ~3.6G，在现有空间内启动 |
| **模拟器启动** | **PASS** — `INFO | Boot completed in 47974 ms` |
| **APK SHA256** | `c091ac549752d9ab92a97ee08c8cc525442d4c92ba2a4c899413085e13d76ae6`（与任务书给定值**完全一致**） |
| **APK 安装** | **PASS** — `Performing Streamed Install / Success`；`versionName=1.0-lite`；升级安装（`firstInstallTime` 保留，数据未清） |
| **App 启动** | **PASS** — `Displayed OnboardingActivity: +1s792ms`；进程存活；零 FATAL/ANR |
| **配置首次创建** | **PASS** — `files/dsha/assistant-voice.json` 234 字节；**敏感字段全为空** |
| **配置迁移** | **PASS** — 日志 `来源决策: MIGRATED_FROM_LEGACY` → `已从旧位置迁移…true（旧文件保留未删）`；内部内容含迁移过来的值；旧文件仍在 |
| **配置不被覆盖** | **PASS** — 内部 `INTERNAL_MARKER`、旧位置 `LEGACY_MARKER`，触发后内部仍是 `INTERNAL_MARKER`，日志 `来源决策: INTERNAL(内部配置存在)` |
| **JSON 损坏处理** | **PASS** — 日志 `配置解析失败（来源 内部存储(filesDir/dsha)），已退回内置默认；文件未改动: org.json.JSONException…`；**无崩溃**、坏文件原样保留 |
| **权限与引导** | **部分 PASS** — 引导页 UI/文案/按钮正确（uiautomator dump 实证）；无障碍启用后正常绑定（`Bound services` 有值、`Crashed` 空）；权限页未逐项走完 → 该子项 NOT TESTED |
| **uiDump 新字段** | **PASS** — `DSHA/Dump: 节点 17 个，带 viewId 12 个，带 contentDescription 0 个（应用 com.android.settings）` |
| **定位器安全验证** | **PASS** — 见下节四个场景，**全程未点击任何目标** |
| **语音及联网功能** | **代码存在 + 服务可被触发**，但**未通过端到端验证**：无真实 ASR/Chat Key（按纪律不注入真实凭据），且模拟器无麦克风输入 → `AssistantWakeService` 按设计退出 |
| **剩余问题** | 见文末 |

---

## 一、模拟器故障的真相（阶段 A 诊断结论）

### 实测数据

```
E 盘可用 4.61G
E:\avd\dsha33.avd  总占 3.0G
  userdata-qemu.img          6442450944 字节（6G）← 标称
  userdata-qemu.img.qcow2     875102208 字节（875MB）← 实际写入
  cache.img                    33554432
  bootcompleted.ini                   0  ← 曾经启动完成过
```

### 结论

1. **7.37GB 不是实际占用**，而是 emulator 的**启动前保守检查**：
   `need = dataPartition × 1.2`（6144 × 1.2 = 7372.8，与报错数字完全吻合）。
2. **`userdata-qemu.img` 是稀疏文件**——标称 6G，实际只占 875MB。
   把"虚拟磁盘最大容量"当成"必须的磁盘空间"是判断错误的根源。
3. 失败与**图形、内存、虚拟化无关**：日志显示
   `hasCompatibleHypervisor: Ok` / `hasSufficientHwGpu: Ok` / `hasSufficientDiskSpace` 只在最后一步失败。

### 恢复操作（最小变量）

| 步骤 | 操作 | 结果 |
|---|---|---|
| 1 | 备份 `config.ini` / `hardware-qemu.ini` 到 `E:\avd\dsha33.avd\_backup-20261011\` | 完成 |
| 2 | `config.ini`: `disk.dataPartition.size` 6442450944 → **3221225472 (3G)** | 完成 |
| 3 | `hardware-qemu.ini`: `disk.dataPartition.size` `6g` → **`3g`**（这是 emulator 真正读的那份） | 完成 |
| 4 | 启动（**不加 `-wipe-data`**，避免配置被重置） | **Boot completed in 47974 ms** |

> 关键教训：`emulator` **每次启动都会重写 `config.ini`**，所以之前只改 `config.ini` 全部无效；
> 必须同时改 `hardware-qemu.ini`（运行时配置），且**不能带 `-wipe-data`**。

---

## 二、设备信息

```
emulator-5554    state=device    boot_completed=1
Android 13 (SDK 33) / 720x1280 @320dpi / x86_64
```

---

## 三、配置存储验证（本轮核心）

### 3.1 首次创建默认配置 — PASS

```
files/dsha/assistant-voice.json   234 字节   04:10 创建
内容：
{ "_说明": "…", "chatEndpoint": "", "chatModel": "", "chatApiKey": "",
  "asrEndpoint": "", "asrModel": "", "asrApiKey": "", "asrLanguage": "zh" }
```
**敏感字段全为空**，未预填任何 Key。

### 3.2 迁移 — PASS

场景：清空内部配置 → 旧位置放测试配置（`TEST_MIGRATION_MARKER`，**非真实凭据**）→ 触发。

```
DSHA/Config: assistant-voice.json 来源决策: MIGRATED_FROM_LEGACY(内部无配置，从旧位置迁移)
DSHA/Config: 已从旧位置迁移 assistant-voice.json 到内部存储: true（旧文件保留未删）
```
- 内部文件内容 = 迁移过来的值 ✓
- 旧文件 `/sdcard/dsh/assistant-voice.json` **仍在** ✓

**附加发现（重要）**：未授予存储权限时，决策为 DEFAULTS 而**不是**强行读取或崩溃——
`decide(internal=false, legacy=true, readable=false)` 这一分支在真机上被实际走到并表现正确。

### 3.3 内部不被覆盖 — PASS

```
内部 = {"chatApiKey":"INTERNAL_MARKER"}
旧位置 = {"chatApiKey":"LEGACY_MARKER"}
触发后内部仍 = {"chatApiKey":"INTERNAL_MARKER"}
日志：来源决策: INTERNAL(内部配置存在)
```
这是"不默默覆盖已有用户数据"的直接证据。

### 3.4 JSON 损坏 — PASS

```
写入 {this is NOT valid json,,,  → 触发
日志：配置解析失败（来源 内部存储(filesDir/dsha)），已退回内置默认；文件未改动:
      org.json.JSONException: Expected ':' after this at character 7 of {this is NOT valid json,,,
```
- 无崩溃、应用存活 ✓
- 坏文件**原样保留**（未被删/未被改写）✓
- 错误信息**指明了来源和具体原因** ✓

---

## 四、定位器安全验证（阶段 D3）

**方法**：新增只读自检入口（`-e locate <targetId>`），只做「读屏 → 定位 → 打日志」，**不派发任何手势**，
严格符合"只允许高亮或展示候选目标，禁止自动点击"的要求。

界面：系统设置页（`com.android.settings`，英文）

| 场景 | 目标/规则 | 实测结果 | 判定 |
|---|---|---|---|
| **命中可点节点** | `viewId=search_action_bar` | `结果=命中 坐标=(360,453) 置信度=1.0 依据=viewId≈search_action_bar(完全匹配) 需AI=false` | **PASS** |
| **命中不可点标签** | `viewId=search_action_bar_title` | `结果=未命中 坐标=(0,0) 置信度=0.4 原因=找到「search_action_bar_title」但它不可点击` | **PASS**（安全：不交出坐标） |
| **按文本命中标签** | `text=Search settings` | `结果=未命中 坐标=(0,0) 原因=找到「Search settings」但它不可点击` | **PASS**（同上） |
| **完全未命中** | `text=ZZZ_NO_SUCH_BUTTON_ZZZ` | `结果=未命中 坐标=(0,0) 需AI=true 试过=[text≈ZZZ_NO_SUCH_BUTTON_ZZZ(完全匹配) → 无命中]` | **PASS**（正确降级） |
| **包名不匹配** | 目标声明 `com.other.app`，当前是设置页 | `结果=未命中 原因=当前界面是 com.android.settings，不是目标应用（别的App） 需AI=false 试过=[包名校验]` | **PASS**（拒绝在别人家找按钮） |

### 安全底线总结（全部守住）

- 只有**可点击**且规则命中时才返回坐标（`(360,453)` 是唯一一次给出真实坐标）
- 其余四种情况一律 `bounds=null` + 坐标 `(0,0)` —— **绝不给出猜的坐标**
- 包名不匹配时直接拒绝，`needsAi=false`（语义正确：这不是"认不出"，是"不该找"）
- 诊断信息完整（`via` / `tried` / `reason`）可用于人工排查

---

## 五、uiDump 新字段验证

```
DSHA/Dump: 节点 17 个，带 viewId 12 个，带 contentDescription 0 个（应用 com.android.settings）
```
- **12 个节点带 viewId** → 新增的 `id=` 字段确实在输出（改造前完全看不到 viewId，定位规则无从写起）
- 统计日志**只报数量不报全文**——避免把屏幕内容（可能有聊天记录/通知）落到 logcat

---

## 六、语音及联网功能（诚实标注）

| 状态 | 实际结果 |
|---|---|
| 功能代码存在 | ✅ |
| 功能可被触发 | ✅ 助手回复"语音唤醒开了"（`runText … handled=true`） |
| 服务在模拟器中运行 | ❌ `AssistantWakeService` 不在服务列表、无 `dsha-wake` 线程、麦克风占用 0 |
| 完整端到端验证 | ❌ **未做** |

**原因（已定位）**：配置里 `asrApiKey` 为空（测试用的假 Key `TEST_KEY_NOT_REAL`），
`AssistantWakeService` 在 loop 开头检查到无 Key 就按设计 `stopSelf()` —— **这是正确行为，不是 bug**。

**未做的理由**：真实验证需要真实 Chat/ASR Key；按纪律**不注入真实凭据**到模拟器，
且模拟器无麦克风输入，也无法产生真实语音。

---

## 七、剩余问题

| # | 问题 | 证据 / 说明 |
|---|---|---|
| 1 | **adb 在软件渲染下会瞬时闪断** | 本次出现 3 次 `device offline`，但模拟器进程一直存活（qemu 占 3GB）；重试即恢复。**不是崩溃** |
| 2 | 上次"崩溃"的重新认识 | 上次判断"进程消失"时进程计数为 0，但本次证明 adb 掉线很容易被误判成崩溃。上次是否为真崩溃**已无法确证**（日志尾部只有 IPv6 消息，无崩溃记录） |
| 3 | `config.ini` 被 emulator 重写 | 改 `config.ini` 单独无效，必须同步改 `hardware-qemu.ini` |
| 4 | 权限页未逐项验证 | `PermissionHubActivity` 的 7 项四态展示未在设备上走完 → NOT TESTED |
| 5 | 引导页未走完 9 步 | 只验证了第 1 步 UI → 后续步骤 NOT TESTED |
| 6 | 定位器未接入主流程 | 目前只有只读自检入口；`AssistantTaskRunner` 尚未调用它 → 真实问答场景下的定位未验证 |
| 7 | 语音/联网 | 缺真实凭据 + 模拟器无麦克风 → 未验证 |
| 8 | 真机（EMUI 12） | 本轮未连接，Android 11 上的表现未复验 |

---

## 八、结论

**首要目标达成**：模拟器**已恢复**，最新 APK **已安装**，应用**已实际运行并验证**。

- 模拟器故障原因**已证实**且**已用最小变量修复**（改 dataPartition 3G，未删任何 AVD/userdata）
- **配置存储改造（本轮核心）5 项全部 PASS**，且有设备日志与文件内容双重证据
- **定位器 5 个安全场景全部 PASS**，且严格遵守"不点击"约束
- uiDump 新字段 **PASS**
- 语音/联网**如实标注未验证**，未把"代码存在"说成"验证通过"

> 一句话：**配置层和定位层这次是真的在设备上跑通并验证了；语音和联网因为缺真实凭据，还没到那一步。**

---

# 【P0 安全闭环轮补充】2026-10-11 14:00

## 本轮模拟器使用情况

| 步骤 | 结果 |
|---|---|
| `adb reconnect offline` 后 kill-server/start-server | ✅ 恢复（`emulator-5554 device`） |
| 装机最新 APK（含 D-01/D-06 修复） | ✅ Success |
| 无障碍重新启用 | ✅ `Bound services` count=1 |
| 定位器只读自检回归 | ✅ `命中 (360,453) 置信度=1.0` |
| 坐标校验回归 | ✅ 越界拒绝 |
| **任务路径验证（D-01/D-06）** | ❌ **BLOCKED** |

## 阻塞原因（具体证据）

1. **模拟器无网络**：`ping -c 1 -W 2 8.8.8.8` → `1 packets transmitted, 0 received, 100% packet loss`
2. **无真实 API Key**：配置里是测试用假 Key（`TEST_KEY_NOT_REAL` + `https://test.invalid`），
   按安全纪律**不注入真实凭据**
3. 两条叠加 → `AssistantTaskRunner` 无法启动 → "任务授权上下文"与"读屏指标"日志无法产生

**未做的事（刻意）**：
- 未删除 AVD / userdata
- 未重置模拟器
- 未尝试用真实界面做自动点击验证（用户明确要求本轮不启用）

## 模拟器稳定性观察（本轮）

- 出现 1 次瞬时 `device offline`（在连续 adb 命令之间），**重连即恢复**
- **未**据此判定模拟器崩溃 —— 符合用户要求"不因 ADB 瞬时 offline 就直接判定模拟器崩溃"
- 进程始终存活，AVD 与 userdata 完好

---

# 【离线安全验证闭环轮补充】2026-10-11 15:00

## 本轮模拟器操作

| 步骤 | 结果 |
|---|---|
| `adb devices` 显示 offline | ⚠️ 显示滞后（**进程活着，1 个 qemu**） |
| 未据此判定崩溃 | ✅ 后续命令全部成功，证明是显示问题 |
| 装机（含 D-07~D-11 修复） | ✅ `Success` |
| 无障碍重新启用 | ✅ `Bound services` count=1 |
| 定位器只读自检 | ✅ `命中 坐标=(360,453) 置信度=1.0` |
| 坐标校验 | ✅ `[ERR] 坐标 (-1,-1) 超出屏幕范围 720x1280，已拒绝执行` |
| **真实点击** | ❌ **NOT TESTED**（按用户要求未启用） |

## 未做的事（刻意遵守安全约束）

- 未删除 AVD / userdata
- 未使用 `-wipe-data`
- 未连接真实 API Key
- 未操作任何敏感应用
- 未启用真实自动点击
- 未修改原 DSHA 主仓库

## 关于 `adb devices` 显示 offline

本轮再次遇到：`adb devices` 报 `offline`，但**同一轮里后续所有 adb 命令都成功**。
结论：**软件渲染下 `adb devices` 的输出存在滞后**，
判定"模拟器是否崩溃"应看**进程是否存在 + 命令是否真的失败**，
不能只看这一行输出（这也是用户明确提醒过的）。
