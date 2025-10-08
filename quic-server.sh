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

echo "🔥 Starting QUIC Server (OPTIMIZED) on port $PORT..."
echo "🚀 Using 1MB chunks + Direct ByteBuffers + JVM tuning"

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
     -jar "$JAR_FILE" server "$PORT"