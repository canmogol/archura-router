package io.archura.router.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.archura.router.config.GlobalConfiguration;
import io.archura.router.notification.event.NotificationServerConnectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

@Slf4j
@RequiredArgsConstructor
@Component
public class GlobalConfigurationListener implements ApplicationListener<NotificationServerConnectedEvent> {

    private final GlobalConfiguration globalConfiguration;
    private volatile boolean globalConfigurationFetched = false;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onApplicationEvent(final NotificationServerConnectedEvent event) {
        // delete domain configurations
        globalConfiguration.deleteDomainConfigurations();
        // fetch new configuration
        getGlobalConfiguration();
    }

    private void getGlobalConfiguration() {
        this.globalConfigurationFetched = false;
        final HttpRequest request = createHttpRequest();
        // loop until configuration is fetched
        while (!this.globalConfigurationFetched) {
            try {
                final GlobalConfiguration from = fetchGlobalConfiguration(request);
                // update global configuration
                globalConfiguration.copy(from);
                // break loop
                this.globalConfigurationFetched = true;
                log.debug("Configuration fetched from configuration server");
            } catch (IOException e) {
                waitAndContinue();
            }
        }
    }

    private HttpRequest createHttpRequest() {
        // prepare request builder
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        for (Map.Entry<String, String> entry : globalConfiguration.getConfigurationServerRequestHeaders().entrySet()) {
            builder = builder.header(entry.getKey(), entry.getValue());
        }
        // prepare request
        final String url = "%s/global".formatted(globalConfiguration.getConfigurationServerURL());
        return builder
                .uri(URI.create(url))
                .GET()
                .build();
    }

    private GlobalConfiguration fetchGlobalConfiguration(final HttpRequest request) throws IOException {
        try {
            final HttpClient httpClient = createHttpClient();
            // send request
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // handle response
            if (response.statusCode() != 200) {
                throw new IOException("Configuration server returned status code " + response.statusCode());
            }
            // parse response
            return objectMapper.readValue(response.body(), GlobalConfiguration.class);
        } catch (IOException | InterruptedException e) {
            final String error = "Failed to connect to configuration server, url: '%s', exception: '%s', message: '%s'"
                    .formatted(
                            request.uri().toURL(),
                            e.getClass().getSimpleName(),
                            e.getMessage()
                    );
            log.error(error);
            throw new ConnectException(error);
        }
    }

    private HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofMillis(globalConfiguration.getConfigurationServerConnectionTimeout()))
                .build();
    }

    private void waitAndContinue() {
        try {
            Thread.sleep(globalConfiguration.getConfigurationServerRetryInterval());
        } catch (InterruptedException interruptedException) {
            log.error("Failed to sleep", interruptedException);
        }
    }

}
