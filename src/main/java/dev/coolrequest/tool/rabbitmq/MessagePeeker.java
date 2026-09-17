package dev.coolrequest.tool.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;

import java.util.List;
import java.util.ArrayList;

/**
 * 堆积消息浏览：只读 peek（basicGet 不 ack，阅后统一 Nack 重新入队，不消费不丢消息）。
 * 对齐 DBX / RabbitMQ Management UI 的 peek 分页模式。
 */
public class MessagePeeker {

    public static class Peeked {
        public long deliveryTag;
        public int offset;
        public String exchange;
        public String routingKey;
        public boolean redelivered;
        public long timestamp;
        public String messageId;
        public byte[] body;
        public String bodyText; // 仅当可 UTF-8 解码时非空
    }

    private final Connection connection;
    private final Channel channel;

    public MessagePeeker(Connection connection) throws Exception {
        this.connection = connection;
        this.channel = connection.createChannel();
    }

    /**
     * 从队头第 offset 条开始取 count 条，不 ack；读取结束后统一 requeue。
     * @throws Exception channel 已关闭或队列异常
     */
    public List<Peeked> peek(String queue, int offset, int count) throws Exception {
        List<Peeked> result = new ArrayList<>();
        long lastTag = -1;
        try {
            int skipped = 0;
            int took = 0;
            // 先跳过 offset 条
            while (skipped < offset) {
                GetResponse g = channel.basicGet(queue, false);
                if (g == null) {
                    break; // 队列为空
                }
                lastTag = g.getEnvelope().getDeliveryTag();
                skipped++;
            }
            while (took < count) {
                GetResponse g = channel.basicGet(queue, false);
                if (g == null) {
                    break;
                }
                lastTag = g.getEnvelope().getDeliveryTag();
                Peeked p = new Peeked();
                p.deliveryTag = lastTag;
                p.offset = offset + took;
                p.exchange = g.getEnvelope().getExchange();
                p.routingKey = g.getEnvelope().getRoutingKey();
                p.redelivered = g.getEnvelope().isRedeliver();
                p.timestamp = (g.getProps() != null && g.getProps().getTimestamp() != null)
                        ? g.getProps().getTimestamp().getTime()
                        : System.currentTimeMillis();
                p.messageId = g.getProps() != null ? g.getProps().getMessageId() : null;
                p.body = g.getBody();
                p.bodyText = Utf8.decode(p.body);
                result.add(p);
                took++;
            }
        } finally {
            // 全部 requeue（Nack multiple, requeue），让消息回到队列、不失不消费
            if (lastTag >= 0 && channel.isOpen()) {
                try {
                    channel.basicNack(lastTag, true, true);
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    public boolean isChannelOpen() {
        return channel != null && channel.isOpen();
    }

    public void close() {
        try {
            channel.close();
        } catch (Exception ignored) {
        }
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }
}