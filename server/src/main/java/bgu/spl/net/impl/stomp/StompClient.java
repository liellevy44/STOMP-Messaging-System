package bgu.spl.net.impl.stomp;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class StompClient {

    // STOMP frames end with null char \0
    private static final char STOMP_EOF = '\0';

    public static void main(String[] args) {
        String host = (args.length >= 1) ? args[0] : "localhost";
        int port = (args.length >= 2) ? Integer.parseInt(args[1]) : 7777;

        System.out.println("Connecting to " + host + ":" + port + " ...");

        try (Socket socket = new Socket(host, port);
             BufferedReader serverIn = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter serverOut = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader userIn = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            System.out.println("Connected.");
            System.out.println("Type STOMP frames. End a frame with an empty line.");
            System.out.println("Example:");
            System.out.println("CONNECT");
            System.out.println("accept-version:1.2");
            System.out.println("host:localhost");
            System.out.println("login:alice");
            System.out.println("passcode:123");
            System.out.println("(empty line to send)\n");

            // Thread that constantly reads server responses
            Thread reader = new Thread(() -> {
                try {
                    String frame;
                    while ((frame = readStompFrame(serverIn)) != null) {
                        System.out.println("\n--- SERVER FRAME ---");
                        System.out.println(frame);
                        System.out.print("> ");
                    }
                } catch (IOException e) {
                    System.out.println("\n[Client] Server connection closed.");
                }
            });
            reader.setDaemon(true);
            reader.start();

            // Main loop: read user frame lines until empty line -> send frame
            while (true) {
                System.out.print("> ");
                String userFrame = readUserFrame(userIn);
                if (userFrame == null) break;

                // Optional local exit
                if (userFrame.trim().equalsIgnoreCase("exit")) {
                    System.out.println("Exiting client.");
                    break;
                }

                // Send to server with \0 terminator
                serverOut.write(userFrame);
                serverOut.write(STOMP_EOF);
                serverOut.flush();
            }

        } catch (Exception e) {
            System.out.println("Client error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Reads user input until an empty line (meaning end-of-frame)
    private static String readUserFrame(BufferedReader userIn) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        boolean gotAnyLine = false;

        while ((line = userIn.readLine()) != null) {
            // empty line = end of frame
            if (line.isEmpty()) {
                if (!gotAnyLine) {
                    // If user pressed Enter twice (empty frame), ignore and ask again
                    return "";
                }
                sb.append("\n"); // STOMP requires a blank line before body if body exists; keeping user format
                return sb.toString();
            }
            gotAnyLine = true;
            sb.append(line).append("\n");
        }
        return null;
    }

    // Reads from server until \0
    private static String readStompFrame(BufferedReader serverIn) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = serverIn.read()) != -1) {
            if (ch == STOMP_EOF) {
                return sb.toString();
            }
            sb.append((char) ch);
        }
        return null; // socket closed
    }
}
