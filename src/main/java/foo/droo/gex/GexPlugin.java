package foo.droo.gex;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import okhttp3.*;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@PluginDescriptor(
    name = "GEX",
    description = "Crowdsourced GE fill data - see actual fill times from real trades, not wiki guesses. Day-aware ETAs and margin overlay.",
    tags = {"grand exchange", "trading", "flipping", "margins", "fill time"}
)
public class GexPlugin extends Plugin implements GexApiClient.ConnectionListener {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private static final Gson GSON = GexApiClient.getGson();

    private static final int BATCH_INTERVAL_MS = 5000;
    private static final long HIGH_VALUE_COOLDOWN_MS = 600_000;
    private static final int MAX_OFFLINE_CACHE_SIZE = 100;
    private static final int EVICTION_WARNING_THRESHOLD = 5;

    @Inject
    private Client client;

    @Inject
    private GexConfig config;

    @Inject
    private OkHttpClient httpClient;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private Notifier notifier;

    @Inject
    private ItemManager itemManager;

    @Inject
    private KeyManager keyManager;

    private GexApiClient apiClient;
    private QuickPriceKeyListener quickPriceKeyListener;
    private GexPanel panel;
    private GexOverlay overlay;
    private NavigationButton navButton;

    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> batchTask;
    private ScheduledFuture<?> dataFetchTask;
    private ScheduledFuture<?> marketRegimeTask;

    // Cache for per-item ML data (itemId -> ML data)
    private final Map<Integer, ItemMlData> itemMlCache = new ConcurrentHashMap<>();
    private static final long ITEM_ML_CACHE_TTL_MS = 300_000; // 5 minutes

    // Track pending ML data fetches to avoid duplicate requests
    private final Set<Integer> pendingMlFetches = ConcurrentHashMap.newKeySet();

    // Cache for item metadata (name, buy_limit) from predictions
    private final Map<Integer, ItemMetadata> itemMetadataCache = new ConcurrentHashMap<>();

    // Cache for queue estimates (slot index -> estimate data)
    private final Map<Integer, QueueEstimate> queueEstimateCache = new ConcurrentHashMap<>();
    private static final long QUEUE_ESTIMATE_CACHE_TTL_MS = 30_000; // 30 seconds

    // Prediction tracking for accuracy dashboard
    private final Map<Integer, PredictionRecord> activePredictions = new ConcurrentHashMap<>();
    private volatile AccuracyStats cachedAccuracyStats = null;
    private volatile long accuracyStatsFetchedAt = 0;
    private static final long ACCURACY_STATS_CACHE_TTL_MS = 300_000; // 5 minutes

    private int eventsSent = 0;
    private volatile boolean connected = false;

    // Event batching queue with bounded capacity for offline resilience
    private final LinkedBlockingQueue<Map<String, Object>> eventQueue = new LinkedBlockingQueue<>(MAX_OFFLINE_CACHE_SIZE);

    // Sequence tracking per slot
    private final Map<Integer, Long> slotSequences = new ConcurrentHashMap<>();

    // Track last known state per slot for idempotency
    private final Map<Integer, String> lastSlotStates = new ConcurrentHashMap<>();

    // Track last heartbeat state per slot for delta encoding
    private final Map<Integer, String> lastHeartbeatStates = new ConcurrentHashMap<>();

    // Notification anti-spam tracking
    private final Set<String> fillNotifiedKeys = ConcurrentHashMap.newKeySet();
    private final Map<Integer, String> stallNotifiedSlots = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> highValueCooldowns = new ConcurrentHashMap<>();

    // Track evicted events for user warning
    private volatile int evictedEventCount = 0;
    private volatile boolean evictionWarningShown = false;

    // Fill curve cache: itemId -> cached data
    private final FillCurveCache fillCurveCache = new FillCurveCache();

    @Override
    protected void startUp() {
        log.info("GEX plugin started");

        // Validate endpoint URL
        String endpointError = GexApiClient.validateEndpointUrl(config.apiEndpoint());
        if (endpointError != null) {
            log.error("GEX: Invalid API endpoint configuration: {}", endpointError);
            notifier.notify("GEX: Invalid API endpoint - " + endpointError);
        }

        // Set up API client
        apiClient = new GexApiClient(httpClient, executor, config);
        apiClient.setConnectionListener(this);

        // Perform health check on startup
        apiClient.healthCheckAsync((healthy, message) -> {
            if (healthy) {
                log.info("GEX: {}", message);
            } else {
                log.warn("GEX: Health check failed - {}", message);
                // Don't notify on startup failure - user will see disconnected status
            }
        });

        // Set up panel
        panel = new GexPanel();

        navButton = NavigationButton.builder()
            .tooltip("GEX")
            .icon(buildIcon())
            .priority(5)
            .panel(panel)
            .build();

        if (config.showPanel()) {
            clientToolbar.addNavigation(navButton);
        }

        // Set up overlay
        overlay = new GexOverlay(client, config);
        overlayManager.add(overlay);

        // Set up quick price hotkey
        quickPriceKeyListener = new QuickPriceKeyListener();
        keyManager.registerKeyListener(quickPriceKeyListener);

        scheduleHeartbeat();
        scheduleBatchSend();
        scheduleDataFetch();
        scheduleMarketRegimeFetch();
    }

    @Override
    protected void shutDown() {
        log.info("GEX plugin stopped");

        clientToolbar.removeNavigation(navButton);
        overlayManager.remove(overlay);
        keyManager.unregisterKeyListener(quickPriceKeyListener);

        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        if (batchTask != null) {
            batchTask.cancel(false);
            batchTask = null;
        }
        if (dataFetchTask != null) {
            dataFetchTask.cancel(false);
            dataFetchTask = null;
        }
        if (marketRegimeTask != null) {
            marketRegimeTask.cancel(false);
            marketRegimeTask = null;
        }
        // Flush remaining events before shutdown
        flushBatch();

        fillNotifiedKeys.clear();
        stallNotifiedSlots.clear();
        highValueCooldowns.clear();
        lastHeartbeatStates.clear();
        itemMlCache.clear();
        pendingMlFetches.clear();
        itemMetadataCache.clear();
        queueEstimateCache.clear();
        activePredictions.clear();
    }

