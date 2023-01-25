package io.archura.router.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

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

    private Map<String, FilterConfiguration> globalFilterConfigurations = new HashMap<>();
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
        private String name;
        private Map<String, String> configuration = new HashMap<>();
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DomainConfiguration {
        private String name;
        private String customerAccount;
    }
}
