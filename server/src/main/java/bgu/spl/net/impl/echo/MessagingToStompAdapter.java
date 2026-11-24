package bgu.spl.net.impl.echo;

import bgu.spl.net.api.MessagingProtocol;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;

public class MessagingToStompAdapter<T> implements StompMessagingProtocol<T> {

    private final MessagingProtocol<T> delegate;
    private Connections<T> connections;
    private int connectionId;

    public MessagingToStompAdapter(MessagingProtocol<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void start(int connectionId, Connections<T> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public void process(T message) {
        T response = delegate.process(message);
        if (response != null && connections != null) {
            connections.send(connectionId, response);
        }

        if (delegate.shouldTerminate() && connections != null) {
            connections.disconnect(connectionId);
        }
    }

    @Override
    public boolean shouldTerminate() {
        return delegate.shouldTerminate();
    }
}
