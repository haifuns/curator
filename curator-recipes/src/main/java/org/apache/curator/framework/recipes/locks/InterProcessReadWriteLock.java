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
package org.apache.curator.framework.recipes.locks;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import org.apache.curator.framework.CuratorFramework;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * <p>
 *    A re-entrant read/write mutex that works across JVMs. Uses Zookeeper to hold the lock. All processes
 *    in all JVMs that use the same lock path will achieve an inter-process critical section. Further, this mutex is
 *    "fair" - each user will get the mutex in the order requested (from ZK's point of view).
 * </p>
 *
 * <p>
 *    A read write lock maintains a pair of associated locks, one for read-only operations and one
 *    for writing. The read lock may be held simultaneously by multiple reader processes, so long as
 *    there are no writers. The write lock is exclusive.
 * </p>
 *
 * <p>
 *    <b>Reentrancy</b><br>
 *    This lock allows both readers and writers to reacquire read or write locks in the style of a
 *    re-entrant lock. Non-re-entrant readers are not allowed until all write locks held by the
 *    writing thread/process have been released. Additionally, a writer can acquire the read lock, but not
 *    vice-versa. If a reader tries to acquire the write lock it will never succeed.<br><br>
 *
 *    <b>Lock downgrading</b><br>
 *    Re-entrancy also allows downgrading from the write lock to a read lock, by acquiring the write
 *    lock, then the read lock and then releasing the write lock. However, upgrading from a read
 *    lock to the write lock is not possible.
 * </p>
 */
public class InterProcessReadWriteLock
{
    private final InterProcessMutex readMutex;
    private final InterProcessMutex writeMutex;

    // must be the same length. LockInternals depends on it
    private static final String READ_LOCK_NAME  = "__READ__";
    private static final String WRITE_LOCK_NAME = "__WRIT__";

    private static class SortingLockInternalsDriver extends StandardLockInternalsDriver
    {
        @Override
        public final String fixForSorting(String str, String lockName)
        {
            str = super.fixForSorting(str, READ_LOCK_NAME);
            str = super.fixForSorting(str, WRITE_LOCK_NAME);
            return str;
        }
    }

    private static class InternalInterProcessMutex extends InterProcessMutex
    {
        private final String lockName;
        private final byte[] lockData;

        InternalInterProcessMutex(CuratorFramework client, String path, String lockName, byte[] lockData, int maxLeases, LockInternalsDriver driver)
        {
            super(client, path, lockName, maxLeases, driver);
            this.lockName = lockName;
            this.lockData = lockData;
        }

        @Override
        public Collection<String> getParticipantNodes() throws Exception
        {
            Collection<String>  nodes = super.getParticipantNodes();
            Iterable<String>    filtered = Iterables.filter
            (
                nodes,
                new Predicate<String>()
                {
                    @Override
                    public boolean apply(String node)
                    {
                        return node.contains(lockName);
                    }
                }
            );
            return ImmutableList.copyOf(filtered);
        }

        @Override
        protected byte[] getLockNodeBytes()
        {
            return lockData;
        }
    }

  /**
    * @param client the client
    * @param basePath path to use for locking
    */
    public InterProcessReadWriteLock(CuratorFramework client, String basePath)
    {
        this(client, basePath, null);
    }

  /**
    * @param client the client
    * @param basePath path to use for locking
    * @param lockData the data to store in the lock nodes
    */
    public InterProcessReadWriteLock(CuratorFramework client, String basePath, byte[] lockData)
    {
        lockData = (lockData == null) ? null : Arrays.copyOf(lockData, lockData.length);

        writeMutex = new InternalInterProcessMutex
        (
            client,
            basePath,
            WRITE_LOCK_NAME,
            lockData,
            // 写锁只能加一个
            1,
            new SortingLockInternalsDriver()
            {
                @Override
                public PredicateResults getsTheLock(CuratorFramework client, List<String> children, String sequenceNodeName, int maxLeases) throws Exception
                {
                    return super.getsTheLock(client, children, sequenceNodeName, maxLeases);
                }
            }
        );

        readMutex = new InternalInterProcessMutex
        (
            client,
            basePath,
            READ_LOCK_NAME,
            lockData,
                // 读锁最大加锁次数
            Integer.MAX_VALUE,
            new SortingLockInternalsDriver()
            {
                @Override
                public PredicateResults getsTheLock(CuratorFramework client, List<String> children, String sequenceNodeName, int maxLeases) throws Exception
                {
                    return readLockPredicate(children, sequenceNodeName);
                }
            }
        );
    }

    /**
     * Returns the lock used for reading.
     *
     * @return read lock
     */
    public InterProcessMutex     readLock()
    {
        return readMutex;
    }

    /**
     * Returns the lock used for writing.
     *
     * @return write lock
     */
    public InterProcessMutex     writeLock()
    {
        return writeMutex;
    }

    private PredicateResults readLockPredicate(List<String> children, String sequenceNodeName) throws Exception
    {
        // 如果是当前线程加的读锁，那么写锁可以加锁成功
        if ( writeMutex.isOwnedByCurrentThread() )
        {
            return new PredicateResults(null, true);
        }

        int         index = 0;
        // 最前面的写锁位置
        int         firstWriteIndex = Integer.MAX_VALUE;
        // 当前读锁的位置
        int         ourIndex = -1;
        for ( String node : children )
        {
            if ( node.contains(WRITE_LOCK_NAME) )
            {
                firstWriteIndex = Math.min(index, firstWriteIndex);
            }
            else if ( node.startsWith(sequenceNodeName) )
            {
                ourIndex = index;
                break;
            }

            ++index;
        }

        StandardLockInternalsDriver.validateOurIndex(sequenceNodeName, ourIndex);

        // 如果当前读锁前面有写锁则加锁失败，否则加锁成功
        boolean     getsTheLock = (ourIndex < firstWriteIndex);
        String      pathToWatch = getsTheLock ? null : children.get(firstWriteIndex);
        return new PredicateResults(pathToWatch, getsTheLock);
    }
}
