package foo.droo.gex;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for fill curve data with TTL-based expiration.
 */
@Slf4j
public class FillCurveCache {

    private static final long DEFAULT_TTL_MS = 180_000; // 3 minutes (reduced for fresher regime data)

    private final Map<Integer, FillCurveEntry> cache = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final Gson gson;

    public FillCurveCache(Gson gson) {
        this(gson, DEFAULT_TTL_MS);
    }

    public FillCurveCache(Gson gson, long ttlMs) {
        this.gson = gson;
        this.ttlMs = ttlMs;
    }

    /**
     * Gets cached fill curve data for an item.
     * Returns null if not cached or expired.
     */
    public FillCurveData get(int itemId) {
        FillCurveEntry entry = cache.get(itemId);
        if (entry == null || entry.isExpired(ttlMs)) {
            return null;
        }
        return entry.data;
    }

    /**
     * Gets the cache entry (including metadata) for an item.
     */
    public FillCurveEntry getEntry(int itemId) {
        return cache.get(itemId);
    }

    /**
     * Checks if item has valid (non-expired) cached data.
     */
    public boolean hasValidEntry(int itemId) {
        FillCurveEntry entry = cache.get(itemId);
        return entry != null && !entry.isExpired(ttlMs);
    }

    /**
     * Puts fill curve data into the cache.
     */
    public void put(int itemId, FillCurveData data) {
        cache.put(itemId, new FillCurveEntry(data, Instant.now()));
    }

    /**
     * Parses a single-item fill curve API response and caches it.
     */
    public FillCurveData parseAndCache(int itemId, String json) {
        FillCurveData data = parseFillCurveResponse(json);
        if (data != null) {
            put(itemId, data);
        }
        return data;
    }

    /**
     * Parses a batch fill curve API response and caches all items.
     * Returns the number of items successfully cached.
     */
    @SuppressWarnings("unchecked")
    public int parseBatchAndCache(String json) {
        int cachedCount = 0;
        int skippedCount = 0;

        try {
            Map<String, Object> envelope = gson.fromJson(json, Map.class);
            if (envelope == null) {
                log.warn("FillCurveCache: batch response is null");
                return 0;
            }

            Object itemsObj = envelope.get("items");
            if (itemsObj == null) {
                log.warn("FillCurveCache: batch response missing 'items' field");
                return 0;
            }
            if (!(itemsObj instanceof List)) {
                log.warn("FillCurveCache: 'items' field is not a list, got: {}",
                    itemsObj.getClass().getSimpleName());
                return 0;
            }

            List<?> items = (List<?>) itemsObj;
            if (items.isEmpty()) {
                log.debug("FillCurveCache: batch response contains empty items list");
                return 0;
            }

            Instant now = Instant.now();

            for (Object itemObj : items) {
                if (!(itemObj instanceof Map)) {
                    skippedCount++;
                    continue;
                }

                Map<String, Object> item = (Map<String, Object>) itemObj;
                Number itemIdNum = (Number) item.get("item_id");
                if (itemIdNum == null) {
                    log.debug("FillCurveCache: item missing 'item_id' field");
                    skippedCount++;
                    continue;
                }

                int itemId = itemIdNum.intValue();

                // Validate required fields exist before parsing
                if (!hasRequiredFields(item)) {
                    log.debug("FillCurveCache: item {} missing required curve data fields", itemId);
                    skippedCount++;
                    continue;
                }

                // Wrap the item data in the expected format
                Map<String, Object> wrappedData = new HashMap<>();
                wrappedData.put("data", item);
                String wrappedJson = gson.toJson(wrappedData);

                FillCurveData data = parseFillCurveResponse(wrappedJson);
                if (data != null) {
                    cache.put(itemId, new FillCurveEntry(data, now));
                    cachedCount++;
                } else {
                    log.debug("FillCurveCache: failed to parse curve data for item {}", itemId);
                    skippedCount++;
                }
            }

            if (skippedCount > 0) {
                log.debug("FillCurveCache: cached {} items, skipped {} invalid entries",
                    cachedCount, skippedCount);
            }

        } catch (Exception e) {
            log.warn("FillCurveCache: error parsing batch response: {}", e.getMessage());
        }

        return cachedCount;
    }

