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
import java.util.List;

import static io.archura.router.filter.ArchuraKeys.ARCHURA_CURRENT_CLIENT_IP;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
@Component
public class BlackListFilter implements ArchuraFilter {

    private static final List<String> CLIENT_IP_HEADERS = List.of(
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR",
            "HTTP_X_FORWARDED",
            "HTTP_X_CLUSTER_CLIENT_IP",
            "HTTP_CLIENT_IP",
            "HTTP_FORWARDED_FOR",
            "HTTP_FORWARDED",
            "HTTP_VIA",
            "REMOTE_ADDR"
    );

    @Override
    public void doFilter(
            final GlobalConfiguration.FilterConfiguration configuration,
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse
    ) throws ArchuraFilterException {
        log.debug("BlackWhiteListFilter");
        if (!(configuration instanceof final GlobalConfiguration.BlackListFilterConfiguration blackListFilterConfiguration)) {
            throw new ArchuraFilterException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Provided configuration is not a BlackListFilterConfiguration object.");
        }
        // extract client ip from request and check if it is blacklisted
        final List<String> blackListedIps = blackListFilterConfiguration.getIps();
        if (!blackListedIps.isEmpty()) {
            final String clientIp = getClientIp(httpServletRequest);
            if (blackListedIps.contains(clientIp)) {
                log.debug("Client IP '{}' is blacklisted.", clientIp);
                throw new ArchuraFilterException(HttpStatus.FORBIDDEN.value(), "Client IP is blacklisted.");
            }
        }
    }

    private String getClientIp(final HttpServletRequest httpServletRequest) {
        final Object requestIp = httpServletRequest.getAttribute(ARCHURA_CURRENT_CLIENT_IP);
        if (isNull(requestIp)) {
            final String clientIp = Collections.list(httpServletRequest.getHeaderNames())
                    .stream()
                    .filter(CLIENT_IP_HEADERS::contains)
                    .map(httpServletRequest::getHeader)
                    .filter(this::isValid)
                    .findFirst()
                    .map(ip -> ip.split(",")[0].trim())
                    .orElse(httpServletRequest.getRemoteAddr());
            httpServletRequest.setAttribute(ARCHURA_CURRENT_CLIENT_IP, clientIp);
        }
        return String.valueOf(httpServletRequest.getAttribute(ARCHURA_CURRENT_CLIENT_IP));
    }

    private boolean isValid(final String ipValue) {
        return nonNull(ipValue) && !ipValue.isEmpty() && !ipValue.isBlank() && !ipValue.equals("unknown");
    }

}
