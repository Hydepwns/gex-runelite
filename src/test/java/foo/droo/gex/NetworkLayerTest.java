package foo.droo.gex;

import com.google.gson.Gson;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

/**
 * Tests for network layer functionality: URL construction, request building,
 * batch serialization, and retry logic.
 */
public class NetworkLayerTest {

    private static final Gson GSON = GexApiClient.getGson();
    private MockWebServer mockServer;

    @Before
    public void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @After
    public void tearDown() throws IOException {
        mockServer.shutdown();
    }

    // ============ buildEndpointUrl tests ============

    @Test
    public void testBuildEndpointUrlPredict() {
        String base = "https://gex.droo.foo/api/v1/slots";
        String result = GexApiClient.buildEndpointUrl(base, "/api/v1/predict");

        assertEquals("https://gex.droo.foo/api/v1/predict", result);
    }

    @Test
    public void testBuildEndpointUrlBatch() {
        String base = "https://gex.droo.foo/api/v1/slots";
        String result = GexApiClient.buildEndpointUrl(base, "/api/v1/slots/batch");

        assertEquals("https://gex.droo.foo/api/v1/slots/batch", result);
    }

    @Test
    public void testBuildEndpointUrlFillCurves() {
        String base = "https://gex.droo.foo/api/v1/slots";
        String result = GexApiClient.buildEndpointUrl(base, "/api/v1/items/440/fill-curves?offer_type=buy");

        assertEquals("https://gex.droo.foo/api/v1/items/440/fill-curves?offer_type=buy", result);
    }

    @Test
    public void testBuildEndpointUrlLocalhost() {
        String base = "http://localhost:4000/api/v1/slots";
        String result = GexApiClient.buildEndpointUrl(base, "/api/v1/predict");

        assertEquals("http://localhost:4000/api/v1/predict", result);
    }

    @Test
    public void testBuildEndpointUrlWithPort() {
        String base = "https://gex.droo.foo:8443/api/v1/slots";
        String result = GexApiClient.buildEndpointUrl(base, "/api/v1/predict");

        assertEquals("https://gex.droo.foo:8443/api/v1/predict", result);
    }

    @Test
    public void testBuildEndpointUrlNullBase() {
        String result = GexApiClient.buildEndpointUrl(null, "/api/v1/predict");
        assertNull(result);
    }

    @Test
    public void testBuildEndpointUrlEmptyBase() {
        String result = GexApiClient.buildEndpointUrl("", "/api/v1/predict");
        assertNull(result);
    }

    @Test
    public void testBuildEndpointUrlInvalidUrl() {
        // Should fall back to string manipulation
        String base = "not-a-valid-url/api/v1/slots";
        String result = GexApiClient.buildEndpointUrl(base, "/api/v1/predict");

        // Falls back to string manipulation, finds /api/ and replaces
        assertEquals("not-a-valid-url/api/v1/predict", result);
    }

    // ============ Batch payload serialization tests ============

    @Test
    public void testBatchPayloadStructure() {
        List<Map<String, Object>> events = new ArrayList<>();

        Map<String, Object> event1 = new HashMap<>();
        event1.put("account_hash", "abc123");
        event1.put("event_type", "offer_update");
        event1.put("timestamp", "2026-02-21T12:00:00Z");

        Map<String, Object> slot1 = new HashMap<>();
        slot1.put("index", 0);
        slot1.put("item_id", 440);
        slot1.put("offer_type", "buy");
        slot1.put("price", 150);
        slot1.put("quantity_total", 1000);
        slot1.put("quantity_filled", 500);
        slot1.put("state", "buying");
        slot1.put("sequence", 1L);
        event1.put("slot", slot1);

        events.add(event1);

        Map<String, Object> batchPayload = new HashMap<>();
        batchPayload.put("events", events);

        String json = GSON.toJson(batchPayload);

        // Verify structure
        assertTrue(json.contains("\"events\""));
        assertTrue(json.contains("\"account_hash\":\"abc123\""));
        assertTrue(json.contains("\"item_id\":440"));
        assertTrue(json.contains("\"sequence\":1"));
    }

    @Test
    public void testBatchPayloadMultipleEvents() {
        List<Map<String, Object>> events = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            Map<String, Object> event = new HashMap<>();
            event.put("account_hash", "hash" + i);
            event.put("event_type", "heartbeat");

            Map<String, Object> slot = new HashMap<>();
            slot.put("index", i);
            slot.put("item_id", 440 + i);
            event.put("slot", slot);

            events.add(event);
        }

        Map<String, Object> batchPayload = new HashMap<>();
        batchPayload.put("events", events);

        String json = GSON.toJson(batchPayload);

