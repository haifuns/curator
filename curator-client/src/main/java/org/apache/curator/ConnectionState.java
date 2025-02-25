/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.curator;

import com.google.common.annotations.VisibleForTesting;
import org.apache.curator.drivers.EventTrace;
import org.apache.curator.drivers.OperationTrace;
import org.apache.curator.drivers.TracerDriver;
import org.apache.curator.ensemble.EnsembleProvider;
import org.apache.curator.utils.CloseableUtils;
import org.apache.curator.utils.DebugUtils;
import org.apache.curator.utils.ThreadUtils;
import org.apache.curator.utils.ZookeeperFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

class ConnectionState implements Watcher, Closeable
{
    private static final int MAX_BACKGROUND_EXCEPTIONS = 10;
    private static final boolean LOG_EVENTS = Boolean.getBoolean(DebugUtils.PROPERTY_LOG_EVENTS);
    private static final Logger log = LoggerFactory.getLogger(ConnectionState.class);
    private final HandleHolder zooKeeper;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final EnsembleProvider ensembleProvider;
    private final int sessionTimeoutMs;
    private final int connectionTimeoutMs;
    private final AtomicReference<TracerDriver> tracer;
    private final Queue<Exception> backgroundExceptions = new ConcurrentLinkedQueue<Exception>();
    private final Queue<Watcher> parentWatchers = new ConcurrentLinkedQueue<Watcher>();
    private final AtomicLong instanceIndex = new AtomicLong();
    private volatile long connectionStartMs = 0;

    @VisibleForTesting
    volatile boolean debugWaitOnExpiredEvent = false;

    ConnectionState(ZookeeperFactory zookeeperFactory, EnsembleProvider ensembleProvider, int sessionTimeoutMs, int connectionTimeoutMs, Watcher parentWatcher, AtomicReference<TracerDriver> tracer, boolean canBeReadOnly)
    {
        this.ensembleProvider = ensembleProvider;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.tracer = tracer;
        if ( parentWatcher != null )
        {
            parentWatchers.offer(parentWatcher);
        }

        zooKeeper = new HandleHolder(zookeeperFactory, this, ensembleProvider, sessionTimeoutMs, canBeReadOnly);
    }

    ZooKeeper getZooKeeper() throws Exception
    {
        if ( SessionFailRetryLoop.sessionForThreadHasFailed() )
        {
            throw new SessionFailRetryLoop.SessionFailedException();
        }

        Exception exception = backgroundExceptions.poll();
        if ( exception != null )
        {
            new EventTrace("background-exceptions", tracer.get()).commit();
            throw exception;
        }

        boolean localIsConnected = isConnected.get();
        if ( !localIsConnected )
        {
            checkTimeouts();
        }

        return zooKeeper.getZooKeeper();
    }

    boolean isConnected()
    {
        return isConnected.get();
    }

    void start() throws Exception
    {
        log.debug("Starting");
        ensembleProvider.start();
        // 创建zk连接
        reset();
    }

    @Override
    public void close() throws IOException
    {
        log.debug("Closing");

        CloseableUtils.closeQuietly(ensembleProvider);
        try
        {
            zooKeeper.closeAndClear();
        }
        catch ( Exception e )
        {
            ThreadUtils.checkInterrupted(e);
            throw new IOException(e);
        }
        finally
        {
            isConnected.set(false);
        }
    }

    void addParentWatcher(Watcher watcher)
    {
        parentWatchers.offer(watcher);
    }

    void removeParentWatcher(Watcher watcher)
    {
        parentWatchers.remove(watcher);
    }

    long getInstanceIndex()
    {
        return instanceIndex.get();
    }

    @Override
    public void process(WatchedEvent event)
    {
        if ( LOG_EVENTS )
        {
            log.debug("ConnectState watcher: " + event);
        }

        final boolean eventTypeNone = event.getType() == Watcher.Event.EventType.None;

        if ( eventTypeNone )
        {
            boolean wasConnected = isConnected.get();
            boolean newIsConnected = checkState(event.getState(), wasConnected);
            if ( newIsConnected != wasConnected )
            {
                isConnected.set(newIsConnected);
                connectionStartMs = System.currentTimeMillis();
            }
        }

        // only wait during tests
        if (debugWaitOnExpiredEvent && event.getState() == Event.KeeperState.Expired)
        {
            waitOnExpiredEvent();
        }

        for ( Watcher parentWatcher : parentWatchers )
        {
            OperationTrace trace = new OperationTrace("connection-state-parent-process", tracer.get(), getSessionId());
            parentWatcher.process(event);
            trace.commit();
        }

        if (eventTypeNone) handleState(event.getState());
    }

