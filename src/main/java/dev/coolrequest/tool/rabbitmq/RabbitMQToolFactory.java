package dev.coolrequest.tool.rabbitmq;

import com.intellij.openapi.project.Project;
import dev.coolrequest.tool.CoolToolPanel;
import dev.coolrequest.tool.ToolPanelFactory;

import javax.swing.*;

public class RabbitMQToolFactory implements ToolPanelFactory {

    private RabbitMQMainPanel panel;

    @Override
    public CoolToolPanel createToolPanel() {
        return new CoolToolPanel() {
            private Project project;

            @Override
            public JPanel createPanel() {
                panel = new RabbitMQMainPanel(project);
                return panel;
            }

            @Override
            public void showTool() {
            }

            @Override
            public void closeTool() {
                if (panel != null) {
                    panel.dispose();
                }
            }
        };
    }
}