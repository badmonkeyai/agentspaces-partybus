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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The real world over standard REST/JSON APIs, with the keys Tripper asks for
 * and one more: {@code OPENAI_API_KEY} for the language model (Spring AI's
 * {@code OpenAiChatModel} behind a {@code ChatClient}, {@code gpt-4.1-mini} unless {@code PARTYBUS_OPENAI_MODEL} says
 * otherwise), {@code BRAVE_API_KEY} for web, news, image, and video search, and
 * optionally {@code YOUTUBE_API_KEY} for the YouTube Data API, which the video
 * watcher prefers when it is present. Places come from OpenStreetMap
 * (Nominatim geocoding and the Overpass API) and need no key.
 *
 * <p>Like Tripper, a live run costs money and the keys are required: a missing
 * key fails at startup with the variable named, never silently at the first
 * search. Every client is {@code java.net.http}; no SDK is involved, so the
 * dependency footprint stays that of the fleet libraries.
 */
public final class LiveSources implements Sources {

    /** The environment variables a live run reads. */
    public static final String OPENAI_API_KEY = "OPENAI_API_KEY";
    /** The Brave Search API key. */
    public static final String BRAVE_API_KEY = "BRAVE_API_KEY";
    /** The optional YouTube Data API key. */
    public static final String YOUTUBE_API_KEY = "YOUTUBE_API_KEY";
    /** Overrides the chat model. */
    public static final String OPENAI_MODEL = "PARTYBUS_OPENAI_MODEL";

    private static final String USER_AGENT = "agentspaces-partybus/0.1 (+https://agentspaces.dev)";

    private final HttpClient http;
    private final ChatClient chat;
    private final String braveKey;
    private final Optional<String> youtubeKey;

    /**
     * Creates live sources over a Spring AI {@link ChatClient}: any provider,
     * any model, and under Spring Boot the application's own client.
     *
     * @param http       the HTTP client for the search, video, and map sources
     * @param chat       the model the scouts' structured answers come from
     * @param braveKey   the Brave Search key
     * @param youtubeKey the YouTube Data key, if any
     */
    public LiveSources(HttpClient http, ChatClient chat, String braveKey, Optional<String> youtubeKey) {
        this.http = Objects.requireNonNull(http, "http");
        this.chat = Objects.requireNonNull(chat, "chat");
        this.braveKey = Objects.requireNonNull(braveKey, "braveKey");
        this.youtubeKey = Objects.requireNonNull(youtubeKey, "youtubeKey");
    }

