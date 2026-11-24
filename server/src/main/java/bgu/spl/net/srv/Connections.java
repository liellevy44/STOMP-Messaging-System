package bgu.spl.net.srv;

import java.util.Map;

public interface Connections<T> {

    boolean send(int connectionId, T msg);

    void send(String channel, T msg);

    void disconnect(int connectionId);

    /// Helper Methods for the Protocol
    
    /// Adds a new connection to the active connections map
    void addConnection(int connectionId, ConnectionHandler<T> handler);

    //Subscribes a client to a specific channel.
    void subscribe(String channel, int connectionId, String subscriptionId);

    //Unsubscribes a client from a specific channel
    void unsubscribe(String channel, int connectionId, String subscriptionId);

    // Checks if a client is connected
    boolean isConnected(int connectionId);

    // Checks if a client is subscribed to a specific channel
    boolean isSubscribed(String channel, int connectionId);

    // Retrieves the subscription ID for a specific channel and connection ID
    String getSubId(String channel, int connectionId);

    // Retrieves the list of subscribers for a specific channel
    Map<Integer, String> getSubscribers(String channel);
}
