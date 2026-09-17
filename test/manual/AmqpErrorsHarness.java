import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import dev.coolrequest.tool.rabbitmq.AmqpErrors;
import dev.coolrequest.tool.rabbitmq.RabbitClient;
import dev.coolrequest.tool.rabbitmq.RabbitConnection;

/**
 * 无 IDE 依赖的链路自测：逐场景连真实 broker，打印原始异常消息与 AmqpErrors 翻译结果。
 * 用法：java AmqpErrorsHarness <host> <port> <vhost> <user> <pass>
 */
public class AmqpErrorsHarness {

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String vhost = args[2];
        String user = args[3];
        String pass = args[4];

        RabbitConnection ok = new RabbitConnection("ok", host, port, vhost, user, pass);

        // 0. 正向对照：正确凭据 + passive 声明已存在队列 + basicConsume 立即取消
        scenario("正向对照(正确凭据连接+订阅)", () -> {
            Connection c = RabbitClient.factory(ok).newConnection();
            Channel ch = c.createChannel();
            ch.queueDeclarePassive("cr.ui.q.f");
            String tag = ch.basicConsume("cr.ui.q.f", false, "harness-" + System.currentTimeMillis(), false, false, null,
                    new com.rabbitmq.client.DefaultConsumer(ch) {
                        @Override
                        public void handleDelivery(String t, com.rabbitmq.client.Envelope e,
                                                   com.rabbitmq.client.AMQP.BasicProperties p, byte[] b) {
                        }
                    });
            ch.basicCancel(tag);
            ch.close();
            c.close(1000);
            return "OK: 连接/声明/订阅/取消全通";
        });

        // 1. 错误密码
        RabbitConnection badPass = new RabbitConnection("badpass", host, port, vhost, user, "wrong-password");
        scenario("错误密码", () -> {
            Connection c = RabbitClient.factory(badPass).newConnection();
            c.close(1000);
            return "(未抛异常?)";
        });

        // 2. 错误端口(5672 默认口)
        RabbitConnection badPort = new RabbitConnection("badport", host, 5672, vhost, user, pass);
        scenario("错误端口(5672)", () -> {
            Connection c = RabbitClient.factory(badPort).newConnection();
            c.close(1000);
            return "(未抛异常?)";
        });

        // 3. 错误 vhost
        RabbitConnection badVhost = new RabbitConnection("badvhost", host, port, "no_such_vhost", user, pass);
        scenario("错误 vhost", () -> {
            Connection c = RabbitClient.factory(badVhost).newConnection();
            c.close(1000);
            return "(未抛异常?)";
        });

        // 4. passive 声明不存在的队列(对应 Auto-declare 未勾选)
        scenario("passive声明不存在的队列", () -> {
            Connection c = RabbitClient.factory(ok).newConnection();
            try {
                Channel ch = c.createChannel();
                ch.queueDeclarePassive("cr.ui.q.nonexistent");
                return "(未抛异常?)";
            } finally {
                c.close(1000);
            }
        });

        // 5. 发布流程镜像 ProducerPanel: 先 exchangeDeclare(durable=false,autoDelete=true) 再 basicPublish 再 close
        scenario("发布到不存在的交换机(镜像Producer:先声明)", () -> {
            Connection c = RabbitClient.factory(ok).newConnection();
            try {
                Channel ch = c.createChannel();
                ch.exchangeDeclare("cr.ui.ex.nonexistent", com.rabbitmq.client.BuiltinExchangeType.DIRECT, false, true, null);
                ch.basicPublish("cr.ui.ex.nonexistent", "uik", null, "x".getBytes("UTF-8"));
                ch.close();
                return "OK: Producer 会先自动声明交换机, 因此该名字会被创建而非报错(走查发现)";
            } finally {
                c.close(1000);
            }
        });

        // 5b. 对已存在但属性不同的交换机按 Producer 口径再声明 → 应触发 406
        scenario("Producer口径声明与已存在属性冲突的交换机", () -> {
            Connection c = RabbitClient.factory(ok).newConnection();
            try {
                Channel ch = c.createChannel();
                ch.exchangeDeclare("cr.ui.ex.conflict", com.rabbitmq.client.BuiltinExchangeType.DIRECT, true, false, null);
                ch.exchangeDeclare("cr.ui.ex.conflict", com.rabbitmq.client.BuiltinExchangeType.DIRECT, false, true, null);
                return "(未抛异常?)";
            } finally {
                c.close(1000);
            }
        });

        // 6. durable 不匹配: cr.ui.q.d 是管理API默认(durable=false)建的, 再按 durable=true 声明
        scenario("durable参数冲突声明", () -> {
            Connection c = RabbitClient.factory(ok).newConnection();
            try {
                Channel ch = c.createChannel();
                ch.queueDeclare("cr.ui.q.d", true, false, false, null);
                return "(未抛异常?)";
            } finally {
                c.close(1000);
            }
        });

