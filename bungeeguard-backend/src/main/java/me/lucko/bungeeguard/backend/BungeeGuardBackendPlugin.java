package me.lucko.bungeeguard.backend;

import com.destroystokyo.paper.event.player.PlayerHandshakeEvent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Simple plugin which re-implements the BungeeCord handshake protocol, and cancels all attempts
 * which don't contain the special token set by the proxy.
 *
 * The token is included within the player's profile properties, but removed during the handshake.
 */
public class BungeeGuardBackendPlugin extends JavaPlugin implements Listener {
    private static final Type PROPERTY_LIST_TYPE = new TypeToken<List<JsonObject>>(){}.getType();

    private final Gson gson = new Gson();

    private String noDataKickMessage;
    private String noPropertiesKickMessage;
    private String invalidTokenKickMessage;

    private Set<String> allowedTokens;

    @Override
    public void onEnable() {
        try {
            Class.forName("com.destroystokyo.paper.event.player.PlayerHandshakeEvent");
        } catch (ClassNotFoundException e1) {
            getLogger().severe("Server " + getServer().getName() + " " + getServer().getBukkitVersion() + " is incompatible with this plugin!");
            try {
                Class.forName("com.destroystokyo.paper.PaperConfig");
                getLogger().info("Your server is too old and does not have the required API, please update!");
            } catch (ClassNotFoundException e2) {
                getLogger().info("You are running a server type which does not provide the required API.");
                getLogger().info("Please install a recent version of Paper! For more info visit https://papermc.io");
            }
            getLogger().info("Shutting down the server to be safe!");
            getServer().shutdown();
            return;
        }
        getLogger().info("Using Paper PlayerHandshakeEvent");
        getServer().getPluginManager().registerEvents(this, this);

        saveDefaultConfig();
        FileConfiguration config = getConfig();

        this.noDataKickMessage = ChatColor.translateAlternateColorCodes('&', config.getString("no-data-kick-message"));
        this.noPropertiesKickMessage = ChatColor.translateAlternateColorCodes('&', config.getString("no-properties-kick-message"));
        this.invalidTokenKickMessage = ChatColor.translateAlternateColorCodes('&', config.getString("invalid-token-kick-message"));
        this.allowedTokens = new HashSet<>(config.getStringList("allowed-tokens"));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHandshake(PlayerHandshakeEvent e) {
        String handshake = e.getOriginalHandshake();
        String[] split = handshake.split("\00");

        if (split.length != 3 && split.length != 4) {
            e.setFailMessage(this.noDataKickMessage);
            e.setFailed(true);
            return;
        }

        // extract ipforwarding info from the handshake
        String serverHostname = split[0];
        String socketAddressHostname = split[1];
        UUID uniqueId = UUID.fromString(split[2].replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));

        // doesn't contain any properties - so deny
        if (split.length == 3) {
            getLogger().warning("Denied connection from " + uniqueId + " @ " + socketAddressHostname + " - No properties were sent in their handshake.");
            e.setFailMessage(this.noPropertiesKickMessage);
            e.setFailed(true);
            return;
        }

        // deserialize the properties in the handshake
        List<JsonObject> properties = new ArrayList<>(this.gson.fromJson(split[3], PROPERTY_LIST_TYPE));

        // fail if no properties
        if (properties.isEmpty()) {
            getLogger().warning("Denied connection from " + uniqueId + " @ " + socketAddressHostname + " - No properties were sent in their handshake.");
            e.setFailMessage(this.noPropertiesKickMessage);
            e.setFailed(true);
            return;
        }

        String token = null;

        // try to find the token
        for (JsonObject property : properties) {
            if (property.get("name").getAsString().equals("bungeeguard-token")) {
                token = property.get("value").getAsString();
                break;
            }
        }

        // deny connection if no token was provided
        if (token == null) {
            getLogger().warning("Denied connection from " + uniqueId + " @ " + socketAddressHostname + " - A token was not included in their handshake properties.");
            e.setFailMessage(this.noPropertiesKickMessage);
            e.setFailed(true);
            return;
        }

        if (this.allowedTokens.isEmpty()) {
            getLogger().info("No token configured. Saving the one from the connection " + uniqueId + " @ " + socketAddressHostname + " to the config!");
            this.allowedTokens.add(token);
            getConfig().set("allowed-tokens", new ArrayList<>(this.allowedTokens));
            saveConfig();
        } else if (!this.allowedTokens.contains(token)) {
            getLogger().warning("Denied connection from " + uniqueId + " @ " + socketAddressHostname + " - An invalid token was used: " + token);
            e.setFailMessage(this.invalidTokenKickMessage);
            e.setFailed(true);
            return;
        }

        // remove our property
        properties.removeIf(property -> property.get("name").getAsString().equals("bungeeguard-token"));
        String newPropertiesString = this.gson.toJson(properties, PROPERTY_LIST_TYPE);

        // pass data back to the event
        e.setServerHostname(serverHostname);
        e.setSocketAddressHostname(socketAddressHostname);
        e.setUniqueId(uniqueId);
        e.setPropertiesJson(newPropertiesString);
    }

}
