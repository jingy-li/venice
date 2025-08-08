package com.linkedin.venice.endToEnd;

import static com.linkedin.davinci.store.rocksdb.RocksDBServerConfig.ROCKSDB_BLOCK_CACHE_SIZE_IN_BYTES;
import static com.linkedin.davinci.store.rocksdb.RocksDBServerConfig.ROCKSDB_PLAIN_TABLE_FORMAT_ENABLED;
import static com.linkedin.venice.ConfigKeys.BLOB_RECEIVE_READER_IDLE_TIME_IN_SECONDS;
import static com.linkedin.venice.ConfigKeys.BLOB_TRANSFER_ACL_ENABLED;
import static com.linkedin.venice.ConfigKeys.BLOB_TRANSFER_CLIENT_READ_LIMIT_BYTES_PER_SEC;
import static com.linkedin.venice.ConfigKeys.BLOB_TRANSFER_DISABLED_OFFSET_LAG_THRESHOLD;
import static com.linkedin.venice.ConfigKeys.BLOB_TRANSFER_MANAGER_ENABLED;
import static com.linkedin.venice.ConfigKeys.BLOB_TRANSFER_SERVICE_WRITE_LIMIT_BYTES_PER_SEC;
import static com.linkedin.venice.ConfigKeys.BLOB_TRANSFER_SSL_ENABLED;
import static com.linkedin.venice.ConfigKeys.CLIENT_SYSTEM_STORE_REPOSITORY_REFRESH_INTERVAL_SECONDS;
import static com.linkedin.venice.ConfigKeys.CLIENT_USE_SYSTEM_STORE_REPOSITORY;
import static com.linkedin.venice.ConfigKeys.DATA_BASE_PATH;
import static com.linkedin.venice.ConfigKeys.DAVINCI_P2P_BLOB_TRANSFER_CLIENT_PORT;
import static com.linkedin.venice.ConfigKeys.DAVINCI_P2P_BLOB_TRANSFER_SERVER_PORT;
import static com.linkedin.venice.ConfigKeys.DAVINCI_PUSH_STATUS_SCAN_INTERVAL_IN_SECONDS;
import static com.linkedin.venice.ConfigKeys.PERSISTENCE_TYPE;
import static com.linkedin.venice.ConfigKeys.PUSH_STATUS_STORE_ENABLED;
import static com.linkedin.venice.ConfigKeys.SERVER_DATABASE_CHECKSUM_VERIFICATION_ENABLED;
import static com.linkedin.venice.integration.utils.DaVinciTestContext.getCachingDaVinciClientFactory;
import static com.linkedin.venice.integration.utils.VeniceClusterWrapper.DEFAULT_KEY_SCHEMA;
import static com.linkedin.venice.meta.PersistenceType.ROCKS_DB;
import static com.linkedin.venice.utils.IntegrationTestPushUtils.createStoreForJob;
import static com.linkedin.venice.utils.IntegrationTestPushUtils.defaultVPJProps;
import static com.linkedin.venice.utils.TestWriteUtils.getTempDataDirectory;
import static com.linkedin.venice.utils.TestWriteUtils.writeSimpleAvroFileWithIntToStringSchema;
import static com.linkedin.venice.vpj.VenicePushJobConstants.VENICE_STORE_NAME_PROP;

import com.linkedin.d2.balancer.D2Client;
import com.linkedin.d2.balancer.D2ClientBuilder;
import com.linkedin.davinci.DaVinciUserApp;
import com.linkedin.davinci.client.DaVinciClient;
import com.linkedin.davinci.client.DaVinciConfig;
import com.linkedin.davinci.client.StorageClass;
import com.linkedin.davinci.client.factory.CachingDaVinciClientFactory;
import com.linkedin.venice.D2.D2ClientUtils;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterCreateOptions;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceRouterWrapper;
import com.linkedin.venice.store.rocksdb.RocksDBUtils;
import com.linkedin.venice.utils.DataProviderUtils;
import com.linkedin.venice.utils.ForkedJavaProcess;
import com.linkedin.venice.utils.IntegrationTestPushUtils;
import com.linkedin.venice.utils.PropertyBuilder;
import com.linkedin.venice.utils.SslUtils;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.VeniceProperties;
import io.tehuti.metrics.MetricsRepository;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


