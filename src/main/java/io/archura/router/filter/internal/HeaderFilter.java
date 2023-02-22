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

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.archura.router.filter.ArchuraKeys.*;
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
        handleHeaders(httpServletRequest, headerFilterConfiguration);
        log.debug("↑ HeaderFilter finished");
    }

    private void handleHeaders(
            final HttpServletRequest httpServletRequest,
            final GlobalConfiguration.HeaderFilterConfiguration configuration
    ) {
        final Map<String, String> requestHeaders = getRequestHeaders(httpServletRequest);
        final Map<String, String> requestVariables = getRequestVariables(httpServletRequest, requestHeaders);

        final List<GlobalConfiguration.HeaderOperation> addOperations = configuration.getAdd();
        if (nonNull(addOperations)) {
            for (final GlobalConfiguration.HeaderOperation addOperation : addOperations) {
                if (nonNull(addOperation.getName()) && nonNull(addOperation.getValue())) {
                    for (final Map.Entry<String, String> variable : requestVariables.entrySet()) {
                        final String variablePattern = "\\$\\{" + variable.getKey() + "}";
                        if (addOperation.getValue().matches(variablePattern)) {
                            final String value = addOperation.getValue().replaceAll(variablePattern, variable.getValue());
                            requestHeaders.put(addOperation.getName(), value);
                            break;
                        }
                    }
                }
            }
        }

        final List<GlobalConfiguration.HeaderOperation> removeOperations = configuration.getRemove();
        if (nonNull(removeOperations)) {
            for (final GlobalConfiguration.HeaderOperation removeOperation : removeOperations) {
                if (nonNull(removeOperation.getName())) {
                    requestHeaders.remove(removeOperation.getName());
                }
            }
        }

        final List<GlobalConfiguration.HeaderOperation> validateOperations = configuration.getValidate();
        if (nonNull(validateOperations)) {
            for (final GlobalConfiguration.HeaderOperation validateOperation : validateOperations) {
                if (nonNull(validateOperation.getName()) && nonNull(validateOperation.getRegex())
                        && requestHeaders.containsKey(validateOperation.getName())) {
                    final String headerValue = requestHeaders.get(validateOperation.getName());
                    final Pattern pattern = getPattern(validateOperation, validateOperation.getRegex());
                    final Matcher matcher = pattern.matcher(headerValue);
                    if (!matcher.matches()) {
                        throw new ArchuraFilterException(
                                HttpStatus.BAD_REQUEST.value(),
                                "Header '%s' value: '%s' does not match regex: '%s'".formatted(
                                        validateOperation.getName(),
                                        headerValue,
                                        validateOperation.getRegex()
                                )
                        );
                    }
                }
            }
        }

        final List<GlobalConfiguration.HeaderOperation> mandatoryOperations = configuration.getMandatory();
        if (nonNull(mandatoryOperations)) {
            for (final GlobalConfiguration.HeaderOperation mandatoryOperation : mandatoryOperations) {
                if (nonNull(mandatoryOperation.getName()) && !requestHeaders.containsKey(mandatoryOperation.getName().toLowerCase())) {
                    throw new ArchuraFilterException(
                            HttpStatus.BAD_REQUEST.value(),
                            "Header '%s' is mandatory but not present in request.".formatted(mandatoryOperation.getName())
                    );
                }
            }
        }

        httpServletRequest.setAttribute(ARCHURA_REQUEST_HEADERS, requestHeaders);
    }

    private Map<String, String> getRequestHeaders(final HttpServletRequest httpServletRequest) {
        final Object attributes = httpServletRequest.getAttribute(ARCHURA_REQUEST_HEADERS);
        if (isNull(attributes)) {
            final Map<String, String> headers = new TreeMap<>();
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

    private Map<String, String> getRequestVariables(
            final HttpServletRequest httpServletRequest,
            final Map<String, String> requestHeaders
    ) {
        final Object requestVars = httpServletRequest.getAttribute(ARCHURA_REQUEST_VARIABLES);
        if (isNull(requestVars)) {
            final Map<String, String> variables = new TreeMap<>();
            // set variables from route if available
            final Object currentRoute = httpServletRequest.getAttribute(ARCHURA_CURRENT_ROUTE);
            if (nonNull(currentRoute) && currentRoute instanceof final GlobalConfiguration.RouteConfiguration routeConfiguration) {
                variables.putAll(routeConfiguration.getVariables());
            } else {
                // set default variables
                variables.put("request.path", httpServletRequest.getRequestURI());
                variables.put("request.method", httpServletRequest.getMethod());
                variables.put("request.query", isNull(httpServletRequest.getQueryString()) ? "" : httpServletRequest.getQueryString());
                for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                    variables.put("request.header." + entry.getKey(), entry.getValue());
                }
                // set domain variable if available
                final Object domainConfig = httpServletRequest.getAttribute(ARCHURA_CURRENT_DOMAIN);
                if (nonNull(domainConfig) && (domainConfig instanceof final GlobalConfiguration.DomainConfiguration domainConfiguration)) {
                    variables.put("request.domain.name", domainConfiguration.getName());
                }
                // set tenant variable if available
                final Object tenantConfig = httpServletRequest.getAttribute(ARCHURA_CURRENT_TENANT);
                if (nonNull(tenantConfig) && (tenantConfig instanceof final GlobalConfiguration.TenantConfiguration tenantConfiguration)) {
                    variables.put("request.tenant.name", tenantConfiguration.getName());
                }
            }
            httpServletRequest.setAttribute(ARCHURA_REQUEST_VARIABLES, variables);
        }
        @SuppressWarnings("unchecked") final Map<String, String> requestVariables = (Map<String, String>) httpServletRequest.getAttribute(ARCHURA_REQUEST_VARIABLES);
        return requestVariables;
    }

    private Pattern getPattern(
            final GlobalConfiguration.PatternHolder patternHolder,
            final String regex
    ) {
        if (isNull(patternHolder.getPattern())) {
            final Pattern pattern = Pattern.compile(regex);
            patternHolder.setPattern(pattern);
        }
        return patternHolder.getPattern();
    }

}
