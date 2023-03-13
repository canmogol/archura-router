package io.archura.router.filter.internal;

import io.archura.router.config.GlobalConfiguration;
import io.archura.router.filter.ArchuraFilter;
import io.archura.router.filter.exception.ArchuraFilterException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.archura.router.filter.ArchuraKeys.ARCHURA_CURRENT_DOMAIN;
import static io.archura.router.filter.ArchuraKeys.ARCHURA_CURRENT_ROUTE;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
@Component
public class AuthenticationFilter implements ArchuraFilter {

    public static final String AUTHORIZATION = "Authorization";

    @Override
    public void doFilter(
            final GlobalConfiguration.FilterConfiguration configuration,
            final HttpServletRequest httpServletRequest,
            final HttpServletResponse httpServletResponse
    ) throws ArchuraFilterException {
        log.debug("↓ AuthenticationFilter started");
        if (isNull(httpServletRequest.getAttribute(ARCHURA_CURRENT_DOMAIN))
                || httpServletRequest.getAttribute(ARCHURA_CURRENT_DOMAIN) instanceof GlobalConfiguration.DomainConfiguration) {
            throw new ArchuraFilterException(HttpStatus.NOT_FOUND.value(), "No domain configuration found for request.");
        }
        if (isNull(httpServletRequest.getAttribute(ARCHURA_CURRENT_ROUTE))
                || httpServletRequest.getAttribute(ARCHURA_CURRENT_ROUTE) instanceof GlobalConfiguration.RouteConfiguration) {
            throw new ArchuraFilterException(HttpStatus.NOT_FOUND.value(), "No route configuration found for request.");
        }
        if (!(configuration instanceof final GlobalConfiguration.AuthenticationFilterConfiguration authenticationFilterConfiguration)) {
            throw new ArchuraFilterException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Provided configuration is not a AuthenticationFilterConfiguration object.");
        }
        if (!authenticationFilterConfiguration.getRoutes().isEmpty()) {
            authenticateRequest(authenticationFilterConfiguration, httpServletRequest);
        }
        log.debug("↑ AuthenticationFilter finished");
    }

    private void authenticateRequest(
            final GlobalConfiguration.AuthenticationFilterConfiguration configuration,
            final HttpServletRequest httpServletRequest
    ) {
        final GlobalConfiguration.DomainConfiguration domainConfiguration =
                (GlobalConfiguration.DomainConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_DOMAIN);
        final GlobalConfiguration.RouteConfiguration currentRoute =
                (GlobalConfiguration.RouteConfiguration) httpServletRequest.getAttribute(ARCHURA_CURRENT_ROUTE);

        if (configuration.isJwt()) {
            validateJWT(
                    domainConfiguration.getPublicCertificate(),
                    domainConfiguration.getPublicCertificateType(),
                    httpServletRequest.getHeader(AUTHORIZATION)
            );
        } else {
            final GlobalConfiguration.HeaderConfiguration headerConfiguration = configuration.getHeaderConfiguration();
            if (nonNull(headerConfiguration)) {
                final String headerValue = httpServletRequest.getHeader(headerConfiguration.getName());
                validateHeader(configuration.getHeaderConfiguration(), configuration.getValidationConfiguration(), headerValue);
            }
        }
    }


    private void validateJWT(
            final String publicCertificate,
            final String publicCertificateType,
            final String authorization
    ) {
        if (isNull(publicCertificate)) {
            throw new ArchuraFilterException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "No public certificate found for domain.");
        }
        if (isNull(publicCertificateType)) {
            throw new ArchuraFilterException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "No public certificate type found for domain.");
        }
        if (isNull(authorization)) {
            throw new ArchuraFilterException(HttpStatus.UNAUTHORIZED.value(), "No authorization header found.");
        }
        final String[] authorizationParts = authorization.split(" ");
        if (authorizationParts.length != 2) {
            throw new ArchuraFilterException(HttpStatus.UNAUTHORIZED.value(), "Invalid authorization header.");
        }
        final String authorizationType = authorizationParts[0];
        if (isNull(authorizationType) || !authorizationType.equalsIgnoreCase("Bearer")) {
            throw new ArchuraFilterException(HttpStatus.UNAUTHORIZED.value(), "Invalid authorization type.");
        }
        final String authorizationToken = authorizationParts[1];
        if (isNull(authorizationToken)) {
            throw new ArchuraFilterException(HttpStatus.UNAUTHORIZED.value(), "Invalid authorization token.");
        }
        try {
            final SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.forName(publicCertificateType);
            final SecretKey secretKey = Keys.secretKeyFor(signatureAlgorithm);
            final Jws<Claims> claimsJws = Jwts.parserBuilder().setSigningKey(secretKey).build().parseClaimsJws(authorizationToken);
            final Claims claims = claimsJws.getBody();
            final Date expiration = claims.getExpiration();
            final long expirationTime = expiration.getTime();
            final long currentTime = System.currentTimeMillis();
            if (currentTime > expirationTime) {
                throw new ArchuraFilterException(HttpStatus.UNAUTHORIZED.value(), "JWT Token has expired.");
            }
        } catch (final Exception e) {
            throw new ArchuraFilterException(HttpStatus.UNAUTHORIZED.value(), "Invalid JWT Signature.");
        }
    }

    private void validateHeader(
            final GlobalConfiguration.HeaderConfiguration headerConfiguration,
            final GlobalConfiguration.ValidationConfiguration validationConfiguration,
            final String headerValue
    ) {
        if (isNull(headerConfiguration)) {
            throw new ArchuraFilterException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "No headerValue configuration found for request.");
        }
        if (isNull(headerValue) || headerValue.isBlank()) {
            throw new ArchuraFilterException(HttpStatus.UNAUTHORIZED.value(), "Header '%s' not found for request.".formatted(headerConfiguration.getName()));
        }
        final List<String> values = new ArrayList<>();
        final String regex = headerConfiguration.getRegex();
        final List<String> captureGroups = headerConfiguration.getCaptureGroups();
        final Pattern pattern = getPattern(headerConfiguration, regex);
        final Matcher matcher = pattern.matcher(headerValue);
        if (matcher.matches()) {
            if (isNull(captureGroups) || captureGroups.isEmpty()) {
                values.add(matcher.group(0));
            } else {
                for (String group : captureGroups) {
                    values.add(matcher.group(group));
                }
            }
        }
        if (values.isEmpty()) {
            throw new ArchuraFilterException(HttpStatus.UNAUTHORIZED.value(), "Header '%s' value does not match the regex.".formatted(headerConfiguration.getName()));
        }
        if (nonNull(validationConfiguration)) {
            final GlobalConfiguration.RemoteEndpointConfiguration remoteEndpoint = validationConfiguration.getRemoteEndpoint();
            final GlobalConfiguration.CacheConfiguration cacheConfiguration = validationConfiguration.getCacheConfiguration();
            if (nonNull(remoteEndpoint)) {
                // TODO: validate the header value against the remote endpoint
                // POST http://remote-validation-server-url/validate-endpoint
                /*
                {
                    'domain': '${domain.name}',
                    'tenant': '${tenant.name}',
                    'route': '${route.name}',
                    'header': '${header.name}',
                    'value': '${header.value}'
                }
                 */
                final String url = remoteEndpoint.getUrl();

                // TODO: cache the response if the remote endpoint is cachable
                if (remoteEndpoint.isCachable() && nonNull(cacheConfiguration)) {
                    final String cacheKey = remoteEndpoint.getCacheKey();
                    final int cacheTtlMillis = remoteEndpoint.getCacheTtl();
                    // TODO: cache the response
                }
            }
        }
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
