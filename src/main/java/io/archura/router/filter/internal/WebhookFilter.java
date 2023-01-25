package io.archura.router.filter.internal;

import io.archura.router.filter.ArchuraFilter;
import io.archura.router.filter.exception.ArchuraFilterException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WebhookFilter implements ArchuraFilter {
    @Override
    public void doFilter(HttpServletRequest httpServletRequest, HttpServletResponse response) throws ArchuraFilterException {
        log.debug("WebhookFilter");
    }
}
