package io.archura.router.configuration.event;

import org.springframework.context.ApplicationEvent;

import java.util.EventListener;

public class GlobalConfigurationFetchFailedEvent extends ApplicationEvent {
    private final Exception exception;

    public GlobalConfigurationFetchFailedEvent(EventListener listener, Exception exception) {
        super(listener);
        this.exception = exception;
    }

    public Exception getException() {
        return exception;
    }

    @Override
    public EventListener getSource() {
        return (EventListener) super.getSource();
    }
}
