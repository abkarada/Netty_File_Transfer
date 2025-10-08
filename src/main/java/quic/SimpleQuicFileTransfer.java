package quic;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 🔥 BASIT VE ÇALIŞAN QUIC FILE TRANSFER 
 * Netty'nin resmi örneğinden adapte edilmiş
 */
public class SimpleQuicFileTransfer {
    private static final Logger logger = LoggerFactory.getLogger(SimpleQuicFileTransfer.class);
    private static final int CHUNK_SIZE = 64 * 1024; // 64KB chunks
    
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage:");
            System.err.println("  Server: java SimpleQuicFileTransfer server [port]");
            System.err.println("  Client: java SimpleQuicFileTransfer client <host> [port] [file]");
            return;
        }
        
        if ("server".equals(args[0])) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 9999;
            runServer(port);
        } else if ("client".equals(args[0])) {
            if (args.length < 2) {
                System.err.println("❌ Client needs host IP! Usage: client <host> [port] [file]");
                return;
            }
            String host = args[1];
            int port = args.length > 2 ? Integer.parseInt(args[2]) : 9999;
            String file = args.length > 3 ? args[3] : "test-files/test_10mb.bin";
            runClient(host, port, file);
        }
    }
    
    // 🚀 SERVER - Basit ve direkt Netty örneğinden
    public static void runServer(int port) throws Exception {
        logger.info("🔥 Starting QUIC Server on port {}", port);
        
        SelfSignedCertificate cert = new SelfSignedCertificate();
        QuicSslContext context = QuicSslContextBuilder.forServer(
                cert.privateKey(), null, cert.certificate())
                .applicationProtocols("file-transfer").build();
                
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        
        try {
            ChannelHandler codec = new QuicServerCodecBuilder()
                    .sslContext(context)
                    .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                    .initialMaxData(50000000) // 50MB
                    .initialMaxStreamDataBidirectionalLocal(10000000)
                    .initialMaxStreamDataBidirectionalRemote(10000000)
                    .initialMaxStreamsBidirectional(100)
                    .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                    .handler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            logger.info("✅ QUIC Connection established");
                        }
                    })
                    .streamHandler(new FileReceiveHandler())
                    .build();
                    
            Bootstrap bs = new Bootstrap();
            Channel channel = bs.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(codec)
                    .bind(new InetSocketAddress(port))
                    .sync().channel();
                    
            logger.info("🚀 Server started on port {}", port);
            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
    
    // 📡 CLIENT - Netty örneğinden adapte
    public static void runClient(String host, int port, String filePath) throws Exception {
        logger.info("🔥 Starting QUIC Client to {}:{}", host, port);
        
        QuicSslContext context = QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols("file-transfer").build();
                
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        
        try {
            ChannelHandler codec = new QuicClientCodecBuilder()
                    .sslContext(context)
                    .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                    .initialMaxData(50000000)
                    .initialMaxStreamDataBidirectionalLocal(10000000)
                    .build();
                    
            Bootstrap bs = new Bootstrap();
            Channel channel = bs.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(codec)
                    .bind(0).sync().channel();
                    
            QuicChannel quicChannel = QuicChannel.newBootstrap(channel)
                    .streamHandler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            ctx.close(); // Sadece client-initiated streams
                        }
                    })
                    .remoteAddress(new InetSocketAddress(host, port))
                    .connect()
                    .get();
                    
            logger.info("✅ Connected to server, starting file transfer: {}", filePath);
            
            // 🔥 FILE TRANSFER STREAM
            QuicStreamChannel streamChannel = quicChannel.createStream(
                    QuicStreamType.BIDIRECTIONAL, 
                    new FileSendHandler(filePath)
            ).sync().getNow();
            
            // Bekle transfer bitsin
            streamChannel.closeFuture().sync();
            quicChannel.closeFuture().sync();
            channel.close().sync();
            
        } finally {
            group.shutdownGracefully();
        }
    }
    
    // 📤 FILE SENDER - Client stream handler
    static class FileSendHandler extends ChannelInboundHandlerAdapter {
        private final String filePath;
        private final AtomicLong bytesSent = new AtomicLong(0);
        private final long startTime = System.nanoTime();
        private FileInputStream fileStream;
        private long fileSize;
        
        public FileSendHandler(String filePath) {
            this.filePath = filePath;
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            logger.info("📤 Stream active, starting file transfer");
            
            // Dosya bilgilerini al
            File file = new File(filePath);
            if (!file.exists()) {
                logger.error("❌ File not found: {}", filePath);
                ctx.close();
                return;
            }
            
            fileSize = file.length();
            fileStream = new FileInputStream(file);
            
            logger.info("📄 File: {} ({} MB)", filePath, fileSize / (1024 * 1024));
            
            // İlk chunk'ı gönder
            sendNextChunk(ctx);
        }
        
        private void sendNextChunk(ChannelHandlerContext ctx) throws IOException {
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead = fileStream.read(buffer);
            
            if (bytesRead > 0) {
                ByteBuf chunk = ctx.alloc().buffer(bytesRead);
                chunk.writeBytes(buffer, 0, bytesRead);
                
                long sent = bytesSent.addAndGet(bytesRead);
                
                // Write and flush
                ctx.writeAndFlush(chunk).addListener(future -> {
                    if (future.isSuccess()) {
                        try {
                            // Progress göster
                            if (sent % (1024 * 1024) == 0 || sent == fileSize) {
                                double progress = (double) sent / fileSize * 100;
                                double speed = sent / ((System.nanoTime() - startTime) / 1e9) / (1024 * 1024);
                                logger.info("📊 Progress: {:.1f}% ({} MB / {} MB) - Speed: {:.2f} MB/s", 
                                    progress, sent / (1024 * 1024), fileSize / (1024 * 1024), speed);
                            }
                            
                            // Bir sonraki chunk'ı gönder
                            sendNextChunk(ctx);
                        } catch (IOException e) {
                            logger.error("❌ Error reading file", e);
                            ctx.close();
                        }
                    } else {
                        logger.error("❌ Write failed", future.cause());
                        ctx.close();
                    }
                });
            } else {
                // Dosya bitti - FIN gönder
                logger.info("✅ File transfer completed!");
                
                double totalTime = (System.nanoTime() - startTime) / 1e9;
                double avgSpeed = fileSize / totalTime / (1024 * 1024);
                logger.info("📈 Total: {} MB in {:.2f}s - Average speed: {:.2f} MB/s", 
                    fileSize / (1024 * 1024), totalTime, avgSpeed);
                
                // PROPER SHUTDOWN - FIN bit gönder
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER)
                   .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
                
                fileStream.close();
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            logger.info("📤 Stream closed");
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("❌ Exception in sender", cause);
            ctx.close();
        }
    }
    
    // 📥 FILE RECEIVER - Server stream handler
    static class FileReceiveHandler extends ChannelInitializer<QuicStreamChannel> {
        @Override
        protected void initChannel(QuicStreamChannel ch) {
            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                private FileOutputStream fileStream;
                private final AtomicLong bytesReceived = new AtomicLong(0);
                private final long startTime = System.nanoTime();
                private String fileName;
                
                @Override
                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                    // Benzersiz dosya adı
                    fileName = "received-files/received_file_" + System.currentTimeMillis() + ".dat";
                    Files.createDirectories(Paths.get("received-files"));
                    fileStream = new FileOutputStream(fileName);
                    
                    logger.info("📥 Stream active, receiving file: {}", fileName);
                }
                
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    ByteBuf data = (ByteBuf) msg;
                    try {
                        int bytes = data.readableBytes();
                        if (bytes > 0) {
                            // Dosyaya yaz
                            data.readBytes(fileStream, bytes);
                            
                            long received = bytesReceived.addAndGet(bytes);
                            
                            // Progress
                            if (received % (1024 * 1024) == 0) {
                                double speed = received / ((System.nanoTime() - startTime) / 1e9) / (1024 * 1024);
                                logger.info("📊 Received: {} MB - Speed: {:.2f} MB/s", 
                                    received / (1024 * 1024), speed);
                            }
                        }
                    } finally {
                        data.release();
                    }
                }
                
                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    try {
                        if (fileStream != null) {
                            fileStream.close();
                        }
                        
                        double totalTime = (System.nanoTime() - startTime) / 1e9;
                        double avgSpeed = bytesReceived.get() / totalTime / (1024 * 1024);
                        
                        logger.info("✅ File received successfully!");
                        logger.info("📈 Total: {} MB in {:.2f}s - Average speed: {:.2f} MB/s", 
                            bytesReceived.get() / (1024 * 1024), totalTime, avgSpeed);
                        logger.info("📁 Saved as: {}", fileName);
                    } catch (IOException e) {
                        logger.error("❌ Error closing file", e);
                    }
                }
                
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    logger.error("❌ Exception in receiver", cause);
                    ctx.close();
                }
            });
        }
    }
}