package dev.coolrequest.tool.rabbitmq;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * 连接管理对话框：列出全部连接，支持选择/编辑/删除。
 */
public class ConnectionManagerDialog extends DialogWrapper {

    private final ConnectionManager cm;
    private final Consumer<RabbitConnection> onSelect;
    private final Project project;
    private final JBList<RabbitConnection> list;
    private final DefaultListModel<RabbitConnection> model = new DefaultListModel<>();

    public ConnectionManagerDialog(Project project, ConnectionManager cm, String selectedName, Consumer<RabbitConnection> onSelect) {
        super(project, true);
        this.project = project;
        this.cm = cm;
        this.onSelect = onSelect;
        setTitle("Manage Connections");
        List<RabbitConnection> conns = cm.getConnections();
        for (RabbitConnection c : conns) {
            model.addElement(c);
        }
        list = new JBList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        for (int i = 0; i < conns.size(); i++) {
            if (conns.get(i).name.equals(selectedName)) {
                list.setSelectedIndex(i);
                break;
            }
        }
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(JBUI.Borders.empty(8));
        panel.setPreferredSize(new Dimension(420, 260));

        JBScrollPane scroll = new JBScrollPane(list);
        panel.add(scroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton editBtn = new JButton("Edit...");
        editBtn.addActionListener(e -> {
            RabbitConnection sel = list.getSelectedValue();
            if (sel == null) {
                return;
            }
            new ConnectionEditorDialog(project, sel, conn -> {
                cm.addOrUpdate(conn);
                reload(null);
            }).show();
        });
        JButton delBtn = new JButton("Delete");
        delBtn.addActionListener(e -> {
            RabbitConnection sel = list.getSelectedValue();
            if (sel == null) {
                return;
            }
            cm.remove(sel.name);
            reload(null);
        });
        JButton selectBtn = new JButton("Select");
        selectBtn.addActionListener(e -> {
            RabbitConnection sel = list.getSelectedValue();
            if (sel != null) {
                onSelect.accept(sel);
                close(OK_EXIT_CODE);
            }
        });
        actions.add(editBtn);
        actions.add(delBtn);
        actions.add(selectBtn);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void reload(RabbitConnection keep) {
        model.clear();
        for (RabbitConnection c : cm.getConnections()) {
            model.addElement(c);
            if (keep != null && c.name.equals(keep.name)) {
                list.setSelectedValue(c, true);
            }
        }
    }

    @Override
    protected void doOKAction() {
        RabbitConnection sel = list.getSelectedValue();
        if (sel != null) {
            onSelect.accept(sel);
        }
        close(OK_EXIT_CODE);
    }
}