package com.linkedin.venice.server;

import com.google.common.collect.ImmutableList;
import com.linkedin.venice.config.VeniceClusterConfig;
import com.linkedin.venice.config.VeniceServerConfig;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.helix.HelixAdapterSerializer;
import com.linkedin.venice.helix.HelixParticipationService;
import com.linkedin.venice.helix.HelixReadOnlySchemaRepository;
import com.linkedin.venice.helix.HelixReadonlyStoreRepository;
import com.linkedin.venice.kafka.consumer.KafkaConsumerPerStoreService;
import com.linkedin.venice.listener.ListenerService;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.meta.ReadonlyStoreRepository;
import com.linkedin.venice.offsets.BdbOffsetManager;
import com.linkedin.venice.service.AbstractVeniceService;
import com.linkedin.venice.storage.StorageService;
import com.linkedin.venice.utils.Utils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.helix.manager.zk.ZkClient;
import org.apache.log4j.Logger;


// TODO curate all comments later
public class VeniceServer {

  private static final Logger logger = Logger.getLogger(VeniceServer.class);
  private final VeniceConfigLoader veniceConfigLoader;
  private final AtomicBoolean isStarted;

  private StorageService storageService;
  private BdbOffsetManager offSetService;

  private ReadOnlySchemaRepository schemaRepo;

  private final List<AbstractVeniceService> services;

  public VeniceServer(VeniceConfigLoader veniceConfigLoader)
      throws VeniceException {
    this.isStarted = new AtomicBoolean(false);
    this.veniceConfigLoader = veniceConfigLoader;

    /*
     * TODO - 1. How do the servers share the same config - For example in Voldemort we use cluster.xml and stores.xml.
     * 2. Check Hostnames like in Voldemort to make sure that local host and ips match up.
     */

    //create all services
    this.services = createServices();
  }

  /**
   * Instantiate all known services. Most of the services in this method intake:
   * 1. StoreRepository - that maps store to appropriate storage engine instance
   * 2. VeniceConfig - which contains configs related to this cluster
   * 3. StoreNameToConfigsMap - which contains store specific configs
   * 4. PartitionAssignmentRepository - which contains how partitions for each store are mapped to nodes in the
   *    cluster
   *
   * @return
   */
  private List<AbstractVeniceService> createServices() {
    /* Services are created in the order they must be started */
    List<AbstractVeniceService> services = new ArrayList<AbstractVeniceService>();

    VeniceClusterConfig clusterConfig = veniceConfigLoader.getVeniceClusterConfig();
    VeniceServerConfig serverConfig = veniceConfigLoader.getVeniceServerConfig();

    // create and add StorageService. storeRepository will be populated by StorageService,
    storageService = new StorageService(serverConfig);
    services.add(storageService);

    // Create and add Offset Service.
    offSetService = new BdbOffsetManager(veniceConfigLoader.getVeniceClusterConfig());
    services.add(offSetService);

    // Create ReadOnlySchemaRepository
    schemaRepo = createSchemaRepository(clusterConfig);

    //create and add KafkaSimpleConsumerService
    KafkaConsumerPerStoreService kafkaConsumerService =
        new KafkaConsumerPerStoreService(storageService.getStoreRepository(), veniceConfigLoader, offSetService, schemaRepo);
    services.add(kafkaConsumerService);

    // start venice participant service if Helix is enabled.
    if(clusterConfig.isHelixEnabled()) {
      HelixParticipationService helixParticipationService =
          new HelixParticipationService(kafkaConsumerService, storageService, veniceConfigLoader,
              clusterConfig.getZookeeperAddress(), clusterConfig.getClusterName(),
              veniceConfigLoader.getVeniceServerConfig().getListenerPort());
      services.add(helixParticipationService);
    } else {
      // Note: Only required when NOT using Helix.
      throw new UnsupportedOperationException("Only Helix mode of operation is supported");
    }

    //create and add ListenerServer for handling GET requests
    ListenerService listenerService =
        new ListenerService(storageService.getStoreRepository(), offSetService, veniceConfigLoader);
    services.add(listenerService);


    /**
     * TODO Create an admin service later. The admin service will need both StorageService and KafkaSimpleConsumerService
     * passed on to it.
     *
     * To add a new store do this in order:
     * 1. Populate storeNameToConfigsMap
     * 2. Get the assignment plan from PartitionNodeAssignmentScheme and  populate the PartitionAssignmentRepository
     * 3. call StorageService.openStore(..) to create the appropriate storage partitions
     * 4. call KafkaSimpleConsumerService.startConsumption(..) to create and start the consumer tasks for all kafka partitions.
     */

    return ImmutableList.copyOf(services);
  }

