package com.provectus.kafka.ui.service.rbac;

import com.provectus.kafka.ui.config.auth.AuthenticatedUser;
import com.provectus.kafka.ui.config.auth.RoleBasedAccessControlProperties;
import com.provectus.kafka.ui.model.ClusterDTO;
import com.provectus.kafka.ui.model.ConnectDTO;
import com.provectus.kafka.ui.model.InternalTopic;
import com.provectus.kafka.ui.model.rbac.AccessContext;
import com.provectus.kafka.ui.model.rbac.Permission;
import com.provectus.kafka.ui.model.rbac.Resource;
import com.provectus.kafka.ui.model.rbac.Role;
import com.provectus.kafka.ui.model.rbac.permission.ConnectAction;
import com.provectus.kafka.ui.model.rbac.permission.ConsumerGroupAction;
import com.provectus.kafka.ui.model.rbac.permission.SchemaAction;
import com.provectus.kafka.ui.model.rbac.permission.TopicAction;
import com.provectus.kafka.ui.service.rbac.extractor.CognitoAuthorityExtractor;
import com.provectus.kafka.ui.service.rbac.extractor.GithubAuthorityExtractor;
import com.provectus.kafka.ui.service.rbac.extractor.GoogleAuthorityExtractor;
import com.provectus.kafka.ui.service.rbac.extractor.LdapAuthorityExtractor;
import com.provectus.kafka.ui.service.rbac.extractor.ProviderAuthorityExtractor;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(RoleBasedAccessControlProperties.class)
@Slf4j
public class AccessControlService {

  @Nullable
  private final InMemoryReactiveClientRegistrationRepository clientRegistrationRepository;

  private boolean rbacEnabled = false;
  private Set<ProviderAuthorityExtractor> extractors;
  private final RoleBasedAccessControlProperties properties;

  @PostConstruct
  public void init() {
    if (properties.getRoles().isEmpty()) {
      log.trace("No roles provided, disabling RBAC");
      return;
    }
    rbacEnabled = true;

    this.extractors = properties.getRoles()
        .stream()
        .map(role -> role.getSubjects()
            .stream()
            .map(provider -> switch (provider.getProvider()) {
              case OAUTH_COGNITO -> new CognitoAuthorityExtractor();
              case OAUTH_GOOGLE -> new GoogleAuthorityExtractor();
              case OAUTH_GITHUB -> new GithubAuthorityExtractor();
              case LDAP, LDAP_AD -> new LdapAuthorityExtractor();
            }).collect(Collectors.toSet()))
        .flatMap(Set::stream)
        .collect(Collectors.toSet());

    if ((clientRegistrationRepository == null || !clientRegistrationRepository.iterator().hasNext())
        && !properties.getRoles().isEmpty()) {
      log.error("Roles are configured but no authentication methods are present. Authentication might fail.");
    }
  }

  public Mono<Void> validateAccess(AccessContext context) {
    if (!rbacEnabled) {
      return Mono.empty();
    }

    return getUser(context.getExchange())
        .doOnNext(user -> {
          boolean accessGranted =
              isClusterAccessible(context, user)
                  && isClusterConfigAccessible(context, user)
                  && isTopicAccessible(context, user)
                  && isConsumerGroupAccessible(context, user)
                  && isConnectAccessible(context, user)
                  && isConnectorAccessible(context, user) // TODO connector selectors
                  && isSchemaAccessible(context, user)
                  && isKsqlAccessible(context, user);

          if (!accessGranted) {
            throw new AccessDeniedException("Access denied");
          }
        })
        .then();
  }

  public Mono<AuthenticatedUser> getUser(ServerWebExchange exchange) {
    return ReactiveSecurityContextHolder.getContext()
        .map(SecurityContext::getAuthentication)
        .map(Principal::getName)
        .flatMap(name -> exchange.getSession().map(a -> {
          Set<String> groups = a.getAttribute("GROUPS");
          return new AuthenticatedUser(name, Objects.requireNonNullElse(groups, Collections.emptySet()));
        }));
  }

  private boolean isClusterAccessible(AccessContext context, AuthenticatedUser user) {
    if (!rbacEnabled) {
      return true;
    }

    Assert.isTrue(StringUtils.isNotEmpty(context.getCluster()), "cluster value is empty");

    return properties.getRoles()
        .stream()
        .filter(filterRole(user))
        .anyMatch(filterCluster(context.getCluster()));
  }