/**
 * Test P2P blob transfer among DaVinci Clients with one client shutdown during the transfer.
 */
public class DaVinciClientP2PBlobTransferShutdownTest {
  private static final Logger LOGGER = LogManager.getLogger(DaVinciClientP2PBlobTransferShutdownTest.class);
  private static final int TEST_TIMEOUT = 120_000;
  private VeniceClusterWrapper cluster;
  private D2Client d2Client;

  @BeforeClass
  public void setUp() {
    Utils.thisIsLocalhost();
    Properties clusterConfig = new Properties();
    clusterConfig.put(PUSH_STATUS_STORE_ENABLED, true);
    clusterConfig.put(DAVINCI_PUSH_STATUS_SCAN_INTERVAL_IN_SECONDS, 3);
    VeniceClusterCreateOptions options = new VeniceClusterCreateOptions.Builder().numberOfControllers(1)
        .numberOfServers(2)
        .numberOfRouters(1)
        .replicationFactor(2)
        .partitionSize(100)
        .sslToStorageNodes(false)
        .sslToKafka(false)
        .extraProperties(clusterConfig)
        .build();
    cluster = ServiceFactory.getVeniceCluster(options);
    d2Client = new D2ClientBuilder().setZkHosts(cluster.getZk().getAddress())
        .setZkSessionTimeout(3, TimeUnit.SECONDS)
        .setZkStartupTimeout(3, TimeUnit.SECONDS)
        .build();
    D2ClientUtils.startClient(d2Client);
  }

  @AfterClass
  public void cleanUp() {
    if (d2Client != null) {
      D2ClientUtils.shutdownClient(d2Client);
    }
    Utils.closeQuietlyWithErrorLogged(cluster);
  }

