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
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.concurrent.DefaultThreadFactory;

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
        logger.info("Starting OPTIMIZED QUIC server on port {}", port);
        
        // High-performance event loop with single thread for server
        NioEventLoopGroup group = new NioEventLoopGroup(1, 
            new DefaultThreadFactory("quic-server", true));
        try {
            // SSL Context oluştur
            QuicSslContext sslContext = QuicSslContextBuilder.forServer(
                    SelfSignedCertificateGenerator.cert.key(), 
                    null, // password
                    SelfSignedCertificateGenerator.cert.cert()
            )
            .applicationProtocols(APPLICATION_PROTOCOL)
            .build();

            // QUIC Server Codec oluştur - EXTREME OPTIMIZATION
            ChannelHandler codec = new QuicServerCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(120000, java.util.concurrent.TimeUnit.MILLISECONDS) // 120s timeout for large files
                    .initialMaxData(2_000_000_000L) // 2GB total window - EXTREME
                    .initialMaxStreamDataBidirectionalLocal(1_000_000_000L) // 1GB per stream
                    .initialMaxStreamDataBidirectionalRemote(1_000_000_000L) // 1GB per stream
                    .initialMaxStreamsBidirectional(1) // Single stream optimization
                    .initialMaxStreamsUnidirectional(0) // Not needed
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
              .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
              .option(ChannelOption.SO_SNDBUF, 8 * 1024 * 1024) // 8MB OS send buffer
              .option(ChannelOption.SO_RCVBUF, 8 * 1024 * 1024) // 8MB OS receive buffer
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
        
        // High-performance event loop with single thread for client
        NioEventLoopGroup group = new NioEventLoopGroup(1, 
            new DefaultThreadFactory("quic-client", true));
        try {
            // SSL Context oluştur (client için)
            QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                    .trustManager(io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE) // Test için
                    .applicationProtocols(APPLICATION_PROTOCOL)
                    .build();

            Bootstrap bs = new Bootstrap();
            ChannelHandler clientCodec = new QuicClientCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(120000, java.util.concurrent.TimeUnit.MILLISECONDS) // 120s timeout for large files
                    .initialMaxData(2_000_000_000L) // 2GB total window - EXTREME
                    .initialMaxStreamDataBidirectionalLocal(1_000_000_000L) // 1GB per stream
                    .initialMaxStreamDataBidirectionalRemote(1_000_000_000L) // 1GB per stream
                    .build();
            
            bs.group(group)
              .channel(NioDatagramChannel.class)
              .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
              .option(ChannelOption.SO_SNDBUF, 8 * 1024 * 1024) // 8MB OS send buffer
              .option(ChannelOption.SO_RCVBUF, 8 * 1024 * 1024) // 8MB OS receive buffer
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
     * Client tarafında dosya gönderme handler'ı - ASYNC OPTIMIZED
     */
    static class FileStreamHandler extends ChannelInboundHandlerAdapter {
        private static final Logger logger = LoggerFactory.getLogger(FileStreamHandler.class);
        private final File file;
        private AsynchronousFileChannel fileChannel;
        private final AtomicLong position = new AtomicLong(0);
        private final AtomicLong totalSent = new AtomicLong(0);
        private final AtomicInteger activeReads = new AtomicInteger(0);
        private long startTime;
        private static final int CHUNK_SIZE = 2 * 1024 * 1024; // 2MB chunks - EXTREME
        private static final int CONCURRENT_READS = 4; // 4 concurrent async reads
        private volatile boolean fileCompleted = false;
        private volatile ChannelHandlerContext channelContext;
        
        public FileStreamHandler(File file) {
            this.file = file;
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            channelContext = ctx;
            logger.info("ASYNC File send stream activated for: {} ({})", 
                    file.getName(), String.format("%.2f MB", file.length() / (1024.0 * 1024.0)));
            startTime = System.currentTimeMillis();
            
            try {
                // Async file channel aç - EXTREME PERFORMANCE
                Path filePath = file.toPath();
                fileChannel = AsynchronousFileChannel.open(filePath, StandardOpenOption.READ);
                
                // CONCURRENT async reads başlat - 4 pipeline
                for (int i = 0; i < CONCURRENT_READS; i++) {
                    initiateAsyncRead(ctx);
                }
            } catch (IOException e) {
                logger.error("Failed to open async file channel", e);
                ctx.close();
            }
            
            super.channelActive(ctx);
        }
        
        /**
         * ASYNC dosya okuma işlemini başlat - EXTREME PERFORMANCE dengan 4 concurrent reads
         */
        private void initiateAsyncRead(ChannelHandlerContext ctx) {
            if (fileCompleted) return;
            
            long currentPos = position.getAndAdd(CHUNK_SIZE);
            if (currentPos >= file.length()) {
                return; // EOF reached
            }
            
            activeReads.incrementAndGet();
            ByteBuffer buffer = ByteBuffer.allocateDirect(CHUNK_SIZE);
            
            fileChannel.read(buffer, currentPos, ctx, new CompletionHandler<Integer, ChannelHandlerContext>() {
                @Override
                public void completed(Integer bytesRead, ChannelHandlerContext attachment) {
                    try {
                        if (bytesRead > 0) {
                            buffer.flip();
                            
                            // Direct ByteBuf kullan - zero copy optimization
                            ByteBuf byteBuf = attachment.alloc().directBuffer(bytesRead);
                            byteBuf.writeBytes(buffer);
                            
                            totalSent.addAndGet(bytesRead);
                            
                            logger.debug("ASYNC sending {} bytes, total: {} bytes", 
                                    bytesRead, totalSent.get());
                            
                            // Immediate write and flush for max throughput
                            attachment.writeAndFlush(byteBuf).addListener(future -> {
                                if (future.isSuccess()) {
                                    // Pipeline next read if file not finished
                                    if (currentPos + CHUNK_SIZE < file.length()) {
                                        initiateAsyncRead(attachment);
                                    } else {
                                        checkForCompletion(attachment);
                                    }
                                } else {
                                    logger.error("ASYNC Write failed", future.cause());
                                    attachment.close();
                                }
                            });
                        } else {
                            // EOF reached
                            checkForCompletion(attachment);
                        }
                    } finally {
                        activeReads.decrementAndGet();
                    }
                }

                @Override
                public void failed(Throwable exc, ChannelHandlerContext attachment) {
                    logger.error("ASYNC file read failed at position {}", currentPos, exc);
                    activeReads.decrementAndGet();
                    attachment.close();
                }
            });
        }
        
        /**
         * Transfer completion check
         */
        private void checkForCompletion(ChannelHandlerContext ctx) {
            if (position.get() >= file.length() && activeReads.get() == 0 && !fileCompleted) {
                fileCompleted = true;
                logger.info("ASYNC file transfer COMPLETED - {} bytes transferred", totalSent.get());
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            long duration = System.currentTimeMillis() - startTime;
            long totalBytes = totalSent.get();
            double throughputMbps = (totalBytes * 8.0 / 1_000_000) / (duration / 1000.0);
            
            logger.info("ASYNC File send completed:");
            logger.info("  File: {}", file.getName());
            logger.info("  Total bytes: {}", totalBytes);
            logger.info("  Duration: {} ms", duration);
            logger.info("  Throughput: {:.2f} Mbps", throughputMbps);
            
            // Async file channel'ı kapat
            if (fileChannel != null) {
                try {
                    fileChannel.close();
                } catch (IOException e) {
                    logger.warn("Error closing async file channel", e);
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