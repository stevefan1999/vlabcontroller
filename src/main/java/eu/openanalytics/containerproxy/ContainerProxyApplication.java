package eu.openanalytics.containerproxy;

import com.fasterxml.jackson.datatype.jsr353.JSR353Module;
import eu.openanalytics.containerproxy.util.ProxyMappingManager;
import io.undertow.Handlers;
import io.undertow.servlet.api.ServletSessionConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.redis.RedisHealthIndicator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.filter.FormContentFilter;

import javax.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Properties;

@SpringBootApplication
@ComponentScan("eu.openanalytics")
public class ContainerProxyApplication {
  public static final String CONFIG_FILENAME = "application.yml";
  public static final String CONFIG_DEMO_PROFILE = "demo";
  private final Logger log = LogManager.getLogger(getClass());
  private final Environment environment;
  private final ProxyMappingManager mappingManager;
  private final DefaultCookieSerializer defaultCookieSerializer;
  
  public ContainerProxyApplication(Environment environment, ProxyMappingManager mappingManager, DefaultCookieSerializer defaultCookieSerializer) {
    this.environment = environment;
    this.mappingManager = mappingManager;
    this.defaultCookieSerializer = defaultCookieSerializer;
  }
  
  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(ContainerProxyApplication.class);
    
    String configFilename = System.getenv("SPRING_CONFIG_LOCATION");
    for (String arg : args) {
      String pattern = "spring.config.location=";
      int idx = arg.indexOf(pattern);
      if (idx > -1) configFilename = arg.substring(idx + pattern.length());
      break;
    }
    if (configFilename == null) configFilename = CONFIG_FILENAME;
    boolean hasExternalConfig = Files.exists(Paths.get(configFilename));
    if (!hasExternalConfig) app.setAdditionalProfiles(CONFIG_DEMO_PROFILE);
    
    setDefaultProperties(app);
    
