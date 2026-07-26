# 编译与使用指南

> 目标 App：`app.unique.one`  
> 补丁项目：`C:\Users\HUAWEI\WorkBuddy\ReVanced`  
> 补丁格式：经典 `.jar`（注解式 API，兼容 ReVanced CLI v4.x / v5.x）

---

## 一、环境依赖与前置条件

### 1.1 必须安装

| 组件 | 最低版本 | 说明 |
|------|----------|------|
| **JDK** | 17 | 编译和运行均需要。推荐 Eclipse Temurin 或 Oracle JDK。 |
| **Android SDK Build-Tools** | 34.0.0 | `generateBundle` 任务需要其中的 `d8` 工具。 |
| **Git** | 任意 | 可选；仅克隆项目时需要。 |

### 1.2 验证安装

```bash
# 确认 JDK
javac --version          # 应输出 17 或更高
echo %JAVA_HOME%         # 应指向 JDK 根目录

# 确认 Android SDK（或仅 Build-Tools）
echo %ANDROID_HOME%      # 应指向 SDK 根目录
# 确保存在 %ANDROID_HOME%/build-tools/34.0.0/d8 或 d8.bat
```

> **提示**：如果不需要生成 `classes.dex` 合并包，`generateBundle` 任务可跳过，此时无需 Android SDK Build-Tools。仅 `./gradlew :patches:build` 即可生成纯 `.jar` 补丁文件。

### 1.3 GitHub Packages 认证（必须）

ReVanced Patcher 托管在 GitHub Packages（`maven.pkg.github.com/revanced/revanced-patcher`）。Gradle 下载依赖时需要认证。

创建文件 `C:\Users\HUAWEI\WorkBuddy\ReVanced\gradle.properties`：

```properties
gpr.user=你的GitHub用户名
gpr.key=ghp_xxxxxxxxxxxxxxxxxxxx   # GitHub Personal Access Token（需要 read:packages 权限）
```

> **Token 获取**：访问 `https://github.com/settings/tokens` → Generate new token (classic) → 勾选 `read:packages` → 生成后复制。

> **注意**：由于原 `revanced/revanced-patcher` 仓库可能收到 DMCA 删除通知，可能需要使用 fork 仓库。此时请将 `patches/build.gradle.kts` 中的 `maven.pkg.github.com/revanced/revanced-patcher` 改为 `maven.pkg.github.com/你的fork名/revanced-patcher`，或使用 JitPack 等替代源。

---

## 二、编译构建步骤

### 2.1 获取源码

```bash
cd C:\Users\HUAWEI\WorkBuddy\ReVanced
```

项目已位于该路径，跳过 clone 步骤。

### 2.2 编译补丁（不含 classes.dex）

```bash
cd C:\Users\HUAWEI\WorkBuddy\ReVanced
.\gradlew :patches:build
```

成功后输出：`patches/build/libs/patches.jar`

这是一个包含编译后 Kotlin class 文件的 JAR，可直接作为 ReVanced CLI 的补丁包使用。

### 2.3 生成 classes.dex 合并包（可选）

```bash
.\gradlew :patches:generateBundle
```

此步骤：
1. 先执行 `:patches:build`
2. 使用 Android SDK 的 `d8` 工具将 JAR 转为 DEX
3. 将 `classes.dex` 打包回 JAR

输出与步骤 2.2 相同路径，但 JAR 内部额外包含 `classes.dex`（旧版 ReVanced CLI 需要此文件）。

### 2.4 完整构建命令（一步完成）

```bash
# 如果 Android SDK 已配置：
.\gradlew :patches:generateBundle

# 如果没有 Android SDK：
.\gradlew :patches:build
```

### 2.5 常见编译问题

| 错误 | 原因 | 解决方法 |
|------|------|----------|
| `401 Unauthorized` 拉取 patcher 依赖失败 | 未配置 `gradle.properties` 或 token 过期 | 检查 `gpr.user` / `gpr.key` |
| `ANDROID_HOME not found` | `generateBundle` 需要 SDK | 设置 ANDROID_HOME 环境变量，或跳过 `generateBundle` 仅执行 `build` |
| `d8: command not found` | Build-Tools 版本不匹配 | 修改 `patches/build.gradle.kts` 中 `d8` 路径对应的版本号 |
| `Unsupported class file major version` | JDK 版本过高或过低 | 确保 JDK 17（在 `patches/build.gradle.kts` 中 `jvmToolchain(17)`） |

---

## 三、使用方法

### 3.1 获取 ReVanced CLI

从 ReVanced GitHub Releases 页面（或其 fork/镜像）下载 CLI：

- **CLI v4.x / v5.x**（兼容 `.jar` 格式补丁）：`revanced-cli-x.x.x-all.jar`
- 推荐版本：`v4.6.0` 或 `v5.x`

