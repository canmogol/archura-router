package io.archura.router.filter.internal;

import io.archura.router.config.GlobalConfiguration;
import io.archura.router.filter.ArchuraFilter;
import io.archura.router.filter.exception.ArchuraFilterException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.archura.router.filter.ArchuraKeys.ARCHURA_CURRENT_DOMAIN;
import static io.archura.router.filter.ArchuraKeys.ARCHURA_CURRENT_ROUTE;
import static io.archura.router.filter.ArchuraKeys.ARCHURA_CURRENT_TENANT;
import static io.archura.router.filter.ArchuraKeys.ARCHURA_DOMAIN_NOT_FOUND_URL;
import static io.archura.router.filter.ArchuraKeys.ARCHURA_REQUEST_HEADERS;
import static io.archura.router.filter.ArchuraKeys.DEFAULT_HTTP_METHOD;
import static io.archura.router.filter.ArchuraKeys.RESTRICTED_HEADER_NAMES;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
@Component
public class RouteMatchingFilter implements ArchuraFilter {
    @Override
    public void doFilter(
            final GlobalConfiguration.FilterConfiguration configuration,
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse
    ) throws ArchuraFilterException {
        log.debug("↓ RouteMatchingFilter started");
        final GlobalConfiguration.DomainConfiguration domainConfiguration =
                (GlobalConfiguration.DomainConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_DOMAIN);
        final GlobalConfiguration.TenantConfiguration tenantConfiguration =
                (GlobalConfiguration.TenantConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_TENANT);
        if (isNull(domainConfiguration) || isNull(tenantConfiguration)) {
            throw new ArchuraFilterException(HttpStatus.NOT_FOUND.value(), "No domain or tenant configuration found for request.");
        }
        // find current route
        final GlobalConfiguration.RouteConfiguration currentRoute =
                findCurrentRoute(httpServletRequest, domainConfiguration, tenantConfiguration);
        httpServletRequest.setAttribute(ARCHURA_CURRENT_ROUTE, currentRoute);
        log.debug("\tcurrent route set to: '{}'", currentRoute.getName());
        log.debug("↑ RouteMatchingFilter finished");
    }

