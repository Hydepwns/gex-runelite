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

        // Color ETA by confidence: green >70%, yellow 40-70%, red <40%
        Color etaColor = pct >= 70 ? PROFIT_COLOR : (pct >= 40 ? NEUTRAL_COLOR : LOSS_COLOR);

        TextComponent etaComp = new TextComponent();
        etaComp.setText("~" + etaText + " " + pct + "%" + dayHint);
        etaComp.setColor(etaColor);
        etaComp.setPosition(new Point(bounds.x + 5, bounds.y + bounds.height - 15));
        etaComp.render(graphics);
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

        SlotData(int itemId, long estimatedMargin, double fillEtaMinutes, double fillConfidence, String etaSource) {
            this.itemId = itemId;
            this.estimatedMargin = estimatedMargin;
            this.fillEtaMinutes = fillEtaMinutes;
            this.fillConfidence = fillConfidence;
            this.etaSource = etaSource;
        }
    }
}
