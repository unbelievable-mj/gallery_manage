# 滑图 · SwipeGallery

以「快速过筛」为核心的 Android 本地相册管理工具。打开即是全屏查看器，**上滑删除、下滑保留、滑走自动下一张**，全程显示「已处理 / 总数」进度，所有破坏性操作都可撤销。

> 当前状态：**M0 骨架已完成**。项目结构、依赖注入、签名打包、GitHub Release 自动发布链路已打通，媒体功能自 M1 起逐步实现。

---

## 手机端安装

打开发布页，下载最新的 `SwipeGallery-v*.apk` 直接安装：

```
https://github.com/unbelievable-mj/gallery_manage/releases/latest
```

首次安装需要在系统设置里允许「安装未知来源应用」。每个版本的 `versionCode` 由 CI 单调递增，新版本可以直接覆盖安装。

---

## 技术选型

| 类别 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.4.10 |
| 构建 | Gradle 9.5.0 + AGP 9.3.2 + JDK 21 |
| UI | Jetpack Compose（Compose BOM 2026.09.00）+ Material 3 |
| 架构 | 多模块 Clean Architecture + MVVM（单向数据流） |
| 依赖注入 | Hilt 2.60.1（KSP 2.3.11） |
| 数据源 | MediaStore（系统媒体库） |
| 本地存储 | Room 2.8.4 + DataStore 1.2.1 |
| 视频 | Media3 ExoPlayer 1.11.0 |
| 图片加载 | Coil 3.6.3 |
| 后台任务 | WorkManager 2.11.2 |
| SDK | minSdk 30 / targetSdk 37 / compileSdk 37 |

### 关于版本锁定

