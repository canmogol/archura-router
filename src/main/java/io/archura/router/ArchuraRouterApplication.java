package io.archura.router;

import io.archura.router.notification.NotificationServerConnector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.Executors;

@Slf4j
@EnableAutoConfiguration
@SpringBootApplication
public class ArchuraRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchuraRouterApplication.class, args);
    }

    @Bean
    public TomcatProtocolHandlerCustomizer<?> tomcatProtocolHandlerCustomizer() {
        return protocolHandler -> {
            protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        };
    }

    @Bean
    public ApplicationRunner applicationRunner(final NotificationServerConnector notificationServerConnector) {
        return args -> notificationServerConnector.connect();
    }


}
