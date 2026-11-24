package org.teamknowledge.mcp.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

@Component
public class JsonCodec {
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new KnowledgeException("INVALID_CONTENT", "Could not serialize content."); }
    }
    public <T> T read(String value, Class<T> type) {
        try {
            if(value==null || value.length()>262144)throw new IllegalArgumentException();
            T result=mapper.readValue(value, type);
            if(result==null)throw new IllegalArgumentException();
            return result;
        }
        catch (Exception e) { throw new KnowledgeException("INVALID_ARGUMENT", "Invalid JSON, field, or enum value."); }
    }
}
