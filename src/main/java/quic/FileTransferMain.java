package quic;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.quic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.net.InetSocketAddress;

/**
 * QUIC tabanlı dosya transfer uygulaması
 * Usage: 
 *   Server: java -jar app.jar server <port>
 *   Client: java -jar app.jar client <host> <port> <filename>
 */
public class FileTransferMain {
    private static final Logger logger = LoggerFactory.getLogger(FileTransferMain.class);
    private static final String APPLICATION_PROTOCOL = "quic-file-transfer";
    
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("  Server: java -jar app.jar server <port>");
            System.out.println("  Client: java -jar app.jar client <host> <port> <filename>");
            return;
        }
        
        try {
            if ("server".equals(args[0])) {
                if (args.length < 2) {
                    System.out.println("Server usage: java -jar app.jar server <port>");
                    return;
                }
                int port = Integer.parseInt(args[1]);
                runServer(port);
            } else if ("client".equals(args[0])) {
                if (args.length < 4) {
                    System.out.println("Client usage: java -jar app.jar client <host> <port> <filename>");
                    return;
                }
                String host = args[1];
                int port = Integer.parseInt(args[2]);
                String filename = args[3];
                runClient(host, port, filename);
            } else {
                System.out.println("First argument must be 'server' or 'client'");
            }
        } catch (Exception e) {
            logger.error("Application error", e);
            throw e;
        }
    }

    static void runServer(int port) throws Exception {
        logger.info("Starting QUIC server on port {}", port);
        
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            // SSL Context oluştur
            QuicSslContext sslContext = QuicSslContextBuilder.forServer(
                    SelfSignedCertificateGenerator.cert.key(), 
                    null, // password
                    SelfSignedCertificateGenerator.cert.cert()
            )
            .applicationProtocols(APPLICATION_PROTOCOL)
            .build();

            // QUIC Server Codec oluştur
            ChannelHandler codec = new QuicServerCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(30000, java.util.concurrent.TimeUnit.MILLISECONDS) // 30s timeout
                    .initialMaxData(100_000_000) // 100MB window - 10x artış
                    .initialMaxStreamDataBidirectionalLocal(50_000_000) // 50MB per stream
                    .initialMaxStreamDataBidirectionalRemote(50_000_000) // 50MB per stream
                    .initialMaxStreamsBidirectional(100)
                    .initialMaxStreamsUnidirectional(100)
                    .tokenHandler(InsecureQuicTokenHandler.INSTANCE) // Test için
                    .handler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) throws Exception {
                            logger.info("QUIC connection established: {}", ctx.channel().remoteAddress());
                            super.channelActive(ctx);
                        }
                        
                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                            logger.info("QUIC connection closed: {}", ctx.channel().remoteAddress());
                            super.channelInactive(ctx);
                        }
                    })
                    .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel ch) throws Exception {
                            logger.debug("New QUIC stream created: {}", ch.streamId());
                            ch.pipeline().addLast(new FileReceiveHandler());
                        }
                    })
                    .build();

            Bootstrap bs = new Bootstrap();
            bs.group(group)
              .channel(NioDatagramChannel.class)
              .handler(codec);

            Channel ch = bs.bind(port).sync().channel();
            logger.info("QUIC server started successfully on port {}", port);
            System.out.println("Server listening on port " + port + " - Press Ctrl+C to stop");
            
            ch.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
            logger.info("Server stopped");
        }
    }

    static void runClient(String host, int port, String filename) throws Exception {
        logger.info("Starting QUIC client - connecting to {}:{}", host, port);
        logger.info("File to send: {}", filename);
        
        // Dosya kontrolü
        File file = new File(filename);
        if (!file.exists()) {
            logger.error("File not found: {}", filename);
            System.err.println("File not found: " + filename);
            return;
        }
        
        if (!file.canRead()) {
            logger.error("File is not readable: {}", filename);
            System.err.println("File is not readable: " + filename);
            return;
        }
        
        logger.info("File size: {} bytes", file.length());
        
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            // SSL Context oluştur (client için)
            QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                    .trustManager(io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE) // Test için
                    .applicationProtocols(APPLICATION_PROTOCOL)
                    .build();

            Bootstrap bs = new Bootstrap();
            ChannelHandler clientCodec = new QuicClientCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(30000, java.util.concurrent.TimeUnit.MILLISECONDS) // 30s timeout
                    .initialMaxData(100_000_000) // 100MB window - 10x artış
                    .initialMaxStreamDataBidirectionalLocal(50_000_000) // 50MB per stream
                    .initialMaxStreamDataBidirectionalRemote(50_000_000) // 50MB per stream
                    .build();
            
            bs.group(group)
              .channel(NioDatagramChannel.class)
              .handler(clientCodec);

            logger.debug("Connecting to server...");
            Channel ch = bs.connect(new InetSocketAddress(host, port)).sync().channel();
            
            // QUIC channel bootstrap ile bağlan
            QuicChannel quicChannel = QuicChannel.newBootstrap(ch)
                    .handler(new ChannelInboundHandlerAdapter())
                    .remoteAddress(new InetSocketAddress(host, port))
                    .connect()
                    .get();
            
            logger.info("QUIC connection established successfully");

            // Stream oluştur
            QuicStreamChannel stream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, 
                    new FileStreamHandler(file)).sync().getNow();
            
            logger.info("File transfer started...");
            
            // Stream kapatılmasını bekle
            stream.closeFuture().sync();
            logger.info("File transfer completed successfully");
            
            // Connection'ı kapat
            quicChannel.close().sync();
            
        } finally {
            group.shutdownGracefully();
            logger.info("Client stopped");
        }
    }
    
    /**
     * Server tarafında dosya alma handler'ı
     */
    static class FileReceiveHandler extends ChannelInboundHandlerAdapter {
        private static final Logger logger = LoggerFactory.getLogger(FileReceiveHandler.class);
        private long totalReceived = 0;
        private long startTime;
        private FileOutputStream fileOutputStream;
        private File receivedFile;
        private static final String RECEIVED_FILES_DIR = "received-files";
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            logger.info("File receive stream activated: {}", ctx.channel());
            startTime = System.currentTimeMillis();
            
            // Received files klasörünü oluştur
            File dir = new File(RECEIVED_FILES_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
                logger.info("Created directory: {}", RECEIVED_FILES_DIR);
            }
            
            // Timestamp ile unique dosya adı oluştur
            String timestamp = String.valueOf(System.currentTimeMillis());
            receivedFile = new File(dir, "received_file_" + timestamp + ".dat");
            fileOutputStream = new FileOutputStream(receivedFile);
            
            logger.info("Ready to receive file: {}", receivedFile.getAbsolutePath());
            super.channelActive(ctx);
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                int readableBytes = buf.readableBytes();
                totalReceived += readableBytes;
                
                logger.debug("Received {} bytes, total: {} bytes", readableBytes, totalReceived);
                
                // Dosyayı diske yaz
                try {
                    byte[] data = new byte[readableBytes];
                    buf.readBytes(data);
                    fileOutputStream.write(data);
                    // Flush'ı azaltalım - performans için
                } catch (IOException e) {
                    logger.error("Error writing to file", e);
                    ctx.close();
                }
                
                buf.release();
            }
            // super.channelRead'i çağırmayalım çünkü zaten buffer'ı işledik
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // Dosya stream'ini kapat
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close();
                } catch (IOException e) {
                    logger.warn("Error closing file output stream", e);
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            double throughputMbps = (totalReceived * 8.0 / 1_000_000) / (duration / 1000.0);
            
            logger.info("File receive completed:");
            logger.info("  File saved: {}", receivedFile != null ? receivedFile.getAbsolutePath() : "unknown");
            logger.info("  Total bytes: {}", totalReceived);
            logger.info("  Duration: {} ms", duration);
            logger.info("  Throughput: {:.2f} Mbps", throughputMbps);
            
            super.channelInactive(ctx);
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.error("Error in file receive handler", cause);
            ctx.close();
        }
    }
    
    /**
     * Client tarafında dosya gönderme handler'ı
     */
    static class FileStreamHandler extends ChannelInboundHandlerAdapter {
        private static final Logger logger = LoggerFactory.getLogger(FileStreamHandler.class);
        private final File file;
        private FileInputStream fis;
        private long totalSent = 0;
        private long startTime;
        private int chunksWritten = 0;
        
        public FileStreamHandler(File file) {
            this.file = file;
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            logger.info("File send stream activated, starting file transfer...");
            startTime = System.currentTimeMillis();
            
            try {
                fis = new FileInputStream(file);
                sendNextChunk(ctx);
            } catch (IOException e) {
                logger.error("Failed to open file for reading", e);
                ctx.close();
            }
            
            super.channelActive(ctx);
        }
        
        private void sendNextChunk(ChannelHandlerContext ctx) {
            try {
                // 1MB chunks - EXTREME size for maximum throughput
                byte[] buffer = new byte[1024 * 1024];
                int bytesRead = fis.read(buffer);
                
                if (bytesRead == -1) {
                    // Dosya sonu - stream'i kapat
                    logger.debug("End of file reached, closing stream");
                    fis.close();
                    ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                } else {
                    // Direct buffer kullan - zero copy optimization
                    ByteBuf buf = ctx.alloc().directBuffer(bytesRead);
                    buf.writeBytes(buffer, 0, bytesRead);
                    totalSent += bytesRead;
                    
                    logger.debug("Sending {} bytes, total sent: {} bytes", bytesRead, totalSent);
                    
                    chunksWritten++;
                    
                    // Flush every 4 chunks or on last chunk (optimize batching)
                    if (chunksWritten % 4 == 0) {
                        ctx.writeAndFlush(buf).addListener(future -> {
                            if (future.isSuccess()) {
                                sendNextChunk(ctx);
                            } else {
                                logger.error("Failed to send data chunk", future.cause());
                                ctx.close();
                            }
                        });
                    } else {
                        ctx.write(buf).addListener(future -> {
                            if (future.isSuccess()) {
                                sendNextChunk(ctx);
                            } else {
                                logger.error("Failed to send data chunk", future.cause());
                                ctx.close();
                            }
                        });
                    }
                }
            } catch (IOException e) {
                logger.error("Error reading file", e);
                ctx.close();
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            long duration = System.currentTimeMillis() - startTime;
            double throughputMbps = (totalSent * 8.0 / 1_000_000) / (duration / 1000.0);
            
            logger.info("File send completed:");
            logger.info("  File: {}", file.getName());
            logger.info("  Total bytes: {}", totalSent);
            logger.info("  Duration: {} ms", duration);
            logger.info("  Throughput: {:.2f} Mbps", throughputMbps);
            
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    logger.warn("Error closing file input stream", e);
                }
            }
            
            super.channelInactive(ctx);
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.error("Error in file send handler", cause);
            ctx.close();
        }
    }
}