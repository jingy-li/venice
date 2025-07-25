package com.linkedin.davinci.kafka.consumer;

import static com.linkedin.davinci.kafka.consumer.LeaderFollowerStateType.LEADER;
import static com.linkedin.venice.VeniceConstants.REWIND_TIME_DECIDED_BY_SERVER;
import static com.linkedin.venice.writer.VeniceWriter.APP_DEFAULT_LOGICAL_TS;

import com.linkedin.davinci.client.DaVinciRecordTransformerConfig;
import com.linkedin.davinci.config.VeniceServerConfig;
import com.linkedin.davinci.config.VeniceStoreVersionConfig;
import com.linkedin.davinci.ingestion.utils.IngestionTaskReusableObjects;
import com.linkedin.davinci.replication.RmdWithValueSchemaId;
import com.linkedin.davinci.replication.merge.MergeConflictResolver;
import com.linkedin.davinci.replication.merge.MergeConflictResolverFactory;
import com.linkedin.davinci.replication.merge.MergeConflictResult;
import com.linkedin.davinci.replication.merge.RmdSerDe;
import com.linkedin.davinci.replication.merge.StringAnnotatedStoreSchemaCache;
import com.linkedin.davinci.stats.AggVersionedIngestionStats;
import com.linkedin.davinci.storage.StorageService;
import com.linkedin.davinci.storage.chunking.ChunkedValueManifestContainer;
import com.linkedin.davinci.storage.chunking.RawBytesChunkingAdapter;
import com.linkedin.davinci.storage.chunking.SingleGetChunkingAdapter;
import com.linkedin.davinci.store.cache.backend.ObjectCacheBackend;
import com.linkedin.davinci.store.record.ByteBufferValueRecord;
import com.linkedin.davinci.store.record.ValueRecord;
import com.linkedin.davinci.utils.ByteArrayKey;
import com.linkedin.venice.exceptions.PersistenceFailureException;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.exceptions.VeniceMessageException;
import com.linkedin.venice.kafka.protocol.ControlMessage;
import com.linkedin.venice.kafka.protocol.Delete;
import com.linkedin.venice.kafka.protocol.KafkaMessageEnvelope;
import com.linkedin.venice.kafka.protocol.Put;
import com.linkedin.venice.kafka.protocol.TopicSwitch;
import com.linkedin.venice.kafka.protocol.Update;
import com.linkedin.venice.kafka.protocol.enums.ControlMessageType;
import com.linkedin.venice.kafka.protocol.enums.MessageType;
import com.linkedin.venice.kafka.protocol.state.StoreVersionState;
import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.offsets.OffsetRecord;
import com.linkedin.venice.pubsub.PubSubConstants;
import com.linkedin.venice.pubsub.api.DefaultPubSubMessage;
import com.linkedin.venice.pubsub.api.PubSubMessage;
import com.linkedin.venice.pubsub.api.PubSubTopic;
import com.linkedin.venice.pubsub.api.PubSubTopicPartition;
import com.linkedin.venice.schema.rmd.RmdUtils;
import com.linkedin.venice.serialization.RawBytesStoreDeserializerCache;
import com.linkedin.venice.storage.protocol.ChunkedValueManifest;
import com.linkedin.venice.utils.ByteUtils;
import com.linkedin.venice.utils.LatencyUtils;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.lazy.Lazy;
import com.linkedin.venice.writer.ChunkAwareCallback;
import com.linkedin.venice.writer.DeleteMetadata;
import com.linkedin.venice.writer.LeaderMetadataWrapper;
import com.linkedin.venice.writer.PutMetadata;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * This class contains logic that SNs must perform if a store-version is running in Active/Active mode.
 */
public class ActiveActiveStoreIngestionTask extends LeaderFollowerStoreIngestionTask {
  private static final Logger LOGGER = LogManager.getLogger(ActiveActiveStoreIngestionTask.class);

  private final int rmdProtocolVersionId;
  private final MergeConflictResolver mergeConflictResolver;
  private final RmdSerDe rmdSerDe;
  private final Lazy<KeyLevelLocksManager> keyLevelLocksManager;
  private final AggVersionedIngestionStats aggVersionedIngestionStats;
  private final RemoteIngestionRepairService remoteIngestionRepairService;
  private final Lazy<IngestionBatchProcessor> ingestionBatchProcessorLazy;

  private final Supplier<IngestionTaskReusableObjects> reusableObjectsSupplier;

  public ActiveActiveStoreIngestionTask(
      StorageService storageService,
      StoreIngestionTaskFactory.Builder builder,
      Store store,
      Version version,
      Properties kafkaConsumerProperties,
      BooleanSupplier isCurrentVersion,
      VeniceStoreVersionConfig storeConfig,
      int errorPartitionId,
      boolean isIsolatedIngestion,
      Optional<ObjectCacheBackend> cacheBackend,
      DaVinciRecordTransformerConfig recordTransformerConfig,
      Lazy<ZKHelixAdmin> zkHelixAdmin) {
    super(
        storageService,
        builder,
        store,
        version,
        kafkaConsumerProperties,
        isCurrentVersion,
        storeConfig,
        errorPartitionId,
        isIsolatedIngestion,
        cacheBackend,
        recordTransformerConfig,
        zkHelixAdmin);

    this.rmdProtocolVersionId = version.getRmdVersionId();

    this.aggVersionedIngestionStats = versionedIngestionStats;
    int knownKafkaClusterNumber = serverConfig.getKafkaClusterIdToUrlMap().size();

    int initialPoolSize = knownKafkaClusterNumber + 1;
    this.keyLevelLocksManager = Lazy.of(
        () -> new KeyLevelLocksManager(
            getVersionTopic().getName(),
            initialPoolSize,
            getKeyLevelLockMaxPoolSizeBasedOnServerConfig(serverConfig, storeVersionPartitionCount)));
    StringAnnotatedStoreSchemaCache annotatedReadOnlySchemaRepository =
        new StringAnnotatedStoreSchemaCache(storeName, schemaRepository);

    this.rmdSerDe = new RmdSerDe(
        annotatedReadOnlySchemaRepository,
        rmdProtocolVersionId,
        getServerConfig().isComputeFastAvroEnabled());
    this.mergeConflictResolver = MergeConflictResolverFactory.getInstance()
        .createMergeConflictResolver(
            annotatedReadOnlySchemaRepository,
            rmdSerDe,
            getStoreName(),
            isWriteComputationEnabled,
            getServerConfig().isComputeFastAvroEnabled());
    this.remoteIngestionRepairService = builder.getRemoteIngestionRepairService();
    this.reusableObjectsSupplier = Objects.requireNonNull(builder.getReusableObjectsSupplier());
    this.ingestionBatchProcessorLazy = Lazy.of(() -> {
      if (!serverConfig.isAAWCWorkloadParallelProcessingEnabled()) {
        LOGGER.info("AA/WC workload parallel processing is disabled for store version: {}", getKafkaVersionTopic());
        return null;
      }
      LOGGER.info("AA/WC workload parallel processing is enabled for store version: {}", getKafkaVersionTopic());
      return new IngestionBatchProcessor(
          kafkaVersionTopic,
          parallelProcessingThreadPool,
          keyLevelLocksManager.get(),
          this::processActiveActiveMessage,
          isWriteComputationEnabled,
          isActiveActiveReplicationEnabled(),
          aggVersionedIngestionStats,
          getHostLevelIngestionStats());
    });
  }

  public static int getKeyLevelLockMaxPoolSizeBasedOnServerConfig(VeniceServerConfig serverConfig, int partitionCount) {
    int consumerPoolSizeForLeaderConsumption = 0;
    if (serverConfig.isDedicatedConsumerPoolForAAWCLeaderEnabled()) {
      consumerPoolSizeForLeaderConsumption = serverConfig.getDedicatedConsumerPoolSizeForAAWCLeader()
          + serverConfig.getDedicatedConsumerPoolSizeForSepRTLeader();
    } else if (serverConfig.getConsumerPoolStrategyType()
        .equals(KafkaConsumerServiceDelegator.ConsumerPoolStrategyType.CURRENT_VERSION_PRIORITIZATION)) {
      consumerPoolSizeForLeaderConsumption = serverConfig.getConsumerPoolSizeForCurrentVersionAAWCLeader()
          + serverConfig.getConsumerPoolSizeForCurrentVersionSepRTLeader()
          + serverConfig.getConsumerPoolSizeForNonCurrentVersionAAWCLeader();
    } else {
      consumerPoolSizeForLeaderConsumption = serverConfig.getConsumerPoolSizePerKafkaCluster();
    }
    int multiplier = 1;
    if (serverConfig.isAAWCWorkloadParallelProcessingEnabled()) {
      multiplier = serverConfig.getAAWCWorkloadParallelProcessingThreadPoolSize();
    }
    return Math.min(partitionCount, consumerPoolSizeForLeaderConsumption)
        * serverConfig.getKafkaClusterIdToUrlMap().size() * multiplier + 1;
  }

