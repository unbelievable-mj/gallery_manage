# 滑图 · SwipeGallery 开发计划

> 版本：v1.2（M0 已落地）
> 日期：2026-09-21
> 目标平台：Android 11+（API 30）
> 开发目录：`/Users/haoli/Desktop/work/Code/projects/gallery-manage`
> 仓库：`gallery_manage`（Public）· 应用名「滑图」· 包名 `com.haoli.swipegallery`

---

## 0. 产品定位

参考 iOS 系统「照片」App 的浏览体验，做一款**以「快速过筛」为核心**的本地相册管理工具：用户在全屏查看器里逐张处理海量图片/视频 —— **上滑删除、下滑保留、滑走自动下一张**，并实时看到「已处理 / 总数」进度。所有破坏性操作都可撤销。

### 0.1 核心交互语义（已确认）

| 手势 | 语义 | 落点 |
| --- | --- | --- |
| **上滑** | 删除 | 系统回收站（两阶段提交，见 §3.5） |
| **下滑** | **保留** | 默认：文件零改动，仅从本次处理队列移除；可选增强：移动到指定相册（§3.2） |
| **左右滑** | 翻页 | 上/下一张 |
| **未达阈值** | 回弹 | 不做任何操作 |

这是一次**纯粹的二选一过筛**：删 or 留。没有中间态，没有二级菜单。

### 0.2 与普通相册 App 的差异

| 维度 | 普通相册 App | 相册管家 |
| --- | --- | --- |
| 交互 | 网格 → 选中 → 菜单 → 删除 | 全屏滑卡，一次手势完成一个决策 |
| 心智 | 「管理文件」 | 「过筛内容」 |
| 进度感 | 无 | 常驻进度条 + 已处理/总数 |
| 容错 | 回收站（需跳转） | 三张缓存 + 多级撤销 + 快捷撤回 |
| 视频 | 与图片割裂 | 与图片同级，顶部一键切换 |

---

## 1. 开源项目调研与复用策略

### 1.1 候选项目