  public Mono<Boolean> isClusterAccessible(ClusterDTO cluster, ServerWebExchange exchange) {
    if (!rbacEnabled) {
      return Mono.just(true);
    }

    AccessContext accessContext = AccessContext
        .builder(exchange)
        .cluster(cluster.getName())
        .build();

    return getUser(exchange).map(u -> isClusterAccessible(accessContext, u));
  }

  public boolean isClusterConfigAccessible(AccessContext context, AuthenticatedUser user) {
    if (!rbacEnabled) {
      return true;
    }

    if (CollectionUtils.isEmpty(context.getClusterConfigActions())) {
      return true;
    }
    Assert.isTrue(StringUtils.isNotEmpty(context.getCluster()), "cluster value is empty");

    Set<String> requiredActions = context.getClusterConfigActions()
        .stream()
        .map(a -> a.toString().toUpperCase())
        .collect(Collectors.toSet());

    return isAccessible(Resource.CLUSTERCONFIG, context.getCluster(), user, context, requiredActions);
  }

  public boolean isTopicAccessible(AccessContext context, AuthenticatedUser user) {
    if (!rbacEnabled) {
      return true;
    }

    if (context.getTopic() == null && context.getTopicActions().isEmpty()) {
      return true;
    }
    Assert.isTrue(!context.getTopicActions().isEmpty(), "actions are empty");

    Set<String> requiredActions = context.getTopicActions()
        .stream()
        .map(a -> a.toString().toUpperCase())
        .collect(Collectors.toSet());

    return isAccessible(Resource.TOPIC, context.getTopic(), user, context, requiredActions);
  }

  public Mono<Boolean> isTopicAccessible(InternalTopic dto, String clusterName, ServerWebExchange exchange) {
    if (!rbacEnabled) {
      return Mono.just(true);
    }

    AccessContext accessContext = AccessContext
        .builder(exchange)
        .cluster(clusterName)
        .topic(dto.getName())
        .topicActions(TopicAction.VIEW)
        .build();

    return getUser(exchange).map(u -> isTopicAccessible(accessContext, u));
  }

  private boolean isConsumerGroupAccessible(AccessContext context, AuthenticatedUser user) {
    if (!rbacEnabled) {
      return true;
    }

    if (context.getConsumerGroup() == null && context.getConsumerGroupActions().isEmpty()) {
      return true;
    }
    Assert.isTrue(!context.getConsumerGroupActions().isEmpty(), "actions are empty");

    Set<String> requiredActions = context.getConsumerGroupActions()
        .stream()
        .map(a -> a.toString().toUpperCase())
        .collect(Collectors.toSet());

    return isAccessible(Resource.CONSUMER, context.getConsumerGroup(), user, context, requiredActions);
  }

  public Mono<Boolean> isConsumerGroupAccessible(String groupId, String clusterName, ServerWebExchange exchange) {
    if (!rbacEnabled) {
      return Mono.just(true);
    }

    AccessContext accessContext = AccessContext
        .builder(exchange)
        .cluster(clusterName)
        .consumerGroup(groupId)
        .consumerGroupActions(ConsumerGroupAction.VIEW)
        .build();

    return getUser(exchange).map(u -> isConsumerGroupAccessible(accessContext, u));
  }

  public boolean isSchemaAccessible(AccessContext context, AuthenticatedUser user) {
    if (!rbacEnabled) {
      return true;
    }

    if (context.getSchema() == null && context.getSchemaActions().isEmpty()) {
      return true;
    }
    Assert.isTrue(!context.getSchemaActions().isEmpty(), "actions are empty");

    Set<String> requiredActions = context.getSchemaActions()
        .stream()
        .map(a -> a.toString().toUpperCase())
        .collect(Collectors.toSet());

    return isAccessible(Resource.SCHEMA, context.getSchema(), user, context, requiredActions);
  }

  public Mono<Boolean> isSchemaAccessible(String schema, String clusterName, ServerWebExchange exchange) {
    if (!rbacEnabled) {
      return Mono.just(true);
    }

    AccessContext accessContext = AccessContext
        .builder(exchange)
        .cluster(clusterName)
        .schema(schema)
        .schemaActions(SchemaAction.VIEW)
        .build();

    return getUser(exchange).map(u -> isSchemaAccessible(accessContext, u));
  }