本项目的依赖版本**不是凭印象写的**，而是从 Maven 仓库探测真实可用版本后确定，并与一个当前正在 CI 中成功构建的开源项目（[IacobIonut01/Gallery](https://github.com/IacobIonut01/Gallery)）对齐。特别是以下几点容易踩坑：

- **AGP 9 内置 Kotlin 支持并默认启用**，因此顶层与模块的 `plugins {}` 中**不应**再出现 `org.jetbrains.kotlin.android` —— 它与 AGP 9 的新 DSL 不兼容。
- Compose 编译器仍需独立的 `org.jetbrains.kotlin.plugin.compose` 插件，版本与 Kotlin 版本一致。
- **KSP 已改为独立版本线**（`2.3.x`），不再使用 `2.4.10-2.0.x` 这种与 Kotlin 绑定的编号。
- `kotlinOptions` 已废弃，改用顶层 `kotlin { compilerOptions { ... } }` 块。
- **API 37 起 Android SDK 改用带小版本号的命名**：仓库里只有 `platforms;android-37.0` /
  `37.1` / `37.2`，**不存在**裸的 `platforms;android-37`。CI 中三个小版本全部安装，
  避免 AGP 解析歧义。
- **compileSdk 不能低于 37**：Compose 1.12.x、Lifecycle 2.11、core-ktx 1.19 等库通过 AAR
  元数据强制要求 `compileSdk >= 37`，降到 36 会在 `checkDebugAarMetadata` 阶段报
  「requires libraries and applications that depend on it to compile against version 37 or later」。

---

## 项目结构

```
gallery-manage/
├─ app/                      壳工程：Application、MainActivity、DI 装配
├─ core/
│  ├─ model/                 领域模型（MediaItem / SortSpec / TriageAction …）
│  ├─ common/                无 Android 依赖的工具（文件大小与时长格式化）
│  ├─ designsystem/          主题、配色、通用组件
│  └─ data/                  MediaStore 数据源 + Room + DataStore + Repository
├─ keystore/                 签名密钥（已 gitignore，不进仓库）
├─ scripts/                  密钥生成与 Secrets 输出脚本
├─ docs/DEVELOPMENT_PLAN.md  完整开发计划
└─ .github/workflows/        CI 与 Release 流水线
```

依赖方向严格单向：`app → core:data → core:model`。UI 层永远不直接调用 `ContentResolver`，所有媒体操作都必须经过 `core:data` 里的 Repository。

> M0 只建立了上述 5 个模块。`feature/*` 模块（browse / viewer / trash / stats / settings）会在各自里程碑有真实内容时再建立，避免过早引入空模块拖慢构建。

---

## CI/CD 流程

### 持续集成（`.github/workflows/ci.yml`）

触发：推送到 `main`、任何 Pull Request。

1. 单元测试 `testDebugUnitTest`
2. Android Lint `lintDebug`
3. **构建 release 变体** —— 提前暴露 R8 混淆与资源压缩的问题，而不是等到发版才发现
4. 构建 debug 包并上传为 Artifact

### 自动发布（`.github/workflows/release.yml`）

触发：推送 `v*` 标签（如 `v0.1.0`）。

1. 从标签解析 `versionName`，`versionCode` 取 `GITHUB_RUN_NUMBER` 保证单调递增
2. 从 Secret 解码 keystore 到临时目录
3. `assembleRelease` + `bundleRelease`
4. 用 `apksigner verify` 校验签名（签名不对就在这里失败，而不是等用户装不上）
5. 重命名产物、生成 SHA256 校验和
6. 创建 GitHub Release 并上传 APK / AAB / mapping.txt，自动生成 changelog

标签含 `-` （如 `v0.2.0-beta.1`）会自动标记为 Pre-release。

---

## 首次配置

### 1. 生成签名密钥

```bash
./scripts/generate-keystore.sh
```

密钥库生成在 `keystore/release.p12`，已被 `.gitignore` 忽略。

> **务必单独备份这个文件。** 它决定了 APK 的签名身份，一旦丢失，已安装旧版本的用户将无法覆盖安装升级，只能卸载重装。

### 2. 配置 GitHub Secrets

```bash
./scripts/print-signing-secrets.sh
```

把输出中的四个值填到仓库的 **Settings → Secrets and variables → Actions**：

| Secret | 说明 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | 密钥库的 base64 编码 |
| `SIGNING_STORE_PASSWORD` | 密钥库口令 |
| `SIGNING_KEY_ALIAS` | 密钥别名 |
| `SIGNING_KEY_PASSWORD` | 密钥口令 |

### 3. 推送并发布

> **本机注意**：`~/.ssh` 里有两把密钥，默认的 `id_ed25519` 认证出来是另一个账号 `31413Hao`。
> 因此远程地址用的是 `github-mj` 别名（在 `~/.ssh/config` 中指向 `id_ed25519_mj`）。
> 如果换成 `git@github.com:...` 会以错误身份提交。

```bash
git push -u origin main

# 打标签即自动发版
git tag v0.1.0
git push origin v0.1.0
```

### 4. 本地构建（可选）

本地无需配置任何密钥即可构建，release 变体会回退到 debug 签名：

```bash
./gradlew assembleDebug
./gradlew assembleRelease -PappVersionName=0.1.0 -PappVersionCode=1
```

带正式签名的本地构建（凭据从 keystore/keystore.properties 读取，无需手输口令）：

```bash
export SIGNING_KEYSTORE_PATH="$PWD/keystore/release.p12"
export SIGNING_STORE_PASSWORD="$(grep -E '^storePassword=' keystore/keystore.properties | cut -d= -f2-)"
export SIGNING_KEY_ALIAS="$(grep -E '^keyAlias=' keystore/keystore.properties | cut -d= -f2-)"
export SIGNING_KEY_PASSWORD="$SIGNING_STORE_PASSWORD"
./gradlew assembleRelease -PappVersionName=0.1.0 -PappVersionCode=1
```

---

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | Android 13+ 读取图片与视频 |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Android 14+ 部分授权（只处理用户选中的项） |
| `READ_EXTERNAL_STORAGE` | Android 12 及以下回退（`maxSdkVersion=32`） |
| `MANAGE_MEDIA` | Android 12+ 免弹窗删除，**强烈建议开启**，否则每次批量删除都会弹系统确认框打断滑卡节奏 |
| `ACCESS_MEDIA_LOCATION` | 读取照片未经编辑的 EXIF 定位信息（可选） |
| `POST_NOTIFICATIONS` | 后台清理任务进度通知（可选） |

因 `minSdk = 30`，本项目**不需要** `WRITE_EXTERNAL_STORAGE`，也不需要处理 API 29 的 `RecoverableSecurityException` 分支。

---

## 开发计划

完整的架构设计、功能模块拆解、里程碑与风险对策见 [`docs/DEVELOPMENT_PLAN.md`](docs/DEVELOPMENT_PLAN.md)。

里程碑概览：

| 里程碑 | 内容 |
| --- | --- |
| **M0** ✅ | 项目骨架 · CI/CD 签名发布 |
| M1 | 媒体库读取 · 图片/视频网格 |
| M2 | 相册接入 · 范围选择与排序 |
| M3 | 查看器 · 视频播放 · 信息栏 |
| **M4** ★ | 滑卡手势 · 上滑删除 / 下滑保留 |
| **M5** ★ | 预加载缓存 · 多级撤销 |
| M6 | 系统回收站集成 · 免弹窗授权 |
| M7 | 存储统计 · 设置项 |
| M8 | 性能打磨 · 首发 v1.0.0 |

---

## 许可证

MIT
