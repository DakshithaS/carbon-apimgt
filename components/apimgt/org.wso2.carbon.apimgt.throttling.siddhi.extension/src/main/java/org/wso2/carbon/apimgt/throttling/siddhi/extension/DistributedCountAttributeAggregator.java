/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.apimgt.throttling.siddhi.extension;

import java.util.AbstractMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.impl.dto.DistributedThrottleConfig;

import org.wso2.carbon.apimgt.throttling.siddhi.extension.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.throttling.siddhi.extension.util.kvstore.KeyValueStoreClient;
import org.wso2.carbon.apimgt.throttling.siddhi.extension.util.kvstore.KeyValueStoreException;
import org.wso2.carbon.apimgt.throttling.siddhi.extension.util.kvstore.KeyValueStoreManager;
import org.wso2.siddhi.core.config.ExecutionPlanContext;
import org.wso2.siddhi.core.executor.ExpressionExecutor;
import org.wso2.siddhi.core.query.selector.attribute.aggregator.AttributeAggregator;
import org.wso2.siddhi.core.query.selector.QuerySelector;
import org.wso2.siddhi.query.api.definition.Attribute;

public class DistributedCountAttributeAggregator extends AttributeAggregator {

    private static final Log log = LogFactory.getLog(DistributedCountAttributeAggregator.class);
    private static Attribute.Type type = Attribute.Type.LONG;
    private KeyValueStoreClient kvStoreClient;
    private String key;
    private final AtomicLong localCounter = new AtomicLong(0L);
    // Store the net change of local counter since last sync task
    private final AtomicLong unsyncedCounter = new AtomicLong(0L);
    private static final ConcurrentHashMap<String, DistributedCountAttributeAggregator> ACTIVE_AGGREGATORS =
            new ConcurrentHashMap<>();
    private final Object kvStoreLock = new Object();

    // Track the error logging status of syncing with key-value store
    private final AtomicLong lastErrorLogTimestamp = new AtomicLong(0L);
    private static final long ERROR_LOG_INTERVAL_MS = 30000L; // 30 seconds

    // Distributed throttling configs
    private static volatile DistributedThrottleConfig DISTRIBUTED_THROTTLE_CONFIG = null;
    private static boolean distributedThrottlingEnabled = false;
    private static int corePoolSize = 10;
    private static int kvStoreSyncIntervalMilliseconds = 10;

    // Scheduler initialization control
    private static volatile boolean schedulerStarted = false;
    private static final Object schedulerLock = new Object();

