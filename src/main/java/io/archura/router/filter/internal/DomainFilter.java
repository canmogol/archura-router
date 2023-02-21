package io.archura.router.filter.internal;

import io.archura.router.config.GlobalConfiguration;
import io.archura.router.filter.ArchuraFilter;
import io.archura.router.filter.exception.ArchuraFilterException;
import io.archura.router.mapping.Mapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

import static io.archura.router.filter.ArchuraKeys.ARCHURA_CURRENT_DOMAIN;
import static java.util.Objects.isNull;

@Slf4j
@RequiredArgsConstructor
@Component
public class DomainFilter implements ArchuraFilter {
    private static final String HEADER_NAME_HOST = "Host";
    private final GlobalConfiguration globalConfiguration;
    private final Mapper mapper;

    @Override
    public void doFilter(
            final GlobalConfiguration.FilterConfiguration configuration,
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse
    ) throws ArchuraFilterException {
        log.debug("↓ DomainFilter started");
        final String host = httpServletRequest.getHeader(HEADER_NAME_HOST);
        if (isNull(host)) {
            throw new ArchuraFilterException(HttpStatus.BAD_REQUEST.value(), "Host header is missing");
        }
        final Map<String, GlobalConfiguration.DomainConfiguration> domains = globalConfiguration.getDomains();
        if (!domains.containsKey(host)) {
            final GlobalConfiguration.DomainConfiguration domainConfiguration = fetchDomainConfiguration(host);
            if (isNull(domainConfiguration)) {
                throw new ArchuraFilterException(HttpStatus.NOT_FOUND.value(), "Domain configuration not found for this host: '%s'".formatted(host));
            } else {
                domains.put(host, domainConfiguration);
            }
        }
        final GlobalConfiguration.DomainConfiguration domainConfiguration = domains.get(host);
        httpServletRequest.setAttribute(ARCHURA_CURRENT_DOMAIN, domainConfiguration);
        log.debug("\tcurrent domain set to: '{}'", domainConfiguration.getName());
        log.debug("↑ DomainFilter finished");
    }

    private GlobalConfiguration.DomainConfiguration fetchDomainConfiguration(final String domain) {
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
        return null;
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
                .timeout(Duration.ofMillis(globalConfiguration.getConfigurationServerConnectionTimeout()))
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

}
