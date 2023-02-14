package io.archura.router.mapping;

public interface Mapper {
    <T> T readValue(final String string, Class<T> type);
}
