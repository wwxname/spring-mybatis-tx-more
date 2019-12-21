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

import com.plusme.lbs.service.zk.jmx.ZKMBeanInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 * This class implements a simple standalone ZooKeeperServer. It sets up the
 * following chain of RequestProcessors to process requests:
 * PrepRequestProcessor -> SyncRequestProcessor -> FinalRequestProcessor
 */
public class ZooKeeperServer implements ZKMBeanInfo {
    protected static final Logger LOG;

    static {
        LOG = LoggerFactory.getLogger(ZooKeeperServer.class);


    }


    public static final int DEFAULT_TICK_TIME = 3000;
    protected int tickTime = DEFAULT_TICK_TIME;
    /** value of -1 indicates unset, use default */
    protected int minSessionTimeout = -1;
    /** value of -1 indicates unset, use default */
    protected int maxSessionTimeout = -1;

    private final AtomicLong hzxid = new AtomicLong(0);
    public final static Exception ok = new Exception("No prob");
    protected volatile State state = State.INITIAL;

    @Override
    public String getName() {
        return null;
    }

    @Override
    public boolean isHidden() {
        return false;
    }

    protected enum State {
        INITIAL, RUNNING, SHUTDOWN, ERROR
    }

    /**
     * This is the secret that we use to generate passwords. For the moment,
     * it's more of a checksum that's used in reconnection, which carries no
     * security weight, and is treated internally as if it carries no
     * security weight.
     */
    static final private long superSecret = 0XB3415C00L;

    private final AtomicInteger requestsInProcess = new AtomicInteger(0);

    public void removeCnxn(ServerCnxn serverCnxn){

    }
    protected ServerCnxnFactory serverCnxnFactory;
    protected ServerCnxnFactory secureServerCnxnFactory;


    private volatile int createSessionTrackerServerId = 1;


    /**
     * Creates a ZooKeeperServer instance. Nothing is setup, use the setX
     * methods to prepare the instance (eg datadir, datalogdir, ticktime,
     * builder, etc...)
     *
     * @throws IOException
     */
    public ZooKeeperServer() {

    }

    public void dumpConf(PrintWriter pwriter) {
        pwriter.print("clientPort=");
        pwriter.println(getClientPort());
        pwriter.print("secureClientPort=");
        pwriter.println(getSecureClientPort());
        pwriter.print("dataDir=");


        pwriter.print("tickTime=");
        pwriter.println(getTickTime());
        pwriter.print("maxClientCnxns=");
        pwriter.println(getMaxClientCnxnsPerHost());
        pwriter.print("minSessionTimeout=");
        pwriter.println(getMinSessionTimeout());
        pwriter.print("maxSessionTimeout=");
        pwriter.println(getMaxSessionTimeout());

        pwriter.print("serverId=");

    }


    /**
     * This constructor is for backward compatibility with the existing unit
     * test code.
     * It defaults to FileLogProvider persistence provider.
     */
    public ZooKeeperServer(File snapDir, File logDir, int tickTime)
            throws IOException {

    }


