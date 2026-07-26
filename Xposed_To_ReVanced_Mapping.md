# Xposed 模块 → ReVanced 补丁 功能对照表

> 原 Xposed 模块：`xposed_module_v1/app/src/main/java/com/bypass/BypassHook.java`（通杀版 V2）  
> ReVanced 补丁：`patches/src/main/kotlin/app/revanced/patches/uniqueone/subscription/SubscriptionBypassPatch.kt`

## 原模块 Hook 点清单

| 编号 | Xposed Hook | 目标签名 | 原行为 | 目的 |
|------|-------------|----------|--------|------|
| H1 | `ContextImpl.getSharedPreferences` / `ContextWrapper.getSharedPreferences`（afterHook） | `(String,int)->SharedPreferences` | 当 name=="app_setting" 时写入 `oas=1`、`osv=0` | 让订阅校验走已订阅路径 |
| H2 | 调用栈定位订阅管理类 zcc | — | 取第一个非系统类 | 运行时定位混淆类名 |
| H3 | 扫描 zcc 静态方法返回类型定位 lf | `() -> lf` | 检查返回类型类：7 字段 + 注解 | 运行时定位数据类 |
| H4 | `zcc.(String,int)->int`（beforeHook） | `(String,int)->int` | key=="oas" 返回 1；key=="osv" 返回 0 | 兜底读 SP |
| H5 | `zcc.()->lf`（Replacement） | `() -> lf` | 返回假 lf 并更新静态缓存 | 兜底数据替换 |
| H6 | `zcc.()->boolean`（beforeHook，按调用者类型） | `() -> boolean` | Fragment 调用返回 false；Activity 调用返回 true | 路径1 + 功能门控兼顾 |

## ReVanced 等效实现

| 编号 | 原 Xposed 功能 | ReVanced 实现 | 说明 |
|------|----------------|---------------|------|
| H1 + H4 | 写入 SP + Hook `(String,int)->int` | 直接 Patch `readSubscriptionInt(String,int)` 方法体，使其对 `"oas"` 返回 `1`、对 `"osv"` 返回 `0`，其余 key 透传 | 静态替换，无需运行时写 SP |
| H2 | 调用栈定位 zcc | `GetSharedPreferencesFingerprint`：通过字符串 `"app_setting"` 定位到 `getSharedPreferences()` 方法，其定义类即为 zcc | 不依赖类名，不依赖运行时栈 |
| H3 | 扫描 zcc 返回类型定位 lf | 从 `getSubscriptionData()`（无参静态、返回非系统类）的返回类型得到 lf 类，再验证字段签名与注解 | 与 Xposed 验证逻辑一致，在补丁时完成 |
| H5 | Hook `() -> lf` 返回假数据 | Patch `getSubscriptionData()` 方法体，用 smali 构造 lf 实例并按注解 `value()` 设置 7 个字段，最后 `sput` 到 zcc 静态缓存并返回 | 字段通过 JSON key 定位，字段名可混淆 |
| H6 | 按调用者类型区分 `() -> boolean` | 拆分为两个独立方法分别 Patch：<br>• 含 `"osv"` 的方法（`oO0OOOo`）→ 返回 `false`<br>• 含 `"oas"` 的复杂方法（`oO0OOOO0`）→ 返回 `true` | 原 `oO0OOOO()` 本就恒返回 `true` 且路径1不调用它，无需 Patch |

## 关键等价性论证

### 路径1启动流程（原 of8.O00OO）

```
读 "oas" = 1  →  versionCheck() = false  →  O00OO0() 显示内容
```

- ReVanced `readSubscriptionInt` 保证 `"oas"` 恒为 `1`。
- ReVanced version-check 方法恒返回 `false`（模拟 `"osv"=0` 的效果）。
- 因此 `of8.O00OO` 必然进入 `:cond_1` 调用 `O00OO0()`，与 Xposed 路径1一致。

### 功能门控

- `oO0OOOO0()` 在 `"oas"=1` 时会直接返回 `false`，导致搜索/扫码被拦截。
- ReVanced 直接将其方法体替换为 `return true`，原方法不再执行，功能门控放行。
- 由于路径1侧 `of8.O00OO` 不调用 `oO0OOOO0()`，两者互不干扰，与 Xposed V2 设计一致。

### 混淆鲁棒性

| 混淆变化 | 影响 | 原因 |
|----------|:----:|------|
| zcc / lf 类名变化 | ❌ | 通过 `"app_setting"` 和返回类型动态定位 |
| 方法名变化 | ❌ | 通过方法签名 + 字符串 `"oas"`/`"osv"` 定位 |
| 字段名变化 | ❌ | 通过注解 `value()` 定位字段 |
| 注解类名变化 | ❌ | 通过元素名 `"value"` 读取 |
| SP 文件名变化 | ⚠️ | 切入点失效（功能性字符串通常不变） |
| JSON key 变化 | ⚠️ | lf 定位/字段设置失效（协议通常不变） |
| 字段类型/数量变化 | ⚠️ | 验证逻辑需更新（业务模型通常不变） |

## 未引入的新行为

本补丁未添加任何原模块不存在的 API 调用或功能：

- 不引入网络请求、不修改资源、不添加 UI。
- 不伪造 ActivityResult 或 AIDL 返回（与 Xposed 路径1策略一致）。
- 构造的 lf 字段值与 Xposed `createFakeSubscriptionData()` 完全一致：
  - `st = 1`
  - `al = true`
  - `pu = true`
  - `ex = Long.MAX_VALUE`
  - `ca = System.currentTimeMillis()`
  - `v = 1`
  - `te = 0`
