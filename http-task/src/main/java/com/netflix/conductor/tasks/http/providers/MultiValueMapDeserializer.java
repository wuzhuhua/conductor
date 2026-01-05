package com.netflix.conductor.tasks.http.providers;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;

public class MultiValueMapDeserializer extends JsonDeserializer<MultiValueMap<String, String>> {

    @Override
    public MultiValueMap<String, String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();

        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode valueNode = entry.getValue();
            if (valueNode.isArray()) {
                for (JsonNode elementNode : valueNode) {
                    String value = elementNode.asText();
                    map.add(key, value);
                }
            } else {
                String value = valueNode.asText();
                map.add(key, value);
            }
        });

        return map;
    }
}