  @Override
  protected DelegateConsumerRecordResult delegateConsumerRecord(
      PubSubMessageProcessedResultWrapper consumerRecordWrapper,
      int partition,
      String kafkaUrl,
      int kafkaClusterId,
      long beforeProcessingPerRecordTimestampNs,
      long beforeProcessingBatchRecordsTimestampMs) {
    if (!consumerRecordWrapper.getMessage().getTopicPartition().getPubSubTopic().isRealTime()) {
      /**
       * We don't need to lock the partition here because during VT consumption there is only one consumption source.
       */
      return super.delegateConsumerRecord(
          consumerRecordWrapper,
          partition,
          kafkaUrl,
          kafkaClusterId,
          beforeProcessingPerRecordTimestampNs,
          beforeProcessingBatchRecordsTimestampMs);
    } else {
      /**
       * The below flow must be executed in a critical session for the same key:
       * Read existing value/RMD from transient record cache/disk -> perform DCR and decide incoming value wins
       * -> update transient record cache -> produce to VT (just call send, no need to wait for the produce future in the critical session)
       *
       * Otherwise, there could be race conditions:
       * [fabric A thread]Read from transient record cache -> [fabric A thread]perform DCR and decide incoming value wins
       * -> [fabric B thread]read from transient record cache -> [fabric B thread]perform DCR and decide incoming value wins
       * -> [fabric B thread]update transient record cache -> [fabric B thread]produce to VT -> [fabric A thread]update transient record cache
       * -> [fabric A thread]produce to VT
       */
      final ByteArrayKey byteArrayKey = ByteArrayKey.wrap(consumerRecordWrapper.getMessage().getKey().getKey());
      ReentrantLock keyLevelLock = this.keyLevelLocksManager.get().acquireLockByKey(byteArrayKey);
      keyLevelLock.lock();
      try {
        return super.delegateConsumerRecord(
            consumerRecordWrapper,
            partition,
            kafkaUrl,
            kafkaClusterId,
            beforeProcessingPerRecordTimestampNs,
            beforeProcessingBatchRecordsTimestampMs);
      } finally {
        keyLevelLock.unlock();
        this.keyLevelLocksManager.get().releaseLock(byteArrayKey);
      }
    }
  }

  @Override
  protected void putInStorageEngine(int partition, byte[] keyBytes, Put put) {
    try {
      // TODO: Honor BatchConflictResolutionPolicy and maybe persist RMD for batch push records.
      StorageOperationType storageOperationType = getStorageOperationTypeForPut(partition, put);
      switch (storageOperationType) {
        case VALUE_AND_RMD:
          storageEngine.putWithReplicationMetadata(
              partition,
              keyBytes,
              put.putValue,
              prependReplicationMetadataBytesWithValueSchemaId(put.replicationMetadataPayload, put.schemaId));
          break;
        case RMD_CHUNK:
          storageEngine.putReplicationMetadata(
              partition,
              keyBytes,
              prependReplicationMetadataBytesWithValueSchemaId(put.replicationMetadataPayload, put.schemaId));
          break;
        case VALUE:
          storageEngine.put(partition, keyBytes, put.putValue);
          break;
        default:
          // do nothing
          break;
      }
    } catch (PersistenceFailureException e) {
      throwOrLogStorageFailureDependingIfStillSubscribed(partition, e);
    }
  }

  @Override
  protected void removeFromStorageEngine(int partition, byte[] keyBytes, Delete delete) {
    try {
      // TODO: Honor BatchConflictResolutionPolicy and maybe persist RMD for batch push records.
      switch (getStorageOperationTypeForDelete(partition, delete)) {
        case VALUE_AND_RMD:
          byte[] metadataBytesWithValueSchemaId =
              prependReplicationMetadataBytesWithValueSchemaId(delete.replicationMetadataPayload, delete.schemaId);
          storageEngine.deleteWithReplicationMetadata(partition, keyBytes, metadataBytesWithValueSchemaId);
          break;
        case VALUE:
          storageEngine.delete(partition, keyBytes);
          break;
        default:
          // do nothing
          break;
      }
    } catch (PersistenceFailureException e) {
      throwOrLogStorageFailureDependingIfStillSubscribed(partition, e);
    }
  }

  StorageOperationType getStorageOperationTypeForPut(int partition, Put put) {
    PartitionConsumptionState pcs = getPartitionConsumptionStateMap().get(partition);
    if (pcs == null) {
      logStorageOperationWhileUnsubscribed(partition);
      return StorageOperationType.SKIP;
    }
    ByteBuffer valuePayload = put.putValue;
    ByteBuffer rmdPayload = put.replicationMetadataPayload;
    checkStorageOperationCommonInvalidPattern(valuePayload, rmdPayload);

    if (isDaVinciClient()) {
      // For Da Vinci Client, RMD chunk should be ignored.
      if (valuePayload.remaining() == 0) {
        return StorageOperationType.SKIP;
      }
      return StorageOperationType.VALUE;
    }

    if (rmdPayload.remaining() == 0) {
      return StorageOperationType.VALUE;
    }
    if (valuePayload.remaining() > 0) {
      return StorageOperationType.VALUE_AND_RMD;
    } else {
      // RMD chunk case.
      return StorageOperationType.RMD_CHUNK;
    }
  }

  StorageOperationType getStorageOperationTypeForDelete(int partition, Delete delete) {
    PartitionConsumptionState pcs = getPartitionConsumptionStateMap().get(partition);
    if (pcs == null) {
      logStorageOperationWhileUnsubscribed(partition);
      return StorageOperationType.SKIP;
    }
    ByteBuffer rmdPayload = delete.replicationMetadataPayload;
    checkStorageOperationCommonInvalidPattern(null, rmdPayload);

    if (isDaVinciClient()) {
      /**
       * Da Vinci always operates on default RocksDB column family only and do not take in RMD. In deferred phase,
       * DELETE is not allowed and it should be skipped.
       */
      if (pcs.isDeferredWrite()) {
        return StorageOperationType.SKIP;
      }
      return StorageOperationType.VALUE;
    }
    /**
     * For before EOP delete without RMD: If it is from reprocessing job, it should be allowed and processed as it will
     * be running in non-deferred-write mode. If it is from regular VPJ, it is unexpected, and low level storage operation
     * will fail and throw exception.
     */
    if (delete.replicationMetadataPayload.remaining() == 0 && !pcs.isEndOfPushReceived()) {
      return StorageOperationType.VALUE;
    }
    return StorageOperationType.VALUE_AND_RMD;
  }

  void checkStorageOperationCommonInvalidPattern(ByteBuffer valuePayload, ByteBuffer rmdPayload) {
    if (rmdPayload == null) {
      throw new IllegalArgumentException("Replication metadata payload not found.");
    }
    /**
     * If value is null, it is a DELETE operation;
     * If value is non-empty, it is a value PUT potentially carrying RMD.
     * If value is empty and RMD is non-empty, it is a RMD chunk PUT.
     * Operation with both content being empty is invalid.
     */
    if (valuePayload != null && valuePayload.remaining() == 0 && rmdPayload.remaining() == 0) {
      throw new IllegalArgumentException("Either value or RMD payload should carry a non-empty content.");
    }
  }

  enum StorageOperationType {
    VALUE_AND_RMD, // Operate on value associated with RMD
    VALUE, // Operate on full or chunked value
    RMD_CHUNK, // Operate on chunked RMD
    SKIP // Will skip the storage operation
  }

  private byte[] prependReplicationMetadataBytesWithValueSchemaId(ByteBuffer metadata, int valueSchemaId) {
    // TODO: Currently this function makes a copy of the data in the original buffer. It may be possible to pre-allocate
    // space for the 4-byte header and reuse the original replication metadata ByteBuffer.
    ByteBuffer bufferWithHeader = ByteUtils.prependIntHeaderToByteBuffer(metadata, valueSchemaId, false);
    bufferWithHeader.position(bufferWithHeader.position() - ByteUtils.SIZE_OF_INT);
    byte[] replicationMetadataBytesWithValueSchemaId = ByteUtils.extractByteArray(bufferWithHeader);
    bufferWithHeader.position(bufferWithHeader.position() + ByteUtils.SIZE_OF_INT);
    return replicationMetadataBytesWithValueSchemaId;
  }

