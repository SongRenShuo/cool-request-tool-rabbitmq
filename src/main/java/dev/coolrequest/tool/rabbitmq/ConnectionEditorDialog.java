package dev.coolrequest.tool.rabbitmq;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * 连接新建/编辑对话框。
 */
public class ConnectionEditorDialog extends DialogWrapper {

    private final RabbitConnection source;
    private final Consumer<RabbitConnection> onSave;

    private final JBTextField nameField = new JBTextField();
    private final JBTextField hostField = new JBTextField();
    private final JBTextField portField = new JBTextField();
    private final JBTextField vhostField = new JBTextField();
    private final JBTextField userField = new JBTextField();
    private final JBPasswordField passField = new JBPasswordField();

    public ConnectionEditorDialog(Project project, RabbitConnection source, Consumer<RabbitConnection> onSave) {
        super(project, true);
        this.source = source;
        this.onSave = onSave;
        setTitle(source.name == null || source.name.isEmpty() ? "Add Connection" : "Edit Connection");
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        nameField.setText(source.name);
        hostField.setText(source.host);
        portField.setText(Integer.toString(source.port));
        vhostField.setText(source.vhost);
        userField.setText(source.username);
        passField.setText(source.password);

        addRow(panel, gbc, 0, "Name:", nameField);
        addRow(panel, gbc, 1, "Host:", hostField);
        addRow(panel, gbc, 2, "Port:", portField);
        addRow(panel, gbc, 3, "VHost:", vhostField);
        addRow(panel, gbc, 4, "Username:", userField);
        addRow(panel, gbc, 5, "Password:", passField);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(panel, BorderLayout.CENTER);
        return wrap;
    }

    private void addRow(JPanel panel, GridBagConstraints gbc, int row, String label, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        JBLabel lbl = new JBLabel(label);
        lbl.setPreferredSize(new Dimension(80, 26));
        panel.add(lbl, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        field.setPreferredSize(new Dimension(260, 26));
        panel.add(field, gbc);
    }

    @Override
    protected void doOKAction() {
        String name = nameField.getText().trim();
        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            setErrorText("Port must be a number", portField);
            return;
        }
        if (name.isEmpty() || host.isEmpty()) {
            setErrorText("Name and Host are required");
            return;
        }
        RabbitConnection c = new RabbitConnection();
        c.name = name;
        c.host = host;
        c.port = port;
        c.vhost = vhostField.getText().trim().isEmpty() ? "/" : vhostField.getText().trim();
        c.username = userField.getText().trim();
        c.password = new String(passField.getPassword());
        onSave.accept(c);
        super.doOKAction();
    }
}