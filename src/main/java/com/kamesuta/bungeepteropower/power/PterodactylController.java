package com.kamesuta.bungeepteropower.power;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kamesuta.bungeepteropower.api.PowerController;
import com.kamesuta.bungeepteropower.api.PowerSignal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

import static com.kamesuta.bungeepteropower.BungeePteroPower.logger;
import static com.kamesuta.bungeepteropower.BungeePteroPower.plugin;

/**
 * Pterodactyl API client.
 */
public class PterodactylController implements PowerController {
    /**
     * Send a power signal to the Pterodactyl server.
     *
     * @param serverName The name of the server to start
     * @param serverId   The Pterodactyl server ID
     * @param signalType The power signal to send
     * @return A future that completes when the request is finished
     */
    @Override
    public CompletableFuture<Void> sendPowerSignal(String serverName, String serverId, PowerSignal signalType) {
        String signal = signalType.getSignal();
        String doing = signalType == PowerSignal.START ? "Starting" : "Stopping";
        logger.info(String.format("%s server: %s (Pterodactyl server ID: %s)", doing, serverName, serverId));

        // Create a path
        String path = "/api/client/servers/" + serverId + "/power";

        HttpClient client = HttpClient.newHttpClient();

        // Create a JSON body to send power signal
        String jsonBody = "{\"signal\": \"" + signal + "\"}";

        // Create a request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(plugin.config.pterodactylUrl.resolve(path).toString()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + plugin.config.pterodactylApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        // Execute request and register a callback
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(status -> {
                    int code = status.statusCode();
                    if (code == 204) {
                        logger.info("Successfully sent " + signal + " signal to the server: " + serverName);
                        return (Void) null;
                    } else {
                        String message = "Failed to send " + signal + " signal to the server: " + serverName + ". Response code: " + code;
                        logger.warning(message);
                        logger.info("Request: " + request + ", Response: " + code + " " + status.body());
                        throw new RuntimeException(message);
                    }
                })
                .exceptionally(e -> {
                    logger.log(Level.WARNING, "Failed to send " + signal + " signal to the server: " + serverName, e);
                    throw new CompletionException(e);
                });
    }

    /**
     * Restore from a backup.
     * Send a stop signal to the server, wait until the server is offline, and then restore from a backup.
     *
     * @param serverName The name of the server
     * @param serverId   The Pterodactyl server ID
     * @param backupName The name of the backup
     * @return A future that completes when the request to restore from the backup is sent after the server becomes offline
     */
    @Override
    public CompletableFuture<Void> sendRestoreSignal(String serverName, String serverId, String backupName) {
        // First, stop the server
        sendPowerSignal(serverName, serverId, PowerSignal.STOP);

        // Wait until the power status becomes offline
        logger.info(String.format("Waiting server to stop: %s (Pterodactyl server ID: %s)", serverName, serverId));
        return waitUntilOffline(serverName, serverId)
                .thenCompose((v) -> {
                    // Restore the backup
                    logger.info(String.format("Successfully stopped server: %s", serverName));
                    return restoreBackup(serverName, serverId, backupName);
                });
    }

    /**
     * Restore from a backup.
     *
     * @param serverName The name of the server
     * @param serverId   The Pterodactyl server ID
     * @param backupUuid The UUID of the backup
     * @return A future that completes when the request is finished
     */

    public CompletableFuture<Void> getTotalMemory(Set<String> servers) {
        String signal = signalType.getSignal();
        String doing = signalType == PowerSignal.START ? "Starting" : "Stopping";
        logger.info(String.format("%s server: %s (Pterodactyl server ID: %s)", doing, serverName, serverId));

        // Create a path
        String path = "/api/client/servers/";

        HttpClient client = HttpClient.newHttpClient();

        // Create a JSON body to send power signal
        String jsonBody = "{\"signal\": \"" + signal + "\"}";

        // Create a request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(plugin.config.pterodactylUrl.resolve(path).toString()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + plugin.config.pterodactylApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        // Execute request and register a callback
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(status -> {
                    int code = status.statusCode();
                    if (code == 200) {
                        // Parse JSON (attributes.current_state)
                        JsonObject root = JsonParser.parseString(status.body()).getAsJsonObject();
                        JsonArray dataArray = root.getAsJsonArray("data");

                        // Initialize the sum variable
                        int memorySum = 0;

                        // Iterate through the data array and sum the memory values
                        for (JsonElement element : dataArray) {
                            JsonObject dataObject = element.getAsJsonObject();
                            JsonObject attributes = dataObject.getAsJsonObject("attributes");
                            JsonObject limits = attributes.getAsJsonObject("limits");

                            // Get the memory value and add it to the sum
                            int memory = limits.get("memory").getAsInt();
                            memorySum += memory;
                        }
                        return memorySum;
                    } else {
                        String message = "Failed to check total memory, Response: " + code + " code";
                        logger.warning(message);
                        logger.info("Request: " + request + ", Response: " + code + " " + status.body());
                        throw new RuntimeException(message);
                    }
                })
                .exceptionally(e -> {
                    logger.log(Level.WARNING, "Failed to send " + signal + " signal to the server: " + serverName, e);
                    throw new CompletionException(e);
                });

    }