  /**
   * Get the existing value schema ID and RMD associated with the given key. If information for this key is found from
   * the transient map then use that, otherwise get it from storage engine.
   *
   * @param partitionConsumptionState The {@link PartitionConsumptionState} of the current partition
   * @param key                       Bytes of key.
   * @param partition                 The partition to fetch the replication metadata from storage engine
   * @return The object containing RMD and value schema id. If nothing is found, return null
   */
  RmdWithValueSchemaId getReplicationMetadataAndSchemaId(
      PartitionConsumptionState partitionConsumptionState,
      byte[] key,
      int partition,
      long currentTimeForMetricsMs) {
    PartitionConsumptionState.TransientRecord cachedRecord = partitionConsumptionState.getTransientRecord(key);
    if (cachedRecord != null) {
      getHostLevelIngestionStats().recordIngestionReplicationMetadataCacheHitCount(currentTimeForMetricsMs);
      return new RmdWithValueSchemaId(
          cachedRecord.getValueSchemaId(),
          getRmdProtocolVersionId(),
          cachedRecord.getReplicationMetadataRecord(),
          cachedRecord.getRmdManifest());
    }
    ChunkedValueManifestContainer rmdManifestContainer = new ChunkedValueManifestContainer();
    byte[] replicationMetadataWithValueSchemaBytes =
        getRmdWithValueSchemaByteBufferFromStorage(partition, key, rmdManifestContainer, currentTimeForMetricsMs);
    if (replicationMetadataWithValueSchemaBytes == null) {
      return null; // No RMD for this key
    }
    RmdWithValueSchemaId rmdWithValueSchemaId = new RmdWithValueSchemaId();
    // Get old RMD manifest value from RMD Manifest container object.
    rmdWithValueSchemaId.setRmdManifest(rmdManifestContainer.getManifest());
    getRmdSerDe()
        .deserializeValueSchemaIdPrependedRmdBytes(replicationMetadataWithValueSchemaBytes, rmdWithValueSchemaId);
    return rmdWithValueSchemaId;
  }

  public RmdSerDe getRmdSerDe() {
    return rmdSerDe;
  }

  /**
   * This method tries to retrieve the RMD bytes with prepended value schema ID from storage engine. It will also store
   * RMD manifest into passed-in {@link ChunkedValueManifestContainer} container object if current RMD value is chunked.
   */
  byte[] getRmdWithValueSchemaByteBufferFromStorage(
      int partition,
      byte[] key,
      ChunkedValueManifestContainer rmdManifestContainer,
      long currentTimeForMetricsMs) {
    final long lookupStartTimeInNS = System.nanoTime();
    ValueRecord result = databaseLookupWithConcurrencyLimit(
        () -> getRmdWithValueSchemaByteBufferFromStorageInternal(partition, key, rmdManifestContainer));
    getHostLevelIngestionStats().recordIngestionReplicationMetadataLookUpLatency(
        LatencyUtils.getElapsedTimeFromNSToMS(lookupStartTimeInNS),
        currentTimeForMetricsMs);
    if (result == null) {
      return null;
    }
    return result.serialize();
  }

  // For testing purpose
  ValueRecord getRmdWithValueSchemaByteBufferFromStorageInternal(
      int partition,
      byte[] key,
      ChunkedValueManifestContainer rmdManifestContainer) {
    return SingleGetChunkingAdapter
        .getReplicationMetadata(getStorageEngine(), partition, key, isChunked(), rmdManifestContainer);
  }

  @Override
  protected IngestionBatchProcessor getIngestionBatchProcessor() {
    return ingestionBatchProcessorLazy.get();
  }

  private PubSubMessageProcessedResult processActiveActiveMessage(
      DefaultPubSubMessage consumerRecord,
      PartitionConsumptionState partitionConsumptionState,
      int partition,
      String kafkaUrl,
      int kafkaClusterId,
      long beforeProcessingRecordTimestampNs,
      long beforeProcessingBatchRecordsTimestampMs) {
    KafkaKey kafkaKey = consumerRecord.getKey();
    KafkaMessageEnvelope kafkaValue = consumerRecord.getValue();
    byte[] keyBytes = kafkaKey.getKey();
    MessageType msgType = MessageType.valueOf(kafkaValue.messageType);
    final int incomingValueSchemaId;
    final int incomingWriteComputeSchemaId;

    switch (msgType) {
      case PUT:
        incomingValueSchemaId = ((Put) kafkaValue.payloadUnion).schemaId;
        incomingWriteComputeSchemaId = -1;
        break;
      case UPDATE:
        Update incomingUpdate = (Update) kafkaValue.payloadUnion;
        incomingValueSchemaId = incomingUpdate.schemaId;
        incomingWriteComputeSchemaId = incomingUpdate.updateSchemaId;
        break;
      case DELETE:
        incomingValueSchemaId = -1; // Ignored since we don't need the schema id for DELETE operations.
        incomingWriteComputeSchemaId = -1;
        break;
      default:
        throw new VeniceMessageException(
            ingestionTaskName + " : Invalid/Unrecognized operation type submitted: " + kafkaValue.messageType);
    }
    final ChunkedValueManifestContainer valueManifestContainer = new ChunkedValueManifestContainer();
    Lazy<ByteBufferValueRecord<ByteBuffer>> oldValueProvider = Lazy.of(
        () -> getValueBytesForKey(
            partitionConsumptionState,
            keyBytes,
            consumerRecord.getTopicPartition(),
            valueManifestContainer,
            beforeProcessingBatchRecordsTimestampMs));
    if (hasChangeCaptureView || (hasComplexVenicePartitionerMaterializedView && msgType == MessageType.DELETE)) {
      /**
       * Since this function will update the transient cache before writing the view, and if there is
       * a change capture view writer, we need to lookup first, otherwise the transient cache will be populated
       * when writing to the view after this function.
       */
      oldValueProvider.get();
    }

    final RmdWithValueSchemaId rmdWithValueSchemaID = getReplicationMetadataAndSchemaId(
        partitionConsumptionState,
        keyBytes,
        partition,
        beforeProcessingBatchRecordsTimestampMs);

    final long writeTimestamp = getWriteTimestampFromKME(kafkaValue);
    final long offsetSumPreOperation =
        rmdWithValueSchemaID != null ? RmdUtils.extractOffsetVectorSumFromRmd(rmdWithValueSchemaID.getRmdRecord()) : 0;
    List<Long> recordTimestampsPreOperation = rmdWithValueSchemaID != null
        ? RmdUtils.extractTimestampFromRmd(rmdWithValueSchemaID.getRmdRecord())
        : Collections.singletonList(0L);

    // get the source offset and the id
    long sourceOffset = consumerRecord.getPosition().getNumericOffset();
    final MergeConflictResult mergeConflictResult;

    aggVersionedIngestionStats.recordTotalDCR(storeName, versionNumber);

    Lazy<ByteBuffer> oldValueByteBufferProvider = unwrapByteBufferFromOldValueProvider(oldValueProvider);

    long beforeDCRTimestampInNs = System.nanoTime();
    switch (msgType) {
      case PUT:
        mergeConflictResult = mergeConflictResolver.put(
            oldValueByteBufferProvider,
            rmdWithValueSchemaID,
            ((Put) kafkaValue.payloadUnion).putValue,
            writeTimestamp,
            incomingValueSchemaId,
            sourceOffset,
            kafkaClusterId,
            kafkaClusterId // Use the kafka cluster ID as the colo ID for now because one colo/fabric has only one
        // Kafka cluster. TODO: evaluate whether it is enough this way, or we need to add a new
        // config to represent the mapping from Kafka server URLs to colo ID.
        );
        getHostLevelIngestionStats()
            .recordIngestionActiveActivePutLatency(LatencyUtils.getElapsedTimeFromNSToMS(beforeDCRTimestampInNs));
        break;

      case DELETE:
        mergeConflictResult = mergeConflictResolver.delete(
            oldValueByteBufferProvider,
            rmdWithValueSchemaID,
            writeTimestamp,
            sourceOffset,
            kafkaClusterId,
            kafkaClusterId);
        getHostLevelIngestionStats()
            .recordIngestionActiveActiveDeleteLatency(LatencyUtils.getElapsedTimeFromNSToMS(beforeDCRTimestampInNs));
        break;

      case UPDATE:
        mergeConflictResult = mergeConflictResolver.update(
            oldValueByteBufferProvider,
            rmdWithValueSchemaID,
            ((Update) kafkaValue.payloadUnion).updateValue,
            incomingValueSchemaId,
            incomingWriteComputeSchemaId,
            writeTimestamp,
            sourceOffset,
            kafkaClusterId,
            kafkaClusterId,
            valueManifestContainer);
        getHostLevelIngestionStats()
            .recordIngestionActiveActiveUpdateLatency(LatencyUtils.getElapsedTimeFromNSToMS(beforeDCRTimestampInNs));
        break;
      default:
        throw new VeniceMessageException(
            ingestionTaskName + " : Invalid/Unrecognized operation type submitted: " + kafkaValue.messageType);
    }

    if (mergeConflictResult.isUpdateIgnored()) {
      hostLevelIngestionStats.recordUpdateIgnoredDCR();
      return new PubSubMessageProcessedResult(
          new MergeConflictResultWrapper(
              mergeConflictResult,
              oldValueProvider,
              oldValueByteBufferProvider,
              rmdWithValueSchemaID,
              valueManifestContainer,
              null,
              null,
              (schemaId) -> storeDeserializerCache.getDeserializer(schemaId, schemaId)));
    } else {
      // If rmdWithValueSchemaID is not null this implies Venice has processed this key before
      // it will be produced to VT with as a duplicate key message. emit this info for log compaction
      if (rmdWithValueSchemaID != null) {
        aggVersionedIngestionStats.recordTotalDuplicateKeyUpdate(storeName, versionNumber);
      }
      validatePostOperationResultsAndRecord(mergeConflictResult, offsetSumPreOperation, recordTimestampsPreOperation);

      final ByteBuffer updatedValueBytes = maybeCompressData(
          consumerRecord.getTopicPartition().getPartitionNumber(),
          mergeConflictResult.getNewValue(),
          partitionConsumptionState);

      final int valueSchemaId = mergeConflictResult.getValueSchemaId();

      GenericRecord rmdRecord = mergeConflictResult.getRmdRecord();
      final ByteBuffer updatedRmdBytes =
          rmdSerDe.serializeRmdRecord(mergeConflictResult.getValueSchemaId(), mergeConflictResult.getRmdRecord());

      if (updatedValueBytes == null) {
        hostLevelIngestionStats.recordTombstoneCreatedDCR();
        aggVersionedIngestionStats.recordTombStoneCreationDCR(storeName, versionNumber);
        partitionConsumptionState
            .setTransientRecord(kafkaClusterId, consumerRecord.getPosition(), keyBytes, valueSchemaId, rmdRecord);
      } else {
        int valueLen = updatedValueBytes.remaining();
        partitionConsumptionState.setTransientRecord(
            kafkaClusterId,
            consumerRecord.getPosition(),
            keyBytes,
            updatedValueBytes.array(),
            updatedValueBytes.position(),
            valueLen,
            valueSchemaId,
            rmdRecord);
      }
      return new PubSubMessageProcessedResult(
          new MergeConflictResultWrapper(
              mergeConflictResult,
              oldValueProvider,
              oldValueByteBufferProvider,
              rmdWithValueSchemaID,
              valueManifestContainer,
              updatedValueBytes,
              updatedRmdBytes,
              (schemaId) -> storeDeserializerCache.getDeserializer(schemaId, schemaId)));
    }
  }

