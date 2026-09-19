# JSON 高亮/格式化 + 消费工具栏收敛 开发计划

> 两个需求一起立项：①凡是显示 JSON 的地方，对齐主插件的体验——语法高亮 + 一键格式化；
> ②消费面板控制行按钮过多，不常用的收进「More」下拉。**不主动同步 PR 镜像**。

## 一、需求 1：JSON 高亮 + 格式化

### 1.1 技术路线（关键决策：对齐主插件同款实现，不引宿主内部类，也不自研词法器）

- **主插件源码实证**（coolrequest-vip）：高亮 = `com.intellij.json.JsonFileType.INSTANCE`
  （`CoolRequestEditorTextField.JSON_FILE_TYPE`）；格式化 = Jackson
  `readValue(text, Object.class)` + `writerWithDefaultPrettyPrinter()`（`ObjectMapperUtils.printer`）。
- **运行时可行性**：宿主 `Launcher.java:46` 创建工具类加载器（`LaunchedURLClassLoader`）时以
  宿主插件类加载器为 parent，工具解析不了的类全部委托宿主——主插件自身未声明 json 插件依赖
  却能用 `JsonFileType`（生产实证），工具走同一条委托链，等价可用。
- **为什么不用反射直接调宿主内部类**（用户提问后的实测结论）：技术上能（父委托，直接 import
  都行），但 tools-rep 全部官方工具零先例（只依赖两个 SDK 接口），`dev.coolrequest.utils.*`
  是无契约内部类，宿主升级即崩；且 `ObjectMapperUtils.printer` 失败吞错返回原文，拿不到
  「非法 JSON 报错位置」。
- **最终路线**：高亮用 `JsonFileType.INSTANCE`（平台 json 插件公开类，编译期加
  `plugins/json/lib/intellij.json.jar` 到 CP）；格式化用 Jackson 同款实现，jackson-core/
  databind/annotations 2.15 从宿主 lib 拷入工具 lib 打进 fat jar（自包含，不依赖宿主 lib 存在）；
  工具侧薄封装 `JsonUtil.prettyPrint`（失败抛 IllegalArgumentException 带位置）。

### 1.2 落点（凡是显示 JSON 的地方）

| 位置 | 改动 |
|---|---|
| 消息详情弹窗 `MessageDetailDialog`（消费+浏览两入口共用） | JSON 模式挂 JSON 高亮器（Text/Hex 恢复普通高亮）；bodyHeader 新增 `Format` 按钮（Copy payload 左侧），pretty-print 失败弹可读错误 |
| 生产面板 Body 编辑器 `ProducerPanel.bodyEditor` | 默认挂 JSON 高亮（非 JSON 文本不受影响，只是不加色）；Send 左侧新增 `Format` 按钮，失败写入结果编辑器 |
| 生产面板结果编辑器 / 消费表格 Body 预览 | 不动（前者是纯文本结果，后者是单行预览） |

### 1.3 新增文件

- `JsonUtil.java`：`isJsonLike`（`{`/`[` 开头快速判断）+ `prettyPrint`（Jackson 同款格式化，
  失败抛 IllegalArgumentException 带位置，供 UI 报错）。lib/ 新增 jackson-core/databind/
  annotations 2.15（拷自宿主 lib，版本对齐）；build-tool.sh 编译 CP 加 intellij.json.jar、
  fat jar 打包清单加三个 jackson jar。

## 二、需求 2：消费面板工具栏收敛（More 下拉）

### 2.1 现状

控制行 10 个控件：Prefetch、Decode、Limit、Auto-declare、Auto ack、Ack、Requeue、Reject、Clear、Subscribe——拥挤且高频低频混排。

### 2.2 方案（按使用频率分层）

- **主行保留（高频）**：`Decode`、`Auto ack`、`Ack`、`Subscribe`、`More ▾`（5 个）。
- **More 弹出面板（低频/配置类）**：`Prefetch`、`Limit`、`Auto-declare`、`Requeue`、`Reject`、`Clear`（6 个）。
- More 用 `JBPopupFactory.createComponentPopupBuilder` 挂自定义面板（含下拉与按钮均可交互），
  点按钮下方弹出、点外部关闭。
- 实现要点：控件是**搬移不是重建**（同一实例从 controlRow 挪进弹出面板），全部既有逻辑
  （enablement 联动、ack 终态守卫、autoAck 置灰 Prefetch、订阅中禁改配置）原样生效；
  `Ack` 留主行因为它是手动确认的主操作，`Requeue/Reject` 属纠错手段频次低。
- 弹出面板打开时控件状态实时（复用同一组件实例，无快照同步问题）。

## 三、验证与交付

1. 编译构建 + 全量 harness 回归（20 场景，UI 层改动不影响链路，防回归）。
2. 无法自动化验证高亮/弹出面板视觉，标注「真机走查项」：JSON 模式颜色、Format 对错路径、More 弹出交互、订阅中弹面板里控件禁用状态。
3. 交付顺序：开发正本提交并 push origin（不建新 PR）；**PR 镜像同步等用户发话**。
