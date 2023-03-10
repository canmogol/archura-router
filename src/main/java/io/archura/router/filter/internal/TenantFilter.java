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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.archura.router.filter.ArchuraKeys.ARCHURA_CURRENT_DOMAIN;
import static io.archura.router.filter.ArchuraKeys.ARCHURA_CURRENT_TENANT;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
@Component
public class TenantFilter implements ArchuraFilter {

    @Override
    public void doFilter(
            final GlobalConfiguration.FilterConfiguration configuration,
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse
    ) throws ArchuraFilterException {
        log.debug("↓ TenantFilter started");
        final Object domainConfiguration = httpServletRequest.getAttribute(ARCHURA_CURRENT_DOMAIN);
        if (isNull(domainConfiguration)) {
            throw new ArchuraFilterException(HttpStatus.NOT_FOUND.value(), "No domain configuration found for request.");
        }
        if (!(domainConfiguration instanceof final GlobalConfiguration.DomainConfiguration currentDomainConfiguration)) {
            throw new ArchuraFilterException(HttpStatus.NOT_FOUND.value(), "No DomainConfiguration found in request.");
        }
        log.debug("\tcurrent domain set to: '{}'", currentDomainConfiguration.getName());
        if (!(configuration instanceof final GlobalConfiguration.TenantFilterConfiguration tenantFilterConfiguration)) {
            throw new ArchuraFilterException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Provided configuration is not a TenantFilterConfiguration object.");
        }
        // extract tenant from request
        final String tenantId = findTenantId(httpServletRequest, tenantFilterConfiguration, currentDomainConfiguration.getDefaultTenantId());
        if (isNull(tenantId)) {
            throw new ArchuraFilterException(HttpStatus.NOT_FOUND.value(), "No tenant found in request.");
        }
        final GlobalConfiguration.TenantConfiguration tenantConfiguration = currentDomainConfiguration.getTenants().get(tenantId);
        if (isNull(tenantConfiguration)) {
            throw new ArchuraFilterException(HttpStatus.NOT_FOUND.value(), "No tenant configuration found for tenantId: '%s'".formatted(tenantId));
        }
        httpServletRequest.setAttribute(ARCHURA_CURRENT_TENANT, tenantConfiguration);
        log.debug("\tcurrent tenant set to: '{}'", tenantConfiguration.getName());
        log.debug("↑ TenantFilter finished");
    }

    private String findTenantId(
            final HttpServletRequest httpServletRequest,
            final GlobalConfiguration.TenantFilterConfiguration configuration,
            final String defaultTenantId
    ) {
        final GlobalConfiguration.ExtractConfiguration extractConfiguration = configuration.getExtractConfiguration();

        // extract tenant from header
        final List<GlobalConfiguration.HeaderConfiguration> headerConfigurations = extractConfiguration.getHeaderConfiguration();
        for (GlobalConfiguration.HeaderConfiguration headerConfiguration : headerConfigurations) {
            final String tenantFromHeader = getTenantFromHeader(httpServletRequest, headerConfiguration);
            if (nonNull(tenantFromHeader)) {
                return tenantFromHeader;
            }
        }

        // extract tenant from path
        final List<GlobalConfiguration.PathConfiguration> pathConfigurations = extractConfiguration.getPathConfiguration();
        for (GlobalConfiguration.PathConfiguration pathConfiguration : pathConfigurations) {
            final String tenantFromPath = getTenantFromPath(httpServletRequest, pathConfiguration);
            if (nonNull(tenantFromPath)) {
                return tenantFromPath;
            }
        }

        // extract tenant from query
        final List<GlobalConfiguration.QueryConfiguration> queryConfigurations = extractConfiguration.getQueryConfiguration();
        for (GlobalConfiguration.QueryConfiguration queryConfiguration : queryConfigurations) {
            final String tenantFromQuery = getTenantFromQuery(httpServletRequest, queryConfiguration);
            if (nonNull(tenantFromQuery)) {
                return tenantFromQuery;
            }
        }

        return defaultTenantId;
    }


    private String getTenantFromHeader(
            final HttpServletRequest httpServletRequest,
            final GlobalConfiguration.HeaderConfiguration headerConfiguration
    ) {
        if (nonNull(headerConfiguration)) {
            final String headerName = headerConfiguration.getName();
            final String input = httpServletRequest.getHeader(headerName);
            if (nonNull(input)) {
                final Pattern pattern = getPattern(headerConfiguration, headerConfiguration.getRegex());
                final List<String> captureGroups = headerConfiguration.getCaptureGroups();
                return getTenantId(pattern, input, captureGroups);
            }
        }
        return null;
    }

    private String getTenantFromPath(
            final HttpServletRequest httpServletRequest,
            final GlobalConfiguration.PathConfiguration pathConfiguration
    ) {
        if (nonNull(pathConfiguration)) {
            final String input = httpServletRequest.getRequestURI();
            final Pattern pattern = getPattern(pathConfiguration, pathConfiguration.getRegex());
            final List<String> captureGroups = pathConfiguration.getCaptureGroups();
            return getTenantId(pattern, input, captureGroups);
        }
        return null;
    }

    private String getTenantFromQuery(
            final HttpServletRequest httpServletRequest,
            final GlobalConfiguration.QueryConfiguration queryConfiguration
    ) {
        if (nonNull(queryConfiguration) && nonNull(httpServletRequest.getQueryString())
                && httpServletRequest.getQueryString().contains(queryConfiguration.getName())) {
            final String queryString = httpServletRequest.getQueryString();
            final String[] pairs = queryString.split("&");
            for (String pair : pairs) {
                final String[] keyValue = pair.split("=");
                if (keyValue.length == 2 && keyValue[0].equals(queryConfiguration.getName())) {
                    final String input = keyValue[1];
                    final Pattern pattern = getPattern(queryConfiguration, queryConfiguration.getRegex());
                    final List<String> captureGroups = queryConfiguration.getCaptureGroups();
                    return getTenantId(pattern, input, captureGroups);
                }
            }
        }
        return null;
    }

    private String getTenantId(
            final Pattern pattern,
            final String input,
            final List<String> captureGroups
    ) {
        try {
            final Matcher matcher = pattern.matcher(input);
            if (matcher.matches() && nonNull(captureGroups)) {
                for (String group : captureGroups) {
                    return matcher.group(group);
                }
            }
        } catch (Exception e) {
            log.debug("\tNo capture group found with pattern: '{}' in input: '{}'", pattern.pattern(), input);
        }
        return null;
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
