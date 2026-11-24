package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Server;

public class StompServer {

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: StompServer <port> <server-type>");
            System.err.println("  server-type: 'tpc' or 'reactor'");
            System.exit(1);
        }

        int port = Integer.parseInt(args[0]);
        String serverType = args[1].toLowerCase();

        Server<String> server;

        if (serverType.equals("tpc")) {
            server = Server.threadPerClient(
                port,
                StompMessagingProtocolImpl::new,
                StompMessageEncoderDecoder::new
            );
        } else if (serverType.equals("reactor")) {
            server = Server.reactor(
                Runtime.getRuntime().availableProcessors(),
                port,
                StompMessagingProtocolImpl::new,
                StompMessageEncoderDecoder::new
            );
        } else {
            System.err.println("Unknown server type: " + serverType);
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        System.out.println("\n--- SERVER SHUTDOWN: PRINTING DATABASE REPORT ---");
        bgu.spl.net.impl.data.Database.getInstance().printReport();
        }));
        server.serve();
    }
}