    // Static shared scheduler for all aggregators
    private static ScheduledExecutorService kvStoreSyncScheduler = null;
    private static final ScheduledExecutorService masterScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, Thread.currentThread().getName()));


    /**
     * The initialization method for FunctionExecutor
     *
     * @param attributeExpressionExecutors are the executors of each attributes in the function
     * @param executionPlanContext         Execution plan runtime context
     */
    @Override
    protected void init(ExpressionExecutor[] attributeExpressionExecutors, ExecutionPlanContext executionPlanContext) {
        if (DISTRIBUTED_THROTTLE_CONFIG == null) {
            synchronized (DistributedCountAttributeAggregator.class) {
                if (DISTRIBUTED_THROTTLE_CONFIG == null) {
                    DISTRIBUTED_THROTTLE_CONFIG = getDistributedThrottleConfig();
                }
            }
            if (DISTRIBUTED_THROTTLE_CONFIG != null) {
                distributedThrottlingEnabled = DISTRIBUTED_THROTTLE_CONFIG.isEnabled();
                corePoolSize = DISTRIBUTED_THROTTLE_CONFIG.getCorePoolSize();
                kvStoreSyncIntervalMilliseconds = DISTRIBUTED_THROTTLE_CONFIG.getSyncInterval();
            }
        }
        String throttleKey = QuerySelector.getThreadLocalGroupByKey();
        if (distributedThrottlingEnabled && !schedulerStarted && throttleKey != null) {
            startScheduler();
        }
        if (distributedThrottlingEnabled && throttleKey != null) {
            this.key = "wso2_throttler:" + throttleKey;
            try {
                this.kvStoreClient = KeyValueStoreManager.getClient();
                if (this.kvStoreClient != null) {
                    initializeFromKVStore();
                    ACTIVE_AGGREGATORS.put(key, this);
                }
            } catch (KeyValueStoreException e) {
                log.error("Failed to initialize KeyValueStoreClient for aggregator with key " + key, e);
                this.kvStoreClient = null;
            } catch (Exception e) {
                log.error("Unexpected error initializing KeyValueStoreClient for aggregator with key " + key, e);
                this.kvStoreClient = null;
            }
        }
    }

    /**
     * The method to initialize the local counter from the key-value store.
     * Initialize the value in key value store if it is not set.
     */
    private void initializeFromKVStore() {
        try {
            String kvStoreValue = kvStoreClient.get(key);
            if (kvStoreValue != null) {
                long initialValue = Long.parseLong(kvStoreValue);
                localCounter.set(initialValue);
            } else {
                kvStoreClient.set(key, "0");
            }
        } catch (Exception e) {
            log.error("Error initializing from key-value store for key " + key, e);
            localCounter.set(0L);
        }
    }

    /**
     * Synchronize the local counter with the key-value store.
     * Update the key-value store with the unsynced counter value.
     */
    private void syncWithKVStore() {
        synchronized (kvStoreLock) {
            if (kvStoreClient == null || key == null) {
                return;
            }
            long currentUnsyncedCount = unsyncedCounter.getAndSet(0L);
            try {
                if (currentUnsyncedCount == 0) {
                    localCounter.set(Long.parseLong(kvStoreClient.get(key)));
                } else if (currentUnsyncedCount > 0) {
                    localCounter.set(kvStoreClient.incrementBy(key, currentUnsyncedCount));
                } else {
                    localCounter.set(kvStoreClient.decrementBy(key, Math.abs(currentUnsyncedCount)));
                }
            } catch (KeyValueStoreException e) {
                long currentTimeMillis = System.currentTimeMillis();
                if (currentTimeMillis - lastErrorLogTimestamp.get() > ERROR_LOG_INTERVAL_MS) {
                    log.error("Error syncing with key-value store for the key " + key, e);
                    lastErrorLogTimestamp.set(currentTimeMillis);
                }
                unsyncedCounter.addAndGet(currentUnsyncedCount);
            }
        }
    }

    public Attribute.Type getReturnType() {
        return type;
    }

    /**
     * Process an add event by incrementing the local counter.
     * If distributed throttling is enabled, also increments the unsynced counter
     * which will be synchronized with the distributed key-value store.
     *
     * @param data The event data to be added.
     * @return The updated value of the local counter after increment.
     */
    @Override
    public Object processAdd(Object data) {
        try {
            localCounter.incrementAndGet();
            if (distributedThrottlingEnabled && kvStoreClient != null && key != null) {
                unsyncedCounter.incrementAndGet();
            }
            return localCounter.get();
        } catch (Exception e) {
            log.error("Error in processAdd for key " + key, e);
            return localCounter.get();
        }
    }

    @Override
    public Object processAdd(Object[] data) {
        return processAdd((Object) data);
    }


    /**
     * Process a remove event by decrementing the local counter.
     * If distributed throttling is enabled, also decrements the unsynced counter
     * which will be synchronized with the distributed key-value store.
     *
     * @param data The event data to be removed.
     * @return The updated value of the local counter after decrement.
     */
    @Override
    public Object processRemove(Object data) {
        try {
            localCounter.decrementAndGet();
            if (distributedThrottlingEnabled && kvStoreClient != null && key != null) {
                unsyncedCounter.decrementAndGet();
            }
            return localCounter.get();

        } catch (Exception e) {
            log.error("Error in processRemove for key " + key, e);
            return localCounter.get();
        }
    }

    @Override
    public Object processRemove(Object[] data) {
        return processRemove((Object) data);
    }


    /**
     * Resets the local counter to zero.
     * If distributed throttling is enabled, also resets the value in the distributed key-value store
     * and clears any pending unsynced changes.
     *
     * @return 0L after reset.
     */
    @Override
    public Object reset() {
        try {
            localCounter.set(0L);
            if (distributedThrottlingEnabled && kvStoreClient != null && key != null) {
                kvStoreClient.set(key, "0");
                unsyncedCounter.set(0L); // Clear pending changes
            }
            return 0L;

        } catch (KeyValueStoreException e) {
            log.error("Error resetting counter for key " + key, e);
            return 0L;
        }
    }

    @Override
    public void start() {
        //Nothing to start
    }

    /**
     * Stops the aggregator instance and performs cleanup.
     * Removes the aggregator from the active aggregator map, synchronizes any unsynced changes
     * with the key-value store, and shuts down the scheduler if there are no more active aggregators.
     * This method should be called when the aggregator is no longer needed.
     */
    @Override
    public void stop() {
        try {
            // Only remove if key is not null and distributed throttling is enabled
            if (distributedThrottlingEnabled && key != null) {
                ACTIVE_AGGREGATORS.remove(key);
                if (kvStoreClient != null) {
                    syncWithKVStore();
                }
                // Shutdown scheduler if no active aggregators exist
                if (ACTIVE_AGGREGATORS.isEmpty()) {
                    shutdownScheduler();
                }
            }
        } catch (Exception e) {
            log.error("Error during stop for key " + key, e);
        }
    }

    @Override
    public Object[] currentState() {
        if (distributedThrottlingEnabled && kvStoreClient != null && key != null) {
            try {
                syncWithKVStore();
            } catch (Exception e) {
                log.warn("Could not sync with key-value store before returning state for key " + key, e);
            }
        }
        return new Object[]{new AbstractMap.SimpleEntry<String, Object>("Value", localCounter.get())};
    }

    @Override
    public void restoreState(Object[] state) {
        Map.Entry<String, Object> stateEntry = (Map.Entry<String, Object>) state[0];
        long restoredValue = (Long) stateEntry.getValue();

        localCounter.set(restoredValue);
        unsyncedCounter.set(0L);

        if (distributedThrottlingEnabled && kvStoreClient != null && key != null) {
            try {
                kvStoreClient.set(key, String.valueOf(restoredValue));
            } catch (KeyValueStoreException e) {
                log.error("Error restoring state to key-value store for key "+ key, e);
            }
        }
    }

    private static DistributedThrottleConfig getDistributedThrottleConfig() {
        try {
            return ServiceReferenceHolder.getInstance()
                    .getAPIManagerConfigurationService()
                    .getAPIManagerConfiguration()
                    .getDistributedThrottleConfig();
        } catch (Exception e) {
            log.warn("Failed to load distributed throttle configuration from API Manager config. Using defaults.", e);
            return null;
        }
    }

    /**
     * Starts the scheduler responsible for periodically synchronizing all active aggregators
     * with the distributed key-value store. The scheduler runs at a fixed interval and submits
     * sync tasks for each active aggregator instance. This ensures that local counter changes
     * are propagated to the distributed store in a timely manner.
     * The scheduler is shared among all aggregator instances and is only started once.
     */
    private static void startScheduler() {
        if (!distributedThrottlingEnabled || schedulerStarted) {
            return;
        }
        synchronized (schedulerLock) {
            if (!distributedThrottlingEnabled || schedulerStarted) {
                return;
            }
            if (kvStoreSyncScheduler == null) {
                kvStoreSyncScheduler = Executors.newScheduledThreadPool(corePoolSize, r ->
                        new Thread(r, Thread.currentThread().getName()));
            }

            log.debug("Starting key-value store sync scheduler with interval: "
                    + kvStoreSyncIntervalMilliseconds + " ms, pool size: " + corePoolSize);

            masterScheduler.scheduleAtFixedRate(() -> {
                try {
                    CompletableFuture<?>[] futures = ACTIVE_AGGREGATORS.values().stream()
                            .map(aggregator -> CompletableFuture.runAsync(() -> {
                                try {
                                    aggregator.syncWithKVStore();
                                } catch (Throwable t) {
                                    log.error("Error syncing with key-value store for key " + aggregator.key, t);
                                }
                            }, kvStoreSyncScheduler))
                            .toArray(CompletableFuture[]::new);
                    if (futures.length == 0) {
                        return;
                    }
                    CompletableFuture.allOf(futures).join();

                } catch (Throwable t) {
                    log.error("Error in key-value store sync scheduler task", t);
                }
            }, kvStoreSyncIntervalMilliseconds, kvStoreSyncIntervalMilliseconds, TimeUnit.MILLISECONDS);

            schedulerStarted = true;
        }
    }

    /**
     * Shuts down the key-value store sync scheduler and the KeyValueStoreManager.
     * This method is called when there are no active aggregators remaining.
     * It ensures that all scheduled sync tasks are stopped and resources such as thread pools
     * and key-value store connections are properly released.
     */
    public static void shutdownScheduler() {
        synchronized (schedulerLock) {
            if (kvStoreSyncScheduler != null && !kvStoreSyncScheduler.isShutdown()) {
                log.debug("Shutting down key-value store sync scheduler...");
                kvStoreSyncScheduler.shutdown();
                try {
                    if (!kvStoreSyncScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.warn("The key-value store sync scheduler did not terminate in time. Forcing shutdown...");
                        kvStoreSyncScheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    log.warn("Interrupted while shutting down key-value store sync scheduler. Forcing shutdown...");
                    kvStoreSyncScheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            kvStoreSyncScheduler = null;
            schedulerStarted = false;

            // Shutdown the KeyValueStoreManager to close JedisPool
            try {
                KeyValueStoreManager.shutdown();
            } catch (Exception e) {
                log.error("Error shutting down KeyValueStoreManager", e);
            }
        }
    }

}

