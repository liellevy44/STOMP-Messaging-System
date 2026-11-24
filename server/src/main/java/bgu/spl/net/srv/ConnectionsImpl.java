package bgu.spl.net.srv;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionsImpl<T> implements Connections<T> {

    // Mapping: connection ID - ConnectionHandler
    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> activeConnections = new ConcurrentHashMap<>();

    // Mapping: channel name - list of subscribed connection IDs
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, String>> channelSubscribers = new ConcurrentHashMap<>();

    // Sends a message to a specific client by connection ID
    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = activeConnections.get(connectionId);
        if (handler == null) return false;
        try {
            handler.send(msg);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Sends a message to all clients subscribed to a specific channel
    @Override
    public void send(String channel, T msg) {
        ConcurrentHashMap<Integer, String> subscribers = channelSubscribers.get(channel); 
        if (subscribers == null) return;
        for (Integer connId : subscribers.keySet()) {
            send(connId, msg);
        }
    }

    //Disconnects a client by removing them from the active connections map.
    @Override
    public void disconnect(int connectionId) {
        ConnectionHandler<T> handler = activeConnections.remove(connectionId);
        for (Map.Entry<String, ConcurrentHashMap<Integer, String>> entry : channelSubscribers.entrySet()) {
            String channel = entry.getKey();
            ConcurrentHashMap<Integer, String> subs = entry.getValue();
            subs.remove(connectionId);
            if (subs.isEmpty()) {
                channelSubscribers.remove(channel, subs);
            }
        }
        if (handler != null) {
            try { handler.close(); } catch (Exception ignored) {}
        }
    }


    // --- Helper Methods for the Protocol ---

    // Retrieves the list of subscribers for a specific channel
    @Override
    public Map<Integer, String> getSubscribers(String channel) {
    return channelSubscribers.get(channel);
    }

    // Adds a new connection to the active connections map
    @Override
    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        activeConnections.put(connectionId, handler);
    }

   //Subscribes a client to a specific channel.
   @Override
    public void subscribe(String channel, int connectionId, String subscriptionId) {
        if (!activeConnections.containsKey(connectionId)) return;
        channelSubscribers.computeIfAbsent(channel, k -> new ConcurrentHashMap<>()).put(connectionId, subscriptionId);
    }

    //Unsubscribes a client from a specific channel
    @Override
    public void unsubscribe(String channel, int connectionId, String subscriptionId) {
       ConcurrentHashMap<Integer, String> subs = channelSubscribers.get(channel);
        if (subs == null) return;
        String current = subs.get(connectionId);
        if (current != null && current.equals(subscriptionId)) {
            subs.remove(connectionId);
            if (subs.isEmpty()) {
                channelSubscribers.remove(channel, subs);
            }
        }
    }

    //Check if a connection exists and is active
    @Override
    public boolean isConnected(int connectionId) {
        return activeConnections.containsKey(connectionId);
    }

    // Check if a connection is subscribed to a specific channel
    @Override
    public boolean isSubscribed(String channel, int connectionId) {
        ConcurrentHashMap<Integer, String> subscribers = channelSubscribers.get(channel);
        return subscribers != null && subscribers.containsKey(connectionId);
    }

    // Get the subscription ID for a specific channel and connection ID
    @Override
    public String getSubId(String channel, int connectionId) {
        ConcurrentHashMap<Integer, String> subs = channelSubscribers.get(channel);
        if (subs == null) return null;
        return subs.get(connectionId);
}

}