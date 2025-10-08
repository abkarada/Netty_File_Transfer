#!/bin/bash

echo "=== QUIC File Transfer Network Configuration ==="
echo ""

# IP Address Detection
echo "🌐 Available Network Interfaces:"
echo "================================="
ip addr show | grep -E "inet [0-9]" | grep -v "127.0.0.1" | awk '{print $2}' | cut -d'/' -f1 | while read ip; do
    interface=$(ip route get $ip | grep -oP 'dev \K\w+' | head -1)
    echo "  Interface: $interface -> IP: $ip"
done

echo ""
echo "🔍 Recommended Server Command:"
echo "=============================="
SERVER_IP=$(ip route get 8.8.8.8 | grep -oP 'src \K\S+')
echo "  ./gradlew run --args=\"server 9443\""
echo "  Server will listen on all interfaces (0.0.0.0:9443)"
echo "  Main IP: $SERVER_IP"

echo ""
echo "🔍 Client Connection Commands:"
echo "=============================="
echo "  From same machine:"
echo "    ./gradlew run --args=\"client localhost 9443 test-files/sample.txt\""
echo ""
echo "  From different machine:"
echo "    ./gradlew run --args=\"client $SERVER_IP 9443 test-files/sample.txt\""

echo ""
echo "🔥 Firewall Configuration:"
echo "=========================="
echo "  Ubuntu/Debian:"
echo "    sudo ufw allow 9443/udp"
echo "    sudo ufw status"
echo ""
echo "  CentOS/RHEL:"
echo "    sudo firewall-cmd --add-port=9443/udp --permanent"
echo "    sudo firewall-cmd --reload"

echo ""
echo "🧪 Network Test Commands:"
echo "========================="
echo "  Check if port is open:"
echo "    netstat -ulnp | grep 9443"
echo "    ss -ulnp | grep 9443"
echo ""
echo "  Test UDP connectivity:"
echo "    nc -u $SERVER_IP 9443"
echo "    nmap -sU -p 9443 $SERVER_IP"

echo ""
echo "⚙️  Advanced Configuration:"
echo "=========================="
echo "  Custom hostname for certificate:"
echo "    ./gradlew run --args=\"server 9443\" -Dquic.hostname=your-hostname.local"
echo ""
echo "  Custom bind address (if needed):"
echo "    # Modify FileTransferMain.java bind() call"