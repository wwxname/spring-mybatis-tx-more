/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.plusme.lbs.service.zk;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class NettyServerCnxn extends ServerCnxn {
    private static final Logger LOG = LoggerFactory.getLogger(NettyServerCnxn.class);
    private final Channel channel;
    private CompositeByteBuf queuedBuffer;
    private final AtomicBoolean throttled = new AtomicBoolean(false);
    private ByteBuffer bb;
    private final ByteBuffer bbLen = ByteBuffer.allocate(4);
    private long sessionId;
    private int sessionTimeout;
    private AtomicLong outstandingCount = new AtomicLong();
    private Certificate[] clientChain;
    private volatile boolean closingChannel;

    /** The ZooKeeperServer for this connection. May be null if the server
     * is not currently serving requests (for example if the server is not
     * an active quorum participant.
     */
    private volatile ZooKeeperServer zkServer;

    private final NettyServerCnxnFactory factory;
    private boolean initialized;

    NettyServerCnxn(Channel channel, ZooKeeperServer zks, NettyServerCnxnFactory factory) {
        this.channel = channel;
        this.closingChannel = false;
        this.zkServer = zks;
        this.factory = factory;
    }

    @Override
    public void close() {
        closingChannel = true;

        if (LOG.isDebugEnabled()) {
            LOG.debug("close called for sessionid:0x{}",
                    Long.toHexString(sessionId));
        }

        // ZOOKEEPER-2743:
        // Always unregister connection upon close to prevent
        // connection bean leak under certain race conditions.
        factory.unregisterConnection(this);

        // if this is not in cnxns then it's already closed
        if (!factory.cnxns.remove(this)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("cnxns size:{}", factory.cnxns.size());
            }
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("close in progress for sessionid:0x{}",
                    Long.toHexString(sessionId));
        }

        factory.removeCnxnFromIpMap(
                this,
                ((InetSocketAddress) channel.remoteAddress()).getAddress());

        if (zkServer != null) {
            zkServer.removeCnxn(this);
        }

        if (channel.isOpen()) {
            // Since we don't check on the futures created by write calls to the channel complete we need to make sure
            // that all writes have been completed before closing the channel or we risk data loss
            // See: http://lists.jboss.org/pipermail/netty-users/2009-August/001122.html
            channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                    future.channel().close().addListener(f -> releaseQueuedBuffer());
                }
            });
        } else {
            channel.eventLoop().execute(this::releaseQueuedBuffer);
        }
    }

    @Override
    public long getSessionId() {
        return sessionId;
    }

    @Override
    public int getSessionTimeout() {
        return sessionTimeout;
    }


    @Override
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }


    @Override
    public void sendBuffer(ByteBuffer sendBuffer) {
        if (sendBuffer == ServerCnxnFactory.closeConn) {
            close();
            return;
        }

    }

    /**
     * This class wraps the sendBuffer method of NIOServerCnxn. It is
     * responsible for chunking up the response to a client. Rather
     * than cons'ing up a response fully in memory, which may be large
     * for some commands, this class chunks up the result.
     */
    private class SendBufferWriter extends Writer {
        private StringBuffer sb = new StringBuffer();

        /**
         * Check if we are ready to send another chunk.
         * @param force force sending, even if not a full chunk
         */
        private void checkFlush(boolean force) {
            if ((force && sb.length() > 0) || sb.length() > 2048) {
                sendBuffer(ByteBuffer.wrap(sb.toString().getBytes()));
                // clear our internal buffer
                sb.setLength(0);
            }
        }

        @Override
        public void close() throws IOException {
            if (sb == null) {
                return;
            }
            checkFlush(true);
            sb = null; // clear out the ref to ensure no reuse
        }

        @Override
        public void flush() throws IOException {
            checkFlush(true);
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            sb.append(cbuf, off, len);
            checkFlush(false);
        }
    }


    /**
     * Helper that throws an IllegalStateException if the current thread is not
     * executing in the channel's event loop thread.
     * @param callerMethodName the name of the calling method to add to the exception message.
     */
    private void checkIsInEventLoop(String callerMethodName) {
        if (!channel.eventLoop().inEventLoop()) {
            throw new IllegalStateException(
                    callerMethodName + "() called from non-EventLoop thread");
        }
    }

    /**
     * Appends <code>buf</code> to <code>queuedBuffer</code>. Does not duplicate <code>buf</code>
     * or call any flavor of {@link ByteBuf#retain()}. Caller must ensure that <code>buf</code>
     * is not owned by anyone else, as this call transfers ownership of <code>buf</code> to the
     * <code>queuedBuffer</code>.
     *
     * This method should only be called from the event loop thread.
     * @param buf the buffer to append to the queue.
     */
    private void appendToQueuedBuffer(ByteBuf buf) {
        checkIsInEventLoop("appendToQueuedBuffer");
        if (queuedBuffer.numComponents() == queuedBuffer.maxNumComponents()) {
            // queuedBuffer has reached its component limit, so combine the existing components.
            queuedBuffer.consolidate();
        }
        queuedBuffer.addComponent(true, buf);
    }

    /**
     * Process incoming message. This should only be called from the event
     * loop thread.
     * Note that this method does not call <code>buf.release()</code>. The caller
     * is responsible for making sure the buf is released after this method
     * returns.
     * @param buf the message bytes to process.
     */
    void processMessage(ByteBuf buf) {
        checkIsInEventLoop("processMessage");
        if (LOG.isDebugEnabled()) {
            LOG.debug("0x{} queuedBuffer: {}",
                    Long.toHexString(sessionId),
                    queuedBuffer);
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("0x{} buf {}",
                    Long.toHexString(sessionId),
                    ByteBufUtil.hexDump(buf));
        }

        if (throttled.get()) {
            LOG.debug("Received message while throttled");
            // we are throttled, so we need to queue
            if (queuedBuffer == null) {
                LOG.debug("allocating queue");
                queuedBuffer = channel.alloc().compositeBuffer();
            }
            appendToQueuedBuffer(buf.retainedDuplicate());
            if (LOG.isTraceEnabled()) {
                LOG.trace("0x{} queuedBuffer {}",
                        Long.toHexString(sessionId),
                        ByteBufUtil.hexDump(queuedBuffer));
            }
        } else {
            LOG.debug("not throttled");
            if (queuedBuffer != null) {
                appendToQueuedBuffer(buf.retainedDuplicate());
                processQueuedBuffer();
            } else {
                receiveMessage(buf);
                // Have to check !closingChannel, because an error in
                // receiveMessage() could have led to close() being called.
                if (!closingChannel && buf.isReadable()) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Before copy {}", buf);
                    }
                    if (queuedBuffer == null) {
                        queuedBuffer = channel.alloc().compositeBuffer();
                    }
                    appendToQueuedBuffer(buf.retainedSlice(buf.readerIndex(), buf.readableBytes()));
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Copy is {}", queuedBuffer);
                        LOG.trace("0x{} queuedBuffer {}",
                                Long.toHexString(sessionId),
                                ByteBufUtil.hexDump(queuedBuffer));
                    }
                }
            }
        }
    }

    /**
     * Try to process previously queued message. This should only be called
     * from the event loop thread.
     */
    void processQueuedBuffer() {
        checkIsInEventLoop("processQueuedBuffer");
        if (queuedBuffer != null) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("processing queue 0x{} queuedBuffer {}",
                        Long.toHexString(sessionId),
                        ByteBufUtil.hexDump(queuedBuffer));
            }
            receiveMessage(queuedBuffer);
            if (closingChannel) {
                // close() could have been called if receiveMessage() failed
                LOG.debug("Processed queue - channel closed, dropping remaining bytes");
            } else if (!queuedBuffer.isReadable()) {
                LOG.debug("Processed queue - no bytes remaining");
                releaseQueuedBuffer();
            } else {
                LOG.debug("Processed queue - bytes remaining");
                // Try to reduce memory consumption by freeing up buffer space
                // which is no longer needed.
                queuedBuffer.discardReadComponents();
            }
        } else {
            LOG.debug("queue empty");
        }
    }

    /**
     * Clean up queued buffer once it's no longer needed. This should only be
     * called from the event loop thread.
     */
    private void releaseQueuedBuffer() {
        checkIsInEventLoop("releaseQueuedBuffer");
        if (queuedBuffer != null) {
            queuedBuffer.release();
            queuedBuffer = null;
        }
    }

    /**
     * Receive a message, which can come from the queued buffer or from a new
     * buffer coming in over the channel. This should only be called from the
     * event loop thread.
     * Note that this method does not call <code>message.release()</code>. The
     * caller is responsible for making sure the message is released after this
     * method returns.
     * @param message the message bytes to process.
     */
    private void receiveMessage(ByteBuf message) {
        checkIsInEventLoop("receiveMessage");
        try {
            while (message.isReadable() && !throttled.get()) {
                if (bb != null) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("message readable {} bb len {} {}",
                                message.readableBytes(),
                                bb.remaining(),
                                bb);
                        ByteBuffer dat = bb.duplicate();
                        dat.flip();
                        LOG.trace("0x{} bb {}",
                                Long.toHexString(sessionId),
                                ByteBufUtil.hexDump(Unpooled.wrappedBuffer(dat)));
                    }

                    if (bb.remaining() > message.readableBytes()) {
                        int newLimit = bb.position() + message.readableBytes();
                        bb.limit(newLimit);
                    }
                    message.readBytes(bb);
                    bb.limit(bb.capacity());

                    if (LOG.isTraceEnabled()) {
                        LOG.trace("after readBytes message readable {} bb len {} {}",
                                message.readableBytes(),
                                bb.remaining(),
                                bb);
                        ByteBuffer dat = bb.duplicate();
                        dat.flip();
                        LOG.trace("after readbytes 0x{} bb {}",
                                Long.toHexString(sessionId),
                                ByteBufUtil.hexDump(Unpooled.wrappedBuffer(dat)));
                    }
                    if (bb.remaining() == 0) {

                        bb.flip();

                        ZooKeeperServer zks = this.zkServer;
                        if (zks == null || !zks.isRunning()) {
                            throw new IOException("ZK down");
                        }
                        if (initialized) {
                            // TODO: if zks.processPacket() is changed to take a ByteBuffer[],
                            // we could implement zero-copy queueing.
                           // zks.processPacket(this, bb);

                            if (zks.shouldThrottle(outstandingCount.incrementAndGet())) {
                                disableRecvNoWait();
                            }
                        } else {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("got conn req request from {}",
                                        getRemoteSocketAddress());
                            }
                            //zks.processConnectRequest(this, bb);
                            initialized = true;
                        }
                        bb = null;
                    }
                } else {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("message readable {} bblenrem {}",
                                message.readableBytes(),
                                bbLen.remaining());
                        ByteBuffer dat = bbLen.duplicate();
                        dat.flip();
                        LOG.trace("0x{} bbLen {}",
                                Long.toHexString(sessionId),
                                ByteBufUtil.hexDump(Unpooled.wrappedBuffer(dat)));
                    }

                    if (message.readableBytes() < bbLen.remaining()) {
                        bbLen.limit(bbLen.position() + message.readableBytes());
                    }
                    message.readBytes(bbLen);
                    bbLen.limit(bbLen.capacity());
                    if (bbLen.remaining() == 0) {
                        bbLen.flip();

                        if (LOG.isTraceEnabled()) {
                            LOG.trace("0x{} bbLen {}",
                                    Long.toHexString(sessionId),
                                    ByteBufUtil.hexDump(Unpooled.wrappedBuffer(bbLen)));
                        }
                        int len = bbLen.getInt();
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("0x{} bbLen len is {}",
                                    Long.toHexString(sessionId),
                                    len);
                        }

                        bbLen.clear();
                        if (!initialized) {

                        }

                        bb = ByteBuffer.allocate(len);
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Closing connection to " + getRemoteSocketAddress(), e);
            close();
        }
    }

    /**
     * An event that triggers a change in the channel's "Auto Read" setting.
     * Used for throttling. By using an enum we can treat the two values as
     * singletons and compare with ==.
     */
    enum AutoReadEvent {
        DISABLE,
        ENABLE
    }

    /**
     * Note that both disableRecv() and disableRecvNoWait() are asynchronous in the netty implementation.
     */
    @Override
    public void disableRecv() {
        if (throttled.compareAndSet(false, true)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Throttling - disabling recv {}", this);
            }
            channel.pipeline().fireUserEventTriggered(AutoReadEvent.DISABLE);
        }
    }

    private void disableRecvNoWait() {
        disableRecv();
    }

    @Override
    public void enableRecv() {
        if (throttled.compareAndSet(true, false)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Sending unthrottle event {}", this);
            }
            channel.pipeline().fireUserEventTriggered(AutoReadEvent.ENABLE);
        }
    }

    @Override
    public long getOutstandingRequests() {
        return outstandingCount.longValue();
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    @Override
    public int getInterestOps() {
        // This might not be 100% right, but it's only used for printing
        // connection info in the netty implementation so it's probably ok.
        if (channel == null || !channel.isOpen()) {
            return 0;
        }
        int interestOps = 0;
        if (!throttled.get()) {
            interestOps |= SelectionKey.OP_READ;
        }
        if (!channel.isWritable()) {
            // OP_READ means "can read", but OP_WRITE means "cannot write",
            // it's weird.
            interestOps |= SelectionKey.OP_WRITE;
        }
        return interestOps;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public InetSocketAddress getRemoteSocketAddress() {
        return (InetSocketAddress) channel.remoteAddress();
    }

    /** Send close connection packet to the client.
     */
    @Override
    public void sendCloseSession() {
        sendBuffer(ServerCnxnFactory.closeConn);
    }


    @Override
    public Certificate[] getClientCertificateChain() {
        if (clientChain == null) {
            return null;
        }
        return Arrays.copyOf(clientChain, clientChain.length);
    }

    @Override
    public void setClientCertificateChain(Certificate[] chain) {
        if (chain == null) {
            clientChain = null;
        } else {
            clientChain = Arrays.copyOf(chain, chain.length);
        }
    }

    // For tests and NettyServerCnxnFactory only, thus package-private.
    Channel getChannel() {
        return channel;
    }
}
