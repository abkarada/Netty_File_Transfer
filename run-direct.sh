#!/bin/bash

# QUIC File Transfer - Direct JAR Execution
# Usage: ./run-direct.sh server <port>
# Usage: ./run-direct.sh client <host> <port> <filename>

JAR_FILE="build/libs/quic-transfer.jar"

# Build if JAR doesn't exist
if [ ! -f "$JAR_FILE" ]; then
    echo "🔨 Building JAR file..."
    ./gradlew shadowJar
    if [ $? -ne 0 ]; then
        echo "❌ Build failed!"
        exit 1
    fi
fi

# Check arguments
if [ $# -lt 2 ]; then
    echo "Usage:"
    echo "  Server: $0 server <port>"
    echo "  Client: $0 client <host> <port> <filename>"
    echo ""
    echo "Examples:"
    echo "  $0 server 9443"
    echo "  $0 client localhost 9443 test-files/sample.txt"
    echo "  $0 client 192.168.1.100 9443 my-file.txt"
    exit 1
fi

MODE=$1

if [ "$MODE" == "server" ]; then
    if [ $# -ne 2 ]; then
        echo "Server usage: $0 server <port>"
        exit 1
    fi
    PORT=$2
    echo "🚀 Starting QUIC Server on port $PORT..."
    echo "Server will listen on all interfaces (0.0.0.0:$PORT)"
    echo "Press Ctrl+C to stop"
    echo ""
    java -jar "$JAR_FILE" server "$PORT"
    
elif [ "$MODE" == "client" ]; then
    if [ $# -ne 4 ]; then
        echo "Client usage: $0 client <host> <port> <filename>"
        exit 1
    fi
    HOST=$2
    PORT=$3
    FILENAME=$4
    
    if [ ! -f "$FILENAME" ]; then
        echo "❌ File not found: $FILENAME"
        exit 1
    fi
    
    echo "🚀 Starting QUIC Client..."
    echo "Target: $HOST:$PORT"
    echo "File: $FILENAME ($(du -h "$FILENAME" | cut -f1))"
    echo ""
    java -jar "$JAR_FILE" client "$HOST" "$PORT" "$FILENAME"
    
else
    echo "❌ Invalid mode: $MODE"
    echo "Use 'server' or 'client'"
    exit 1
fi