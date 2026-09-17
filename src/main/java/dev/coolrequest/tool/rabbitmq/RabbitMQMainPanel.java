package dev.coolrequest.tool.rabbitmq;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/**
 * RabbitMQ 工具主面板。
 * 顶栏：连接选择器；中区：生产/消费入口卡片；底栏：模式切换。
 */
public class RabbitMQMainPanel extends JPanel {

    private static final String CARD_HOME = "Home";
    private static final String CARD_PRODUCER = "Producer";
    private static final String CARD_CONSUMER = "Consumer";
    private static final String CARD_BROWSE = "Browse";

    private final Project project;
    private final ConnectionManager connectionManager;
    private final CardLayout cardLayout;
    private final JPanel contentPanel;
    private final JBLabel hostLabel;
    private final JBLabel modeLabel;
    private final ConsumerPanel consumerPanel;
    private final BrowsePanel browsePanel;
    private String currentMode = CARD_HOME;

    public RabbitMQMainPanel(Project project) {
        this.project = project;
        this.connectionManager = new ConnectionManager(project);
        setLayout(new BorderLayout());

        // Top bar - connection selector
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        topBar.setBorder(JBUI.Borders.customLine(UIManager.getColor("Separator.separatorColor"), 0, 0, 1, 0));
        JBLabel connTitle = new JBLabel("Connection:");
        topBar.add(connTitle);

        hostLabel = new JBLabel(describe(getSelected()));
        hostLabel.setForeground(UIManager.getColor("Link.activeForeground"));
        hostLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        hostLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showConnectionPopup();
            }
        });
        topBar.add(hostLabel);
        add(topBar, BorderLayout.NORTH);

        // Content area
        cardLayout = new CardLayout();
        contentPanel = new JPanel(cardLayout);
        contentPanel.add(createHomePanel(), CARD_HOME);
        consumerPanel = new ConsumerPanel(project, connectionManager);
        browsePanel = new BrowsePanel(project, connectionManager);
        contentPanel.add(new ProducerPanel(project, connectionManager), CARD_PRODUCER);
        contentPanel.add(consumerPanel, CARD_CONSUMER);
        contentPanel.add(browsePanel, CARD_BROWSE);
        add(contentPanel, BorderLayout.CENTER);

        // Bottom status bar - mode switcher
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        statusBar.setBorder(JBUI.Borders.customLine(UIManager.getColor("Separator.separatorColor"), 1, 0, 0, 0));
        JBLabel modeTitle = new JBLabel("Mode:");
        statusBar.add(modeTitle);

        modeLabel = new JBLabel(currentMode);
        modeLabel.setForeground(UIManager.getColor("Link.activeForeground"));
        modeLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        modeLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (CARD_PRODUCER.equals(currentMode)) {
                    showMode(CARD_CONSUMER);
                } else if (CARD_CONSUMER.equals(currentMode)) {
                    showMode(CARD_BROWSE);
                } else {
                    showMode(CARD_PRODUCER);
                }
            }
        });
        statusBar.add(modeLabel);
        add(statusBar, BorderLayout.SOUTH);

        cardLayout.show(contentPanel, CARD_HOME);
    }

    public RabbitConnection getSelected() {
        String sel = connectionManager.getSelectedName();
        for (RabbitConnection c : connectionManager.getConnections()) {
            if (c.name.equals(sel)) {
                return c;
            }
        }
        return connectionManager.getConnections().get(0);
    }

    private String describe(RabbitConnection c) {
        return c.username + "@" + c.host + ":" + c.port + " (" + c.vhost + ")";
    }

    private void showConnectionPopup() {
        List<RabbitConnection> conns = connectionManager.getConnections();
        java.util.List<String> items = new java.util.ArrayList<>();
        for (RabbitConnection c : conns) {
            items.add(c.name + "  " + describe(c));
        }
        items.add("+ Add Connection...");
        items.add("Manage...");

        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(
                new com.intellij.openapi.ui.popup.util.BaseListPopupStep<String>("Select Connection", items) {
                    @Override
                    public com.intellij.openapi.ui.popup.PopupStep<?> onChosen(String selectedValue, boolean finalChoice) {
                        if ("+ Add Connection...".equals(selectedValue)) {
                            SwingUtilities.invokeLater(() -> showConnectionDialog(null, hostLabel));
                        } else if ("Manage...".equals(selectedValue)) {
                            SwingUtilities.invokeLater(RabbitMQMainPanel.this::showManageDialog);
                        } else {
                            for (RabbitConnection c : conns) {
                                if (selectedValue.startsWith(c.name + "  ")) {
                                    connectionManager.setSelected(c);
                                    refreshHostLabel();
                                    break;
                                }
                            }
                        }
                        return com.intellij.openapi.ui.popup.PopupStep.FINAL_CHOICE;
                    }
                }).showUnderneathOf(hostLabel);
    }

    private void showManageDialog() {
        new ConnectionManagerDialog(project, connectionManager, getSelected().name, conn -> {
            connectionManager.setSelected(conn);
            refreshHostLabel();
        }).show();
    }

    private void showConnectionDialog(RabbitConnection existing, JComponent anchor) {
        new ConnectionEditorDialog(project, existing == null ? new RabbitConnection() : existing, conn -> {
            connectionManager.addOrUpdate(conn);
            connectionManager.setSelected(conn);
            refreshHostLabel();
        }).show();
    }

    private void refreshHostLabel() {
        hostLabel.setText(describe(getSelected()));
        consumerPanel.onConnectionChanged(getSelected());
    }

    private JPanel createHomePanel() {
        JPanel homePanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(20);
        gbc.gridy = 0;

        JPanel producerCard = createCard("Producer", "发送消息到 RabbitMQ");
        JPanel consumerCard = createCard("Consumer", "从 RabbitMQ 订阅消息");
        JPanel browseCard = createCard("Browse", "只读浏览队列堆积消息");

        producerCard.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showMode(CARD_PRODUCER);
            }
        });
        consumerCard.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showMode(CARD_CONSUMER);
            }
        });
        browseCard.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showMode(CARD_BROWSE);
            }
        });

        gbc.gridx = 0;
        homePanel.add(producerCard, gbc);
        gbc.gridx = 1;
        homePanel.add(consumerCard, gbc);
        gbc.gridx = 2;
        homePanel.add(browseCard, gbc);
        return homePanel;
    }

    private JPanel createCard(String title, String description) {
        JPanel card = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g2.dispose();
            }
        };
        card.setPreferredSize(new Dimension(180, 160));
        card.setBorder(JBUI.Borders.empty(20));
        card.setOpaque(false);
        card.setBackground(UIManager.getColor("Button.background"));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        card.add(titleLabel, gbc);

        gbc.gridy = 1;
        gbc.insets = JBUI.insets(8, 0, 0, 0);
        JBLabel descLabel = new JBLabel(description);
        descLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        card.add(descLabel, gbc);

        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                Color hover = UIManager.getColor("Button.hoverBackground");
                card.setBackground(hover != null ? hover : UIManager.getColor("Button.background").brighter());
                card.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                card.setBackground(UIManager.getColor("Button.background"));
                card.repaint();
            }
        });
        return card;
    }

    private void showMode(String mode) {
        currentMode = mode;
        modeLabel.setText(mode);
        cardLayout.show(contentPanel, mode);
    }

    public void dispose() {
        consumerPanel.dispose();
        browsePanel.dispose();
    }
}