    /**
     * Builds live sources from the environment, or fails naming every missing
     * key.
     *
     * @return the live sources
     * @throws MissingApiKeyException if a required key is absent
     */
    public static LiveSources fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * Builds live sources from the given environment map.
     *
     * @param env the environment
     * @return the live sources
     * @throws MissingApiKeyException if a required key is absent
     */
    public static LiveSources fromEnvironment(Map<String, String> env) {
        List<String> missing = new ArrayList<>();
        for (String required : List.of(OPENAI_API_KEY, BRAVE_API_KEY)) {
            if (env.getOrDefault(required, "").isBlank()) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new MissingApiKeyException(missing);
        }
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(env.get(OPENAI_API_KEY))
                        .model(env.getOrDefault(OPENAI_MODEL, "gpt-4.1-mini"))
                        .temperature(0.2)
                        .build())
                .build();
        return new LiveSources(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                ChatClient.create(model), env.get(BRAVE_API_KEY),
                Optional.ofNullable(env.get(YOUTUBE_API_KEY)).filter(k -> !k.isBlank()));
    }

    /** Thrown when a live run starts without its keys. */
    public static final class MissingApiKeyException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        MissingApiKeyException(List<String> missing) {
            super("Party Bus needs API keys for a live run, as Tripper does. Missing: "
                    + String.join(", ", missing) + ". Export them, for example\n"
                    + "  export OPENAI_API_KEY=...\n  export BRAVE_API_KEY=...\n"
                    + "or run the recorded trip with PARTYBUS_SOURCES=recorded.");
        }
    }

    @Override
    public String name() {
        return "live";
    }

    // ---------------------------------------------------------------- Brave

    @Override
    public List<Link> search(String query, int limit) {
        JsonNode body = brave("web", query, limit);
        return links(body.path("web").path("results"), "url", "description");
    }

    @Override
    public List<Link> news(String query, int limit) {
        JsonNode body = brave("news", query, limit);
        return links(body.path("results"), "url", "description");
    }

    @Override
    public List<Link> images(String query, int limit) {
        JsonNode body = brave("images", query, limit);
        List<Link> out = new ArrayList<>();
        for (JsonNode result : body.path("results")) {
            String image = result.path("properties").path("url").asText("");
            if (!image.isEmpty()) {
                out.add(new Link(image, result.path("title").asText("")));
            }
        }
        return out;
    }

    @Override
    public List<Link> videos(String query, int limit) {
        if (youtubeKey.isPresent()) {
            return youtube(query, limit);
        }
        JsonNode body = brave("videos", query, limit);
        return links(body.path("results"), "url", "description");
    }

    private JsonNode brave(String vertical, String query, int limit) {
        URI uri = URI.create("https://api.search.brave.com/res/v1/" + vertical + "/search?q="
                + encode(query) + "&count=" + Math.max(1, Math.min(limit, 20)));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", braveKey)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        return Json.parse(send(request, "Brave " + vertical + " search"));
    }

    private static List<Link> links(JsonNode results, String urlField, String textField) {
        List<Link> out = new ArrayList<>();
        for (JsonNode result : results) {
            String url = result.path(urlField).asText("");
            if (url.isEmpty()) {
                continue;
            }
            String title = result.path("title").asText("");
            String text = result.path(textField).asText("");
            out.add(new Link(url, title.isEmpty() ? text : title + ". " + text));
        }
        return out;
    }

    // -------------------------------------------------------------- YouTube

    private List<Link> youtube(String query, int limit) {
        URI uri = URI.create("https://www.googleapis.com/youtube/v3/search?part=snippet"
                + "&type=video&order=date&maxResults=" + Math.max(1, Math.min(limit, 25))
                + "&q=" + encode(query) + "&key=" + encode(youtubeKey.orElseThrow()));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        JsonNode body = Json.parse(send(request, "YouTube search"));
        List<Link> out = new ArrayList<>();
        for (JsonNode item : body.path("items")) {
            String id = item.path("id").path("videoId").asText("");
            if (id.isEmpty()) {
                continue;
            }
            JsonNode snippet = item.path("snippet");
            out.add(new Link("https://www.youtube.com/watch?v=" + id,
                    snippet.path("title").asText("") + ". "
                            + snippet.path("description").asText("")));
        }
        return out;
    }

    // ---------------------------------------------------------- OpenStreetMap

    @Override
    public List<Place> places(String location, String amenity, int limit) {
        URI geocode = URI.create("https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
                + encode(location));
        JsonNode hits = Json.parse(send(HttpRequest.newBuilder(geocode)
                .header("User-Agent", USER_AGENT).header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20)).GET().build(), "Nominatim geocode"));
        if (!hits.isArray() || hits.isEmpty()) {
            return List.of();
        }
        double lat = hits.get(0).path("lat").asDouble();
        double lon = hits.get(0).path("lon").asDouble();
        String overpass = "[out:json][timeout:20];nwr(around:8000," + lat + "," + lon
                + ")[amenity=" + amenity + "];out center " + Math.max(1, limit * 3) + ";";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://overpass-api.de/api/interpreter"))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(40))
                .POST(HttpRequest.BodyPublishers.ofString("data=" + encode(overpass)))
                .build();
        JsonNode body = Json.parse(send(request, "Overpass query"));
        List<Place> out = new ArrayList<>();
        for (JsonNode element : body.path("elements")) {
            JsonNode tags = element.path("tags");
            String name = tags.path("name").asText("");
            if (name.isEmpty()) {
                continue;
            }
            double elat = element.has("lat") ? element.path("lat").asDouble()
                    : element.path("center").path("lat").asDouble();
            double elon = element.has("lon") ? element.path("lon").asDouble()
                    : element.path("center").path("lon").asDouble();
            String address = (tags.path("addr:street").asText("") + " "
                    + tags.path("addr:housenumber").asText("")).strip();
            boolean emergency = "yes".equals(tags.path("emergency").asText(""));
            out.add(new Place(name, address, elat, elon, tags.path("phone").asText(""),
                    emergency));
        }
        out.sort((a, b) -> Double.compare(distanceKm(lat, lon, a.lat(), a.lon()),
                distanceKm(lat, lon, b.lat(), b.lon())));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** Great-circle distance in kilometres (haversine). */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    // --------------------------------------------------------------- OpenAI

    @Override
    public <T> T structured(String tag, String prompt, Class<T> type) {
        // Spring AI's structured output: the response format comes from the
        // target type, and the answer binds straight into the record.
        T answer = chat.prompt()
                .system("You are a specialist on a travel-planning team. Use real, current"
                        + " information from the material you are given; never invent URLs.")
                .user(prompt)
                .call()
                .entity(type);
        if (answer == null) {
            throw new IllegalStateException("the model returned no " + type.getSimpleName() + " for " + tag);
        }
        return answer;
    }

    // ------------------------------------------------------------------ http

    private String send(HttpRequest request, String what) {
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(what + " failed: HTTP " + response.statusCode()
                        + " " + abbreviate(response.body()));
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException(what + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(what + " interrupted", e);
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
