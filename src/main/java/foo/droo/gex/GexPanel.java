package foo.droo.gex;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;


import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.Map;

@Slf4j
public class GexPanel extends PluginPanel {

    private static final Color RISK_LOW_COLOR = new Color(76, 175, 80);
    private static final Color RISK_MEDIUM_COLOR = new Color(255, 235, 59);
    private static final Color RISK_HIGH_COLOR = new Color(244, 67, 54);

    private final JPanel historyPanel;
    private final JPanel statsPanel;
    private final JLabel statusLabel;
    private final JLabel riskLabel;
    private final JPanel riskPanel;

    public GexPanel() {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel titleLabel = new JLabel("GEX Trading");
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        headerPanel.add(titleLabel, BorderLayout.WEST);

        // Right side: risk indicator + status
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        rightPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        riskPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        riskPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        riskLabel = new JLabel("");
        riskLabel.setFont(riskLabel.getFont().deriveFont(Font.BOLD, 9f));
        riskPanel.add(riskLabel);
        riskPanel.setVisible(false);

        statusLabel = new JLabel("Disconnected");
        statusLabel.setForeground(new Color(244, 67, 54));
        statusLabel.setFont(statusLabel.getFont().deriveFont(10f));

        rightPanel.add(riskPanel);
        rightPanel.add(statusLabel);
        headerPanel.add(rightPanel, BorderLayout.EAST);

        add(headerPanel, BorderLayout.NORTH);

        // Main content
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Stats section
        statsPanel = new JPanel(new GridLayout(0, 2, 5, 5));
        statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        statsPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
                "Session Stats",
                0, 0, null, ColorScheme.LIGHT_GRAY_COLOR
            ),
            new EmptyBorder(5, 5, 5, 5)
        ));

        addStatRow(statsPanel, "Events Sent:", "0");
        addStatRow(statsPanel, "Active Orders:", "0");
        addStatRow(statsPanel, "Today's P&L:", "-");

        contentPanel.add(statsPanel);
        contentPanel.add(Box.createVerticalStrut(10));

        // History section
        historyPanel = new JPanel();
        historyPanel.setLayout(new BoxLayout(historyPanel, BoxLayout.Y_AXIS));
        historyPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        historyPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
                "Recent Trades",
                0, 0, null, ColorScheme.LIGHT_GRAY_COLOR
            ),
            new EmptyBorder(5, 5, 5, 5)
        ));

        JLabel noTradesLabel = new JLabel("No recent trades");
        noTradesLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        noTradesLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        historyPanel.add(noTradesLabel);

        JScrollPane scrollPane = new JScrollPane(historyPanel);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setBorder(null);
        scrollPane.setPreferredSize(new Dimension(0, 200));

        contentPanel.add(scrollPane);

        add(contentPanel, BorderLayout.CENTER);
    }

    private void addStatRow(JPanel panel, String label, String value) {
        JLabel labelComp = new JLabel(label);
        labelComp.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        JLabel valueComp = new JLabel(value);
        valueComp.setForeground(Color.WHITE);
        valueComp.setHorizontalAlignment(SwingConstants.RIGHT);

        panel.add(labelComp);
        panel.add(valueComp);
    }

    public void updateStats(int eventsSent, int activeOrders, String pnl) {
        updateStats(eventsSent, activeOrders, pnl, 0);
    }

    public void updateStats(int eventsSent, int activeOrders, String pnl, int queueDepth) {
        SwingUtilities.invokeLater(() -> {
            statsPanel.removeAll();
            addStatRow(statsPanel, "Events Sent:", String.valueOf(eventsSent));
            addStatRow(statsPanel, "Active Orders:", String.valueOf(activeOrders));
            addStatRow(statsPanel, "Today's P&L:", pnl);
            if (queueDepth > 0) {
                addStatRow(statsPanel, "Queued:", String.valueOf(queueDepth));
            }
            statsPanel.revalidate();
            statsPanel.repaint();
        });
    }

    public void updateHistory(List<Map<String, Object>> trades) {
        SwingUtilities.invokeLater(() -> {
            historyPanel.removeAll();

            if (trades == null || trades.isEmpty()) {
                JLabel noTradesLabel = new JLabel("No recent trades");
                noTradesLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                noTradesLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                historyPanel.add(noTradesLabel);
            } else {
                for (Map<String, Object> trade : trades) {
                    historyPanel.add(createTradeRow(trade));
                    historyPanel.add(Box.createVerticalStrut(3));
                }
            }

            historyPanel.revalidate();
            historyPanel.repaint();
        });
    }

    private JPanel createTradeRow(Map<String, Object> trade) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(new EmptyBorder(3, 5, 3, 5));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        String itemName = (String) trade.getOrDefault("item_name", "Unknown");
        String type = (String) trade.getOrDefault("type", "buy");
        Number pnl = (Number) trade.getOrDefault("pnl", 0);

        JLabel nameLabel = new JLabel(itemName);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(nameLabel.getFont().deriveFont(11f));

        JLabel typeLabel = new JLabel(type.toUpperCase());
        typeLabel.setForeground(type.equals("buy") ? new Color(76, 175, 80) : new Color(244, 67, 54));
        typeLabel.setFont(typeLabel.getFont().deriveFont(Font.BOLD, 10f));

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        leftPanel.add(typeLabel);
        leftPanel.add(nameLabel);

        JLabel pnlLabel = new JLabel(GpFormatter.format(pnl.longValue()));
        pnlLabel.setForeground(pnl.longValue() >= 0 ? new Color(76, 175, 80) : new Color(244, 67, 54));
        pnlLabel.setFont(pnlLabel.getFont().deriveFont(Font.BOLD, 11f));

        row.add(leftPanel, BorderLayout.WEST);
        row.add(pnlLabel, BorderLayout.EAST);

        return row;
    }

    public void setStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(status);
            statusLabel.setForeground(
                "Connected".equals(status) ? new Color(76, 175, 80) : new Color(244, 67, 54)
            );
        });
    }

    /**
     * Update the market risk indicator.
     * @param riskLevel "low", "medium", or "high"
     * @param dominantRegime "tight", "normal", or "wide"
     */
    public void updateMarketRisk(String riskLevel, String dominantRegime) {
        SwingUtilities.invokeLater(() -> {
            if (riskLevel == null || riskLevel.isEmpty()) {
                riskPanel.setVisible(false);
                return;
            }

            Color riskColor;
            String riskText;
            switch (riskLevel.toLowerCase()) {
                case "high":
                    riskColor = RISK_HIGH_COLOR;
                    riskText = "HIGH";
                    break;
                case "medium":
                    riskColor = RISK_MEDIUM_COLOR;
                    riskText = "MED";
                    break;
                case "low":
                default:
                    riskColor = RISK_LOW_COLOR;
                    riskText = "LOW";
                    break;
            }

            String regimeHint = dominantRegime != null ? " " + dominantRegime.substring(0, 1).toUpperCase() : "";
            riskLabel.setText(riskText + regimeHint);
            riskLabel.setForeground(riskColor);
            riskPanel.setVisible(true);
        });
    }
}
