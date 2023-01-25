package io.archura.router.mapping;

import java.util.Optional;

public interface Mapper {
    <T> Optional<T> readValue(final String string, Class<T> type);
}