  // This function may modify the original record in KME, it is unsafe to use the payload from KME directly after
  // this function.
  protected void processMessageAndMaybeProduceToKafka(
      PubSubMessageProcessedResultWrapper consumerRecordWrapper,
      PartitionConsumptionState partitionConsumptionState,
      int partition,
      String kafkaUrl,
      int kafkaClusterId,
      long beforeProcessingRecordTimestampNs,
      long beforeProcessingBatchRecordsTimestampMs) {
    /**
     * With {@link BatchConflictResolutionPolicy.BATCH_WRITE_LOSES} there is no need
     * to perform DCR before EOP and L/F DIV passthrough mode should be used. If the version is going through data
     * recovery then there is no need to perform DCR until we completed data recovery and switched to consume from RT.
     * TODO. We need to refactor this logic when we support other batch conflict resolution policy.
     */
    if (!partitionConsumptionState.isEndOfPushReceived()
        || isDataRecovery && partitionConsumptionState.getTopicSwitch() != null) {
      super.processMessageAndMaybeProduceToKafka(
          consumerRecordWrapper,
          partitionConsumptionState,
          partition,
          kafkaUrl,
          kafkaClusterId,
          beforeProcessingRecordTimestampNs,
          beforeProcessingBatchRecordsTimestampMs);
      return;
    }
    DefaultPubSubMessage consumerRecord = consumerRecordWrapper.getMessage();
    KafkaKey kafkaKey = consumerRecord.getKey();
    byte[] keyBytes = kafkaKey.getKey();
    final MergeConflictResultWrapper mergeConflictResultWrapper;
    if (consumerRecordWrapper.getProcessedResult() != null
        && consumerRecordWrapper.getProcessedResult().getMergeConflictResultWrapper() != null) {
      mergeConflictResultWrapper = consumerRecordWrapper.getProcessedResult().getMergeConflictResultWrapper();
    } else {
      mergeConflictResultWrapper = processActiveActiveMessage(
          consumerRecord,
          partitionConsumptionState,
          partition,
          kafkaUrl,
          kafkaClusterId,
          beforeProcessingRecordTimestampNs,
          beforeProcessingBatchRecordsTimestampMs).getMergeConflictResultWrapper();
    }

    MergeConflictResult mergeConflictResult = mergeConflictResultWrapper.getMergeConflictResult();
    if (!mergeConflictResult.isUpdateIgnored()) {
      // Apply this update to any views for this store
      // TODO: It'd be good to be able to do this in LeaderFollowerStoreIngestionTask instead, however, AA currently is
      // the
      // only extension of IngestionTask which does a read from disk before applying the record. This makes the
      // following function
      // call in this context much less obtrusive, however, it implies that all views can only work for AA stores

      // Write to views
      Runnable produceToVersionTopic = () -> producePutOrDeleteToKafka(
          mergeConflictResultWrapper,
          partitionConsumptionState,
          keyBytes,
          consumerRecord,
          partition,
          kafkaUrl,
          kafkaClusterId,
          beforeProcessingRecordTimestampNs);
      if (hasViewWriters()) {
        /**
         * The ordering guarantees we want is the following:
         *
         * 1. Write to all view topics (in parallel).
         * 2. Write to the VT only after we get the ack for all views AND the previous write to VT was queued into the
         *    producer (but not necessarily acked).
         */
        ByteBuffer oldValueBB = mergeConflictResultWrapper.getOldValueByteBufferProvider().get();
        int oldValueSchemaId =
            oldValueBB == null ? -1 : mergeConflictResultWrapper.getOldValueProvider().get().writerSchemaId();
        Lazy<GenericRecord> valueProvider = mergeConflictResultWrapper.getValueProvider();
        // The helper function takes in a BiFunction but the parameter for view partition set will never be used and
        // always null for A/A ingestion of the RT topic.
        queueUpVersionTopicWritesWithViewWriters(
            partitionConsumptionState,
            (viewWriter, ignored) -> viewWriter.processRecord(
                mergeConflictResultWrapper.getUpdatedValueBytes(),
                oldValueBB,
                keyBytes,
                mergeConflictResult.getValueSchemaId(),
                oldValueSchemaId,
                mergeConflictResult.getRmdRecord(),
                valueProvider),
            null,
            produceToVersionTopic);
      } else {
        // This function may modify the original record in KME and it is unsafe to use the payload from KME directly
        // after this call.
        produceToVersionTopic.run();
      }
    }
  }

  /**
   * Package private for testing purposes.
   */
  static Lazy<ByteBuffer> unwrapByteBufferFromOldValueProvider(
      Lazy<ByteBufferValueRecord<ByteBuffer>> oldValueProvider) {
    return Lazy.of(() -> {
      ByteBufferValueRecord<ByteBuffer> bbValueRecord = oldValueProvider.get();
      return bbValueRecord == null ? null : bbValueRecord.value();
    });
  }

  private long getWriteTimestampFromKME(KafkaMessageEnvelope kme) {
    if (kme.producerMetadata.logicalTimestamp >= 0) {
      return kme.producerMetadata.logicalTimestamp;
    } else {
      return kme.producerMetadata.messageTimestamp;
    }
  }

  private void validatePostOperationResultsAndRecord(
      MergeConflictResult mergeConflictResult,
      Long offsetSumPreOperation,
      List<Long> timestampsPreOperation) {
    // Nothing was applied, no harm no foul
    if (mergeConflictResult.isUpdateIgnored()) {
      return;
    }
    // Post Validation checks on resolution
    GenericRecord rmdRecord = mergeConflictResult.getRmdRecord();
    if (offsetSumPreOperation > RmdUtils.extractOffsetVectorSumFromRmd(rmdRecord)) {
      // offsets went backwards, raise an alert!
      hostLevelIngestionStats.recordOffsetRegressionDCRError();
      aggVersionedIngestionStats.recordOffsetRegressionDCRError(storeName, versionNumber);
      LOGGER
          .error("Offset vector found to have gone backwards!! New invalid replication metadata result: {}", rmdRecord);
    }

    // TODO: This comparison doesn't work well for write compute+schema evolution (can spike up). VENG-8129
    // this works fine for now however as we do not fully support A/A write compute operations (as we only do root
    // timestamp comparisons).

    List<Long> timestampsPostOperation = RmdUtils.extractTimestampFromRmd(rmdRecord);
    for (int i = 0; i < timestampsPreOperation.size(); i++) {
      if (timestampsPreOperation.get(i) > timestampsPostOperation.get(i)) {
        // timestamps went backwards, raise an alert!
        hostLevelIngestionStats.recordTimestampRegressionDCRError();
        aggVersionedIngestionStats.recordTimestampRegressionDCRError(storeName, versionNumber);
        LOGGER.error(
            "Timestamp found to have gone backwards!! Invalid replication metadata result: {}",
            mergeConflictResult.getRmdRecord());
      }
    }
  }

