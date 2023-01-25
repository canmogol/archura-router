package io.archura.router.filter;

import io.archura.router.config.GlobalConfiguration;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class InitialFilter implements Filter {

    private final GlobalConfiguration globalConfiguration;
    private final FilterFactory filterFactory;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.debug("InitialFilter initialized");
    }

    @Override
    public void doFilter(
            final ServletRequest servletRequest,
            final ServletResponse servletResponse,
            final FilterChain filterChain
    ) throws IOException, ServletException {

        if (servletRequest instanceof HttpServletRequest httpServletRequest
                && servletResponse instanceof HttpServletResponse httpServletResponse) {
            // run initial generic global filters
            globalConfiguration.getGlobalFilterConfigurations()
                    .forEach((key, value) -> {
                        final ArchuraFilter filter = filterFactory.create(key, value);
                        filter.doFilter(httpServletRequest, httpServletResponse);
                    });
            // Route
            //      run Tenant's filter
            //      run Customer's filters
            // return response
        }

        filterChain.doFilter(servletRequest, servletResponse);
    }

    @Override
    public void destroy() {
        log.debug("InitialFilter destroyed");
    }

}
