package foo.droo.gex;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * HTTP client for GEX API communication.
 * Handles request building, gzip compression, retry logic, and endpoint construction.
 */
@Slf4j
public class GexApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType JSON_GZIP = MediaType.parse("application/json");

    private static final int MAX_RETRY_ATTEMPTS = 4;
    private static final int[] RETRY_DELAYS_MS = {1000, 2000, 4000, 8000};

    // Explicit timeouts to prevent hanging on unresponsive server
    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int READ_TIMEOUT_SECONDS = 10;
    private static final int WRITE_TIMEOUT_SECONDS = 10;

    // Rate limiting
    private static final long DEFAULT_RATE_LIMIT_COOLDOWN_MS = 60_000; // 1 minute default
    private static final long MAX_RATE_LIMIT_COOLDOWN_MS = 300_000;    // 5 minutes max
    private volatile long rateLimitedUntil = 0;
    private volatile long currentCooldownMs = DEFAULT_RATE_LIMIT_COOLDOWN_MS;

    private final OkHttpClient httpClient;
    private final ScheduledExecutorService executor;
    private final GexConfig config;
    private final Gson gson;

    public interface ConnectionListener {
        void onConnectionStatusChanged(boolean connected);
    }

    private ConnectionListener connectionListener;

    public GexApiClient(OkHttpClient httpClient, ScheduledExecutorService executor, GexConfig config, Gson gson) {
        // Configure client with explicit timeouts
        this.httpClient = httpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, SECONDS)
            .build();
        this.executor = executor;
        this.config = config;
        this.gson = gson;
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    /**
     * Check if we're currently rate limited.
     */
    public boolean isRateLimited() {
        return System.currentTimeMillis() < rateLimitedUntil;
    }

    /**
     * Get remaining rate limit cooldown in milliseconds.
     */
    public long getRateLimitRemainingMs() {
        long remaining = rateLimitedUntil - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    /**
     * Handle a 429 rate limit response.
     * Parses Retry-After header if present, otherwise uses exponential backoff.
     */
    private void handleRateLimit(Response response) {
        String retryAfter = response.header("Retry-After");
        long cooldownMs;

        if (retryAfter != null) {
            try {
                // Retry-After can be seconds
                cooldownMs = Long.parseLong(retryAfter) * 1000;
            } catch (NumberFormatException e) {
                cooldownMs = currentCooldownMs;
            }
        } else {
            // Exponential backoff
            cooldownMs = currentCooldownMs;
            currentCooldownMs = Math.min(currentCooldownMs * 2, MAX_RATE_LIMIT_COOLDOWN_MS);
        }

        rateLimitedUntil = System.currentTimeMillis() + cooldownMs;
        log.warn("Rate limited by server, backing off for {}s", cooldownMs / 1000);
    }

    /**
     * Reset rate limit cooldown after successful request.
     */
    private void resetRateLimitCooldown() {
        currentCooldownMs = DEFAULT_RATE_LIMIT_COOLDOWN_MS;
    }

    /**
     * Sends a batch of events to the API with gzip compression and retry logic.
     */
    public void sendBatchAsync(List<Map<String, Object>> batch, Callback callback) {
        if (isRateLimited()) {
            log.debug("Skipping batch send - rate limited for {}s more", getRateLimitRemainingMs() / 1000);
            callback.onFailure(null, new IOException("Rate limited"));
            return;
        }
        sendBatchWithRetry(batch, 0, callback);
    }

    private void sendBatchWithRetry(List<Map<String, Object>> batch, int attempt, Callback callback) {
        String batchEndpoint = buildEndpointUrl(config.apiEndpoint(), "/api/v1/slots/batch");
        if (batchEndpoint == null) {
            callback.onFailure(null, new IOException("Invalid endpoint configuration"));
            return;
        }

        Map<String, Object> batchPayload = new HashMap<>();
        batchPayload.put("events", batch);

        String json = gson.toJson(batchPayload);
        byte[] compressed = compressGzip(json);
        if (compressed == null) {
            compressed = json.getBytes(StandardCharsets.UTF_8);
        }

        Request request = buildRequest(batchEndpoint)
            .post(RequestBody.create(JSON_GZIP, compressed))
            .header("Content-Encoding", "gzip")
            .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("Failed to send GEX batch: {}", e.getMessage());
                notifyConnectionStatus(false);
                retryBatch(batch, attempt, callback);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        int code = response.code();
                        if (code == 429) {
                            // Rate limited - back off
                            handleRateLimit(response);
                            notifyConnectionStatus(true); // Server is up, just rate limited
                            callback.onFailure(call, new IOException("Rate limited"));
                        } else if (code >= 500 && code < 600) {
                            notifyConnectionStatus(false);
                            retryBatch(batch, attempt, callback);
                        } else {
                            log.warn("GEX batch rejected with {}: {}", code, response.message());
                            notifyConnectionStatus(true);
                            try {
                                callback.onResponse(call, response);
                            } catch (IOException e) {
                                log.debug("Callback error: {}", e.getMessage());
                            }
                        }
                    } else {
                        notifyConnectionStatus(true);
                        resetRateLimitCooldown();
                        try {
                            callback.onResponse(call, response);
                        } catch (IOException e) {
                            log.debug("Callback error: {}", e.getMessage());
                        }
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    private void retryBatch(List<Map<String, Object>> batch, int attempt, Callback callback) {
        if (attempt >= MAX_RETRY_ATTEMPTS) {
            log.error("GEX batch failed after {} attempts, dropping {} events", MAX_RETRY_ATTEMPTS, batch.size());
            callback.onFailure(null, new IOException("Max retries exceeded"));
            return;
        }

        int delay = RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)];
        executor.schedule(() -> sendBatchWithRetry(batch, attempt + 1, callback), delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Fetches predictions for the given item IDs.
     */
    public void fetchPredictionsAsync(List<Integer> itemIds, String accountHash, Callback callback) {
        if (isRateLimited()) {
            log.debug("Skipping predictions fetch - rate limited");
            callback.onFailure(null, new IOException("Rate limited"));
            return;
        }

        String predictEndpoint = buildEndpointUrl(config.apiEndpoint(), "/api/v1/predict");
        if (predictEndpoint == null) {
            callback.onFailure(null, new IOException("Invalid endpoint configuration"));
            return;
        }

        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("item_ids", itemIds);
        requestPayload.put("account_hash", accountHash);

        String json = gson.toJson(requestPayload);

        Request request = buildRequest(predictEndpoint)
            .post(RequestBody.create(JSON, json))
            .build();

        httpClient.newCall(request).enqueue(wrapCallbackWithRateLimitHandling(callback));
    }

    /**
     * Fetches fill curves for a single item.
     */
    public void fetchFillCurvesAsync(int itemId, String offerType, Callback callback) {
        String curveEndpoint = buildEndpointUrl(
            config.apiEndpoint(),
            "/api/v1/items/" + itemId + "/fill-curves?offer_type=" + offerType
        );
        if (curveEndpoint == null) {
            callback.onFailure(null, new IOException("Invalid endpoint configuration"));
            return;
        }

        Request request = buildRequest(curveEndpoint)
            .get()
            .build();

        httpClient.newCall(request).enqueue(callback);
    }

    /**
     * Fetches the optimal quick price for an item.
     */
    public void fetchQuickPriceAsync(int itemId, String offerType, Callback callback) {
        String endpoint = buildEndpointUrl(
            config.apiEndpoint(),
            "/api/v1/quick-price?item_id=" + itemId + "&offer_type=" + offerType
        );
        if (endpoint == null) {
            callback.onFailure(null, new IOException("Invalid endpoint configuration"));
            return;
        }

        Request request = buildRequest(endpoint)
            .get()
            .build();

        httpClient.newCall(request).enqueue(callback);
    }

    /**
     * Fetches slot data with queue estimates for an account.
     */
    public void fetchSlotsWithEstimatesAsync(String accountHash, Callback callback) {
        String endpoint = buildEndpointUrl(config.apiEndpoint(), "/api/v1/slots/" + accountHash);
        if (endpoint == null) {
            callback.onFailure(null, new IOException("Invalid endpoint configuration"));
            return;
        }

        Request request = buildRequest(endpoint)
            .get()
            .build();

        httpClient.newCall(request).enqueue(callback);
    }

    /**
     * Fetches ML data for an item (regime, anomaly, fill probability).
     */
    public void fetchItemMlDataAsync(int itemId, Callback callback) {
        String endpoint = buildEndpointUrl(config.apiEndpoint(), "/api/v1/items/" + itemId + "/ml");
        if (endpoint == null) {
            callback.onFailure(null, new IOException("Invalid endpoint configuration"));
            return;
        }

        Request request = buildRequest(endpoint)
            .get()
            .build();

        httpClient.newCall(request).enqueue(callback);
    }

    /**
     * Fetches market-wide regime and risk data.
     */
    public void fetchMarketRegimeAsync(Callback callback) {
        String endpoint = buildEndpointUrl(config.apiEndpoint(), "/api/v1/market/regime");
        if (endpoint == null) {
            callback.onFailure(null, new IOException("Invalid endpoint configuration"));
            return;
        }

        Request request = buildRequest(endpoint)
            .get()
            .build();

        httpClient.newCall(request).enqueue(callback);
    }

    /**
     * Fetches fill curves for multiple items in a batch.
     */
    public void fetchFillCurvesBatchAsync(List<Integer> itemIds, Callback callback) {
        String batchEndpoint = buildEndpointUrl(config.apiEndpoint(), "/api/v1/fill-curves/batch");
        if (batchEndpoint == null) {
            callback.onFailure(null, new IOException("Invalid endpoint configuration"));
            return;
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("item_ids", itemIds);
        String jsonBody = gson.toJson(requestBody);

        Request request = buildRequest(batchEndpoint)
            .post(RequestBody.create(JSON, jsonBody))
            .build();

        httpClient.newCall(request).enqueue(callback);
        log.debug("Batch fetching fill curves for {} items", itemIds.size());
    }

    /**
     * Builds a request with common headers (Authorization).
     */
    private Request.Builder buildRequest(String url) {
        Request.Builder builder = new Request.Builder().url(url);

        String apiKey = config.apiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return builder;
    }

    /**
     * Compresses data using gzip.
     */
    public byte[] compressGzip(String data) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
                gzip.write(data.getBytes(StandardCharsets.UTF_8));
            }
            return baos.toByteArray();
        } catch (IOException e) {
            log.warn("Failed to compress payload: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds a URL by replacing the path component of the base endpoint.
     */
    public static String buildEndpointUrl(String baseEndpoint, String newPath) {
        if (baseEndpoint == null || baseEndpoint.isEmpty()) {
            return null;
        }
        try {
            URL base = new URL(baseEndpoint);
            URL newUrl = new URL(base.getProtocol(), base.getHost(), base.getPort(), newPath);
            return newUrl.toString();
        } catch (MalformedURLException e) {
            // Fallback: try simple string manipulation
            int pathStart = baseEndpoint.indexOf("/api/");
            if (pathStart > 0) {
                return baseEndpoint.substring(0, pathStart) + newPath;
            }
            return null;
        }
    }

    /**
     * Returns the Gson instance for JSON parsing.
     */
    public Gson getGson() {
        return gson;
    }

    private void notifyConnectionStatus(boolean connected) {
        if (connectionListener != null) {
            connectionListener.onConnectionStatusChanged(connected);
        }
    }

    /**
     * Wraps a callback to handle rate limit responses.
     */
    private Callback wrapCallbackWithRateLimitHandling(Callback original) {
        return new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                original.onFailure(call, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.code() == 429) {
                    handleRateLimit(response);
                    response.close();
                    original.onFailure(call, new IOException("Rate limited"));
                } else if (response.isSuccessful()) {
                    resetRateLimitCooldown();
                    original.onResponse(call, response);
                } else {
                    original.onResponse(call, response);
                }
            }
        };
    }

    /**
     * Validates the API endpoint URL format.
     * @return null if valid, error message if invalid
     */
    public static String validateEndpointUrl(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return "Endpoint URL is empty";
        }

        try {
            URL url = new URL(endpoint);
            String protocol = url.getProtocol();
            if (!protocol.equals("http") && !protocol.equals("https")) {
                return "Endpoint must use http or https protocol";
            }
            if (url.getHost() == null || url.getHost().isEmpty()) {
                return "Endpoint has no host";
            }
            return null; // Valid
        } catch (MalformedURLException e) {
            return "Invalid URL format: " + e.getMessage();
        }
    }

    /**
     * Performs a health check against the API endpoint.
     * Calls the callback with success=true if healthy, false otherwise.
     */
    public void healthCheckAsync(HealthCheckCallback callback) {
        String healthEndpoint = buildEndpointUrl(config.apiEndpoint(), "/api/v1/health");
        if (healthEndpoint == null) {
            callback.onResult(false, "Invalid endpoint configuration");
            return;
        }

        Request request = buildRequest(healthEndpoint)
            .get()
            .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onResult(false, "Connection failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (response.isSuccessful()) {
                        callback.onResult(true, "Connected to " + config.apiEndpoint());
                    } else {
                        callback.onResult(false, "Server returned " + response.code());
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    public interface HealthCheckCallback {
        void onResult(boolean healthy, String message);
    }

    /**
     * Fetches prediction accuracy metrics.
     */
    public void fetchAccuracyAsync(int days, Callback callback) {
        if (isRateLimited()) {
            callback.onFailure(null, new IOException("Rate limited"));
            return;
        }

        String endpoint = buildEndpointUrl(config.apiEndpoint(),
            "/api/v1/predictions/accuracy?days=" + days);
        if (endpoint == null) {
            callback.onFailure(null, new IOException("Invalid endpoint configuration"));
            return;
        }

        Request request = buildRequest(endpoint)
            .get()
            .build();

        httpClient.newCall(request).enqueue(wrapCallbackWithRateLimitHandling(callback));
    }

    /**
     * Submits a prediction outcome for accuracy tracking.
     */
    public void submitOutcomeAsync(int itemId, String accountHash, boolean filled,
                                    double predictedEta, double actualMinutes, Callback callback) {
        if (isRateLimited()) {
            callback.onFailure(null, new IOException("Rate limited"));
            return;
        }

        String endpoint = buildEndpointUrl(config.apiEndpoint(), "/api/v1/predictions/outcome");
        if (endpoint == null) {
            callback.onFailure(null, new IOException("Invalid endpoint configuration"));
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("item_id", itemId);
        payload.put("account_hash", accountHash);
        payload.put("prediction_type", "fill_probability");
        payload.put("filled", filled);
        if (predictedEta > 0 && actualMinutes > 0) {
            payload.put("predicted_value", predictedEta);
            payload.put("actual_minutes", actualMinutes);
        }

        String json = gson.toJson(payload);

        Request request = buildRequest(endpoint)
            .post(RequestBody.create(JSON, json))
            .build();

        httpClient.newCall(request).enqueue(callback);
    }
}
