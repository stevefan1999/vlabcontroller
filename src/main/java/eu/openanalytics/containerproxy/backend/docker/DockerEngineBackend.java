package eu.openanalytics.containerproxy.backend.docker;

import com.spotify.docker.client.DockerClient.RemoveContainerParam;
import com.spotify.docker.client.messages.*;
import com.spotify.docker.client.messages.HostConfig.Builder;
import eu.openanalytics.containerproxy.model.runtime.Container;
import eu.openanalytics.containerproxy.model.runtime.Proxy;
import eu.openanalytics.containerproxy.model.spec.ContainerSpec;

import java.net.URI;
import java.net.URL;
import java.util.*;

public class DockerEngineBackend extends AbstractDockerBackend {
  
  @Override
  protected Container startContainer(ContainerSpec spec, Proxy proxy) throws Exception {
    Builder hostConfigBuilder = HostConfig.builder();
    
    Map<String, List<PortBinding>> portBindings = new HashMap<>();
    if (isUseInternalNetwork()) {
      // In internal networking mode, we can access container ports directly, no need to bind on host.
    } else {
      // Allocate ports on the docker host to proxy to.
      for (Integer containerPort : spec.getPortMapping().values()) {
        int hostPort = portAllocator.allocate(proxy.getId());
        portBindings.put(String.valueOf(containerPort), Collections.singletonList(PortBinding.of("0.0.0.0", hostPort)));
      }
    }
    hostConfigBuilder.portBindings(portBindings);
    
    hostConfigBuilder.memoryReservation(memoryToBytes(spec.getMemoryRequest()));
    hostConfigBuilder.memory(memoryToBytes(spec.getMemoryLimit()));
    if (spec.getCpuLimit() != null) {
      // Workaround, see https://github.com/spotify/docker-client/issues/959
      long period = 100000;
      long quota = (long) (period * Float.parseFloat(spec.getCpuLimit()));
      hostConfigBuilder.cpuPeriod(period);
      hostConfigBuilder.cpuQuota(quota);
    }
    
    Optional.ofNullable(spec.getNetwork()).ifPresent(n -> hostConfigBuilder.networkMode(spec.getNetwork()));
    Optional.ofNullable(spec.getDns()).ifPresent(dns -> hostConfigBuilder.dns(dns));
    Optional.ofNullable(spec.getVolumes()).ifPresent(v -> hostConfigBuilder.binds(v));
    hostConfigBuilder.privileged(isPrivileged() || spec.isPrivileged());
    
    Map<String, String> labels = spec.getLabels();
    spec.getRuntimeLabels().forEach((key, value) -> labels.put(key, value.getSecond()));
    
    ContainerConfig containerConfig = ContainerConfig.builder()
      .hostConfig(hostConfigBuilder.build())
      .image(spec.getImage())
      .labels(labels)
      .exposedPorts(portBindings.keySet())
      .cmd(spec.getCmd())
      .env(buildEnv(spec, proxy))
      .build();
    ContainerCreation containerCreation = dockerClient.createContainer(containerConfig);
    
    if (spec.getNetworkConnections() != null) {
      for (String networkConnection : spec.getNetworkConnections()) {
        dockerClient.connectToNetwork(containerCreation.id(), networkConnection);
      }
    }
    
    dockerClient.startContainer(containerCreation.id());
    
    Container container = new Container();
    container.setSpec(spec);
    container.setId(containerCreation.id());
    
    // Calculate proxy routes for all configured ports.
    for (String mappingKey : spec.getPortMapping().keySet()) {
      int containerPort = spec.getPortMapping().get(mappingKey);
      
      List<PortBinding> binding = portBindings.get(String.valueOf(containerPort));
      int hostPort = Integer.valueOf(Optional.ofNullable(binding).map(pb -> pb.get(0).hostPort()).orElse("0"));
      
      String mapping = mappingStrategy.createMapping(mappingKey, container, proxy);
      URI target = calculateTarget(container, containerPort, hostPort);
      proxy.getTargets().put(mapping, target);
    }
    
    return container;
  }
  
  protected URI calculateTarget(Container container, int containerPort, int hostPort) throws Exception {
    String targetProtocol;
    String targetHostName;
    String targetPort;
    
    if (isUseInternalNetwork()) {
      targetProtocol = getProperty(PROPERTY_CONTAINER_PROTOCOL, DEFAULT_TARGET_PROTOCOL);
      
      // For internal networks, DNS resolution by name only works with custom names.
      // See comments on https://github.com/docker/for-win/issues/1009
      ContainerInfo info = dockerClient.inspectContainer(container.getId());
      targetHostName = info.config().hostname();
//			targetHostName = container.getName();
      
      targetPort = String.valueOf(containerPort);
    } else {
      URL hostURL = new URL(getProperty(PROPERTY_URL, DEFAULT_TARGET_URL));
      targetProtocol = getProperty(PROPERTY_CONTAINER_PROTOCOL, hostURL.getProtocol());
      targetHostName = hostURL.getHost();
      targetPort = String.valueOf(hostPort);
    }
    
    return new URI(String.format("%s://%s:%s", targetProtocol, targetHostName, targetPort));
  }
  
  @Override
  protected void doStopProxy(Proxy proxy) throws Exception {
    for (Container container : proxy.getContainers()) {
      String[] networkConnections = container.getSpec().getNetworkConnections();
      if (networkConnections != null) {
        for (String conn : networkConnections) {
          dockerClient.disconnectFromNetwork(container.getId(), conn);
        }
      }
      dockerClient.removeContainer(container.getId(), RemoveContainerParam.forceKill());
    }
    portAllocator.release(proxy.getId());
  }
  
}
