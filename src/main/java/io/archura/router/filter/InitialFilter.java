package io.archura.router.filter;

import io.archura.router.config.GlobalConfiguration;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
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
    private static final String ARCHURA_REQUEST_HEADERS = "archura.request.headers";
    private static final String ARCHURA_NOT_FOUND_URI = "/not-found";
    private static final int ARCHURA_DOWNSTREAM_CONNECTION_TIMEOUT = 10000;
    public static final String ARCHURA_REQUEST_HANDLED = "archura.request.handled";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(ARCHURA_DOWNSTREAM_CONNECTION_TIMEOUT))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

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
            handleHttpRequest(httpServletRequest, httpServletResponse);
        } else {
            filterChain.doFilter(servletRequest, servletResponse);
        }
    }

    private void handleHttpRequest(
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse
    ) throws IOException {

        // global pre-filters
        for (Map.Entry<String, GlobalConfiguration.FilterConfiguration> filters : globalConfiguration.getGlobalPreFilters().entrySet()) {
            runFilter(httpServletRequest, httpServletResponse, filters.getKey(), filters.getValue());
            if (nonNull(httpServletRequest.getAttribute("archura.request.handled"))) {
                log.debug("request already handled by the global filter '%s', will stop processing".formatted(filters.getKey()));
                return;
            }
        }

        // domain pre-filters
        if (isNull(httpServletRequest.getAttribute(ARCHURA_DOMAIN))) {
            httpServletRequest.setAttribute(ARCHURA_DOMAIN, DEFAULT_DOMAIN);
        }
        final String domain = String.valueOf(httpServletRequest.getAttribute(ARCHURA_DOMAIN));
        final GlobalConfiguration.DomainConfiguration domainConfiguration = globalConfiguration.getDomains().get(domain);
        for (Map.Entry<String, GlobalConfiguration.FilterConfiguration> filters : domainConfiguration.getDomainPreFilters().entrySet()) {
            runFilter(httpServletRequest, httpServletResponse, filters.getKey(), filters.getValue());
            if (nonNull(httpServletRequest.getAttribute("archura.request.handled"))) {
                log.debug("request already handled by the domain filter '%s', will stop processing".formatted(filters.getKey()));
                return;
            }
        }

        // tenant pre-filters
        if (isNull(httpServletRequest.getAttribute(ARCHURA_TENANT))) {
            httpServletRequest.setAttribute(ARCHURA_TENANT, DEFAULT_TENANT);
        }
        final String tenant = String.valueOf(httpServletRequest.getAttribute(ARCHURA_TENANT));
        final GlobalConfiguration.TenantConfiguration tenantConfiguration = domainConfiguration.getTenants().get(tenant);
        for (Map.Entry<String, GlobalConfiguration.FilterConfiguration> filters : tenantConfiguration.getTenantPreFilters().entrySet()) {
            runFilter(httpServletRequest, httpServletResponse, filters.getKey(), filters.getValue());
            if (nonNull(httpServletRequest.getAttribute("archura.request.handled"))) {
                log.debug("request already handled by the tenant filter '%s', will stop processing".formatted(filters.getKey()));
                return;
            }
        }

        // route pre-filters
        final GlobalConfiguration.RouteConfiguration currentRoute = findCurrentRoute(httpServletRequest, domainConfiguration, tenantConfiguration);
        for (Map.Entry<String, GlobalConfiguration.FilterConfiguration> filters : currentRoute.getRoutePreFilters().entrySet()) {
            runFilter(httpServletRequest, httpServletResponse, filters.getKey(), filters.getValue());
            if (nonNull(httpServletRequest.getAttribute(ARCHURA_REQUEST_HANDLED))) {
                log.debug("request already handled by the route filter '%s', will stop processing".formatted(filters.getKey()));
                return;
            }
        }

        // send downstream request
        // Routing
        final String currentRouteId = currentRoute.getId();
        final GlobalConfiguration.MapConfiguration currentRouteMapConfiguration = currentRoute.getMapConfiguration();
        final String downstreamRequestUrl = currentRouteMapConfiguration.getUrl();
        final Map<String, String> downstreamRequestHeaders = currentRouteMapConfiguration.getHeaders();
        final String downstreamRequestHttpMethod = currentRouteMapConfiguration.getMethodMap().getOrDefault(httpServletRequest.getMethod(), httpServletRequest.getMethod());
        final long downstreamConnectionTimeout = httpServletRequest.getAttribute("archura.downstream.connection.timeout") != null ? (long) httpServletRequest.getAttribute("archura.downstream.connection.timeout") : ARCHURA_DOWNSTREAM_CONNECTION_TIMEOUT;
        final HttpRequest httpRequest = buildHttpRequest(
                downstreamRequestUrl,
                downstreamRequestHeaders,
                downstreamRequestHttpMethod,
                downstreamConnectionTimeout
        );
        log.debug("Executing route: '%s', will send downstream request: %s".formatted(currentRouteId, httpRequest));
        try {
            // send downstream request and get response
            final HttpResponse<InputStream> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            handleResponse(httpServletRequest, httpServletResponse, httpResponse);

            // route post-filters

            // tenant post-filters

            // domain post-filters

            // global post-filters


            // read response from downstream server and write to client
            int responseTotalLength = 0;
            try (InputStream responseInputStream = httpResponse.body()) {
                byte[] buf = new byte[8192];
                int length;
                while ((length = responseInputStream.read(buf)) != -1) {
                    httpServletResponse.getOutputStream().write(buf, 0, length);
                    responseTotalLength += length;
                }
            }
            httpServletResponse.setContentLength(responseTotalLength);
        } catch (Exception e) {
            // TODO: handle exception
        }


    }

    private static void handleResponse(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, HttpResponse<InputStream> httpResponse) throws IOException {
        // get response status and content type
        final int responseStatus = httpResponse.statusCode();
        final String responseContentType = httpResponse.headers().firstValue("content-type")
                .orElse(nonNull(httpServletRequest.getContentType()) ? httpServletRequest.getContentType() : "text/plain");

        // set content type and character encoding
        final String[] contentTypeAndCharacterEncoding = responseContentType.split("charset=");
        if (contentTypeAndCharacterEncoding.length > 1) {
            httpServletResponse.setCharacterEncoding(contentTypeAndCharacterEncoding[1]);
        } else if (nonNull(httpServletRequest.getCharacterEncoding())) {
            httpServletResponse.setCharacterEncoding(httpServletRequest.getCharacterEncoding());
        } else {
            httpServletResponse.setCharacterEncoding("utf-8");
        }
        // set response headers
        for (Map.Entry<String, List<String>> entry : httpResponse.headers().map().entrySet()) {
            httpResponse.headers().firstValue(entry.getKey())
                    .ifPresent(value -> httpServletResponse.setHeader(entry.getKey(), value));
        }
        // set response status and content type and length
        httpServletResponse.setStatus(responseStatus);
        httpServletResponse.setContentType(responseContentType);
    }

    private static HttpRequest buildHttpRequest(
            final String downstreamRequestUrl,
            final Map<String, String> downstreamRequestHeaders,
            final String downstreamRequestHttpMethod,
            final long downstreamConnectionTimeout
    ) {
        HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder()
                .timeout(Duration.ofMillis(downstreamConnectionTimeout))
                .uri(URI.create(downstreamRequestUrl))
                .method(downstreamRequestHttpMethod, HttpRequest.BodyPublishers.noBody());
        for (String header : downstreamRequestHeaders.keySet()) {
            httpRequestBuilder = httpRequestBuilder.header(header, downstreamRequestHeaders.get(header));
        }
        return httpRequestBuilder.build();
    }

    private GlobalConfiguration.RouteConfiguration findCurrentRoute(
            final HttpServletRequest httpServletRequest,
            final GlobalConfiguration.DomainConfiguration domainConfiguration,
            final GlobalConfiguration.TenantConfiguration tenantConfiguration
    ) {
        final String method = httpServletRequest.getMethod();

        // check for HTTP Method specific routes
        final List<GlobalConfiguration.RouteConfiguration> routeConfigurations = tenantConfiguration.getMethodRoutes().get(method);
        if (nonNull(routeConfigurations)) {
            final Optional<GlobalConfiguration.RouteConfiguration> routeConfiguration = findMatchingRoute(httpServletRequest, routeConfigurations);
            if (routeConfiguration.isPresent()) {
                return routeConfiguration.get();
            }
        }

        // check for catch all routes
        final List<GlobalConfiguration.RouteConfiguration> catchAllRoutes = tenantConfiguration.getMethodRoutes().get("*");
        if (nonNull(catchAllRoutes)) {
            final Optional<GlobalConfiguration.RouteConfiguration> catchAllRouteConfiguration = findMatchingRoute(httpServletRequest, catchAllRoutes);
            if (catchAllRouteConfiguration.isPresent()) {
                return catchAllRouteConfiguration.get();
            }
        }

        // return not found route
        return getNotFoundRouteConfiguration(httpServletRequest, domainConfiguration);
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
            if (isNull(routePathConfiguration.getPattern())) {
                routePathConfiguration.setPattern(Pattern.compile(regex));
            }
            final Pattern pattern = routePathConfiguration.getPattern();
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
                if (isNull(headerConfiguration.getPattern())) {
                    headerConfiguration.setPattern(Pattern.compile(regex));
                }
                final Pattern pattern = headerConfiguration.getPattern();
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
                if (isNull(queryConfiguration.getPattern())) {
                    queryConfiguration.setPattern(Pattern.compile(regex));
                }
                final Pattern pattern = queryConfiguration.getPattern();
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
            final HttpServletRequest httpServletRequest,
            final GlobalConfiguration.DomainConfiguration domainConfiguration
    ) {
        final GlobalConfiguration.RouteConfiguration notFoundRoute = new GlobalConfiguration.RouteConfiguration();
        final Map<String, String> requestHeaders = getRequestHeaders(httpServletRequest);
        final String method = httpServletRequest.getMethod();
        final String notFoundUrl = getNotFoundUrl(httpServletRequest, domainConfiguration);
        final GlobalConfiguration.MapConfiguration notFoundMap = createNotFoundMap(requestHeaders, method, notFoundUrl);
        notFoundRoute.setMapConfiguration(notFoundMap);
        return notFoundRoute;
    }

    private static String getNotFoundUrl(
            final HttpServletRequest httpServletRequest,
            final GlobalConfiguration.DomainConfiguration domainConfiguration
    ) {
        if (nonNull(domainConfiguration.getParameters().get(ARCHURA_DOMAIN_NOT_FOUND_URL))) {
            return domainConfiguration.getParameters().get(ARCHURA_DOMAIN_NOT_FOUND_URL);
        } else {
            final StringBuffer requestURL = httpServletRequest.getRequestURL();
            final String requestURI = httpServletRequest.getRequestURI();
            final int urlEnd = requestURL.length() - requestURI.length();
            final String url = requestURL.substring(0, urlEnd);
            return url + ARCHURA_NOT_FOUND_URI;
        }
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
        final Object attributes = httpServletRequest.getAttribute(ARCHURA_REQUEST_HEADERS);
        if (isNull(attributes)) {
            final Map<String, String> headers = new HashMap<>();
            for (String headerName : Collections.list(httpServletRequest.getHeaderNames())) {
                String headerValue = httpServletRequest.getHeader(headerName);
                headers.put(headerName, headerValue);
            }
            httpServletRequest.setAttribute("archura.request.headers", headers);
        }
        @SuppressWarnings("unchecked") final Map<String, String> requestHeaders = (Map<String, String>) httpServletRequest.getAttribute("archura.request.headers");
        return requestHeaders;
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
