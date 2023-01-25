package io.archura.router.filter;

import io.archura.router.config.GlobalConfiguration;
import io.archura.router.filter.internal.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.nonNull;

@Component
public class FilterFactory {

    @Autowired(required = false)
    private CustomFilterRegistry customFilterRegistry;

    public ArchuraFilter create(final String filterName, final GlobalConfiguration.FilterConfiguration configuration) {
        final ArchuraFilter filter = findFilter(filterName);
        filter.setConfiguration(configuration);
        return filter;
    }

    private ArchuraFilter findFilter(String filterName) {
        if (nonNull(customFilterRegistry)) {
            final Optional<ArchuraFilter> customFilter;
            customFilter = customFilterRegistry.findFilter(filterName);
            if (customFilter.isPresent()) {
                return customFilter.get();
            }
        }
        return switch (filterName) {
            case "Domain" -> new DomainFilter();
            case "Throttling" -> new ThrottlingFilter();
            case "RateLimiting" -> new RateLimitingFilter();
            case "Tenant" -> new TenantFilter();
            case "RouteMatching" -> new RouteMatchingFilter();
            case "ZeroDeployment" -> new ZeroDeploymentFilter();
            case "Authentication" -> new AuthenticationFilter();
            case "Authorization" -> new AuthorizationFilter();
            case "Auditing" -> new AuditingFilter();
            case "Header" -> new HeaderFilter();
            case "Caching" -> new CachingFilter();
            case "Webhook" -> new WebhookFilter();
            case "PredefinedResponse" -> new PredefinedResponseFilter();
            case "Timeout" -> new TimeoutFilter();
            case "Retry" -> new RetryFilter();
            case "Parallelization" -> new ParallelizationFilter();
            case "CircuitBreaker" -> new CircuitBreakerFilter();
            case "ExternalHttp" -> new ExternalHttpFilter();
            case "Routing" -> new RoutingFilter();
            default -> new UnknownFilter(filterName);
        };
    }
}
