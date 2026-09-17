package dev.coolrequest.tool.rabbitmq;

import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.AuthenticationFailureException;
import com.rabbitmq.client.PossibleAuthenticationFailureException;
import com.rabbitmq.client.ShutdownSignalException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/**
 * AMQP/网络异常 → 可读错误文本。
 * 约束：任何出口都不允许把裸 "null" 展示给用户（教训：订阅失败只弹 null，无法排障）。
 */
public final class AmqpErrors {

    private AmqpErrors() {
    }

    /**
     * 生成面向用户的三段式错误：一句话原因 + 可操作建议 + 异常类型（含定位栈帧）。
     */
    public static String describe(Throwable ex) {
        if (ex == null) {
            return "未知错误（异常对象为 null）";
        }
        Throwable root = rootCause(ex);
        StringBuilder sb = new StringBuilder(firstLine(root));
        String hint = hintFor(root);
        if (hint != null) {
            sb.append("\n建议: ").append(hint);
        }
        sb.append("\n类型: ").append(root.getClass().getName());
        String frames = topFrames(root, 3);
        if (!frames.isEmpty()) {
            sb.append("\n位置: ").append(frames);
        }
        return sb.toString();
    }

    private static String firstLine(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return "连接/操作失败（服务端或客户端未给出原因）";
        }
        // AMQP 协议关闭帧提取 reply-text，比整段 protocol method 可读
        int idx = msg.indexOf("reply-text=");
        if (idx >= 0) {
            int end = msg.indexOf(',', idx);
            String text = (end > idx ? msg.substring(idx + 11, end) : msg.substring(idx + 11)).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        int nl = msg.indexOf('\n');
        String line = nl > 0 ? msg.substring(0, nl).trim() : msg.trim();
        return line.isEmpty() ? "连接/操作失败" : line;
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable cur = ex;
        int depth = 0;
        while (cur.getCause() != null && cur.getCause() != cur && depth < 8) {
            cur = cur.getCause();
            depth++;
        }
        return cur;
    }

    private static String topFrames(Throwable t, int n) {
        StackTraceElement[] st = t.getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < st.length && i < n; i++) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(st[i].getClassName().replace("com.rabbitmq.client.impl.", "r.c.impl.")
                    .replace("com.rabbitmq.client.", "r.c."))
                    .append('.').append(st[i].getMethodName())
                    .append(':').append(st[i].getLineNumber());
        }
        return sb.toString();
    }

    private static String hintFor(Throwable t) {
        String msg = t.getMessage() == null ? "" : t.getMessage();

        if (t instanceof AuthenticationFailureException
                || t instanceof PossibleAuthenticationFailureException
                || msg.contains("Login was refused") || msg.contains("authentication failure")) {
            return "用户名或密码错误（或该账号无此 vhost 权限），请在连接管理中核对。";
        }
        if (t instanceof ConnectException) {
            return "连接被拒绝：地址可达但端口未监听，请确认端口是 AMQP 端口（默认 5672，本测试环境 20070），不是管理端口。";
        }
        if (t instanceof SocketTimeoutException || msg.contains("timed out") || msg.contains("timeout during")) {
            return "连接超时：检查 Host/Port 是否正确、网络与防火墙是否放行该端口。";
        }
        if (t instanceof UnknownHostException) {
            return "主机名无法解析：检查 Host 填写。";
        }
        if (t instanceof SSLException) {
            return "TLS/SSL 握手失败：目标端口通常不是 SSL 监听，或证书不被本机信任。";
        }
        if (t instanceof ShutdownSignalException || t instanceof AlreadyClosedException) {
            if (msg.contains("ACCESS_REFUSED") || msg.contains("(403)") || msg.contains("reply-code=403")) {
                return "服务端拒绝访问：账号无此 vhost 权限，或用户名/密码错误。";
            }
            if (msg.contains("NOT_ALLOWED") || msg.contains("vhost") && msg.contains("not found")) {
                return "vhost 不存在或账号无权限：请核对 vhost 拼写（区分大小写），确认账号被授权访问该 vhost。";
            }
            if (msg.contains("NOT_FOUND") || msg.contains("(404)") || msg.contains("reply-code=404")) {
                return "队列或交换机不存在：勾选 Auto-declare 自动建队列，或先在服务端声明该资源。";
            }
            if (msg.contains("PRECONDITION_FAILED") || msg.contains("(406)") || msg.contains("inequivalent arg")) {
                return "队列/交换机参数冲突：同名资源已存在但 durable 等参数不一致，请换名或删除旧资源后重试。";
            }
            if (msg.contains("CONNECTION_FORCED") || msg.contains("(320)") || msg.contains("reply-code=320")) {
                return "服务端主动关闭了连接（维护中或被踢出）。";
            }
            if (msg.contains("unexpected frame") || msg.contains("connection reset")) {
                return "连接被重置：端口上可能不是 AMQP 服务（比如误把 HTTP 管理端口当 AMQP 端口）。";
            }
            return "连接被服务端关闭。";
        }
        if (t instanceof NullPointerException) {
            return "插件内部空指针：请截图本弹窗（含下方类型/位置）反馈。";
        }
        return null;
    }
}
