package io.archura.router.config;

import io.archura.router.compat.ArchuraObjectMapper;
import io.archura.router.mapping.Mapper;
import io.archura.router.notification.NotificationServerConnector;
import org.apache.coyote.http2.Http2Protocol;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

@Configuration
@EnableAutoConfiguration
public class ApplicationConfiguration {

    @Bean
    public TomcatProtocolHandlerCustomizer<?> tomcatProtocolHandlerCustomizer() {
        return protocolHandler -> protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    @Bean
    public ConfigurableServletWebServerFactory tomcatCustomizer() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.addConnectorCustomizers(connector -> connector.addUpgradeProtocol(new Http2Protocol()));
        return factory;
    }

    @Bean
    public ApplicationRunner applicationRunner(final NotificationServerConnector notificationServerConnector) {
        return args -> notificationServerConnector.connect();
    }

    @Bean
    public Mapper mapper(final ArchuraObjectMapper mapper) {
        return mapper;
    }

}
