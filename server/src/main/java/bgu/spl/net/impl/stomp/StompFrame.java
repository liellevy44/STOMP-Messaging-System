package bgu.spl.net.impl.stomp;

import java.util.HashMap;
import java.util.Map;

public class StompFrame {

    private String command;
    private Map<String, String> headers = new HashMap<>();
    private String body = "";

    public StompFrame(String command) {
        this.command = command;
    }

    public String getCommand() {
        return command;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public void addHeader(String key, String value) {
        headers.put(key, value);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(command).append('\n');

        for (Map.Entry<String, String> header : headers.entrySet()) {
            sb.append(header.getKey())
              .append(':')
              .append(header.getValue())
              .append('\n');
        }

        sb.append('\n'); // שורה ריקה שמפרידה בין ה-Headers ל-Body
        
        if (body != null && !body.isEmpty()) {
            sb.append(body);
        }

        sb.append('\u0000'); // תו הסיום של STOMP
        return sb.toString();
    }

    public static StompFrame fromString(String msg) {
        String[] lines = msg.split("\n", -1);
        int lineIdx = 0;

        // דילוג על שורות ריקות בהתחלה (קורה לפעמים בתקשורת)
        String commandString = null;
        while (lineIdx < lines.length) {
            String line = lines[lineIdx].trim();
            if (!line.isEmpty()) {
                commandString = line;
                lineIdx++;
                break;
            }
            lineIdx++;
        }

        if (commandString == null) {
            throw new IllegalArgumentException("No command found in message");
        }

        StompFrame frame = new StompFrame(commandString);

        // קריאת הכותרות (Headers)
        while (lineIdx < lines.length) {
            String line = lines[lineIdx];
            if (line.trim().isEmpty()) { // שורה ריקה מסמנת את סוף הכותרות
                lineIdx++;
                break;
            }

            int splitLimit = line.indexOf(':');
            if (splitLimit != -1) {
                String key = line.substring(0, splitLimit).trim();
                String value = line.substring(splitLimit + 1).trim();
                frame.addHeader(key, value);
            }
            lineIdx++;
        }

        // Body
        int emptyLineIndex = msg.indexOf("\n\n");
        if (emptyLineIndex != -1) {
            String bodyContent = msg.substring(emptyLineIndex + 2);
            // remove the trailing null character if present
            if (bodyContent.endsWith("\u0000")) {
                bodyContent = bodyContent.substring(0, bodyContent.length() - 1);
            }
            frame.setBody(bodyContent);
        }
        return frame;
    }
}