    try {
      app.setLogStartupInfo(false);
      app.run(args);
    } catch (Exception e) {
      // Workaround for bug in UndertowEmbeddedServletContainer.start():
      // If undertow.start() fails, started remains false which prevents undertow.stop() from ever being called.
      // Undertow's (non-daemon) XNIO worker threads will then prevent the JVM from exiting.
      if (e instanceof PortInUseException) System.exit(-1);
    }
  }
  
  private static void setDefaultProperties(SpringApplication app) {
    Properties properties = new Properties();
    
    // use in-memory session storage by default. Can be overwritten in application.yml
    properties.put("spring.session.store-type", "none");
    // required for proper working of the SP_USER_INITIATED_LOGOUT session attribute in the UserService
    properties.put("spring.session.redis.flush-mode", "IMMEDIATE");
    
    // disable multi-part handling by Spring. We don't need this anywhere in the application.
    // When enabled this will cause problems when proxying file-uploads to the shiny apps.
    properties.put("spring.servlet.multipart.enabled", "false");
    
    // disable logging of requests, since this reads part of the requests and therefore undertow is unable to correctly handle those requests
    properties.put("logging.level.org.springframework.web.servlet.DispatcherServlet", "INFO");
    
    properties.put("spring.application.name", "ContainerProxy");
    
    // Metrics configuration
    // ====================
    
    // disable all supported exporters by default
    // Note: if we upgrade to Spring Boot 2.4.0 we can use properties.put("management.metrics.export.defaults.enabled", "false");
    properties.put("management.metrics.export.prometheus.enabled", "false");
    properties.put("management.metrics.export.influx.enabled", "false");
    // set actuator to port 9090 (can be overwritten)
    properties.put("management.server.port", "9090");
    // enable prometheus endpoint by default (but not the exporter)
    properties.put("management.endpoint.prometheus.enabled", "true");
    // include prometheus and health endpoint in exposure
    properties.put("management.endpoints.web.exposure.include", "health,prometheus");
    
    // ====================
    
    // Health configuration
    // ====================
    
    // enable redisSession check for the readiness probe
    properties.put("management.endpoint.health.group.readiness.include", "readinessProbe,redisSession");
    // disable ldap health endpoint
    properties.put("management.health.ldap.enabled", false);
    // disable default redis health endpoint since it's managed by redisSession
    properties.put("management.health.redis.enabled", "false");
    // enable Kubernetes probes
    properties.put("management.endpoint.health.probes.enabled", true);
    
    // ====================
    
    app.setDefaultProperties(properties);
  }
  
  @PostConstruct
  public void init() {
    if (environment.getProperty("server.use-forward-headers") != null) {
      log.warn("WARNING: Using server.use-forward-headers will not work in this ShinyProxy release, you need to change your configuration to use another property. See https://shinyproxy.io/documentation/security/#forward-headers on how to change your configuration.");
    }
    
    String sameSiteCookie = environment.getProperty("proxy.same-site-cookie", "Lax");
    log.debug("Setting sameSiteCookie policy to {}", sameSiteCookie);
    defaultCookieSerializer.setSameSite(sameSiteCookie);
    String proxyIdentifier = environment.getProperty("proxy.identifier-value");
    if (proxyIdentifier != null && !proxyIdentifier.isEmpty()) {
      defaultCookieSerializer.setCookieName("SESSION_" + proxyIdentifier.toUpperCase());
    }
  }
  
  // Disable specific Spring filters that parse the request body, preventing it from being proxied.
  
  @Bean
  public UndertowServletWebServerFactory servletContainer() {
    UndertowServletWebServerFactory factory = new UndertowServletWebServerFactory();
    factory.addDeploymentInfoCustomizers(info -> {
      info.setPreservePathOnForward(false); // required for the /api/route/{id}/ endpoint to work properly
      if (Boolean.valueOf(environment.getProperty("logging.requestdump", "false"))) {
        info.addOuterHandlerChainWrapper(defaultHandler -> Handlers.requestDump(defaultHandler));
      }
      info.addInnerHandlerChainWrapper(defaultHandler -> {
        return mappingManager.createHttpHandler(defaultHandler);
      });
      ServletSessionConfig sessionConfig = new ServletSessionConfig();
      sessionConfig.setHttpOnly(true);
      sessionConfig.setSecure(Boolean.valueOf(environment.getProperty("server.secureCookies", "false")));
      info.setServletSessionConfig(sessionConfig);
    });
    try {
      factory.setAddress(InetAddress.getByName(environment.getProperty("proxy.bind-address", "0.0.0.0")));
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("Invalid bind address specified", e);
    }
    factory.setPort(Integer.parseInt(environment.getProperty("proxy.port", "8080")));
    return factory;
  }
  
  @Bean
  public FilterRegistrationBean<FormContentFilter> registration2(FormContentFilter filter) {
    FilterRegistrationBean<FormContentFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
  
  /**
   * Register the Jackson module which implements compatibility between javax.json and Jackson.
   *
   * @return
   */
  @Bean
  public JSR353Module jsr353Module() {
    return new JSR353Module();
  }
  
  @Bean
  public HealthIndicator redisSessionHealthIndicator(RedisConnectionFactory rdeRedisConnectionFactory) {
    if (Objects.equals(environment.getProperty("spring.session.store-type"), "redis")) {
      // if we are using redis for session -> use a proper health check for redis
      return new RedisHealthIndicator(rdeRedisConnectionFactory);
    } else {
      // not using redis for session -> just pretend it's always online
      return new HealthIndicator() {
        
        @Override
        public Health getHealth(boolean includeDetails) {
          return Health.up().build();
        }
        
        @Override
        public Health health() {
          return Health.up().build();
        }
      };
    }
  }
  
  /**
   * This Bean ensures that User Session are properly expired when using Redis for session storage.
   */
  @Bean
  @ConditionalOnProperty(name = "spring.session.store-type", havingValue = "redis")
  public <S extends Session> SessionRegistry sessionRegistry(FindByIndexNameSessionRepository<S> sessionRepository) {
    return new SpringSessionBackedSessionRegistry<S>(sessionRepository);
  }
  
  @Bean
  public HttpSessionEventPublisher httpSessionEventPublisher() {
    return new HttpSessionEventPublisher();
  }
  
}
