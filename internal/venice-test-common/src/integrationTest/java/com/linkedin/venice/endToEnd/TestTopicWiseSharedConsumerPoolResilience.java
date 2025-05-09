package com.linkedin.venice.endToEnd;

import static com.linkedin.venice.ConfigKeys.ADMIN_HELIX_MESSAGING_CHANNEL_ENABLED;
import static com.linkedin.venice.ConfigKeys.KAFKA_READ_CYCLE_DELAY_MS;
import static com.linkedin.venice.ConfigKeys.PARTICIPANT_MESSAGE_CONSUMPTION_DELAY_MS;
import static com.linkedin.venice.ConfigKeys.PARTICIPANT_MESSAGE_STORE_ENABLED;
import static com.linkedin.venice.ConfigKeys.SERVER_CONSUMER_POOL_SIZE_PER_KAFKA_CLUSTER;
import static com.linkedin.venice.ConfigKeys.SERVER_MAX_WAIT_FOR_VERSION_INFO_MS_CONFIG;
import static com.linkedin.venice.ConfigKeys.SERVER_SHARED_CONSUMER_ASSIGNMENT_STRATEGY;
import static com.linkedin.venice.utils.IntegrationTestPushUtils.createStoreForJob;
import static com.linkedin.venice.utils.IntegrationTestPushUtils.defaultVPJProps;
import static com.linkedin.venice.utils.TestWriteUtils.getTempDataDirectory;
import static com.linkedin.venice.vpj.VenicePushJobConstants.VENICE_STORE_NAME_PROP;

import com.linkedin.davinci.kafka.consumer.KafkaConsumerService;
import com.linkedin.davinci.kafka.consumer.ParticipantStoreConsumptionTask;
import com.linkedin.davinci.kafka.consumer.StoreIngestionTask;
import com.linkedin.venice.controller.Admin;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.hadoop.VenicePushJob;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterCreateOptions;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceControllerWrapper;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.TestWriteUtils;
import com.linkedin.venice.utils.Utils;
import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.avro.Schema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


@Deprecated
public class TestTopicWiseSharedConsumerPoolResilience {
  private static final Logger LOGGER = LogManager.getLogger(TestTopicWiseSharedConsumerPoolResilience.class);

  private VeniceClusterWrapper veniceCluster;

  @BeforeClass(alwaysRun = true)
  public void setUp() {
    Properties extraProperties = new Properties();
    // Disable helix message channel for job kill and enable participant message
    extraProperties.setProperty(ADMIN_HELIX_MESSAGING_CHANNEL_ENABLED, "false");
    extraProperties.setProperty(PARTICIPANT_MESSAGE_STORE_ENABLED, "true");
    // Disable ParticipantStoreIngestionTask
    extraProperties.setProperty(PARTICIPANT_MESSAGE_CONSUMPTION_DELAY_MS, Integer.toString(Integer.MAX_VALUE));
    extraProperties.setProperty(SERVER_CONSUMER_POOL_SIZE_PER_KAFKA_CLUSTER, Integer.toString(3));
    extraProperties.setProperty(KAFKA_READ_CYCLE_DELAY_MS, Integer.toString(1));
    extraProperties.setProperty(
        SERVER_SHARED_CONSUMER_ASSIGNMENT_STRATEGY,
        KafkaConsumerService.ConsumerAssignmentStrategy.TOPIC_WISE_SHARED_CONSUMER_ASSIGNMENT_STRATEGY.name());
    // Set it to lower value so that L->F wait for deprecated version will be faster in test.
    extraProperties.setProperty(SERVER_MAX_WAIT_FOR_VERSION_INFO_MS_CONFIG, Integer.toString(1));
    VeniceClusterCreateOptions options = new VeniceClusterCreateOptions.Builder().numberOfControllers(1)
        .numberOfServers(2)
        .numberOfRouters(1)
        .replicationFactor(2)
        .partitionSize(1000000)
        .sslToStorageNodes(false)
        .sslToKafka(false)
        .extraProperties(extraProperties)
        .build();
    veniceCluster = ServiceFactory.getVeniceCluster(options);
  }

  @AfterClass(alwaysRun = true)
  public void cleanUp() {
    if (veniceCluster != null) {
      veniceCluster.close();
    }
  }

  /**
   * This test is used to validate whether {@link StoreIngestionTask} will automatically deallocate the shared consumer
   * when it is closed while {@link ParticipantStoreConsumptionTask} is stuck/not working.
   * @throws IOException
   */
  @Test
  public void testConsumerPoolShouldNotExhaustDuringRegularDataPushes() throws IOException {
    String storeName = Utils.getUniqueString("batch-store");
    File inputDir = getTempDataDirectory();
    String inputDirPath = "file://" + inputDir.getAbsolutePath();
    Schema recordSchema = TestWriteUtils.writeSimpleAvroFileWithStringToStringSchema(inputDir); // records 1-100
    Properties vpjProperties = defaultVPJProps(veniceCluster, inputDirPath, storeName);

    VeniceControllerWrapper controllerWrapper = veniceCluster.getRandomVeniceController();
    Admin admin = controllerWrapper.getVeniceAdmin();

    try (ControllerClient controllerClient =
        createStoreForJob(veniceCluster.getClusterName(), recordSchema, vpjProperties)) {
      int pushes = 5;
      for (int cur = 1; cur <= pushes; ++cur) {
        int expectedVersionNumber = cur;
        long vpjStart = System.currentTimeMillis();
        String jobName = Utils.getUniqueString("hybrid-job-" + expectedVersionNumber);
        try (VenicePushJob job = new VenicePushJob(jobName, vpjProperties)) {
          job.run();
          TestUtils.waitForNonDeterministicCompletion(
              5,
              TimeUnit.SECONDS,
              () -> controllerClient.getStore((String) vpjProperties.get(VENICE_STORE_NAME_PROP))
                  .getStore()
                  .getCurrentVersion() == expectedVersionNumber);
          LOGGER.info("**TIME** VPJ{} took {} ms", expectedVersionNumber, (System.currentTimeMillis() - vpjStart));
        }
        if (expectedVersionNumber >= 3) {
          // need to wait for the resource for the backup version is completely dropped to free up the share consumer
          // resource when participant store task is not working.
          String resourceNameForBackupVersion = Version.composeKafkaTopic(storeName, (cur - 2));
          TestUtils.waitForNonDeterministicCompletion(
              10,
              TimeUnit.SECONDS,
              () -> !admin.isResourceStillAlive(resourceNameForBackupVersion));
          /**
           * Adding this wait so that dropping resource can complete. The above check only checks Helix side resource,
           * not on server side. The heartbeat monitor action could retry to find deleted store resource, so in this test
           * we add wait here and tuned down timeout for the store repository wait to avoid test flakiness.
           */

          Utils.sleep(1000);
        }
      }
    }
  }
}
