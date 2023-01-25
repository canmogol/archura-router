package io.archura.router.filter;

import io.archura.router.config.GlobalConfiguration;
import io.archura.router.filter.exception.ArchuraFilterException;
import io.archura.router.mapping.Mapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface ArchuraFilter {

    void doFilter(HttpServletRequest httpServletRequest, HttpServletResponse response) throws ArchuraFilterException;

    default void setConfiguration(GlobalConfiguration.FilterConfiguration configuration) {
    }

    default void setGlobalConfiguration(GlobalConfiguration globalConfiguration) {
    }

    default void setMapper(Mapper mapper) {
    }

}

