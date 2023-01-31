/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.replication.logical.repository;

import static io.crate.replication.logical.LogicalReplicationSettings.PUBLISHER_INDEX_UUID;
import static io.crate.replication.logical.LogicalReplicationSettings.REPLICATION_SUBSCRIPTION_NAME;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.IndexCommit;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.StepListener;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexTemplateMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.RepositoryMetadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.snapshots.IndexShardSnapshotStatus;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.indices.recovery.RecoveryState;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.repositories.RepositoryData;
import org.elasticsearch.repositories.ShardGenerations;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.snapshots.SnapshotInfo;
import org.elasticsearch.snapshots.SnapshotState;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.RemoteClusters;

import io.crate.common.unit.TimeValue;
import io.crate.exceptions.SQLExceptions;
import io.crate.replication.logical.LogicalReplicationService;
import io.crate.replication.logical.LogicalReplicationSettings;
import io.crate.replication.logical.action.GetStoreMetadataAction;
import io.crate.replication.logical.action.PublicationsStateAction;
import io.crate.replication.logical.action.PublicationsStateAction.Response;
import io.crate.replication.logical.action.ReleasePublisherResourcesAction;
import io.crate.replication.logical.metadata.ConnectionInfo;

/**
 * Derived from org.opensearch.replication.repository.RemoteClusterRepository
 */
public class LogicalReplicationRepository extends AbstractLifecycleComponent implements Repository {

    private static final Logger LOGGER = LogManager.getLogger(LogicalReplicationRepository.class);

    public static final String TYPE = "logical_replication";
    public static final String LATEST = "_latest_";
    public static final String REMOTE_REPOSITORY_PREFIX = "_logical_replication_";
    private static final SnapshotId SNAPSHOT_ID = new SnapshotId(LATEST, LATEST);
    public static final long REMOTE_CLUSTER_REPO_REQ_TIMEOUT_IN_MILLI_SEC = 60000L;

    private final LogicalReplicationService logicalReplicationService;
    private final RepositoryMetadata metadata;
    private final String subscriptionName;
    private final ThreadPool threadPool;
    private final ClusterService clusterService;
    private final RemoteClusters remoteClusters;
    private final LogicalReplicationSettings replicationSettings;

    public LogicalReplicationRepository(ClusterService clusterService,
                                        LogicalReplicationService logicalReplicationService,
                                        RemoteClusters remoteClusters,
                                        RepositoryMetadata metadata,
                                        ThreadPool threadPool,
                                        LogicalReplicationSettings replicationSettings) {
        this.clusterService = clusterService;
        this.logicalReplicationService = logicalReplicationService;
        this.remoteClusters = remoteClusters;
        this.metadata = metadata;
        assert metadata.name().startsWith(REMOTE_REPOSITORY_PREFIX)
            : "SubscriptionRepository metadata.name() must start with: " + REMOTE_REPOSITORY_PREFIX;
        //noinspection ConstantConditions
        this.subscriptionName = Strings.split(metadata.name(), REMOTE_REPOSITORY_PREFIX)[1];
        this.threadPool = threadPool;
        this.replicationSettings = replicationSettings;
    }

    @Override
    protected void doStart() {

    }

    @Override
    protected void doStop() {

    }

    @Override
    protected void doClose() throws IOException {

    }

    @Override
    public RepositoryMetadata getMetadata() {
        return metadata;
    }

    @Override
    public CompletableFuture<SnapshotInfo> getSnapshotInfo(SnapshotId snapshotId) {
        assert SNAPSHOT_ID.equals(snapshotId) : "SubscriptionRepository only supports " + SNAPSHOT_ID + " as the SnapshotId";
        return getPublicationsState()
            .thenApply(stateResponse ->
                new SnapshotInfo(snapshotId, stateResponse.concreteIndices(), SnapshotState.SUCCESS, Version.CURRENT));
    }

    @Override
    public void getSnapshotInfo(SnapshotId snapshotId, ActionListener<SnapshotInfo> listener) {
        getSnapshotInfo(snapshotId).whenComplete(listener);
    }

    @Override
    public CompletableFuture<Metadata> getSnapshotGlobalMetadata(SnapshotId snapshotId) {
        return getPublicationsState()
            .thenCompose(stateResponse -> {
                var indices = stateResponse.concreteIndices().toArray(new String[0]);
                var templates = stateResponse.concreteTemplates().toArray(new String[0]);
                return getRemoteClusterState(false, true, indices, templates);
            })
            .thenApply(remoteClusterStateResp -> {
                ClusterState remoteClusterState = remoteClusterStateResp.getState();
                var metadataBuilder = Metadata.builder(remoteClusterState.metadata());
                for (var cursor : remoteClusterState.metadata().templates().values()) {
                    // Add subscription name as a setting value which can be used to restrict operations on
                    // partitioned tables (e.g. forbid writes/creating new partitions)
                    var settings = Settings.builder()
                        .put(cursor.value.settings())
                        .put(REPLICATION_SUBSCRIPTION_NAME.getKey(), subscriptionName)
                        .build();
                    var templateMetadata = new IndexTemplateMetadata.Builder(cursor.value)
                        .settings(settings);
                    metadataBuilder.put(templateMetadata);
                }
                return metadataBuilder.build();
            });
    }

