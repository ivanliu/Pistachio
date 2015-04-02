/*
 * Copyright 2014 Yahoo! Inc. Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or
 * agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */


package com.yahoo.ads.pb;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TSSLTransportFactory;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TSSLTransportFactory.TSSLTransportParameters;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;

import java.nio.ByteBuffer;

import com.yahoo.ads.pb.helix.HelixPartitionSpectator;
import com.yahoo.ads.pb.util.ConfigurationManager;

import org.apache.commons.configuration.Configuration;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.LoggerFactory;

import com.google.api.client.util.ExponentialBackOff;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.codahale.metrics.JmxReporter;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.client.util.BackOff;
import com.yahoo.ads.pb.PistachiosClientImpl;
import com.yahoo.ads.pb.PistachiosServer;
import com.yahoo.ads.pb.customization.ProcessorRegistry;
import com.yahoo.ads.pb.exception.*;
import com.yahoo.ads.pb.network.netty.NettyPistachioClient;

/**
 * Main Pistachio Client Class
 * <ul>
 * <li>To use the Client, new an instance and call the functions
 * </ul>
 * <p>
 * 
 * @author      Gavin Li
 * @version     %I%, %G%
 * @since       1.0
 */
public class PistachiosClient {
	private Configuration conf = ConfigurationManager.getConfiguration();
	private static Logger logger = LoggerFactory.getLogger(PistachiosClient.class);
	final static MetricRegistry metrics = new MetricRegistry();
	final static JmxReporter reporter = JmxReporter.forRegistry(metrics).inDomain("pistachio.client.metrics").build();