        // 7/8/9. mandatory+ReturnListener 不可路由检测(镜像 Producer 新逻辑)
        scenario("headers交换机: 无header发送(预期被退回 NO_ROUTE)", () -> {
            return publishAndCheckReturn(ok, "cr.ui.ex.headers", "", null, "ui-no-header");
        });
        scenario("headers交换机: header不匹配(预期被退回 NO_ROUTE)", () -> {
            return publishAndCheckReturn(ok, "cr.ui.ex.headers", "", java.util.Collections.singletonMap("x", "y"), "ui-wrong-header");
        });
        scenario("headers交换机: header匹配foo=bar(预期已路由)", () -> {
            return publishAndCheckReturn(ok, "cr.ui.ex.headers", "", java.util.Collections.singletonMap("foo", "bar"), "ui-match-header");
        });
        scenario("direct交换机: 正确routing key(预期已路由)", () -> {
            return publishAndCheckReturn(ok, "cr.ui.ex.direct", "uik", null, "ui-direct-ok");
        });

        // 13. AUTO 模式镜像：不声明直接发既有交换机（新默认行为，绝不自动创建、不会 406）
        scenario("AUTO模式: 不声明直接发既有durable交换机", () -> {
            Connection c = RabbitClient.factory(ok).newConnection();
            try {
                Channel ch = c.createChannel();
                java.util.List<com.rabbitmq.client.Return> returned = new java.util.concurrent.CopyOnWriteArrayList<>();
                ch.addReturnListener(returned::add);
                ch.basicPublish("cr.ui.ex.durable", "no-match-key", true,
                        new com.rabbitmq.client.AMQP.BasicProperties.Builder().deliveryMode(2).build(),
                        "ui-auto-direct".getBytes("UTF-8"));
                Thread.sleep(800);
                ch.close();
                // 该交换机无绑定, 无论退回与否, 都证明 406 未发生(没有声明动作)
                return "OK: 发送链路无声明动作, 406 不可能发生 (退回数=" + returned.size() + " 属正常路由语义)";
            } finally {
                c.close(1000);
            }
        });

        // 14. AUTO 模式发不存在的交换机 → 404(不自动创建的正确语义)
        scenario("AUTO模式: 发不存在交换机(预期404不创建)", () -> {
            Connection c = RabbitClient.factory(ok).newConnection();
            try {
                Channel ch = c.createChannel();
                ch.basicPublish("cr.ui.ex.autononexist", "uik", true, null, "x".getBytes("UTF-8"));
                Thread.sleep(800);
                ch.close();
                return "(未抛异常?)";
            } finally {
                c.close(1000);
            }
        });

        System.out.println("\n==== 全部场景执行完毕 ====");
    }

    /** 镜像 ProducerPanel 新发送逻辑：mandatory=true + ReturnListener + 800ms 等待。 */
    private static String publishAndCheckReturn(RabbitConnection conn, String exchange, String routingKey,
                                                java.util.Map<String, Object> headers, String body) throws Exception {
        Connection c = RabbitClient.factory(conn).newConnection();
        try {
            Channel ch = c.createChannel();
            com.rabbitmq.client.BuiltinExchangeType type = "cr.ui.ex.headers".equals(exchange)
                    ? com.rabbitmq.client.BuiltinExchangeType.HEADERS
                    : com.rabbitmq.client.BuiltinExchangeType.DIRECT;
            ch.exchangeDeclare(exchange, type, false, true, null);
            java.util.List<com.rabbitmq.client.Return> returned = new java.util.concurrent.CopyOnWriteArrayList<>();
            ch.addReturnListener(returned::add);
            com.rabbitmq.client.AMQP.BasicProperties.Builder b = new com.rabbitmq.client.AMQP.BasicProperties.Builder().deliveryMode(2);
            if (headers != null && !headers.isEmpty()) {
                b.headers(headers);
            }
            ch.basicPublish(exchange, routingKey, true, b.build(), body.getBytes("UTF-8"));
            Thread.sleep(800);
            ch.close();
            if (returned.isEmpty()) {
                return "OK: 已路由到至少一个队列(无退回)";
            }
            com.rabbitmq.client.Return r = returned.get(0);
            return "退回: replyCode=" + r.getReplyCode() + " replyText=" + r.getReplyText() + " (消息未进任何队列)";
        } finally {
            c.close(1000);
        }
    }

    private static void scenario(String name, Task task) {
        System.out.println("\n########## " + name + " ##########");
        try {
            System.out.println("[结果] " + task.run());
        } catch (Throwable t) {
            System.out.println("[原始] " + t.getClass().getName() + " | getMessage()=" + t.getMessage());
            if (t.getCause() != null && t.getCause() != t) {
                System.out.println("[根因] " + t.getCause().getClass().getName() + " | getMessage()=" + t.getCause().getMessage());
            }
            System.out.println("[翻译] --- UI 将显示 ---");
            System.out.println(AmqpErrors.describe(t));
        }
    }

    interface Task {
        String run() throws Exception;
    }
}