  /**
   * Get the value bytes for a key from {@link PartitionConsumptionState.TransientRecord} or from disk. The assumption
   * is that the {@link PartitionConsumptionState.TransientRecord} only contains the full value.
   * @param partitionConsumptionState The {@link PartitionConsumptionState} of the current partition
   * @param key The key bytes of the incoming record.
   * @param topicPartition The {@link PubSubTopicPartition} from which the incoming record was consumed
   * @return
   */
  private ByteBufferValueRecord<ByteBuffer> getValueBytesForKey(
      PartitionConsumptionState partitionConsumptionState,
      byte[] key,
      PubSubTopicPartition topicPartition,
      ChunkedValueManifestContainer valueManifestContainer,
      long currentTimeForMetricsMs) {
    ByteBufferValueRecord<ByteBuffer> originalValue = null;
    // Find the existing value. If a value for this key is found from the transient map then use that value, otherwise
    // get it from DB.
    PartitionConsumptionState.TransientRecord transientRecord = partitionConsumptionState.getTransientRecord(key);
    if (transientRecord == null) {
      long lookupStartTimeInNS = System.nanoTime();
      IngestionTaskReusableObjects reusableObjects = reusableObjectsSupplier.get();
      ByteBuffer reusedRawValue = reusableObjects.getReusedByteBuffer();
      BinaryDecoder binaryDecoder = reusableObjects.getBinaryDecoder();

      originalValue = databaseLookupWithConcurrencyLimit(
          () -> RawBytesChunkingAdapter.INSTANCE.getWithSchemaId(
              storageEngine,
              topicPartition.getPartitionNumber(),
              ByteBuffer.wrap(key),
              isChunked,
              reusedRawValue,
              binaryDecoder,
              RawBytesStoreDeserializerCache.getInstance(),
              compressor.get(),
              valueManifestContainer));
      hostLevelIngestionStats.recordIngestionValueBytesLookUpLatency(
          LatencyUtils.getElapsedTimeFromNSToMS(lookupStartTimeInNS),
          currentTimeForMetricsMs);
    } else {
      hostLevelIngestionStats.recordIngestionValueBytesCacheHitCount(currentTimeForMetricsMs);
      // construct originalValue from this transient record only if it's not null.
      if (transientRecord.getValue() != null) {
        if (valueManifestContainer != null) {
          valueManifestContainer.setManifest(transientRecord.getValueManifest());
        }
        originalValue = new ByteBufferValueRecord<>(
            getCurrentValueFromTransientRecord(transientRecord),
            transientRecord.getValueSchemaId());
      }
    }
    return originalValue;
  }

  ByteBuffer getCurrentValueFromTransientRecord(PartitionConsumptionState.TransientRecord transientRecord) {
    ByteBuffer compressedValue =
        ByteBuffer.wrap(transientRecord.getValue(), transientRecord.getValueOffset(), transientRecord.getValueLen());
    try {
      return getCompressionStrategy().isCompressionEnabled()
          ? getCompressor().get()
              .decompress(compressedValue.array(), compressedValue.position(), compressedValue.remaining())
          : compressedValue;
    } catch (IOException e) {
      throw new VeniceException(e);
    }
  }

  /**
   * This function parses the {@link MergeConflictResult} and decides if the update should be ignored or emit a PUT or a
   * DELETE record to VT.
   * <p>
   * This function may modify the original record in KME and it is unsafe to use the payload from KME directly after
   * this function.
   *
   * @param mergeConflictResultWrapper       The result of conflict resolution.
   * @param partitionConsumptionState The {@link PartitionConsumptionState} of the current partition
   * @param key                       The key bytes of the incoming record.
   * @param consumerRecord            The {@link PubSubMessage} for the current record.
   * @param partition
   * @param kafkaUrl
   */
  private void producePutOrDeleteToKafka(
      MergeConflictResultWrapper mergeConflictResultWrapper,
      PartitionConsumptionState partitionConsumptionState,
      byte[] key,
      DefaultPubSubMessage consumerRecord,
      int partition,
      String kafkaUrl,
      int kafkaClusterId,
      long beforeProcessingRecordTimestampNs) {
    MergeConflictResult mergeConflictResult = mergeConflictResultWrapper.getMergeConflictResult();
    ByteBuffer updatedValueBytes = mergeConflictResultWrapper.getUpdatedValueBytes();
    ByteBuffer updatedRmdBytes = mergeConflictResultWrapper.getUpdatedRmdBytes();
    final int valueSchemaId = mergeConflictResult.getValueSchemaId();

    ChunkedValueManifest oldValueManifest = mergeConflictResultWrapper.getOldValueManifestContainer().getManifest();
    ChunkedValueManifest oldRmdManifest = mergeConflictResultWrapper.getOldRmdWithValueSchemaId() == null
        ? null
        : mergeConflictResultWrapper.getOldRmdWithValueSchemaId().getRmdManifest();
    // finally produce
    if (mergeConflictResultWrapper.getUpdatedValueBytes() == null) {
      hostLevelIngestionStats.recordTombstoneCreatedDCR();
      aggVersionedIngestionStats.recordTombStoneCreationDCR(storeName, versionNumber);
      Delete deletePayload = new Delete();
      deletePayload.schemaId = valueSchemaId;
      deletePayload.replicationMetadataVersionId = rmdProtocolVersionId;
      deletePayload.replicationMetadataPayload = mergeConflictResultWrapper.getUpdatedRmdBytes();
      BiConsumer<ChunkAwareCallback, LeaderMetadataWrapper> produceToTopicFunction =
          (callback, sourceTopicOffset) -> partitionConsumptionState.getVeniceWriterLazyRef()
              .get()
              .delete(
                  key,
                  callback,
                  sourceTopicOffset,
                  APP_DEFAULT_LOGICAL_TS,
                  new DeleteMetadata(valueSchemaId, rmdProtocolVersionId, updatedRmdBytes),
                  oldValueManifest,
                  oldRmdManifest);
      LeaderProducedRecordContext leaderProducedRecordContext =
          LeaderProducedRecordContext.newDeleteRecord(kafkaClusterId, consumerRecord.getPosition(), key, deletePayload);
      produceToLocalKafka(
          consumerRecord,
          partitionConsumptionState,
          leaderProducedRecordContext,
          produceToTopicFunction,
          partition,
          kafkaUrl,
          kafkaClusterId,
          beforeProcessingRecordTimestampNs);
    } else {
      Put updatedPut = new Put();
      updatedPut.putValue = ByteUtils
          .prependIntHeaderToByteBuffer(updatedValueBytes, valueSchemaId, mergeConflictResult.doesResultReuseInput());
      updatedPut.schemaId = valueSchemaId;
      updatedPut.replicationMetadataVersionId = rmdProtocolVersionId;
      updatedPut.replicationMetadataPayload = updatedRmdBytes;

      BiConsumer<ChunkAwareCallback, LeaderMetadataWrapper> produceToTopicFunction = getProduceToTopicFunction(
          partitionConsumptionState,
          key,
          updatedValueBytes,
          updatedRmdBytes,
          oldValueManifest,
          oldRmdManifest,
          valueSchemaId,
          mergeConflictResult.doesResultReuseInput());
      produceToLocalKafka(
          consumerRecord,
          partitionConsumptionState,
          LeaderProducedRecordContext.newPutRecord(kafkaClusterId, consumerRecord.getPosition(), key, updatedPut),
          produceToTopicFunction,
          partition,
          kafkaUrl,
          kafkaClusterId,
          beforeProcessingRecordTimestampNs);
    }
  }

