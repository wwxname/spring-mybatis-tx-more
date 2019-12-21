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

import com.plusme.lbs.service.zk.jmx.MBeanRegistry;
import com.plusme.lbs.service.zk.jmx.ZKMBeanInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.ObjectName;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Implementation of connection MBean interface.
 */
public class ConnectionBean implements ZKMBeanInfo{
    private static final Logger LOG = LoggerFactory.getLogger(ConnectionBean.class);

    private final ServerCnxn connection;


    private final ZooKeeperServer zk;

    private final String remoteIP;
    private final long sessionId;

    public ConnectionBean(ServerCnxn connection, ZooKeeperServer zk) {
        this.connection = connection;
        this.zk = zk;

        InetSocketAddress sockAddr = connection.getRemoteSocketAddress();
        if (sockAddr == null) {
            remoteIP = "Unknown";
        } else {
            InetAddress addr = sockAddr.getAddress();
            if (addr instanceof Inet6Address) {
                remoteIP = ObjectName.quote(addr.getHostAddress());
            } else {
                remoteIP = addr.getHostAddress();
            }
        }
        sessionId = connection.getSessionId();
    }


    public String getSessionId() {
        return "0x" + Long.toHexString(sessionId);
    }


    public String getSourceIP() {
        InetSocketAddress sockAddr = connection.getRemoteSocketAddress();
        if (sockAddr == null) {
            return null;
        }
        return sockAddr.getAddress().getHostAddress()
                + ":" + sockAddr.getPort();
    }


    @Override
    public String getName() {
        return MBeanRegistry.getInstance().makeFullPath("Connections", remoteIP,
                getSessionId());
    }


    @Override
    public boolean isHidden() {
        return false;
    }


    public void terminateSession() {
        try {
            zk.closeSession(sessionId);
        } catch (Exception e) {
            LOG.warn("Unable to closeSession() for session: 0x"
                    + getSessionId(), e);
        }
    }


    public void terminateConnection() {
        connection.sendCloseSession();
    }


}
