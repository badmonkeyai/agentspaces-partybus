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

import ai.badmonkey.agentspaces.partybus.domain.Research.Link;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A recorded trip, replayed. One JSON document holds the links each channel
 * returned and the structured answers the model gave, keyed by tag; a lookup
 * tries the most specific key first ({@code hotels:Sintra}), then the tag alone
 * ({@code hotels}). The recording is what lets the vacation simulator and the
 * flow test run the whole fleet with no network and no keys, deterministically,
 * while the agents' code paths stay the ones a live run exercises.
 *
 * <p>Every lookup is counted, so a test can assert which specialists actually
 * did work.
 */
public final class RecordedSources implements Sources {

    private final JsonNode recording;
    private final String name;
    private final ConcurrentHashMap<String, AtomicInteger> lookups = new ConcurrentHashMap<>();

    RecordedSources(String name, JsonNode recording) {
        this.name = Objects.requireNonNull(name, "name");
        this.recording = Objects.requireNonNull(recording, "recording");
    }

    /** The bundled recording: a party of three, Boston to Lisbon, one bad week. */
    public static RecordedSources lisbon() {
        return new RecordedSources("recorded", Json.parse(Json.resource("/recorded/lisbon.json")));
    }

    /**
     * A recording from any JSON document of the same layout.
     *
     * @param name      a label
     * @param document  the recording
     * @return the sources
     */
    public static RecordedSources of(String name, String document) {
        return new RecordedSources(name, Json.parse(document));
    }

    @Override
    public String name() {
        return name;
    }

    /** How many times a tag (or channel) was looked up. */
    public int lookups(String key) {
        AtomicInteger count = lookups.get(key);
        return count == null ? 0 : count.get();
    }

    @Override
    public List<Link> search(String query, int limit) {
        return links("search", query, limit);
    }

    @Override
    public List<Link> news(String query, int limit) {
        return links("news", query, limit);
    }

    @Override
    public List<Link> images(String query, int limit) {
        return links("images", query, limit);
    }

    @Override
    public List<Link> videos(String query, int limit) {
        return links("videos", query, limit);
    }

    @Override
    public List<Place> places(String location, String amenity, int limit) {
        count("places:" + amenity);
        JsonNode node = lookup(recording.path("places"), amenity + ":" + location, amenity);
        List<Place> out = new ArrayList<>();
        for (JsonNode place : node) {
            out.add(Json.bind(place, Place.class));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    @Override
    public <T> T structured(String tag, String prompt, Class<T> type) {
        count(tag);
        int colon = tag.indexOf(':');
        String base = colon < 0 ? tag : tag.substring(0, colon);
        JsonNode node = lookup(recording.path("structured"), tag, base);
        if (node.isMissingNode()) {
            throw new IllegalStateException("recording has no structured answer for '" + tag
                    + "' (nor '" + base + "')");
        }
        return Json.bind(node, type);
    }

    private List<Link> links(String channel, String query, int limit) {
        count(channel);
        JsonNode channelNode = recording.path("links").path(channel);
        // Prefer a keyed set whose key appears in the query; fall back to "default".
        JsonNode chosen = channelNode.path("default");
        var fields = channelNode.fieldNames();
        String lowered = query.toLowerCase();
        while (fields.hasNext()) {
            String key = fields.next();
            if (!key.equals("default") && lowered.contains(key.toLowerCase())) {
                chosen = channelNode.path(key);
                break;
            }
        }
        List<Link> out = new ArrayList<>();
        for (JsonNode link : chosen) {
            out.add(Json.bind(link, Link.class));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private static JsonNode lookup(JsonNode map, String exact, String fallback) {
        JsonNode node = map.path(exact);
        return node.isMissingNode() ? map.path(fallback) : node;
    }

    private void count(String key) {
        lookups.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }
}
