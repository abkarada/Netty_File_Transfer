#!/bin/bash
PORT=${1:-9999}
echo "🚀 Starting Simple QUIC Server on port $PORT..."
java -cp build/libs/quic-transfer.jar quic.SimpleQuicFileTransfer server "$PORT"