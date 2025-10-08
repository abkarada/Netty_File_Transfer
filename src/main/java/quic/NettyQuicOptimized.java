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

    // 🔥 MULTI-STREAM FILE TRANSFER IMPLEMENTATION
    static class MultiStreamFileTransfer {
        private final QuicChannel quicChannel;
        private final File file;
        private final int STREAM_COUNT = 4; // 🔥 4 STREAMS - less contention
        private final int CHUNK_SIZE = 256 * 1024; // 🔥 256KB chunks - larger to reduce overhead
        private final AtomicLong totalSent = new AtomicLong(0);
        private final long startTime = System.currentTimeMillis();
        private final CountDownLatch completionLatch = new CountDownLatch(STREAM_COUNT);
        
        MultiStreamFileTransfer(QuicChannel quicChannel, File file) {
            this.quicChannel = quicChannel;
            this.file = file;
        }
        
        void start() throws Exception {
            long fileSize = file.length();
            long chunkPerStream = fileSize / STREAM_COUNT;
            
            logger.info("🚀 Starting MULTI-STREAM transfer: {} streams, {}KB per stream", 
                       STREAM_COUNT, chunkPerStream / 1024);
            
            // 🔥 CREATE ALL STREAMS FIRST, THEN START TRANSFER
            QuicStreamChannel[] streams = new QuicStreamChannel[STREAM_COUNT];
            
            for (int i = 0; i < STREAM_COUNT; i++) {
                streams[i] = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                        new ChannelInboundHandlerAdapter()).get();
            }
            
            // 🔥 NOW START TRANSFERS ON ALL STREAMS
            for (int i = 0; i < STREAM_COUNT; i++) {
                final int streamIndex = i;
                final long startPos = i * chunkPerStream;
                final long endPos = (i == STREAM_COUNT - 1) ? fileSize : (i + 1) * chunkPerStream;
                
                QuicStreamChannel stream = streams[i];
                stream.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(ChannelHandlerContext ctx) {
                        logger.debug("Stream {} active, transferring bytes {}-{}", 
                                   streamIndex, startPos, endPos);
                        transferChunk(ctx, startPos, endPos, streamIndex);
                    }
                });
            }
            
            // Wait for all streams to complete
            completionLatch.await();
            
            long duration = System.currentTimeMillis() - startTime;
            double throughputMbps = (totalSent.get() * 8.0 / 1_000_000) / (duration / 1000.0);
            
            logger.info("🔥 MULTI-STREAM Transfer COMPLETED!");
            logger.info("  Total bytes: {}", totalSent.get());
            logger.info("  Duration: {} ms", duration);
            logger.info("  Throughput: {:.2f} Mbps", throughputMbps);
            
            quicChannel.close();
        }
        
        private void transferChunk(ChannelHandlerContext ctx, long startPos, long endPos, int streamIndex) {
            try {
                AsynchronousFileChannel fileChannel = AsynchronousFileChannel.open(
                        file.toPath(), StandardOpenOption.READ);
                
                sendFileChunk(ctx, fileChannel, startPos, endPos, streamIndex);
            } catch (IOException e) {
                logger.error("Error opening file for stream {}", streamIndex, e);
                completionLatch.countDown();
            }
        }
        
        private void sendFileChunk(ChannelHandlerContext ctx, AsynchronousFileChannel fileChannel, 
                                 long currentPos, long endPos, int streamIndex) {
            if (currentPos >= endPos) {
                // Stream completed
                logger.debug("Stream {} completed", streamIndex);
                try {
                    fileChannel.close();
                } catch (IOException e) {
                    logger.warn("Error closing file channel for stream {}", streamIndex, e);
                }
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
                completionLatch.countDown();
                return;
            }
            
            int readSize = (int) Math.min(CHUNK_SIZE, endPos - currentPos);
            ByteBuffer buffer = ByteBuffer.allocateDirect(readSize);
            
            fileChannel.read(buffer, currentPos, ctx, new CompletionHandler<Integer, ChannelHandlerContext>() {
                @Override
                public void completed(Integer bytesRead, ChannelHandlerContext attachment) {
                    if (bytesRead > 0) {
                        buffer.flip();
                        ByteBuf nettyBuf = attachment.alloc().directBuffer(bytesRead);
                        nettyBuf.writeBytes(buffer);
                        
                        attachment.writeAndFlush(nettyBuf).addListener(future -> {
                            if (future.isSuccess()) {
                                totalSent.addAndGet(bytesRead);
                                // Continue with next chunk
                                sendFileChunk(attachment, fileChannel, currentPos + bytesRead, endPos, streamIndex);
                            } else {
                                logger.error("Write failed for stream {}", streamIndex, future.cause());
                                try {
                                    fileChannel.close();
                                } catch (IOException e) {
                                    logger.warn("Error closing file channel", e);
                                }
                                completionLatch.countDown();
                            }
                        });
                    } else {
                        // End of stream
                        try {
                            fileChannel.close();
                        } catch (IOException e) {
                            logger.warn("Error closing file channel", e);
                        }
                        attachment.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
                        completionLatch.countDown();
                    }
                }
                
                @Override
                public void failed(Throwable exc, ChannelHandlerContext attachment) {
                    logger.error("File read failed for stream {}", streamIndex, exc);
                    try {
                        fileChannel.close();
                    } catch (IOException e) {
                        logger.warn("Error closing file channel", e);
                    }
                    completionLatch.countDown();
                }
            });
        }
    }

    // File receive handler for server
    static class FileReceiveHandler extends ChannelInboundHandlerAdapter {
        private volatile java.io.FileOutputStream fileOut;
        private final AtomicLong totalReceived = new AtomicLong(0);
        private final long startTime = System.currentTimeMillis();
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            try {
                String fileName = "received-files/received_" + System.currentTimeMillis() + ".dat";
                new File("received-files").mkdirs();
                fileOut = new java.io.FileOutputStream(fileName);
                logger.info("Stream active, receiving to: {}", fileName);
            } catch (IOException e) {
                logger.error("Error creating output file", e);
                ctx.close();
            }
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf data = (ByteBuf) msg;
            try {
                if (fileOut != null) {
                    byte[] bytes = new byte[data.readableBytes()];
                    data.readBytes(bytes);
                    fileOut.write(bytes);
                    totalReceived.addAndGet(bytes.length);
                }
            } catch (IOException e) {
                logger.error("Error writing to file", e);
                ctx.close();
            } finally {
                data.release();
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (fileOut != null) {
                try {
                    fileOut.close();
                    long duration = System.currentTimeMillis() - startTime;
                    double throughputMbps = (totalReceived.get() * 8.0 / 1_000_000) / (duration / 1000.0);
                    logger.info("Stream completed: {} bytes in {} ms ({:.2f} Mbps)", 
                              totalReceived.get(), duration, throughputMbps);
                } catch (IOException e) {
                    logger.error("Error closing file", e);
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