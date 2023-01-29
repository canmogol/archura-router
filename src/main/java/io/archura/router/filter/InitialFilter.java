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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            final Optional<GlobalConfiguration.RouteConfiguration> routeConfiguration = findMatchingRoute(httpServletRequest, routeConfigurations);
            if (routeConfiguration.isPresent()) {
                return routeConfiguration.get();
            }
        }

        final List<GlobalConfiguration.RouteConfiguration> catchAllRoutes = tenantConfiguration.getMethodRoutes().get("*");
        if (nonNull(catchAllRoutes)) {
            // TODO: return the first matching route
        }

        // return not found route
        final Map<String, String> requestHeaders = getRequestHeaders(httpServletRequest);
        return getNotFoundRouteConfiguration(domainConfiguration, requestHeaders, method);
    }

    private Optional<GlobalConfiguration.RouteConfiguration> findMatchingRoute(
            final HttpServletRequest httpServletRequest,
            final List<GlobalConfiguration.RouteConfiguration> routeConfigurations
    ) {
        final String uri = httpServletRequest.getRequestURI();
        final Map<String, String> requestHeaders = getRequestHeaders(httpServletRequest);
        final Map<String, String> templateVariables = new HashMap<>();
        for (GlobalConfiguration.RouteConfiguration routeConfiguration : routeConfigurations) {
            final Optional<GlobalConfiguration.RouteConfiguration> matched = matchRouteConfiguration(httpServletRequest, uri, requestHeaders, templateVariables, routeConfiguration);
            if (matched.isPresent()) {
                final GlobalConfiguration.RouteConfiguration matchedRouteConfiguration = matched.get();
                final GlobalConfiguration.MapConfiguration mapConfiguration = matchedRouteConfiguration.getMapConfiguration();
                final GlobalConfiguration.MapConfiguration appliedMapConfiguration = applyTemplateVariables(mapConfiguration, templateVariables);
                final GlobalConfiguration.RouteConfiguration appliedRouteConfiguration = matchedRouteConfiguration.toBuilder()
                        .mapConfiguration(appliedMapConfiguration)
                        .build();
                return Optional.of(appliedRouteConfiguration);
            }
        }
        return Optional.empty();
    }

    private GlobalConfiguration.MapConfiguration applyTemplateVariables(
            final GlobalConfiguration.MapConfiguration mapConfiguration,
            final Map<String, String> templateVariables
    ) {
        String url = mapConfiguration.getUrl();
        final Map<String, String> headers = mapConfiguration.getHeaders();

        // templateVariables: { 'match.url.tenantId' : '12345', 'extract.header.token' : 'qwerty' }
        for (String variable : templateVariables.keySet()) {
            final String value = templateVariables.get(variable);
            final String variablePattern = "\\$\\{" + variable + "\\}";
            url = url.replaceAll(variablePattern, value);
            for (String header : headers.keySet()) {
                final String headerValue = headers.get(header);
                headers.put(header, headerValue.replaceAll(variablePattern, value));
            }
        }
        final GlobalConfiguration.MapConfiguration appliedMapConfiguration = new GlobalConfiguration.MapConfiguration();
        appliedMapConfiguration.setUrl(url);
        appliedMapConfiguration.setHeaders(headers);
        appliedMapConfiguration.setMethodMap(mapConfiguration.getMethodMap());
        return appliedMapConfiguration;
    }

    private void addExtractVariables(
            final HttpServletRequest httpServletRequest,
            final Map<String, String> requestHeaders,
            final Map<String, String> templateVariables,
            final GlobalConfiguration.ExtractConfiguration extractConfiguration
    ) {
        final GlobalConfiguration.RoutePathConfiguration routePathConfiguration = extractConfiguration.getRoutePathConfiguration();
        if (nonNull(routePathConfiguration)) {
            final String input = httpServletRequest.getRequestURI();
            final String regex = routePathConfiguration.getRegex();
            final List<String> captureGroups = routePathConfiguration.getCaptureGroups();
            final Pattern pattern = Pattern.compile(regex);
            final Matcher matcher = pattern.matcher(input);
            if (matcher.matches()) {
                for (String group : captureGroups) {
                    templateVariables.put("extract.path." + group, matcher.group(group));
                }
            }
        }
        final GlobalConfiguration.RouteHeaderConfiguration headerConfiguration = extractConfiguration.getRouteHeaderConfiguration();
        if (nonNull(headerConfiguration) && requestHeaders.containsKey(headerConfiguration.getName())) {
            final String input = requestHeaders.get(headerConfiguration.getName());
            final String regex = headerConfiguration.getRegex();
            final List<String> captureGroups = headerConfiguration.getCaptureGroups();
            final Pattern pattern = Pattern.compile(regex);
            final Matcher matcher = pattern.matcher(input);
            if (matcher.matches()) {
                for (String group : captureGroups) {
                    templateVariables.put("extract.header." + group, matcher.group(group));
                }
            }
        }
        final GlobalConfiguration.RouteQueryConfiguration queryConfiguration = extractConfiguration.getRouteQueryConfiguration();
        if (nonNull(queryConfiguration) && httpServletRequest.getParameterMap().containsKey(queryConfiguration.getName())) {
            final String input = httpServletRequest.getParameter(queryConfiguration.getName());
            final String regex = queryConfiguration.getRegex();
            final List<String> captureGroups = queryConfiguration.getCaptureGroups();
            final Pattern pattern = Pattern.compile(regex);
            final Matcher matcher = pattern.matcher(input);
            if (matcher.matches()) {
                for (String group : captureGroups) {
                    templateVariables.put("extract.query." + group, matcher.group(group));
                }
            }
        }
    }

    private Optional<GlobalConfiguration.RouteConfiguration> matchRouteConfiguration(
            final HttpServletRequest httpServletRequest,
            final String uri,
            final Map<String, String> requestHeaders,
            final Map<String, String> templateVariables,
            final GlobalConfiguration.RouteConfiguration routeConfiguration
    ) {
        boolean match = false;
        final GlobalConfiguration.MatchConfiguration matchConfiguration = routeConfiguration.getMatchConfiguration();
        final GlobalConfiguration.RoutePathConfiguration routePathConfiguration = matchConfiguration.getRoutePathConfiguration();
        if (nonNull(routePathConfiguration)) {
            final String input = uri;
            final String regex = routePathConfiguration.getRegex();
            final List<String> captureGroups = routePathConfiguration.getCaptureGroups();
            final Pattern pattern = Pattern.compile(regex);
            final Matcher matcher = pattern.matcher(input);
            if (matcher.matches()) {
                for (String group : captureGroups) {
                    templateVariables.put("match.path." + group, matcher.group(group));
                }
                match = true;
            } else {
                match = false;
            }
        }
        final GlobalConfiguration.RouteHeaderConfiguration headerConfiguration = matchConfiguration.getRouteHeaderConfiguration();
        if (nonNull(headerConfiguration)) {
            if (requestHeaders.containsKey(headerConfiguration.getName())) {
                final String input = requestHeaders.get(headerConfiguration.getName());
                final String regex = headerConfiguration.getRegex();
                final List<String> captureGroups = headerConfiguration.getCaptureGroups();
                final Pattern pattern = Pattern.compile(regex);
                final Matcher matcher = pattern.matcher(input);
                if (matcher.matches()) {
                    for (String group : captureGroups) {
                        templateVariables.put("match.header." + group, matcher.group(group));
                    }
                    match = true;
                } else {
                    match = false;
                }
            } else {
                match = false;
            }
        }
        final GlobalConfiguration.RouteQueryConfiguration queryConfiguration = matchConfiguration.getRouteQueryConfiguration();
        if (nonNull(queryConfiguration)) {
            if (httpServletRequest.getParameterMap().containsKey(queryConfiguration.getName())) {
                final String input = httpServletRequest.getParameter(queryConfiguration.getName());
                final String regex = queryConfiguration.getRegex();
                final List<String> captureGroups = routePathConfiguration.getCaptureGroups();
                final Pattern pattern = Pattern.compile(regex);
                final Matcher matcher = pattern.matcher(input);
                if (matcher.matches()) {
                    for (String group : captureGroups) {
                        templateVariables.put("match.query." + group, matcher.group(group));
                    }
                    match = true;
                } else {
                    match = false;
                }
            } else {
                match = false;
            }
        }
        if (match) {
            final GlobalConfiguration.ExtractConfiguration extractConfiguration = routeConfiguration.getExtractConfiguration();
            addExtractVariables(httpServletRequest, requestHeaders, templateVariables, extractConfiguration);
            addRequestVariables(httpServletRequest, requestHeaders, templateVariables);
            return Optional.of(routeConfiguration);
        } else {
            return Optional.empty();
        }
    }

    private void addRequestVariables(
            final HttpServletRequest httpServletRequest,
            final Map<String, String> requestHeaders,
            final Map<String, String> templateVariables
    ) {
        templateVariables.put("request.path", httpServletRequest.getRequestURI());
        templateVariables.put("request.method", httpServletRequest.getMethod());
        templateVariables.put("request.query", httpServletRequest.getQueryString());
        for (String header : requestHeaders.keySet()) {
            templateVariables.put("request.header." + header, requestHeaders.get(header));
        }
    }

    private GlobalConfiguration.RouteConfiguration getNotFoundRouteConfiguration(
            final GlobalConfiguration.DomainConfiguration domainConfiguration,
            final Map<String, String> requestHeaders,
            final String method
    ) {
        final String notFoundUrl = nonNull(domainConfiguration.getParameters().get(ARCHURA_DOMAIN_NOT_FOUND_URL))
                ? domainConfiguration.getParameters().get(ARCHURA_DOMAIN_NOT_FOUND_URL) : "/not-found";

        final GlobalConfiguration.RouteConfiguration notFoundRoute = new GlobalConfiguration.RouteConfiguration();
        final GlobalConfiguration.MapConfiguration notFoundMap = createNotFoundMap(requestHeaders, method, notFoundUrl);
        notFoundRoute.setMapConfiguration(notFoundMap);
        return notFoundRoute;
    }

    private GlobalConfiguration.MapConfiguration createNotFoundMap(
            final Map<String, String> requestHeaders,
            final String method,
            final String notFoundUrl
    ) {
        final GlobalConfiguration.MapConfiguration notFoundMap = new GlobalConfiguration.MapConfiguration();
        notFoundMap.setUrl(notFoundUrl);
        notFoundMap.setMethodMap(Map.of(method, HTTP_METHOD_GET));
        notFoundMap.setHeaders(requestHeaders);
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
