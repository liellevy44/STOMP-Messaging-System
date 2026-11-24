#!/usr/bin/env python3
"""
Basic Python Server for STOMP Assignment – Stage 3.3
Modified to include SQLite logic.
"""

import socket
import sys
import threading
import sqlite3
import os

SERVER_NAME = "STOMP_PYTHON_SQL_SERVER"  # DO NOT CHANGE!
DB_FILE = "stomp_server.db"              # DO NOT CHANGE!

def recv_null_terminated(sock: socket.socket) -> str:
    data = b""
    while True:
        chunk = sock.recv(1024)
        if not chunk:
            return ""
        data += chunk
        if b"\0" in data:
            msg, _ = data.split(b"\0", 1)
            return msg.decode("utf-8", errors="replace")

def init_database():
    """Create tables matching the Java Database.java schema exactly"""
    print(f"[{SERVER_NAME}] Initializing Database...")
    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()
    
    # 1. Users table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS users (
            username TEXT PRIMARY KEY,
            password TEXT NOT NULL,
            registration_date TEXT
        )
    """)
    
    # 2. Login History table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS login_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL,
            login_time TEXT NOT NULL,
            logout_time TEXT,
            FOREIGN KEY(username) REFERENCES users(username)
        )
    """)
    
    # 3. File Tracking table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS file_tracking (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL,
            filename TEXT NOT NULL,
            upload_time TEXT,
            game_channel TEXT,
            FOREIGN KEY(username) REFERENCES users(username)
        )
    """)
    
    conn.commit()
    conn.close()
    print(f"[{SERVER_NAME}] Database initialized with Java-compatible schema.")

def execute_sql_command(sql_command: str) -> str:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute(sql_command)
        conn.commit()
        conn.close()
        return "SUCCESS"
    except Exception as e:
        return f"ERROR: {e}"

def execute_sql_query(sql_query: str) -> str:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute(sql_query)
        rows = cursor.fetchall()
        conn.close()
        
        # Format: row1_field1 | row1_field2 \n row2_field1 | row2_field2
        result_list = []
        for row in rows:
            # Clean conversion to string separated by pipe
            row_str = " | ".join([str(item) if item is not None else "None" for item in row])
            result_list.append(row_str)
            
        return "\n".join(result_list)
    except Exception as e:
        return f"ERROR: {e}"

def handle_client(client_socket: socket.socket, addr):
    try:
        while True:
            message = recv_null_terminated(client_socket)
            if message == "":
                break

            print(f"SQL executing: {message}")
            
            response = ""
            if message.strip().upper().startswith("SELECT"):
                response = execute_sql_query(message)
            else:
                response = execute_sql_command(message)

            client_socket.sendall((response + "\0").encode("utf-8"))

    except Exception as e:
        print(f"Error: {e}")
    finally:
        client_socket.close()

def start_server(host="127.0.0.1", port=7778):
    init_database()
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        server_socket.bind((host, port))
        server_socket.listen(5)
        print(f"[{SERVER_NAME}] Listening on {host}:{port}")

        while True:
            client, addr = server_socket.accept()
            threading.Thread(target=handle_client, args=(client, addr), daemon=True).start()

    except KeyboardInterrupt:
        print("Stopping...")
    finally:
        server_socket.close()

if __name__ == "__main__":
    start_server()