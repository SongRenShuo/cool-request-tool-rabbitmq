package dev.coolrequest.tool.rabbitmq;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 堆积消息浏览面板：DBX/Management UI 精华——只读 peek（不消费不丢消息，阅后 requeue），offset/count 分页，本地搜索。
 */
public class BrowsePanel extends JPanel {

    private final Project project;
    private final ConnectionManager connectionManager;

    private final JBTextField queueField;
    private final ComboBox<Integer> pageSizeCombo;
    private final JButton peekButton;
    private final JButton refreshButton;
    private final JBTable messageTable;
    private final DefaultTableModel tableModel;

    private volatile com.rabbitmq.client.Connection heldConnection;
    private volatile MessagePeeker peeker;
    private int currentOffset = 0;
    private int loadedTotal = 0;
    /** 原始 peek 数据（含 deliveryTag/body 等），用于详情与 requeue */
    private final List<MessagePeeker.Peeked> loaded = new ArrayList<>();

    public BrowsePanel(Project project, ConnectionManager connectionManager) {
        this.project = project;
        this.connectionManager = connectionManager;
        setLayout(new BorderLayout(0, 4));
        setBorder(JBUI.Borders.empty(8));

        // Top row
        JPanel topRow = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(2, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridy = 0;

        gbc.gridx = 0; gbc.weightx = 0;
        topRow.add(new JBLabel("Queue:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        queueField = new JBTextField();
        topRow.add(queueField, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        topRow.add(new JBLabel("PageSize:"), gbc);
        gbc.gridx = 3; gbc.weightx = 0;
        pageSizeCombo = new ComboBox<>(new Integer[]{20, 50, 100});
        pageSizeCombo.setSelectedItem(50);
        topRow.add(pageSizeCombo, gbc);

        gbc.gridx = 4;
        peekButton = new JButton("Peek next");
        peekButton.addActionListener(e -> peek(false));
        topRow.add(peekButton, gbc);

        gbc.gridx = 5;
        refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> peek(true));
        topRow.add(refreshButton, gbc);

        add(topRow, BorderLayout.NORTH);

        // Table
        String[] columns = {"#", "Offset", "RoutingKey", "Size", "Body", "Tag", "Time"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        messageTable = new JBTable(tableModel);
        messageTable.setRowHeight(36);
        messageTable.getColumnModel().getColumn(0).setPreferredWidth(36);
        messageTable.getColumnModel().getColumn(1).setPreferredWidth(56);
        messageTable.getColumnModel().getColumn(2).setPreferredWidth(150);
        messageTable.getColumnModel().getColumn(3).setPreferredWidth(50);
        messageTable.getColumnModel().getColumn(4).setPreferredWidth(340);
        messageTable.getColumnModel().getColumn(5).setPreferredWidth(60);
        messageTable.getColumnModel().getColumn(6).setPreferredWidth(150);

        messageTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = messageTable.getSelectedRow();
                    if (row >= 0 && row < loaded.size()) {
                        MessagePeeker.Peeked p = loaded.get(row);
                        new MessageDetailDialog(project, fmtTime(p.timestamp),
                                "#" + p.deliveryTag, p.routingKey, String.valueOf(p.body.length), p.body, queueField.getText().trim()).show();
                    }
                }
            }
        });

        JBScrollPane scroll = new JBScrollPane(messageTable);
        add(scroll, BorderLayout.CENTER);

        // footer count
        JBLabel countLabel = new JBLabel(" ");
        add(countLabel, BorderLayout.SOUTH);
    }

    private static String fmtTime(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(millis));
    }

    private void peek(boolean restart) {
        RabbitConnection conn = connectionManager.getSelected();
        String queue = queueField.getText().trim();
        if (queue.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Queue is required.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        int pageSize = (Integer) pageSizeCombo.getSelectedItem();
        if (restart) {
            currentOffset = 0;
        }
        final int fetchOffset = currentOffset;
        final int fetchCount = pageSize;
        peekButton.setEnabled(false);
        refreshButton.setEnabled(false);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ensurePeeker(conn);
                if (!peeker.isChannelOpen()) {
                    throw new IllegalStateException("connection closed");
                }
                List<MessagePeeker.Peeked> page = peeker.peek(queue, fetchOffset, fetchCount);
                SwingUtilities.invokeLater(() -> {
                    if (restart) {
                        loaded.clear();
                        tableModel.setRowCount(0);
                    }
                    loaded.addAll(page);
                    for (MessagePeeker.Peeked p : page) {
                        tableModel.addRow(new Object[]{loaded.size(), fetchOffset + page.indexOf(p),
                                p.routingKey, p.body.length, preview(p.bodyText), "#" + p.deliveryTag, fmtTime(p.timestamp)});
                    }
                    loadedTotal = loaded.size();
                    currentOffset += page.size();
                    peekButton.setEnabled(true);
                    refreshButton.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(BrowsePanel.this,
                            "Peek failed:\n" + AmqpErrors.describe(ex), "Error", JOptionPane.ERROR_MESSAGE);
                    peekButton.setEnabled(true);
                    refreshButton.setEnabled(true);
                });
            }
        });
    }

    private void ensurePeeker(RabbitConnection conn) throws Exception {
        if (peeker == null || !peeker.isChannelOpen()) {
            if (peeker != null) {
                peeker.close();
            }
            heldConnection = RabbitClient.factory(conn).newConnection();
            peeker = new MessagePeeker(heldConnection);
        }
    }

    private String preview(String text) {
        if (text == null) {
            return "(binary)";
        }
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    public void dispose() {
        if (peeker != null) {
            peeker.close();
            peeker = null;
        }
    }
}