  @Override
  protected Map<String, Long> calculateLeaderUpstreamOffsetWithTopicSwitch(
      PartitionConsumptionState partitionConsumptionState,
      PubSubTopic newSourceTopic,
      List<CharSequence> unreachableBrokerList) {
    TopicSwitch topicSwitch = partitionConsumptionState.getTopicSwitch().getTopicSwitch();
    if (topicSwitch == null) {
      throw new VeniceException(
          "New leader does not have topic switch, unable to switch to realtime leader topic: " + newSourceTopic);
    }
    final PubSubTopicPartition sourceTopicPartition = partitionConsumptionState.getSourceTopicPartition(newSourceTopic);
    long rewindStartTimestamp;
    // calculate the rewind start time here if controller asked to do so by using this sentinel value.
    if (topicSwitch.rewindStartTimestamp == REWIND_TIME_DECIDED_BY_SERVER) {
      rewindStartTimestamp = calculateRewindStartTime(partitionConsumptionState);
      LOGGER.info(
          "{} leader calculated rewindStartTimestamp: {} for topic-partition: {}",
          ingestionTaskName,
          rewindStartTimestamp,
          sourceTopicPartition);
    } else {
      rewindStartTimestamp = topicSwitch.rewindStartTimestamp;
    }
    Set<String> sourceKafkaServers = getKafkaUrlSetFromTopicSwitch(partitionConsumptionState.getTopicSwitch());
    Map<String, Long> upstreamOffsetsByKafkaURLs = new HashMap<>(sourceKafkaServers.size());
    sourceKafkaServers.forEach(sourceKafkaURL -> {
      long upstreamStartOffset = partitionConsumptionState.getLatestProcessedUpstreamRTOffset(sourceKafkaURL);
      if (upstreamStartOffset < 0) {
        if (rewindStartTimestamp > 0) {
          PubSubTopicPartition newSourceTopicPartition =
              resolveTopicPartitionWithKafkaURL(newSourceTopic, partitionConsumptionState, sourceKafkaURL);
          try {
            upstreamStartOffset =
                getTopicPartitionOffsetByKafkaURL(sourceKafkaURL, newSourceTopicPartition, rewindStartTimestamp);
            LOGGER.info(
                "{} get upstreamStartOffset: {} for source URL: {}, topic-partition: {}, rewind timestamp: {}",
                ingestionTaskName,
                upstreamStartOffset,
                sourceKafkaURL,
                newSourceTopicPartition,
                rewindStartTimestamp);
            upstreamOffsetsByKafkaURLs.put(sourceKafkaURL, upstreamStartOffset);
          } catch (Exception e) {
            /**
             * This is actually tricky. Potentially we could return a -1 value here, but this has the gotcha that if we
             * have a non-symmetrical failure (like, region1 can't talk to the region2 broker) this will result in a remote
             * colo rewinding to a potentially non-deterministic offset when the remote becomes available again. So
             * instead, we record the context of this call and commit it to a repair queue to rewind to a
             * consistent place on the RT
             *
             * NOTE: It is possible that the outage is so long and the rewind so large that we fall off retention. If
             * this happens we can detect and repair the inconsistency from the offline DCR validator.
             */
            unreachableBrokerList.add(sourceKafkaURL);
            upstreamStartOffset = OffsetRecord.LOWEST_OFFSET;
            LOGGER.error(
                "Failed contacting broker {} when processing topic switch! for {}. Setting upstream start offset to {}",
                sourceKafkaURL,
                sourceTopicPartition,
                upstreamStartOffset);
            hostLevelIngestionStats.recordIngestionFailure();
            /**
             *  Add to repair queue. We won't attempt to resubscribe for brokers we couldn't compute an upstream offset
             *  accurately for. We will not persist the wrong offset into OffsetRecord, we'll reattempt subscription later.
             */
            if (remoteIngestionRepairService != null) {
              this.remoteIngestionRepairService.registerRepairTask(
                  this,
                  buildRepairTask(
                      sourceKafkaURL,
                      sourceTopicPartition,
                      rewindStartTimestamp,
                      partitionConsumptionState));
            } else {
              // If there isn't an available repair service, then we need to abort in order to make sure the error is
              // propagated up
              throw new VeniceException(
                  String.format(
                      "Failed contacting broker (%s) and no repair service available!  Aborting topic switch processing for %s. Setting upstream start offset to %d",
                      sourceKafkaURL,
                      sourceTopicPartition,
                      upstreamStartOffset));
            }
          }
        } else {
          LOGGER.warn(
              "{} got unexpected rewind time: {}, will start ingesting upstream from the beginning",
              ingestionTaskName,
              rewindStartTimestamp);
          upstreamStartOffset = OffsetRecord.LOWEST_OFFSET;
          upstreamOffsetsByKafkaURLs.put(sourceKafkaURL, upstreamStartOffset);
        }
      } else {
        upstreamOffsetsByKafkaURLs.put(sourceKafkaURL, upstreamStartOffset);
      }
    });
    if (unreachableBrokerList.size() >= ((sourceKafkaServers.size() + 1) / 2)) {
      // We couldn't reach a quorum of brokers and that's a red flag, so throw exception and abort!
      throw new VeniceException("Couldn't reach any broker!!  Aborting topic switch triggered consumer subscription!");
    }
    return upstreamOffsetsByKafkaURLs;
  }

  @Override
  protected void startConsumingAsLeader(PartitionConsumptionState partitionConsumptionState) {
    final int partition = partitionConsumptionState.getPartition();
    final OffsetRecord offsetRecord = partitionConsumptionState.getOffsetRecord();
    final PubSubTopic leaderTopic = offsetRecord.getLeaderTopic(pubSubTopicRepository);

    /**
     * Note that this function is called after the new leader has waited for 5 minutes of inactivity on the local VT topic.
     * The new leader might NOT need to switch to remote consumption in a case where map-reduce jobs of a batch job stuck
     * on producing to the local VT so that there is no activity in the local VT.
     */
    if (shouldNewLeaderSwitchToRemoteConsumption(partitionConsumptionState)) {
      partitionConsumptionState.setConsumeRemotely(true);
      LOGGER
          .info("{} enabled remote consumption from topic {} partition {}", ingestionTaskName, leaderTopic, partition);
    }
    partitionConsumptionState.setLeaderFollowerState(LEADER);
    prepareOffsetCheckpointAndStartConsumptionAsLeader(leaderTopic, partitionConsumptionState, true);
  }

  /**
   * Ensures the PubSub URL is present in the PubSub cluster URL-to-ID map before subscribing to a topic.
   * Prevents subscription to unknown PubSub URLs, which can cause issues during message consumption.
   */
  public void consumerSubscribe(PubSubTopicPartition pubSubTopicPartition, long startOffset, String pubSubAddress) {
    VeniceServerConfig serverConfig = getServerConfig();
    if (isDaVinciClient() || serverConfig.getKafkaClusterUrlToIdMap().containsKey(pubSubAddress)) {
      super.consumerSubscribe(pubSubTopicPartition, startOffset, pubSubAddress);
      return;
    }
    LOGGER.error(
        "PubSub address: {} is not in the pubsub cluster map: {}. Cannot subscribe to topic-partition: {}",
        pubSubAddress,
        serverConfig.getKafkaClusterUrlToIdMap(),
        pubSubTopicPartition);
    throw new VeniceException(
        String.format(
            "PubSub address: %s is not in the pubsub cluster map. Cannot subscribe to topic-partition: %s",
            pubSubAddress,
            pubSubTopicPartition));
  }

  @Override
  protected void leaderExecuteTopicSwitch(
      PartitionConsumptionState partitionConsumptionState,
      TopicSwitch topicSwitch,
      PubSubTopic newSourceTopic) {
    if (partitionConsumptionState.getLeaderFollowerState() != LEADER) {
      throw new VeniceException(String.format("Expect state %s but got %s", LEADER, partitionConsumptionState));
    }
    if (topicSwitch.sourceKafkaServers.isEmpty()) {
      throw new VeniceException(
          "In the A/A mode, source Kafka URL list cannot be empty in Topic Switch control message.");
    }

    final int partition = partitionConsumptionState.getPartition();
    final PubSubTopic currentLeaderTopic =
        partitionConsumptionState.getOffsetRecord().getLeaderTopic(pubSubTopicRepository);
    final PubSubTopicPartition sourceTopicPartition = partitionConsumptionState.getSourceTopicPartition(newSourceTopic);

    // unsubscribe the old source and subscribe to the new source
    unsubscribeFromTopic(currentLeaderTopic, partitionConsumptionState);
    waitForLastLeaderPersistFuture(
        partitionConsumptionState,
        String.format(
            "Leader failed to produce the last message to version topic before switching feed topic from %s to %s on partition %s",
            currentLeaderTopic,
            newSourceTopic,
            partition));

    if (topicSwitch.sourceKafkaServers.size() != 1
        || (!Objects.equals(topicSwitch.sourceKafkaServers.get(0).toString(), localKafkaServer))) {
      partitionConsumptionState.setConsumeRemotely(true);
      LOGGER.info(
          "{} enabled remote consumption and switch to topic-partition {}",
          ingestionTaskName,
          sourceTopicPartition);
    }
    // Update leader topic.
    partitionConsumptionState.getOffsetRecord().setLeaderTopic(newSourceTopic);
    // Calculate leader offset and start consumption
    prepareOffsetCheckpointAndStartConsumptionAsLeader(newSourceTopic, partitionConsumptionState, false);
  }

  /**
   * Process {@link TopicSwitch} control message at given partition offset for a specific {@link PartitionConsumptionState}.
   */
  @Override
  protected void processTopicSwitch(
      ControlMessage controlMessage,
      int partition,
      long offset,
      PartitionConsumptionState partitionConsumptionState) {
    TopicSwitch topicSwitch = (TopicSwitch) controlMessage.controlMessageUnion;
    ingestionNotificationDispatcher.reportTopicSwitchReceived(partitionConsumptionState);
    final String newSourceTopicName = topicSwitch.sourceTopicName.toString();
    PubSubTopic newSourceTopic = pubSubTopicRepository.getTopic(newSourceTopicName);

    /**
     * TopicSwitch needs to be persisted locally for both servers and DaVinci clients so that ready-to-serve check
     * can make the correct decision.
     */
    syncTopicSwitchToIngestionMetadataService(topicSwitch, partitionConsumptionState);
    if (!isLeader(partitionConsumptionState)) {
      partitionConsumptionState.getOffsetRecord().setLeaderTopic(newSourceTopic);
    }
  }