    private CompletableFuture<Void> restoreBackup(String serverName, String serverId, String backupUuid) {
        logger.info(String.format("Restoring from backup: %s to server: %s (Pterodactyl server ID: %s)", backupUuid, serverName, serverId));

        // Create a path
        String path = "/api/client/servers/" + serverId + "/backups/" + backupUuid + "/restore";

        HttpClient client = HttpClient.newHttpClient();

        // Create a JSON body to delete all files
        String jsonBody = "{\"truncate\":true}";

        // Create a request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(plugin.config.pterodactylUrl.resolve(path).toString()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + plugin.config.pterodactylApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        // Execute request and register a callback
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(status -> {
                    int code = status.statusCode();
                    if (code == 204) {
                        logger.info("Successfully restored backup: " + backupUuid + " to server: " + serverName);
                        return (Void) null;
                    } else {
                        String message = "Failed to restore backup: " + backupUuid + " to server: " + serverName + ". Response code: " + code;
                        logger.warning(message);
                        logger.info("Request: " + request + ", Response: " + code + " " + status.body());
                        throw new RuntimeException(message);
                    }
                })
                .exceptionally(e -> {
                    logger.log(Level.WARNING, "Failed to restore backup: " + backupUuid + " to server: " + serverName, e);
                    throw new CompletionException(e);
                });
    }

    /**
     * Wait until the power status becomes offline.
     *
     * @param serverName The name of the server
     * @param serverId   The Pterodactyl server ID
     * @return A future that waits until the server becomes offline
     */
    private CompletableFuture<Void> waitUntilOffline(String serverName, String serverId) {
        CompletableFuture<Void> future = new CompletableFuture<Void>().orTimeout(plugin.config.restoreTimeout, TimeUnit.SECONDS);
        // Wait until the server becomes offline
        Consumer<String> callback = new Consumer<>() {
            @Override
            public void accept(String powerStatus) {
                // Do nothing if timeout or already completed
                if (future.isDone()) {
                    return;
                }
                // Complete if the server is offline
                if (powerStatus.equals("offline")) {
                    future.complete(null);
                    return;
                }
                // Otherwise schedule another ping
                logger.fine("Server is still " + powerStatus + ". Waiting for it to be offline: " + serverName);
                plugin.getProxy().getScheduler().schedule(plugin, () -> getPowerStatus(serverName, serverId).thenAccept(this), plugin.config.restorePingInterval, TimeUnit.SECONDS);
            }
        };
        // Initial check
        getPowerStatus(serverName, serverId).thenAccept(callback);

        return future;
    }

    /**
     * Get the power status of the server.
     *
     * @param serverName The name of the server
     * @param serverId   The Pterodactyl server ID
     * @return A future that completes with the power status
     */
    private CompletableFuture<String> getPowerStatus(String serverName, String serverId) {
        // Create a path
        String path = "/api/client/servers/" + serverId + "/resources";

        HttpClient client = HttpClient.newHttpClient();

        // Create a request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(plugin.config.pterodactylUrl.resolve(path).toString()))
                .header("Authorization", "Bearer " + plugin.config.pterodactylApiKey)
                .GET()
                .build();

        // Execute request and register a callback
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(status -> {
                    int code = status.statusCode();
                    if (code == 200) {
                        // Parse JSON (attributes.current_state)
                        JsonObject root = JsonParser.parseString(status.body()).getAsJsonObject();
                        return root.getAsJsonObject("attributes").get("current_state").getAsString();
                    } else {
                        String message = "Failed to get power status of server: " + serverName + ". Response code: " + code;
                        logger.warning(message);
                        logger.info("Request: " + request + ", Response: " + code + " " + status.body());
                        throw new RuntimeException(message);
                    }
                })
                .exceptionally(e -> {
                    logger.log(Level.WARNING, "Failed to get power status of server: " + serverName, e);
                    throw new CompletionException(e);
                });
    }
}
