package io.archura.router.filter;

import io.archura.router.config.GlobalConfiguration;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@Component
@RequiredArgsConstructor
public class InitialFilter implements Filter {

    private static final String ARCHURA_DOMAIN = "archura.domain";
    private static final String DEFAULT_DOMAIN = "default";
    private static final String ARCHURA_TENANT = "archura.tenant";
    private static final String DEFAULT_TENANT = "default";
    private static final String ARCHURA_DOMAIN_NOT_FOUND_URL = "archura.domain.not-found.url";
    private static final String HTTP_METHOD_GET = "GET";
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
            if (isNull(httpServletRequest.getAttribute(ARCHURA_TENANT))) {
                httpServletRequest.setAttribute(ARCHURA_TENANT, DEFAULT_TENANT);
            }
            final String tenant = String.valueOf(httpServletRequest.getAttribute(ARCHURA_TENANT));
            final GlobalConfiguration.TenantConfiguration tenantConfiguration = domainConfiguration.getTenants().get(tenant);
            tenantConfiguration.getTenantPreFilters()
                    .forEach((name, configuration) -> runFilter(httpServletRequest, httpServletResponse, name, configuration));

            // route pre-filters
            final GlobalConfiguration.RouteConfiguration currentRoute = findCurrentRoute(httpServletRequest, domainConfiguration, tenantConfiguration);
            currentRoute.getRoutePreFilters()
                    .forEach((name, configuration) -> runFilter(httpServletRequest, httpServletResponse, name, configuration));

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

    private GlobalConfiguration.RouteConfiguration findCurrentRoute(
            final HttpServletRequest httpServletRequest,
            final GlobalConfiguration.DomainConfiguration domainConfiguration,
            final GlobalConfiguration.TenantConfiguration tenantConfiguration
    ) {
        final String method = httpServletRequest.getMethod();

        final List<GlobalConfiguration.RouteConfiguration> routeConfigurations = tenantConfiguration.getMethodRoutes().get(method);
        if (nonNull(routeConfigurations)) {
            // TODO: return the first matching route
        }

        final List<GlobalConfiguration.RouteConfiguration> catchAllRoutes = tenantConfiguration.getMethodRoutes().get("*");
        if (nonNull(catchAllRoutes)) {
            // TODO: return the first matching route
        }

        // return not found route
        final String notFoundUrl = nonNull(domainConfiguration.getParameters().get(ARCHURA_DOMAIN_NOT_FOUND_URL))
                ? domainConfiguration.getParameters().get(ARCHURA_DOMAIN_NOT_FOUND_URL) : "/not-found";

        final GlobalConfiguration.RouteConfiguration notFoundRoute = new GlobalConfiguration.RouteConfiguration();
        final GlobalConfiguration.MapConfiguration notFoundMap = createNotFoundMap(httpServletRequest, method, notFoundUrl);
        notFoundRoute.setMapConfiguration(notFoundMap);
        return notFoundRoute;
    }

    private GlobalConfiguration.MapConfiguration createNotFoundMap(
            final HttpServletRequest httpServletRequest,
            final String method,
            final String notFoundUrl
    ) {
        final GlobalConfiguration.MapConfiguration notFoundMap = new GlobalConfiguration.MapConfiguration();
        notFoundMap.setUrl(notFoundUrl);
        notFoundMap.setMethodMap(Map.of(method, HTTP_METHOD_GET));
        notFoundMap.setHeaders(getRequestHeaders(httpServletRequest));
        return notFoundMap;
    }

    private Map<String, String> getRequestHeaders(final HttpServletRequest httpServletRequest) {
        Map<String, String> headers = new HashMap<>();
        for (String headerName : Collections.list(httpServletRequest.getHeaderNames())) {
            String headerValue = httpServletRequest.getHeader(headerName);
            headers.put(headerName, headerValue);
        }
        return headers;
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
