/*
 * Copyright 2026 Bad Monkey, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.badmonkey.agentspaces.partybus.sources;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The one JSON mapper the sources share, plus the record-shape hint the live
 * sources hand the language model so that its answer binds to a domain record.
 */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .findAndRegisterModules();

    private Json() {
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }

    static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException("unparseable JSON: " + abbreviate(text), e);
        }
    }

    static <T> T bind(String text, Class<T> type) {
        try {
            return MAPPER.readValue(stripFences(text), type);
        } catch (IOException e) {
            throw new UncheckedIOException("JSON does not bind to " + type.getSimpleName()
                    + ": " + abbreviate(text), e);
        }
    }

    static <T> T bind(JsonNode node, Class<T> type) {
        try {
            return MAPPER.treeToValue(node, type);
        } catch (IOException e) {
            throw new UncheckedIOException("JSON does not bind to " + type.getSimpleName(), e);
        }
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String resource(String path) {
        try (InputStream in = Json.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A compact description of a record's fields, e.g.
     * {@code {"name": string, "options": [{"carrier": string, ...}]}}, so the
     * model answers in exactly the shape the fleet's entry type expects.
     */
    static String shape(Class<?> type) {
        return shape(type, 0);
    }

    private static String shape(Type type, int depth) {
        if (depth > 4) {
            return "…";
        }
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (raw instanceof Class<?> rawClass && List.class.isAssignableFrom(rawClass)) {
                return "[" + shape(parameterized.getActualTypeArguments()[0], depth + 1) + "]";
            }
            return "object";
        }
        if (!(type instanceof Class<?> cls)) {
            return "value";
        }
        if (cls == String.class) {
            return "string";
        }
        if (cls == int.class || cls == Integer.class || cls == long.class || cls == Long.class) {
            return "integer";
        }
        if (cls == double.class || cls == Double.class) {
            return "number";
        }
        if (cls == boolean.class || cls == Boolean.class) {
            return "boolean";
        }
        if (cls.isEnum()) {
            List<String> names = new ArrayList<>();
            for (Object constant : cls.getEnumConstants()) {
                names.add(constant.toString());
            }
            return "one of " + names;
        }
        if (cls.isRecord()) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (RecordComponent component : cls.getRecordComponents()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                out.append('"').append(component.getName()).append("\": ")
                        .append(shape(component.getGenericType(), depth + 1));
            }
            return out.append('}').toString();
        }
        return "value";
    }

    private static String stripFences(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int firstBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                return trimmed.substring(firstBreak + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
