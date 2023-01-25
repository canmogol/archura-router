package io.archura.router.compat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.archura.router.mapping.Mapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArchuraObjectMapper implements Mapper {

    private final ObjectMapper objectMapper;

    public <T> Optional<T> readValue(String string, Class<T> type) {
        try {
            return Optional.of(objectMapper.readValue(string, type));
        } catch (JsonProcessingException e) {
            log.error("Error while parsing json", e);
            return Optional.empty();
        }
    }
}