| 项目 | 技术栈 | 许可证 | 可复用价值 | 结论 |
| --- | --- | --- | --- | --- |
| [FossifyOrg/Gallery](https://github.com/FossifyOrg/Gallery) | Kotlin + Compose，`org.fossify.commons` 公共库，Gradle 9.4.1 / AGP 9.2.0，Fastlane 发布 | GPL-3.0 | ★★★★★ 回收站、多格式支持、MediaStore 删除、EXIF 剥离的实现思路最贴近本项目 | **参考思路，不 fork** |
| [IacobIonut01/Gallery (ReFra)](https://github.com/IacobIonut01/Gallery) | Kotlin 2.3.20 + AGP 9.1.0，Hilt 2.59 / Room 2.8.4 / Media3 1.10 / Navigation 2.9.7 / DataStore 1.2.1，多模块 + ADR 文档 | GPL 系 | ★★★★☆ 工程化最成熟：多模块拆分、Baseline Profile、版本目录、CI 结构可借鉴 | **参考工程结构** |
| [deckerst/aves](https://github.com/deckerst/aves) | Kotlin + Compose | GPL-3.0 | ★★★☆☆ 元数据解析、EXIF 处理 | 参考 |
| [googol-apps/Gallery](https://github.com/googol-apps/Gallery) | Kotlin + Compose，轻量 | 需核对 | ★★☆☆☆ 轻量网格/查看器 | 参考 |

### 1.2 复用决策（已确认）

**自研主体，许可证 MIT 或 Apache-2.0。**

- ✅ 阅读 GPL 项目源码理解 `MediaStore` 删除兼容层、回收站表结构、权限流、`ExifInterface` 用法，用自有代码实现
- ✅ 复用其版本号选型、CI workflow 结构、`detekt` / `lint` 配置（配置本身不受版权保护）
- ❌ 不复制 GPL 源码文件，不 fork 后改名发布（会触发传染性开源义务）

好处：许可证干净，将来想闭源、想上架 Google Play、想接入商业服务都不受限。

---

## 2. 整体架构与技术选型

### 2.1 技术选型（版本以 2026-09 官方稳定版为准）

| 类别 | 选型 | 版本（已锁定） | 说明 |
| --- | --- | --- | --- |
| 语言 | Kotlin | **2.4.10** | 由 AGP 内置 Kotlin 承载，不再单独声明 `kotlin.android` 插件 |
| 构建 | Gradle / AGP | **9.5.0 / 9.3.2** | AGP 9.3.2 要求 Gradle ≥ 9.5、JDK ≥ 17 |
| JDK | Toolchain | **21** | CI 与本地统一 |
| UI | Jetpack Compose + Material3 | Compose BOM **2026.09.00** | 内含 Compose 1.12.1 + Material3 1.4.0 |
| 导航 | navigation-compose | 2.9.8 | 类型安全路由 |
| 架构 | 多模块 Clean Architecture + MVVM | — | 单向数据流 `StateFlow<UiState>` |
| DI | Hilt（KSP） | 2.60.1 / KSP 2.3.11 | KSP 已改为独立版本线，不再与 Kotlin 版本绑定 |
| 异步 | Coroutines + Flow | 1.11.0 | — |
| 主数据源 | **MediaStore**（ContentResolver 查询） | 平台 API | 不自行扫描文件系统 |
| 本地库 | Room | 2.8.4 | 待删队列、回收站索引、保留记录、操作栈 |
| 偏好 | DataStore Preferences | 1.2.1 | 缓存张数、排序、下滑目标、删除策略 |
| 分页 | Paging 3 | 3.5.1 | 万级媒体库网格 |
| 图片加载 | **Coil 3** + 自定义 Fetcher | 3.6.3 | 图片走 `loadThumbnail`，视频抽帧走 `MediaMetadataRetriever` |
| 视频播放 | **Media3 ExoPlayer** | 1.11.0 | 预览播放、音频焦点 |
| 后台任务 | WorkManager | 2.11.2 | 待删队列兜底提交、缩略图预热 |
| 日志 | Timber | 5.0.1 | Debug 树 / Release 静默 |
| 测试 | JUnit4、MockK、Turbine、Robolectric、Compose UI Test | — | 单元 / UI / 性能三层 |
| 图表 | 自绘 Compose Canvas | — | 不引重型图表库，控制包体积 |

> **版本锁定策略**：所有版本均从 Maven 仓库探测真实可用版本后确定，并与一个当前正在 CI 中成功构建的开源项目（IacobIonut01/Gallery）对齐。这不是保守，而是必要——AGP 9 引入了内置 Kotlin 支持，`org.jetbrains.kotlin.android` 与新 DSL 不兼容，KSP 也已改为独立版本线，凭经验写版本号必然失败。详见 README「关于版本锁定」。

**SDK 版本（已确认）**

```
compileSdk = 37        // AGP 9.3.2 支持上限 API 37
targetSdk  = 37
minSdk     = 30        // Android 11+，删除逻辑只需一套
```

`minSdk = 30` 的收益：`createTrashRequest` / `createDeleteRequest` / `createWriteRequest` 全部可用，**不需要** `<29` 直删分支、`29` 的 `RecoverableSecurityException` 捕获分支，也不需要 `WRITE_EXTERNAL_STORAGE`。删除相关工作量显著下降。

### 2.2 模块划分

「1 壳 + 4 core + 5 feature」，避免模块过多拖慢构建：

```
gallery-manage/
├─ app/                    壳工程：Application、导航宿主、DI 装配、MainActivity
├─ core/
│  ├─ common/              工具、Result、Dispatcher、格式化（文件大小/时间）
│  ├─ model/               领域模型：MediaItem / Album / MediaType / TriageAction / PendingDelete
│  ├─ designsystem/        主题、色板、动态取色、通用组件（SegmentedTab / ProgressBar / SwipeCard）
│  └─ data/                MediaStore 数据源 + Room + DataStore + Repository
│     ├─ media/            查询、缩略图、相册聚合、删除/写入/回收站兼容层（★核心）
│     └─ repository/       MediaRepository / TrashRepository / StatsRepository
└─ feature/
   ├─ browse/              图片/视频切换、网格、相册列表与范围选择、排序
   ├─ viewer/              全屏查看器 + 滑卡处理（★核心）
   ├─ trash/               回收站、撤销栈
   ├─ stats/               存储占用统计
   └─ settings/            设置
```

依赖方向严格单向：`feature/* → core/data → core/model`；`feature` 之间不互相依赖。

### 2.3 分层与数据流

```
        ┌──────────────── UI (Compose) ────────────────┐
        │  BrowseScreen  ViewerScreen  TrashScreen ... │
        └───────────────────┬──────────────────────────┘
                            │ UiState (StateFlow) / UiEvent
        ┌───────────────────▼──────────────────────────┐
        │  ViewModel（MVI：intent → reduce → state）    │
        └───────────────────┬──────────────────────────┘
                            │ suspend / Flow
        ┌───────────────────▼──────────────────────────┐
        │  Repository（唯一数据出入口）                  │
        │   ├─ MediaStoreDataSource  ← 系统媒体库       │
        │   ├─ MediaOperator         ← 删除/写入/回收站  │
        │   └─ Room DAO / DataStore  ← 本地状态         │
        └──────────────────────────────────────────────┘
```

**关键设计约束**：所有删除/保留/移动都必须经过 `MediaOperator`，它在内部维护**操作日志（ActionLog）**——这是「撤销」能力的唯一来源。UI 层永远不直接调用 `ContentResolver.delete()`。

---

## 3. 主要功能模块

### 3.1 媒体浏览（图片 / 视频切换）

- **顶部切换**：`SegmentedTab`，选项「图片 / 视频」（可选第三项「全部」）。切换即替换数据集并重置滚动位置。
- **数据源**：
  - 图片：`MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)`
  - 视频：`MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)`
  - 投影列：`_ID, DISPLAY_NAME, DATE_TAKEN, DATE_ADDED, DATE_MODIFIED, SIZE, WIDTH, HEIGHT, DURATION, MIME_TYPE, BUCKET_ID, BUCKET_DISPLAY_NAME, RELATIVE_PATH, IS_TRASHED, IS_FAVORITE`
  - 过滤：`IS_TRASHED = 0 AND IS_PENDING = 0`
- **网格**：默认 3 列，可调 2–5 列；Paging 3 增量加载；滚动时用 `contentType` 复用。
- **缩略图**：
  - 图片：`ContentResolver.loadThumbnail(uri, Size, CancellationSignal)`
  - 视频：`MediaMetadataRetriever.getFrameAtTime()` 抽帧并落盘缓存（避免重复解码）
- **时间线**：按 `DATE_TAKEN`（缺失回退 `DATE_ADDED`）分天/分月分组 + 右侧快速滚动条（scrubber）。
- **视频角标**：右下角显示时长 `mm:ss`；Live Photo / GIF / RAW 加类型角标。

### 3.2 相册接入与处理范围（新增，对应你的需求）

**读取本机已有相册**：按 `BUCKET_ID` 聚合 `MediaStore`，得到相册列表（封面 + 名称 + 数量 + 占用空间）。

**处理范围（进入滑卡模式前选择）**：

| 范围 | 说明 | 下滑「保留」的落点 |
| --- | --- | --- |
| 全部图片 / 全部视频 | 处理整个媒体库 | 保留在原相册（文件零改动） |
| 指定相册 | 只处理某个相册内的媒体 | 保留在原相册（文件零改动） |
| 时间范围 | 如「本月」「2024 年」 | 同上 |
| 大小筛选 | 如「> 10MB」 | 同上 |

**下滑目标可在设置中改写**（默认保持「保留在原处」）：

| 档位 | 行为 | 权限成本 |
| --- | --- | --- |
| **保留在原相册（默认）** | 仅写 Room 保留记录 + 移出本次队列，文件零改动 | 无 |
| 移动到指定相册 | 修改 `MediaColumns.RELATIVE_PATH` | 首次需 `createWriteRequest` 授权 |
| 复制到自选目录 | 通过 SAF 目录选择器指定目标，原文件保留 | 一次 SAF 授权，占双份空间 |
| 标记为收藏 | `createFavoriteRequest` 或写 `IS_FAVORITE` | 首次需授权 |

**排序（用户可自选，核心需求）**：

| 字段 | 依据列 | 默认方向 |
| --- | --- | --- |
| 拍摄/创建时间 | `DATE_TAKEN` → `DATE_ADDED` | 新 → 旧 |
| 修改时间 | `DATE_MODIFIED` | 新 → 旧 |
| **文件占用空间** | `SIZE` | 大 → 小 |
| 文件名 | `DISPLAY_NAME` | A → Z |
| 时长（视频） | `DURATION` | 长 → 短 |

排序结果直接决定滑卡模式的队列顺序 —— 先按大小排序再逐张过筛，是清理空间最有效的路径。

### 3.3 全屏查看器（★核心交互）

- **翻页**：`HorizontalPager`，左右滑动切换上/下一张。
- **手势仲裁**（详见 §3.6）：
  - **上滑 → 删除**（阈值：位移 > 30% 屏高 **或** 速度 > 1200 px/s）
  - **下滑 → 保留**
  - **左右滑 → 翻页**
- **滑出反馈**：卡片跟随手指位移 + 轻微旋转 + 图标渐显（删除=红底垃圾桶，保留=绿底书签），越过阈值时图标放大并触发轻震动（`HapticFeedback`）。
- **自动下一张**：滑出动画结束后从数据集中移除该项并保持 Pager 位置稳定（移除后 `currentPage` 不回跳）。
- **视频预览**：Media3 ExoPlayer 嵌入 Compose（`AndroidView`），进入页面自动播放（可配置是否带声音），离开时 `pause()` + `release()`；音频焦点用 `AudioAttributes` + `setAudioAttributes(..., handleAudioFocus = true)`。
- **底部信息栏（概览信息）**：
  ```
  ┌──────────────────────────────────────────────┐
  │  📷 拍摄于 2024-08-12 15:32                  │
  │  📦 4.7 MB  ·  4032 × 3024  ·  相册：相机     │
  └──────────────────────────────────────────────┘
  ```
  字段：拍摄/创建时间、文件大小（自动 B/KB/MB/GB）、分辨率、视频时长、所在相册、路径（可折叠）。信息栏可上滑收起以全屏看图。
- **顶部进度**：
  ```
  ┌──────────────────────────────────────────────┐
  │  ✕   已处理 128 / 2,431   ▓▓▓░░░░░░░░   5%    │
  │  已删 42 · 已保留 86        [撤销] [回收站]    │
  └──────────────────────────────────────────────┘
  ```
  进度 = 已决策数 / 队列总数；同时显示「已删 N · 已保留 M」与「本次已释放空间」。
- **辅助**：双击/双指缩放、单击显隐 UI、长按进入多选。

### 3.4 滑卡缓存与撤销体系（★核心）

**① 预加载缓存（默认 3 张，可配 1–10）**

- `PrefetchCache`：以当前索引为中心，预取 `N-1, N, N+1`。
- 两级缓存：内存 `LruCache<Uri, Bitmap>`（上限 `maxMemory / 8`）+ 磁盘缩略图缓存（Coil 3 磁盘缓存，视频抽帧结果单独落盘）。
- 视频额外预加载：`ExoPlayer` 实例预热（`prepare()` 但不 `play()`），翻页秒出画面。
- 方向感知：连续上滑时提高向后预取优先级，连续下滑时同理。

**② 操作栈（ActionStack）—— 多级撤销**

```kotlin
sealed interface TriageAction {
    val id: Long
    val timestamp: Long
    data class Deleted(val item: MediaItem) : TriageAction
    data class Kept(val item: MediaItem, val target: KeepTarget) : TriageAction
    data class Moved(val item: MediaItem, val fromAlbum: String, val toAlbum: String) : TriageAction
}

class ActionStack(maxDepth: Int = 50) {
    fun push(action: TriageAction)
    fun undo(): TriageAction?                    // 弹出并返回待撤销项
    fun peek(): TriageAction?
    fun undoAllInSession(): List<TriageAction>   // 撤销本次会话全部
}
```

连续上滑 5 张 → 连按撤销 5 次可逐张恢复；也支持一键撤销本次会话全部操作。

**③ 快捷撤回入口（三重）**

1. 底部 `Snackbar`：「已删除 1 项 · 撤销」（窗口内可点）
2. 右上角常驻 `撤销` 按钮（可连续点击，退栈）
3. `回收站` 页面：完整列表，可单张/批量还原或彻底删除

### 3.5 删除流水线：两阶段提交（★关键设计）

你的选择是「系统回收站」，但系统回收站是**异步 + 需用户确认 + 瞬时不可逆**的，与「滑走即可撤销」直接冲突。解法是**两阶段提交**：

```
【阶段一 · 本地，零延迟，完全可逆】
用户上滑
  ↓
① 立即从 UI 列表移除（乐观更新）
② 写入 Room `pending_delete` 表（uri / 原始位置 / 大小 / 时间戳）
③ 文件本体与系统媒体库「完全不动」
  ↓
撤销窗口内（默认 10 秒）用户点撤销
  → 删除 Room 记录 → 列表项瞬时回位 → 无任何系统调用 ✅

【阶段二 · 系统，批量，触发式】
触发条件（满足任一）：
  · 撤销窗口过期
  · 待删队列达到批量阈值（默认 20 张）
  · 用户点「立即清理」
  · 退出滑卡模式 / 应用退到后台超过 N 分钟
  ↓
调用 MediaStore.createTrashRequest(resolver, uris, isTrashed = true)
  → 系统一次性确认框（已授予 MANAGE_MEDIA 则无框）
  ↓
成功后写入 Room `trashed` 表，记录系统回收站条目与过期时间

【阶段二之后的撤销】
调用 MediaStore.createTrashRequest(resolver, uris, isTrashed = false)
  → 从系统回收站还原（同样受 MANAGE_MEDIA 影响）
  → 失败则引导用户到系统相册回收站手动还原
```

**为什么必须引导开启 MANAGE_MEDIA**：没有这个特殊权限，每次批量删除都会弹出系统确认框，滑卡的连续节奏会被硬打断。引导页应把它作为「强烈推荐开启」，并解释清楚用途。

**兜底**：WorkManager 每日任务扫描 `pending_delete` 与 `trashed` 表，处理未完成的提交、同步系统回收站状态（`IS_TRASHED` 查询）、清理已过期条目。

### 3.6 手势仲裁（技术难点）

- 用 `pointerInput + awaitPointerEventScope` 自行仲裁，**不依赖** `detectVerticalDragGestures` 的默认行为。
- 判定顺序：
  1. 手指移动超过 `touchSlop` 后，比较 `abs(dy)` 与 `abs(dx)`
  2. `abs(dy) > abs(dx)` → 锁定为垂直决策，**不把事件交给 Pager**
  3. 否则 → 锁定为水平翻页，垂直手势不再响应
  4. 锁定后中途反向不切换（避免抖动误判）
- 阈值：位移 > 30% 屏高 **或** 速度 > 1200 px/s（可配置灵敏度）。
- 未达阈值 → 弹性回弹复位。
- 视频区域内的缩放/拖动需要额外优先级处理（缩放中不触发滑卡）。

### 3.7 存储占用统计

- 总占用 / 图片占用 / 视频占用；按相册 Top N 排行。
- 大小分档直方图：`<1MB`、`1–5MB`、`5–20MB`、`>20MB`（Compose Canvas 自绘）。
- 「本次已释放空间」实时累计（滑卡模式顶部显示）。
- 设备剩余空间（`StatFs`）。

### 3.8 搜索（M8 可选）

- 文件名 / 相册名 / 日期范围 / 大小范围 / 类型。
- 先做内存 + Room 索引的即时过滤；后续可选接入 ML Kit 图像标签（注意包体积与 Play 政策）。

### 3.9 设置项

缓存张数（默认 3）、**下滑目标（默认保留在原相册）**、默认处理范围、默认标签页、默认排序字段与方向、滑动灵敏度、是否二次确认、批量阈值、撤销窗口时长、视频自动播放与是否静音、主题（跟随系统/深色/浅色/动态取色）、MANAGE_MEDIA 权限状态与引导入口。

---

## 4. GitHub CI/CD 自动构建与发布设计

### 4.1 分支与版本策略

- `main` 为保护分支，PR 必须通过 CI 才可合并。
- 功能分支 `feat/*`、修复 `fix/*`。
- **版本号**：SemVer。`versionName` 取自 git tag（去掉 `v` 前缀），`versionCode` 由 CI 生成（`run_number` 或提交数，保证单调递增）。
- **发布触发**：推送 tag `v*` → 自动构建签名产物 → 创建 GitHub Release。
- 预发布：tag 含 `-beta.1` 等后缀 → 标记为 Pre-release。

### 4.2 Workflows 清单

| 文件 | 触发 | 内容 |
| --- | --- | --- |
| `.github/workflows/ci.yml` | `pull_request`、`push: main` | ① `ktlintCheck` + `detekt` ② `testDebugUnitTest`（含 JaCoCo）③ `assembleDebug` ④ 上传 test report + debug APK artifact ⑤ `concurrency` 取消同分支旧任务 |
| `.github/workflows/release.yml` | `push: tags: v*` | ① 从 Secret 还原 keystore ② `assembleRelease` + `bundleRelease` ③ `apksigner verify` 校验签名 ④ 生成 changelog（`git log` 按 `feat`/`fix`/`perf` 分组）⑤ `softprops/action-gh-release` 上传 APK / AAB / `mapping.txt` ⑥（可选）上传 Google Play internal track |
| `.github/workflows/nightly.yml` | `schedule`（每日） | 构建 debug APK 作为 artifact，便于真机日常测试 |
| `.github/workflows/deps.yml` | `schedule`（每周） | Gradle Dependency Submission + 依赖漏洞扫描 |

### 4.3 关键 Action 与缓存（版本已核实）

以下均为撰写时各仓库的最新发布版本，通过 `git ls-remote` 直接读取 tag 列表确认：

- `actions/checkout@v7`
- `actions/setup-java@v6`（`distribution: temurin`、`java-version: 21`）
- `gradle/actions/setup-gradle@v6`（Gradle Build Cache，无需在 setup-java 中重复开启 cache）
- `android-actions/setup-android@v4` + 独立的 `sdkmanager --install` 步骤
- `actions/upload-artifact@v7`
- `softprops/action-gh-release@v3`

启用 `org.gradle.parallel=true` 与 `org.gradle.caching=true`。配置缓存（`configuration-cache`）暂不开启，先保证 M0 稳定，M1 起逐模块验证后再打开。

### 4.4 Secrets 与签名

| Secret | 说明 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | 密钥库（PKCS12）的 base64 编码 |
| `SIGNING_STORE_PASSWORD` | keystore 口令 |
| `SIGNING_KEY_ALIAS` | key 别名 |
| `SIGNING_KEY_PASSWORD` | key 口令 |

`app/build.gradle.kts` 中 `signingConfigs` 从**环境变量**读取，本地缺失时自动回退 debug 签名；`keystore/`、`*.p12`、`*.jks` 一律进 `.gitignore`。CI 中先 base64 解码写出临时 keystore 到 `$RUNNER_TEMP`，runner 销毁后自动消失，不进入任何缓存或产物。

签名密钥采用 PKCS12 格式（AGP 与 apksigner 均支持），有效期 30 年 —— Android 要求应用的签名证书长期有效，否则证书过期后无法发布更新。

### 4.5 质量门禁与产物

- **门禁**：单测全绿、`detekt` 无 error、Android Lint 无 error、Release 构建成功。
- **产物命名**：`GalleryManage-v1.2.0-release.apk` / `.aab` / `mapping-v1.2.0.txt`（便于 Obtainium 识别）。
- **构建优化**：R8 混淆 + 资源压缩 + Baseline Profile（首屏与滚动性能）。
- **Release Notes**：自动按 `feat` / `fix` / `perf` 分类生成。

---

## 5. 开发阶段与里程碑

> 相对工作量：S（小）/ M（中）/ L（大）。按单人全职节奏的量级估算。

| 里程碑 | 内容 | 工作量 | 交付验收 |
| --- | --- | --- | --- |
| **M0 立项与骨架** | 仓库初始化、版本目录、模块骨架、签名配置、CI 跑通（空壳也能出 APK）、Release 流程验证 | S | 推 tag 能自动产出可安装的签名 APK |
| **M1 数据层与浏览** | 分级权限申请、MediaStore 查询、Paging 网格、图片/视频切换 Tab、缩略图（图片 + 视频抽帧） | M | 能浏览本机全部图片与视频 |
| **M2 相册接入与范围选择** | 相册聚合列表、处理范围选择（全部/指定相册/时间/大小）、多字段排序、队列生成 | M | 能选定范围并按指定字段排序进入处理 |
| **M3 查看器与信息栏** | 全屏查看、左右翻页、Media3 视频播放、底部信息栏（时间/大小/分辨率）、顶部进度 | M | 能逐张查看并预览视频 |
| **M4 核心滑动手势** ★ | 上滑删除/下滑保留、主轴仲裁、滑出动画与触觉反馈、滑走自动下一张、位置稳定性 | L | 可连续快速过筛 |
| **M5 待删队列与撤销** ★ | 三张预加载缓存、ActionStack 多级撤销、Snackbar 快捷撤回、Room 待删/保留表 | L | 误删可无损恢复 |
| **M6 系统回收站集成** | 两阶段提交、批量 `createTrashRequest`、`MANAGE_MEDIA` 引导、系统回收站还原、WorkManager 兜底 | M | 真实落盘删除 + 系统回收站还原 |
| **M7 统计与设置** | 存储占用图表、本次释放空间累计、设置项全量、回收站页面完善 | M | 完整管理能力 |
| **M8 打磨与首发** | 性能优化（Baseline Profile、大列表、视频内存）、深浅色/动态主题、无障碍、首发 `v1.0.0` | M | 正式 Release |
| **M9 增强（可选）** | 搜索、相似照片/重复检测、清理建议、标签、云备份 | L | — |

**关键路径**：M0 → M1 → M2 → **M4 → M5 → M6**。

M4/M5/M6 是产品灵魂，建议在 M2 之后立即进入，不要被统计、搜索等次要功能插队。M6 的系统回收站交互最不可控（受系统版本、厂商 ROM、权限状态影响），建议留出真机多机型验证时间。

**建议的验证节奏**：每个里程碑结束推送一个 `v0.x.0-alpha.n` tag，走一遍完整 CI/CD，确保发布链路始终可用（而不是等到 M8 才发现签名或 CI 有问题）。

---

## 6. 关键依赖与权限

### 6.1 依赖清单（`gradle/libs.versions.toml`）

```toml
[versions]
kotlin                = "2.4.x"
agp                   = "9.4.x"
ksp                   = "<与 kotlin 对应>"
composeBom            = "2026.09.00"
coreKtx               = "1.17.x"
lifecycle             = "2.9.x"
activityCompose       = "1.11.x"
navigationCompose     = "2.9.x"
hilt                  = "2.59.x"
room                  = "2.8.x"
datastore             = "1.2.x"
paging                = "3.3.x"
coil                  = "3.x"
media3                = "1.10.x"
work                  = "2.10.x"
coroutines            = "1.9.x"
timber                = "5.x"
detekt                = "2.x"
ktlint                = "1.x"

[libraries]
# --- AndroidX 基础 ---
androidx-core-ktx            = { module = "androidx.core:core-ktx" }
androidx-lifecycle-runtime   = { module = "androidx.lifecycle:lifecycle-runtime-compose" }
androidx-lifecycle-viewmodel = { module = "androidx.lifecycle:lifecycle-viewmodel-compose" }
androidx-activity-compose    = { module = "androidx.activity:activity-compose" }
androidx-navigation-compose  = { module = "androidx.navigation:navigation-compose" }

# --- Compose ---
androidx-compose-bom         = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui          = { module = "androidx.compose.ui:ui" }
androidx-compose-material3   = { module = "androidx.compose.material3:material3" }
androidx-compose-foundation  = { module = "androidx.compose.foundation:foundation" }

# --- DI / 数据 ---
hilt-android                 = { module = "com.google.dagger:hilt-android" }
hilt-compiler                = { module = "com.google.dagger:hilt-android-compiler" }
hilt-navigation-compose      = { module = "androidx.hilt:hilt-navigation-compose" }
room-runtime                 = { module = "androidx.room:room-runtime" }
room-ktx                     = { module = "androidx.room:room-ktx" }
room-compiler                = { module = "androidx.room:room-compiler" }
datastore-preferences        = { module = "androidx.datastore:datastore-preferences" }
paging-compose               = { module = "androidx.paging:paging-compose" }

# --- 媒体 ---
coil-compose                 = { module = "io.coil-kt.coil3:coil-compose" }
coil-video                   = { module = "io.coil-kt.coil3:coil-video" }
media3-exoplayer             = { module = "androidx.media3:media3-exoplayer" }
media3-ui-compose            = { module = "androidx.media3:media3-ui-compose" }
media3-common                = { module = "androidx.media3:media3-common" }

# --- 后台 / 工具 ---
work-runtime-ktx             = { module = "androidx.work:work-runtime-ktx" }
kotlinx-coroutines-android   = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android" }
timber                       = { module = "com.jakewharton.timber:timber" }

# --- 测试 ---
junit                        = { module = "junit:junit" }
mockk                        = { module = "io.mockk:mockk" }
turbine                      = { module = "app.cash.turbine:turbine" }
robolectric                  = { module = "org.robolectric:robolectric" }
androidx-test-core           = { module = "androidx.test:core-ktx" }
compose-ui-test-junit4       = { module = "androidx.compose.ui:ui-test-junit4" }
benchmark-macro-junit4       = { module = "androidx.benchmark:benchmark-macro-junit4" }
```

> 具体小版本在 M0 阶段用 `./gradlew dependencyUpdates` 或 Android Studio 的 AGP 升级助手锁定一次。

### 6.2 权限清单（AndroidManifest.xml）

因 `minSdk = 30`，权限集大幅简化：

```xml
<!-- 读取媒体：Android 13+ 用细粒度权限 -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />              <!-- API 33+ -->
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />               <!-- API 33+ -->
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" /><!-- API 34+ -->
<!-- Android 12 及以下（30–32）回退用 -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
                 android:maxSdkVersion="32" />

<!-- 免弹窗删除（API 31+，需用户到系统设置授予特殊权限）—— 强烈推荐 -->
<uses-permission android:name="android.permission.MANAGE_MEDIA" />

<!-- 可选：读取原始 EXIF 定位信息 -->
<uses-permission android:name="android.permission.ACCESS_MEDIA_LOCATION" />

<!-- 可选：后台任务通知 -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

**已删除**：`WRITE_EXTERNAL_STORAGE`（minSdk 30 不需要）、`requestLegacyExternalStorage`（仅 API 29 需要）、`FOREGROUND_SERVICE`（除非后续做大文件复制）。

其他 Manifest 配置：`FileProvider`（分享用）、`<queries>`（跳转系统相册回收站）。

### 6.3 权限申请策略

| 系统版本 | 读取权限 | 删除方式 |
| --- | --- | --- |
| Android 11 (30) | `READ_EXTERNAL_STORAGE` | `createTrashRequest` / `createDeleteRequest` |
| Android 12 (31–32) | `READ_EXTERNAL_STORAGE` | 同上；**可申请 `MANAGE_MEDIA` 免弹窗** |
| Android 13 (33) | `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO`（一次合并弹窗） | 同上 |
| Android 14+ (34+) | 额外支持「仅选择部分照片」`READ_MEDIA_VISUAL_USER_SELECTED`，需处理部分授权态 | 同上 |

**引导设计**：首启引导页 → 说明用途 → 请求读取权限 → 引导开启 `MANAGE_MEDIA`（`Settings.ACTION_REQUEST_MANAGE_MEDIA`）。被拒绝读取权限时降级到系统 Photo Picker（仅能处理用户选中的项）；被永久拒绝时引导到系统设置。

---

## 7. 决策记录

### 7.1 已确认

| # | 决策点 | 结论 |
| --- | --- | --- |
| 1 | 下滑「保存」的语义 | **保留在原相册**（文件零改动，仅移出本次队列）。设置中可改为移动到指定相册。处理范围支持「全部媒体」或「指定相册」。 |
| 2 | 删除默认档位 | **系统回收站**（`createTrashRequest`），通过两阶段提交实现可撤销（§3.5） |
| 3 | minSdk | **30（Android 11+）** |
| 4 | 复用方式与许可证 | **自研主体，MIT / Apache-2.0**；GPL 项目仅参考思路 |

### 7.2 已确认（第二轮）

| # | 决策点 | 结论 |
| --- | --- | --- |
| 5 | 发布渠道 | **仅 GitHub Release**。手机直接访问 `releases/latest` 下载 APK 安装，不走 Google Play |
| 6 | App 名称与包名 | **滑图 / SwipeGallery**，包名 `com.haoli.swipegallery`，仓库名 `gallery_manage`（账号 `unbelievable-mj`） |
| 7 | 仓库可见性 | **Public** —— Actions 分钟数不限，且手机端下载 Release 无需登录 GitHub |
| 8 | 本地构建环境 | 不在本地安装 Android SDK，构建验证完全交给 CI |

---

## 8. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| **系统删除弹框打断滑卡节奏** | 核心体验受损 | 引导开启 `MANAGE_MEDIA`；未开启时用批量阈值把弹框频率降到最低 |
| **删除不可逆** | 数据永久丢失 | 两阶段提交 + 多级撤销 + 系统回收站 30 天兜底 |
| **厂商 ROM 差异** | 部分机型 `createTrashRequest` 行为不一致或不可用 | 真机多机型验证；失败时降级为 `createDeleteRequest` 或直接 `delete()`；错误码分类上报 |
| **手势冲突** | 上下滑与 Pager 左右滑互相干扰 | 主轴锁定 + 独立 `pointerInput` 仲裁 + 多机型验证 |
| **视频内存/性能** | 大视频抽帧 OOM、ExoPlayer 实例泄漏 | 限制并发解码数、`PlayerPool` 复用、离开页面立即 `release()` |
| **万级媒体库卡顿** | 网格滚动掉帧 | Paging 3 + Baseline Profile + 缩略图尺寸分级 |
| **CI 签名泄漏** | 密钥泄露 | keystore 只存 Secret、构建后删除临时文件、开启 Secret Scanning |
| **Google Play 权限政策** | `READ_MEDIA_IMAGES/VIDEO` 属敏感权限，上架需填写声明表并可能被驳回 | 首发走 GitHub Release；上架前准备权限使用说明与截图 |

---

## 9. M0 实施记录（已完成）

### 9.1 已落地内容

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| Gradle wrapper | ✅ | 9.5.0，带官方 SHA256 校验和，防止分发被篡改 |
| 版本目录 | ✅ | `gradle/libs.versions.toml`，25 个版本 / 41 个依赖 / 5 个插件，全部经 Maven 探测确认存在 |
| 模块骨架 | ✅ | 5 个模块：`app` + `core:{model, common, designsystem, data}` |
| 依赖注入 | ✅ | Hilt 2.60.1 + KSP 2.3.11，真实注入链：ViewModel → MediaRepository（跨模块） |
| 单元测试 | ✅ | `core:common` 的格式化工具，8 个用例覆盖边界值与异常 |
| 签名配置 | ✅ | 从环境变量读取，本地无密钥时回退 debug 签名 |
| 签名密钥 | ✅ | `keystore/release.p12`（PKCS12，RSA 4096，30 年有效期），已被 `.gitignore` 排除 |
| Secrets 脚本 | ✅ | `scripts/generate-keystore.sh`、`scripts/print-signing-secrets.sh` |
| CI 流水线 | ✅ | `.github/workflows/ci.yml` |
| 发布流水线 | ✅ | `.github/workflows/release.yml` |
| 文档 | ✅ | `README.md`（含首次配置全流程）、`LICENSE`（MIT） |

### 9.2 与计划的偏差（有意为之）

| 计划 | 实际 | 原因 |
| --- | --- | --- |
| AGP 9.4.x + Gradle 9.6 | **AGP 9.3.2 + Gradle 9.5.0** | 本地无法编译验证，因此选用一个有真实项目在 CI 中成功构建的组合。落后一个小版本是可接受的代价 |
| M0 建立 10 个模块 | **建立 5 个** | `feature/*` 模块在 M1 起有真实内容时再建，避免空模块拖慢构建 |
| ktlint + detekt 作为门禁 | **暂缓** | detekt 稳定版（1.23.8）尚不支持 Kotlin 2.4；新版 `dev.detekt` 仅有 alpha。M1 代码成型后再评估，先用 Android Lint 占位 |
| `versionCode` 由提交数生成 | **用 `GITHUB_RUN_NUMBER`** | 更简单且天然单调递增，直接满足 Android 覆盖安装的要求 |

### 9.3 已验证项（本地无 Android SDK，故为静态验证）

- TOML 与两个 workflow YAML 语法解析通过
- 40 个 `libs.*` 依赖引用全部指向版本目录中真实存在的条目
- 6 个 `project(":...")` 模块引用全部指向 `settings.gradle.kts` 中已包含的模块
- 密钥库可被 openssl 正确读取，含私钥
- Gradle 9.5.0 分发校验和与官方值一致

### 9.4 首次 CI 的预期风险点

按可能性从高到低排列，遇到时按此顺序排查：

1. **`platforms;android-37` 是否可下载** —— 若报错，把 `compileSdk`/`targetSdk` 降到 36 即可
2. **R8 混淆** —— CI 已在 PR 阶段就跑 `assembleRelease`，问题会在 PR 而非发版时暴露；应急手段是把 `isMinifyEnabled` 临时设为 `false`
3. **Hilt 与 AGP 9 内置 Kotlin 的配合** —— 已对齐真实项目配置，风险较低
4. **Compose BOM 2026.09.00 与 Kotlin 2.4.10 的编译器匹配** —— 若报 Compose 编译器版本错误，把 Kotlin 提到 2.4.20

---

## 10. 下一步

M0 收尾动作：

- [x] 本地提交（`5f084c0`，43 个文件）
- [x] 在 GitHub 创建 Public 仓库 `unbelievable-mj/gallery_manage`
- [x] 配置远程地址（`git@github-mj:unbelievable-mj/gallery_manage.git`）
- [ ] 推送 `main` 分支
- [ ] 按 README 配置 4 个签名 Secret
- [ ] 打标签触发首次发版：`git tag v0.1.0 && git push origin v0.1.0`
- [ ] 观察 Actions 是否全绿。若失败，按 §9.4 的顺序排查

M0 验收标准：**手机上能打开 `https://github.com/unbelievable-mj/gallery_manage/releases/latest`，下载并成功安装 APK，打开后看到版本号与构建类型。**

之后进入 M1：接入 MediaStore，读取真实媒体库并渲染网格。