  @Test(timeOut = 2 * TEST_TIMEOUT, dataProviderClass = DataProviderUtils.class, dataProvider = "True-and-False")
  public void testBlobP2PTransferAmongDVCWithServerShutdown(boolean isGracefulShutdown) throws Exception {
    String dvcPath1 = Utils.getTempDataDirectory().getAbsolutePath();
    String zkHosts = cluster.getZk().getAddress();
    int port1 = TestUtils.getFreePort();
    int port2 = TestUtils.getFreePort();
    while (port1 == port2) {
      port2 = TestUtils.getFreePort();
    }
    Consumer<UpdateStoreQueryParams> paramsConsumer = params -> params.setBlobTransferEnabled(true);
    String storeName = Utils.getUniqueString("test-store");
    setUpStore(storeName, paramsConsumer, properties -> {}, true);

    // Start the first DaVinci Client using DaVinciUserApp
    File configDir = Utils.getTempDataDirectory();
    File configFile = new File(configDir, "dvc-config.properties");
    Properties props = new Properties();
    props.setProperty("zk.hosts", zkHosts);
    props.setProperty("base.data.path", dvcPath1);
    props.setProperty("store.name", storeName);
    props.setProperty("sleep.seconds", "100");
    props.setProperty("heartbeat.timeout.seconds", "10");
    props.setProperty("ingestion.isolation", "false");
    props.setProperty("blob.transfer.server.port", Integer.toString(port1));
    props.setProperty("blob.transfer.client.port", Integer.toString(port2));
    props.setProperty("storage.class", StorageClass.DISK.toString());
    props.setProperty("record.transformer.enabled", "false");
    props.setProperty("blob.transfer.manager.enabled", "true");
    props.setProperty("batch.push.report.enabled", "false");

    // Write properties to file
    try (FileWriter writer = new FileWriter(configFile)) {
      props.store(writer, null);
    }

    ForkedJavaProcess forkedDaVinciUserApp = ForkedJavaProcess.exec(DaVinciUserApp.class, configFile.getAbsolutePath());

    // Wait for the first DaVinci Client to complete ingestion
    Thread.sleep(60000);

    // Prepare client 2 configs
    String dvcPath2 = Utils.getTempDataDirectory().getAbsolutePath();
    PropertyBuilder configBuilder = new PropertyBuilder().put(PERSISTENCE_TYPE, ROCKS_DB)
        .put(ROCKSDB_PLAIN_TABLE_FORMAT_ENABLED, "false")
        .put(SERVER_DATABASE_CHECKSUM_VERIFICATION_ENABLED, "true")
        .put(CLIENT_USE_SYSTEM_STORE_REPOSITORY, true)
        .put(CLIENT_SYSTEM_STORE_REPOSITORY_REFRESH_INTERVAL_SECONDS, 1)
        .put(DATA_BASE_PATH, dvcPath2)
        .put(DAVINCI_P2P_BLOB_TRANSFER_SERVER_PORT, port2)
        .put(DAVINCI_P2P_BLOB_TRANSFER_CLIENT_PORT, port1)
        .put(PUSH_STATUS_STORE_ENABLED, true)
        .put(ROCKSDB_BLOCK_CACHE_SIZE_IN_BYTES, 2 * 1024 * 1024L)
        .put(DAVINCI_PUSH_STATUS_SCAN_INTERVAL_IN_SECONDS, 1)
        .put(BLOB_TRANSFER_MANAGER_ENABLED, true)
        .put(BLOB_TRANSFER_SSL_ENABLED, true)
        .put(BLOB_TRANSFER_ACL_ENABLED, true)
        .put(BLOB_TRANSFER_DISABLED_OFFSET_LAG_THRESHOLD, -1000000)
        .put(BLOB_TRANSFER_CLIENT_READ_LIMIT_BYTES_PER_SEC, 1)
        .put(BLOB_TRANSFER_SERVICE_WRITE_LIMIT_BYTES_PER_SEC, 1);

    // set up SSL configs.
    Properties sslProperties = SslUtils.getVeniceLocalSslProperties();
    sslProperties.forEach((key, value) -> configBuilder.put((String) key, value));

    if (!isGracefulShutdown) {
      // if not graceful shutdown, expect the idle event trigger,
      // set idle time as 1, trigger the idle handler immediately
      configBuilder.put(BLOB_RECEIVE_READER_IDLE_TIME_IN_SECONDS, 1);
    }

    VeniceProperties backendConfig2 = configBuilder.build();
    DaVinciConfig dvcConfig = new DaVinciConfig().setIsolated(true);

    // Monitor snapshot folder creation to detect if a blob transfer is happening
    CompletableFuture.runAsync(() -> {
      try {
        while (true) {
          for (int partition = 0; partition < 3; partition++) {
            String snapshotPath1 = RocksDBUtils.composeSnapshotDir(dvcPath1 + "/rocksdb", storeName + "_v1", partition);
            if (Files.exists(Paths.get(snapshotPath1))) {
              if (isGracefulShutdown) {
                LOGGER
                    .info("Detected snapshot folder for partition {}, immediately destroy client1 process.", partition);
                forkedDaVinciUserApp.destroy();
              } else {
                LOGGER.info(
                    "Detected snapshot folder for partition {}, immediately destroyForcibly client1 process.",
                    partition);
                forkedDaVinciUserApp.destroyForcibly();
              }
              return; // Exit monitoring loop
            }
          }
          // if is graceful shutdown, check more frequently, otherwise transfer may complete before channel close.
          Thread.sleep(isGracefulShutdown ? 10 : 100);
        }
      } catch (Exception e) {
        LOGGER.error("Error in monitoring snapshot creation.", e);
      }
    });

    try (CachingDaVinciClientFactory factory2 = getCachingDaVinciClientFactory(
        d2Client,
        VeniceRouterWrapper.CLUSTER_DISCOVERY_D2_SERVICE_NAME,
        new MetricsRepository(),
        backendConfig2,
        cluster)) {
      // Start client 2
      DaVinciClient<Integer, Object> client2 = factory2.getAndStartGenericAvroClient(storeName, dvcConfig);
      client2.subscribeAll().get();

      // Verify that client2 can still complete the bootstrap successfully
      // even though client1 was killed during the transfer.
      try {
        client2.get(300, TimeUnit.SECONDS);
        TestUtils.waitForNonDeterministicAssertion(60, TimeUnit.SECONDS, () -> {
          for (int i = 0; i < 3; i++) {
            String partitionPath = RocksDBUtils.composePartitionDbDir(dvcPath2 + "/rocksdb", storeName + "_v1", i);
            Assert.assertTrue(Files.exists(Paths.get(partitionPath)));
          }
        });
      } finally {
        client2.close();
      }
    }
  }

