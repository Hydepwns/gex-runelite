package foo.droo.gex;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TextComponent;

import javax.inject.Inject;
import java.awt.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class GexOverlay extends Overlay {

    private static final Color PROFIT_COLOR = new Color(76, 175, 80);
    private static final Color LOSS_COLOR = new Color(244, 67, 54);
    private static final Color NEUTRAL_COLOR = new Color(255, 235, 59);
    private static final Color CONNECTED_COLOR = new Color(76, 175, 80);
    private static final Color DISCONNECTED_COLOR = new Color(244, 67, 54);
    private static final int STATUS_DOT_SIZE = 8;

    // Regime colors: tight = fast fills, wide = slow/risky
    private static final Color REGIME_TIGHT_COLOR = new Color(76, 175, 80);   // Green
    private static final Color REGIME_NORMAL_COLOR = new Color(255, 235, 59); // Yellow
    private static final Color REGIME_WIDE_COLOR = new Color(244, 67, 54);    // Red

    // Anomaly warning color
    private static final Color ANOMALY_WARNING_COLOR = new Color(255, 152, 0); // Orange

    // Optimal window indicator
    private static final Color OPTIMAL_WINDOW_COLOR = new Color(33, 150, 243); // Blue

    private final Client client;
    private final GexConfig config;

    // Cached data from backend
    private final Map<Integer, SlotData> slotDataCache = new ConcurrentHashMap<>();

    // Connection status
    private volatile boolean connected = false;

    @Inject
    public GexOverlay(Client client, GexConfig config) {
        this.client = client;
        this.config = config;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(PRIORITY_HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showOverlay()) {
            return null;
        }

        Widget geWidget = client.getWidget(ComponentID.GRAND_EXCHANGE_WINDOW_CONTAINER);
        if (geWidget == null || geWidget.isHidden()) {
            return null;
        }

        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) {
            return null;
        }

        for (int i = 0; i < offers.length && i < 8; i++) {
            GrandExchangeOffer offer = offers[i];
            if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY) {
                continue;
            }

            renderSlotOverlay(graphics, i, offer);
        }

        // Render connection status indicator in corner of GE window
        renderConnectionStatus(graphics, geWidget.getBounds());

        return null;
    }

    private void renderConnectionStatus(Graphics2D graphics, Rectangle geBounds) {
        if (geBounds == null) {
            return;
        }

        // Draw status dot in top-right corner of GE window
        int x = geBounds.x + geBounds.width - STATUS_DOT_SIZE - 5;
        int y = geBounds.y + 5;

        graphics.setColor(connected ? CONNECTED_COLOR : DISCONNECTED_COLOR);
        graphics.fillOval(x, y, STATUS_DOT_SIZE, STATUS_DOT_SIZE);
    }

    private void renderSlotOverlay(Graphics2D graphics, int slot, GrandExchangeOffer offer) {
        Widget slotWidget = getSlotWidget(slot);
        if (slotWidget == null || slotWidget.isHidden()) {
            return;
        }

        Rectangle bounds = slotWidget.getBounds();
        if (bounds == null) {
            return;
        }

        SlotData data = slotDataCache.get(slot);
        int itemId = offer.getItemId();

        // Render profit margin estimate
        if (data != null && data.itemId == itemId) {
            renderProfitOverlay(graphics, bounds, data, offer);
            renderEtaOverlay(graphics, bounds, data, offer);
            renderWarningsOverlay(graphics, bounds, data, offer);
        }
    }

    private void renderWarningsOverlay(Graphics2D graphics, Rectangle bounds, SlotData data, GrandExchangeOffer offer) {
        GrandExchangeOfferState state = offer.getState();
        if (state != GrandExchangeOfferState.BUYING && state != GrandExchangeOfferState.SELLING) {
            return;
        }

        int warningY = bounds.y + 5;
        int warningX = bounds.x + 5;

        // Anomaly warning (orange "!" icon)
        if (data.hasAnomalyWarning()) {
            TextComponent anomalyComp = new TextComponent();
            anomalyComp.setText("!");
            anomalyComp.setColor(ANOMALY_WARNING_COLOR);
            anomalyComp.setPosition(new Point(warningX, warningY));
            anomalyComp.render(graphics);
            warningX += 12;
        }

        // Optimal window indicator (blue dot if in window)
        if (data.inOptimalWindow) {
            graphics.setColor(OPTIMAL_WINDOW_COLOR);
            graphics.fillOval(warningX, warningY - 6, 6, 6);
            warningX += 10;
        }

        // Reset countdown warning (red if won't fill before reset)
        if (data.minutesUntilReset > 0 && data.minutesUntilReset < 240) {
            String resetText;
            if (data.minutesUntilReset < 60) {
                resetText = data.minutesUntilReset + "m";
            } else {
                resetText = String.format("%dh%dm", data.minutesUntilReset / 60, data.minutesUntilReset % 60);
            }

            Color resetColor = data.willFillBeforeReset ? NEUTRAL_COLOR : LOSS_COLOR;
            String prefix = data.willFillBeforeReset ? "" : "!";

            TextComponent resetComp = new TextComponent();
            resetComp.setText(prefix + "R:" + resetText);
            resetComp.setColor(resetColor);
            resetComp.setPosition(new Point(bounds.x + bounds.width - 55, bounds.y + 5));
            resetComp.render(graphics);
        }
    }

    private void renderProfitOverlay(Graphics2D graphics, Rectangle bounds, SlotData data, GrandExchangeOffer offer) {
        if (data.estimatedMargin == 0) {
            return;
        }

        long potentialProfit = data.estimatedMargin * offer.getTotalQuantity();

        // Adjust for partially filled
        if (offer.getQuantitySold() > 0) {
            potentialProfit = data.estimatedMargin * (offer.getTotalQuantity() - offer.getQuantitySold());
        }

        Color color = potentialProfit > 0 ? PROFIT_COLOR : (potentialProfit < 0 ? LOSS_COLOR : NEUTRAL_COLOR);

        TextComponent profitText = new TextComponent();
        profitText.setText(GpFormatter.format(potentialProfit));
        profitText.setColor(color);
        profitText.setPosition(new Point(bounds.x + bounds.width - 50, bounds.y + 5));
        profitText.render(graphics);
    }

    private void renderEtaOverlay(Graphics2D graphics, Rectangle bounds, SlotData data, GrandExchangeOffer offer) {
        if (data.fillEtaMinutes <= 0) {
            return;
        }

        // Only show ETA for active orders
        GrandExchangeOfferState state = offer.getState();
        if (state != GrandExchangeOfferState.BUYING && state != GrandExchangeOfferState.SELLING) {
            return;
        }

        String etaText = formatEta(data.fillEtaMinutes);
        int pct = (int) (data.fillConfidence * 100);
        String dayHint = data.etaSource != null ? " (" + data.etaSource + ")" : "";

        // Color ETA by spread regime if available, otherwise by confidence
        Color etaColor;
        if (data.spreadRegime != null) {
            etaColor = getRegimeColor(data.spreadRegime);
        } else {
            etaColor = pct >= 70 ? PROFIT_COLOR : (pct >= 40 ? NEUTRAL_COLOR : LOSS_COLOR);
        }

        // Build ETA text with optional ML indicator
        String mlIndicator = data.hasItemModel ? " [ML]" : "";
        String basisHint = data.etaBasis != null ? " " + data.etaBasis.substring(0, 1).toUpperCase() : "";

        TextComponent etaComp = new TextComponent();
        etaComp.setText("~" + etaText + " " + pct + "%" + dayHint + basisHint + mlIndicator);
        etaComp.setColor(etaColor);
        etaComp.setPosition(new Point(bounds.x + 5, bounds.y + bounds.height - 15));
        etaComp.render(graphics);

        // Render regime indicator dot
        if (data.spreadRegime != null) {
            renderRegimeIndicator(graphics, bounds, data.spreadRegime);
        }
    }

    private void renderRegimeIndicator(Graphics2D graphics, Rectangle bounds, String regime) {
        // Small colored dot in the bottom-right of the slot to indicate regime
        int dotSize = 6;
        int x = bounds.x + bounds.width - dotSize - 5;
        int y = bounds.y + bounds.height - dotSize - 5;

        graphics.setColor(getRegimeColor(regime));
        graphics.fillOval(x, y, dotSize, dotSize);
    }

    private Color getRegimeColor(String regime) {
        if (regime == null) {
            return NEUTRAL_COLOR;
        }
        switch (regime.toLowerCase()) {
            case "tight":
                return REGIME_TIGHT_COLOR;
            case "wide":
                return REGIME_WIDE_COLOR;
            case "normal":
            default:
                return REGIME_NORMAL_COLOR;
        }
    }

    private Widget getSlotWidget(int slot) {
        if (slot < 0 || slot >= 8) {
            return null;
        }

        Widget geWindow = client.getWidget(ComponentID.GRAND_EXCHANGE_WINDOW_CONTAINER);
        if (geWindow == null) {
            return null;
        }

        // Slot widgets are children 7-14 of the GE window container
        return geWindow.getChild(7 + slot);
    }

    private String formatEta(double minutes) {
        if (minutes < 1) {
            return "<1m";
        } else if (minutes < 60) {
            return String.format("%.0fm", minutes);
        } else {
            return String.format("%.1fh", minutes / 60);
        }
    }

    public void updateSlotData(int slot, int itemId, long estimatedMargin, double fillEtaMinutes, double fillConfidence) {
        slotDataCache.put(slot, new SlotData(itemId, estimatedMargin, fillEtaMinutes, fillConfidence, null));
    }

    public void updateSlotData(int slot, int itemId, long estimatedMargin, double fillEtaMinutes, double fillConfidence, String etaSource) {
        slotDataCache.put(slot, new SlotData(itemId, estimatedMargin, fillEtaMinutes, fillConfidence, etaSource));
    }

    public void updateSlotDataWithMl(int slot, int itemId, long estimatedMargin, double fillEtaMinutes,
                                      double fillConfidence, String etaSource, String spreadRegime,
                                      double regimeCertainty, boolean hasItemModel, String etaBasis) {
        slotDataCache.put(slot, new SlotData(itemId, estimatedMargin, fillEtaMinutes, fillConfidence,
            etaSource, spreadRegime, regimeCertainty, hasItemModel, etaBasis));
    }

    public void updateSlotDataFull(int slot, int itemId, long estimatedMargin, double fillEtaMinutes,
                                   double fillConfidence, String etaSource, String spreadRegime,
                                   double regimeCertainty, boolean hasItemModel, String etaBasis,
                                   double anomalyScore, boolean inOptimalWindow,
                                   int minutesUntilReset, boolean willFillBeforeReset) {
        slotDataCache.put(slot, new SlotData(itemId, estimatedMargin, fillEtaMinutes, fillConfidence,
            etaSource, spreadRegime, regimeCertainty, hasItemModel, etaBasis,
            anomalyScore, inOptimalWindow, minutesUntilReset, willFillBeforeReset));
    }

    public void clearSlotData(int slot) {
        slotDataCache.remove(slot);
    }

    public void clearAllSlotData() {
        slotDataCache.clear();
    }

    public void setConnected(boolean status) {
        this.connected = status;
    }

    private static class SlotData {
        final int itemId;
        final long estimatedMargin;
        final double fillEtaMinutes;
        final double fillConfidence;
        final String etaSource;
        final String spreadRegime;      // "tight", "normal", "wide"
        final double regimeCertainty;   // 0.0-1.0
        final boolean hasItemModel;     // Per-item ML model available
        final String etaBasis;          // "velocity", "historical", "heuristic"
        final double anomalyScore;      // 0.0-1.0, warning if > 0.6
        final boolean inOptimalWindow;  // Currently in optimal trading window
        final int minutesUntilReset;    // Buy limit reset countdown, -1 if N/A
        final boolean willFillBeforeReset; // Prediction if fill will complete before reset

        SlotData(int itemId, long estimatedMargin, double fillEtaMinutes, double fillConfidence, String etaSource) {
            this(itemId, estimatedMargin, fillEtaMinutes, fillConfidence, etaSource,
                null, 0.0, false, null, 0.0, false, -1, true);
        }

        SlotData(int itemId, long estimatedMargin, double fillEtaMinutes, double fillConfidence, String etaSource,
                 String spreadRegime, double regimeCertainty, boolean hasItemModel, String etaBasis) {
            this(itemId, estimatedMargin, fillEtaMinutes, fillConfidence, etaSource,
                spreadRegime, regimeCertainty, hasItemModel, etaBasis, 0.0, false, -1, true);
        }

        SlotData(int itemId, long estimatedMargin, double fillEtaMinutes, double fillConfidence, String etaSource,
                 String spreadRegime, double regimeCertainty, boolean hasItemModel, String etaBasis,
                 double anomalyScore, boolean inOptimalWindow, int minutesUntilReset, boolean willFillBeforeReset) {
            this.itemId = itemId;
            this.estimatedMargin = estimatedMargin;
            this.fillEtaMinutes = fillEtaMinutes;
            this.fillConfidence = fillConfidence;
            this.etaSource = etaSource;
            this.spreadRegime = spreadRegime;
            this.regimeCertainty = regimeCertainty;
            this.hasItemModel = hasItemModel;
            this.etaBasis = etaBasis;
            this.anomalyScore = anomalyScore;
            this.inOptimalWindow = inOptimalWindow;
            this.minutesUntilReset = minutesUntilReset;
            this.willFillBeforeReset = willFillBeforeReset;
        }

        boolean hasAnomalyWarning() {
            return anomalyScore > 0.6;
        }
    }
}