    @Provides
    GexConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GexConfig.class);
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
        if (!config.enabled()) {
            return;
        }

        GrandExchangeOffer offer = event.getOffer();
        int slot = event.getSlot();

        // Generate idempotency key based on slot state
        String stateKey = buildStateKey(slot, offer);
        String lastState = lastSlotStates.get(slot);

        // Skip if state hasn't changed (deduplication)
        if (stateKey.equals(lastState)) {
            return;
        }
        lastSlotStates.put(slot, stateKey);

        // Reset stall notification on any state change
        stallNotifiedSlots.remove(slot);

        // Notify on fill completion and submit accuracy outcome
        GrandExchangeOfferState state = offer.getState();
        if ((state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD)
                && fillNotifiedKeys.add(stateKey)) {
            // Submit prediction outcome for accuracy tracking
            submitPredictionOutcome(slot, offer, true);

            if (config.notifyFillCompletion()) {
                notifyFillCompletion(slot, offer);
            }
        }

        // Handle cancelled orders - submit as not filled
        if ((state == GrandExchangeOfferState.CANCELLED_BUY || state == GrandExchangeOfferState.CANCELLED_SELL)) {
            submitPredictionOutcome(slot, offer, false);
        }

        // Increment sequence number for this slot
        long seq = slotSequences.compute(slot, (k, v) -> v == null ? 1L : v + 1);

        Map<String, Object> payload = buildOfferPayload(slot, offer, "offer_update", seq);
        queueEvent(payload);
    }

    private String buildStateKey(int slot, GrandExchangeOffer offer) {
        return String.format("%d:%d:%d:%d:%s",
            slot,
            offer.getItemId(),
            offer.getQuantitySold(),
            offer.getPrice(),
            offer.getState().name()
        );
    }

    private void scheduleHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }

        int interval = config.heartbeatInterval();
        if (interval > 0) {
            heartbeatTask = executor.scheduleAtFixedRate(
                this::sendHeartbeat,
                interval,
                interval,
                TimeUnit.SECONDS
            );
        }
    }

    private void scheduleBatchSend() {
        if (batchTask != null) {
            batchTask.cancel(false);
        }

        batchTask = executor.scheduleAtFixedRate(
            this::flushBatch,
            BATCH_INTERVAL_MS,
            BATCH_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    private void scheduleDataFetch() {
        if (dataFetchTask != null) {
            dataFetchTask.cancel(false);
        }

        // Fetch slot data every 30 seconds
        dataFetchTask = executor.scheduleAtFixedRate(
            this::fetchSlotData,
            5000,
            30000,
            TimeUnit.MILLISECONDS
        );
    }

    private void scheduleMarketRegimeFetch() {
        if (marketRegimeTask != null) {
            marketRegimeTask.cancel(false);
        }

        // Fetch market regime every 60 seconds
        marketRegimeTask = executor.scheduleAtFixedRate(
            this::fetchMarketRegime,
            10000,
            60000,
            TimeUnit.MILLISECONDS
        );
    }

    @SuppressWarnings("unchecked")
    private void fetchQueueEstimates() {
        if (!config.enabled() || client.getLocalPlayer() == null) {
            return;
        }

        String accountHash = hashAccountId(client.getAccountHash());

        apiClient.fetchSlotsWithEstimatesAsync(accountHash, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.debug("Failed to fetch queue estimates: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        Map<String, Object> data = GSON.fromJson(body, Map.class);
                        if (data != null && data.containsKey("slots")) {
                            List<?> slots = (List<?>) data.get("slots");
                            for (Object slotObj : slots) {
                                if (!(slotObj instanceof Map)) continue;
                                Map<String, Object> slot = (Map<String, Object>) slotObj;

                                Number indexNum = (Number) slot.get("index");
                                if (indexNum == null) continue;
                                int index = indexNum.intValue();

                                Map<String, Object> queueEstimate = (Map<String, Object>) slot.get("queue_estimate");
                                if (queueEstimate != null) {
                                    Number etaMins = (Number) queueEstimate.get("estimated_fill_minutes");
                                    String basis = (String) queueEstimate.get("basis");

                                    // The backend returns remaining_quantity, we estimate reset info
                                    // For now, use a simple heuristic: if ETA > 180 min, likely won't fill
                                    double eta = etaMins != null ? etaMins.doubleValue() : 0;
                                    int minutesUntilReset = 240; // Default 4h window
                                    boolean willFill = eta < minutesUntilReset;

                                    queueEstimateCache.put(index, new QueueEstimate(
                                        minutesUntilReset, willFill, 0.0, basis
                                    ));
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error processing queue estimates: {}", e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void fetchMarketRegime() {
        if (!config.enabled()) {
            return;
        }

        // Also fetch accuracy stats when fetching market regime
        fetchAccuracyStats();

        apiClient.fetchMarketRegimeAsync(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.debug("Failed to fetch market regime: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        Map<String, Object> wrapper = GSON.fromJson(body, Map.class);
                        if (wrapper != null && wrapper.containsKey("data")) {
                            Map<String, Object> data = (Map<String, Object>) wrapper.get("data");
                            String riskLevel = (String) data.get("risk_level");
                            String dominantRegime = (String) data.get("dominant_regime");
                            panel.updateMarketRisk(riskLevel, dominantRegime);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error processing market regime: {}", e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void fetchItemMlData(int itemId) {
        ItemMlData cached = itemMlCache.get(itemId);
        if (cached != null && !cached.isExpired()) {
            return;
        }

        // Avoid duplicate in-flight requests
        if (!pendingMlFetches.add(itemId)) {
            return;
        }

        apiClient.fetchItemMlDataAsync(itemId, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                pendingMlFetches.remove(itemId);
                log.debug("Failed to fetch ML data for item {}: {}", itemId, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        Map<String, Object> wrapper = GSON.fromJson(body, Map.class);
                        if (wrapper != null && wrapper.containsKey("data")) {
                            Map<String, Object> data = (Map<String, Object>) wrapper.get("data");

                            // Parse spread regime
                            Map<String, Object> regimeData = (Map<String, Object>) data.get("spread_regime");
                            String regime = regimeData != null ? (String) regimeData.get("regime") : null;
                            double certainty = regimeData != null ?
                                ((Number) regimeData.getOrDefault("certainty", 0.0)).doubleValue() : 0.0;

                            // Parse per-item model availability
                            Map<String, Object> modelData = (Map<String, Object>) data.get("per_item_model");
                            boolean hasItemModel = modelData != null &&
                                Boolean.TRUE.equals(modelData.get("available"));

                            // Parse anomaly score
                            Number anomalyNum = (Number) data.get("anomaly_score");
                            double anomalyScore = anomalyNum != null ? anomalyNum.doubleValue() : 0.0;

                            // Parse optimal trading hours
                            int buyStart = -1, buyEnd = -1, sellStart = -1, sellEnd = -1;
                            Map<String, Object> tradingHours = (Map<String, Object>) data.get("optimal_trading_hours");
                            if (tradingHours != null) {
                                Map<String, Object> buyWindow = (Map<String, Object>) tradingHours.get("buy_window");
                                if (buyWindow != null) {
                                    Number start = (Number) buyWindow.get("start");
                                    Number end = (Number) buyWindow.get("end");
                                    if (start != null) buyStart = start.intValue();
                                    if (end != null) buyEnd = end.intValue();
                                }
                                Map<String, Object> sellWindow = (Map<String, Object>) tradingHours.get("sell_window");
                                if (sellWindow != null) {
                                    Number start = (Number) sellWindow.get("start");
                                    Number end = (Number) sellWindow.get("end");
                                    if (start != null) sellStart = start.intValue();
                                    if (end != null) sellEnd = end.intValue();
                                }
                            }

                            itemMlCache.put(itemId, new ItemMlData(
                                regime, certainty, hasItemModel, anomalyScore,
                                buyStart, buyEnd, sellStart, sellEnd
                            ));

                            // Cache item metadata if present
                            String itemName = (String) data.get("item_name");
                            if (itemName != null) {
                                itemMetadataCache.put(itemId, new ItemMetadata(itemName, 0));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error processing ML data for item {}: {}", itemId, e.getMessage());
                } finally {
                    pendingMlFetches.remove(itemId);
                    response.close();
                }
            }
        });
    }

    private void fetchSlotData() {
        if (!config.enabled() || client.getLocalPlayer() == null) {
            return;
        }

        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) {
            return;
        }

        // Collect active item IDs
        List<Integer> itemIds = new ArrayList<>();
        for (GrandExchangeOffer offer : offers) {
            if (offer != null && offer.getState() != GrandExchangeOfferState.EMPTY) {
                itemIds.add(offer.getItemId());
            }
        }

        if (itemIds.isEmpty()) {
            return;
        }

        String accountHash = hashAccountId(client.getAccountHash());

        // Also fetch queue estimates
        fetchQueueEstimates();

        apiClient.fetchPredictionsAsync(itemIds, accountHash, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.debug("Failed to fetch slot data: {}", e.getMessage());
                setConnected(false);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        setConnected(true);
                        String body = response.body().string();
                        processSlotPredictions(body, offers);
                    }
                } catch (IOException e) {
                    log.debug("Error reading slot data response: {}", e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void processSlotPredictions(String json, GrandExchangeOffer[] offers) {
        try {
            Map<String, Object> data = GSON.fromJson(json, Map.class);
            if (data == null) {
                log.debug("Empty response from predictions endpoint");
                return;
            }

            Object predictionsObj = data.get("predictions");
            if (!(predictionsObj instanceof List)) {
                log.debug("Invalid predictions format: expected List, got {}",
                    predictionsObj != null ? predictionsObj.getClass().getSimpleName() : "null");
                return;
            }
            List<?> predictionsList = (List<?>) predictionsObj;

            Map<Integer, Map<String, Object>> predictionsByItemId = new HashMap<>();
            for (Object predObj : predictionsList) {
                if (!(predObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> pred = (Map<String, Object>) predObj;
                Object itemIdObj = pred.get("item_id");
                if (itemIdObj instanceof Number) {
                    int itemId = ((Number) itemIdObj).intValue();
                    predictionsByItemId.put(itemId, pred);

                    // Cache item metadata from predictions to avoid N+1 lookups
                    String itemName = (String) pred.get("item_name");
                    Number buyLimit = (Number) pred.get("buy_limit");
                    if (itemName != null) {
                        itemMetadataCache.put(itemId, new ItemMetadata(
                            itemName,
                            buyLimit != null ? buyLimit.intValue() : 0
                        ));
                    }
                }
            }

            // Collect items that need fill curve data (batch fetch)
            List<Integer> itemsNeedingCurves = new ArrayList<>();
            for (GrandExchangeOffer offer : offers) {
                if (offer != null && offer.getState() != GrandExchangeOfferState.EMPTY) {
                    if (!fillCurveCache.hasValidEntry(offer.getItemId())) {
                        itemsNeedingCurves.add(offer.getItemId());
                    }
                }
            }

            // Batch fetch fill curves if needed
            if (!itemsNeedingCurves.isEmpty()) {
                fetchFillCurvesBatch(itemsNeedingCurves);
            }

            for (int i = 0; i < offers.length; i++) {
                GrandExchangeOffer offer = offers[i];
                if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY) {
                    overlay.clearSlotData(i);
                    continue;
                }

                int itemId = offer.getItemId();

                // Fetch ML data for this item if not cached
                fetchItemMlData(itemId);

                Map<String, Object> pred = predictionsByItemId.get(itemId);
                if (pred != null) {
                    Number margin = (Number) pred.getOrDefault("estimated_margin", 0);
                    Number eta = (Number) pred.getOrDefault("fill_eta_minutes", 0);
                    Number confidence = (Number) pred.getOrDefault("fill_confidence", 0);

                    // Resolve day-aware ETA
                    double[] etaResult = resolveDayAwareEta(
                            itemId, eta.doubleValue(),
                            fillCurveCache, config.showDayAwareEta());
                    double resolvedEta = etaResult[0];
                    int etaDayOfWeek = (int) etaResult[1];
                    String etaSource = etaDayOfWeek > 0 ? dayAbbreviation(etaDayOfWeek) : null;

                    // Get cached ML data
                    ItemMlData mlData = itemMlCache.get(itemId);
                    String spreadRegime = mlData != null ? mlData.spreadRegime : null;
                    double regimeCertainty = mlData != null ? mlData.regimeCertainty : 0.0;
                    boolean hasItemModel = mlData != null && mlData.hasItemModel;
                    double anomalyScore = mlData != null ? mlData.anomalyScore : 0.0;

                    // Check if in optimal trading window
                    int currentHourUtc = LocalDateTime.now(ZoneOffset.UTC).getHour();
                    boolean isBuying = offer.getState() == GrandExchangeOfferState.BUYING;
                    boolean inOptimalWindow = false;
                    if (mlData != null) {
                        inOptimalWindow = isBuying ?
                            mlData.isInOptimalBuyWindow(currentHourUtc) :
                            mlData.isInOptimalSellWindow(currentHourUtc);
                    }

                    // Get queue estimate data
                    QueueEstimate queueEst = queueEstimateCache.get(i);
                    int minutesUntilReset = queueEst != null ? queueEst.minutesUntilReset : -1;
                    boolean willFillBeforeReset = queueEst != null ? queueEst.willFillBeforeReset : true;

                    // Determine ETA basis (velocity > historical > heuristic)
                    String etaBasis = etaDayOfWeek > 0 ? "velocity" :
                        (queueEst != null && queueEst.basis != null ? queueEst.basis : "historical");

                    overlay.updateSlotDataFull(
                        i,
                        itemId,
                        margin.longValue(),
                        resolvedEta,
                        confidence.doubleValue(),
                        etaSource,
                        spreadRegime,
                        regimeCertainty,
                        hasItemModel,
                        etaBasis,
                        anomalyScore,
                        inOptimalWindow,
                        minutesUntilReset,
                        willFillBeforeReset
                    );

                    // Record prediction for accuracy tracking (only for active orders)
                    GrandExchangeOfferState state = offer.getState();
                    if (state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING) {
                        if (!activePredictions.containsKey(i)) {
                            activePredictions.put(i, new PredictionRecord(
                                itemId, i, resolvedEta, confidence.doubleValue()
                            ));
                        }
                    }

                    // Stall notification
                    if (config.notifyStall()
                            && (offer.getState() == GrandExchangeOfferState.BUYING || offer.getState() == GrandExchangeOfferState.SELLING)
                            && shouldNotifyStall(eta.doubleValue(), config.stallThresholdMinutes())) {
                        String stallKey = "slot:" + offer.getItemId() + ":" + offer.getState().name();
                        String prev = stallNotifiedSlots.put(i, stallKey);
                        if (!stallKey.equals(prev)) {
                            notifyStall(i, eta.doubleValue());
                        }
                    }

                    // High-value signal notification
                    if (config.notifyHighValue()
                            && shouldNotifyHighValue(margin.longValue(), confidence.doubleValue(),
                                    config.highValueMarginThreshold(), config.highValueConfidenceThreshold())) {
                        Instant cooldownExpiry = highValueCooldowns.get(offer.getItemId());
                        if (cooldownExpiry == null || Instant.now().isAfter(cooldownExpiry)) {
                            highValueCooldowns.put(offer.getItemId(), Instant.now().plusMillis(HIGH_VALUE_COOLDOWN_MS));
                            notifyHighValue(offer.getItemId(), margin.longValue(), confidence.doubleValue());
                        }
                    }
                }
            }

            // Update panel stats
            int activeOrders = 0;
            for (GrandExchangeOffer offer : offers) {
                if (offer != null && offer.getState() != GrandExchangeOfferState.EMPTY) {
                    activeOrders++;
                }
            }

            Object pnlObj = data.get("daily_pnl");
            String pnl = pnlObj instanceof String ? (String) pnlObj : "-";
            panel.updateStats(eventsSent, activeOrders, pnl, eventQueue.size());

            // Update trade history with type validation
            Object historyObj = data.get("recent_trades");
            List<Map<String, Object>> history = null;
            if (historyObj instanceof List) {
                history = new ArrayList<>();
                for (Object item : (List<?>) historyObj) {
                    if (item instanceof Map) {
                        history.add((Map<String, Object>) item);
                    }
                }
            }
            panel.updateHistory(history);

        } catch (Exception e) {
            log.debug("Error processing slot predictions: {}", e.getMessage());
        }
    }

    private void sendHeartbeat() {
        if (!config.enabled() || client.getLocalPlayer() == null) {
            return;
        }

        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) {
            return;
        }

        int changedCount = 0;
        for (int i = 0; i < offers.length; i++) {
            GrandExchangeOffer offer = offers[i];
            if (offer == null) {
                // Slot became empty, clear tracked state
                lastHeartbeatStates.remove(i);
                continue;
            }

            if (offer.getState() == GrandExchangeOfferState.EMPTY) {
                // Track that slot is now empty
                String lastState = lastHeartbeatStates.remove(i);
                if (lastState != null) {
                    // Slot changed from non-empty to empty, send update
                    long seq = slotSequences.getOrDefault(i, 0L);
                    Map<String, Object> payload = buildOfferPayload(i, offer, "heartbeat", seq);
                    queueEvent(payload);
                    changedCount++;
                }
                continue;
            }

            // Build state key for comparison (same format as event deduplication)
            String currentState = buildHeartbeatStateKey(i, offer);
            String lastState = lastHeartbeatStates.get(i);

            // Only send if state changed since last heartbeat
            if (!currentState.equals(lastState)) {
                lastHeartbeatStates.put(i, currentState);
                long seq = slotSequences.getOrDefault(i, 0L);
                Map<String, Object> payload = buildOfferPayload(i, offer, "heartbeat", seq);
                queueEvent(payload);
                changedCount++;
            }
        }

        if (changedCount > 0) {
            log.debug("Heartbeat sent {} changed slots (delta encoding)", changedCount);
        }
    }

    /**
     * Build a state key for heartbeat delta comparison.
     * Includes all fields that constitute a meaningful state change.
     */
    private String buildHeartbeatStateKey(int slot, GrandExchangeOffer offer) {
        return String.format("%d:%d:%d:%d:%d:%s",
            slot,
            offer.getItemId(),
            offer.getQuantitySold(),
            offer.getTotalQuantity(),
            offer.getPrice(),
            offer.getState().name()
        );
    }

    private void queueEvent(Map<String, Object> payload) {
        // FIFO eviction: if queue is full, remove oldest event to make room
        while (!eventQueue.offer(payload)) {
            Map<String, Object> evicted = eventQueue.poll();
            if (evicted != null) {
                evictedEventCount++;
                logEvictedEvent(evicted);

                // Warn user once when eviction threshold reached
                if (!evictionWarningShown && evictedEventCount >= EVICTION_WARNING_THRESHOLD) {
                    evictionWarningShown = true;
                    notifyDataLoss();
                }
            }
        }
        eventsSent++;
    }

    @SuppressWarnings("unchecked")
    private void logEvictedEvent(Map<String, Object> evicted) {
        try {
            Map<String, Object> slot = (Map<String, Object>) evicted.get("slot");
            if (slot != null) {
                Object itemId = slot.get("item_id");
                Object offerType = slot.get("offer_type");
                Object state = slot.get("state");
                log.warn("Event queue full - lost event: item_id={}, offer_type={}, state={}",
                    itemId, offerType, state);
            } else {
                log.warn("Event queue full - lost event (no slot data)");
            }
        } catch (Exception e) {
            log.warn("Event queue full - lost event (parse error)");
        }
    }

    private void notifyDataLoss() {
        String msg = String.format(
            "GEX: Network issues detected - %d events lost. Some trade data may be missing.",
            evictedEventCount);
        notifier.notify(msg);
        log.warn("Data loss notification sent: {} events evicted from queue", evictedEventCount);
    }

    private void flushBatch() {
        if (eventQueue.isEmpty()) {
            return;
        }

        List<Map<String, Object>> batch = new ArrayList<>();
        Map<String, Object> event;
        while ((event = eventQueue.poll()) != null && batch.size() < 50) {
            batch.add(event);
        }

        if (!batch.isEmpty()) {
            sendBatch(batch);
        }
    }

    private Map<String, Object> buildOfferPayload(int slotIndex, GrandExchangeOffer offer, String eventType, long sequence) {
        Map<String, Object> payload = new HashMap<>();

        long accountHash = client.getAccountHash();
        String timestamp = ISO_FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC));

        payload.put("account_hash", hashAccountId(accountHash));
        payload.put("timestamp", timestamp);
        payload.put("event_type", eventType);

        // Idempotency key: hash of account + slot + item + filled + state + timestamp(second precision)
        String idempotencyKey = generateIdempotencyKey(accountHash, slotIndex, offer, timestamp);
        payload.put("idempotency_key", idempotencyKey);

        Map<String, Object> slot = new HashMap<>();
        slot.put("index", slotIndex);
        slot.put("item_id", offer.getItemId());
        slot.put("offer_type", offer.getState().toString().contains("BUY") ? "buy" : "sell");
        slot.put("price", offer.getPrice());
        slot.put("quantity_total", offer.getTotalQuantity());
        slot.put("quantity_filled", offer.getQuantitySold());
        slot.put("state", mapState(offer.getState()));
        slot.put("spent", offer.getSpent());
        slot.put("sequence", sequence);

        payload.put("slot", slot);

        return payload;
    }

    /**
     * Hash the RuneLite account hash with SHA-256 for privacy.
     * This ensures the raw RuneLite hash is never transmitted or stored.
     */
    private String hashAccountId(long accountId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Include a fixed prefix to prevent rainbow table attacks
            String input = "gex:" + accountId;
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) { // 32 hex chars (128 bits) - sufficient for uniqueness
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available, but fallback just in case
            return Long.toHexString(accountId);
        }
    }

    private String generateIdempotencyKey(long accountHash, int slot, GrandExchangeOffer offer, String timestamp) {
        String input = String.format("%d:%d:%d:%d:%s:%s",
            accountHash,
            slot,
            offer.getItemId(),
            offer.getQuantitySold(),
            offer.getState().name(),
            timestamp.substring(0, 19) // Second precision
        );

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) { // Full 32 hex chars (128 bits)
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return input.hashCode() + "";
        }
    }

    static String mapState(GrandExchangeOfferState state) {
        switch (state) {
            case BUYING:
                return "buying";
            case BOUGHT:
                return "bought";
            case SELLING:
                return "selling";
            case SOLD:
                return "sold";
            case CANCELLED_BUY:
                return "cancelled_buy";
            case CANCELLED_SELL:
                return "cancelled_sell";
            case EMPTY:
            default:
                return "empty";
        }
    }

    /**
     * Get item name from cache, falling back to itemManager.
     */
    private String getItemName(int itemId) {
        ItemMetadata cached = itemMetadataCache.get(itemId);
        if (cached != null && cached.name != null) {
            return cached.name;
        }
        return itemManager.getItemComposition(itemId).getName();
    }

    private void notifyFillCompletion(int slot, GrandExchangeOffer offer) {
        String itemName = getItemName(offer.getItemId());
        String action = offer.getState() == GrandExchangeOfferState.BOUGHT ? "Bought" : "Sold";
        long totalValue = (long) offer.getPrice() * offer.getQuantitySold();
        String msg = String.format("GEX: %s %d x %s for %s gp (slot %d)",
            action, offer.getQuantitySold(), itemName, GpFormatter.format(totalValue), slot + 1);
        notifier.notify(msg);
    }

    private void notifyStall(int slot, double etaMinutes) {
        String msg = String.format("GEX: %s on slot %d may be stalled (ETA %.1fh, threshold %dm)",
            lastSlotStates.containsKey(slot) && lastSlotStates.get(slot).contains("BUYING") ? "Buy" : "Sell",
            slot + 1, etaMinutes / 60.0, config.stallThresholdMinutes());
        notifier.notify(msg);
    }

    private void submitPredictionOutcome(int slot, GrandExchangeOffer offer, boolean filled) {
        PredictionRecord prediction = activePredictions.remove(slot);
        if (prediction == null || prediction.itemId != offer.getItemId()) {
            return;
        }

        String accountHash = hashAccountId(client.getAccountHash());
        double actualMinutes = prediction.getActualMinutes();

        apiClient.submitOutcomeAsync(
            offer.getItemId(),
            accountHash,
            filled,
            prediction.predictedEtaMinutes,
            actualMinutes,
            new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    log.debug("Failed to submit prediction outcome: {}", e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    response.close();
                    log.debug("Submitted prediction outcome: item={} filled={} predicted={}m actual={}m",
                        offer.getItemId(), filled, prediction.predictedEtaMinutes, actualMinutes);
                }
            }
        );
    }

    @SuppressWarnings("unchecked")
    private void fetchAccuracyStats() {
        if (System.currentTimeMillis() - accuracyStatsFetchedAt < ACCURACY_STATS_CACHE_TTL_MS) {
            return;
        }

        apiClient.fetchAccuracyAsync(7, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.debug("Failed to fetch accuracy stats: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        Map<String, Object> wrapper = GSON.fromJson(body, Map.class);
                        if (wrapper != null && wrapper.containsKey("data")) {
                            Map<String, Object> data = (Map<String, Object>) wrapper.get("data");
                            Number total = (Number) data.get("total_predictions");
                            Number correct = (Number) data.get("correct_predictions");
                            Number accuracy = (Number) data.get("accuracy_percent");

                            cachedAccuracyStats = new AccuracyStats(
                                total != null ? total.intValue() : 0,
                                correct != null ? correct.intValue() : 0,
                                accuracy != null ? accuracy.doubleValue() : 0.0
                            );
                            accuracyStatsFetchedAt = System.currentTimeMillis();

                            // Update panel with accuracy
                            panel.updateAccuracy(cachedAccuracyStats.accuracyPercent,
                                cachedAccuracyStats.totalPredictions);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error processing accuracy stats: {}", e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    private void notifyHighValue(int itemId, long margin, double confidence) {
        String itemName = getItemName(itemId);
        String msg = String.format("GEX: High-value signal for %s — margin %s gp, %.0f%% confidence",
            itemName, GpFormatter.format(margin), confidence);
        notifier.notify(msg);
    }

    /**
     * Resolves the best ETA for a slot using day-aware fill curves with fallback.
     * Returns a two-element array: [etaMinutes, dayOfWeek (0 if not day-aware)].
     */
    static double[] resolveDayAwareEta(
            int itemId,
            double predictionEta,
            FillCurveCache cache,
            boolean dayAwareEnabled) {

        if (!dayAwareEnabled) {
            return new double[]{predictionEta, 0};
        }

        FillCurveCache.FillCurveData data = cache.get(itemId);
        if (data == null) {
            return new double[]{predictionEta, 0};
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int currentDay = now.getDayOfWeek().getValue(); // 1=Mon..7=Sun (ISO)
        int currentHour = now.getHour();

        // Tier 1: day-specific data
        Map<Integer, Map<String, Number>> dayGrid = data.dailyGrid.get(currentDay);
        if (dayGrid != null) {
            Map<String, Number> hourStats = dayGrid.get(currentHour);
            if (hourStats != null) {
                Number median = hourStats.get("median");
                if (median != null && median.doubleValue() > 0) {
                    return new double[]{median.doubleValue() / 60.0, currentDay};
                }
            }
        }

        // Tier 2: aggregate by hour
        Map<String, Number> aggStats = data.aggregateByHour.get(currentHour);
        if (aggStats != null) {
            Number median = aggStats.get("median");
            if (median != null && median.doubleValue() > 0) {
                return new double[]{median.doubleValue() / 60.0, 0};
            }
        }

        // Tier 3: prediction ETA
        return new double[]{predictionEta, 0};
    }

    @SuppressWarnings("unchecked")
    private void fetchFillCurves(int itemId, String offerType) {
        if (fillCurveCache.hasValidEntry(itemId)) {
            return;
        }

        apiClient.fetchFillCurvesAsync(itemId, offerType, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.debug("Failed to fetch fill curves for item {}: {}", itemId, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        fillCurveCache.parseAndCache(itemId, body);
                    }
                } catch (IOException e) {
                    log.debug("Error reading fill curve response: {}", e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * Batch fetch fill curves for multiple items in a single request.
     */
    private void fetchFillCurvesBatch(List<Integer> itemIds) {
        if (itemIds.isEmpty()) {
            return;
        }

        apiClient.fetchFillCurvesBatchAsync(itemIds, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.debug("Failed to batch fetch fill curves: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        fillCurveCache.parseBatchAndCache(body);
                    }
                } catch (IOException e) {
                    log.debug("Error reading batch fill curve response: {}", e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    private static final String[] DAY_ABBREVS = {"", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    static String dayAbbreviation(int dayOfWeek) {
        return (dayOfWeek >= 1 && dayOfWeek <= 7) ? DAY_ABBREVS[dayOfWeek] : "";
    }

    static boolean shouldNotifyStall(double etaMinutes, int thresholdMinutes) {
        return etaMinutes >= thresholdMinutes;
    }

    static boolean shouldNotifyHighValue(long margin, double confidence, int marginThreshold, int confidenceThreshold) {
        return margin >= marginThreshold && confidence >= confidenceThreshold;
    }

    /**
     * Cached ML data for an item.
     */
    private static class ItemMlData {
        final String spreadRegime;
        final double regimeCertainty;
        final boolean hasItemModel;
        final double anomalyScore;
        final int buyWindowStart;  // Hour UTC, -1 if not available
        final int buyWindowEnd;
        final int sellWindowStart;
        final int sellWindowEnd;
        final long cachedAt;

        ItemMlData(String spreadRegime, double regimeCertainty, boolean hasItemModel,
                   double anomalyScore, int buyWindowStart, int buyWindowEnd,
                   int sellWindowStart, int sellWindowEnd) {
            this.spreadRegime = spreadRegime;
            this.regimeCertainty = regimeCertainty;
            this.hasItemModel = hasItemModel;
            this.anomalyScore = anomalyScore;
            this.buyWindowStart = buyWindowStart;
            this.buyWindowEnd = buyWindowEnd;
            this.sellWindowStart = sellWindowStart;
            this.sellWindowEnd = sellWindowEnd;
            this.cachedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > ITEM_ML_CACHE_TTL_MS;
        }

        boolean hasAnomalyWarning() {
            return anomalyScore > 0.6;
        }

        boolean isInOptimalBuyWindow(int currentHourUtc) {
            if (buyWindowStart < 0) return false;
            if (buyWindowStart <= buyWindowEnd) {
                return currentHourUtc >= buyWindowStart && currentHourUtc <= buyWindowEnd;
            } else {
                // Window wraps around midnight
                return currentHourUtc >= buyWindowStart || currentHourUtc <= buyWindowEnd;
            }
        }

        boolean isInOptimalSellWindow(int currentHourUtc) {
            if (sellWindowStart < 0) return false;
            if (sellWindowStart <= sellWindowEnd) {
                return currentHourUtc >= sellWindowStart && currentHourUtc <= sellWindowEnd;
            } else {
                return currentHourUtc >= sellWindowStart || currentHourUtc <= sellWindowEnd;
            }
        }
    }

    /**
     * Cached item metadata from predictions.
     */
    private static class ItemMetadata {
        final String name;
        final int buyLimit;

        ItemMetadata(String name, int buyLimit) {
            this.name = name;
            this.buyLimit = buyLimit;
        }
    }

    /**
     * Cached queue estimate for a slot.
     */
    private static class QueueEstimate {
        final int minutesUntilReset;
        final boolean willFillBeforeReset;
        final double fillProbability;
        final String basis;  // "velocity", "historical", "heuristic"
        final long cachedAt;

        QueueEstimate(int minutesUntilReset, boolean willFillBeforeReset, double fillProbability, String basis) {
            this.minutesUntilReset = minutesUntilReset;
            this.willFillBeforeReset = willFillBeforeReset;
            this.fillProbability = fillProbability;
            this.basis = basis;
            this.cachedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > QUEUE_ESTIMATE_CACHE_TTL_MS;
        }
    }

    /**
     * Tracks a prediction for accuracy measurement.
     */
    private static class PredictionRecord {
        final int itemId;
        final int slotIndex;
        final double predictedEtaMinutes;
        final double fillConfidence;
        final long recordedAt;

        PredictionRecord(int itemId, int slotIndex, double predictedEtaMinutes, double fillConfidence) {
            this.itemId = itemId;
            this.slotIndex = slotIndex;
            this.predictedEtaMinutes = predictedEtaMinutes;
            this.fillConfidence = fillConfidence;
            this.recordedAt = System.currentTimeMillis();
        }

        double getActualMinutes() {
            return (System.currentTimeMillis() - recordedAt) / 60000.0;
        }
    }

    /**
     * Cached accuracy statistics.
     */
    private static class AccuracyStats {
        final int totalPredictions;
        final int correctPredictions;
        final double accuracyPercent;

        AccuracyStats(int total, int correct, double accuracy) {
            this.totalPredictions = total;
            this.correctPredictions = correct;
            this.accuracyPercent = accuracy;
        }
    }

    private void sendBatch(List<Map<String, Object>> batch) {
        apiClient.sendBatchAsync(batch, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // API client handles retries internally
            }

            @Override
            public void onResponse(Call call, Response response) {
                // Connection status updates handled via ConnectionListener
                response.close();
            }
        });
    }

    @Override
    public void onConnectionStatusChanged(boolean connected) {
        setConnected(connected);
    }

    private BufferedImage buildIcon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Gold coin background
        g.setColor(new Color(218, 165, 32));
        g.fillOval(1, 1, 14, 14);
        g.setColor(new Color(184, 134, 11));
        g.drawOval(1, 1, 13, 13);

        // "G" letter
        g.setColor(new Color(60, 40, 0));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.drawString("G", 3, 13);

        g.dispose();
        return img;
    }

    private void setConnected(boolean status) {
        boolean wasDisconnected = !connected;
        connected = status;

        // Check if rate limited
        if (apiClient != null && apiClient.isRateLimited()) {
            long remainingSec = apiClient.getRateLimitRemainingMs() / 1000;
            panel.setStatus("Rate limited " + remainingSec + "s");
        } else {
            panel.setStatus(status ? "Connected" : "Disconnected");
        }

        overlay.setConnected(status);

        // Reset eviction tracking when connection restored
        if (status && wasDisconnected) {
            evictedEventCount = 0;
            evictionWarningShown = false;
        }
    }

    // Track last item/offer type when user starts setting up an offer
    private volatile int lastSetupItemId = -1;
    private volatile String lastSetupOfferType = "buy";

    /**
     * Check if the GE price chatbox is open.
     */
    private boolean isPriceChatboxOpen() {
        Widget chatboxTitle = client.getWidget(ComponentID.CHATBOX_TITLE);
        if (chatboxTitle == null || chatboxTitle.isHidden()) {
            return false;
        }
        String text = chatboxTitle.getText();
        return text != null && (text.contains("Set a price") || text.contains("price per item"));
    }

    /**
     * Get the item ID for the offer being set up.
     * Uses the most recent offer that's in BUYING or SELLING state with partial fill.
     */
    private int getCurrentOfferItemId() {
        // First check if we have a tracked setup item
        if (lastSetupItemId > 0) {
            return lastSetupItemId;
        }

        // Fall back to finding an active offer being set up
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) {
            return -1;
        }

        // Look for slots that are in the process of being set up
        for (GrandExchangeOffer offer : offers) {
            if (offer != null && offer.getItemId() > 0) {
                GrandExchangeOfferState state = offer.getState();
                // BUYING/SELLING with 0 filled = being set up
                if ((state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING)
                        && offer.getQuantitySold() == 0) {
                    lastSetupItemId = offer.getItemId();
                    lastSetupOfferType = state == GrandExchangeOfferState.SELLING ? "sell" : "buy";
                    return offer.getItemId();
                }
            }
        }

        return -1;
    }

    /**
     * Get the offer type (buy/sell) for the current setup.
     */
    private String getCurrentOfferType() {
        // Ensure we've populated the setup info
        if (lastSetupItemId <= 0) {
            getCurrentOfferItemId();
        }
        return lastSetupOfferType;
    }

    /**
     * Clear the setup tracking when offers change significantly.
     */
    private void clearSetupTracking() {
        lastSetupItemId = -1;
        lastSetupOfferType = "buy";
    }

    /**
     * Inject a price into the chatbox input.
     */
    private void injectPrice(int price) {
        Widget chatboxInput = client.getWidget(ComponentID.CHATBOX_FULL_INPUT);
        if (chatboxInput != null && !chatboxInput.isHidden()) {
            String priceStr = price + "*";
            chatboxInput.setText(priceStr);
            log.debug("Injected quick price: {}", priceStr);
        }
    }

    /**
     * Fetch and inject quick price for the current GE offer.
     */
    private void fetchAndInjectQuickPrice() {
        int itemId = getCurrentOfferItemId();
        if (itemId <= 0) {
            log.debug("No item selected for quick price");
            return;
        }

        String offerType = getCurrentOfferType();

        apiClient.fetchQuickPriceAsync(itemId, offerType, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("Failed to fetch quick price: {}", e.getMessage());
            }

            @Override
            @SuppressWarnings("unchecked")
            public void onResponse(Call call, Response response) {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        Map<String, Object> data = GSON.fromJson(body, Map.class);

                        if (data != null && data.containsKey("optimal_price")) {
                            Number optimalPrice = (Number) data.get("optimal_price");
                            if (optimalPrice != null) {
                                int price = optimalPrice.intValue();
                                // Must run on client thread
                                executor.execute(() -> injectPrice(price));

                                String itemName = (String) data.getOrDefault("item_name", "Item");
                                String reasoning = (String) data.getOrDefault("reasoning", "");
                                log.info("Quick price for {}: {} gp ({})", itemName, price, reasoning);
                            }
                        }
                    }
                } catch (IOException e) {
                    log.debug("Error reading quick price response: {}", e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * Parse the configured hotkey string into key code and modifiers.
     */
    private int[] parseHotkey(String hotkeyStr) {
        if (hotkeyStr == null || hotkeyStr.isEmpty()) {
            return new int[]{KeyEvent.VK_Q, KeyEvent.SHIFT_DOWN_MASK};
        }

        String[] parts = hotkeyStr.toLowerCase().split("\\s+");
        int modifiers = 0;
        int keyCode = KeyEvent.VK_Q;

        for (String part : parts) {
            switch (part) {
                case "shift":
                    modifiers |= KeyEvent.SHIFT_DOWN_MASK;
                    break;
                case "ctrl":
                case "control":
                    modifiers |= KeyEvent.CTRL_DOWN_MASK;
                    break;
                case "alt":
                    modifiers |= KeyEvent.ALT_DOWN_MASK;
                    break;
                default:
                    if (part.length() == 1) {
                        keyCode = KeyEvent.getExtendedKeyCodeForChar(part.charAt(0));
                    }
                    break;
            }
        }

        return new int[]{keyCode, modifiers};
    }

    /**
     * Key listener for quick price hotkey.
     */
    private class QuickPriceKeyListener implements KeyListener {

        @Override
        public void keyTyped(KeyEvent e) {
            // Not used
        }

        @Override
        public void keyPressed(KeyEvent e) {
            if (!config.enableQuickPrice() || !config.enabled()) {
                return;
            }

            int[] hotkey = parseHotkey(config.quickPriceHotkey());
            int expectedKey = hotkey[0];
            int expectedModifiers = hotkey[1];

            int actualModifiers = e.getModifiersEx() & (
                KeyEvent.SHIFT_DOWN_MASK |
                KeyEvent.CTRL_DOWN_MASK |
                KeyEvent.ALT_DOWN_MASK
            );

            if (e.getKeyCode() == expectedKey && actualModifiers == expectedModifiers) {
                if (isPriceChatboxOpen()) {
                    e.consume();
                    fetchAndInjectQuickPrice();
                }
            }
        }

        @Override
        public void keyReleased(KeyEvent e) {
            // Not used
        }
    }
}
