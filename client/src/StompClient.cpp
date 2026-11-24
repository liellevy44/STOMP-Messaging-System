#include <iostream>
#include <string>
#include <sstream>
#include <thread>
#include <atomic>
#include <vector>
#include <map>
#include <fstream>
#include <algorithm>
#include <mutex>
#include <condition_variable>
#include "../include/ConnectionHandler.h"
#include "../include/event.h"

struct GameStats {
    std::map<std::string, std::string> generalStats;
    std::map<std::string, std::string> teamAStats;
    std::map<std::string, std::string> teamBStats;
    // השינוי: שומרים זוג של (זמן, הודעה) כדי שנוכל למיין
    std::vector<std::pair<int, std::string>> eventLogs;

    // בנאי דיפולטיבי (מתקן את אזהרות Weffc++)
    GameStats() : generalStats(), teamAStats(), teamBStats(), eventLogs() {}
};

// Map: game name -> user name -> stats
std::map<std::string, std::map<std::string, GameStats>> game_data_map;

// Auxiliary maps
std::map<std::string, std::string> game_team_a_names;
std::map<std::string, std::string> game_team_b_names;
std::map<std::string, int> game_to_subid;
int subIdCounter = 1;
int receiptIdCounter = 1;

// --- Variables for Receipt synchronization (waiting for server confirmation) ---
std::mutex mtx;
std::condition_variable cv;
int expectedReceiptId = -1; // -1 means we are not currently waiting
bool receiptArrived = false;

std::string trim(std::string s) {
    while(!s.empty() && (s.back() == '\r' || s.back() == '\n' || s.back() == ' ')) {
        s.pop_back();
    }
    size_t first = s.find_first_not_of(" \r\n");
    if (std::string::npos == first) return "";
    return s.substr(first);
}

// blocks Main until confirmation arrives
void waitForReceipt(int id) {
    std::unique_lock<std::mutex> lk(mtx);
    expectedReceiptId = id;
    receiptArrived = false;
    cv.wait(lk, []{ return receiptArrived; });
    expectedReceiptId = -1; // Reset
}

void runListener(ConnectionHandler* handler, std::atomic<bool>& connected) {
    while (connected) {
        std::string answer;
        if (!handler->getFrameAscii(answer, '\0')) {
            std::cout << "Disconnected from server." << std::endl;
            connected = false;
            // Release in case of sudden disconnection to avoid blocking Main
            {
                std::lock_guard<std::mutex> lk(mtx);
                receiptArrived = true;
            }
            cv.notify_all();
            break;
        }

        if (answer.empty()) continue;

        // --- Handle RECEIPT ---
        if (answer.find("RECEIPT") == 0) {
            size_t idPos = answer.find("receipt-id:");
            if (idPos != std::string::npos) {
                size_t start = idPos + 11;
                size_t end = answer.find('\n', start);
                std::string recIdStr = trim(answer.substr(start, end - start));
                try {
                    int recId = std::stoi(recIdStr);
                    // Check if this is the Receipt we were waiting for
                    std::lock_guard<std::mutex> lk(mtx);
                    if (recId == expectedReceiptId) {
                        receiptArrived = true;
                        cv.notify_all(); // Unblock Main
                    }
                } catch (...) {}
            }
        }
        
        // --- Handle MESSAGE (save data for report) ---
        else if (answer.find("MESSAGE") == 0) {
            std::string gameName, user, eventName, time, description;
            std::map<std::string, std::string> updatesGen, updatesA, updatesB;
            
            // Extract game name from Header
            size_t destPos = answer.find("destination:/");
            if (destPos != std::string::npos) {
                size_t startName = destPos + 13;
                size_t endName = answer.find('\n', startName);
                gameName = trim(answer.substr(startName, endName - startName));
            }

            std::stringstream ss(answer);
            std::string line;
            bool isBody = false;
            bool inGen = false, inA = false, inB = false;

            while (std::getline(ss, line)) {
                line = trim(line);
                if (line.empty()) { isBody = true; continue; }
                if (!isBody) continue;

                if (line.find("user: ") == 0) user = line.substr(6);
                else if (line.find("team a: ") == 0) game_team_a_names[gameName] = line.substr(8);
                else if (line.find("team b: ") == 0) game_team_b_names[gameName] = line.substr(8);
                else if (line.find("event name: ") == 0) eventName = line.substr(12);
                else if (line.find("time: ") == 0) time = line.substr(6);
                else if (line.find("description: ") == 0) description = line.substr(13);
                
                // Identify update sections
                else if (line == "general game updates:") { inGen=true; inA=false; inB=false; }
                else if (line == "team a updates:") { inGen=false; inA=true; inB=false; }
                else if (line == "team b updates:") { inGen=false; inA=false; inB=true; }
                
                // Read keys and values
                else if (line.find(':') != std::string::npos) {
                    size_t colon = line.find(':');
                    std::string key = trim(line.substr(0, colon));
                    std::string val = trim(line.substr(colon + 1));
                    
                    if (inGen) updatesGen[key] = val;
                    else if (inA) updatesA[key] = val;
                    else if (inB) updatesB[key] = val;
                }
            }
            
            // Save data in the new data structure
            if (!user.empty() && !gameName.empty()) {
                GameStats& stats = game_data_map[gameName][user];
                
                // Update maps (overwrites previous values and saves new ones)
                for(auto& p : updatesGen) stats.generalStats[p.first] = p.second;
                for(auto& p : updatesA) stats.teamAStats[p.first] = p.second;
                for(auto& p : updatesB) stats.teamBStats[p.first] = p.second;
                
                std::string logEntry = time + " - " + eventName + ":\n" + description + "\n\n";
                
                // השינוי: המרת הזמן למספר ושמירה בוקטור
                try {
                    int timeInt = std::stoi(time);
                    stats.eventLogs.push_back({timeInt, logEntry});
                } catch (...) {
                    stats.eventLogs.push_back({0, logEntry});
                }
            }
        }
        
        // Print to screen (optional, to see what is happening)
        // std::cout << answer << std::endl; 
    }
}

