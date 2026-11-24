package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.impl.data.Database;
import bgu.spl.net.impl.data.LoginStatus;
import bgu.spl.net.srv.Connections;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {

    private int connectionId;
    private Connections<String> connections;
    private boolean shouldTerminate = false;
    private boolean isLoggedIn = false;
    private String username = null;
    private Map<String, String> subscriptions = new ConcurrentHashMap<>();

    // All login/logout logic is now delegated to the Database class.
    
    private static final AtomicInteger messageIdGenerator = new AtomicInteger(0);

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public void process(String message) {
        StompFrame request;
        try {
            request = StompFrame.fromString(message);
        } catch (Exception e) {
            sendError("Malformed Frame", "Failed to parse frame: " + e.getMessage());
            return;
        }

        String command = String.valueOf(request.getCommand());

        // Require login for all commands except CONNECT
        if (!isLoggedIn && !command.equals("CONNECT")) {
            sendError("Not connected", "You must log in first.");
            return;
        }

        try {
            switch (command) {
                case "CONNECT":
                    handleConnect(request);
                    break;
                case "SUBSCRIBE":
                    handleSubscribe(request);
                    break;
                case "UNSUBSCRIBE":
                    handleUnsubscribe(request);
                    break;
                case "SEND":
                    handleSend(request);
                    break;
                case "DISCONNECT":
                    handleDisconnect(request);
                    break;
                default:
                    sendError("Unknown Command", "Command " + command + " is not supported.");
            }
        } catch (Exception e) {
            sendError("Processing Error", "An error occurred while processing the request: " + e.getMessage());
        }
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    // --- Handlers for each STOMP command ---

    private void handleConnect(StompFrame frame) {
        if (isLoggedIn) {
            sendError("Already connected", "Client is already connected.");
            return;
        }
        String login = frame.getHeaders().get("login");
        String passcode = frame.getHeaders().get("passcode");

        if (login == null || passcode == null) {
            sendError("Malformed Frame", "CONNECT header missing login or passcode");
            return;
        }

        // --- SQL INTEGRATION START ---
        // Delegate the login check to the Database (which communicates with Python)
        LoginStatus status = Database.getInstance().login(connectionId, login, passcode);

        if (status == LoginStatus.LOGGED_IN_SUCCESSFULLY || status == LoginStatus.ADDED_NEW_USER) {
            // Login successful
            isLoggedIn = true;
            username = login;

            StompFrame connected = new StompFrame("CONNECTED");
            connected.addHeader("version", "1.2");
            connections.send(connectionId, connected.toString());
        } else {
            // Login failed - map the status to an error message
            String errMsg = "Login failed";
            if (status == LoginStatus.WRONG_PASSWORD) errMsg = "Wrong password";
            else if (status == LoginStatus.ALREADY_LOGGED_IN) errMsg = "User already logged in";
            else if (status == LoginStatus.CLIENT_ALREADY_CONNECTED) errMsg = "Client already connected";
            
            sendError("Login Error", errMsg);
        }
        // --- SQL INTEGRATION END ---
    }

    private void handleSubscribe(StompFrame frame) {
        String destination = frame.getHeaders().get("destination");
        String id = frame.getHeaders().get("id");
        String receipt = frame.getHeaders().get("receipt");

        if (destination == null || id == null) {
            sendError("Malformed Frame", "SUBSCRIBE missing destination or id headers.");
            return;
        }

        String prev = subscriptions.put(id, destination);
        if (prev != null) {
            connections.unsubscribe(prev, connectionId, id);
        }
        connections.subscribe(destination, connectionId, id);
        if (receipt != null) sendReceipt(receipt);
    }

    private void handleUnsubscribe(StompFrame frame) {
        String id = frame.getHeaders().get("id");
        String receipt = frame.getHeaders().get("receipt");

        if (id == null) {
            sendError("Malformed Frame", "UNSUBSCRIBE missing id header.");
            return;
        }

        String channel = subscriptions.remove(id);
        if (channel != null) {
            connections.unsubscribe(channel, connectionId, id);
        }

        if (receipt != null) sendReceipt(receipt);
    }

    private void handleSend(StompFrame frame) {
        String destination = frame.getHeaders().get("destination");
        String receipt = frame.getHeaders().get("receipt");

        if (destination == null) {
            sendError("Malformed Frame", "SEND missing destination header.");
            return;
        }

        String mySub = connections.getSubId(destination, connectionId);
        if (mySub == null) {
            sendError("Not Subscribed", "Client is not subscribed to " + destination);
            return;
        }
        
        // --- SQL INTEGRATION: Track File Upload ---
        String fileName = frame.getHeaders().get("file-name"); 
        if (fileName != null) {
            Database.getInstance().trackFileUpload(username, fileName, destination);
        }
        

        Map<Integer, String> subs = connections.getSubscribers(destination);
        if (subs == null || subs.isEmpty()) {
            if (receipt != null) sendReceipt(receipt);
            return;
        }
        
        int msgId = messageIdGenerator.incrementAndGet();
        for (Map.Entry<Integer, String> e : subs.entrySet()) {
            int targetConnId = e.getKey();
            String subId = e.getValue();

            StompFrame msg = new StompFrame("MESSAGE");
            msg.addHeader("destination", destination);
            msg.addHeader("message-id", String.valueOf(msgId));
            msg.addHeader("subscription", subId);
            msg.setBody(frame.getBody());

            connections.send(targetConnId, msg.toString());
        }

        if (receipt != null) {
            sendReceipt(receipt);
        }
    }

    private void handleDisconnect(StompFrame frame) {
        String receipt = frame.getHeaders().get("receipt");
        if (receipt != null) {
            sendReceipt(receipt);
        }
        terminate();
    }

    private void sendReceipt(String receiptId) {
        StompFrame receiptFrame = new StompFrame("RECEIPT");
        receiptFrame.addHeader("receipt-id", receiptId);
        connections.send(connectionId, receiptFrame.toString());
    }

    private void sendError(String message, String description) {
        StompFrame error = new StompFrame("ERROR");
        error.addHeader("message", message);
        error.setBody("The message:\n-----\n" + description + "\n-----");
        connections.send(connectionId, error.toString());
        terminate();
    }
    
    private void terminate() {
        shouldTerminate = true;
        
        // --- SQL INTEGRATION: Update Logout Time ---
        // Notify database that the user has disconnected
        if (isLoggedIn) {
            Database.getInstance().logout(connectionId);
        }
        
        for (Map.Entry<String, String> entry : subscriptions.entrySet()) {
            String subId = entry.getKey();
            String destination = entry.getValue();
            connections.unsubscribe(destination, connectionId, subId);
        }

        subscriptions.clear();
        connections.disconnect(connectionId);
    }
}