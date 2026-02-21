package foo.droo.gex;

import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GexPluginTest {

    @Test
    public void testMapStateBuying() {
        assertEquals("buying", GexPlugin.mapState(GrandExchangeOfferState.BUYING));
    }

    @Test
    public void testMapStateBought() {
        assertEquals("bought", GexPlugin.mapState(GrandExchangeOfferState.BOUGHT));
    }

    @Test
    public void testMapStateSelling() {
        assertEquals("selling", GexPlugin.mapState(GrandExchangeOfferState.SELLING));
    }

    @Test
    public void testMapStateSold() {
        assertEquals("sold", GexPlugin.mapState(GrandExchangeOfferState.SOLD));
    }

    @Test
    public void testMapStateCancelledBuy() {
        assertEquals("cancelled_buy", GexPlugin.mapState(GrandExchangeOfferState.CANCELLED_BUY));
    }

    @Test
    public void testMapStateCancelledSell() {
        assertEquals("cancelled_sell", GexPlugin.mapState(GrandExchangeOfferState.CANCELLED_SELL));
    }

    @Test
    public void testMapStateEmpty() {
        assertEquals("empty", GexPlugin.mapState(GrandExchangeOfferState.EMPTY));
    }

    // shouldNotifyStall tests

    @Test
    public void testShouldNotifyStallAboveThreshold() {
        assertTrue(GexPlugin.shouldNotifyStall(150.0, 120));
    }

    @Test
    public void testShouldNotifyStallBelowThreshold() {
        assertFalse(GexPlugin.shouldNotifyStall(60.0, 120));
    }

    @Test
    public void testShouldNotifyStallExactThreshold() {
        assertTrue(GexPlugin.shouldNotifyStall(120.0, 120));
    }

    @Test
    public void testShouldNotifyStallZeroThreshold() {
        assertTrue(GexPlugin.shouldNotifyStall(0.0, 0));
    }

    // shouldNotifyHighValue tests

    @Test
    public void testShouldNotifyHighValueMeetsBoth() {
        assertTrue(GexPlugin.shouldNotifyHighValue(15000, 85.0, 10000, 70));
    }

    @Test
    public void testShouldNotifyHighValueMarginTooLow() {
        assertFalse(GexPlugin.shouldNotifyHighValue(5000, 85.0, 10000, 70));
    }

    @Test
    public void testShouldNotifyHighValueConfidenceTooLow() {
        assertFalse(GexPlugin.shouldNotifyHighValue(15000, 50.0, 10000, 70));
    }

    @Test
    public void testShouldNotifyHighValueExactThresholds() {
        assertTrue(GexPlugin.shouldNotifyHighValue(10000, 70.0, 10000, 70));
    }

    // parseFillCurveResponse tests

    @Test
    public void testParseFillCurveResponseValid() {
        String json = "{\"data\":{\"item_id\":440,\"item_name\":\"Iron ore\",\"curves\":[{"
                + "\"offer_type\":\"buy\","
                + "\"aggregate_by_hour\":{\"10\":{\"avg\":120.5,\"median\":90.0,\"p90\":300.0,\"samples\":15}},"
                + "\"daily_grid\":[{\"day_of_week\":6,\"hours\":{\"10\":{\"avg\":80.0,\"median\":60.0,\"p90\":200.0,\"samples\":5}}}]"
                + "}]}}";

        FillCurveCache.FillCurveData data = FillCurveCache.parseFillCurveResponse(json);

        assertNotNull(data);
        assertNotNull(data.aggregateByHour);
        assertEquals(1, data.aggregateByHour.size());
        assertEquals(90.0, data.aggregateByHour.get(10).get("median").doubleValue(), 0.01);

        assertNotNull(data.dailyGrid);
        assertEquals(1, data.dailyGrid.size());
        assertNotNull(data.dailyGrid.get(6));
        assertEquals(60.0, data.dailyGrid.get(6).get(10).get("median").doubleValue(), 0.01);
    }

    @Test
    public void testParseFillCurveResponseEmptyCurves() {
        String json = "{\"data\":{\"item_id\":440,\"item_name\":\"Iron ore\",\"curves\":[]}}";

        FillCurveCache.FillCurveData data = FillCurveCache.parseFillCurveResponse(json);
        assertNull(data);
    }

    @Test
    public void testParseFillCurveResponseInvalid() {
        FillCurveCache.FillCurveData data = FillCurveCache.parseFillCurveResponse("not json");
        assertNull(data);
    }

    // resolveDayAwareEta tests

    @Test
    public void testDayAwareEtaPrefersDaySpecific() {
        // Build a cache with day-specific data for current day/hour
        FillCurveCache cache = new FillCurveCache();

        Map<Integer, Map<String, Number>> aggByHour = new HashMap<>();
        Map<String, Number> aggStats = new HashMap<>();
        aggStats.put("median", 300.0); // 5 min aggregate
        // Populate for all hours so test works regardless of current time
        for (int h = 0; h < 24; h++) {
            aggByHour.put(h, aggStats);
        }

        Map<Integer, Map<Integer, Map<String, Number>>> dailyGrid = new HashMap<>();
        Map<String, Number> dayStats = new HashMap<>();
        dayStats.put("median", 120.0); // 2 min day-specific

        // Put data for all days and hours so we always hit
        for (int d = 1; d <= 7; d++) {
            Map<Integer, Map<String, Number>> dayHours = new HashMap<>();
            for (int h = 0; h < 24; h++) {
                dayHours.put(h, dayStats);
            }
            dailyGrid.put(d, dayHours);
        }

        FillCurveCache.FillCurveData curveData = new FillCurveCache.FillCurveData(aggByHour, dailyGrid);
        cache.put(440, curveData);

        // resolveDayAwareEta uses LocalDateTime.now() internally
        // The test validates the function doesn't crash and returns a reasonable value
        double[] result = GexPlugin.resolveDayAwareEta(440, 10.0, cache, true);

        // Should use day-specific 120s = 2.0 min since we have data for all hours/days
        assertEquals(2.0, result[0], 0.01);
        assertTrue(result[1] >= 1 && result[1] <= 7); // dayOfWeek should be 1-7
    }

    @Test
    public void testDayAwareEtaDisabledReturnsPrediction() {
        FillCurveCache cache = new FillCurveCache();

        double[] result = GexPlugin.resolveDayAwareEta(440, 5.0, cache, false);

        assertEquals(5.0, result[0], 0.01);
        assertEquals(0.0, result[1], 0.01);
    }

    @Test
    public void testDayAwareEtaFallsBackWithNoCache() {
        FillCurveCache cache = new FillCurveCache();

        double[] result = GexPlugin.resolveDayAwareEta(440, 7.5, cache, true);

        assertEquals(7.5, result[0], 0.01);
        assertEquals(0.0, result[1], 0.01);
    }

    @Test
    public void testDayAwareEtaExpiredCacheFallsBack() {
        // Use short TTL cache to test expiration
        FillCurveCache cache = new FillCurveCache(1); // 1ms TTL

        Map<Integer, Map<String, Number>> aggByHour = new HashMap<>();
        Map<Integer, Map<Integer, Map<String, Number>>> dailyGrid = new HashMap<>();
        FillCurveCache.FillCurveData curveData = new FillCurveCache.FillCurveData(aggByHour, dailyGrid);
        cache.put(440, curveData);

        // Wait for cache to expire
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}

        double[] result = GexPlugin.resolveDayAwareEta(440, 3.0, cache, true);

        assertEquals(3.0, result[0], 0.01);
        assertEquals(0.0, result[1], 0.01);
    }

    // dayAbbreviation tests

    @Test
    public void testDayAbbreviations() {
        assertEquals("Mon", GexPlugin.dayAbbreviation(1));
        assertEquals("Tue", GexPlugin.dayAbbreviation(2));
        assertEquals("Wed", GexPlugin.dayAbbreviation(3));
        assertEquals("Thu", GexPlugin.dayAbbreviation(4));
        assertEquals("Fri", GexPlugin.dayAbbreviation(5));
        assertEquals("Sat", GexPlugin.dayAbbreviation(6));
        assertEquals("Sun", GexPlugin.dayAbbreviation(7));
        assertEquals("", GexPlugin.dayAbbreviation(0));
    }
}
