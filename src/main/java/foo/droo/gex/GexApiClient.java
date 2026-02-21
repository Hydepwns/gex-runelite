package foo.droo.gex;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

/**
 * HTTP client for GEX API communication.
 * Handles request building, gzip compression, retry logic, and endpoint construction.
 */
@Slf4j
public class GexApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType JSON_GZIP = MediaType.parse("application/json");
    private static final Gson GSON = new GsonBuilder().create();

    private static final int MAX_RETRY_ATTEMPTS = 4;
    private static final int[] RETRY_DELAYS_MS = {1000, 2000, 4000, 8000};

    private final OkHttpClient httpClient;
    private final ScheduledExecutorService executor;
    private final GexConfig config;

    public interface ConnectionListener {
        void onConnectionStatusChanged(boolean connected);
    }

    private ConnectionListener connectionListener;

    public GexApiClient(OkHttpClient httpClient, ScheduledExecutorService executor, GexConfig config) {
        this.httpClient = httpClient;
        this.executor = executor;
        this.config = config;
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    /**
     * Sends a batch of events to the API with gzip compression and retry logic.
     */
    public void sendBatchAsync(List<Map<String, Object>> batch, Callback callback) {
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

        String json = GSON.toJson(batchPayload);
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
                        if (code >= 500 && code < 600) {
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
        String predictEndpoint = buildEndpointUrl(config.apiEndpoint(), "/api/v1/predict");
        if (predictEndpoint == null) {
            callback.onFailure(null, new IOException("Invalid endpoint configuration"));
            return;
        }

        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("item_ids", itemIds);
        requestPayload.put("account_hash", accountHash);

        String json = GSON.toJson(requestPayload);

        Request request = buildRequest(predictEndpoint)
            .post(RequestBody.create(JSON, json))
            .build();

        httpClient.newCall(request).enqueue(callback);
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
        String jsonBody = GSON.toJson(requestBody);

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
    public static Gson getGson() {
        return GSON;
    }

    private void notifyConnectionStatus(boolean connected) {
        if (connectionListener != null) {
            connectionListener.onConnectionStatusChanged(connected);
        }
    }
}
