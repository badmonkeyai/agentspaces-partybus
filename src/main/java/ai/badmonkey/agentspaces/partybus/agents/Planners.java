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
package ai.badmonkey.agentspaces.partybus.agents;

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.BidFunction;
import ai.badmonkey.agentspaces.agent.annotation.SpaceRef;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.DaySlot;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.ScheduledActivity;
import ai.badmonkey.agentspaces.partybus.domain.Research.PhotoSet;
import ai.badmonkey.agentspaces.partybus.domain.Research.RestaurantOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.SightseeingOptions;
import ai.badmonkey.agentspaces.partybus.domain.Trip.PointOfInterest;

import java.util.List;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * The activity planners: four agents that bid for every day of the trip in
 * the {@code slots} space, which runs the {@code AUCTION} strategy. A
 * {@link DaySlot} is written once per day; each planner's {@code @BidFunction}
 * prices the day from its own point of view (how well the day suits what it
 * does, how the party's interests match, how the pace and the weather sit with
 * it); the space runs the CBBA rounds and awards the day to the lowest bid; and
 * the winner's {@code @SpaceTake} fires with the slot, returning the
 * {@link ScheduledActivity} the space writes back.
 *
 * <p>Nobody dispatches. The dining planner wins the foodie's days, the sights
 * planner wins the historian's days, the photo walk sweeps the cheap days in
 * between, and when the travelers' energy aggregate turns a day to
 * {@code REST}, the rest planner's bid collapses and it wins the day with a
 * pool and a long lunch. When the simulator's storm re-auctions a day
 * ({@code round} climbs), the outdoor planners' bids rise and the day changes
 * hands. That is the whole scheduler.
 *
 * <p>Every planner carries a {@code @SpaceRef} to the research space so its
 * schedule names real places the scouts found rather than a placeholder.
 */
public final class Planners {

    /** The auction space. */
    public static final String SLOTS = "slots";

    private Planners() {
    }

    /** Whether the party's interests mention any of the given words. */
    static boolean interested(DaySlot slot, String... words) {
        for (String interest : slot.interests()) {
            String lower = interest.toLowerCase();
            for (String word : words) {
                if (lower.contains(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Dinner, lunch, and everything in between. */
    @AgentSpec(name = "dining-planner",
            description = "Plans a day around the table: markets, long lunches, a reserved dinner",
            goals = {"fill a day with food"})
    public static final class DiningPlanner {
        @SpaceRef(Scouts.SPACE)
        Space research;

        @BidFunction(space = SLOTS)
        public double bid(DaySlot slot) {
            double bid = 90;
            if (interested(slot, "food", "wine", "eat", "cook")) {
                bid -= 35;
            }
            if (slot.pace().equals("REST")) {
                bid += 20;
            }
            return bid + slot.round() * 5; // a strike barely touches a lunch
        }

        @SpaceTake(space = SLOTS, lease = "1m", pollTimeout = "500ms")
        public ScheduledActivity plan(DaySlot slot) {
            String table = research.readAll(Template.of(RestaurantOptions.class)
                            .where("tripId", eq(slot.tripId())), 20).stream()
                    .filter(r -> r.location().equalsIgnoreCase(slot.location()))
                    .flatMap(r -> r.restaurants().stream()).map(r -> r.name()).findFirst()
                    .orElse("a table the scouts are still finding");
            return new ScheduledActivity(slot.tripId(), slot.date(), slot.location(), "DINING",
                    "Market morning, long lunch, dinner at " + table, bid(slot),
                    "dining-planner", slot.round());
        }
    }

    /** Monuments, museums, and the walk between them. */
    @AgentSpec(name = "sights-planner",
            description = "Plans a day of sights: monuments, museums, and the walk between them",
            goals = {"fill a day with sights"})
    public static final class SightsPlanner {
        @SpaceRef(Scouts.SPACE)
        Space research;

        @BidFunction(space = SLOTS)
        public double bid(DaySlot slot) {
            double bid = 80;
            if (interested(slot, "history", "architecture", "museum", "art")) {
                bid -= 35;
            }
            if (slot.pace().equals("REST")) {
                bid += 80; // nobody wants a six-mile walk on a rest day
            }
            return bid + slot.round() * 45; // storms and strikes ruin an outdoor day
        }

        @SpaceTake(space = SLOTS, lease = "1m", pollTimeout = "500ms")
        public ScheduledActivity plan(DaySlot slot) {
            List<PointOfInterest> pois = research.readAll(Template.of(SightseeingOptions.class)
                            .where("tripId", eq(slot.tripId())), 5).stream()
                    .flatMap(s -> s.pointsOfInterest().stream()).toList();
            String pick = pois.isEmpty() ? "the old town"
                    : pois.get(Math.abs(slot.date().hashCode()) % pois.size()).name();
            return new ScheduledActivity(slot.tripId(), slot.date(), slot.location(), "SIGHTS",
                    "Sights day: " + pick + " and the streets around it", bid(slot),
                    "sights-planner", slot.round());
        }
    }

    /** Golden hour, viewpoints, and the curated shot list. */
    @AgentSpec(name = "photo-walk-planner",
            description = "Plans a photo walk around the curated shot list and the light",
            goals = {"fill a day with photographs"})
    public static final class PhotoWalkPlanner {
        @SpaceRef(Scouts.SPACE)
        Space research;

        @BidFunction(space = SLOTS)
        public double bid(DaySlot slot) {
            double bid = 75; // a walk and a camera, and the light does the rest
            if (interested(slot, "photo", "camera", "instagram")) {
                bid -= 25;
            }
            if (slot.pace().equals("EASY")) {
                bid -= 20; // arrival days and gentle days are made for wandering
            }
            if (slot.pace().equals("REST")) {
                bid += 60;
            }
            return bid + slot.round() * 50; // rain and a camera do not mix
        }

        @SpaceTake(space = SLOTS, lease = "1m", pollTimeout = "500ms")
        public ScheduledActivity plan(DaySlot slot) {
            int shots = research.readAll(Template.of(PhotoSet.class)
                            .where("tripId", eq(slot.tripId())), 10).stream()
                    .mapToInt(p -> p.images().size()).sum();
            return new ScheduledActivity(slot.tripId(), slot.date(), slot.location(),
                    "PHOTO_WALK", "Golden-hour walk with a " + shots + "-shot list", bid(slot),
                    "photo-walk-planner", slot.round());
        }
    }

    /** The day off. */
    @AgentSpec(name = "rest-planner",
            description = "Plans a day off: a pool, a long lunch, an early night",
            goals = {"give the party a day off"})
    public static final class RestPlanner {
        @BidFunction(space = SLOTS)
        public double bid(DaySlot slot) {
            // Expensive to waste a good day; nearly free when the party is spent.
            return slot.pace().equals("REST") ? 5 : 140;
        }

        @SpaceTake(space = SLOTS, lease = "1m", pollTimeout = "500ms")
        public ScheduledActivity plan(DaySlot slot) {
            return new ScheduledActivity(slot.tripId(), slot.date(), slot.location(), "REST",
                    "Rest day: pool, siesta, one gentle dinner", bid(slot), "rest-planner",
                    slot.round());
        }
    }
}