  @Override
  protected void updateLatestInMemoryProcessedOffset(
      PartitionConsumptionState partitionConsumptionState,
      DefaultPubSubMessage consumerRecord,
      LeaderProducedRecordContext leaderProducedRecordContext,
      String kafkaUrl,
      boolean dryRun) {
    updateOffsetsFromConsumerRecord(
        partitionConsumptionState,
        consumerRecord,
        leaderProducedRecordContext,
        partitionConsumptionState::updateLatestProcessedLocalVersionTopicOffset,
        (sourceKafkaUrl, upstreamTopic, upstreamTopicOffset) -> {
          if (upstreamTopic.isRealTime()) {
            partitionConsumptionState.updateLatestProcessedUpstreamRTOffset(sourceKafkaUrl, upstreamTopicOffset);
          } else {
            partitionConsumptionState.updateLatestProcessedUpstreamVersionTopicOffset(upstreamTopicOffset);
          }
        },
        (sourceKafkaUrl, upstreamTopic) -> upstreamTopic.isRealTime()
            ? partitionConsumptionState.getLatestProcessedUpstreamRTOffset(sourceKafkaUrl)
            : partitionConsumptionState.getLatestProcessedUpstreamVersionTopicOffset(),
        () -> getUpstreamKafkaUrl(partitionConsumptionState, consumerRecord, kafkaUrl),
        dryRun);
  }

  /**
   * Get upstream kafka url for record.
   *   1. For leaders, it is the source where the record was consumed from
   *   2. For Followers, it is either
   *       a. The upstream kafka url populated by the leader in the record, or
   *       b. The local kafka address
   *
   * @param partitionConsumptionState The current {@link PartitionConsumptionState} for the partition
   * @param consumerRecord The record for which the upstream Kafka url needs to be computed
   * @param recordSourceKafkaUrl The Kafka URL from where the record was consumed
   * @return The computed upstream Kafka URL for the record
   */
  private String getUpstreamKafkaUrl(
      PartitionConsumptionState partitionConsumptionState,
      DefaultPubSubMessage consumerRecord,
      String recordSourceKafkaUrl) {
    final String upstreamKafkaURL;
    if (isLeader(partitionConsumptionState)) {
      // Wherever leader consumes from is considered as "upstream"
      upstreamKafkaURL = recordSourceKafkaUrl;
    } else {
      KafkaMessageEnvelope kafkaValue = consumerRecord.getValue();
      if (kafkaValue.leaderMetadataFooter == null) {
        /**
         * This "leaderMetadataFooter" field do not get populated in case the source fabric is the same as the
         * local fabric since the VT source will be one of the prod fabric after batch NR is fully ramped. The
         * leader replica consumes from local Kafka URL. Hence, from a follower's perspective, the upstream Kafka
         * cluster which the leader consumes from should be the local Kafka URL.
         */
        upstreamKafkaURL = localKafkaServer;
      } else {
        upstreamKafkaURL =
            getUpstreamKafkaUrlFromKafkaValue(consumerRecord, recordSourceKafkaUrl, this.kafkaClusterIdToUrlMap);
      }
    }
    return upstreamKafkaURL;
  }

  @Override
  protected boolean isRealTimeBufferReplayStarted(PartitionConsumptionState partitionConsumptionState) {
    TopicSwitchWrapper topicSwitchWrapper = partitionConsumptionState.getTopicSwitch();
    if (topicSwitchWrapper == null) {
      return false;
    }
    if (topicSwitchWrapper.getTopicSwitch().sourceKafkaServers.isEmpty()) {
      throw new VeniceException("Got empty source Kafka URLs in Topic Switch.");
    }
    return topicSwitchWrapper.getNewSourceTopic().isRealTime();
  }

  /**
   * Returns the latest processed upstream real-time offset for the given region.
   * This is used to compute hybrid offset lag on a per-region basis, which is then
   * used in conjunction with lag from other regions to determine ready-to-serve status.
   */
  @Override
  protected long getLatestPersistedUpstreamOffsetForHybridOffsetLagMeasurement(
      PartitionConsumptionState pcs,
      String upstreamKafkaUrl) {
    return pcs.getLatestProcessedUpstreamRTOffset(upstreamKafkaUrl);
  }

  /**
   * Different from the persisted upstream offset map in OffsetRecord, latest consumed upstream offset map is maintained
   * for each individual Kafka url.
   */
  @Override
  protected long getLatestConsumedUpstreamOffsetForHybridOffsetLagMeasurement(
      PartitionConsumptionState pcs,
      String upstreamKafkaUrl) {
    return pcs.getLeaderConsumedUpstreamRTOffset(upstreamKafkaUrl);
  }

  @Override
  protected void updateLatestInMemoryLeaderConsumedRTOffset(
      PartitionConsumptionState pcs,
      String kafkaUrl,
      long offset) {
    pcs.updateLeaderConsumedUpstreamRTOffset(kafkaUrl, offset);
  }

  /**
   * N.B. package-private for testing purposes.
   */
  static String getUpstreamKafkaUrlFromKafkaValue(
      DefaultPubSubMessage consumerRecord,
      String recordSourceKafkaUrl,
      Int2ObjectMap<String> kafkaClusterIdToUrlMap) {
    KafkaMessageEnvelope kafkaValue = consumerRecord.getValue();
    if (kafkaValue.leaderMetadataFooter == null) {
      throw new VeniceException("leaderMetadataFooter field in KME should have been set.");
    }
    String upstreamKafkaURL = kafkaClusterIdToUrlMap.get(kafkaValue.leaderMetadataFooter.upstreamKafkaClusterId);
    if (upstreamKafkaURL == null) {
      MessageType type = MessageType.valueOf(kafkaValue.messageType);
      throw new VeniceException(
          String.format(
              "No Kafka cluster ID found in the cluster ID to Kafka URL map. "
                  + "Got cluster ID %d and ID to cluster URL map %s. Source Kafka: %s; "
                  + "%s; Offset: %d; Message type: %s; ProducerMetadata: %s; LeaderMetadataFooter: %s",
              kafkaValue.leaderMetadataFooter.upstreamKafkaClusterId,
              kafkaClusterIdToUrlMap,
              recordSourceKafkaUrl,
              consumerRecord.getTopicPartition(),
              consumerRecord.getPosition().getNumericOffset(),
              type.toString() + (type == MessageType.CONTROL_MESSAGE
                  ? "/" + ControlMessageType.valueOf((ControlMessage) kafkaValue.getPayloadUnion())
                  : ""),
              kafkaValue.producerMetadata,
              kafkaValue.leaderMetadataFooter));
    }
    return upstreamKafkaURL;
  }

  /**
   * For Active-Active this buffer is always used, as long as we're post-EOP.
   * @return
   */
  @Override
  public boolean isTransientRecordBufferUsed(PartitionConsumptionState partitionConsumptionState) {
    return partitionConsumptionState.isEndOfPushReceived();
  }

  @Override
  public long getRegionHybridOffsetLag(int regionId) {
    StoreVersionState svs = storageEngine.getStoreVersionState();
    if (svs == null) {
      /**
       * Store version metadata is created for the first time when the first START_OF_PUSH message is processed;
       * however, the ingestion stat is created the moment an ingestion task is created, so there is a short time
       * window where there is no version metadata, which is not an error.
       */
      return 0;
    }

    if (partitionConsumptionStateMap.isEmpty()) {
      /**
       * Partition subscription happens after the ingestion task and stat are created, it's not an error.
       */
      return 0;
    }

    String kafkaSourceAddress = kafkaClusterIdToUrlMap.get(regionId);
    // This storage node does not register with the given region ID.
    if (kafkaSourceAddress == null) {
      return 0;
    }

    long offsetLag = partitionConsumptionStateMap.values()
        .stream()
        .filter(LeaderFollowerStoreIngestionTask.LEADER_OFFSET_LAG_FILTER)
        // the lag is (latest fabric RT offset - consumed fabric RT offset)
        .mapToLong((pcs) -> {
          PubSubTopic resolvedLeaderTopic =
              resolveTopicWithKafkaURL(pcs.getOffsetRecord().getLeaderTopic(pubSubTopicRepository), kafkaSourceAddress);
          if (resolvedLeaderTopic == null || !resolvedLeaderTopic.isRealTime()) {
            // Leader topic not found, indicating that it is VT topic.
            return 0;
          }
          String resolvedKafkaUrl = Utils.resolveKafkaUrlForSepTopic(kafkaSourceAddress);
          // Consumer might not exist after the consumption state is created, but before attaching the corresponding
          // consumer.
          long lagBasedOnMetrics =
              getPartitionOffsetLagBasedOnMetrics(resolvedKafkaUrl, resolvedLeaderTopic, pcs.getPartition());
          if (lagBasedOnMetrics >= 0) {
            return lagBasedOnMetrics;
          }

          // Fall back to calculate offset lag in the old way
          return measureLagWithCallToPubSub(
              resolvedKafkaUrl,
              resolvedLeaderTopic,
              pcs.getPartition(),
              pcs.getLeaderConsumedUpstreamRTOffset(kafkaSourceAddress));
        })
        .filter(VALID_LAG)
        .sum();

    return minZeroLag(offsetLag);
  }

