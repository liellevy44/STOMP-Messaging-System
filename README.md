# Distributed STOMP Messaging System

A multi-language publish/subscribe messaging platform implementing the **STOMP 1.2** protocol over TCP sockets, designed for real-time sports event reporting and tracking.

## Architecture & Technologies
* **Server (Java):** Implements the STOMP 1.2 specification and supports two concurrency models selectable at runtime:
  * **Thread-Per-Client (TPC)**
  * **Reactor** (non-blocking I/O)
* **Client (C++):** Multithreaded terminal client handling concurrent keyboard commands and asynchronous socket communication, including JSON event file parsing and per-user game state tracking.
* **Database Service (Python & SQLite):** Relational database server handling user registration, login/logout session timestamps, and reporting history via SQL queries.

## Build & Run

### 1. Database Server (Python)
```bash
cd data
python3 sql_server.py
```

### 2. STOMP Server (Java / Maven)
```bash
cd server
mvn compile
# Run in Thread-Per-Client mode:
mvn exec:java -Dexec.mainClass="bgu.spl.net.impl.stomp.StompServer" -Dexec.args="<port> tpc"
# Or run in Reactor mode:
mvn exec:java -Dexec.mainClass="bgu.spl.net.impl.stomp.StompServer" -Dexec.args="<port> reactor"
```

### 3. Client (C++ / Make)
```bash
cd client
make
./bin/StompWCIClient
```

## Supported Client Commands
* `login {host:port} {username} {password}` — Connect and authenticate with the server.
* `join {game_name}` — Subscribe to a specific game topic.
* `exit {game_name}` — Unsubscribe from a game topic.
* `report {file}` — Parse a JSON game events file and broadcast updates to the channel.
* `summary {game_name} {user} {file}` — Export chronologically ordered game events and statistics for a specific reporter.
* `logout` — Gracefully disconnect from the server using STOMP receipts.