  private ReadOnlySchemaRepository createSchemaRepository(VeniceClusterConfig clusterConfig) {
    ZkClient zkClient = new ZkClient(clusterConfig.getZookeeperAddress(), ZkClient.DEFAULT_SESSION_TIMEOUT, ZkClient.DEFAULT_CONNECTION_TIMEOUT);
    HelixAdapterSerializer adapter = new HelixAdapterSerializer();
    String clusterName = clusterConfig.getClusterName();
    ReadonlyStoreRepository storeRepo = new HelixReadonlyStoreRepository(zkClient, adapter, clusterName);
    // Load existing store config and setup watches
    storeRepo.refresh();
    ReadOnlySchemaRepository schemaRepo = new HelixReadOnlySchemaRepository(storeRepo, zkClient, adapter, clusterName);
    schemaRepo.refresh();

    return schemaRepo;
  }

  public StorageService getStorageService(){
    if (isStarted()){
      return storageService;
    } else {
      throw new VeniceException("Cannot get storage service if server is not started");
    }
  }

  /**
   * @return true if the {@link VeniceServer} and all of its inner services are fully started
   *         false if the {@link VeniceServer} was not started or if any of its inner services
   *         are not finished starting.
   */
  public boolean isStarted() {
    return isStarted.get() && services.stream().allMatch(abstractVeniceService -> abstractVeniceService.isStarted());
  }

  /**
   * Method which starts the services instantiate earlier
   *
   * @throws Exception
   */
  public void start()
      throws VeniceException {
    boolean isntStarted = isStarted.compareAndSet(false, true);
    if (!isntStarted) {
      throw new IllegalStateException("Service is already started!");
    }
    // TODO - Efficient way to lock java heap
    logger.info("Starting " + services.size() + " services.");
    long start = System.currentTimeMillis();
    for (AbstractVeniceService service : services) {
      service.start();
    }
    long end = System.currentTimeMillis();
    logger.info("Startup completed in " + (end - start) + " ms.");
  }

  /**
   * Method which closes VeniceServer, shuts down its resources, and exits the
   * JVM.
   * @throws Exception
   * */
  public void shutdown()
      throws VeniceException {
    List<Exception> exceptions = new ArrayList<Exception>();
    logger.info("Stopping all services on Node: " + veniceConfigLoader.getVeniceServerConfig().getNodeId());

    /* Stop in reverse order */

    //TODO: we may want a dependency structure so we ensure services are shutdown in the correct order.
    synchronized (this) {
      if (!isStarted()) {
        logger.info("The server is already stopped, ignoring duplicate attempt.");
        return;
      }
      for (AbstractVeniceService service : Utils.reversed(services)) {
        try {
          service.stop();
        } catch (Exception e) {
          exceptions.add(e);
          logger.error("Exception in stopping service: " + service.getName(), e);
        }
      }
      logger.info("All services stopped");

      if (exceptions.size() > 0) {
        throw new VeniceException(exceptions.get(0));
      }
      isStarted.set(false);

      // TODO - Efficient way to unlock java heap
    }
  }

  public static void main(String args[])
      throws Exception {
    VeniceConfigLoader veniceConfigService = null;
    try {
      if (args.length == 0) {
        veniceConfigService = VeniceConfigLoader.loadFromEnvironmentVariable();
      } else if (args.length == 1) {
        veniceConfigService = VeniceConfigLoader.loadFromConfigDirectory(args[0]);
      } else {
        Utils.croak("USAGE: java " + VeniceServer.class.getName() + " [venice_config_dir] ");
      }
    } catch (Exception e) {
      logger.error("Error starting Venice Server ", e);
      Utils.croak("Error while loading configuration: " + e.getMessage());
    }
    final VeniceServer server = new VeniceServer(veniceConfigService);
    if (!server.isStarted()) {
      server.start();
    }

    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        if (server.isStarted()) {
          try {
            server.shutdown();
          } catch (Exception e) {
            logger.error("Error shutting the server. ", e);
          }
        }
      }
    });

  }
}