  /**
   * For stores in active/active mode, if no fabric is unreachable, return the maximum lag of all fabrics. If only one
   * fabric is unreachable, return the maximum lag of other fabrics. If more than one fabrics are unreachable, return
   * Long.MAX_VALUE, which means the partition is not ready-to-serve.
   * TODO: For active/active incremental push stores or stores with only one samza job, we should consider the weight of
   * unreachable fabric and make the decision. For example, we should not let partition ready-to-serve when the only
   * source fabric is unreachable.
   *
   * @param sourceRealTimeTopicKafkaURLs
   * @param partitionConsumptionState
   * @param shouldLogLag
   * @return
   */
  @Override
  protected long measureRTOffsetLagForMultiRegions(
      Set<String> sourceRealTimeTopicKafkaURLs,
      PartitionConsumptionState partitionConsumptionState,
      boolean shouldLogLag) {
    long maxLag = Long.MIN_VALUE;
    int numberOfUnreachableRegions = 0;
    for (String sourceRealTimeTopicKafkaURL: sourceRealTimeTopicKafkaURLs) {
      try {
        long lag =
            measureRTOffsetLagForSingleRegion(sourceRealTimeTopicKafkaURL, partitionConsumptionState, shouldLogLag);
        maxLag = Math.max(lag, maxLag);
      } catch (Exception e) {
        LOGGER.error(
            "Failed to measure RT offset lag for replica: {} in {}/{}",
            partitionConsumptionState.getReplicaId(),
            Utils.getReplicaId(
                partitionConsumptionState.getOffsetRecord().getLeaderTopic(pubSubTopicRepository),
                partitionConsumptionState.getPartition()),
            sourceRealTimeTopicKafkaURL,
            e);
        if (++numberOfUnreachableRegions > 1) {
          LOGGER.error(
              "More than one regions are unreachable. Returning lag: {} as replica: {} may not be ready-to-serve.",
              Long.MAX_VALUE,
              partitionConsumptionState.getReplicaId());
          return Long.MAX_VALUE;
        }
      }
    }
    return maxLag;
  }

  /** used for metric purposes **/
  @Override
  public boolean isReadyToServeAnnouncedWithRTLag() {
    if (!hybridStoreConfig.isPresent() || partitionConsumptionStateMap.isEmpty()) {
      return false;
    }
    long offsetLagThreshold = hybridStoreConfig.get().getOffsetLagThresholdToGoOnline();
    for (PartitionConsumptionState pcs: partitionConsumptionStateMap.values()) {
      if (pcs.hasLagCaughtUp() && offsetLagThreshold >= 0) {
        // If pcs is marked as having caught up, but we're not ready to serve, that means we're lagging
        // after having announced that we are ready to serve.
        try {
          if (!this.isReadyToServe(pcs)) {
            return true;
          }
        } catch (Exception e) {
          // Something wasn't reachable, we'll report that something is amiss.
          return true;
        }
      }
    }
    return false;
  }

  Runnable buildRepairTask(
      String sourceKafkaUrl,
      PubSubTopicPartition sourceTopicPartition,
      long rewindStartTimestamp,
      PartitionConsumptionState pcs) {
    return () -> {
      PubSubTopic pubSubTopic = sourceTopicPartition.getPubSubTopic();
      PubSubTopicPartition resolvedTopicPartition = resolveTopicPartitionWithKafkaURL(pubSubTopic, pcs, sourceKafkaUrl);
      // Calculate upstream offset
      long upstreamOffset =
          getTopicPartitionOffsetByKafkaURL(sourceKafkaUrl, resolvedTopicPartition, rewindStartTimestamp);
      // Subscribe (unsubscribe should have processed correctly regardless of remote broker state)
      consumerSubscribe(pubSubTopic, pcs, upstreamOffset, sourceKafkaUrl);
      // syncConsumedUpstreamRTOffsetMapIfNeeded
      Map<String, Long> urlToOffsetMap = new HashMap<>();
      urlToOffsetMap.put(sourceKafkaUrl, upstreamOffset);
      syncConsumedUpstreamRTOffsetMapIfNeeded(pcs, urlToOffsetMap);

      LOGGER.info(
          "Successfully repaired consumption and subscribed to {} at offset {}",
          sourceTopicPartition,
          upstreamOffset);
    };
  }

  int getRmdProtocolVersionId() {
    return rmdProtocolVersionId;
  }

  protected BiConsumer<ChunkAwareCallback, LeaderMetadataWrapper> getProduceToTopicFunction(
      PartitionConsumptionState partitionConsumptionState,
      byte[] key,
      ByteBuffer updatedValueBytes,
      ByteBuffer updatedRmdBytes,
      ChunkedValueManifest oldValueManifest,
      ChunkedValueManifest oldRmdManifest,
      int valueSchemaId,
      boolean resultReuseInput) {
    return (callback, leaderMetadataWrapper) -> {
      if (resultReuseInput) {
        // Restore the original header so this function is eventually idempotent as the original KME ByteBuffer
        // will be recovered after producing the message to Kafka or if the production failing.
        ((ActiveActiveProducerCallback) callback).setOnCompletionFunction(
            () -> ByteUtils.prependIntHeaderToByteBuffer(
                updatedValueBytes,
                ByteUtils.getIntHeaderFromByteBuffer(updatedValueBytes),
                true));
      }
      getVeniceWriter(partitionConsumptionState).get()
          .put(
              key,
              ByteUtils.extractByteArray(updatedValueBytes),
              valueSchemaId,
              callback,
              leaderMetadataWrapper,
              APP_DEFAULT_LOGICAL_TS,
              new PutMetadata(getRmdProtocolVersionId(), updatedRmdBytes),
              oldValueManifest,
              oldRmdManifest);
    };
  }

  protected LeaderProducerCallback createProducerCallback(
      DefaultPubSubMessage consumerRecord,
      PartitionConsumptionState partitionConsumptionState,
      LeaderProducedRecordContext leaderProducedRecordContext,
      int partition,
      String kafkaUrl,
      long beforeProcessingRecordTimestampNs) {
    return new ActiveActiveProducerCallback(
        this,
        consumerRecord,
        partitionConsumptionState,
        leaderProducedRecordContext,
        partition,
        kafkaUrl,
        beforeProcessingRecordTimestampNs);
  }

  @Override
  void prepareOffsetCheckpointAndStartConsumptionAsLeader(
      PubSubTopic leaderTopic,
      PartitionConsumptionState partitionConsumptionState,
      boolean isTransition) {
    Set<String> leaderSourceKafkaURLs = getConsumptionSourceKafkaAddress(partitionConsumptionState);
    Map<String, Long> leaderOffsetByKafkaURL = new HashMap<>(leaderSourceKafkaURLs.size());
    List<CharSequence> unreachableBrokerList = new ArrayList<>();
    boolean useLcro = isTransition && isGlobalRtDivEnabled();
    // Read previously checkpointed offset and maybe fallback to TopicSwitch if any of upstream offset is missing.
    for (String kafkaURL: leaderSourceKafkaURLs) {
      leaderOffsetByKafkaURL
          .put(kafkaURL, partitionConsumptionState.getLeaderOffset(kafkaURL, pubSubTopicRepository, useLcro));
    }
    if (leaderTopic.isRealTime() && leaderOffsetByKafkaURL.containsValue(OffsetRecord.LOWEST_OFFSET)) {
      leaderOffsetByKafkaURL =
          calculateLeaderUpstreamOffsetWithTopicSwitch(partitionConsumptionState, leaderTopic, unreachableBrokerList);
    }

    if (!unreachableBrokerList.isEmpty()) {
      LOGGER.warn(
          "Failed to reach broker urls {}, will schedule retry to compute upstream offset and resubscribe!",
          unreachableBrokerList.toString());
    }
    // subscribe to the new upstream
    leaderOffsetByKafkaURL.forEach((kafkaURL, leaderStartOffset) -> {
      consumerSubscribe(leaderTopic, partitionConsumptionState, leaderStartOffset, kafkaURL);
    });

    syncConsumedUpstreamRTOffsetMapIfNeeded(partitionConsumptionState, leaderOffsetByKafkaURL);

    LOGGER.info(
        "{}, as a leader, started consuming from topic: {}, partition {}: with offset by Kafka URL mapping: {}",
        partitionConsumptionState.getReplicaId(),
        leaderTopic,
        partitionConsumptionState.getPartition(),
        leaderOffsetByKafkaURL);
  }

  private long calculateRewindStartTime(PartitionConsumptionState partitionConsumptionState) {
    long rewindStartTime;
    long rewindTimeInMs = hybridStoreConfig.get().getRewindTimeInSeconds() * Time.MS_PER_SECOND;
    if (isDataRecovery) {
      // Override the user rewind if the version is under data recovery to avoid data loss when user have short rewind.
      rewindTimeInMs = Math.max(PubSubConstants.BUFFER_REPLAY_MINIMAL_SAFETY_MARGIN, rewindTimeInMs);
    }
    switch (hybridStoreConfig.get().getBufferReplayPolicy()) {
      case REWIND_FROM_SOP:
        rewindStartTime = partitionConsumptionState.getStartOfPushTimestamp() - rewindTimeInMs;
        break;
      case REWIND_FROM_EOP:
      default:
        rewindStartTime = partitionConsumptionState.getEndOfPushTimestamp() - rewindTimeInMs;
    }
    return rewindStartTime;
  }

}
