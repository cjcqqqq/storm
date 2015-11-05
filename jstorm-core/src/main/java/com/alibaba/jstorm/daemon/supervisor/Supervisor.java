/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.jstorm.daemon.supervisor;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import backtype.storm.Config;
import backtype.storm.messaging.IContext;
import backtype.storm.utils.LocalState;
import backtype.storm.utils.Utils;

import com.alibaba.jstorm.callback.AsyncLoopRunnable;
import com.alibaba.jstorm.callback.AsyncLoopThread;
import com.alibaba.jstorm.client.ConfigExtension;
import com.alibaba.jstorm.cluster.Cluster;
import com.alibaba.jstorm.cluster.Common;
import com.alibaba.jstorm.cluster.StormClusterState;
import com.alibaba.jstorm.cluster.StormConfig;
import com.alibaba.jstorm.daemon.worker.hearbeat.SyncContainerHb;
import com.alibaba.jstorm.event.EventManagerImp;
import com.alibaba.jstorm.event.EventManagerPusher;
import com.alibaba.jstorm.utils.JStormServerUtils;
import com.alibaba.jstorm.utils.JStormUtils;

/**
 * 
 * 
 * Supevisor workflow 1. write SupervisorInfo to ZK
 * 
 * 2. Every 10 seconds run SynchronizeSupervisor 2.1 download new topology 2.2
 * release useless worker 2.3 assgin new task to
 * /local-dir/supervisor/localstate 2.4 add one syncProcesses event
 * 
 * 3. Every supervisor.monitor.frequency.secs run SyncProcesses 3.1 kill useless
 * worker 3.2 start new worker
 * 
 * 4. create heartbeat thread every supervisor.heartbeat.frequency.secs, write
 * SupervisorInfo to ZK
 */

public class Supervisor {

    private static Logger LOG = LoggerFactory.getLogger(Supervisor.class);


    /**
     * create and start one supervisor
     * 
     * @param conf : configurationdefault.yaml storm.yaml
     * @param sharedContext : null (right now)
     * @return SupervisorManger: which is used to shutdown all workers and
     *         supervisor
     */
    @SuppressWarnings("rawtypes")
    public SupervisorManger mkSupervisor(Map conf, IContext sharedContext)
            throws Exception {

        LOG.info("Starting Supervisor with conf " + conf);

        /**
         * Step 1: cleanup all files in /storm-local-dir/supervisor/tmp
         */
        String path = StormConfig.supervisorTmpDir(conf);
        FileUtils.cleanDirectory(new File(path));

        /*
         * Step 2: create ZK operation instance StromClusterState
         */

        StormClusterState stormClusterState =
                Cluster.mk_storm_cluster_state(conf);

        /*
         * Step 3, create LocalStat LocalStat is one KV database 4.1 create
         * LocalState instance; 4.2 get supervisorId, if no supervisorId, create
         * one
         */

        LocalState localState = StormConfig.supervisorState(conf);

        String supervisorId = (String) localState.get(Common.LS_ID);
        if (supervisorId == null) {
            supervisorId = UUID.randomUUID().toString();
            localState.put(Common.LS_ID, supervisorId);
        }

        Vector<AsyncLoopThread> threads = new Vector<AsyncLoopThread>();

        // Step 5 create HeartBeat
        // every supervisor.heartbeat.frequency.secs, write SupervisorInfo to ZK
        // sync hearbeat to nimbus
        Heartbeat hb = new Heartbeat(conf, stormClusterState, supervisorId);
        hb.update();
        AsyncLoopThread heartbeat =
                new AsyncLoopThread(hb, false, null, Thread.MIN_PRIORITY, true);
        threads.add(heartbeat);

        // Sync heartbeat to Apsara Container
        AsyncLoopThread syncContainerHbThread =
                SyncContainerHb.mkSupervisorInstance(conf);
        if (syncContainerHbThread != null) {
            threads.add(syncContainerHbThread);
        }

        // Step 6 create and start sync Supervisor thread
        // every supervisor.monitor.frequency.secs second run SyncSupervisor
        EventManagerImp processEventManager = new EventManagerImp();
        AsyncLoopThread processEventThread =
                new AsyncLoopThread(processEventManager);
        threads.add(processEventThread);

        ConcurrentHashMap<String, String> workerThreadPids =
                new ConcurrentHashMap<String, String>();
        SyncProcessEvent syncProcessEvent =
                new SyncProcessEvent(supervisorId, conf, localState,
                        workerThreadPids, sharedContext);

        EventManagerImp syncSupEventManager = new EventManagerImp();
        AsyncLoopThread syncSupEventThread =
                new AsyncLoopThread(syncSupEventManager);
        threads.add(syncSupEventThread);

        SyncSupervisorEvent syncSupervisorEvent =
                new SyncSupervisorEvent(supervisorId, conf,
                        processEventManager, syncSupEventManager,
                        stormClusterState, localState, syncProcessEvent, hb);

        int syncFrequence =
                JStormUtils.parseInt(conf
                        .get(Config.SUPERVISOR_MONITOR_FREQUENCY_SECS));
        EventManagerPusher syncSupervisorPusher =
                new EventManagerPusher(syncSupEventManager,
                        syncSupervisorEvent, syncFrequence);
        AsyncLoopThread syncSupervisorThread =
                new AsyncLoopThread(syncSupervisorPusher);
        threads.add(syncSupervisorThread);

        Httpserver httpserver = null;
        if (StormConfig.local_mode(conf) == false) {
            // Step 7 start httpserver
            int port = ConfigExtension.getSupervisorDeamonHttpserverPort(conf);
            httpserver = new Httpserver(port, conf);
            httpserver.start();
        }

        // SupervisorManger which can shutdown all supervisor and workers
        return new SupervisorManger(conf, supervisorId, threads,
                syncSupEventManager, processEventManager, httpserver,
                stormClusterState, workerThreadPids);
    }

    /**
     * shutdown
     * 
     * @param supervisor
     */
    public void killSupervisor(SupervisorManger supervisor) {
        supervisor.shutdown();
    }

    private void initShutdownHook(SupervisorManger supervisor) {
        Runtime.getRuntime().addShutdownHook(new Thread(supervisor));
    }

    private void createPid(Map conf) throws Exception {
        String pidDir = StormConfig.supervisorPids(conf);

        JStormServerUtils.createPid(pidDir);
    }

    /**
     * start supervisor
     */
    public void run() {

        SupervisorManger supervisorManager = null;
        try {
            Map<Object, Object> conf = Utils.readStormConfig();

            StormConfig.validate_distributed_mode(conf);

            createPid(conf);

            supervisorManager = mkSupervisor(conf, null);

            JStormUtils.redirectOutput("/dev/null");

            initShutdownHook(supervisorManager);
            
            while (supervisorManager.isFinishShutdown() == false) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {

                }
            }

        } catch (Exception e) {
            LOG.error("Failed to start supervisor\n", e);
            System.exit(1);
        }finally {
        	LOG.info("Shutdown supervisor!!!");
        }

        
    }

    /**
     * supervisor daemon enter entrance
     * 
     * @param args
     */
    public static void main(String[] args) {

        JStormServerUtils.startTaobaoJvmMonitor();

        Supervisor instance = new Supervisor();

        instance.run();

    }

}