    private long getDirSize(File file) {
        long size = 0L;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    size += getDirSize(f);
                }
            }
        } else {
            size = file.length();
        }
        return size;
    }

    public long getZxid() {
        return hzxid.get();
    }


    long getNextZxid() {
        return hzxid.incrementAndGet();
    }

    public void setZxid(long zxid) {
        hzxid.set(zxid);
    }

    private void close(long sessionId) {

    }

    public void closeSession(long sessionId) {
        LOG.info("Closing session 0x" + Long.toHexString(sessionId));

        // we do not want to wait for a session close. send it as soon as we
        // detect it!
        close(sessionId);
    }


    public static class MissingSessionException extends IOException {
        private static final long serialVersionUID = 7467414635467261007L;

        public MissingSessionException(String msg) {
            super(msg);
        }
    }

    void touch(ServerCnxn cnxn) throws MissingSessionException {
        if (cnxn == null) {
            return;
        }
        long id = cnxn.getSessionId();
        int to = cnxn.getSessionTimeout();

    }


    public void setCreateSessionTrackerServerId(int newId) {
        createSessionTrackerServerId = newId;
    }


    /**
     * This can be used while shutting down the server to see whether the server
     * is already shutdown or not.
     *
     * @return true if the server is running or server hits an error, false
     *         otherwise.
     */
    protected boolean canShutdown() {
        return state == State.RUNNING || state == State.ERROR;
    }

    /**
     * @return true if the server is running, false otherwise.
     */
    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public void shutdown() {
        shutdown(false);
    }

    /**
     * Shut down the server instance
     * @param fullyShutDown true if another server using the same database will not replace this one in the same process
     */
    public synchronized void shutdown(boolean fullyShutDown) {
        if (!canShutdown()) {
            LOG.debug("ZooKeeper server is not running, so not proceeding to shutdown!");
            return;
        }
        LOG.info("shutting down");


        unregisterJMX();
    }

    protected void unregisterJMX() {
        // unregister from JMX

    }

    public void incInProcess() {
        requestsInProcess.incrementAndGet();
    }

    public void decInProcess() {
        requestsInProcess.decrementAndGet();
    }

    public int getInProcess() {
        return requestsInProcess.get();
    }


    byte[] generatePasswd(long id) {
        Random r = new Random(id ^ superSecret);
        byte p[] = new byte[16];
        r.nextBytes(p);
        return p;
    }

    protected boolean checkPasswd(long sessionId, byte[] passwd) {
        return sessionId != 0
                && Arrays.equals(passwd, generatePasswd(sessionId));
    }


    public void reopenSession(ServerCnxn cnxn, long sessionId, byte[] passwd,
                              int sessionTimeout) throws IOException {
        if (checkPasswd(sessionId, passwd)) {

        } else {
            LOG.warn("Incorrect password from " + cnxn.getRemoteSocketAddress()
                    + " for session 0x" + Long.toHexString(sessionId));
            finishSessionInit(cnxn, false);
        }
    }

    public void finishSessionInit(ServerCnxn cnxn, boolean valid) {
        // register with JMX
        try {
            if (valid) {
                if (serverCnxnFactory != null && serverCnxnFactory.cnxns.contains(cnxn)) {
                    serverCnxnFactory.registerConnection(cnxn);
                } else if (secureServerCnxnFactory != null && secureServerCnxnFactory.cnxns.contains(cnxn)) {
                    secureServerCnxnFactory.registerConnection(cnxn);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
        }

        try {

        } catch (Exception e) {
            LOG.warn("Exception while establishing session, closing", e);
            cnxn.close();
        }
    }


    public static int getSnapCount() {
        String sc = System.getProperty("zookeeper.snapCount");
        try {
            int snapCount = Integer.parseInt(sc);

            // snapCount must be 2 or more. See org.apache.zookeeper.server.SyncRequestProcessor
            if (snapCount < 2) {
                LOG.warn("SnapCount should be 2 or more. Now, snapCount is reset to 2");
                snapCount = 2;
            }
            return snapCount;
        } catch (Exception e) {
            return 100000;
        }
    }

    public int getGlobalOutstandingLimit() {
        String sc = System.getProperty("zookeeper.globalOutstandingLimit");
        int limit;
        try {
            limit = Integer.parseInt(sc);
        } catch (Exception e) {
            limit = 1000;
        }
        return limit;
    }

    public void setServerCnxnFactory(ServerCnxnFactory factory) {
        serverCnxnFactory = factory;
    }

    public ServerCnxnFactory getServerCnxnFactory() {
        return serverCnxnFactory;
    }

    public ServerCnxnFactory getSecureServerCnxnFactory() {
        return secureServerCnxnFactory;
    }

    public void setSecureServerCnxnFactory(ServerCnxnFactory factory) {
        secureServerCnxnFactory = factory;
    }

    /**
     * return the last proceesed id from the
     * datatree
     */

    /**
     * return the outstanding requests
     * in the queue, which havent been
     * processed yet
     */
    public long getOutstandingRequests() {
        return getInProcess();
    }

    /**
     * return the total number of client connections that are alive
     * to this server
     */
    public int getNumAliveConnections() {
        int numAliveConnections = 0;

        if (serverCnxnFactory != null) {
            numAliveConnections += serverCnxnFactory.getNumAliveConnections();
        }

        if (secureServerCnxnFactory != null) {
            numAliveConnections += secureServerCnxnFactory.getNumAliveConnections();
        }

        return numAliveConnections;
    }

    /**
     * trunccate the log to get in sync with others
     * if in a quorum
     * @param zxid the zxid that it needs to get in sync
     * with others
     * @throws IOException
     */
    public void truncateLog(long zxid) throws IOException {

    }

    public int getTickTime() {
        return tickTime;
    }

    public void setTickTime(int tickTime) {
        LOG.info("tickTime set to " + tickTime);
        this.tickTime = tickTime;
    }

    public int getMinSessionTimeout() {
        return minSessionTimeout;
    }

    public void setMinSessionTimeout(int min) {
        this.minSessionTimeout = min == -1 ? tickTime * 2 : min;
        LOG.info("minSessionTimeout set to {}", this.minSessionTimeout);
    }

    public int getMaxSessionTimeout() {
        return maxSessionTimeout;
    }

    public void setMaxSessionTimeout(int max) {
        this.maxSessionTimeout = max == -1 ? tickTime * 20 : max;
        LOG.info("maxSessionTimeout set to {}", this.maxSessionTimeout);
    }

    public int getClientPort() {
        return serverCnxnFactory != null ? serverCnxnFactory.getLocalPort() : -1;
    }

    public int getSecureClientPort() {
        return secureServerCnxnFactory != null ? secureServerCnxnFactory.getLocalPort() : -1;
    }

    /** Maximum number of connections allowed from particular host (ip) */
    public int getMaxClientCnxnsPerHost() {
        if (serverCnxnFactory != null) {
            return serverCnxnFactory.getMaxClientCnxnsPerHost();
        }
        if (secureServerCnxnFactory != null) {
            return secureServerCnxnFactory.getMaxClientCnxnsPerHost();
        }
        return -1;
    }


    public boolean shouldThrottle(long outStandingCount) {
        if (getGlobalOutstandingLimit() < getInProcess()) {
            return outStandingCount > 0;
        }
        return false;
    }

}
