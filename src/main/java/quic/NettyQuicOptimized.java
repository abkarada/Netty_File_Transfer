package quic;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 🔥 NETTY NATIVE TRANSPORT + MULTI-STREAM OPTIMIZED QUIC 
 * Based on Netty QUIC examples with performance optimizations from Facebook research
 */
public class NettyQuicOptimized {
    private static final Logger logger = LoggerFactory.getLogger(NettyQuicOptimized.class);
    
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java NettyQuicOptimized server [port]");
            System.out.println("       java NettyQuicOptimized client <host> <port> <file>");
            return;
        }

        if ("server".equals(args[0])) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 7000;
            startServer(port);
        } else if ("client".equals(args[0])) {
            if (args.length < 4) {
                System.out.println("Usage: java NettyQuicOptimized client <host> <port> <file>");
                return;
            }
            String host = args[1];
            int port = Integer.parseInt(args[2]);
            String fileName = args[3];
            startClient(host, port, fileName);
        }
    }

    private static void startServer(int port) throws Exception {
        // 🔥 NATIVE TRANSPORT: Use Epoll for Linux
        EventLoopGroup group = new EpollEventLoopGroup(1);
        
        try {
            SelfSignedCertificate cert = new SelfSignedCertificate();
            QuicSslContext sslContext = QuicSslContextBuilder.forServer(
                    cert.privateKey(), null, cert.certificate())
                    .applicationProtocols("quic-file-transfer")
                    .build();

            // 🚀 NETTY EXAMPLE PATTERN: Optimized configuration 
            ChannelHandler codec = new QuicServerCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(600000, TimeUnit.MILLISECONDS) // 10 minutes
                    .initialMaxData(100_000_000L) // 🔥 100MB total - prevent flow control stall
                    .initialMaxStreamDataBidirectionalLocal(10_000_000L) // 🔥 10MB per stream
                    .initialMaxStreamDataBidirectionalRemote(10_000_000L) // 🔥 10MB per stream
                    .initialMaxStreamsBidirectional(1000) // 🔥 SUPPORT 1000 STREAMS
                    .maxRecvUdpPayloadSize(1350) // Larger packets
                    .maxSendUdpPayloadSize(1350) // Larger packets  
                    .congestionControlAlgorithm(QuicCongestionControlAlgorithm.CUBIC)
                    .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                    .handler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            logger.info("🔥 NATIVE QUIC Server connection active: {}", ctx.channel().remoteAddress());
                        }
                        
                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) {
                            logger.info("🔥 NATIVE QUIC Server connection inactive");
                        }
                    })
                    .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel ch) {
                            ch.pipeline().addLast(new FileReceiveHandler());
                        }
                    })
                    .build();

            Bootstrap bs = new Bootstrap();
            Channel channel = bs.group(group)
                    .channel(EpollDatagramChannel.class) // 🔥 NATIVE TRANSPORT
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .option(ChannelOption.SO_SNDBUF, 8 * 1024 * 1024) // 8MB send buffer
                    .option(ChannelOption.SO_RCVBUF, 8 * 1024 * 1024) // 8MB receive buffer
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .handler(codec)
                    .bind(port)
                    .sync()
                    .channel();

            logger.info("🔥 NATIVE QUIC Server started on port {}", port);
            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    private static void startClient(String host, int port, String fileName) throws Exception {
        File file = new File(fileName);
        if (!file.exists()) {
            logger.error("File not found: {}", fileName);
            return;
        }

        // 🔥 NATIVE TRANSPORT: Use Epoll for Linux  
        EventLoopGroup group = new EpollEventLoopGroup(1);
        
        try {
            QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols("quic-file-transfer")
                    .build();

            // 🚀 NETTY EXAMPLE PATTERN: Optimized client configuration
            ChannelHandler codec = new QuicClientCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(600000, TimeUnit.MILLISECONDS) // 10 minutes
                    .initialMaxData(100_000_000L) // 🔥 100MB total - prevent flow control stall
                    .initialMaxStreamDataBidirectionalLocal(10_000_000L) // 🔥 10MB per stream
                    .maxRecvUdpPayloadSize(1350) // Larger packets
                    .maxSendUdpPayloadSize(1350) // Larger packets
                    .congestionControlAlgorithm(QuicCongestionControlAlgorithm.CUBIC)
                    .build();

            Bootstrap bs = new Bootstrap();
            Channel channel = bs.group(group)
                    .channel(EpollDatagramChannel.class) // 🔥 NATIVE TRANSPORT
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .option(ChannelOption.SO_SNDBUF, 8 * 1024 * 1024) // 8MB send buffer
                    .option(ChannelOption.SO_RCVBUF, 8 * 1024 * 1024) // 8MB receive buffer
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .handler(codec)
                    .bind(0)
                    .sync()
                    .channel();

            // 🚀 MULTI-STREAM FILE TRANSFER
            QuicChannel quicChannel = QuicChannel.newBootstrap(channel)
                    .streamHandler(new ChannelInboundHandlerAdapter())
                    .remoteAddress(new InetSocketAddress(host, port))
                    .connect()
                    .get();

            logger.info("🔥 NATIVE QUIC Connected to {}:{}", host, port);
            
            // 🔥 MULTI-STREAM TRANSFER: Create multiple streams for parallel transfer
            new MultiStreamFileTransfer(quicChannel, file).start();
            
            quicChannel.closeFuture().sync();
            channel.close().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    // 🔥 PIPELINE MULTI-STREAM FILE TRANSFER IMPLEMENTATION
    static class MultiStreamFileTransfer {
        private final QuicChannel quicChannel;
        private final File file;
        private final int STREAM_COUNT = 4; // 🔥 4 STREAMS for pipeline
        private final int CHUNK_SIZE = 64 * 1024; // 🔥 64KB chunks for faster response
        private final AtomicLong totalSent = new AtomicLong(0);
        private final long startTime = System.currentTimeMillis();
        private final AtomicLong currentPosition = new AtomicLong(0);
        private final long fileSize;
        private volatile boolean transferComplete = false;
        
        MultiStreamFileTransfer(QuicChannel quicChannel, File file) {
            this.quicChannel = quicChannel;
            this.file = file;
            this.fileSize = file.length();
        }
        
        void start() throws Exception {            
            logger.info("🚀 Starting PIPELINE transfer: {} streams, {}KB chunks, total {}KB", 
                       STREAM_COUNT, CHUNK_SIZE / 1024, fileSize / 1024);
            
            // 🔥 CREATE PIPELINE STREAMS - Each stream requests next available chunk
            for (int i = 0; i < STREAM_COUNT; i++) {
                final int streamIndex = i;
                
                QuicStreamChannel stream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                        new ChannelInboundHandlerAdapter()).get();
                
                logger.info("✅ Pipeline stream {} created", streamIndex);
                
                // 🔥 START PIPELINE CHUNK TRANSFER
                sendNextChunk(stream, streamIndex);
            }
            
            // Wait until all file transferred
            while (!transferComplete && totalSent.get() < fileSize) {
                Thread.sleep(10);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            double throughputMbps = (totalSent.get() * 8.0 / 1_000_000) / (duration / 1000.0);
            
            logger.info("🔥 PIPELINE Transfer COMPLETED!");
            logger.info("  Total bytes: {}", totalSent.get());
            logger.info("  Duration: {} ms", duration);
            logger.info("  Throughput: {:.2f} Mbps", throughputMbps);
            
            quicChannel.close();
        }
        
        private void sendNextChunk(QuicStreamChannel stream, int streamIndex) {
            // 🔥 Get next sequential chunk position
            long chunkStart = currentPosition.getAndAdd(CHUNK_SIZE);
            
            if (chunkStart >= fileSize) {
                // No more chunks, close stream
                logger.debug("Stream {} finished - no more chunks", streamIndex);
                stream.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
                
                // Check if all done
                if (totalSent.get() >= fileSize) {
                    transferComplete = true;
                }
                return;
            }
            
            long chunkEnd = Math.min(chunkStart + CHUNK_SIZE, fileSize);
            int chunkSize = (int)(chunkEnd - chunkStart);
            
            logger.debug("Stream {} sending chunk: {}-{} ({}KB)", 
                streamIndex, chunkStart, chunkEnd, chunkSize / 1024);
            
            try {
                AsynchronousFileChannel fileChannel = AsynchronousFileChannel.open(
                        file.toPath(), StandardOpenOption.READ);
                
                sendChunkData(stream, fileChannel, chunkStart, chunkSize, streamIndex);
            } catch (IOException e) {
                logger.error("Error opening file for stream {}", streamIndex, e);
            }
        }
        
        private void sendChunkData(QuicStreamChannel stream, AsynchronousFileChannel fileChannel,
                                 long position, int size, int streamIndex) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(size);
            
            fileChannel.read(buffer, position, stream, new CompletionHandler<Integer, QuicStreamChannel>() {
                @Override
                public void completed(Integer bytesRead, QuicStreamChannel attachment) {
                    try {
                        fileChannel.close();
                    } catch (IOException e) {
                        logger.warn("Error closing file channel", e);
                    }
                    
                    if (bytesRead > 0) {
                        buffer.flip();
                        ByteBuf nettyBuf = attachment.alloc().directBuffer(bytesRead);
                        nettyBuf.writeBytes(buffer);
                        
                        attachment.writeAndFlush(nettyBuf).addListener(future -> {
                            if (future.isSuccess()) {
                                totalSent.addAndGet(bytesRead);
                                logger.debug("Stream {} sent {}KB, total: {}KB", 
                                    streamIndex, bytesRead / 1024, totalSent.get() / 1024);
                                
                                // 🔥 IMMEDIATELY REQUEST NEXT CHUNK
                                sendNextChunk(attachment, streamIndex);
                            } else {
                                logger.error("Write failed for stream {}", streamIndex, future.cause());
                            }
                        });
                    } else {
                        logger.debug("Stream {} read 0 bytes", streamIndex);
                        attachment.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
                    }
                }
                
                @Override
                public void failed(Throwable exc, QuicStreamChannel attachment) {
                    logger.error("File read failed for stream {}", streamIndex, exc);
                    try {
                        fileChannel.close();
                    } catch (IOException e) {
                        logger.warn("Error closing file channel", e);
                    }
                }
            });
        }
        

    }

    // 🔥 PIPELINE STREAM FILE RECEIVE HANDLER
    static class FileReceiveHandler extends ChannelInboundHandlerAdapter {
        private static volatile java.io.FileOutputStream sharedFileOut;
        private static volatile String sharedFileName;
        private static final AtomicLong totalReceived = new AtomicLong(0);
        private static volatile long sharedStartTime = System.currentTimeMillis();
        private static final AtomicLong activeStreams = new AtomicLong(0);
        private static final Object writeLock = new Object(); // 🔥 Synchronize writes
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            synchronized (FileReceiveHandler.class) {
                if (sharedFileOut == null) {
                    try {
                        sharedFileName = "received-files/received_" + System.currentTimeMillis() + ".dat";
                        new File("received-files").mkdirs();
                        sharedFileOut = new java.io.FileOutputStream(sharedFileName);
                        sharedStartTime = System.currentTimeMillis();
                        logger.info("🔥 MULTI-STREAM: First stream active, receiving to: {}", sharedFileName);
                    } catch (IOException e) {
                        logger.error("Error creating shared output file", e);
                        ctx.close();
                        return;
                    }
                }
            }
            
            long streamCount = activeStreams.incrementAndGet();
            logger.info("✅ Stream {} active (total active: {})", 
                ctx.channel().id().asShortText(), streamCount);
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf data = (ByteBuf) msg;
            try {
                // 🔥 FAST SYNCHRONIZED WRITE
                synchronized (writeLock) {
                    if (sharedFileOut != null) {
                        byte[] bytes = new byte[data.readableBytes()];
                        data.readBytes(bytes);
                        sharedFileOut.write(bytes);
                        sharedFileOut.flush(); // 🔥 Immediate flush for pipeline
                        totalReceived.addAndGet(bytes.length);
                        
                        if (bytes.length > 0) {
                            logger.debug("Stream {} wrote {}KB, total: {}KB", 
                                ctx.channel().id().asShortText(), 
                                bytes.length / 1024, totalReceived.get() / 1024);
                        }
                    }
                }
            } catch (IOException e) {
                logger.error("Error writing to shared file", e);
                ctx.close();
            } finally {
                data.release();
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            long remainingStreams = activeStreams.decrementAndGet();
            logger.info("❌ Stream {} inactive (remaining: {})", 
                ctx.channel().id().asShortText(), remainingStreams);
            
            // Close shared file when all streams are done
            if (remainingStreams == 0) {
                synchronized (FileReceiveHandler.class) {
                    if (sharedFileOut != null) {
                        try {
                            sharedFileOut.close();
                            long duration = System.currentTimeMillis() - sharedStartTime;
                            double throughputMbps = (totalReceived.get() * 8.0 / 1_000_000) / (duration / 1000.0);
                            logger.info("🔥 MULTI-STREAM TRANSFER COMPLETED!");
                            logger.info("  File: {}", sharedFileName);
                            logger.info("  Total bytes: {}", totalReceived.get());
                            logger.info("  Duration: {} ms", duration);
                            logger.info("  Throughput: {:.2f} Mbps", throughputMbps);
                            
                            // Reset for next transfer
                            sharedFileOut = null;
                            sharedFileName = null;
                            totalReceived.set(0);
                        } catch (IOException e) {
                            logger.error("Error closing shared file", e);
                        }
                    }
                }
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("Stream exception", cause);
            ctx.close();
        }
    }
}