  public boolean isConnectAccessible(AccessContext context, AuthenticatedUser user) {
    if (!rbacEnabled) {
      return true;
    }

    if (context.getConnect() == null && context.getConnectActions().isEmpty()) {
      return true;
    }
    Assert.isTrue(!context.getConnectActions().isEmpty(), "actions are empty");

    Set<String> requiredActions = context.getConnectActions()
        .stream()
        .map(a -> a.toString().toUpperCase())
        .collect(Collectors.toSet());

    return isAccessible(Resource.CONNECT, context.getConnect(), user, context, requiredActions);
  }

  public Mono<Boolean> isConnectAccessible(ConnectDTO dto, String clusterName, ServerWebExchange exchange) {
    if (!rbacEnabled) {
      return Mono.just(true);
    }

    return isConnectAccessible(dto.getName(), clusterName, exchange);
  }

  public Mono<Boolean> isConnectAccessible(String connectName, String clusterName, ServerWebExchange exchange) {
    if (!rbacEnabled) {
      return Mono.just(true);
    }

    AccessContext accessContext = AccessContext
        .builder(exchange)
        .cluster(clusterName)
        .connect(connectName)
        .connectActions(ConnectAction.VIEW)
        .build();

    return getUser(exchange).map(u -> isConnectAccessible(accessContext, u));
  }

  public boolean isConnectorAccessible(AccessContext context, AuthenticatedUser user) {
    if (!rbacEnabled) {
      return true;
    }

    return isConnectAccessible(context, user);
  }

  public Mono<Boolean> isConnectorAccessible(String connectName, String connectorName, String clusterName,
                                             ServerWebExchange exchange) {
    if (!rbacEnabled) {
      return Mono.just(true);
    }

    AccessContext accessContext = AccessContext
        .builder(exchange)
        .cluster(clusterName)
        .connect(connectName)
        .connectActions(ConnectAction.VIEW)
        .connector(connectorName)
        .build();

    return getUser(exchange).map(u -> isConnectorAccessible(accessContext, u));
  }

  private boolean isKsqlAccessible(AccessContext context, AuthenticatedUser user) {
    if (!rbacEnabled) {
      return true;
    }

    if (context.getKsqlActions().isEmpty()) {
      return true;
    }

    Set<String> requiredActions = context.getKsqlActions()
        .stream()
        .map(a -> a.toString().toUpperCase())
        .collect(Collectors.toSet());

    return isAccessible(Resource.KSQL, null, user, context, requiredActions);
  }

  public Set<ProviderAuthorityExtractor> getExtractors() {
    return extractors;
  }

  public List<Role> getRoles() {
    return Collections.unmodifiableList(properties.getRoles());
  }

  private boolean isAccessible(Resource resource, String resourceValue,
                               AuthenticatedUser user, AccessContext context, Set<String> requiredActions) {
    Set<String> grantedActions = properties.getRoles()
        .stream()
        .filter(filterRole(user))
        .filter(filterCluster(context.getCluster()))
        .flatMap(grantedRole -> grantedRole.getPermissions().stream())
        .filter(filterResource(resource))
        .filter(filterResourceValue(resourceValue))
        .flatMap(grantedPermission -> grantedPermission.getActions().stream())
        .map(String::toUpperCase)
        .collect(Collectors.toSet());

    return grantedActions.containsAll(requiredActions);
  }

  private Predicate<Role> filterRole(AuthenticatedUser user) {
    return role -> user.groups().contains(role.getName());
  }

  private Predicate<Role> filterCluster(String cluster) {
    return grantedRole -> grantedRole.getClusters()
        .stream()
        .anyMatch(cluster::equalsIgnoreCase);
  }

  private Predicate<Permission> filterResource(Resource resource) {
    return grantedPermission -> resource == grantedPermission.getResource();
  }

  private Predicate<Permission> filterResourceValue(String resourceValue) {

    if (resourceValue == null) {
      return grantedPermission -> true;
    }
    return grantedPermission -> {
      Pattern value = grantedPermission.getValue();
      if (value == null) {
        return true;
      }
      return value.matcher(resourceValue).matches();
    };
  }

  public boolean isRbacEnabled() {
    return rbacEnabled;
  }
}
