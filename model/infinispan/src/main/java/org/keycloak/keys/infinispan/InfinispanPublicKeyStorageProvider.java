/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.keys.infinispan;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import org.infinispan.Cache;
import org.jboss.logging.Logger;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.common.util.Time;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.crypto.PublicKeysWrapper;
import org.keycloak.keys.PublicKeyLoader;
import org.keycloak.keys.PublicKeyStorageProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransaction;


/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class InfinispanPublicKeyStorageProvider implements PublicKeyStorageProvider {

    private static final Logger log = Logger.getLogger(InfinispanPublicKeyStorageProvider.class);

    private final KeycloakSession session;

    private final Cache<String, PublicKeysEntry> keys;

    private final Map<String, FutureTask<PublicKeysEntry>> tasksInProgress;

    private final int minTimeBetweenRequests ;

    private Set<String> invalidations = new HashSet<>();

    private boolean transactionEnlisted = false;

    public InfinispanPublicKeyStorageProvider(KeycloakSession session, Cache<String, PublicKeysEntry> keys, Map<String, FutureTask<PublicKeysEntry>> tasksInProgress, int minTimeBetweenRequests) {
        this.session = session;
        this.keys = keys;
        this.tasksInProgress = tasksInProgress;
        this.minTimeBetweenRequests = minTimeBetweenRequests;
    }

    void addInvalidation(String cacheKey) {
        if (!transactionEnlisted) {
            session.getTransactionManager().enlistAfterCompletion(getAfterTransaction());
            transactionEnlisted = true;
        }

        this.invalidations.add(cacheKey);
    }


    protected KeycloakTransaction getAfterTransaction() {
        return new KeycloakTransaction() {

            @Override
            public void begin() {
            }

            @Override
            public void commit() {
                runInvalidations();
            }

            @Override
            public void rollback() {
                runInvalidations();
            }

            @Override
            public void setRollbackOnly() {
            }

            @Override
            public boolean getRollbackOnly() {
                return false;
            }

            @Override
            public boolean isActive() {
                return true;
            }
        };
    }


    protected void runInvalidations() {
        ClusterProvider cluster = session.getProvider(ClusterProvider.class);

        for (String cacheKey : invalidations) {
            keys.remove(cacheKey);
            cluster.notify(InfinispanCachePublicKeyProviderFactory.PUBLIC_KEY_STORAGE_INVALIDATION_EVENT, PublicKeyStorageInvalidationEvent.create(cacheKey), true, ClusterProvider.DCNotify.ALL_DCS);
        }
    }

    @Override
    public KeyWrapper getFirstPublicKey(String modelKey, String algorithm, PublicKeyLoader loader) {
        return getPublicKey(modelKey, null, algorithm, loader);
    }

    @Override
    public KeyWrapper getPublicKey(String modelKey, String kid, String algorithm, PublicKeyLoader loader) {
        PublicKeysEntry entry = keys.get(modelKey);
        int lastRequestTime = entry==null ? 0 : entry.getLastRequestTime();
        int currentTime = Time.currentTime();
        boolean isSendingRequestAllowed = currentTime > lastRequestTime + minTimeBetweenRequests;

        // Check if key is in cache, but only if KID is provided or if the key cache has been loaded recently,
        // in order to get a key based on partial match with alg param.
        if (entry != null && (kid != null || !isSendingRequestAllowed)) {
            KeyWrapper publicKey = entry.getCurrentKeys().getKeyByKidAndAlg(kid, algorithm);
            if (publicKey != null) {
                // return a copy of the key to not modify the cached one
                return publicKey.cloneKey();
            }
        }

        // Check if we are allowed to send request
        if (isSendingRequestAllowed) {
            WrapperCallable wrapperCallable = new WrapperCallable(modelKey, loader);
            FutureTask<PublicKeysEntry> task = new FutureTask<>(wrapperCallable);
            FutureTask<PublicKeysEntry> existing = tasksInProgress.putIfAbsent(modelKey, task);

            if (existing == null) {
                task.run();
            } else {
                task = existing;
            }

            try {
                entry = task.get();

                // Computation finished. Let's see if key is available
                KeyWrapper publicKey = entry.getCurrentKeys().getKeyByKidAndAlg(kid, algorithm);
                if (publicKey != null) {
                    // return a copy of the key to not modify the cached one
                    return publicKey.cloneKey();
                }

            } catch (ExecutionException ee) {
                throw new RuntimeException("Error when loading public keys: " + ee.getMessage(), ee);
            } catch (InterruptedException ie) {
                throw new RuntimeException("Error. Interrupted when loading public keys", ie);
            } finally {
                // Our thread inserted the task. Let's clean
                if (existing == null) {
                    tasksInProgress.remove(modelKey);
                }
            }
        } else {
            log.warnf("Won't load the keys for model '%s' . Last request time was %d", modelKey, lastRequestTime);
        }

        List<String> availableKids = entry==null ? Collections.emptyList() : entry.getCurrentKeys().getKids();
        log.warnf("PublicKey wasn't found in the storage. Requested kid: '%s' . Available kids: '%s'", kid, availableKids);

        return null;
    }

    @Override
    public void close() {

    }

    private class WrapperCallable implements Callable<PublicKeysEntry> {

        private final String modelKey;
        private final PublicKeyLoader delegate;

        public WrapperCallable(String modelKey, PublicKeyLoader delegate) {
            this.modelKey = modelKey;
            this.delegate = delegate;
        }

        @Override
        public PublicKeysEntry call() throws Exception {
            PublicKeysEntry entry = keys.get(modelKey);

            int lastRequestTime = entry==null ? 0 : entry.getLastRequestTime();
            int currentTime = Time.currentTime();

            // Check again if we are allowed to send request. There is a chance other task was already finished and removed from tasksInProgress in the meantime.
            if (currentTime > lastRequestTime + minTimeBetweenRequests) {

                PublicKeysWrapper publicKeys = delegate.loadKeys();

                if (log.isDebugEnabled()) {
                    log.debugf("Public keys retrieved successfully for model %s. New kids: %s", modelKey, publicKeys.getKids());
                }

                entry = new PublicKeysEntry(currentTime, publicKeys);

                keys.put(modelKey, entry);
            }
            return entry;
        }
    }
}
