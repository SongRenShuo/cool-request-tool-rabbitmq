package dev.coolrequest.tool.rabbitmq;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RabbitMQ 消费面板：订阅队列、实时表格、多策略 Body 解码、消息详情。
 */
public class ConsumerPanel extends JPanel {

    private final Project project;
    private final ConnectionManager connectionManager;

    private final JBTextField queueField;
    private final JBTextField consumerPrefixField;
    private final ComboBox<Integer> limitComboBox;
    private final ComboBox<Integer> prefetchComboBox;
    private final JBCheckBox autoDeclareCheck;
    private final ComboBox<String> decodeCombo;
    private final JButton subscribeButton;
    private final JButton clearButton;
    private final JBTable messageTable;
    private final DefaultTableModel tableModel;
    /** 与表格行一一对应的原始 body；add 于底部、limit 超限移除头部，避免行号随 removeRow 错位 */
    private final java.util.List<byte[]> rowBodies = new java.util.ArrayList<>();

    private volatile Connection connection;
    private volatile Channel consumeChannel;
    private volatile String consumerTag;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private RabbitConnection activeConnection;

    public ConsumerPanel(Project project, ConnectionManager connectionManager) {
        this.project = project;
        this.connectionManager = connectionManager;
        this.activeConnection = connectionManager.getSelected();
        setLayout(new BorderLayout(0, 4));
        setBorder(JBUI.Borders.empty(8));

        // Top config row
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
        topRow.add(new JBLabel("ConsumerPrefix:"), gbc);
        gbc.gridx = 3; gbc.weightx = 0.6;
        consumerPrefixField = new JBTextField("CoolRequest-");
        topRow.add(consumerPrefixField, gbc);

        // Table columns
        String[] columns = {"Time", "DeliveryTag", "RoutingKey", "Size", "Body"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        // Second row: Limit, Prefetch, decode, Auto-declare, buttons
        JPanel controlRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        controlRow.add(new JBLabel("Prefetch:"));
        prefetchComboBox = new ComboBox<>(new Integer[]{0, 1, 10, 50, 100});
        prefetchComboBox.setSelectedItem(10);
        controlRow.add(prefetchComboBox);

        controlRow.add(new JBLabel("Decode:"));
        decodeCombo = new ComboBox<>(new String[]{"JSON", "Text", "Hex"});
        controlRow.add(decodeCombo);

        controlRow.add(new JBLabel("Limit:"));
        limitComboBox = new ComboBox<>(new Integer[]{10, 100, 500, 1000, 5000});
        limitComboBox.setSelectedItem(200);
        controlRow.add(limitComboBox);

        autoDeclareCheck = new JBCheckBox("Auto-declare", true);
        controlRow.add(autoDeclareCheck);

        clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> {
            tableModel.setRowCount(0);
            rowBodies.clear();
        });
        controlRow.add(clearButton);

        subscribeButton = new JButton("Subscribe");
        subscribeButton.addActionListener(e -> toggleSubscription());
        controlRow.add(subscribeButton);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(topRow, BorderLayout.NORTH);
        northPanel.add(controlRow, BorderLayout.SOUTH);
        add(northPanel, BorderLayout.NORTH);

        messageTable = new JBTable(tableModel);
        messageTable.setRowHeight(40);
        messageTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        messageTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        messageTable.getColumnModel().getColumn(2).setPreferredWidth(160);
        messageTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        messageTable.getColumnModel().getColumn(4).setPreferredWidth(340);

        messageTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = messageTable.getSelectedRow();
                    if (row >= 0) {
                        openDetail(row);
                    }
                }
            }
        });

        JBScrollPane tableScroll = new JBScrollPane(messageTable);
        add(tableScroll, BorderLayout.CENTER);
    }

    public void onConnectionChanged(RabbitConnection conn) {
        RabbitConnection prev = activeConnection;
        this.activeConnection = conn;
        if (running.get() && (prev == null || !prev.name.equals(conn.name))) {
            stopConsumer();
        }
    }

    private void toggleSubscription() {
        if (running.get()) {
            stopConsumer();
        } else {
            startConsumer();
        }
    }

    private void startConsumer() {
        RabbitConnection conn = connectionManager.getSelected();
        String queue = queueField.getText().trim();
        String consumerPrefix = consumerPrefixField.getText().trim();
        if (consumerPrefix.isEmpty()) {
            consumerPrefix = "CoolRequest-";
        }
        int prefetch = (Integer) prefetchComboBox.getSelectedItem();
        int decodeMode = decodeMode();

        if (queue.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Queue is required.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        setFieldsEnabled(false);
        subscribeButton.setText("Stop");
        running.set(true);

        String finalPrefix = consumerPrefix;
        if (finalPrefix == null || finalPrefix.isEmpty()) {
            finalPrefix = "CoolRequest-";
        }
        final String prefixForThread = finalPrefix;
        int finalMode = decodeMode;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Connection conn2 = null;
            try {
                conn2 = openConnection(conn);
                this.connection = conn2;
                Channel channel = conn2.createChannel();
                this.consumeChannel = channel;
                if (prefetch > 0) {
                    channel.basicQos(prefetch);
                }
                String declaredQueue = queue;
                if (autoDeclareCheck.isSelected()) {
                    channel.queueDeclare(queue, true, false, false, null);
                } else {
                    channel.queueDeclarePassive(queue);
                }

                DefaultConsumer consumer = new DefaultConsumer(channel) {
                    @Override
                    public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
                        receive(envelope, properties, body, finalMode);
                    }
                };
                String tagName = channel.basicConsume(declaredQueue, false, prefixForThread + "-" + System.currentTimeMillis(), false, false, null, consumer);
                this.consumerTag = tagName;
                SwingUtilities.invokeLater(() -> {
                    tableModel.setRowCount(0);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(ConsumerPanel.this,
                            "Failed to start consumer:\n" + AmqpErrors.describe(ex),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    stopConsumer();
                });
            }
        });
    }

    private void receive(Envelope envelope, AMQP.BasicProperties properties, byte[] body, int decodeMode) {
        final String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        final String tag = envelope.getDeliveryTag() + (envelope.isRedeliver() ? " (redeliver)" : "");
        final String routingKey = envelope.getRoutingKey();
        final String exchange = envelope.getExchange();
        final int size = body == null ? 0 : body.length;
        final byte[] bodyBytes = body == null ? new byte[0] : body;
        String decoded;
        try {
            decoded = BodyCodec.decode(bodyBytes, decodeMode);
        } catch (Exception ex) {
            decoded = "(decode error: " + AmqpErrors.describe(ex) + ")";
        }
        String bodyPreview = decoded.length() > 200 ? decoded.substring(0, 200) + "…" : decoded;

        SwingUtilities.invokeLater(() -> {
            int limit = (Integer) limitComboBox.getSelectedItem();
            if (tableModel.getRowCount() >= limit) {
                tableModel.removeRow(0);
                if (!rowBodies.isEmpty()) {
                    rowBodies.remove(0);
                }
            }
            tableModel.addRow(new Object[]{time, tag, routingKey, size, bodyPreview});
            rowBodies.add(bodyBytes);
        });
    }

    private void openDetail(int row) {
        String time = (String) tableModel.getValueAt(row, 0);
        String deliveryTag = (String) tableModel.getValueAt(row, 1);
        String routingKey = (String) tableModel.getValueAt(row, 2);
        String size = String.valueOf(tableModel.getValueAt(row, 3));
        byte[] body = row >= 0 && row < rowBodies.size() ? rowBodies.get(row) : null;
        new MessageDetailDialog(project, time, deliveryTag, routingKey, size, body, queueField.getText().trim()).show();
    }

    private void stopConsumer() {
        running.set(false);
        String tag = consumerTag;
        Channel ch = consumeChannel;
        Connection c = connection;
        consumeChannel = null;
        consumerTag = null;
        connection = null;
        if (tag != null && ch != null && ch.isOpen()) {
            try {
                ch.basicCancel(tag);
            } catch (Exception ignored) {
            }
        }
        if (c != null && c.isOpen()) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
        setFieldsEnabled(true);
        subscribeButton.setText("Subscribe");
    }

    public void dispose() {
        stopConsumer();
    }

    private void setFieldsEnabled(boolean enabled) {
        queueField.setEnabled(enabled);
        consumerPrefixField.setEnabled(enabled);
        prefetchComboBox.setEnabled(enabled);
        limitComboBox.setEnabled(enabled);
        decodeCombo.setEnabled(enabled);
        autoDeclareCheck.setEnabled(enabled);
    }

    private int decodeMode() {
        Object sel = decodeCombo.getSelectedItem();
        if ("Hex".equals(sel)) {
            return BodyCodec.MODE_HEX;
        }
        return BodyCodec.MODE_JSON;
    }

    private Connection openConnection(RabbitConnection conn) throws Exception {
        return RabbitClient.factory(conn).newConnection();
    }
}