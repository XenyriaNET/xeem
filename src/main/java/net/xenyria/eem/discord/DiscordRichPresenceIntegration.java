package net.xenyria.eem.discord;

import net.xenyria.eem.config.screen.XenyriaConfigManager;
import net.xenyria.eem.discord.activity.DefaultDiscordActivityAccess;
import net.xenyria.eem.discord.activity.IDiscordActivityAccess;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DiscordRichPresenceIntegration {

    private static DiscordRichPresenceIntegration instance;
    public static DiscordRichPresenceIntegration getInstance() { return instance; }
    public static void createInstance() throws IllegalStateException {
        if(instance != null) return;
        instance = new DiscordRichPresenceIntegration();
    }

    public static Logger LOGGER = LoggerFactory.getLogger("Xenyria/DiscordIntegration");
    // Delay in milliseconds until we assume that the connection to the server has been lost
    public static final long TIMEOUT = 3000L;
    // Delay in milliseconds for activity updates
    public static final long UPDATE_INTERVAL = 500L;

    // Constructor
    private DiscordRichPresenceIntegration() throws IllegalStateException {
        // Initialize the discord library
        discordActivityAccess = new DefaultDiscordActivityAccess();
        discordActivityAccess.initialize();
        // Start the rich presence update loop
        enterRichPresenceUpdateLoop();
    }

    public static JSONObject loadDefaultRichPresenceData() {
        try(var stream = DiscordRichPresenceIntegration.class
                .getResourceAsStream("/discord/anonymous_rich_presence.json")) {
            byte[] data = stream.readAllBytes();
            String rawJson = new String(data, StandardCharsets.UTF_8);
            return new JSONObject(rawJson);
        } catch (Exception e) {
            LOGGER.error("Failed to load default rich presence data: " + e.getMessage());
        }
        return null;
    }

    /**
     * Interface for accessing Discord's activity API
     */
    private final IDiscordActivityAccess discordActivityAccess;
    public IDiscordActivityAccess getActivityAccess() {
        return discordActivityAccess;
    }

    /**
     * The last known rich presence state is stored here along with a timestamp.
     * If we don't receive any new data in the last few seconds we'll automatically
     * stop sending rich presence data to the API.
     * **/
    private static final Object lock = new Object();
    private static JSONObject lastReceivedRichPresence;
    private static long lastReceivedPacket = 0L;
    public static void setLastReceivedRichPresence(JSONObject lastReceivedRichPresence) {
        synchronized (lock) {
            lastReceivedPacket = System.currentTimeMillis();
            DiscordRichPresenceIntegration.lastReceivedRichPresence = lastReceivedRichPresence;
        }
    }

    // The last application ID that was used to initialize the activity access.
    private long lastApplicationID = -1L;

    /**
     * Starts a thread that passes data to the activity access
     */
    public void enterRichPresenceUpdateLoop() {
        LOGGER.info("Entering rich presence update loop...");

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        LOGGER.info("Starting API task...");
        executor.scheduleAtFixedRate(() -> {
            try {
                JSONObject richPresenceData = null;
                boolean isDataPresent = false;

                // Lock so this#setLastReceivedRichPresence is blocked while we copy data
                synchronized (lock) {
                    if (lastReceivedRichPresence != null) {
                        long deltaSinceLastPacket = System.currentTimeMillis() - lastReceivedPacket;
                        if (deltaSinceLastPacket < TIMEOUT) {
                            isDataPresent = true;
                            richPresenceData = new JSONObject(lastReceivedRichPresence.toMap());
                        }
                    }
                }

                if (isDataPresent) {

                    // Replace the received rich presence data with generic content
                    // if the User doesn't want to share their activity on the server.
                    if (!XenyriaConfigManager.getConfig().shareServerActivity) {
                        richPresenceData = loadDefaultRichPresenceData();
                    }

                    long applicationId = richPresenceData.getLong("applicationId");
                    if (applicationId != lastApplicationID) {
                        // Application ID has changed - Therefore we restart our activity access with a different ID
                        lastApplicationID = applicationId;
                        LOGGER.info("Switching application ID to " + applicationId + "...");
                        discordActivityAccess.stop();
                        discordActivityAccess.start(applicationId);
                        return;
                    }

                    // Pass rich presence data down to the Discord activity access
                    var details = richPresenceData.getString("details");
                    var state = richPresenceData.getString("state");
                    var smallImageId = richPresenceData.getString("smallImageId");
                    var smallImageText = richPresenceData.getString("smallImageText");
                    var largeImageId = richPresenceData.getString("largeImageId");
                    var largeImageText = richPresenceData.getString("largeImageText");
                    var activityStart = richPresenceData.getLong("activityStart");
                    var activityEnd = richPresenceData.getLong("activityEnd");

                    discordActivityAccess.updateRichPresence(applicationId,
                            details,
                            state,
                            activityStart,
                            activityEnd,
                            smallImageId,
                            smallImageText,
                            largeImageId,
                            largeImageText);
                } else {
                    // The application ID has to be reset here so the core gets started up again once we receive data again
                    lastApplicationID = 0;
                    discordActivityAccess.stop();
                }
                // Run callbacks
                discordActivityAccess.runCallbacks();
            } catch (Exception e) {
                LOGGER.error("An error occurred during the rich presence update loop", e);
            }
        }, UPDATE_INTERVAL, UPDATE_INTERVAL, TimeUnit.MILLISECONDS);


    }

}
