#!/bin/bash
HOST=${1:-localhost}
PORT=${2:-9999}
FILE=${3:-test-files/test_10mb.bin}

echo "🔥 Starting Simple QUIC Client to $HOST:$PORT"
echo "📁 File to transfer: $FILE"
java -cp build/libs/QUICC-1.0-SNAPSHOT.jar quic.SimpleQuicFileTransfer client "$HOST" "$PORT" "$FILE"