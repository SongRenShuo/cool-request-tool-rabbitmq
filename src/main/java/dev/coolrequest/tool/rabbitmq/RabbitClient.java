package dev.coolrequest.tool.rabbitmq;

import com.rabbitmq.client.ConnectionFactory;

/**
 * 连接工厂统一封装：heartbeat、超时、channelMax 等。
 */
public final class RabbitClient {

    private RabbitClient() {
    }

    public static ConnectionFactory factory(RabbitConnection conn) {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(conn.host);
        f.setPort(conn.port);
        f.setVirtualHost(conn.vhost);
        f.setUsername(conn.username);
        f.setPassword(conn.password);
        f.setConnectionTimeout(8000);
        f.setHandshakeTimeout(10000);
        f.setRequestedHeartbeat(10);
        f.setRequestedChannelMax(2047);
        return f;
    }
}