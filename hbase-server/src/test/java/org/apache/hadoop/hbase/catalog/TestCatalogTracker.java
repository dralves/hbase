/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.catalog;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.Assert;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.exceptions.NotAllMetaRegionsOnlineException;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.client.AdminProtocol;
import org.apache.hadoop.hbase.client.ClientProtocol;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HConnectionTestingUtility;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.RetriesExhaustedException;
import org.apache.hadoop.hbase.client.ServerCallable;
import org.apache.hadoop.hbase.exceptions.ServerNotRunningYetException;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetRegionInfoRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetRegionInfoResponse;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.GetRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.GetResponse;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.zookeeper.MetaRegionTracker;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.hadoop.util.Progressable;
import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;

import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;

/**
 * Test {@link CatalogTracker}
 */
@Category(MediumTests.class)
public class TestCatalogTracker {
  private static final Log LOG = LogFactory.getLog(TestCatalogTracker.class);
  private static final HBaseTestingUtility UTIL = new HBaseTestingUtility();
  private static final ServerName SN =
    new ServerName("example.org", 1234, System.currentTimeMillis());
  private ZooKeeperWatcher watcher;
  private Abortable abortable;

  @BeforeClass public static void beforeClass() throws Exception {
    // Set this down so tests run quicker
    UTIL.getConfiguration().setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER, 3);
    UTIL.startMiniZKCluster();
  }

  @AfterClass public static void afterClass() throws IOException {
    UTIL.getZkCluster().shutdown();
  }

  @Before public void before() throws IOException {
    this.abortable = new Abortable() {
      @Override
      public void abort(String why, Throwable e) {
        LOG.info(why, e);
      }

      @Override
      public boolean isAborted()  {
        return false;
      }
    };
    this.watcher = new ZooKeeperWatcher(UTIL.getConfiguration(),
      this.getClass().getSimpleName(), this.abortable, true);
  }

  @After public void after() {
    this.watcher.close();
  }

  private CatalogTracker constructAndStartCatalogTracker(final HConnection c)
  throws IOException, InterruptedException {
    CatalogTracker ct = new CatalogTracker(this.watcher, UTIL.getConfiguration(),
      c, this.abortable);
    ct.start();
    return ct;
  }

  /**
   * Test that we get notification if .META. moves.
   * @throws IOException
   * @throws InterruptedException
   * @throws KeeperException
   */
  @Test public void testThatIfMETAMovesWeAreNotified()
  throws IOException, InterruptedException, KeeperException {
    HConnection connection = Mockito.mock(HConnection.class);
    constructAndStartCatalogTracker(connection);
    try {
      MetaRegionTracker.setMetaLocation(this.watcher,
          new ServerName("example.com", 1234, System.currentTimeMillis()));
    } finally {
      // Clean out meta location or later tests will be confused... they presume
      // start fresh in zk.
      MetaRegionTracker.deleteMetaLocation(this.watcher);
    }
  }

  /**
   * Test interruptable while blocking wait on meta.
   * @throws IOException
   * @throws ServiceException
   * @throws InterruptedException
   */
  @Test public void testInterruptWaitOnMeta()
  throws IOException, InterruptedException, ServiceException {
    final ClientProtocol client = Mockito.mock(ClientProtocol.class);
    HConnection connection = mockConnection(null, client);
    try {
      Mockito.when(client.get((RpcController)Mockito.any(), (GetRequest)Mockito.any())).
      thenReturn(GetResponse.newBuilder().build());
      final CatalogTracker ct = constructAndStartCatalogTracker(connection);
      ServerName meta = ct.getMetaLocation();
      Assert.assertNull(meta);
      Thread t = new Thread() {
        @Override
        public void run() {
          try {
            ct.waitForMeta();
          } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted", e);
          }
        }
      };
      t.start();
      while (!t.isAlive())
        Threads.sleep(1);
      Threads.sleep(1);
      assertTrue(t.isAlive());
      ct.stop();
      // Join the thread... should exit shortly.
      t.join();
    } finally {
      HConnectionManager.deleteConnection(UTIL.getConfiguration());
    }
  }

  private void testVerifyMetaRegionLocationWithException(Exception ex)
  throws IOException, InterruptedException, KeeperException, ServiceException {
    // Mock an ClientProtocol.
    final ClientProtocol implementation = Mockito.mock(ClientProtocol.class);
    HConnection connection = mockConnection(null, implementation);
    try {
      // If a 'get' is called on mocked interface, throw connection refused.
      Mockito.when(implementation.get((RpcController) Mockito.any(), (GetRequest) Mockito.any())).
        thenThrow(new ServiceException(ex));
      // Now start up the catalogtracker with our doctored Connection.
      final CatalogTracker ct = constructAndStartCatalogTracker(connection);
      try {
        MetaRegionTracker.setMetaLocation(this.watcher, SN);
        long timeout = UTIL.getConfiguration().
          getLong("hbase.catalog.verification.timeout", 1000);
        Assert.assertFalse(ct.verifyMetaRegionLocation(timeout));
      } finally {
        // Clean out meta location or later tests will be confused... they
        // presume start fresh in zk.
        MetaRegionTracker.deleteMetaLocation(this.watcher);
      }
    } finally {
      // Clear out our doctored connection or could mess up subsequent tests.
      HConnectionManager.deleteConnection(UTIL.getConfiguration());
    }
  }

  /**
   * Test we survive a connection refused {@link ConnectException}
   * @throws IOException
   * @throws InterruptedException
   * @throws KeeperException
   * @throws ServiceException
   */
  @Test
  public void testGetMetaServerConnectionFails()
  throws IOException, InterruptedException, KeeperException, ServiceException {
    testVerifyMetaRegionLocationWithException(new ConnectException("Connection refused"));
  }

  /**
   * Test that verifyMetaRegionLocation properly handles getting a
   * ServerNotRunningException. See HBASE-4470.
   * Note this doesn't check the exact exception thrown in the
   * HBASE-4470 as there it is thrown from getHConnection() and
   * here it is thrown from get() -- but those are both called
   * from the same function anyway, and this way is less invasive than
   * throwing from getHConnection would be.
   *
   * @throws IOException
   * @throws InterruptedException
   * @throws KeeperException
   * @throws ServiceException
   */
  @Test
  public void testVerifyMetaRegionServerNotRunning()
  throws IOException, InterruptedException, KeeperException, ServiceException {
    testVerifyMetaRegionLocationWithException(new ServerNotRunningYetException("mock"));
  }

  /**
   * Test get of meta region fails properly if nothing to connect to.
   * @throws IOException
   * @throws InterruptedException
   * @throws KeeperException
   * @throws ServiceException
   */
  @Test
  public void testVerifyMetaRegionLocationFails()
  throws IOException, InterruptedException, KeeperException, ServiceException {
    HConnection connection = Mockito.mock(HConnection.class);
    ServiceException connectException =
      new ServiceException(new ConnectException("Connection refused"));
    final AdminProtocol implementation =
      Mockito.mock(AdminProtocol.class);
    Mockito.when(implementation.getRegionInfo((RpcController)Mockito.any(),
      (GetRegionInfoRequest)Mockito.any())).thenThrow(connectException);
    Mockito.when(connection.getAdmin(Mockito.any(ServerName.class), Mockito.anyBoolean())).
      thenReturn(implementation);
    final CatalogTracker ct = constructAndStartCatalogTracker(connection);
    try {
      MetaRegionTracker.setMetaLocation(this.watcher,
          new ServerName("example.com", 1234, System.currentTimeMillis()));
      Assert.assertFalse(ct.verifyMetaRegionLocation(100));
    } finally {
      // Clean out meta location or later tests will be confused... they presume
      // start fresh in zk.
      MetaRegionTracker.deleteMetaLocation(this.watcher);
    }
  }

  @Test (expected = NotAllMetaRegionsOnlineException.class)
  public void testTimeoutWaitForMeta()
  throws IOException, InterruptedException {
    HConnection connection = Mockito.mock(HConnection.class);
    final CatalogTracker ct = constructAndStartCatalogTracker(connection);
    ct.waitForMeta(100);
  }

  /**
   * Test waiting on meat w/ no timeout specified.
   * @throws IOException
   * @throws InterruptedException
   * @throws KeeperException
   */
  @Test public void testNoTimeoutWaitForMeta()
  throws IOException, InterruptedException, KeeperException {
    HConnection connection = Mockito.mock(HConnection.class);
    final CatalogTracker ct = constructAndStartCatalogTracker(connection);
    ServerName hsa = ct.getMetaLocation();
    Assert.assertNull(hsa);

    // Now test waiting on meta location getting set.
    Thread t = new WaitOnMetaThread(ct);
    startWaitAliveThenWaitItLives(t, 1);
    // Set a meta location.
    hsa = setMetaLocation();
    // Join the thread... should exit shortly.
    t.join();
    // Now meta is available.
    Assert.assertTrue(ct.getMetaLocation().equals(hsa));
  }

  private ServerName setMetaLocation() throws KeeperException {
    MetaRegionTracker.setMetaLocation(this.watcher, SN);
    return SN;
  }

  /**
   * @param admin An {@link AdminProtocol} instance; you'll likely
   * want to pass a mocked HRS; can be null.
   * @param client A mocked ClientProtocol instance, can be null
   * @return Mock up a connection that returns a {@link Configuration} when
   * {@link HConnection#getConfiguration()} is called, a 'location' when
   * {@link HConnection#getRegionLocation(byte[], byte[], boolean)} is called,
   * and that returns the passed {@link AdminProtocol} instance when
   * {@link HConnection#getAdmin(ServerName)} is called, returns the passed
   * {@link ClientProtocol} instance when {@link HConnection#getClient(ServerName)}
   * is called (Be sure call
   * {@link HConnectionManager#deleteConnection(org.apache.hadoop.conf.Configuration)}
   * when done with this mocked Connection.
   * @throws IOException
   */
  private HConnection mockConnection(final AdminProtocol admin,
      final ClientProtocol client) throws IOException {
    HConnection connection =
      HConnectionTestingUtility.getMockedConnection(UTIL.getConfiguration());
    Mockito.doNothing().when(connection).close();
    // Make it so we return any old location when asked.
    final HRegionLocation anyLocation =
      new HRegionLocation(HRegionInfo.FIRST_META_REGIONINFO, SN, HConstants.NO_SEQNUM);
    Mockito.when(connection.getRegionLocation((byte[]) Mockito.any(),
        (byte[]) Mockito.any(), Mockito.anyBoolean())).
      thenReturn(anyLocation);
    Mockito.when(connection.locateRegion((byte[]) Mockito.any(),
        (byte[]) Mockito.any())).
      thenReturn(anyLocation);
    if (admin != null) {
      // If a call to getHRegionConnection, return this implementation.
      Mockito.when(connection.getAdmin(Mockito.any(ServerName.class))).
        thenReturn(admin);
    }
    if (client != null) {
      // If a call to getClient, return this implementation.
      Mockito.when(connection.getClient(Mockito.any(ServerName.class))).
        thenReturn(client);
    }
    return connection;
  }

  /**
   * @return A mocked up Result that fakes a Get on a row in the
   * <code>.META.</code> table.
   * @throws IOException
   */
  private Result getMetaTableRowResult() throws IOException {
    return MetaMockingUtil.getMetaTableRowResult(HRegionInfo.FIRST_META_REGIONINFO, SN);
  }

  private void startWaitAliveThenWaitItLives(final Thread t, final int ms) {
    t.start();
    while(!t.isAlive()) {
      // Wait
    }
    // Wait one second.
    Threads.sleep(ms);
    Assert.assertTrue("Assert " + t.getName() + " still waiting", t.isAlive());
  }

  class CountingProgressable implements Progressable {
    final AtomicInteger counter = new AtomicInteger(0);
    @Override
    public void progress() {
      this.counter.incrementAndGet();
    }
  }

  /**
   * Wait on META.
   */
  class WaitOnMetaThread extends Thread {
    final CatalogTracker ct;

    WaitOnMetaThread(final CatalogTracker ct) {
      super("WaitOnMeta");
      this.ct = ct;
    }

    @Override
    public void run() {
      try {
        doWaiting();
      } catch (InterruptedException e) {
        throw new RuntimeException("Failed wait", e);
      }
      LOG.info("Exiting " + getName());
    }

    void doWaiting() throws InterruptedException {
      try {
        while (this.ct.waitForMeta(100) == null);
      } catch (NotAllMetaRegionsOnlineException e) {
        // Ignore.
      }
    }
  }

}
