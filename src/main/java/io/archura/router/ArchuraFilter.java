package io.archura.router;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

public class ArchuraFilter implements Filter {
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(
            final ServletRequest servletRequest,
            final ServletResponse servletResponse,
            final FilterChain filterChain
    ) throws IOException, ServletException {

        if (servletRequest instanceof HttpServletRequest httpServletRequest) {
            // httpServletRequest
            // run initial generic global filters
            // run CustomerAccountFilter
            //      run Customer's filters
            // run TenantFilter
            //      run Tenant's filter
            // Route
            //      run Tenant's filter
            //      run Customer's filters
            // return response
        }

        filterChain.doFilter(servletRequest, servletResponse);
    }

    @Override
    public void destroy() {
    }
}
