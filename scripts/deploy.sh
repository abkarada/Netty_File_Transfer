#!/bin/bash

# QUIC File Transfer Deployment Script
# Bu script projeyi diğer bilgisayarlara deploy etmek için kullanılır

echo "🚀 QUIC File Transfer Deployment"
echo "================================="

# Build the project
echo "📦 Building project..."
./gradlew build

if [ $? -ne 0 ]; then
    echo "❌ Build failed!"
    exit 1
fi

echo "✅ Build successful!"

# Create deployment package
DEPLOY_DIR="quic-deploy-$(date +%Y%m%d-%H%M%S)"
echo "📁 Creating deployment package: $DEPLOY_DIR"

mkdir -p "$DEPLOY_DIR"

# Copy necessary files
cp -r build/libs "$DEPLOY_DIR/"
cp -r src "$DEPLOY_DIR/"
cp build.gradle "$DEPLOY_DIR/"
cp gradle.properties "$DEPLOY_DIR/" 2>/dev/null || true
cp settings.gradle "$DEPLOY_DIR/" 2>/dev/null || true
cp -r gradle "$DEPLOY_DIR/"
cp gradlew "$DEPLOY_DIR/"
cp gradlew.bat "$DEPLOY_DIR/"
cp -r test-files "$DEPLOY_DIR/"
cp README.md "$DEPLOY_DIR/"

# Create run scripts
cat > "$DEPLOY_DIR/run-server.sh" << 'EOF'
#!/bin/bash
echo "🔥 Starting QUIC Server..."
echo "Server will listen on all interfaces (0.0.0.0:9443)"
echo "Press Ctrl+C to stop"
echo ""
./gradlew run --args="server 9443"
EOF

cat > "$DEPLOY_DIR/run-client.sh" << 'EOF'
#!/bin/bash
if [ $# -lt 2 ]; then
    echo "Usage: $0 <server-ip> <filename>"
    echo "Example: $0 192.168.1.100 test-files/sample.txt"
    exit 1
fi

SERVER_IP=$1
FILENAME=$2

echo "🔥 Starting QUIC Client..."
echo "Connecting to: $SERVER_IP:9443"
echo "File to send: $FILENAME"
echo ""
./gradlew run --args="client $SERVER_IP 9443 $FILENAME"
EOF

chmod +x "$DEPLOY_DIR/run-server.sh"
chmod +x "$DEPLOY_DIR/run-client.sh"
chmod +x "$DEPLOY_DIR/gradlew"

# Create setup instructions
cat > "$DEPLOY_DIR/SETUP.md" << 'EOF'
# QUIC File Transfer Setup

## Requirements
- Java 11 or higher
- Open UDP port 9443

## Server Setup
1. Extract this package on the server machine
2. Run: `./run-server.sh`
3. Server will listen on all interfaces (0.0.0.0:9443)

## Client Setup
1. Extract this package on the client machine
2. Run: `./run-client.sh <server-ip> <filename>`
3. Example: `./run-client.sh 192.168.1.100 test-files/sample.txt`

## Firewall Configuration
### Ubuntu/Debian:
```bash
sudo ufw allow 9443/udp
```

### CentOS/RHEL:
```bash
sudo firewall-cmd --add-port=9443/udp --permanent
sudo firewall-cmd --reload
```

## Network Testing
```bash
# Check if server is running
netstat -ulnp | grep 9443

# Test UDP connectivity from client
nc -u <server-ip> 9443
```
EOF

# Create archive
echo "📦 Creating deployment archive..."
tar -czf "${DEPLOY_DIR}.tar.gz" "$DEPLOY_DIR"

echo ""
echo "✅ Deployment package created!"
echo "📁 Directory: $DEPLOY_DIR"
echo "📦 Archive: ${DEPLOY_DIR}.tar.gz"
echo ""
echo "🚀 Next Steps:"
echo "1. Copy ${DEPLOY_DIR}.tar.gz to target machines"
echo "2. Extract: tar -xzf ${DEPLOY_DIR}.tar.gz"
echo "3. Run server: cd $DEPLOY_DIR && ./run-server.sh"
echo "4. Run client: cd $DEPLOY_DIR && ./run-client.sh <server-ip> <file>"