package dev.coolrequest.tool.rabbitmq;

import com.rabbitmq.client.Channel;

import java.io.IOException;

/**
 * 消息解码辅助：优先 JSON、否则纯文本、又否则 hex。
 */
public final class BodyCodec {

    private BodyCodec() {
    }

    public static final int MODE_JSON = 0;
    public static final int MODE_TEXT = 1;
    public static final int MODE_HEX = 2;

    public static String decode(byte[] body, int mode) {
        if (body == null) {
            return "";
        }
        switch (mode) {
            case MODE_TEXT:
                return new String(body, java.nio.charset.StandardCharsets.UTF_8);
            case MODE_HEX:
                return toHex(body);
            case MODE_JSON:
            default:
                String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                if (looksLikeJson(text)) {
                    return text;
                }
                return text;
        }
    }

    private static boolean looksLikeJson(String s) {
        String t = s.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    private static String toHex(byte[] body) {
        StringBuilder sb = new StringBuilder(body.length * 2);
        for (byte b : body) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    /**
     * 尝试将消费的 message 重投回队列（不 ack）。
     */
    public static void requeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException ignored) {
        }
    }
}