    @Override
    public CompletableFuture<Collection<IndexMetadata>> getSnapshotIndexMetadata(RepositoryData repositoryData,
                                                                                 SnapshotId snapshotId,
                                                                                 Collection<IndexId> indexIds) {
        assert SNAPSHOT_ID.equals(snapshotId) : "SubscriptionRepository only supports " + SNAPSHOT_ID + " as the SnapshotId";
        var remoteIndices = indexIds.stream().map(IndexId::getName).toArray(String[]::new);
        return getRemoteClusterState(remoteIndices).thenApply(response -> {
            var result = new ArrayList<IndexMetadata>();
            ClusterState remoteClusterState = response.getState();
            for (var i : remoteClusterState.metadata().indices()) {
                if (remoteClusterState.routingTable().index(i.key).allPrimaryShardsActive() == false) {
                    // skip indices where not all shards are active yet, restore will fail if primaries are not (yet) assigned
                    continue;
                }
                var indexMetadata = i.value;
                // Add replication specific settings, this setting will trigger a custom engine, see {@link SQLPlugin#getEngineFactory}
                var builder = Settings.builder().put(indexMetadata.getSettings());
                builder.put(REPLICATION_SUBSCRIPTION_NAME.getKey(), subscriptionName);
                // Store publishers original index UUID to be able to resolve the original index later on
                builder.put(PUBLISHER_INDEX_UUID.getKey(), indexMetadata.getIndexUUID());

                var indexMdBuilder = IndexMetadata.builder(indexMetadata).settings(builder);
                indexMetadata.getAliases().valuesIt().forEachRemaining(a -> indexMdBuilder.putAlias(a));
                result.add(indexMdBuilder.build());
            }
            return result;
        });
    }

    public CompletableFuture<IndexMetadata> getSnapshotIndexMetadata(RepositoryData repositoryData,
                                                                     SnapshotId snapshotId,
                                                                     IndexId index) {
        assert SNAPSHOT_ID.equals(snapshotId) : "SubscriptionRepository only supports " + SNAPSHOT_ID + " as the SnapshotId";
        return getRemoteClusterState(index.getName()).thenApply(response -> {
            ClusterState remoteClusterState = response.getState();
            var indexMetadata = remoteClusterState.metadata().index(index.getName());
            // Add replication specific settings, this setting will trigger a custom engine, see {@link SQLPlugin#getEngineFactory}
            var builder = Settings.builder().put(indexMetadata.getSettings());
            builder.put(REPLICATION_SUBSCRIPTION_NAME.getKey(), subscriptionName);
            // Store publishers original index UUID to be able to resolve the original index later on
            builder.put(PUBLISHER_INDEX_UUID.getKey(), indexMetadata.getIndexUUID());

            var indexMdBuilder = IndexMetadata.builder(indexMetadata).settings(builder);
            indexMetadata.getAliases().valuesIt().forEachRemaining(a -> indexMdBuilder.putAlias(a));
            return indexMdBuilder.build();
        });
    }

    @Override
    public void getRepositoryData(ActionListener<RepositoryData> listener) {
        StepListener<PublicationsStateAction.Response> responseStepListener = new StepListener<>();
        responseStepListener.whenComplete(stateResponse -> {
            StepListener<ClusterState> clusterStateStepListener = new StepListener<>();
            getRemoteClusterState(
                false,
                false,
                clusterStateStepListener,
                stateResponse.concreteIndices().toArray(new String[0]),
                stateResponse.concreteTemplates().toArray(new String[0])
            );
            clusterStateStepListener.whenComplete(remoteClusterState -> {
                var remoteMetadata = remoteClusterState.metadata();
                var shardGenerations = ShardGenerations.builder();

                var it = remoteMetadata.getIndices().valuesIt();
                while (it.hasNext()) {
                    var indexMetadata = it.next();
                    var indexId = new IndexId(indexMetadata.getIndex().getName(), indexMetadata.getIndexUUID());
                    for (int i = 0; i < indexMetadata.getNumberOfShards(); i++) {
                        shardGenerations.put(indexId, i, "dummy");
                    }
                }
                var repositoryData = RepositoryData.EMPTY.addSnapshot(SNAPSHOT_ID,
                                                                      SnapshotState.SUCCESS,
                                                                      Version.CURRENT,
                                                                      shardGenerations.build(),
                                                                      null,
                                                                      null);
                listener.onResponse(repositoryData);
            }, listener::onFailure);
        }, listener::onFailure);
        getPublicationsState().whenComplete((ActionListener<Response>) responseStepListener);
    }

