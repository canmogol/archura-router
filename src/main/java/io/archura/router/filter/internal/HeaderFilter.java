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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.archura.router.filter.ArchuraKeys.ARCHURA_CURRENT_DOMAIN;
import static io.archura.router.filter.ArchuraKeys.ARCHURA_CURRENT_ROUTE;
import static io.archura.router.filter.ArchuraKeys.ARCHURA_CURRENT_TENANT;
import static io.archura.router.filter.ArchuraKeys.ARCHURA_REQUEST_HEADERS;
import static io.archura.router.filter.ArchuraKeys.RESTRICTED_HEADER_NAMES;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
@Component
public class HeaderFilter implements ArchuraFilter {
    @Override
    public void doFilter(
            final GlobalConfiguration.FilterConfiguration configuration,
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse
    ) throws ArchuraFilterException {
        log.debug("↓ HeaderFilter started");
        final GlobalConfiguration.DomainConfiguration domainConfiguration =
                (GlobalConfiguration.DomainConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_DOMAIN);
        final GlobalConfiguration.TenantConfiguration tenantConfiguration =
                (GlobalConfiguration.TenantConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_TENANT);
        if (isNull(domainConfiguration) || isNull(tenantConfiguration)) {
            throw new ArchuraFilterException(HttpStatus.NOT_FOUND.value(), "No domain or tenant configuration found for request.");
        }
        if (!(configuration instanceof final GlobalConfiguration.HeaderFilterConfiguration headerFilterConfiguration)) {
            throw new ArchuraFilterException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Provided configuration is not a HeaderFilterConfiguration object.");
        }

        final Map<String, GlobalConfiguration.HeaderOperations> routeOperations = headerFilterConfiguration.getRouteOperations();
        if (nonNull(routeOperations) && !routeOperations.isEmpty()) {
            final GlobalConfiguration.HeaderOperations wildCardOperations = routeOperations.get("*");
            if (nonNull(wildCardOperations)) {

            }
            handleCurrentRoute(httpServletRequest, routeOperations);
        }
        log.debug("↓ HeaderFilter finished");
    }

    private void handleCurrentRoute(
            final HttpServletRequest httpServletRequest,
            final Map<String, GlobalConfiguration.HeaderOperations> routeOperations
    ) {
        final Object route = httpServletRequest.getAttribute(ARCHURA_CURRENT_ROUTE);
        if (nonNull(route) && route instanceof final GlobalConfiguration.RouteConfiguration routeConfiguration
                && routeOperations.containsKey(routeConfiguration.getName())) {
            final GlobalConfiguration.HeaderOperations currentOperations = routeOperations.get(routeConfiguration.getName());
            if (nonNull(currentOperations) && nonNull(currentOperations.getOperations()) && !currentOperations.getOperations().isEmpty()) {
                handleOperations(currentOperations, routeConfiguration, httpServletRequest);
            }
        }
    }

    private void handleOperations(
            final GlobalConfiguration.HeaderOperations currentOperations,
            final GlobalConfiguration.RouteConfiguration routeConfiguration,
            final HttpServletRequest httpServletRequest
    ) {
        final Map<String, String> requestHeaders = getRequestHeaders(httpServletRequest);

        final Map<String, List<GlobalConfiguration.HeaderOperation>> operations = currentOperations.getOperations();
        final List<GlobalConfiguration.HeaderOperation> addOperations = operations.get("add");
        if (nonNull(addOperations)) {
            for (final GlobalConfiguration.HeaderOperation addOperation : addOperations) {
                if (nonNull(addOperation.getName()) && nonNull(addOperation.getValue())) {
                    for (final Map.Entry<String, String> variable : routeConfiguration.getVariables().entrySet()) {
                        final String variablePattern = "\\$\\{" + variable.getKey() + "}";
                        final String value = addOperation.getValue().replaceAll(variablePattern, variable.getValue());
                        requestHeaders.put(addOperation.getName(), value);
                    }
                }
            }
        }

        final List<GlobalConfiguration.HeaderOperation> removeOperations = operations.get("remove");
        if (nonNull(removeOperations)) {
            for (final GlobalConfiguration.HeaderOperation removeOperation : removeOperations) {
                if (nonNull(removeOperation.getName()) && requestHeaders.containsKey(removeOperation.getName())) {
                    requestHeaders.remove(removeOperation.getName());
                }
            }
        }

        final List<GlobalConfiguration.HeaderOperation> validateOperations = operations.get("validate");
        if (nonNull(validateOperations)) {
            for (final GlobalConfiguration.HeaderOperation validateOperation : validateOperations) {
                if (nonNull(validateOperation.getName()) && nonNull(validateOperation.getRegex())
                        && requestHeaders.containsKey(validateOperation.getName())) {
                    final String headerValue = requestHeaders.get(validateOperation.getName());
                    final Pattern pattern = Pattern.compile(validateOperation.getRegex());
                    final Matcher matcher = pattern.matcher(input);


                    if (!headerValue.matches(validateOperation.getRegex())) {
                        throw new ArchuraFilterException(HttpStatus.BAD_REQUEST.value(), "Header value does not match regex.");
                    }
                }
            }
        }
        httpServletRequest.setAttribute(ARCHURA_REQUEST_HEADERS, requestHeaders);
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
            httpServletRequest.setAttribute(ARCHURA_REQUEST_HEADERS, headers);
        }
        @SuppressWarnings("unchecked") final Map<String, String> requestHeaders = (Map<String, String>) httpServletRequest.getAttribute(ARCHURA_REQUEST_HEADERS);
        return requestHeaders;
    }
}
