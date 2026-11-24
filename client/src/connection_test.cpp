#include <iostream>
#include <string>
#include "../include/ConnectionHandler.h"

int main() {
    std::string host = "127.0.0.1";
    short port = 7777; // תשני לפורט שהשרת שלך מאזין אליו

    ConnectionHandler ch(host, port);

    std::cout << "Connecting to " << host << ":" << port << "...\n";
    if (!ch.connect()) {
        std::cerr << "❌ connect() failed\n";
        return 1;
    }
    std::cout << "✅ connected\n";

    // שולחים פריים שמסתיים ב-\0 (כמו STOMP Frame)
    std::string frameToSend = "hello-from-client";
    if (!ch.sendFrameAscii(frameToSend, '\0')) {
        std::cerr << "❌ sendFrameAscii failed\n";
        return 1;
    }
    std::cout << "➡️ sent frame: " << frameToSend << " (delim=\\0)\n";

    // מקבלים פריים עד \0
    std::string received;
    if (!ch.getFrameAscii(received, '\0')) {
        std::cerr << "❌ getFrameAscii failed\n";
        return 1;
    }
    std::cout << "⬅️ received frame: " << received << "\n";

    ch.close();
    std::cout << "🔌 closed\n";
    return 0;
}
