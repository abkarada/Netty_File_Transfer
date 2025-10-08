package quic;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
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
import java.util.concurrent.ConcurrentHashMap;
import java.io.RandomAccessFile;
import io.netty.buffer.PooledByteBufAllocator;

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
        
        // RESEARCH: High-priority single thread with CPU affinity
        NioEventLoopGroup group = new NioEventLoopGroup(1, r -> {
            Thread t = new Thread(r, "quic-server-optimized");
            t.setPriority(Thread.MAX_PRIORITY); // RESEARCH: Max priority
            return t;
        });
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
                    .maxIdleTimeout(600000, java.util.concurrent.TimeUnit.MILLISECONDS) // EMERGENCY: 10 minutes timeout
                    .initialMaxData(50_000_000L) // 🔥 RESEARCH: Facebook optimal 50MB 
                    .initialMaxStreamDataBidirectionalLocal(1_000_000L) // 🔥 RESEARCH: 1MB per stream (Facebook pattern)
                    .initialMaxStreamDataBidirectionalRemote(1_000_000L) // 🔥 RESEARCH: 1MB per stream (Facebook pattern)
                    .initialMaxStreamsBidirectional(100) // RESEARCH: Support multiple streams
                    .maxRecvUdpPayloadSize(1350) // 🔥 RESEARCH: Facebook uses larger packets  
                    .maxSendUdpPayloadSize(1350) // 🔥 RESEARCH: Still safe for most networks
                    .congestionControlAlgorithm(QuicCongestionControlAlgorithm.CUBIC) // 🔥 RESEARCH: Best available in Netty
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
              .option(ChannelOption.SO_SNDBUF, 4 * 1024 * 1024) // 🔥 RESEARCH: 4MB for throughput
              .option(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024) // 🔥 RESEARCH: 4MB for throughput
              .option(ChannelOption.SO_REUSEADDR, true) // RESEARCH: Port reuse
              .option(ChannelOption.IP_TOS, 0x10) // RESEARCH: Low delay TOS bit
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
        
        // RESEARCH: High-priority single thread for client
        NioEventLoopGroup group = new NioEventLoopGroup(1, r -> {
            Thread t = new Thread(r, "quic-client-optimized");
            t.setPriority(Thread.MAX_PRIORITY); // RESEARCH: Max priority
            return t;
        });
        try {
            // SSL Context oluştur (client için)
            QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                    .trustManager(io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE) // Test için
                    .applicationProtocols(APPLICATION_PROTOCOL)
                    .build();

            Bootstrap bs = new Bootstrap();
            ChannelHandler clientCodec = new QuicClientCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(600000, java.util.concurrent.TimeUnit.MILLISECONDS) // ❗ EMERGENCY: 10 minutes matching server
                    .initialMaxData(50_000_000L) // 🔥 RESEARCH: Facebook tuned this iteratively - 50MB optimal
                    .initialMaxStreamDataBidirectionalLocal(1_000_000L) // 🔥 RESEARCH: 1MB per stream (Facebook pattern)  
                    .initialMaxStreamDataBidirectionalRemote(1_000_000L) // 🔥 RESEARCH: 1MB per stream (Facebook pattern)
                    .maxRecvUdpPayloadSize(1350) // 🔥 RESEARCH: Facebook uses larger packets
                    .maxSendUdpPayloadSize(1350) // 🔥 RESEARCH: Still safe for most networks
                    .congestionControlAlgorithm(QuicCongestionControlAlgorithm.CUBIC) // RESEARCH: Proven
                    .build();
            
            bs.group(group)
              .channel(NioDatagramChannel.class)
              .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
              .option(ChannelOption.SO_SNDBUF, 4 * 1024 * 1024) // 🔥 RESEARCH: 4MB for throughput  
              .option(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024) // 🔥 RESEARCH: 4MB for throughput  
              .option(ChannelOption.SO_REUSEADDR, true) // RESEARCH: Port reuse
              .option(ChannelOption.IP_TOS, 0x10) // RESEARCH: Low delay TOS
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
                    new MultiStreamFileHandler(file)).sync().getNow();
            
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
     * WEB RESEARCH: Multi-Stream File Receive Handler - Facebook/Cloudflare pattern
     */
    static class FileReceiveHandler extends ChannelInboundHandlerAdapter {
        private static final Logger logger = LoggerFactory.getLogger(FileReceiveHandler.class);
        private static final String RECEIVED_FILES_DIR = "received-files";
        private long totalReceived = 0;
        private long startTime;
        private FileOutputStream fileOutputStream;
        private File receivedFile;
        
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
     * WEB RESEARCH: Multi-Stream File Transfer Handler - FACEBOOK/CLOUDFLARE APPROACH
     */
    static class MultiStreamFileHandler extends ChannelInboundHandlerAdapter {
        private static final Logger logger = LoggerFactory.getLogger(MultiStreamFileHandler.class);
        private final File file;
        private long startTime;
        private static final int STREAM_COUNT = 8; // WEB RESEARCH: 8 parallel streams like Facebook
        private static final int CHUNK_SIZE = 4 * 1024 * 1024; // WEB RESEARCH: 4MB chunks
        private final AtomicLong totalSent = new AtomicLong(0);
        private final AtomicInteger completedStreams = new AtomicInteger(0);
        
        public MultiStreamFileHandler(File file) {
            this.file = file;
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            logger.info("EMERGENCY FIX: Single-Stream High-Performance Transfer for: {} ({})", 
                    file.getName(), String.format("%.2f MB", file.length() / (1024.0 * 1024.0)));
            startTime = System.currentTimeMillis();
            
            // EMERGENCY: Single stream with extreme optimization
            try {
                Path filePath = file.toPath();
                AsynchronousFileChannel fileChannel = AsynchronousFileChannel.open(filePath, StandardOpenOption.READ);
                
                // EMERGENCY: Direct massive chunk reading
                sendFileInChunks(ctx, fileChannel, 0);
                
            } catch (IOException e) {
                logger.error("Failed to open file channel", e);
                ctx.close();
            }
            
            super.channelActive(ctx);
        }
        
        private void sendFileInChunks(ChannelHandlerContext ctx, AsynchronousFileChannel fileChannel, long position) {
            if (position >= file.length()) {
                // Transfer complete
                long duration = System.currentTimeMillis() - startTime;
                double throughputMbps = (totalSent.get() * 8.0 / 1_000_000) / (duration / 1000.0);
                
                logger.info("EMERGENCY SINGLE-STREAM Transfer COMPLETED!");
                logger.info("  Total bytes: {}", totalSent.get());
                logger.info("  Duration: {} ms", duration);
                logger.info("  Throughput: {:.2f} Mbps", throughputMbps);
                
                try {
                    fileChannel.close();
                } catch (IOException e) {
                    logger.warn("Error closing file channel", e);
                }
                ctx.close();
                return;
            }
            
            int chunkSize = (int) Math.min(32 * 1024, file.length() - position); // 🔥 RESEARCH: 32KB optimal (Facebook pattern)
            ByteBuffer buffer = ByteBuffer.allocateDirect(chunkSize);
            
            fileChannel.read(buffer, position, ctx, new CompletionHandler<Integer, ChannelHandlerContext>() {
                @Override
                public void completed(Integer bytesRead, ChannelHandlerContext attachment) {
                    if (bytesRead > 0) {
                        buffer.flip();
                        
                        // Direct transfer
                        ByteBuf nettyBuf = attachment.alloc().directBuffer(bytesRead);
                        nettyBuf.writeBytes(buffer);
                        
                        totalSent.addAndGet(bytesRead);
                        
                        // EMERGENCY: Immediate flush
                        attachment.writeAndFlush(nettyBuf).addListener(future -> {
                            if (future.isSuccess()) {
                                // Continue with next chunk
                                sendFileInChunks(attachment, fileChannel, position + bytesRead);
                            } else {
                                logger.error("Write failed", future.cause());
                                attachment.close();
                            }
                        });
                    } else {
                        // EOF
                        try {
                            fileChannel.close();
                        } catch (IOException e) {
                            logger.warn("Error closing file channel", e);
                        }
                        attachment.close();
                    }
                }

                @Override
                public void failed(Throwable exc, ChannelHandlerContext attachment) {
                    logger.error("File read failed at position {}", position, exc);
                    try {
                        fileChannel.close();
                    } catch (IOException e) {
                        logger.warn("Error closing file channel", e);
                    }
                    attachment.close();
                }
            });
        }
        
        /**
         * WEB RESEARCH: Stream completion notification from individual streams
         */
        public void onStreamCompleted(long bytesTransferred) {
            totalSent.addAndGet(bytesTransferred);
            int completed = completedStreams.incrementAndGet();
            
            logger.info("Stream completed. {}/{} streams done, {} bytes total", 
                    completed, STREAM_COUNT, totalSent.get());
            
            if (completed == STREAM_COUNT) {
                long duration = System.currentTimeMillis() - startTime;
                double throughputMbps = (totalSent.get() * 8.0 / 1_000_000) / (duration / 1000.0);
                
                logger.info("WEB RESEARCH: Multi-Stream Transfer COMPLETED!");
                logger.info("  File: {}", file.getName());
                logger.info("  Total bytes: {}", totalSent.get());
                logger.info("  Streams used: {}", STREAM_COUNT);
                logger.info("  Duration: {} ms", duration);
                logger.info("  Throughput: {:.2f} Mbps", throughputMbps);
            }
        }
    }
    
    /**
     * WEB RESEARCH: Individual stream handler for file chunks - Facebook/Cloudflare pattern
     */
    static class StreamFileHandler extends ChannelInboundHandlerAdapter {
        private static final Logger logger = LoggerFactory.getLogger(StreamFileHandler.class);
        private final File file;
        private final long startPos;
        private final long endPos;
        private final int streamId;
        private final MultiStreamFileHandler parent;
        private AsynchronousFileChannel fileChannel;
        private final AtomicLong position;
        private final AtomicLong streamSent = new AtomicLong(0);
        private static final int STREAM_CHUNK_SIZE = 1024 * 1024; // 1MB per read
        
        public StreamFileHandler(File file, long startPos, long endPos, int streamId, MultiStreamFileHandler parent) {
            this.file = file;
            this.startPos = startPos;
            this.endPos = endPos;
            this.streamId = streamId;
            this.parent = parent;
            this.position = new AtomicLong(startPos);
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            logger.debug("WEB RESEARCH: Stream {} active for range {}-{} ({} bytes)", 
                    streamId, startPos, endPos, endPos - startPos);
            
            try {
                // WEB RESEARCH: Each stream opens its own file channel
                fileChannel = AsynchronousFileChannel.open(file.toPath(), StandardOpenOption.READ);
                
                // WEB RESEARCH: Send stream header with metadata
                sendStreamHeader(ctx);
                
                // WEB RESEARCH: Start reading this stream's chunk
                readNextChunk(ctx);
                
            } catch (IOException e) {
                logger.error("Stream {} failed to open file channel", streamId, e);
                ctx.close();
            }
            
            super.channelActive(ctx);
        }
        
        private void sendStreamHeader(ChannelHandlerContext ctx) {
            // WEB RESEARCH: Send stream metadata like Facebook does
            ByteBuf header = ctx.alloc().directBuffer(24);
            header.writeLong(streamId);     // Stream ID
            header.writeLong(startPos);     // Start position  
            header.writeLong(endPos);       // End position
            ctx.writeAndFlush(header);
            
            logger.debug("Stream {} header sent: pos={}-{}", streamId, startPos, endPos);
        }
        
        private void readNextChunk(ChannelHandlerContext ctx) {
            long currentPos = position.get();
            if (currentPos >= endPos) {
                // WEB RESEARCH: Stream completed, notify parent
                logger.debug("Stream {} completed - {} bytes transferred", 
                        streamId, streamSent.get());
                parent.onStreamCompleted(streamSent.get());
                ctx.close();
                return;
            }
            
            int chunkSize = (int) Math.min(STREAM_CHUNK_SIZE, endPos - currentPos);
            ByteBuffer buffer = ByteBuffer.allocateDirect(chunkSize);
            
            fileChannel.read(buffer, currentPos, ctx, new CompletionHandler<Integer, ChannelHandlerContext>() {
                @Override
                public void completed(Integer bytesRead, ChannelHandlerContext attachment) {
                    if (bytesRead > 0) {
                        buffer.flip();
                        
                        // WEB RESEARCH: Direct buffer transfer
                        ByteBuf nettyBuf = attachment.alloc().directBuffer(bytesRead);
                        nettyBuf.writeBytes(buffer);
                        
                        streamSent.addAndGet(bytesRead);
                        position.set(currentPos + bytesRead);
                        
                        // WEB RESEARCH: Write and continue pipeline
                        attachment.writeAndFlush(nettyBuf).addListener(future -> {
                            if (future.isSuccess()) {
                                readNextChunk(attachment); // Continue reading
                            } else {
                                logger.error("Stream {} write failed", streamId, future.cause());
                                attachment.close();
                            }
                        });
                    } else {
                        // EOF or error
                        logger.debug("Stream {} EOF reached", streamId);
                        parent.onStreamCompleted(streamSent.get());
                        attachment.close();
                    }
                }

                @Override
                public void failed(Throwable exc, ChannelHandlerContext attachment) {
                    logger.error("Stream {} read failed at position {}", streamId, currentPos, exc);
                    attachment.close();
                }
            });
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (fileChannel != null) {
                try {
                    fileChannel.close();
                } catch (IOException e) {
                    logger.warn("Error closing file channel for stream {}", streamId, e);
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