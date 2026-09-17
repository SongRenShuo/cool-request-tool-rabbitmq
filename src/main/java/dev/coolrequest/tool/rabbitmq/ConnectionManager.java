package dev.coolrequest.tool.rabbitmq;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 连接配置的增删改选与持久化。
 * 存储于 IntelliJ PropertiesComponent，随项目配置跨会话保留。
 */
public class ConnectionManager {

    private static final String STORAGE_KEY = "coolrequest.rabbitmq.connections";
    private static final String SELECTED_KEY = "coolrequest.rabbitmq.selectedConnection";

    private final PropertiesComponent properties;

    public ConnectionManager(Project project) {
        this.properties = PropertiesComponent.getInstance(project);
    }

    public List<RabbitConnection> getConnections() {
        List<RabbitConnection> list = new ArrayList<>();
        String raw = properties.getValue(STORAGE_KEY, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                for (String seg : raw.split(Character.toString((char) 30))) {
                    if (seg.isEmpty()) {
                        continue;
                    }
                    String[] p = seg.split(Character.toString((char) 31), -1);
                    RabbitConnection c = new RabbitConnection();
                    c.name = decode(p.length > 0 ? p[0] : "");
                    c.host = decode(p.length > 1 ? p[1] : "");
                    c.port = p.length > 2 && !p[2].isEmpty() ? Integer.parseInt(decode(p[2])) : 5672;
                    c.vhost = p.length > 3 && !p[3].isEmpty() ? decode(p[3]) : "/";
                    c.username = decode(p.length > 4 ? p[4] : "");
                    c.password = decode(p.length > 5 ? p[5] : "");
                    list.add(c);
                }
            } catch (Exception ignored) {
                list.clear();
            }
        }
        if (list.isEmpty()) {
            list.add(defaultConnection());
        }
        return list;
    }

    private static RabbitConnection defaultConnection() {
        return new RabbitConnection("localhost", "127.0.0.1", 5672, "/", "guest", "guest");
    }

    private void save(List<RabbitConnection> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append((char) 30);
            }
            RabbitConnection c = list.get(i);
            sb.append(encode(c.name)).append((char) 31).append(encode(c.host)).append((char) 31)
                    .append(encode(Integer.toString(c.port))).append((char) 31).append(encode(c.vhost)).append((char) 31)
                    .append(encode(c.username)).append((char) 31).append(encode(c.password));
        }
        properties.setValue(STORAGE_KEY, sb.toString());
    }

    private static String encode(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String decode(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getUrlDecoder().decode(s), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    public String getSelectedName() {
        String sel = properties.getValue(SELECTED_KEY);
        if (sel == null || sel.isEmpty()) {
            sel = getConnections().get(0).name;
            properties.setValue(SELECTED_KEY, sel);
        }
        return sel;
    }

    public RabbitConnection getSelected() {
        String sel = getSelectedName();
        for (RabbitConnection c : getConnections()) {
            if (c.name.equals(sel)) {
                return c;
            }
        }
        return getConnections().get(0);
    }

    public void setSelected(RabbitConnection c) {
        properties.setValue(SELECTED_KEY, c.name);
    }

    public void addOrUpdate(RabbitConnection c) {
        List<RabbitConnection> list = new ArrayList<>(getConnections());
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(c.name)) {
                list.set(i, c);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(c);
        }
        save(list);
    }

    public void remove(String name) {
        List<RabbitConnection> list = new ArrayList<>(getConnections());
        list.removeIf(c -> c.name.equals(name));
        if (list.isEmpty()) {
            list.add(defaultConnection());
        }
        save(list);
        if (name.equals(getSelectedName())) {
            properties.setValue(SELECTED_KEY, list.get(0).name);
        }
    }
}