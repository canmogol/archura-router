package io.archura.router.filter;

import java.util.List;

public class ArchuraKeys {
    public static final String ARCHURA_DOMAIN = "archura.domain";
    public static final String DEFAULT_DOMAIN = "default";
    public static final String ARCHURA_TENANT = "archura.tenant";
    public static final String DEFAULT_TENANT = "default";
    public static final String ARCHURA_DOMAIN_NOT_FOUND_URL = "archura.domain.not-found.url";
    public static final String ARCHURA_REQUEST_HEADERS = "archura.request.headers";
    public static final String ARCHURA_CURRENT_ROUTE = "archura.current.route";
    public static final String DEFAULT_HTTP_METHOD = "GET";
    public static final int ARCHURA_DOWNSTREAM_CONNECTION_TIMEOUT = 10_000;
    public static final List<String> RESTRICTED_HEADER_NAMES = List.of("host", "upgrade", "connection", "content-length");

}