    /**
     * Validates that an item map has the required fields for fill curve data.
     */
    @SuppressWarnings("unchecked")
    private boolean hasRequiredFields(Map<String, Object> item) {
        // Check for curves list
        Object curvesObj = item.get("curves");
        if (!(curvesObj instanceof List)) {
            return false;
        }

        List<?> curves = (List<?>) curvesObj;
        if (curves.isEmpty()) {
            return false;
        }

        // First curve should have at least one of aggregate_by_hour or daily_grid
        Object firstCurve = curves.get(0);
        if (!(firstCurve instanceof Map)) {
            return false;
        }

        Map<String, Object> curve = (Map<String, Object>) firstCurve;
        boolean hasAggregate = curve.get("aggregate_by_hour") instanceof Map;
        boolean hasDailyGrid = curve.get("daily_grid") instanceof List;

        return hasAggregate || hasDailyGrid;
    }

    /**
     * Parses fill curve response JSON into FillCurveData.
     */
    @SuppressWarnings("unchecked")
    public FillCurveData parseFillCurveResponse(String json) {
        try {
            Map<String, Object> envelope = gson.fromJson(json, Map.class);
            Map<String, Object> data = (Map<String, Object>) envelope.get("data");
            if (data == null) return null;

            List<Map<String, Object>> curvesList = (List<Map<String, Object>>) data.get("curves");
            if (curvesList == null || curvesList.isEmpty()) return null;

            Map<String, Object> curve = curvesList.get(0);

            // Parse aggregate_by_hour
            Map<Integer, Map<String, Number>> aggregateByHour = new HashMap<>();
            Map<String, Object> aggRaw = (Map<String, Object>) curve.get("aggregate_by_hour");
            if (aggRaw != null) {
                for (Map.Entry<String, Object> entry : aggRaw.entrySet()) {
                    int hour = Integer.parseInt(entry.getKey());
                    Map<String, Object> stats = (Map<String, Object>) entry.getValue();
                    aggregateByHour.put(hour, toNumberMap(stats));
                }
            }

            // Parse daily_grid
            Map<Integer, Map<Integer, Map<String, Number>>> dailyGrid = new HashMap<>();
            List<Map<String, Object>> gridRaw = (List<Map<String, Object>>) curve.get("daily_grid");
            if (gridRaw != null) {
                for (Map<String, Object> dayEntry : gridRaw) {
                    Number dayNum = (Number) dayEntry.get("day_of_week");
                    if (dayNum == null) continue;
                    int day = dayNum.intValue();

                    Map<String, Object> hoursRaw = (Map<String, Object>) dayEntry.get("hours");
                    if (hoursRaw == null) continue;

                    Map<Integer, Map<String, Number>> hourMap = new HashMap<>();
                    for (Map.Entry<String, Object> hourEntry : hoursRaw.entrySet()) {
                        int hour = Integer.parseInt(hourEntry.getKey());
                        Map<String, Object> stats = (Map<String, Object>) hourEntry.getValue();
                        hourMap.put(hour, toNumberMap(stats));
                    }
                    dailyGrid.put(day, hourMap);
                }
            }

            return new FillCurveData(aggregateByHour, dailyGrid);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Number> toNumberMap(Map<String, Object> raw) {
        Map<String, Number> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getValue() instanceof Number) {
                result.put(entry.getKey(), (Number) entry.getValue());
            }
        }
        return result;
    }

    /**
     * Fill curve data containing hourly aggregates and daily grid.
     */
    public static class FillCurveData {
        public final Map<Integer, Map<String, Number>> aggregateByHour;
        public final Map<Integer, Map<Integer, Map<String, Number>>> dailyGrid;

        public FillCurveData(
                Map<Integer, Map<String, Number>> aggregateByHour,
                Map<Integer, Map<Integer, Map<String, Number>>> dailyGrid) {
            this.aggregateByHour = aggregateByHour;
            this.dailyGrid = dailyGrid;
        }
    }

    /**
     * Cache entry with data and fetch timestamp.
     */
    public static class FillCurveEntry {
        public final FillCurveData data;
        public final Instant fetchedAt;

        public FillCurveEntry(FillCurveData data, Instant fetchedAt) {
            this.data = data;
            this.fetchedAt = fetchedAt;
        }

        public boolean isExpired(long ttlMs) {
            return Instant.now().toEpochMilli() - fetchedAt.toEpochMilli() > ttlMs;
        }
    }
}
