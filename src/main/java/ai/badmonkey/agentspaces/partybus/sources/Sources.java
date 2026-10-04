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

import java.util.List;

/**
 * Everything a scout reads from the outside world, behind one provider
 * interface. Tripper reaches the world through MCP tool groups (web, maps,
 * Brave image search, Airbnb) and through an LLM; Party Bus keeps the same
 * shape with plain HTTP clients so that the fleet compiles with no framework:
 * {@link LiveSources} talks to the real APIs and requires the API keys, exactly
 * as Tripper does, and {@link RecordedSources} replays a recorded trip for the
 * simulator and the tests.
 *
 * <p>The seam is orthogonal to coordination. A scout builds a query, asks its
 * sources, and writes a typed result entry; which sources answered is nobody
 * else's business.
 */
public interface Sources {

    /** A place from the map provider. */
    record Place(String name, String address, double lat, double lon, String phone,
                 boolean emergency) {
    }

    /** A short label for logs and the console: {@code live} or {@code recorded}. */
    String name();

    /** Web search results. */
    List<Link> search(String query, int limit);

    /** News results, freshest first. */
    List<Link> news(String query, int limit);

    /** Image results; each link is the image itself. */
    List<Link> images(String query, int limit);

    /** Video results, YouTube first when a key is present. */
    List<Link> videos(String query, int limit);

    /**
     * Places of one kind near a location, from the map provider.
     *
     * @param location a place name to geocode
     * @param amenity  an OpenStreetMap amenity value, e.g. {@code hospital}
     * @param limit    the maximum results
     * @return the places, nearest first
     */
    List<Place> places(String location, String amenity, int limit);

    /**
     * Asks the language model for a structured answer and binds it to a record
     * type. The prompt describes the task; the implementation adds the shape of
     * the record and parses the JSON that comes back.
     *
     * @param tag    a stable name for the kind of answer (the recorded key)
     * @param prompt the task
     * @param type   the record type to bind
     * @param <T>    the record type
     * @return the bound answer
     */
    <T> T structured(String tag, String prompt, Class<T> type);

    /**
     * The sources the environment selects: {@link LiveSources} when the API keys
     * are present, and otherwise a fail-fast error naming what is missing, the
     * way Tripper insists on its keys. {@code PARTYBUS_SOURCES=recorded} selects
     * the recorded trip explicitly.
     *
     * @return the configured sources
     */
    static Sources fromEnvironment() {
        String mode = System.getenv().getOrDefault("PARTYBUS_SOURCES", "live");
        if ("recorded".equalsIgnoreCase(mode)) {
            return RecordedSources.lisbon();
        }
        return LiveSources.fromEnvironment();
    }
}
