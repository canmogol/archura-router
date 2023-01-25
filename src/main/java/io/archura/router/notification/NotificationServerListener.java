package io.archura.router.notification;

import io.archura.router.notification.event.NotificationServerConnectedEvent;
import io.archura.router.notification.event.NotificationServerDisconnectedEvent;
import io.archura.router.notification.event.NotificationServerErrorEvent;
import io.archura.router.notification.event.NotificationServerTextMessageEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;

@Slf4j
@AllArgsConstructor
@Component
public class NotificationServerListener implements WebSocket.Listener {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void onOpen(WebSocket webSocket) {
        log.debug("Connected to notification server");
        Thread.startVirtualThread(() -> applicationEventPublisher.publishEvent(new NotificationServerConnectedEvent(this)));
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        log.debug("Received notification from notification server: {}", data);
        Thread.startVirtualThread(() -> applicationEventPublisher.publishEvent(new NotificationServerTextMessageEvent(this, data.toString())));
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        log.debug("Disconnected from notification server");
        Thread.startVirtualThread(() -> applicationEventPublisher.publishEvent(new NotificationServerDisconnectedEvent(this, statusCode, reason)));
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        log.error("Error occurred while communicating with notification server", error);
        Thread.startVirtualThread(() -> applicationEventPublisher.publishEvent(new NotificationServerErrorEvent(this, error)));
    }

}