    @Override
    public void finalizeSnapshot(final ShardGenerations shardGenerations,
                                 final long repositoryStateId,
                                 final Metadata clusterMetadata,
                                 SnapshotInfo snapshotInfo,
                                 Version repositoryMetaVersion,
                                 Function<ClusterState, ClusterState> stateTransformer,
                                 final ActionListener<RepositoryData> listener) {
        throw new UnsupportedOperationException("Operation not permitted");
    }

    @Override
    public void deleteSnapshots(Collection<SnapshotId> snapshotId,
                               long repositoryStateId,
                               Version repositoryMetaVersion,
                               ActionListener<RepositoryData> listener) {
        throw new UnsupportedOperationException("Operation not permitted");
    }

    @Override
    public String startVerification() {
        throw new UnsupportedOperationException("Operation not permitted");
    }

    @Override
    public void endVerification(String verificationToken) {
        throw new UnsupportedOperationException("Operation not permitted");
    }

    @Override
    public void verify(String verificationToken, DiscoveryNode localNode) {
        throw new UnsupportedOperationException("Operation not permitted");
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public void snapshotShard(Store store,
                              MapperService mapperService,
                              SnapshotId snapshotId,
                              IndexId indexId,
                              IndexCommit snapshotIndexCommit,
                              @Nullable String shardStateIdentifier,
                              IndexShardSnapshotStatus snapshotStatus,
                              Version repositoryMetaVersion,
                              ActionListener<String> listener) {
        throw new UnsupportedOperationException("Operation not permitted");
    }

    @Override
    public void updateState(ClusterState state) {
        throw new UnsupportedOperationException("Operation not permitted");
    }


    @Override
    public void executeConsistentStateUpdate(Function<RepositoryData,
                                      ClusterStateUpdateTask> createUpdateTask, String source,
                                      Consumer<Exception> onFailure) {
        throw new UnsupportedOperationException("Operation not permitted");
    }

    @Override
    public void restoreShard(Store store,
                             SnapshotId snapshotId,
                             IndexId indexId,
                             ShardId snapshotShardId,
                             RecoveryState recoveryState,
                             ActionListener<Void> listener) {
        store.incRef();
        var releaseListener = new ActionListener<Void>() {

            @Override
            public void onResponse(Void response) {
                store.decRef();
                listener.onResponse(response);
            }

            @Override
            public void onFailure(Exception e) {
                store.decRef();
                listener.onFailure(e);
            }
        };
        restoreShardUsingMultiChunkTransfer(store, indexId, snapshotShardId, recoveryState, releaseListener);

    }

    private void restoreShardUsingMultiChunkTransfer(Store store,
                                                     IndexId indexId,
                                                     ShardId snapshotShardId,
                                                     RecoveryState recoveryState,
                                                     ActionListener<Void> listener) {
        // 1. Get all the files info from the publisher cluster for this shardId
        StepListener<ClusterState> clusterStateStepListener = new StepListener<>();
        getRemoteClusterState(true, true, clusterStateStepListener, new String[]{indexId.getName()}, Strings.EMPTY_ARRAY);
        clusterStateStepListener.whenComplete(publisherClusterState -> {
            var publisherShardRouting = publisherClusterState.routingTable()
                .shardRoutingTable(
                    snapshotShardId.getIndexName(),
                    snapshotShardId.getId()
                )
                .primaryShard();
            var publisherShardNode = publisherClusterState.nodes().get(publisherShardRouting.currentNodeId());
            // Get the index UUID of the publisher cluster for the metadata request
            var shardId = new ShardId(
                snapshotShardId.getIndexName(),
                publisherClusterState.metadata().index(indexId.getName()).getIndexUUID(),
                snapshotShardId.getId()
            );
            var restoreUUID = UUIDs.randomBase64UUID();
            var getStoreMetadataRequest = new GetStoreMetadataAction.Request(
                restoreUUID,
                publisherShardNode,
                shardId,
                clusterService.getClusterName().value()
            );

            var remoteClient = getRemoteClient();

            // Gets the remote store metadata
            StepListener<GetStoreMetadataAction.Response> responseStepListener = new StepListener<>();
            remoteClient.execute(GetStoreMetadataAction.INSTANCE, getStoreMetadataRequest)
                .whenComplete(responseStepListener);
            responseStepListener.whenComplete(metadataResponse -> {
                var metadataSnapshot = metadataResponse.metadataSnapshot();

                // 2. Request for individual files from publisher cluster for this shardId
                // make sure the store is not released until we are done.
                var fileMetadata = new ArrayList<>(metadataSnapshot.asMap().values());
                var multiChunkTransfer = new RemoteClusterMultiChunkTransfer(
                    LOGGER,
                    clusterService.getClusterName().value(),
                    store,
                    replicationSettings.maxConcurrentFileChunks(),
                    restoreUUID,
                    //metadata,
                    publisherShardNode,
                    shardId,
                    fileMetadata,
                    remoteClient,
                    threadPool,
                    recoveryState,
                    replicationSettings.recoveryChunkSize(),
                    new ActionListener<>() {
                        @Override
                        public void onResponse(Void unused) {
                            LOGGER.info("Restore successful for {}", store.shardId());
                            releasePublisherResources(remoteClient, restoreUUID, publisherShardNode, shardId);
                            recoveryState.getIndex().setFileDetailsComplete();
                            listener.onResponse(null);
                        }

                        @Override
                        public void onFailure(Exception e) {
                            LOGGER.error("Restore of " + store.shardId() + " failed due to ", e);
                            releasePublisherResources(remoteClient, restoreUUID, publisherShardNode, shardId);
                            listener.onFailure(e);
                        }
                    }
                );
                if (fileMetadata.isEmpty()) {
                    LOGGER.info("Initializing with empty store for shard: {}", shardId.getId());
                    try {
                        store.createEmpty(store.indexSettings().getIndexVersionCreated().luceneVersion);
                        listener.onResponse(null);
                    } catch (IOException e) {
                        listener.onFailure(new UncheckedIOException(e));
                    } finally {
                        releasePublisherResources(remoteClient, restoreUUID, publisherShardNode, shardId);
                    }
                } else {
                    multiChunkTransfer.start();
                }
            }, listener::onFailure);
        }, listener::onFailure);
    }

    private Client getRemoteClient() {
        return remoteClusters.getClient(subscriptionName);
    }

    private CompletableFuture<ClusterStateResponse> getRemoteClusterState(String... remoteIndices) {
        return getRemoteClusterState(false, true, remoteIndices, Strings.EMPTY_ARRAY);
    }

    private CompletableFuture<ClusterStateResponse> getRemoteClusterState(boolean includeNodes,
                                                                          boolean includeRouting,
                                                                          String[] remoteIndices,
                                                                          String[] remoteTemplates) {
        Client remoteClient = getRemoteClient();
        var clusterStateRequest = new ClusterStateRequest()
            .indices(remoteIndices)
            .templates(remoteTemplates)
            .metadata(true)
            .nodes(includeNodes)
            .routingTable(includeRouting)
            .indicesOptions(IndicesOptions.strictSingleIndexNoExpandForbidClosed())
            .waitForTimeout(new TimeValue(REMOTE_CLUSTER_REPO_REQ_TIMEOUT_IN_MILLI_SEC));
        return remoteClient.admin().cluster().state(clusterStateRequest);
    }

    private void getRemoteClusterState(boolean includeNodes,
                                       boolean includeRouting,
                                       ActionListener<ClusterState> listener,
                                       String[] remoteIndices,
                                       String[] remoteTemplates) {
        getRemoteClusterState(includeNodes, includeRouting, remoteIndices, remoteTemplates)
            .thenApply(response -> response.getState())
            .whenComplete(listener);
    }

    private CompletableFuture<Response> getPublicationsState() {
        return getRemoteClient().execute(
            PublicationsStateAction.INSTANCE,
            new PublicationsStateAction.Request(
                logicalReplicationService.subscriptions().get(subscriptionName).publications(),
                logicalReplicationService.subscriptions().get(subscriptionName).connectionInfo().settings().get(ConnectionInfo.USERNAME.getKey())
            ));
    }

    private void releasePublisherResources(Client remoteClient,
                                           String restoreUUID,
                                           DiscoveryNode publisherShardNode,
                                           ShardId shardId) {
        var releaseResourcesReq = new ReleasePublisherResourcesAction.Request(
            restoreUUID,
            publisherShardNode,
            shardId,
            clusterService.getClusterName().value()
        );
        remoteClient.execute(ReleasePublisherResourcesAction.INSTANCE, releaseResourcesReq)
            .whenComplete((response, err) -> {
                if (err == null) {
                    if (response.isAcknowledged()) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Successfully released resources at the publisher cluster for {} at {}",
                                            shardId,
                                            publisherShardNode
                            );
                        }
                    }
                } else {
                    LOGGER.error("Releasing publisher resource failed due to ", SQLExceptions.unwrap(err));
                }
            });
    }

    @Override
    public CompletableFuture<IndexShardSnapshotStatus> getShardSnapshotStatus(SnapshotId snapshotId, IndexId indexId, ShardId shardId) {
        // Should implement this and disallow null in InternalSnapshotsInfoService
        return CompletableFuture.completedFuture(null);
    }
}
