package io.archura.router.filter;

import io.archura.router.config.GlobalConfiguration;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static java.util.Objects.isNull;

@Slf4j
@Component
@RequiredArgsConstructor
public class InitialFilter implements Filter {

    private static final String ARCHURA_DOMAIN = "archura.domain";
    private static final String DEFAULT_DOMAIN = "default";
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

            // global pre-filters
            globalConfiguration.getGlobalPreFilters()
                    .forEach((name, configuration) -> runFilter(httpServletRequest, httpServletResponse, name, configuration));

            // domain pre-filters
            if (isNull(httpServletRequest.getAttribute(ARCHURA_DOMAIN))) {
                httpServletRequest.setAttribute(ARCHURA_DOMAIN, DEFAULT_DOMAIN);
            }
            final String domain = String.valueOf(httpServletRequest.getAttribute(ARCHURA_DOMAIN));
            final GlobalConfiguration.DomainConfiguration domainConfiguration = globalConfiguration.getDomains().get(domain);
            domainConfiguration.getDomainPreFilters()
                    .forEach((name, configuration) -> runFilter(httpServletRequest, httpServletResponse, name, configuration));

            // tenant pre-filters

            // route pre-filters

            // send downstream request
            // Routing

            List.of(globalConfiguration.getGlobalPostFilters(),
                    globalConfiguration.getDomainPostFilters(),
                    globalConfiguration.getTenantPostFilters()
            ).forEach(runFilters(httpServletRequest, httpServletResponse));

            // comes from downstream server
            final int status = HttpServletResponse.SC_OK;
            final String contentType = "text/plain";
            final String content = "Hello World";
            final String charset = "UTF-8";
            // combined with downstream server's headers
            final Map<String, String> headers = Map.of("x-a-header1", "Value1");

            // write response
            httpServletResponse.setStatus(status);
            httpServletResponse.setContentType(contentType);
            httpServletResponse.setContentLength(content.length());
            httpServletResponse.setCharacterEncoding(charset);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                httpServletResponse.setHeader(entry.getKey(), entry.getValue());
            }
            httpServletResponse.getWriter().write(content);
        } else {
            filterChain.doFilter(servletRequest, servletResponse);
        }
    }

    private void runFilter(
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse,
            final String name,
            final GlobalConfiguration.FilterConfiguration configuration
    ) {
        final ArchuraFilter filter = filterFactory.create(name);
        filter.doFilter(configuration, httpServletRequest, httpServletResponse);
    }

    @Override
    public void destroy() {
        log.debug("InitialFilter destroyed");
    }

}
