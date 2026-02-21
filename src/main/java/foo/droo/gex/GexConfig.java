package foo.droo.gex;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("gex")
public interface GexConfig extends Config {

    @ConfigItem(
        keyName = "apiEndpoint",
        name = "API Endpoint",
        description = "The GEX backend API endpoint for slot telemetry",
        position = 1
    )
    default String apiEndpoint() {
        return "https://gex.droo.foo/api/v1/slots";
    }

    @ConfigItem(
        keyName = "apiKey",
        name = "API Key",
        description = "Your GEX API key for authentication",
        secret = true,
        position = 2
    )
    default String apiKey() {
        return "";
    }

    @ConfigItem(
        keyName = "enabled",
        name = "Enabled",
        description = "Enable or disable telemetry sending",
        position = 3
    )
    default boolean enabled() {
        return true;
    }

    @ConfigItem(
        keyName = "heartbeatInterval",
        name = "Heartbeat Interval (seconds)",
        description = "How often to send full slot snapshots",
        position = 4
    )
    default int heartbeatInterval() {
        return 60;
    }

    @ConfigItem(
        keyName = "showOverlay",
        name = "Show GE Overlay",
        description = "Display profit estimates and fill ETA on GE slots",
        position = 5
    )
    default boolean showOverlay() {
        return true;
    }

    @ConfigItem(
        keyName = "showPanel",
        name = "Show Side Panel",
        description = "Show the GEX panel with trade history and stats",
        position = 6
    )
    default boolean showPanel() {
        return true;
    }

    @ConfigItem(
        keyName = "showDayAwareEta",
        name = "Day-Aware ETA",
        description = "Use day-of-week-specific fill curves for more accurate ETA estimates",
        position = 7
    )
    default boolean showDayAwareEta() {
        return true;
    }

    @ConfigItem(
        keyName = "notifyFillCompletion",
        name = "Notify on Fill Completion",
        description = "Send a notification when a GE offer finishes buying or selling",
        position = 8
    )
    default boolean notifyFillCompletion() {
        return true;
    }

    @ConfigItem(
        keyName = "notifyStall",
        name = "Notify on Stall",
        description = "Send a notification when an offer's ETA exceeds the stall threshold",
        position = 9
    )
    default boolean notifyStall() {
        return false;
    }

    @ConfigItem(
        keyName = "stallThresholdMinutes",
        name = "Stall Threshold (minutes)",
        description = "ETA above this many minutes is considered stalled",
        position = 10
    )
    default int stallThresholdMinutes() {
        return 120;
    }

    @ConfigItem(
        keyName = "notifyHighValue",
        name = "Notify High-Value Signals",
        description = "Send a notification when a high-margin, high-confidence signal is detected",
        position = 11
    )
    default boolean notifyHighValue() {
        return false;
    }

    @ConfigItem(
        keyName = "highValueMarginThreshold",
        name = "High-Value Margin (gp)",
        description = "Minimum estimated margin in gp to trigger a high-value notification",
        position = 12
    )
    default int highValueMarginThreshold() {
        return 10000;
    }

    @ConfigItem(
        keyName = "highValueConfidenceThreshold",
        name = "High-Value Confidence (%)",
        description = "Minimum fill confidence percentage to trigger a high-value notification",
        position = 13
    )
    default int highValueConfidenceThreshold() {
        return 70;
    }
}
