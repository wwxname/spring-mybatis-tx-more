package com.plusme.lbs.service.zk;


import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class NettyServerCnxnFactory extends ServerCnxnFactory {
    private static final Logger LOG = LoggerFactory.getLogger(NettyServerCnxnFactory.class);

    private final ServerBootstrap bootstrap;
    private Channel parentChannel;
    private final ChannelGroup allChannels =
            new DefaultChannelGroup("zkServerCnxns", new DefaultEventExecutor());
    // Access to ipMap or to any Set contained in the map needs to be
    // protected with synchronized (ipMap) { ... }
    private final Map<InetAddress, Set<NettyServerCnxn>> ipMap = new HashMap<>();
    private InetSocketAddress localAddress;
    private int maxClientCnxns = 60;

    private static final AttributeKey<NettyServerCnxn> CONNECTION_ATTRIBUTE =
            AttributeKey.valueOf("NettyServerCnxn");

    private static final AtomicReference<ByteBufAllocator> TEST_ALLOCATOR =
            new AtomicReference<>(null);

    /**
     * This is an inner class since we need to extend ChannelDuplexHandler, but
     * NettyServerCnxnFactory already extends ServerCnxnFactory. By making it inner
     * this class gets access to the member variables and methods.
     */
    @Sharable
    class CnxnChannelHandler extends ChannelDuplexHandler {

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Channel active {}", ctx.channel());
            }

            NettyServerCnxn cnxn = new NettyServerCnxn(ctx.channel(),
                    zkServer, NettyServerCnxnFactory.this);
            ctx.channel().attr(CONNECTION_ATTRIBUTE).set(cnxn);


            allChannels.add(ctx.channel());
            addCnxn(cnxn);

        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Channel inactive {}", ctx.channel());
            }
            allChannels.remove(ctx.channel());
            NettyServerCnxn cnxn = ctx.channel().attr(CONNECTION_ATTRIBUTE).getAndSet(null);
            if (cnxn != null) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Channel inactive caused close {}", cnxn);
                }
                cnxn.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            LOG.warn("Exception caught", cause);
            NettyServerCnxn cnxn = ctx.channel().attr(CONNECTION_ATTRIBUTE).getAndSet(null);
            if (cnxn != null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Closing {}", cnxn);
                }
                cnxn.close();
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            try {
                if (evt == NettyServerCnxn.AutoReadEvent.ENABLE) {
                    LOG.debug("Received AutoReadEvent.ENABLE");
                    NettyServerCnxn cnxn = ctx.channel().attr(CONNECTION_ATTRIBUTE).get();
                    // TODO(ivmaykov): Not sure if cnxn can be null here. It becomes null if channelInactive()
                    // or exceptionCaught() trigger, but it's unclear to me if userEventTriggered() can run
                    // after either of those. Check for null just to be safe ...
                    if (cnxn != null) {
                        cnxn.processQueuedBuffer();
                    }
                    ctx.channel().config().setAutoRead(true);
                } else if (evt == NettyServerCnxn.AutoReadEvent.DISABLE) {
                    LOG.debug("Received AutoReadEvent.DISABLE");
                    ctx.channel().config().setAutoRead(false);
                }
            } finally {
                ReferenceCountUtil.release(evt);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("message received called {}", msg);
                }
                try {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("New message {} from {}", msg, ctx.channel());
                    }
                    NettyServerCnxn cnxn = ctx.channel().attr(CONNECTION_ATTRIBUTE).get();
                    if (cnxn == null) {
                        LOG.error("channelRead() on a closed or closing NettyServerCnxn");
                    } else {
                        cnxn.processMessage((ByteBuf) msg);
                    }
                } catch (Exception ex) {
                    LOG.error("Unexpected exception in receive", ex);
                    throw ex;
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        // Use a single listener instance to reduce GC
        // Note: this listener is only added when LOG.isTraceEnabled() is true,
        // so it should not do any work other than trace logging.
        private final GenericFutureListener<Future<Void>> onWriteCompletedTracer = (f) -> {
            LOG.trace("write {}", f.isSuccess() ? "complete" : "failed");
        };

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (LOG.isTraceEnabled()) {
                promise.addListener(onWriteCompletedTracer);
            }
            super.write(ctx, msg, promise);
        }

    }

    CnxnChannelHandler channelHandler = new CnxnChannelHandler();

    private ServerBootstrap configureBootstrapAllocator(ServerBootstrap bootstrap) {
        ByteBufAllocator testAllocator = TEST_ALLOCATOR.get();
        if (testAllocator != null) {
            return bootstrap
                    .option(ChannelOption.ALLOCATOR, testAllocator)
                    .childOption(ChannelOption.ALLOCATOR, testAllocator);
        } else {
            return bootstrap;
        }
    }

    NettyServerCnxnFactory() {


        EventLoopGroup bossGroup = NettyUtils.newNioOrEpollEventLoopGroup(
                NettyUtils.getClientReachableLocalInetAddressCount());
        EventLoopGroup workerGroup = NettyUtils.newNioOrEpollEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NettyUtils.nioOrEpollServerSocketChannel())
                // parent channel options
                .option(ChannelOption.SO_REUSEADDR, true)
                // child channels options
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_LINGER, -1)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast("servercnxnfactory", channelHandler);
                    }
                });
        this.bootstrap = configureBootstrapAllocator(bootstrap);
        this.bootstrap.validate();
    }


    @Override
    public void closeAll() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("closeAll()");
        }
        // clear all the connections on which we are selecting
        int length = cnxns.size();
        for (ServerCnxn cnxn : cnxns) {
            try {
                // This will remove the cnxn from cnxns
                cnxn.close();
            } catch (Exception e) {
                LOG.warn("Ignoring exception closing cnxn sessionid 0x"
                        + Long.toHexString(cnxn.getSessionId()), e);
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("allChannels size:" + allChannels.size() + " cnxns size:"
                    + length);
        }
    }

    @Override
    public boolean closeSession(long sessionId) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("closeSession sessionid:0x" + sessionId);
        }
        for (ServerCnxn cnxn : cnxns) {
            if (cnxn.getSessionId() == sessionId) {
                try {
                    cnxn.close();
                } catch (Exception e) {
                    LOG.warn("exception during session close", e);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void configure(InetSocketAddress addr, int maxClientCnxns, boolean secure)
            throws IOException {

        localAddress = addr;
        this.maxClientCnxns = maxClientCnxns;

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMaxClientCnxnsPerHost() {
        return maxClientCnxns;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMaxClientCnxnsPerHost(int max) {
        maxClientCnxns = max;
    }

    @Override
    public int getLocalPort() {
        return localAddress.getPort();
    }

    private boolean killed; // use synchronized(this) to access

    @Override
    public void join() throws InterruptedException {
        synchronized (this) {
            while (!killed) {
                wait();
            }
        }
    }

    @Override
    public void shutdown() {
        synchronized (this) {
            if (killed) {
                LOG.info("already shutdown {}", localAddress);
                return;
            }
        }
        LOG.info("shutdown called {}", localAddress);


        final EventLoopGroup bossGroup = bootstrap.config().group();
        final EventLoopGroup workerGroup = bootstrap.config().childGroup();
        // null if factory never started
        if (parentChannel != null) {
            ChannelFuture parentCloseFuture = parentChannel.close();
            if (bossGroup != null) {
                parentCloseFuture.addListener(future -> {
                    bossGroup.shutdownGracefully();
                });
            }
            closeAll();
            ChannelGroupFuture allChannelsCloseFuture = allChannels.close();
            if (workerGroup != null) {
                allChannelsCloseFuture.addListener(future -> {
                    workerGroup.shutdownGracefully();
                });
            }
        } else {
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
        }

        if (zkServer != null) {
            zkServer.shutdown();
        }
        synchronized (this) {
            killed = true;
            notifyAll();
        }
    }

    @Override
    public void start() {
        LOG.info("binding to port {}", localAddress);
        parentChannel = bootstrap.bind(localAddress).syncUninterruptibly().channel();
        // Port changes after bind() if the original port was 0, update
        // localAddress to get the real port.
        localAddress = (InetSocketAddress) parentChannel.localAddress();
        LOG.info("bound to port " + getLocalPort());
    }

    @Override
    public void reconfigure(InetSocketAddress addr) {
        Channel oldChannel = parentChannel;
        try {
            LOG.info("binding to port {}", addr);
            parentChannel = bootstrap.bind(addr).syncUninterruptibly().channel();
            // Port changes after bind() if the original port was 0, update
            // localAddress to get the real port.
            localAddress = (InetSocketAddress) parentChannel.localAddress();
            LOG.info("bound to port " + getLocalPort());
        } catch (Exception e) {
            LOG.error("Error while reconfiguring", e);
        } finally {
            oldChannel.close();
        }
    }

    @Override
    public void startup(ZooKeeperServer zks, boolean startServer)
            throws IOException, InterruptedException {
        start();
        setZooKeeperServer(zks);
        if (startServer) {

        }
    }

    @Override
    public Iterable<ServerCnxn> getConnections() {
        return cnxns;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    private void addCnxn(NettyServerCnxn cnxn) {
        cnxns.add(cnxn);
        synchronized (ipMap) {
            InetAddress addr =
                    ((InetSocketAddress) cnxn.getChannel().remoteAddress()).getAddress();
            Set<NettyServerCnxn> s = ipMap.get(addr);
            if (s == null) {
                s = new HashSet<>();
                ipMap.put(addr, s);
            }
            s.add(cnxn);
        }
    }

    void removeCnxnFromIpMap(NettyServerCnxn cnxn, InetAddress remoteAddress) {
        synchronized (ipMap) {
            Set<NettyServerCnxn> s = ipMap.get(remoteAddress);
            if (s != null) {
                s.remove(cnxn);
                if (s.isEmpty()) {
                    ipMap.remove(remoteAddress);
                }
                return;
            }
        }
        // Fallthrough and log errors outside the synchronized block
        LOG.error(
                "Unexpected null set for remote address {} when removing cnxn {}",
                remoteAddress,
                cnxn);
    }

    @Override
    public void resetAllConnectionStats() {
        // No need to synchronize since cnxns is backed by a ConcurrentHashMap
        for (ServerCnxn c : cnxns) {
            c.resetStats();
        }
    }

    @Override
    public Iterable<Map<String, Object>> getAllConnectionInfo(boolean brief) {
        HashSet<Map<String, Object>> info = new HashSet<Map<String, Object>>();
        // No need to synchronize since cnxns is backed by a ConcurrentHashMap
        for (ServerCnxn c : cnxns) {
            info.add(c.getConnectionInfo(brief));
        }
        return info;
    }

    /**
     * Sets the test ByteBufAllocator. This allocator will be used by all
     * future instances of this class.
     * It is not recommended to use this method outside of testing.
     *
     * @param allocator the ByteBufAllocator to use for all netty buffer
     *                  allocations.
     */
    static void setTestAllocator(ByteBufAllocator allocator) {
        TEST_ALLOCATOR.set(allocator);
    }

    /**
     * Clears the test ByteBufAllocator. The default allocator will be used
     * by all future instances of this class.
     * It is not recommended to use this method outside of testing.
     */
    static void clearTestAllocator() {
        TEST_ALLOCATOR.set(null);
    }
}