    private GlobalConfiguration.RouteConfiguration findCurrentRoute(
            final HttpServletRequest httpServletRequest,
            final GlobalConfiguration.DomainConfiguration domainConfiguration,
            final GlobalConfiguration.TenantConfiguration tenantConfiguration
    ) {
        final String method = httpServletRequest.getMethod();

        // check for HTTP Method specific tenant routes
        final List<GlobalConfiguration.RouteConfiguration> tenantRouteConfigurations = tenantConfiguration.getMethodRoutes().get(method);
        if (nonNull(tenantRouteConfigurations)) {
            final Optional<GlobalConfiguration.RouteConfiguration> tenantRouteConfiguration = findMatchingRoute(httpServletRequest, tenantRouteConfigurations);
            if (tenantRouteConfiguration.isPresent()) {
                return tenantRouteConfiguration.get();
            }
        }

        // check for catch all routes (wildcard) for HTTP Method '*'
        final List<GlobalConfiguration.RouteConfiguration> tenantCatchAllRoutes = tenantConfiguration.getMethodRoutes().get("*");
        if (nonNull(tenantCatchAllRoutes)) {
            final Optional<GlobalConfiguration.RouteConfiguration> tenantCatchAllRouteConfiguration = findMatchingRoute(httpServletRequest, tenantCatchAllRoutes);
            if (tenantCatchAllRouteConfiguration.isPresent()) {
                return tenantCatchAllRouteConfiguration.get();
            }
        }

        // check for HTTP Method specific domain routes
        final List<GlobalConfiguration.RouteConfiguration> domainRouteConfigurations = domainConfiguration.getMethodRoutes().get(method);
        if (nonNull(domainRouteConfigurations)) {
            final Optional<GlobalConfiguration.RouteConfiguration> domainRouteConfiguration = findMatchingRoute(httpServletRequest, domainRouteConfigurations);
            if (domainRouteConfiguration.isPresent()) {
                return domainRouteConfiguration.get();
            }
        }

        // check for catch all domain routes (wildcard) for HTTP Method '*'
        final List<GlobalConfiguration.RouteConfiguration> domainCatchAllRoutes = domainConfiguration.getMethodRoutes().get("*");
        if (nonNull(domainCatchAllRoutes)) {
            final Optional<GlobalConfiguration.RouteConfiguration> domainCatchAllRouteConfiguration = findMatchingRoute(httpServletRequest, domainCatchAllRoutes);
            if (domainCatchAllRouteConfiguration.isPresent()) {
                return domainCatchAllRouteConfiguration.get();
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
        final Map<String, String> templateVariables = new TreeMap<>();
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
            if (nonNull(value)) {
                final String variablePattern = "\\$\\{" + templateVariable.getKey() + "}";
                url = url.replaceAll(variablePattern, value);
                for (Map.Entry<String, String> entry : mapHeaders.entrySet()) {
                    final String headerValue = mapHeaders.get(entry.getKey());
                    mapHeaders.put(entry.getKey(), headerValue.replaceAll(variablePattern, value));
                }
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
        final List<GlobalConfiguration.PathConfiguration> pathConfigurations = extractConfiguration.getPathConfiguration();
        for (GlobalConfiguration.PathConfiguration pathConfiguration : pathConfigurations) {
            extractPathVariables(httpServletRequest, templateVariables, pathConfiguration);
        }

        final List<GlobalConfiguration.HeaderConfiguration> headerConfigurations = extractConfiguration.getHeaderConfiguration();
        for (GlobalConfiguration.HeaderConfiguration headerConfig : headerConfigurations) {
            extractHeaderVariables(requestHeaders, templateVariables, headerConfig);
        }

        final List<GlobalConfiguration.QueryConfiguration> queryConfigurations = extractConfiguration.getQueryConfiguration();
        for (GlobalConfiguration.QueryConfiguration queryConfig : queryConfigurations) {
            extractQueryVariables(httpServletRequest, templateVariables, queryConfig);
        }
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
                if (isNull(captureGroups) || captureGroups.isEmpty()) {
                    templateVariables.put("extract.path", matcher.group(0));
                } else {
                    for (String group : captureGroups) {
                        templateVariables.put("extract.path." + group, matcher.group(group));
                    }
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
                if (isNull(captureGroups) || captureGroups.isEmpty()) {
                    templateVariables.put("extract.header." + headerConfiguration.getName(), matcher.group(0));
                } else {
                    for (String group : captureGroups) {
                        templateVariables.put("extract.header." + group, matcher.group(group));
                    }
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
                if (isNull(captureGroups) || captureGroups.isEmpty()) {
                    templateVariables.put("extract.query." + queryConfiguration.getName(), matcher.group(0));
                } else {
                    for (String group : captureGroups) {
                        templateVariables.put("extract.query." + group, matcher.group(group));
                    }
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
        final List<GlobalConfiguration.PathConfiguration> pathConfigurations = matchConfiguration.getPathConfiguration();
        for (GlobalConfiguration.PathConfiguration pathConfiguration : pathConfigurations) {
            match = isPathMatch(uri, templateVariables, match, pathConfiguration);
            if (!match) {
                break;
            }
        }

        final List<GlobalConfiguration.HeaderConfiguration> headerConfigurations = matchConfiguration.getHeaderConfiguration();
        for (GlobalConfiguration.HeaderConfiguration headerConfiguration : headerConfigurations) {
            match = isHeaderMatch(requestHeaders, templateVariables, match, headerConfiguration);
            if (!match) {
                break;
            }
        }

        final List<GlobalConfiguration.QueryConfiguration> queryConfigurations = matchConfiguration.getQueryConfiguration();
        for (GlobalConfiguration.QueryConfiguration queryConfiguration : queryConfigurations) {
            match = isQueryMatch(httpServletRequest, templateVariables, match, queryConfiguration);
            if (!match) {
                break;
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
                if (isNull(captureGroups) || captureGroups.isEmpty()) {
                    templateVariables.put("match.path", matcher.group(0));
                } else {
                    for (String group : captureGroups) {
                        templateVariables.put("match.path." + group, matcher.group(group));
                    }
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
                    if (isNull(captureGroups) || captureGroups.isEmpty()) {
                        templateVariables.put("match.header." + headerConfiguration.getName(), matcher.group(0));
                    } else {
                        for (String group : captureGroups) {
                            templateVariables.put("match.header." + group, matcher.group(group));
                        }
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
            final GlobalConfiguration.QueryConfiguration queryConfiguration
    ) {
        if (nonNull(queryConfiguration)) {
            if (httpServletRequest.getParameterMap().containsKey(queryConfiguration.getName())) {
                final String input = httpServletRequest.getParameter(queryConfiguration.getName());
                final String regex = queryConfiguration.getRegex();
                final List<String> captureGroups = queryConfiguration.getCaptureGroups();
                if (isNull(queryConfiguration.getPattern())) {
                    queryConfiguration.setPattern(Pattern.compile(regex));
                }
                final Pattern pattern = queryConfiguration.getPattern();
                final Matcher matcher = pattern.matcher(input);
                if (matcher.matches()) {
                    if (isNull(captureGroups) || captureGroups.isEmpty()) {
                        templateVariables.put("match.query." + queryConfiguration.getName(), matcher.group(0));
                    } else {
                        for (String group : captureGroups) {
                            templateVariables.put("match.query." + group, matcher.group(group));
                        }
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
        templateVariables.put("request.query", isNull(httpServletRequest.getQueryString()) ? "" : httpServletRequest.getQueryString());
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

}