	private final static Meter lookupFailureRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "lookupFailureRequests"));
	private final static Meter storeFailureRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "storeFailureRequests"));
	private final static Meter processFailureRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "processFailureRequests"));
	private final static Meter multiLookupFailureRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "multiLookupFailureRequests"));	
	private final static Meter multiLookupAsyncFailureRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "multiLookupAsyncFailureRequests"));	
	private final static Meter multiProcessAsyncFailureRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "multiProcessAsyncFailureRequests"));	
	private final static Meter storeAsyncFailureRequests = metrics.meter(MetricRegistry.name(PistachiosServer.class, "storeAsyncFailureRequests"));	

	private final static Timer lookupTimer = metrics.timer(MetricRegistry.name(PistachiosServer.class, "lookupTimer"));
	private final static Timer storeTimer = metrics.timer(MetricRegistry.name(PistachiosServer.class, "storeTimer"));
	private final static Timer processTimer = metrics.timer(MetricRegistry.name(PistachiosServer.class, "processTimer"));
	private final static Timer multiLookupTimer = metrics.timer(MetricRegistry.name(PistachiosServer.class, "multiLookupTimer"));
	private final static Timer multiLookupAsyncTimer = metrics.timer(MetricRegistry.name(PistachiosServer.class, "multiLookupAsyncTimer"));
	private final static Timer multiProcessAsyncTimer = metrics.timer(MetricRegistry.name(PistachiosServer.class, "multiLookupAsyncTimer"));
	private final static Timer storeAsyncTimer = metrics.timer(MetricRegistry.name(PistachiosServer.class, "storeAsyncTimer"));

    private PistachiosClientImpl clientImpl = new NettyPistachioClient();



	static {
		reporter.start();
        ZooKeeper zk =  null;
        try {
            if (ConfigurationManager.getConfiguration().getString("Pistachio.Processor.JarPath") != null &&
                ConfigurationManager.getConfiguration().getString("Pistachio.Processor.ClassName") != null) {
                zk = new ZooKeeper(ConfigurationManager.getConfiguration().getString("Pistachio.ZooKeeper.Server"),40000,null);
                zk.setData(ProcessorRegistry.PATH, (ConfigurationManager.getConfiguration().getString("Pistachio.Processor.JarPath") + ";" +
                        ConfigurationManager.getConfiguration().getString("Pistachio.Processor.ClassName")).getBytes(), -1);

            }
        } catch (Exception e) {
        } finally {
            try {
            if (zk != null)
                zk.close();
            } catch (Exception e) {
            }
        }
	}
	private int initialIntervalMillis = conf.getInt("Pistachio.AutoRetry.BackOff.InitialIntervalMillis", 100);
	private int maxElapsedTimeMillis = conf.getInt("Pistachio.AutoRetry.BackOff.MaxElapsedTimeMillis", 100 * 1000);
	private int maxIntervalMillis = conf.getInt("Pistachio.AutoRetry.BackOff.MaxIntervalMillis", 5000);
	private Boolean noMasterAutoRetry = conf.getBoolean("Pistachio.NoMasterAutoRetry", true);
	private Boolean connectionBrokenAutoRetry = conf.getBoolean("Pistachio.ConnectionBrokenAutoRetry", true);

    private class RetryWaiter {
        long backOffMillis =  0;
        BackOff backoff;
        Meter failureMeter;
        public RetryWaiter(Meter failureMeter0) {
            failureMeter = failureMeter0;

            backoff = (new ExponentialBackOff.Builder()).setInitialIntervalMillis(initialIntervalMillis)
                .setMaxElapsedTimeMillis(maxElapsedTimeMillis)
                .setMaxIntervalMillis(maxIntervalMillis)
                .build();
        }
        void waitBeforeRetry(Exception me) throws Exception {
            if (me instanceof MasterNotFoundException && !noMasterAutoRetry) {
                failureMeter.mark();
                throw me;
            }

            if (me instanceof ConnectionBrokenException && !connectionBrokenAutoRetry) {
                failureMeter.mark();
                throw me;
            }

            try{
                backOffMillis = backoff.nextBackOffMillis();
                if (backOffMillis == BackOff.STOP) {
                    failureMeter.mark();
                    throw me;
                }
                logger.debug("no master found, auto retry after sleeping {} ms", backOffMillis);
                Thread.sleep(backOffMillis);
            }catch(Exception e) {
            }
        }
    }

	public PistachiosClient() throws Exception {

	}

    /** 
     * To lookup the value of an id. Given the id return the value as a byte array.
     *
     * @param id        id to look up as byte[].
     * @return          <code>byte array</code> return in byte array, null if key not found
     * @exception       MasterNotFoundException when fail because no master found
     * @exception       ConnectionBrokenException when fail because connection is broken in the middle
     * @exception       Exception other errors indicating failure
     */
	public byte[] lookup(byte[] id) throws MasterNotFoundException, ConnectionBrokenException, Exception{

		final Timer.Context context = lookupTimer.time();
        RetryWaiter retryWaiter = new RetryWaiter(lookupFailureRequests);

		try {
			while (true) {
                try {
                    return clientImpl.lookup(id);
                } catch (Exception e) {
                    retryWaiter.waitBeforeRetry(e);
                }

            }

		} finally {
			context.stop();
		}

	}
	
    /** 
     * To lookup a list of ids. Given the id list return the values.
     *
     * @param ids       id to look up as list of byte[].
     * @return          <code>Map<byte[], byte[]></code> return in a map of values for each corresponding ids
     * @exception       MasterNotFoundException when fail because no master found
     * @exception       ConnectionBrokenException when fail because connection is broken in the middle
     * @exception       Exception other errors indicating failure
     */
	public Map<byte[], byte[]> multiLookUp(List<byte[]> ids) throws MasterNotFoundException, ConnectionBrokenException, Exception {
		final Timer.Context context = multiLookupTimer.time();
        RetryWaiter retryWaiter = new RetryWaiter(multiLookupFailureRequests);

		try {
			while (true) {
                try {
                    return clientImpl.multiLookup(ids);
                }catch (Exception e) {
                    retryWaiter.waitBeforeRetry(e);
                }
            }

		} finally {
			context.stop();
		}		
	}
	
    /** 
     * To lookup a list of ids asynchronously. Given the id list return the futures to get the values.
     *
     * @param ids       id to look up as list of byte[].
     * @return          <code>Map<byte[], Future<byte[]>></code> return in a map of futre of value for each corresponding ids
     * @exception       MasterNotFoundException when fail because no master found
     * @exception       ConnectionBrokenException when fail because connection is broken in the middle
     * @exception       Exception other errors indicating failure
     */
	public Map<byte[], Future<byte[]>> multiLookUpAsync(List<byte[]> ids) throws MasterNotFoundException, ConnectionBrokenException, Exception {
		final Timer.Context context = multiLookupAsyncTimer.time();
        RetryWaiter retryWaiter = new RetryWaiter(multiLookupAsyncFailureRequests);

		try {
			while (true) {
                try {
                    return clientImpl.multiLookupAsync(ids);
                }catch (Exception e) {
                    retryWaiter.waitBeforeRetry(e);
                }
            }
		} finally {
			context.stop();
		}		
	}

    /** 
     * To store the key value without calling callback.
     *
     * @param id        id to store as byte[].
     * @param value     value to store as byte array
     * @exception       MasterNotFoundException when fail because no master found
     * @exception       ConnectionBrokenException when fail because connection is broken in the middle
     * @exception       Exception other errors indicating failure
     * @return          <code>boolean</code> succeeded or not
     */
	public boolean store(byte[] id, byte[] value)  throws MasterNotFoundException, ConnectionBrokenException, Exception{
        return store(id, value, true);
    }

    /** 
     * To store the key value.
     *
     * @param id        id to store as byte[].
     * @param value     value to store as byte array
     * @param callback  does the registered callback need to be triggered
     * @exception       MasterNotFoundException when fail because no master found
     * @exception       ConnectionBrokenException when fail because connection is broken in the middle
     * @exception       Exception other errors indicating failure
     * @return          <code>boolean</code> succeeded or not
     */
	public boolean store(byte[] id, byte[] value, boolean callback)  throws MasterNotFoundException, ConnectionBrokenException, Exception{
		final Timer.Context context = storeTimer.time();
        RetryWaiter retryWaiter = new RetryWaiter(storeFailureRequests);

		try {
			while (true) {
                try {
                    return clientImpl.store(id, value, callback);
                }catch (Exception e) {
                    retryWaiter.waitBeforeRetry(e);
                }
            }
		} finally {
			context.stop();
		}
	}

    /** 
     * To close all the resource gracefully
     */
	public void close() {
        clientImpl.close();
    }

    /** 
     * To process a batch of events
     *
     * @param id        id to store as byte[].
     * @param events    list of events as byte []
     * @exception       MasterNotFoundException when fail because no master found
     * @exception       ConnectionBrokenException when fail because connection is broken in the middle
     * @exception       Exception other errors indicating failure
     * @return          <code>boolean</code> succeeded or not
     */
	public boolean processBatch(byte[] id, List<byte[]> events) throws MasterNotFoundException, ConnectionBrokenException, Exception{
		final Timer.Context context = processTimer.time();
        RetryWaiter retryWaiter = new RetryWaiter(processFailureRequests);

		try {
			while (true) {
                try {
                    return clientImpl.processBatch(id, events);
                }catch (Exception e) {
                    retryWaiter.waitBeforeRetry(e);
                }
            }
		} finally {
			context.stop();
		}
	}
	
    /** 
     * To process a batch of events
     *
     * @param events    Mapping keeps id (byte[]) -> event (byte[])
     * @return          <code>List<Future<Boolean>></code> succeeded or not
     * @exception       MasterNotFoundException when fail because no master found
     * @exception       ConnectionBrokenException when fail because connection is broken in the middle
     * @exception       Exception other errors indicating failure
     */
	public Map<byte[], Future<Boolean>> multiProcessAsync(Map<byte[], byte[]> events)  throws MasterNotFoundException, ConnectionBrokenException, Exception{
		final Timer.Context context = multiProcessAsyncTimer.time();
        RetryWaiter retryWaiter = new RetryWaiter(multiProcessAsyncFailureRequests);

		try {
			while (true) {
                try {
                    return clientImpl.multiProcessAsync(events);
                }catch (Exception e) {
                    retryWaiter.waitBeforeRetry(e);
                }
            }
		} finally {
			context.stop();
		}
	}
	
    /** 
     * To store the key value.
     *
     * @param id        id to store as byte[].
     * @param value     value to store as byte array
     * @return          <code>boolean</code> succeeded or not
     * @exception       MasterNotFoundException when fail because no master found
     * @exception       ConnectionBrokenException when fail because connection is broken in the middle
     * @exception       Exception other errors indicating failure
     */
	public Future<Boolean> storeAsync(byte[] id, byte[] value) throws MasterNotFoundException, ConnectionBrokenException, Exception{
		final Timer.Context context = storeAsyncTimer.time();
        RetryWaiter retryWaiter = new RetryWaiter(storeAsyncFailureRequests);

		try {
			while (true) {
                try {
                    return clientImpl.storeAsync(id, value);
                }catch (Exception e) {
                    retryWaiter.waitBeforeRetry(e);
                }
            }
		} finally {
			context.stop();
		}
	}

  public static void main(String [] args) {
	  PistachiosClient client = null;
      try {
          client = new PistachiosClient();
      }catch (Exception e) {
          logger.info("error creating clietn", e);
          if (client != null)
              client.close();
          return;
      }

      try {

          String id = "";
          boolean store = false;
          String value="" ;
          if (args.length ==2 && args[0].equals("lookup") ) {
              id = args[1];
              System.out.println("client.lookup(" + id + ")" + new String(client.lookup(id.getBytes())));
          } else if (args.length == 3 && args[0].equals("store") ) {
              id = args[1];
              store = true;
              value = args[2];
              client.store(id.getBytes(), value.getBytes());
          } else if (args.length == 3 && args[0].equals("processbatch") ) {
                  id = (args[1]);
              store = true;
              value = args[2];
              List list = new java.util.ArrayList();
              list.add(value.getBytes());
              client.processBatch(id.getBytes(), list);
          } else {
              System.out.println("USAGE: xxxx lookup id or xxxx store id value");
              System.exit(0);
          }
      } catch (Exception e) {
          System.out.println("error: "+ e);
      } finally {
          client.close();
      }




	/*
    if (args.length != 1) {
      System.out.println("Please enter 'simple' or 'secure'");
      System.exit(0);
    }
	*/

  }

}