  /*
   * Batch data schema:
   * Key: Integer
   * Value: String
   */
  private void setUpStore(
      String storeName,
      Consumer<UpdateStoreQueryParams> paramsConsumer,
      Consumer<Properties> propertiesConsumer,
      boolean useDVCPushStatusStore) {
    boolean chunkingEnabled = false;
    CompressionStrategy compressionStrategy = CompressionStrategy.NO_OP;

    File inputDir = getTempDataDirectory();

    Runnable writeAvroFileRunnable = () -> {
      try {
        writeSimpleAvroFileWithIntToStringSchema(inputDir);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };
    String valueSchema = "\"string\"";
    setUpStore(
        storeName,
        paramsConsumer,
        propertiesConsumer,
        useDVCPushStatusStore,
        chunkingEnabled,
        compressionStrategy,
        writeAvroFileRunnable,
        valueSchema,
        inputDir);
  }

  private void setUpStore(
      String storeName,
      Consumer<UpdateStoreQueryParams> paramsConsumer,
      Consumer<Properties> propertiesConsumer,
      boolean useDVCPushStatusStore,
      boolean chunkingEnabled,
      CompressionStrategy compressionStrategy,
      Runnable writeAvroFileRunnable,
      String valueSchema,
      File inputDir) {
    // Produce input data.
    writeAvroFileRunnable.run();

    // Setup VPJ job properties.
    String inputDirPath = "file://" + inputDir.getAbsolutePath();
    Properties vpjProperties = defaultVPJProps(cluster, inputDirPath, storeName);
    propertiesConsumer.accept(vpjProperties);
    // Create & update store for test.
    final int numPartitions = 3;
    UpdateStoreQueryParams params = new UpdateStoreQueryParams().setPartitionCount(numPartitions)
        .setChunkingEnabled(chunkingEnabled)
        .setCompressionStrategy(compressionStrategy);

    paramsConsumer.accept(params);

    try (ControllerClient controllerClient =
        createStoreForJob(cluster, DEFAULT_KEY_SCHEMA, valueSchema, vpjProperties)) {
      cluster.createMetaSystemStore(storeName);
      if (useDVCPushStatusStore) {
        cluster.createPushStatusSystemStore(storeName);
      }
      TestUtils.assertCommand(controllerClient.updateStore(storeName, params));
      runVPJ(vpjProperties, 1, cluster);
    }
  }

  private static void runVPJ(Properties vpjProperties, int expectedVersionNumber, VeniceClusterWrapper cluster) {
    IntegrationTestPushUtils.runVPJ(vpjProperties);
    String storeName = (String) vpjProperties.get(VENICE_STORE_NAME_PROP);
    cluster.waitVersion(storeName, expectedVersionNumber);
  }
}
