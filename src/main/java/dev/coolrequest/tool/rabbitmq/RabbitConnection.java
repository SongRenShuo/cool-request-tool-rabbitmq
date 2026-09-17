package dev.coolrequest.tool.rabbitmq;

import com.intellij.openapi.project.Project;

/**
 * RabbitMQ 连接配置模型。
 */
public class RabbitConnection {
    public String name;
    public String host;
    public int port = 5672;
    public String vhost = "/";
    public String username = "guest";
    public String password = "guest";

    public RabbitConnection() {
    }

    public RabbitConnection(String name, String host, int port, String vhost, String username, String password) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.vhost = vhost;
        this.username = username;
        this.password = password;
    }

    public String address() {
        return host + ":" + port;
    }

    @Override
    public String toString() {
        return name;
    }
}