int main(int argc, char *argv[]) {
    ConnectionHandler* connectionHandler = nullptr;
    std::atomic<bool> connected(false);
    std::thread* listenerThread = nullptr;
    std::string currentUsername = "";

    while (true) {
        const short bufsize = 1024;
        char buf[bufsize];
        if (!std::cin.getline(buf, bufsize)) break;
        std::string line(buf);
        std::stringstream ss(line);
        std::string command;
        ss >> command;

        if (command == "login") {
            if (connected) {
                std::cout << "The client is already logged in, log out before trying again" << std::endl;
                continue;
            }
            std::string hostPort, username, password;
            ss >> hostPort >> username >> password;
            currentUsername = username;
            size_t colonPos = hostPort.find(':');
            if (colonPos == std::string::npos) continue;
            std::string host = hostPort.substr(0, colonPos);
            short port = std::stoi(hostPort.substr(colonPos + 1));
            
            connectionHandler = new ConnectionHandler(host, port);
            if (!connectionHandler->connect()) {
                std::cout << "Could not connect to server" << std::endl;
                delete connectionHandler;
                connectionHandler = nullptr;
                continue;
            }
            
            connected = true;
            listenerThread = new std::thread(runListener, connectionHandler, std::ref(connected));
            
            std::string connectFrame = "CONNECT\naccept-version:1.2\nhost:stomp.cs.bgu.ac.il\nlogin:" + username + "\npasscode:" + password + "\n\n";
            connectionHandler->sendFrameAscii(connectFrame, '\0');
            
            std::cout << "Login successful" << std::endl; 
        } 
        else if (command == "join") {
            if (!connected) continue;
            std::string gameName;
            ss >> gameName;
            int id = subIdCounter++;
            int recId = receiptIdCounter++;
            game_to_subid[gameName] = id;
            
            // Send with Receipt
            std::string joinFrame = "SUBSCRIBE\ndestination:/" + gameName + "\nid:" + std::to_string(id) + "\nreceipt:" + std::to_string(recId) + "\n\n";
            connectionHandler->sendFrameAscii(joinFrame, '\0');
            
            // Wait for response
            waitForReceipt(recId);
            std::cout << "Joined channel " << gameName << std::endl;
        }
        else if (command == "exit") {
            if (!connected) continue;
            std::string gameName;
            ss >> gameName;
            if (game_to_subid.count(gameName)) {
                int recId = receiptIdCounter++;
                std::string unsubFrame = "UNSUBSCRIBE\nid:" + std::to_string(game_to_subid[gameName]) + "\nreceipt:" + std::to_string(recId) + "\n\n";
                connectionHandler->sendFrameAscii(unsubFrame, '\0');
                
                // Wait for response
                waitForReceipt(recId);
                std::cout << "Exited channel " << gameName << std::endl;
                
                game_to_subid.erase(gameName);
            }
        }
        else if (command == "report") {
            if (!connected) continue;
            std::string fileName;
            ss >> fileName;
            names_and_events result = parseEventsFile(fileName);
            
            for (const auto& event : result.events) {
                std::string dest = event.get_team_a_name() + "_" + event.get_team_b_name();
                std::string sendFrame = "SEND\n";
                sendFrame += "destination:/" + dest + "\n";
                sendFrame += "file-name:" + fileName + "\n";
                sendFrame += "\n"; 
                sendFrame += "user: " + currentUsername + "\nteam a: " + event.get_team_a_name() + "\nteam b: " + event.get_team_b_name() + "\nevent name: " + event.get_name() + "\n";
                sendFrame += "time: " + std::to_string(event.get_time()) + "\ngeneral game updates:\n";
                for (const auto& update : event.get_game_updates()) {
                    sendFrame += "    " + update.first + ": " + update.second + "\n";
                }
                sendFrame += "team a updates:\n"; 
                for (const auto& update : event.get_team_a_updates()) {
                     sendFrame += "    " + update.first + ": " + update.second + "\n";
                }
                sendFrame += "team b updates:\n"; 
                for (const auto& update : event.get_team_b_updates()) {
                     sendFrame += "    " + update.first + ": " + update.second + "\n";
                }
                
                sendFrame += "description: " + event.get_discription() + "\n";
                connectionHandler->sendFrameAscii(sendFrame, '\0');
            }
        }
        else if (command == "summary") {
            std::string gameName, user, fileName;
            ss >> gameName >> user >> fileName;
            
            // Check if there is information about this game and user
            if (game_data_map.count(gameName) && game_data_map[gameName].count(user)) {
                std::ofstream outFile(fileName);
                auto& stats = game_data_map[gameName][user];
                
                // Use team names saved from MESSAGE
                std::string teamA = game_team_a_names[gameName];
                std::string teamB = game_team_b_names[gameName];

                outFile << teamA << " vs " << teamB << "\n";
                outFile << "Game stats:\n";
                outFile << "General stats:\n";
                for (auto const& x : stats.generalStats) outFile << "    " << x.first << ": " << x.second << "\n";
                
                outFile << teamA << " stats:\n";
                for (auto const& x : stats.teamAStats) outFile << "    " << x.first << ": " << x.second << "\n";
                
                outFile << teamB << " stats:\n";
                for (auto const& x : stats.teamBStats) outFile << "    " << x.first << ": " << x.second << "\n";

                outFile << "Game event reports:\n";
                
                // השינוי: מיון האירועים לפי הזמן לפני ההדפסה
                std::sort(stats.eventLogs.begin(), stats.eventLogs.end(), 
                    [](const std::pair<int, std::string>& a, const std::pair<int, std::string>& b) {
                        return a.first < b.first;
                    }
                );

                // הדפסה
                for (const auto& logPair : stats.eventLogs) {
                    outFile << logPair.second;
                }
                
                outFile.close();
                std::cout << "Summary saved to " << fileName << std::endl;
            } else {
                std::cout << "No updates found for " << user << " in game " << gameName << std::endl;
            }
        }
        else if (command == "logout") {
            if (connected) {
                int recId = receiptIdCounter++;
                connectionHandler->sendFrameAscii("DISCONNECT\nreceipt:" + std::to_string(recId) + "\n\n", '\0');
                
                // Wait for confirmation before disconnecting!
                waitForReceipt(recId);
                
                connected = false;
                if (listenerThread->joinable()) listenerThread->join();
                delete listenerThread; listenerThread = nullptr;
                delete connectionHandler; connectionHandler = nullptr;
                
                std::cout << "Logout successful" << std::endl;
            }
        }
    }
    return 0;
}