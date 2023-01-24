package io.archura.router.notification.event;

import org.springframework.context.ApplicationEvent;

import java.net.http.WebSocket;

public class NotificationServerTextMessageEvent extends ApplicationEvent {
    private final String text;

    public NotificationServerTextMessageEvent(WebSocket.Listener listener, String text) {
        super(listener);
        this.text = text;
    }

    public String getText() {
        return text;
    }

    @Override
    public WebSocket.Listener getSource() {
        return (WebSocket.Listener) super.getSource();
    }
}
