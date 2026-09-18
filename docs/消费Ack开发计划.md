# 消费确认（Ack）功能开发计划

> 背景：现网消费面板 `basicConsume(queue, false, ...)` 是**手动确认模式**，但全仓无 `basicAck` 调用，
> `BodyCodec.requeue()` 也从未被调用——即「手动模式 + 从不 ack」。后果：`prefetch` 默认 10，
> broker 最多同时投递 10 条未确认消息，第 11 条起停止投递，表格看似卡住；消息不丢（连接关闭时
> broker 全部重投），但消费面板实际只能看 10 条，且 UI 无任何确认入口。
>
> 目标：补全确认能力，使消费面板成为可控的手动确认调试工具，同时保留快速消费的自动确认通道。

## 一、方案（用户选定：方案 3 = 两者都做）

### 1. Auto ack 勾选

- 控制行新增 `JBCheckBox("Auto ack", false)`，默认**不勾**（手动确认，与现状一致但补上 ack 能力）。
- 勾选时 `basicConsume(queue, true, ...)`：消息一投递即确认，立刻从队列删除，适合「快速消费掉」场景；
  表格 Ack 列显示 `Auto`，行内确认按钮对该模式无意义（自动置灰）。
- 勾选状态下 `Prefetch` 下拉置灰（autoAck 无流控意义）。
- 风险提示写进 tooltip：自动确认下消息不可恢复。

### 2. 行内确认操作（手动模式）

- 表格新增 `Ack` 状态列（位于 DeliveryTag 之后），取值：`Unacked`（待确认）/ `Acked` / `Requeued` / `Rejected` / `Auto`。
- 控制行新增三枚按钮：`Ack`（`basicAck(tag,false)`，确认并从队列删除）、
  `Requeue`（`basicNack(tag,false,true)`，退回队列重投）、`Reject`（`basicNack(tag,false,false)`，丢弃不进 DLX 除非队列配置）。
- 支持多选：对选中行逐条按各自 delivery tag 单条确认（tag 可能不连续，不用 multiple）。
- 已处于终态（Acked/Requeued/Rejected）的行跳过，避免 `unknown delivery tag` 报错。
- 按钮仅在「订阅中 + 有选中行 + 非 Auto ack」时可用（`ListSelectionListener` 驱动）。
- 通道操作走后台线程（网络 IO），结果回 EDT 更新状态列；失败经 `AmqpErrors.describe` 弹可读错误。

### 3. Prefetch 语义说明

手动模式下在途未确认数受 `Prefetch` 限制（默认 10）：确认一条，broker 补投下一条。
如需一次看更多，把 Prefetch 调大或设为 0（不限流，注意大积压队列的内存占用）。

### 4. 队列深度读数 + 手动刷新（补充项，用户提议）

确认操作是否生效、队列还剩多少，表格本身看不出来（表格只显示已投递到本端的历史），故顶部行新增：

- `Ready:` 数值标签 + `Refresh` 按钮：经 AMQP `queueDeclarePassive().getMessageCount()` 取**即时**可投递条数；
  订阅中每 2 秒自动刷新（EDT 定时器，RPC 在后台线程），Ack/Requeue/Reject 后立即刷新一次；
  未订阅时也能点 Refresh（临时开连接探测后即关）。
- 未订阅/队列名为空显示 `—`，探测失败显示 `N/A`（不弹错，避免定时刷新刷屏）。
- **不显示在途未确认数**：AMQP 协议不提供该读数，管理 API 有统计发布延迟（新声明队列的
  `messages_ready`/`messages_unacknowledged` 字段直接缺失），故只报 ready 并在 tooltip 说明。

## 二、实现要点

- 行状态需与表格行严格对齐：新增 `rowTags`（`Long`）、`rowStates`（`String`）两个与 `rowBodies` 平行的列表；
  `limit` 超限移除首行、`Clear`、以及表格行被删除时三者同步增删（现状 `rowBodies` 已有该约定，沿用同一处代码）。
- delivery tag 在通道内唯一，重连后失效；`stopConsumer` 后按钮失效，状态列保留最后状态（不谎报为已确认）。
- 不改动 `MessagePeeker`（浏览面板本就是 basicGet + 统一 requeue 的只读语义，无 ack 概念）。

## 三、验证

1. **harness 新增 6 个 ack 场景**（AMQP 层镜像面板逻辑，连真 broker；队列 `cr.ui.q.ack` 自建自清）：
   - `手动Ack`：QoS=1 收一条（在途 1 + ready 2）→ `basicAck` → 关通道强制回投后重读 ready=2（被 ack 的那条不再回来）；
   - `手动未Ack对照`：收下不 ack → 关通道重读 ready=3（未确认消息由 broker 全部重投，不丢）；
   - `手动Requeue`：`basicNack(requeue=true)` → ready 由 0 回到 1，再投标记 `redelivered=true`；
   - `手动Reject`：`basicNack(requeue=false)` → ready=0（丢弃不回队列）；
   - `Auto ack`：`basicConsume(autoAck=true)` → 收齐 2 条且 ready=0；
   - `Prefetch 语义`：投 15 条 QoS=10 → 收到 10 条后停投、ready 剩 5（复现旧版「无 ack 入口时表格卡住」的现象）。

   实测结果（20260918，真 broker 114.66.55.245:20070）：**6 个场景全部符合预期**；
   全量 harness 合计 **20 场景**（13 正常路径 + 7 错误翻译分支）无回归。
   踩坑记录：管理 API 对新声明队列有统计发布延迟（`messages_ready`/`messages_unacknowledged` 字段会缺失，
   实测刚建的队列响应里根本没有这两个字段），深度读数改用 AMQP `queueDeclarePassive().getMessageCount()`
   取即时值；harness 输出在 Windows 下是 GBK 编码，读取需按 GBK 解码。
2. 构建 + 部署宿主 jar，用户真机走查：手动 ack 后消息从队列消失、Requeue 后重投（标 redeliver）、Reject 后消失、Auto ack 下订阅即清空队列。
3. 测试服务器 `cr.ui.*` 种子用完即清。

## 四、交付顺序

开发正本提交 → 同步 PR 镜像（**等用户发话**）→ 上游 PR #1 追加 commit。
