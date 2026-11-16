# 智能待办 · SmartTodo

一个**完全本地运行**的 Android 待办 App：从手机通知里自动提取待办事项，按截止时间提醒，不联网、不上传任何数据。

> 适合把班级群、工作群、教务/办公 App 的「交材料、开会、打卡」类通知自动收成待办清单，避免刷屏时漏看。

## 功能

### 通知自动提取
- 双通道接入：**通知使用权**（首选）+ **无障碍服务**兜底读取（解决部分 ROM 通知栏取不到内容）
- 规则引擎：动作词 + 时间词 + 来源上下文打分，命中即生成待办
- 时间表达：`明天下午3点`、`周四23:59`、`12月5日`、`月底`、`下个月5号`、`3天内`、`一周内`、`开学前`、`期末前`、`周末` 等
- 分句与清洗：支持换行 / emoji 切分，过滤「@某人」「@全体成员」、表情、多余标点
- 闲聊抑制：负向打分（哈哈哈、吃什么、怎么办…）直接淘汰，降低误识别
- 去重：`StatusBarNotification.getKey()` 优先，6 小时内同文本兜底

### 待办管理
- 新增 / 完成 / 取消完成 / 删除 / 清空已完成
- 分区：待处理、已完成；进度「已完成 X / 共 X 项」
- 截止标签：已过期、**即将到期（<24h）**、今天、明天
- 左滑两档：
  - 滑过**阈值 A** → 停住露出「删除」，点击才删
  - 滑过**阈值 B** → 松手直接完成
  - 未过 A 回弹；全局同一时刻只允许一行展开
- 点击条目跳回来源 App（可关）

### 提醒与桌面
- 本地闹钟：到截止时间发系统通知；完成 / 删除自动取消
- 桌面小组件：展示完成进度与前 3 条未完成待办，数据变更即时刷新

### 数据与设置
- JSON 导入 / 导出（系统文件选择器，不需要存储权限）
- App 黑白名单：控制哪些应用的通知可以生成待办
- 解析预览调试：记录每条通知的判定结果（应用过滤 / 非任务 / 重复 / 已生成）
- 深色 / 浅色 / 跟随系统，弹窗与遮罩完整适配
- 权限中心：5 类权限逐项检测、说明、跳转专属设置页、返回自动复检

## 技术栈

| 项 | 版本 |
|---|---|
| 语言 | Java + Kotlin |
| AGP / Gradle | 8.5.2 / 8.7 |
| Kotlin | 1.9.22 |
| compileSdk / targetSdk | 34 |
| minSdk | 24 |
| 依赖 | 仅 `androidx.recyclerview:recyclerview:1.3.2`，其余全部使用系统 API |

## 构建

要求：JDK 17、Android SDK Platform 34。

```bash
git clone https://github.com/048x/SmartTodo.git
cd SmartTodo
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

`local.properties`（`sdk.dir`）与 `gradle.properties`（本机代理）不随仓库提供，请自行创建。

## 目录结构

```
SmartTodo/
├── app/src/main/java/com/example/smarttodo/
│   ├── MainActivity.java            首页、添加、设置、导入导出
│   ├── TodoAdapter.java             RecyclerView 适配器（分区/DiffUtil/标签）
│   ├── SwipeRow.java                左滑两档手势 + 全局单行
│   ├── SpringRecyclerView.java      越界橡皮筋 + 弹簧回弹
│   ├── TodoExtractor.java           通知文本 → 待办（规则+打分）
│   ├── DueParser.java               中文时间 → 时间戳
│   ├── NotifIngest.java             去重 + 入库 + 调试记录
│   ├── TodoListenerService.java     通知监听服务（子线程队列）
│   ├── ReminderScheduler.java       闹钟排期
│   ├── ReminderReceiver.java        到点提醒
│   ├── AppFilter.java               黑白名单
│   ├── AppFilterActivity.java
│   ├── TodoJson.java                JSON 导入导出
│   ├── TodoWidgetProvider.java      桌面小组件
│   ├── ParseLog.java / ParseLogActivity.java
│   ├── SetupActivity.java           一键授权页
│   ├── TodoDb.java                  SQLite（v3，增量升级）
│   └── permission/                  权限中心（Kotlin）
└── app/src/main/res/                布局、主题、图标、小组件
```

## 数据流

```
通知 → App 黑白名单过滤 → 文本抽取（动作/时间/评分）
     → 去重（key → 6h 文本）→ SQLite 写入
     → ContentObserver 刷新列表 / 小组件
     → 重排本地闹钟 → 到点发通知
```

## 权限说明

| 权限 | 类型 | 用途 |
|---|---|---|
| 通知使用权 | 特殊 | 读取通知内容（推荐） |
| 无障碍服务 | 特殊 | 通知兜底读取 + 授权页自动点击开关 |
| 通知权限 | 运行时 | Android 13+ 展示提醒 |
| 忽略电池优化 | 特殊 | 锁屏后不被限制 |
| 自启动 / 后台运行 | ROM 设置 | 由各厂商系统管理 |
| 悬浮窗 / 所有文件访问 | 特殊 | 预留能力，主流程不依赖 |

无障碍、通知使用权等特殊权限**无法通过代码自动授予**，只能跳转系统设置；Android 13+ 侧载应用若无障碍开关置灰，需要先允许「受限制的设置」。

## 已知限制

- 规则解析对长句、方言、暗含任务会有漏判，可用「调试记录」定位并调整词表；
- 系统会限制后台精确闹钟数量，最多排 120 条；
- 小组件字体与配色跟随系统，不受 App 内手动主题影响；
- 不同 ROM 的无障碍设置页文案不同，自动点击词表在 `res/values/perm_targets.xml` 可配。

## 免责声明

本项目仅处理**本机已授权的通知**，不读取聊天记录、不联网、不上传数据。请遵守所在平台与学校/公司的使用规定。

## License

暂未附带开源许可证。如需二次分发，请先联系仓库作者。
