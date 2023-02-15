package io.archura.router.filter;

import io.archura.router.config.GlobalConfiguration;
import io.archura.router.filter.exception.ArchuraFilterException;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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

import static io.archura.router.filter.ArchuraKeys.*;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@Component
@RequiredArgsConstructor
public class InitialFilter implements Filter {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(ARCHURA_DOWNSTREAM_CONNECTION_TIMEOUT))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .version(HttpClient.Version.HTTP_2)
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
    ) {
        try {
            // run global pre-filters, domain pre-filters, tenant pre-filters, and route pre-filters
            runGlobalPreFilters(httpServletRequest, httpServletResponse);
            runDomainPreFilters(httpServletRequest, httpServletResponse);
            runTenantPreFilters(httpServletRequest, httpServletResponse);
            runRoutePreFilters(httpServletRequest, httpServletResponse);

            // handle request if not handled by the pre-filters
            if (!httpServletResponse.isCommitted()) {
                // handle current route
                final GlobalConfiguration.RouteConfiguration currentRoute = (GlobalConfiguration.RouteConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_ROUTE);
                final GlobalConfiguration.PredefinedResponseConfiguration predefinedResponseConfiguration = currentRoute.getPredefinedResponseConfiguration();
                if (nonNull(predefinedResponseConfiguration)) {
                    // handle predefined response
                    handlePredefinedResponse(httpServletResponse, predefinedResponseConfiguration);
                } else {
                    // handle downstream request
                    // send downstream request and get response
                    final HttpRequest httpRequest = buildHttpRequest(httpServletRequest);
                    final HttpResponse<InputStream> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                    populateHttpServletResponse(httpServletResponse, httpResponse);

                    // run global post-filters, domain post-filters, tenant post-filters, and route post-filters
                    runGlobalPostFilters(httpServletRequest, httpServletResponse);
                    runDomainPostFilters(httpServletRequest, httpServletResponse);
                    runTenantPostFilters(httpServletRequest, httpServletResponse);
                    runRoutePostFilters(httpServletRequest, httpServletResponse);

                    if (!httpServletResponse.isCommitted()) {
                        // read response from downstream server and write to client
                        writeToHttpServletResponse(httpServletResponse, httpResponse);
                    } else {
                        log.debug("request already handled by the post-filters");
                    }
                }

            } else {
                log.debug("request already handled by the pre-filters");
            }
        } catch (ArchuraFilterException e) {
            log.error("Error occurred while handling request", e);
            httpServletResponse.setStatus(e.getStatusCode());
            try {
                final byte[] errorMessage = e.getMessage().getBytes();
                httpServletResponse.setContentLength(errorMessage.length);
                httpServletResponse.getOutputStream().write(errorMessage);
            } catch (IOException ex) {
                log.error("Error occurred while writing error message to response", ex);
            }
        } catch (Exception e) {
            log.error("Error occurred while handling request", e);
            httpServletResponse.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            try {
                httpServletResponse.getOutputStream().write(e.getMessage().getBytes());
            } catch (IOException ex) {
                log.error("Error occurred while writing error message to response", ex);
            }
        }
    }

    private void runGlobalPreFilters(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        // run global pre-filters
        if (!httpServletResponse.isCommitted()) {
            runPreFilters(httpServletRequest, httpServletResponse, globalConfiguration.getPreFilters());
        }
    }

    private void runDomainPreFilters(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        // run domain pre-filters
        if (!httpServletResponse.isCommitted()) {
            // get current domain configuration
            if (isNull(httpServletRequest.getAttribute(ARCHURA_CURRENT_DOMAIN))) {
                final GlobalConfiguration.DomainConfiguration defaultDomain = new GlobalConfiguration.DomainConfiguration();
                httpServletRequest.setAttribute(ARCHURA_CURRENT_DOMAIN, defaultDomain);
            }
            final GlobalConfiguration.DomainConfiguration domainConfiguration =
                    (GlobalConfiguration.DomainConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_DOMAIN);
            runPreFilters(httpServletRequest, httpServletResponse, domainConfiguration.getPreFilters());
        }
    }

    private void runTenantPreFilters(
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse
    ) {
        // run tenant pre-filters
        if (!httpServletResponse.isCommitted()) {
            // get current tenant configuration
            if (isNull(httpServletRequest.getAttribute(ARCHURA_CURRENT_TENANT))) {
                final GlobalConfiguration.TenantConfiguration defaultTenant = new GlobalConfiguration.TenantConfiguration();
                httpServletRequest.setAttribute(ARCHURA_CURRENT_TENANT, defaultTenant);
            }
            final GlobalConfiguration.TenantConfiguration tenantConfiguration =
                    (GlobalConfiguration.TenantConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_TENANT);
            runPreFilters(httpServletRequest, httpServletResponse, tenantConfiguration.getPreFilters());
        }
    }

    private void runRoutePreFilters(
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse
    ) {
        // run route pre-filters
        if (!httpServletResponse.isCommitted()) {
            // get domain and tenant configurations
            final GlobalConfiguration.DomainConfiguration domainConfiguration =
                    (GlobalConfiguration.DomainConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_DOMAIN);
            final GlobalConfiguration.TenantConfiguration tenantConfiguration =
                    (GlobalConfiguration.TenantConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_TENANT);
            // find current route configuration
            final GlobalConfiguration.RouteConfiguration currentRoute = findCurrentRoute(httpServletRequest, domainConfiguration, tenantConfiguration);
            httpServletRequest.setAttribute(ARCHURA_CURRENT_ROUTE, currentRoute);
            runPreFilters(httpServletRequest, httpServletResponse, currentRoute.getPreFilters());
        }
    }

    private void runPreFilters(
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse,
            final Map<String, GlobalConfiguration.FilterConfiguration> filters
    ) {
        for (Map.Entry<String, GlobalConfiguration.FilterConfiguration> filter : filters.entrySet()) {
            runFilter(httpServletRequest, httpServletResponse, filter.getKey(), filter.getValue());
            if (httpServletResponse.isCommitted()) {
                log.debug("request already handled by the pre-filter '%s', will stop processing".formatted(filter.getKey()));
                break;
            }
        }
    }

    private void handlePredefinedResponse(
            final HttpServletResponse httpServletResponse,
            final GlobalConfiguration.PredefinedResponseConfiguration predefinedResponseConfiguration
    ) throws IOException {
        final int predefinedStatus = predefinedResponseConfiguration.getStatus();
        final String predefinedBody = predefinedResponseConfiguration.getBody();
        httpServletResponse.setStatus(predefinedStatus);
        httpServletResponse.getOutputStream().write(predefinedBody.getBytes());
        httpServletResponse.setContentLength(predefinedBody.length());
    }

    private void populateHttpServletResponse(
            final HttpServletResponse httpServletResponse,
            final HttpResponse<InputStream> httpResponse
    ) {
        // get response status and content type
        final int responseStatus = httpResponse.statusCode();
        final String responseContentType = httpResponse.headers().firstValue("content-type")
                .orElse("text/plain");

        // set content type and character encoding
        final String[] contentTypeAndCharacterEncoding = responseContentType.split("charset=");
        if (contentTypeAndCharacterEncoding.length > 1) {
            httpServletResponse.setCharacterEncoding(contentTypeAndCharacterEncoding[1]);
        } else {
            httpServletResponse.setCharacterEncoding("utf-8");
        }
        // set response headers
        for (Map.Entry<String, List<String>> entry : httpResponse.headers().map().entrySet()) {
            final String headerName = entry.getKey();
            if (!RESTRICTED_HEADER_NAMES.contains(headerName)) {
                httpResponse.headers().firstValue(headerName)
                        .ifPresent(value -> httpServletResponse.setHeader(headerName, value));
            }
        }
        // set response status and content type and length
        httpServletResponse.setStatus(responseStatus);
        httpServletResponse.setContentType(responseContentType);
    }

    private void runGlobalPostFilters(
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse
    ) {
        // run global post-filters
        if (!httpServletResponse.isCommitted()) {
            runPostFilters(httpServletRequest, httpServletResponse, globalConfiguration.getPostFilters());
        }
    }

    private void runDomainPostFilters(
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse
    ) {
        // run domain post-filters
        if (!httpServletResponse.isCommitted()) {
            // get current domain configuration
            final GlobalConfiguration.DomainConfiguration domainConfiguration =
                    (GlobalConfiguration.DomainConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_DOMAIN);
            runPostFilters(httpServletRequest, httpServletResponse, domainConfiguration.getPostFilters());
        }
    }

    private void runTenantPostFilters(
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse
    ) {
        // run tenant post-filters
        if (!httpServletResponse.isCommitted()) {
            // get current tenant configuration
            final GlobalConfiguration.TenantConfiguration tenantConfiguration =
                    (GlobalConfiguration.TenantConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_TENANT);
            runPostFilters(httpServletRequest, httpServletResponse, tenantConfiguration.getPostFilters());
        }
    }

    private void runRoutePostFilters(
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse
    ) {
        // run tenant post-filters
        if (!httpServletResponse.isCommitted()) {
            // get current route configuration
            final GlobalConfiguration.RouteConfiguration currentRoute =
                    (GlobalConfiguration.RouteConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_ROUTE);
            runPostFilters(httpServletRequest, httpServletResponse, currentRoute.getPostFilters());
        }
    }

    private void runPostFilters(
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse,
            final Map<String, GlobalConfiguration.FilterConfiguration> filters
    ) {
        for (Map.Entry<String, GlobalConfiguration.FilterConfiguration> filter : filters.entrySet()) {
            runFilter(httpServletRequest, httpServletResponse, filter.getKey(), filter.getValue());
            if (httpServletResponse.isCommitted()) {
                log.debug("request already handled by the post-filter '%s', will stop processing".formatted(filter.getKey()));
                break;
            }
        }
    }

    private void runFilter(
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse,
            final String filterName,
            final GlobalConfiguration.FilterConfiguration configuration
    ) {
        final ArchuraFilter filter = filterFactory.create(filterName);
        filter.doFilter(configuration, httpServletRequest, httpServletResponse);
    }

    private HttpRequest buildHttpRequest(
            final HttpServletRequest httpServletRequest
    ) {
        final GlobalConfiguration.RouteConfiguration currentRoute = (GlobalConfiguration.RouteConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_ROUTE);
        final String currentRouteId = currentRoute.getName();
        final GlobalConfiguration.MapConfiguration currentRouteMapConfiguration = currentRoute.getMapConfiguration();
        final String downstreamRequestUrl = currentRouteMapConfiguration.getUrl();
        final Map<String, String> downstreamRequestHeaders = currentRouteMapConfiguration.getHeaders();
        final String downstreamRequestHttpMethod = currentRouteMapConfiguration.getMethodMap().getOrDefault(httpServletRequest.getMethod(), httpServletRequest.getMethod());
        final long downstreamConnectionTimeout = httpServletRequest.getAttribute("archura.downstream.connection.timeout") != null ? (long) httpServletRequest.getAttribute("archura.downstream.connection.timeout") : ARCHURA_DOWNSTREAM_CONNECTION_TIMEOUT;

        // build downstream request
        final HttpRequest httpRequest = buildHttpRequest(
                downstreamRequestUrl,
                downstreamRequestHeaders,
                downstreamRequestHttpMethod,
                downstreamConnectionTimeout,
                httpServletRequest
        );
        log.debug("Executing route: '%s', will send downstream request: %s".formatted(currentRouteId, httpRequest));
        return httpRequest;
    }

    private HttpRequest buildHttpRequest(
            final String downstreamRequestUrl,
            final Map<String, String> downstreamRequestHeaders,
            final String downstreamRequestHttpMethod,
            final long downstreamConnectionTimeout,
            final HttpServletRequest httpServletRequest
    ) {
        HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder()
                .timeout(Duration.ofMillis(downstreamConnectionTimeout))
                .uri(URI.create(downstreamRequestUrl))
                .method(downstreamRequestHttpMethod, HttpRequest.BodyPublishers.noBody());
        for (Map.Entry<String, String> entry : downstreamRequestHeaders.entrySet()) {
            httpRequestBuilder = httpRequestBuilder.header(entry.getKey(), entry.getValue());
        }
        final String requestHttpMethod = httpServletRequest.getMethod();
        if (requestHttpMethod.equalsIgnoreCase("POST")
                || requestHttpMethod.equalsIgnoreCase("PUT")
                || requestHttpMethod.equalsIgnoreCase("PATCH")) {
            httpRequestBuilder = httpRequestBuilder.method(downstreamRequestHttpMethod, HttpRequest.BodyPublishers.ofInputStream(() -> {
                try {
                    return httpServletRequest.getInputStream();
                } catch (IOException e) {
                    throw new ArchuraFilterException(HttpStatus.BAD_REQUEST.value(), "Error while reading request body", e);
                }
            }));
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
                final GlobalConfiguration.MapConfiguration appliedMapConfiguration = applyTemplateVariables(httpServletRequest, mapConfiguration, templateVariables);
                final GlobalConfiguration.RouteConfiguration appliedRouteConfiguration = matchedRouteConfiguration.toBuilder()
                        .mapConfiguration(appliedMapConfiguration)
                        .build();
                return Optional.of(appliedRouteConfiguration);
            }
        }
        return Optional.empty();
    }

    private GlobalConfiguration.MapConfiguration applyTemplateVariables(
            final HttpServletRequest httpServletRequest,
            final GlobalConfiguration.MapConfiguration mapConfiguration,
            final Map<String, String> templateVariables
    ) {
        // replace template variables in url and headers
        String url = mapConfiguration.getUrl();
        final Map<String, String> mapHeaders = mapConfiguration.getHeaders();
        for (Map.Entry<String, String> templateVariable : templateVariables.entrySet()) {
            final String value = templateVariables.get(templateVariable.getKey());
            final String variablePattern = "\\$\\{" + templateVariable.getKey() + "}";
            url = url.replaceAll(variablePattern, value);
            for (Map.Entry<String, String> entry : mapHeaders.entrySet()) {
                final String headerValue = mapHeaders.get(entry.getKey());
                mapHeaders.put(entry.getKey(), headerValue.replaceAll(variablePattern, value));
            }
        }
        // override request headers with map headers
        final Map<String, String> requestHeaders = getRequestHeaders(httpServletRequest);
        requestHeaders.putAll(mapHeaders);
        // return new map configuration
        final GlobalConfiguration.MapConfiguration appliedMapConfiguration = new GlobalConfiguration.MapConfiguration();
        appliedMapConfiguration.setUrl(url);
        appliedMapConfiguration.setHeaders(requestHeaders);
        appliedMapConfiguration.setMethodMap(mapConfiguration.getMethodMap());
        return appliedMapConfiguration;
    }

    private void addExtractVariables(
            final HttpServletRequest httpServletRequest,
            final Map<String, String> requestHeaders,
            final Map<String, String> templateVariables,
            final GlobalConfiguration.ExtractConfiguration extractConfiguration
    ) {
        final GlobalConfiguration.PathConfiguration pathConfiguration = extractConfiguration.getPathConfiguration();
        extractPathVariables(httpServletRequest, templateVariables, pathConfiguration);

        final GlobalConfiguration.HeaderConfiguration headerConfiguration = extractConfiguration.getHeaderConfiguration();
        extractHeaderVariables(requestHeaders, templateVariables, headerConfiguration);

        final GlobalConfiguration.QueryConfiguration queryConfiguration = extractConfiguration.getQueryConfiguration();
        extractQueryVariables(httpServletRequest, templateVariables, queryConfiguration);
    }

    private void extractPathVariables(
            final HttpServletRequest httpServletRequest,
            final Map<String, String> templateVariables,
            final GlobalConfiguration.PathConfiguration pathConfiguration
    ) {
        if (nonNull(pathConfiguration)) {
            final String input = httpServletRequest.getRequestURI();
            final String regex = pathConfiguration.getRegex();
            final List<String> captureGroups = pathConfiguration.getCaptureGroups();
            final Pattern pattern = Pattern.compile(regex);
            final Matcher matcher = pattern.matcher(input);
            if (matcher.matches()) {
                for (String group : captureGroups) {
                    templateVariables.put("extract.path." + group, matcher.group(group));
                }
            }
        }
    }

    private void extractHeaderVariables(
            final Map<String, String> requestHeaders,
            final Map<String, String> templateVariables,
            final GlobalConfiguration.HeaderConfiguration headerConfiguration
    ) {
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
    }

    private void extractQueryVariables(
            final HttpServletRequest httpServletRequest,
            final Map<String, String> templateVariables,
            final GlobalConfiguration.QueryConfiguration queryConfiguration
    ) {
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
        final GlobalConfiguration.PathConfiguration pathConfiguration = matchConfiguration.getPathConfiguration();
        match = isPathMatch(uri, templateVariables, match, pathConfiguration);

        final GlobalConfiguration.HeaderConfiguration headerConfiguration = matchConfiguration.getHeaderConfiguration();
        match = isHeaderMatch(requestHeaders, templateVariables, match, headerConfiguration);

        final GlobalConfiguration.QueryConfiguration queryConfiguration = matchConfiguration.getQueryConfiguration();
        match = isQueryMatch(httpServletRequest, templateVariables, match, pathConfiguration, queryConfiguration);

        if (match) {
            final GlobalConfiguration.ExtractConfiguration extractConfiguration = routeConfiguration.getExtractConfiguration();
            addExtractVariables(httpServletRequest, requestHeaders, templateVariables, extractConfiguration);
            addRequestVariables(httpServletRequest, requestHeaders, templateVariables);
            return Optional.of(routeConfiguration);
        } else {
            return Optional.empty();
        }
    }

    private boolean isPathMatch(
            final String input,
            final Map<String, String> templateVariables,
            boolean match,
            final GlobalConfiguration.PathConfiguration pathConfiguration
    ) {
        if (nonNull(pathConfiguration)) {
            final String regex = pathConfiguration.getRegex();
            final List<String> captureGroups = pathConfiguration.getCaptureGroups();
            if (isNull(pathConfiguration.getPattern())) {
                pathConfiguration.setPattern(Pattern.compile(regex));
            }
            final Pattern pattern = pathConfiguration.getPattern();
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
        return match;
    }

    private boolean isHeaderMatch(
            final Map<String, String> requestHeaders,
            final Map<String, String> templateVariables,
            boolean match,
            final GlobalConfiguration.HeaderConfiguration headerConfiguration
    ) {
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
        return match;
    }

    private boolean isQueryMatch(
            final HttpServletRequest httpServletRequest,
            final Map<String, String> templateVariables,
            boolean match,
            final GlobalConfiguration.PathConfiguration pathConfiguration,
            final GlobalConfiguration.QueryConfiguration queryConfiguration
    ) {
        if (nonNull(queryConfiguration)) {
            if (httpServletRequest.getParameterMap().containsKey(queryConfiguration.getName())) {
                final String input = httpServletRequest.getParameter(queryConfiguration.getName());
                final String regex = queryConfiguration.getRegex();
                final List<String> captureGroups = pathConfiguration.getCaptureGroups();
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
        return match;
    }

    private void addRequestVariables(
            final HttpServletRequest httpServletRequest,
            final Map<String, String> requestHeaders,
            final Map<String, String> templateVariables
    ) {
        templateVariables.put("request.path", httpServletRequest.getRequestURI());
        templateVariables.put("request.method", httpServletRequest.getMethod());
        templateVariables.put("request.query", httpServletRequest.getQueryString());
        for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
            templateVariables.put("request.header." + entry.getKey(), entry.getValue());
        }
    }

    private GlobalConfiguration.RouteConfiguration getNotFoundRouteConfiguration(
            final HttpServletRequest httpServletRequest,
            final GlobalConfiguration.DomainConfiguration domainConfiguration
    ) {
        final GlobalConfiguration.RouteConfiguration notFoundRoute = new GlobalConfiguration.RouteConfiguration();
        final String notFoundUrl = domainConfiguration.getParameters().get(ARCHURA_DOMAIN_NOT_FOUND_URL);
        if (isNull(notFoundUrl)) {
            notFoundRoute.setPredefinedResponseConfiguration(
                    new GlobalConfiguration.PredefinedResponseConfiguration(
                            HttpStatus.NOT_FOUND.value(),
                            "Request URL not found"
                    ));
        } else {
            final String method = httpServletRequest.getMethod();
            final Map<String, String> requestHeaders = getRequestHeaders(httpServletRequest);
            final GlobalConfiguration.MapConfiguration notFoundMap = createNotFoundMap(requestHeaders, method, notFoundUrl);
            notFoundRoute.setMapConfiguration(notFoundMap);
        }
        return notFoundRoute;
    }

    private GlobalConfiguration.MapConfiguration createNotFoundMap(
            final Map<String, String> requestHeaders,
            final String method,
            final String notFoundUrl
    ) {
        final GlobalConfiguration.MapConfiguration notFoundMap = new GlobalConfiguration.MapConfiguration();
        notFoundMap.setUrl(notFoundUrl);
        notFoundMap.setMethodMap(Map.of(method, DEFAULT_HTTP_METHOD));
        notFoundMap.setHeaders(requestHeaders);
        return notFoundMap;
    }

    private Map<String, String> getRequestHeaders(final HttpServletRequest httpServletRequest) {
        final Object attributes = httpServletRequest.getAttribute(ARCHURA_REQUEST_HEADERS);
        if (isNull(attributes)) {
            final Map<String, String> headers = new HashMap<>();
            for (String headerName : Collections.list(httpServletRequest.getHeaderNames())) {
                String headerValue = httpServletRequest.getHeader(headerName);
                if (!RESTRICTED_HEADER_NAMES.contains(headerName.toLowerCase())) {
                    headers.put(headerName, headerValue);
                }
            }
            httpServletRequest.setAttribute("archura.request.headers", headers);
        }
        @SuppressWarnings("unchecked") final Map<String, String> requestHeaders = (Map<String, String>) httpServletRequest.getAttribute("archura.request.headers");
        return requestHeaders;
    }

    private void writeToHttpServletResponse(
            final HttpServletResponse httpServletResponse,
            final HttpResponse<InputStream> httpResponse
    ) throws IOException {
        try (InputStream responseInputStream = httpResponse.body()) {
            byte[] buf = new byte[8192];
            int length;
            while ((length = responseInputStream.read(buf)) != -1) {
                httpServletResponse.getOutputStream().write(buf, 0, length);
            }
        }
        httpServletResponse.getOutputStream().flush();
    }

    @Override
    public void destroy() {
        log.debug("InitialFilter destroyed");
    }

}