> 由于上游 DMCA，请在 [ReVanced 社区站点](https://revanced.net/) 或 GitHub 镜像仓库中查找历史版本。

### 3.2 准备 APK

- 目标 App B 的 APK 文件（`app.unique.one`），放在工作目录中。例如命名为 `app-unique-one.apk`。

### 3.3 列出补丁

验证补丁包是否正确加载：

```bash
java -jar revanced-cli-x.x.x-all.jar list-patches patches/build/libs/patches.jar
```

应能看到一个名为 `Subscription bypass` 的补丁。

### 3.4 应用补丁

```bash
java -jar revanced-cli-x.x.x-all.jar patch ^
  -b patches/build/libs/patches.jar ^
  -o app-unique-one-patched.apk ^
  app-unique-one.apk
```

> 参数说明：  
> `-b`：补丁包路径  
> `-o`：输出 APK 路径  
> 最后一个参数：输入 APK 路径  

### 3.5 安装补丁后的 APK

将输出的 `app-unique-one-patched.apk` 传输到 Android 设备并安装：

```bash
adb install app-unique-one-patched.apk
```

如果已有旧版本安装，添加 `-r` 参数覆盖安装：

```bash
adb install -r app-unique-one-patched.apk
```

### 3.6 验证绕过效果

1. 启动补丁后的 App。
2. 应直接显示内容界面（不弹出未订阅提示）。
3. 测试搜索、扫码等功能，应正常可用。

---

## 四、常见配置选项说明

### 4.1 `gradle.properties` 配置项

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `gpr.user` | GitHub 用户名 | 环境变量 `GITHUB_ACTOR` |
| `gpr.key` | GitHub Personal Access Token（`read:packages`） | 环境变量 `GITHUB_TOKEN` |

### 4.2 `gradle/libs.versions.toml` 版本控制

| 键 | 说明 | 当前值 |
|------|------|--------|
| `versions.kotlin` | Kotlin 编译器版本 | `2.0.21` |
| `versions.revanced-patcher` | ReVanced Patcher 版本 | `21.0.0` |

如需升级 Patcher，修改 `revanced-patcher` 版本号即可（需确认对应 fork 仓库中存在该版本）。

### 4.3 `patches/build.gradle.kts` 构建配置

| 配置项 | 说明 |
|--------|------|
| `jvmToolchain(17)` | 编译目标 JDK 版本。App 运行在 Android Runtime 上，此处仅影响编译过程。 |
| `d8` 路径 | `generateBundle` 任务中的 `d8` 路径。如 Build-Tools 版本不同，修改 `${androidHome}/build-tools/34.0.0/d8` 中的版本号。 |
| GitHub Packages 仓库 URL | 如需使用 fork 的 patcher 仓库，修改 `url = uri("https://maven.pkg.github.com/...")` |

### 4.4 补丁选项

本补丁 `Subscription bypass` **不提供可配置选项**，所有行为已预先确定：
- `"oas"` → `1`（订阅状态：已订阅）
- `"osv"` → `0`（版本号：不满足，触发路径1）
- 假订���数据：`status=1, isActive=true, isPremium=true, expireTime=Long.MAX_VALUE`
- 版本检查方法 → 返回 `false`
- 功能门控方法 → 返回 `true`

如需调整，修改 `SubscriptionBypassPatch.kt` 中对应的常量或 smali 生成逻辑后重新编译。

---

## 五、完整步骤速查

```bash
# ===== 1. 配置认证 =====
# 确保 C:\Users\HUAWEI\WorkBuddy\ReVanced\gradle.properties 存在，内容：
#   gpr.user=你的GitHub用户名
#   gpr.key=ghp_xxxxxxxxxxxxxxxxxxxx

# ===== 2. 编译 =====
cd C:\Users\HUAWEI\WorkBuddy\ReVanced
.\gradlew :patches:build

# ===== 3. 应用补丁 =====
java -jar revanced-cli-4.6.0-all.jar patch ^
  -b patches/build/libs/patches.jar ^
  -o app-unique-one-patched.apk ^
  app-unique-one.apk

# ===== 4. 安装 =====
adb install -r app-unique-one-patched.apk
```

---

## 六、GitHub Actions 自动构建（输出成品 APK）

CI 工作流已配置在 `.github/workflows/patch-apk.yml`，支持两种触发方式：

### 6.1 手动触发（推荐）

在 GitHub 仓库页面 → **Actions** → **Build & Patch APK** → **Run workflow** → 填入 APK 下载直链。

执行步骤：
1. 下载原始 APK
2. `./gradlew :patcher-runner:shadowJar` — 编译补丁 + 打包 fat JAR
3. 运行 `PatcherRunner` — 加载补丁 → 修改字节码 → 签名
4. 上传 `output.apk` 为 workflow artifact（保留 90 天）

### 6.2 推送触发

推送到 `main` 或 `dev` 分支时自动构建补丁 JAR（上传为 artifact），但不执行打补丁操作（因为没有输入 APK）。

### 6.3 项目结构

```
ReVanced/
├── patches/                    # 补丁代码（BytecodePatch）
├── patcher-runner/             # 自包含 runner（含 fat JAR）
│   └── PatcherRunner.kt       # 入口：Patcher API + ApkUtils 签名
├── .github/workflows/
│   └── patch-apk.yml          # CI 定义
└── ...
```

### 6.4 签名说明

Runner 使用 JDK 自带的 `keytool` 生成临时 debug 密钥库签名。如需正式签名，修改 `PatcherRunner.kt` 中的 `createDebugKeystore` 逻辑或传入已有密钥库。

### 6.5 验证产物

```bash
# 下载 Artifact 后验证：
adb install output.apk
# 启动 App，确认无订阅弹窗，搜索/扫码功能正常
```
