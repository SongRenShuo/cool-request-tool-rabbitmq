# RabbitMQ 小工具 UI 走查计划

> 本文是 `docs/开发计划.md`（开发/构建/功能测试）之后补充的 **UI 走查（方案 C）计划**。
> 走查目标：由 ZCode 通过 Computer Use 接管 IDEA 鼠标键盘，把小工具的全部功能在真实 UI 上点一遍，
> 代替/补足 Smoke 脚本测试，验证「用户实际操作路径」下功能可用。

## 一、走查环境约定

- 宿主：IDEA 2026.2（本机），Cool Request 插件面板已在右侧打开，RabbitMQ 工具已装配并显示。
- 测试连接凭据（来自 dbx 配置）：`114.66.55.245:20070`，vhost=`dbx_test`，
  user=`dbx_test`，password=`bxGyxEy5TJklZDcQbZ96vaYA`。
- 测试命名统一前缀 `cr.ui.*`（区别于此前 Smoke 的 `cr.smoke.*`），便于清点。
- 交互方式：IDEA a11y 树不可用（元素极少），全部走 screenshot → zoom 定位 → coordinate click 路线；
  注意 zoom 后坐标系重置为新裁剪图，连续 zoom 需基于上一张 raster 内的坐标。

## 二、走查清单

### 1. 连接管理（增 / 改 / 选）
- 打开连接下拉 → 新增连接，填入测试凭据 → 保存。
- 切换连接（建一个临时的错误连接，验证保存后可选、可改回正确凭据）。
- 编辑连接：改密码为错值 → 尝试发消息应报连接失败（异常可读）；改回正确值恢复。
- 选中连接记忆：重启面板/IDE 后默认选中保持（视走查耗时决定是否做 IDE 重启项）。

### 2. 生产面板
- 队列/交换机预置（走查第一步先经 UI 或外部声明）：
  - `cr.ui.ex.direct`（direct）、`cr.ui.ex.topic`（topic）、`cr.ui.ex.fanout`（fanout）、`cr.ui.ex.headers`（headers）
  - 绑定队列：`cr.ui.q.d`（key=uik）、`cr.ui.q.t`（pattern=#）、`cr.ui.q.f`、`cr.ui.q.h`
- 分别用四种 exchange 类型各发一条消息，body 中文本含标识 `ui-check-N`；
  验证发送结果显示成功（msgId/耗时）。
- 发送失败分支：exchange 名填不存在且未开自动声明 → 发送应报可读错误。
- headers 类型至少发一条带自定义 header 的消息。

### 3. 消费面板
- 订阅 `cr.ui.q.f`（自动声明+绑定已建），应实时收到生产面板发来的消息行。
- 双击行 → 详情弹窗：元数据（Time/Queue/DeliveryTag/RoutingKey/Size）正确，Text/JSON/Hex 解码切换可用，Copy 可用。
- 停止消费 → 表格不再增长；重新启动消费正常。

### 4. 浏览面板（peek）
- 向 `cr.ui.q.d` 发 3 条消息 → 浏览面板选该队列 → Peek next 应列出 3 条（#/Offset/RoutingKey/Size/Body）。
- PageSize 切换 20/50/100 生效；Refresh 重新拉取。
- 关键断言：**peek 后消息仍在队列中**（只读不回丢）——浏览后再开消费，仍能收到全部 3 条。
- 双击行 → 详情弹窗可开。

### 5. 收尾
- 汇总走查结论（每个面板 pass/fail + 截图证据要点）。
- 清点测试痕迹：删除 `cr.ui.*` 全部队列/交换机/绑定，经 Management API
  （`http://114.66.55.245:20071`）复核无残留；确认无遗留消费者连接。
- 更新记忆文件 `coolrequest-rabbitmq-tool.md`：UI 走查状态从「真机点开未走查」改为走查结论。

## 三、验收口径

- 每个功能项 pass 的标准：UI 上可完整操作（无报错弹窗或报错符合预期文案），
  且服务端效果（消息到达/队列残留）可被消费面板或 Management API 佐证。
- 发现的问题按 P0（不可用）/P1（可用但体验差）/P2（小瑕疵）记录，P0/P1 修复后需复测对应项。
