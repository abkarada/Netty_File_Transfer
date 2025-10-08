#!/bin/bash
# QUIC Client - Direct execution  
# Usage: ./quic-client.sh <host> <port> <filename>

JAR_FILE="build/libs/quic-transfer.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "🔨 Building executable JAR..."
    ./gradlew shadowJar
fi

if [ $# -ne 3 ]; then
    echo "Usage: $0 <host> <port> <filename>"
    echo "Examples:"
    echo "  $0 localhost 9443 test-files/sample.txt"
    echo "  $0 192.168.1.100 9443 my-document.pdf"
    exit 1
fi

HOST=$1
PORT=$2
FILENAME=$3

if [ ! -f "$FILENAME" ]; then
    echo "❌ File not found: $FILENAME"
    exit 1
fi

echo "🚀 QUIC Client connecting to $HOST:$PORT (OPTIMIZED)"
echo "📄 Sending file: $FILENAME ($(du -h "$FILENAME" | cut -f1))"
# JVM performance tuning for high-throughput file transfer
java -Xms512m -Xmx2g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=10 \
     -XX:+UseStringDeduplication \
     -Dio.netty.allocator.type=pooled \
     -Dio.netty.allocator.numDirectArenas=16 \
     -Dio.netty.allocator.numHeapArenas=16 \
     -Dio.netty.allocator.pageSize=8192 \
     -Dio.netty.allocator.maxOrder=11 \
     -jar "$JAR_FILE" client "$HOST" "$PORT" "$FILENAME"