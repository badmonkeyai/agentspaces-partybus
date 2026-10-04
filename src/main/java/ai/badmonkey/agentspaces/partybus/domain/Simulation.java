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
package ai.badmonkey.agentspaces.partybus.domain;

/**
 * The vacation simulator's vocabulary: the clock it advances and the events it
 * throws at the fleet. Agents never talk to the simulator; they react to these
 * entries exactly as they would react to the real world, which is what makes
 * the simulation a test of the fleet rather than of the simulator.
 */
public final class Simulation {

    private Simulation() {
    }

    /** The kinds of thing that go wrong on a trip. */
    public enum EventKind {
        /** A storm; outdoor plans for the day should be rebid. */
        WEATHER,
        /** A transport strike; the day's slot re-auctions and ground transport is re-scouted. */
        STRIKE,
        /** Unrest in the city; the watchers sweep and the panel re-votes the advisory. */
        PROTEST,
        /** A traveler is ill; the hospital locator finds the nearest emergency room. */
        ILLNESS,
        /** Documents lost; the accessibility and hospital advisors point at the consulate. */
        LOST_PASSPORT,
        /** Nothing happened, a good day. */
        CALM
    }

    /**
     * The simulator's clock: the day the party is living through.
     *
     * @param tripId   the trip
     * @param date     the ISO date
     * @param dayIndex zero-based day number
     * @param location where the party is
     * @param pace     the pace the aggregate chose for the day
     */
    public record TripDay(String tripId, String date, int dayIndex, String location, String pace) {
    }

    /**
     * Something that happened on a day.
     *
     * @param tripId   the trip
     * @param date     the ISO date
     * @param kind     what happened
     * @param location where
     * @param detail   the story
     * @param severity 1 (nuisance) to 5 (emergency)
     * @param eventId  unique per event, so reactions deduplicate
     */
    public record TripEvent(String tripId, String date, EventKind kind, String location,
                            String detail, int severity, String eventId) {
    }

    /**
     * What an agent did about an event, so the turret shows the fleet's response.
     *
     * @param tripId  the trip
     * @param eventId the event
     * @param agent   who reacted
     * @param action  what it did
     */
    public record EventResponse(String tripId, String eventId, String agent, String action) {
    }
}