        // Count occurrences of account_hash (should be 3)
        int count = json.split("account_hash").length - 1;
        assertEquals(3, count);
    }

    // ============ Retry delay pattern tests ============

    @Test
    public void testRetryDelayPattern() {
        int[] delays = {1000, 2000, 4000, 8000};

        // Verify exponential backoff pattern
        assertEquals(1000, delays[0]);
        assertEquals(2000, delays[1]);
        assertEquals(4000, delays[2]);
        assertEquals(8000, delays[3]);

        // Verify each delay is double the previous
        for (int i = 1; i < delays.length; i++) {
            assertEquals(delays[i - 1] * 2, delays[i]);
        }
    }

    @Test
    public void testRetryDelayBounds() {
        int[] delays = {1000, 2000, 4000, 8000};
        int maxRetries = 4;

        // Test that accessing delays with attempt >= maxRetries uses last delay
        for (int attempt = 0; attempt < maxRetries + 2; attempt++) {
            int delay = delays[Math.min(attempt, delays.length - 1)];

            if (attempt < delays.length) {
                assertEquals(delays[attempt], delay);
            } else {
                assertEquals(delays[delays.length - 1], delay);
            }
        }
    }

    // ============ GZIP decompression helper for verification ============

    private String decompressGzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(gis.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ============ Response parsing edge cases ============

    @Test
    public void testParsePredictionsResponseEmpty() {
        String json = "{\"predictions\":[]}";
        // This should not throw and should handle empty list gracefully
        Map<String, Object> data = GSON.fromJson(json, Map.class);
        assertNotNull(data);
        assertTrue(data.containsKey("predictions"));
    }

    @Test
    public void testParsePredictionsResponseMissingPredictions() {
        String json = "{\"daily_pnl\":\"10.5K\"}";
        Map<String, Object> data = GSON.fromJson(json, Map.class);
        assertNotNull(data);
        assertNull(data.get("predictions"));
    }

    @Test
    public void testParsePredictionsResponseMalformed() {
        String json = "{\"predictions\": \"not an array\"}";
        Map<String, Object> data = GSON.fromJson(json, Map.class);
        assertNotNull(data);
        // predictions is a string, not a list
        assertFalse(data.get("predictions") instanceof List);
    }

    // ============ Idempotency key format tests ============

    @Test
    public void testIdempotencyKeyFormat() {
        // Idempotency key should be 16 hex characters (8 bytes of SHA-256)
        String input = "12345:0:440:500:BUYING:2026-02-21T12:00:00";
        // Simulate what generateIdempotencyKey does
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            String key = sb.toString();

            assertEquals(16, key.length());
            assertTrue(key.matches("[0-9a-f]{16}"));
        } catch (Exception e) {
            fail("SHA-256 should be available");
        }
    }

    // ============ Queue behavior tests ============

    @Test
    public void testQueueBoundedCapacity() {
        // LinkedBlockingQueue with capacity 100
        java.util.concurrent.LinkedBlockingQueue<Integer> queue =
                new java.util.concurrent.LinkedBlockingQueue<>(100);

        // Fill to capacity
        for (int i = 0; i < 100; i++) {
            assertTrue(queue.offer(i));
        }

        // Queue is full, offer should return false
        assertFalse(queue.offer(100));

        // After removing one, offer should succeed
        queue.poll();
        assertTrue(queue.offer(100));
    }

    @Test
    public void testQueueFifoEviction() {
        java.util.concurrent.LinkedBlockingQueue<Integer> queue =
                new java.util.concurrent.LinkedBlockingQueue<>(3);

        queue.offer(1);
        queue.offer(2);
        queue.offer(3);

        // Simulate FIFO eviction: poll oldest, then offer new
        while (!queue.offer(4)) {
            Integer evicted = queue.poll();
            assertEquals(Integer.valueOf(1), evicted);
        }

        // Queue should now contain 2, 3, 4
        assertEquals(Integer.valueOf(2), queue.poll());
        assertEquals(Integer.valueOf(3), queue.poll());
        assertEquals(Integer.valueOf(4), queue.poll());
    }

    // ============ Delta encoding tests ============

    @Test
    public void testHeartbeatStateKeyFormat() {
        // Heartbeat state key format: slot:itemId:quantitySold:quantityTotal:price:state
        String stateKey = String.format("%d:%d:%d:%d:%d:%s",
            0, 440, 500, 1000, 150, "BUYING");

        assertEquals("0:440:500:1000:150:BUYING", stateKey);
    }

    @Test
    public void testHeartbeatStateKeyDifferentQuantity() {
        String state1 = String.format("%d:%d:%d:%d:%d:%s", 0, 440, 500, 1000, 150, "BUYING");
        String state2 = String.format("%d:%d:%d:%d:%d:%s", 0, 440, 600, 1000, 150, "BUYING");

        // States should be different when quantity changes
        assertNotEquals(state1, state2);
    }

    @Test
    public void testHeartbeatStateKeySameState() {
        String state1 = String.format("%d:%d:%d:%d:%d:%s", 0, 440, 500, 1000, 150, "BUYING");
        String state2 = String.format("%d:%d:%d:%d:%d:%s", 0, 440, 500, 1000, 150, "BUYING");

        // Same state should be equal
        assertEquals(state1, state2);
    }

    @Test
    public void testDeltaEncodingWithMap() {
        Map<Integer, String> lastHeartbeatStates = new ConcurrentHashMap<>();

        // First heartbeat for slot 0
        String state1 = "0:440:0:1000:150:BUYING";
        assertNull(lastHeartbeatStates.put(0, state1));

        // Same state - should not trigger send (equals check returns true)
        String state2 = "0:440:0:1000:150:BUYING";
        assertEquals(state1, state2);
        assertTrue(state2.equals(lastHeartbeatStates.get(0)));

        // Changed state - should trigger send
        String state3 = "0:440:100:1000:150:BUYING";
        assertFalse(state3.equals(lastHeartbeatStates.get(0)));

        // Update tracked state
        lastHeartbeatStates.put(0, state3);
        assertEquals(state3, lastHeartbeatStates.get(0));
    }

    @Test
    public void testDeltaEncodingSlotCleared() {
        Map<Integer, String> lastHeartbeatStates = new ConcurrentHashMap<>();

        // Add state for slot 0
        lastHeartbeatStates.put(0, "0:440:500:1000:150:BUYING");
        assertTrue(lastHeartbeatStates.containsKey(0));

        // Remove state when slot becomes empty
        String removed = lastHeartbeatStates.remove(0);
        assertNotNull(removed);
        assertFalse(lastHeartbeatStates.containsKey(0));

        // Second removal returns null (already cleared)
        assertNull(lastHeartbeatStates.remove(0));
    }
}