    // only for testing
    private void waitOnExpiredEvent()
    {
        log.debug("Waiting on Expired event for testing");
        try
        {
            Thread.sleep(1000);
        }
        catch(InterruptedException e) {}
        log.debug("Continue processing");
    }

    EnsembleProvider getEnsembleProvider()
    {
        return ensembleProvider;
    }

    private synchronized void checkTimeouts() throws Exception
    {
        int minTimeout = Math.min(sessionTimeoutMs, connectionTimeoutMs);
        long elapsed = System.currentTimeMillis() - connectionStartMs;
        if ( elapsed >= minTimeout )
        {
            if ( zooKeeper.hasNewConnectionString() )
            {
                handleNewConnectionString();
            }
            else
            {
                int maxTimeout = Math.max(sessionTimeoutMs, connectionTimeoutMs);
                if ( elapsed > maxTimeout )
                {
                    if ( !Boolean.getBoolean(DebugUtils.PROPERTY_DONT_LOG_CONNECTION_ISSUES) )
                    {
                        log.warn(String.format("Connection attempt unsuccessful after %d (greater than max timeout of %d). Resetting connection and trying again with a new connection.", elapsed, maxTimeout));
                    }
                    reset();
                }
                else
                {
                    KeeperException.ConnectionLossException connectionLossException = new CuratorConnectionLossException();
                    if ( !Boolean.getBoolean(DebugUtils.PROPERTY_DONT_LOG_CONNECTION_ISSUES) )
                    {
                        log.error(String.format("Connection timed out for connection string (%s) and timeout (%d) / elapsed (%d)", zooKeeper.getConnectionString(), connectionTimeoutMs, elapsed), connectionLossException);
                    }
                    new EventTrace("connections-timed-out", tracer.get(), getSessionId()).commit();
                    throw connectionLossException;
                }
            }
        }
    }

    /**
     * Return the current session id
     */
    public long getSessionId() {
        long sessionId = 0;
        try {
            ZooKeeper zk = zooKeeper.getZooKeeper();
            if (zk != null) {
                sessionId = zk.getSessionId();
            }
        } catch (Exception e) {
            // Ignore the exception
        }
        return sessionId;
    }

    private synchronized void reset() throws Exception
    {
        log.debug("reset");

        instanceIndex.incrementAndGet();

        isConnected.set(false);
        connectionStartMs = System.currentTimeMillis();
        zooKeeper.closeAndReset();
        // 初始化zookeeper连接
        zooKeeper.getZooKeeper();   // initiate connection
    }

    private boolean checkState(Event.KeeperState state, boolean wasConnected)
    {
        boolean isConnected = wasConnected;
        switch ( state )
        {
        default:
        case Disconnected:
        case Expired:
        {
            isConnected = false;
            break;
        }

        case SyncConnected:
        case ConnectedReadOnly:
        {
            isConnected = true;
            break;
        }

        case AuthFailed:
        {
            isConnected = false;
            log.error("Authentication failed");
            break;
        }

        case SaslAuthenticated:
        {
            // NOP
            break;
        }
        }
        // the session expired is logged in handleExpiredSession, so not log here
        if (state != Event.KeeperState.Expired) {
            new EventTrace(state.toString(), tracer.get(), getSessionId()).commit();
        }

        return isConnected;
    }

    private void handleState(Event.KeeperState state)
    {
        if (state == Event.KeeperState.Expired)
        {
            handleExpiredSession();
        }
        else if (zooKeeper.hasNewConnectionString())
        {
            handleNewConnectionString();
        }
    }

    private void handleNewConnectionString()
    {
        log.info("Connection string changed");
        new EventTrace("connection-string-changed", tracer.get(), getSessionId()).commit();

        try
        {
            reset();
        }
        catch ( Exception e )
        {
            ThreadUtils.checkInterrupted(e);
            queueBackgroundException(e);
        }
    }

    private void handleExpiredSession()
    {
        log.warn("Session expired event received");
        new EventTrace("session-expired", tracer.get(), getSessionId()).commit();

        try
        {
            reset();
        }
        catch ( Exception e )
        {
            ThreadUtils.checkInterrupted(e);
            queueBackgroundException(e);
        }
    }

    @SuppressWarnings({"ThrowableResultOfMethodCallIgnored"})
    private void queueBackgroundException(Exception e)
    {
        while ( backgroundExceptions.size() >= MAX_BACKGROUND_EXCEPTIONS )
        {
            backgroundExceptions.poll();
        }
        backgroundExceptions.offer(e);
    }
}
