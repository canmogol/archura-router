package io.archura.router.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Objects.nonNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Configuration
public class GlobalConfiguration {

    @Value("${configuration.server.url:https://localhost:9010}")
    private URI configurationServerURL;

    @Value("#{${configuration.server.request.headers}}")
    private Map<String, String> configurationServerRequestHeaders = new HashMap<>();

    @Value("${configuration.server.connection.timeout:10000}")
    private long configurationServerConnectionTimeout;

    @Value("${configuration.server.retry.interval:10000}")
    private long configurationServerRetryInterval;

    @Value("${notification.server.url:https://localhost:9000}")
    private URI notificationServerURL;

    @Value("#{${notification.server.request.headers}}")
    private Map<String, String> notificationServerRequestHeaders = new HashMap<>();

    @Value("${notification.server.connection.timeout:10000}")
    private long notificationServerConnectionTimeout;

    @Value("${notification.server.retry.interval:10000}")
    private long notificationServerRetryInterval;

    private Map<String, FilterConfiguration> globalPreFilters = new HashMap<>();
    private Map<String, FilterConfiguration> globalPostFilters = new HashMap<>();
    private Map<String, DomainConfiguration> domains = new HashMap<>();

    public void copy(final GlobalConfiguration from) {
        if (nonNull(from)
                && nonNull(from.getConfigurationServerURL())
                && nonNull(from.getNotificationServerURL())
        ) {
            this.configurationServerURL = from.getConfigurationServerURL();
            this.notificationServerURL = from.getNotificationServerURL();
            this.configurationServerRequestHeaders.putAll(from.getConfigurationServerRequestHeaders());
            this.configurationServerRequestHeaders = from.getConfigurationServerRequestHeaders();
            this.configurationServerConnectionTimeout = from.getConfigurationServerConnectionTimeout();
            this.configurationServerRetryInterval = from.getConfigurationServerRetryInterval();
            this.notificationServerRequestHeaders = from.getNotificationServerRequestHeaders();
            this.notificationServerConnectionTimeout = from.getNotificationServerConnectionTimeout();
            this.notificationServerRetryInterval = from.getNotificationServerRetryInterval();
        }
    }

    public void deleteDomainConfigurations() {
        this.domains.clear();
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FilterConfiguration {
        private Map<String, String> parameters = new HashMap<>();
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DomainConfiguration {
        private String customerAccount;
        private Map<String, String> parameters = new HashMap<>();
        private Map<String, FilterConfiguration> domainPreFilters = new HashMap<>();
        private Map<String, FilterConfiguration> domainPostFilters = new HashMap<>();
        private Map<String, TenantConfiguration> tenants = new HashMap<>();

    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TenantConfiguration {
        private Map<String, FilterConfiguration> tenantPreFilters = new HashMap<>();
        private Map<String, FilterConfiguration> tenantPostFilters = new HashMap<>();
        private Map<String, List<RouteConfiguration>> methodRoutes = new HashMap<>();

    }

    @Data
    @Builder(toBuilder = true)
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RouteConfiguration {
        private String id;
        private Map<String, FilterConfiguration> routePreFilters = new HashMap<>();
        private Map<String, FilterConfiguration> routePostFilters = new HashMap<>();
        private MatchConfiguration matchConfiguration;
        private ExtractConfiguration extractConfiguration;
        private MapConfiguration mapConfiguration;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MatchConfiguration {
        private RoutePathConfiguration routePathConfiguration;
        private RouteHeaderConfiguration routeHeaderConfiguration;
        private RouteQueryConfiguration routeQueryConfiguration;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ExtractConfiguration {
        private RoutePathConfiguration routePathConfiguration;
        private RouteHeaderConfiguration routeHeaderConfiguration;
        private RouteQueryConfiguration routeQueryConfiguration;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RoutePathConfiguration {

        /**
         * Path to be matched.
         * i.e.
         * path: "/12345/user/1"
         * regex: "\/(?<tenantId>.*)\/user.*"
         * the 'tenantId' value will be 12345
         * the 'tenantId' will be extracted from the path and will be available in the 'match.path.tenantId' or 'extract.path.tenantId' variables.
         * You can refer to it using the ${match.path.tenantId} or ${extract.path.tenantId} placeholders.
         */
        private String regex;

        /**
         * Compiled pattern.
         */
        private Pattern pattern;

        /**
         * Capture groups to be extracted from the path.
         * i.e. ["tenantId"]
         */
        private List<String> captureGroups;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RouteHeaderConfiguration {

        /**
         * Name of the header to be matched.
         * i.e. 'Some-Request-Header'
         */
        private String name;

        /**
         * Header value to be matched.
         * i.e.
         * header value: "TenantId:12345"
         * regex: "TenantId:(?<tenantId>.*)"
         * the 'tenantId' value will be 12345
         * the 'tenantId' will be extracted from the header and will be available in the 'match.header.tenantId' or 'extract.header.tenantId' variables.
         * You can refer to it using the ${match.header.tenantId} or ${extract.header.tenantId} placeholders.
         */
        private String regex;

        /**
         * Compiled pattern.
         */
        private Pattern pattern;

        /**
         * Capture groups to be extracted from the path.
         * i.e. ["tenantId"]
         */
        private List<String> captureGroups;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RouteQueryConfiguration {
        /**
         * Name of the query parameter to be matched.
         * i.e. 'tenantId'
         */
        private String name;

        /**
         * Query parameter value to be matched.
         * i.e.
         * query parameter value: "12345"
         * regex: "(?<tenantId>.*)"
         * the 'tenantId' value will be 12345
         * the 'tenantId' will be extracted from the query and will be available in the 'match.query.tenantId' or 'extract.query.tenantId' variables.
         * You can refer to it using the ${match.query.tenantId} or ${extract.query.tenantId} placeholders.
         */
        private String regex;

        /**
         * Compiled pattern.
         */
        private Pattern pattern;

        /**
         * Capture groups to be extracted from the path.
         * i.e. ["tenantId"]
         */
        private List<String> captureGroups;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MapConfiguration {
        /**
         * URL to be mapped to.
         * i.e. "http://some-service-url/with-a-context/${match.path.GroupOne}?param=${extract.query.GroupTwo}&${request.query}"
         */
        private String url;

        /**
         * HTTP Method to be mapped to.
         * i.e. { "PUT": "POST" }
         */
        private Map<String, String> methodMap = new HashMap<>();

        /**
         * Headers to be mapped to.
         * i.e. { "X-A-New-Header" : "${match.header.GroupOne}" , "X-A-Generic-Header" : "${extract.header.GroupTwo}"}
         */
        private Map<String, String> headers = new HashMap<>();
    }

}

