package com.linkedin.venice.controllerapi;

import com.linkedin.d2.balancer.D2Client;
import com.linkedin.r2.message.rest.RestResponse;
import com.linkedin.venice.D2.D2ClientUtils;
import com.linkedin.venice.annotation.VisibleForTesting;
import com.linkedin.venice.d2.D2ClientFactory;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.security.SSLFactory;
import com.linkedin.venice.utils.ObjectMapperFactory;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class D2ControllerClient extends ControllerClient {
  private static final Logger LOGGER = LogManager.getLogger(D2ControllerClient.class);
  private static final String D2_SCHEME = "d2://";
  /**
   * {@link #DUMMY_URL_WHEN_USING_D2_CLIENT} is not used since {@link D2ControllerClient}
   * will use D2Client to fetch the controller URL.
   */
  private static final String DUMMY_URL_WHEN_USING_D2_CLIENT = "http://fake.host";

  private final String d2ServiceName;
  private final List<D2Client> d2Clients;
  private final boolean externalD2Client;
  private final List<String> d2ZkHosts;
  private final Optional<SSLFactory> sslFactory;

  @VisibleForTesting
  public D2ControllerClient(
      String d2ServiceName,
      String clusterName,
      String d2ZkHost,
      Optional<SSLFactory> sslFactory) {
    super(clusterName, DUMMY_URL_WHEN_USING_D2_CLIENT, sslFactory);
    this.d2ServiceName = d2ServiceName;
    this.d2Clients = Collections.singletonList(D2ClientFactory.getD2Client(d2ZkHost, sslFactory));
    this.externalD2Client = false;
    this.d2ZkHosts = Collections.singletonList(d2ZkHost);
    this.sslFactory = sslFactory;
  }

  public D2ControllerClient(String d2ServiceName, String clusterName, D2Client d2Client) {
    this(d2ServiceName, clusterName, d2Client, Optional.empty());
  }

  public D2ControllerClient(
      String d2ServiceName,
      String clusterName,
      D2Client d2Client,
      Optional<SSLFactory> sslFactory) {
    super(clusterName, DUMMY_URL_WHEN_USING_D2_CLIENT, sslFactory);
    this.d2ServiceName = d2ServiceName;
    this.d2Clients = Collections.singletonList(d2Client);
    this.externalD2Client = true;
    this.d2ZkHosts = null;
    this.sslFactory = sslFactory;
  }

  public D2ControllerClient(
      String d2ServiceName,
      String clusterName,
      List<D2Client> d2Clients,
      Optional<SSLFactory> sslFactory) {
    super(clusterName, DUMMY_URL_WHEN_USING_D2_CLIENT, sslFactory);
    this.d2ServiceName = d2ServiceName;
    this.d2Clients = d2Clients;
    this.externalD2Client = true;
    this.d2ZkHosts = null;
    this.sslFactory = sslFactory;
  }

  public D2ControllerClient(
      String d2ServiceName,
      String clusterName,
      Optional<SSLFactory> sslFactory,
      List<String> d2ZkHosts) {
    super(clusterName, DUMMY_URL_WHEN_USING_D2_CLIENT, sslFactory);
    this.d2ServiceName = d2ServiceName;
    this.d2Clients = d2ZkHosts.stream()
        .map(d2ZkHost -> D2ClientFactory.getD2Client(d2ZkHost, sslFactory))
        .collect(Collectors.toList());
    this.externalD2Client = false;
    this.d2ZkHosts = d2ZkHosts;
    this.sslFactory = sslFactory;
  }

  /**
   * Discover leader controller from a list of D2 Clients.
   * @return leader controller url
   */
  @Override
  protected String discoverLeaderController() {
    LeaderControllerResponse controllerResponse = null;
    for (D2Client d2Client: d2Clients) {
      try {
        controllerResponse = d2ClientGet(
            d2Client,
            d2ServiceName,
            ControllerRoute.LEADER_CONTROLLER.getPath(),
            newParams(),
            LeaderControllerResponse.class);
        // we get a successful response, so we break out of loop
        break;
      } catch (VeniceException e) {
        LOGGER.warn("Failed to discover leader controller with D2 client: " + d2Client, e);
      }
    }
    if (controllerResponse == null) {
      throw new VeniceException("Failed to discover leader controller with D2 client");
    }

    /**
     * Current controller D2 announcement is announcing url with http: prefix and the regular HTTP port number (1576);
     * if we change the D2 announcement, the existing Samza users which depend on D2 result would break; if we upgrade
     * Samza lib first, they would get the original D2 result (1576) which is wrong. To ensure none of these happen,
     * we need a synchronized deployment for updating D2 announcement and all samza users at the same time.. which is
     * impossible.
     *
     * The below logic is a workaround to allow client to recognize both the old D2 result and new D2 result, which
     * would achieve a smooth migration. Notice that all Samza customers must be upgraded first before changing D2 announcement.
     *
     * TODO: Remove the code below after controller ACL migration is completed.
     */
    if (sslFactory.isPresent()) {
      try {
        if (controllerResponse.getSecureUrl() != null) {
          return controllerResponse.getSecureUrl();
        }
        URL responseUrl = new URL(controllerResponse.getUrl());
        if (responseUrl.getProtocol().equalsIgnoreCase("http")) {
          URL secureControllerUrl = convertToSecureUrl(responseUrl);
          return secureControllerUrl.toString();
        }
      } catch (MalformedURLException e) {
        throw new VeniceException("Error when building URL.", e);
      }
    }
    return controllerResponse.getUrl();
  }

  private static <RESPONSE> RESPONSE d2ClientGet(
      D2Client d2Client,
      String d2ServiceName,
      String path,
      QueryParams params,
      Class<RESPONSE> responseClass) {
    String requestPath = D2_SCHEME + d2ServiceName + path + "?" + encodeQueryParams(params);
    try {
      RestResponse response = D2ClientUtils.sendD2GetRequest(requestPath, d2Client);
      String responseBody = response.getEntity().asString(StandardCharsets.UTF_8);
      return ObjectMapperFactory.getInstance().readValue(responseBody, responseClass);
    } catch (Exception e) {
      throw new VeniceException("Failed to get response for url: " + requestPath + " with D2 Client", e);
    }
  }

  public static D2ServiceDiscoveryResponse discoverCluster(D2Client d2Client, String d2ServiceName, String storeName) {
    return d2ClientGet(
        d2Client,
        d2ServiceName,
        ControllerRoute.CLUSTER_DISCOVERY.getPath(),
        getQueryParamsToDiscoverCluster(storeName),
        D2ServiceDiscoveryResponse.class);
  }

  @Deprecated
  public static D2ServiceDiscoveryResponse discoverCluster(
      String d2ZkHost,
      String d2ServiceName,
      String storeName,
      int retryAttempts) {
    return discoverCluster(d2ZkHost, d2ServiceName, storeName, retryAttempts, Optional.empty());
  }

  @Deprecated
  public static D2ServiceDiscoveryResponse discoverCluster(
      String d2ZkHost,
      String d2ServiceName,
      String storeName,
      int retryAttempts,
      Optional<SSLFactory> sslFactory) {
    D2Client d2Client;
    try {
      d2Client = D2ClientFactory.getD2Client(d2ZkHost, sslFactory);
      return discoverCluster(d2Client, d2ServiceName, storeName, retryAttempts);
    } finally {
      D2ClientFactory.release(d2ZkHost);
    }
  }

  public static D2ServiceDiscoveryResponse discoverCluster(
      D2Client d2Client,
      String d2ServiceName,
      String storeName,
      int retryAttempts) {
    try (D2ControllerClient client = new D2ControllerClient(d2ServiceName, "*", d2Client)) {
      return retryableRequest(client, retryAttempts, c -> discoverCluster(d2Client, d2ServiceName, storeName));
    }
  }

  @Override
  public D2ServiceDiscoveryResponse discoverCluster(String storeName) {
    for (D2Client d2Client: d2Clients) {
      try {
        return discoverCluster(d2Client, d2ServiceName, storeName, 1);
      } catch (Exception e) {
        LOGGER.warn("Failed to discover cluster with D2 client: " + d2Client, e);
      }
    }
    throw new VeniceException("Failed to discover cluster with D2 client");
  }

  @Override
  public void close() {
    if (D2ControllerClientFactory.release(this)) {
      // Object is no longer used in other places. Safe to clean up resources
      super.close();
      if (!externalD2Client) {
        for (String d2ZkHost: d2ZkHosts) {
          D2ClientFactory.release(d2ZkHost);
        }
      }
    } else {
      // Object is still in use at other places. Do not release resources right now.
    }
  }

  /**
   * Convert a HTTP url to HTTPS url with 1578 port number;
   * TODO: remove the below helper functions after Controller ACL migration.
   * @deprecated
   */
  @Deprecated
  public static URL convertToSecureUrl(URL url) throws MalformedURLException {
    return convertToSecureUrl(url, 1578);
  }

  /**
   * Convert a HTTP url to HTTPS url with specific port number;
   * TODO: remove the below helper functions after Controller ACL migration.
   * @deprecated
   */
  @Deprecated
  public static URL convertToSecureUrl(URL url, int port) throws MalformedURLException {
    return new URL("https", url.getHost(), port, url.getFile());
  }
}
