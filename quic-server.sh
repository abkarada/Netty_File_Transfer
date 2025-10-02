#!/bin/bash
# QUIC Server - Direct execution
# Usage: ./quic-server.sh <port>

JAR_FILE="build/libs/quic-transfer.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "🔨 Building executable JAR..."
    ./gradlew shadowJar
fi

if [ $# -ne 1 ]; then
    echo "Usage: $0 <port>"
    echo "Example: $0 9443"
    exit 1
fi

PORT=$1
echo "🔥 QUIC Server starting on port $PORT..."
java -jar "$JAR_FILE" server "$PORT"