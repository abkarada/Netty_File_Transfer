#!/bin/bash
if [ $# -lt 1 ]; then
    echo "❌ Usage: ./simple-client.sh <HOST_IP> [PORT] [FILE]"
    echo "Example: ./simple-client.sh 192.168.1.100 9999 test-files/test_10mb.bin"
    exit 1
fi

HOST=$1
PORT=${2:-9999}
FILE=${3:-test-files/test_10mb.bin}

echo "🔥 Starting Simple QUIC Client to $HOST:$PORT"
echo "📁 File to transfer: $FILE"
java -cp build/libs/quic-transfer.jar quic.SimpleQuicFileTransfer client "$HOST" "$PORT" "$FILE"