package io.archura.router.filter.internal;

import io.archura.router.config.GlobalConfiguration;
import io.archura.router.filter.ArchuraFilter;
import io.archura.router.mapping.Mapper;
import io.archura.router.filter.exception.ArchuraFilterException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static java.util.Objects.nonNull;

@Slf4j
public class DomainFilter implements ArchuraFilter {
    public static final String DEFAULT_DOMAIN = "default";
    public static final String DEFAULT_CUSTOMER_ACCOUNT = "default";
    private GlobalConfiguration globalConfiguration;
    private Mapper mapper;

    @Override
    public void doFilter(HttpServletRequest httpServletRequest, HttpServletResponse response) throws ArchuraFilterException {
        final String host = nonNull(httpServletRequest.getHeader("Host")) ? httpServletRequest.getHeader("Host") : "localhost";
        final Map<String, GlobalConfiguration.DomainConfiguration> domains = globalConfiguration.getDomains();
        if (!domains.containsKey(host)) {
            final Optional<GlobalConfiguration.DomainConfiguration> optionalDomainConfiguration = fetchDomainConfiguration(host);
            optionalDomainConfiguration.ifPresent(domainConfiguration -> domains.put(host, domainConfiguration));
        }
        if (domains.containsKey(host)) {
            httpServletRequest.setAttribute("archura.domain", host);
            httpServletRequest.setAttribute("archura.customer", domains.get(host).getCustomerAccount());
        } else {
            httpServletRequest.setAttribute("archura.domain", DEFAULT_DOMAIN);
            httpServletRequest.setAttribute("archura.customer", DEFAULT_CUSTOMER_ACCOUNT);
        }
    }

    private Optional<GlobalConfiguration.DomainConfiguration> fetchDomainConfiguration(final String domain) {
        final HttpClient httpClient = createHttpClient();
        final HttpRequest httpRequest = createHttpRequest(domain);
        try {
            // send request
            final HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            // handle response
            if (response.statusCode() == 200) {
                // parse response
                return mapper.readValue(response.body(), GlobalConfiguration.DomainConfiguration.class);
            } else {
                log.error("Error while fetching domain configuration, status code: {}", response.statusCode());
            }
        } catch (InterruptedException | IOException e) {
            log.error("Error while fetching domain configuration", e);
        }
        return Optional.empty();
    }

    private HttpRequest createHttpRequest(final String domain) {
        // prepare request builder
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        for (Map.Entry<String, String> entry : globalConfiguration.getConfigurationServerRequestHeaders().entrySet()) {
            builder = builder.header(entry.getKey(), entry.getValue());
        }
        // prepare request
        final String url = "%s/domain/%s".formatted(globalConfiguration.getConfigurationServerURL(), domain);
        final URI uri = URI.create(url);
        return builder
                .uri(uri)
                .GET()
                .build();
    }

    private HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofMillis(globalConfiguration.getConfigurationServerConnectionTimeout()))
                .build();
    }

    @Override
    public void setGlobalConfiguration(GlobalConfiguration globalConfiguration) {
        this.globalConfiguration = globalConfiguration;
    }

    @Override
    public void setMapper(Mapper mapper) {
        this.mapper = mapper;
    }
}
