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
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import ai.badmonkey.agentspaces.partybus.domain.Research.AccessibilityGuide;
import ai.badmonkey.agentspaces.partybus.domain.Research.AccessibilityRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.DietOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.DietRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.FoodieOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.FoodieRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.Hospital;
import ai.badmonkey.agentspaces.partybus.domain.Research.HospitalList;
import ai.badmonkey.agentspaces.partybus.domain.Research.HospitalRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.Link;
import ai.badmonkey.agentspaces.partybus.sources.Sources;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static ai.badmonkey.agentspaces.partybus.agents.Prompts.RESEARCHER;
import static ai.badmonkey.agentspaces.partybus.agents.Prompts.digest;
import static ai.badmonkey.agentspaces.partybus.agents.Prompts.list;

/**
 * The welfare advisors: the same replicated-worker shape as the scouts, on
 * the questions Tripper never asks. What can the traveler with the nut allergy
 * eat, where does the traveler who uses a wheelchair get stuck, and which
 * emergency room is nearest when somebody eats the wrong thing anyway.
 *
 * <p>The hospital locator is the one specialist that goes to the map rather
 * than the model: OpenStreetMap knows where hospitals are, and a language
 * model guessing at an address is the wrong tool for an emergency.
 */
public final class Advisors {

    private Advisors() {
    }

    /** Sensible options for travelers with dietary needs. */
    @AgentSpec(name = "diet-advisor",
            description = "Finds sensible dining for travelers with dietary needs and allergies",
            goals = {"feed everyone safely"})
    public static final class DietAdvisor {
        private final Sources sources;

        public DietAdvisor(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = Scouts.SPACE, lease = "2m", pollTimeout = "500ms")
        public DietOptions advise(DietRequest request) {
            List<Link> web = sources.search(list(request.needs(), "healthy") + " restaurants "
                    + request.location(), 10);
            DietOptions found = sources.structured("diet", RESEARCHER + "\nIn "
                    + request.location() + ", find up to five restaurants that reliably serve "
                    + "travelers who need: " + list(request.needs(), "sensible portions")
                    + ". Add practical tips: phrases to ask with, dishes to avoid, what to carry.\n"
                    + digest("Search results", web), DietOptions.class);
            return new DietOptions(request.tripId(), request.topic(), found.restaurants(),
                    found.tips(), "diet-advisor");
        }
    }

    /** The best food experiences a place offers. */
    @AgentSpec(name = "foodie-advisor",
            description = "Finds the dishes and tables a place is famous for",
            goals = {"eat the best of a place"})
    public static final class FoodieAdvisor {
        private final Sources sources;

        public FoodieAdvisor(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = Scouts.SPACE, lease = "2m", pollTimeout = "500ms")
        public FoodieOptions advise(FoodieRequest request) {
            List<Link> web = sources.search("must eat " + request.location()
                    + " famous dishes best restaurants", 10);
            FoodieOptions found = sources.structured("foodie", RESEARCHER + "\nFor "
                    + request.location() + ", name the five dishes the party must try and the "
                    + "tables to try them at. Brief: " + request.brief() + "\n"
                    + digest("Search results", web), FoodieOptions.class);
            return new FoodieOptions(request.tripId(), request.topic(), found.mustEat(),
                    found.dishes(), "foodie-advisor");
        }
    }

    /** How to travel a place with mobility, sight, or hearing restrictions. */
    @AgentSpec(name = "accessibility-advisor",
            description = "Explains how to move through a place with mobility, sight, or hearing needs",
            goals = {"keep the trip accessible"})
    public static final class AccessibilityAdvisor {
        private final Sources sources;

        public AccessibilityAdvisor(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = Scouts.SPACE, lease = "2m", pollTimeout = "500ms")
        public AccessibilityGuide advise(AccessibilityRequest request) {
            List<Link> web = sources.search(request.location() + " accessibility "
                    + list(request.needs(), "wheelchair") + " transit attractions", 10);
            AccessibilityGuide found = sources.structured("accessibility", RESEARCHER
                    + "\nFor travelers in " + request.location() + " who need: "
                    + list(request.needs(), "step-free access") + ", explain transit, lodging, "
                    + "and attractions that work, and the specific traps (cobbles, hills, "
                    + "stations with no lift).\n" + digest("Search results", web),
                    AccessibilityGuide.class);
            return new AccessibilityGuide(request.tripId(), request.topic(), found.transit(),
                    found.lodging(), found.attractions(), found.warnings(),
                    "accessibility-advisor");
        }
    }

    /** The nearest hospitals, from the map. */
    @AgentSpec(name = "hospital-locator",
            description = "Locates the nearest hospitals and emergency rooms to a place",
            goals = {"find the nearest hospital"})
    public static final class HospitalLocator {
        private final Sources sources;

        public HospitalLocator(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = Scouts.SPACE, lease = "1m", pollTimeout = "500ms")
        public HospitalList locate(HospitalRequest request) {
            List<Sources.Place> places = sources.places(request.location(), "hospital", 5);
            List<Hospital> hospitals = new ArrayList<>();
            for (Sources.Place place : places) {
                hospitals.add(new Hospital(place.name(), place.address(),
                        Math.round(distanceFromFirst(places, place) * 10.0) / 10.0,
                        place.emergency(), place.phone()));
            }
            return new HospitalList(request.tripId(), request.topic(), request.location(),
                    hospitals, emergencyNumber(request.location()), "hospital-locator");
        }

        private static double distanceFromFirst(List<Sources.Place> places, Sources.Place place) {
            if (places.isEmpty()) {
                return 0.0;
            }
            Sources.Place origin = places.get(0);
            return ai.badmonkey.agentspaces.partybus.sources.LiveSources.distanceKm(
                    origin.lat(), origin.lon(), place.lat(), place.lon());
        }

        /** The emergency number: 112 across Europe, 911 in North America, else 112. */
        static String emergencyNumber(String location) {
            String lower = location.toLowerCase();
            if (lower.contains("usa") || lower.contains("united states") || lower.contains(", us")
                    || lower.contains("canada") || lower.contains("mexico")) {
                return "911";
            }
            return "112";
        }
    }
}
