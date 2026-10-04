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
import ai.badmonkey.agentspaces.partybus.domain.Research.CarOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.CarRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.FlightOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.FlightRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.HotelOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.HotelRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.Link;
import ai.badmonkey.agentspaces.partybus.domain.Research.NextDestinationIdeas;
import ai.badmonkey.agentspaces.partybus.domain.Research.NextDestinationRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.PhotoRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.PhotoSet;
import ai.badmonkey.agentspaces.partybus.domain.Research.RestaurantOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.RestaurantRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.ReviewDigest;
import ai.badmonkey.agentspaces.partybus.domain.Research.ReviewRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.SightseeingOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.SightseeingRequest;
import ai.badmonkey.agentspaces.partybus.sources.Sources;

import java.util.List;
import java.util.Objects;

import static ai.badmonkey.agentspaces.partybus.agents.Prompts.RESEARCHER;
import static ai.badmonkey.agentspaces.partybus.agents.Prompts.digest;
import static ai.badmonkey.agentspaces.partybus.agents.Prompts.list;

/**
 * The scouts: the replicated-worker idiom, eight times over. Each scout is one
 * class with one {@code @SpaceTake} method on the {@code research} space. The
 * binder turns the method's parameter type into that scout's work queue, takes
 * each request under a lease, and writes whatever the method returns back into
 * the space as the result, so a scout that dies mid-search lets its lease lapse
 * and another scout of the same kind picks the request up.
 *
 * <p>Tripper does this research in one process with {@code parallelMap};
 * here the parallelism is the fleet. Run two flight scouts and the flight
 * queue drains twice as fast; kill one and nothing is lost. Every scout also
 * publishes an {@code AgentCard} naming the type it consumes and the type it
 * produces, which is exactly what the planner bridge turns into an action.
 */
public final class Scouts {

    /** The research space every scout works. */
    public static final String SPACE = "research";

    private Scouts() {
    }

    /** Finds the outbound and return legs. */
    @AgentSpec(name = "flight-scout",
            description = "Finds flights and rail for the outbound and return legs of a trip",
            goals = {"find transport for a trip"})
    public static final class FlightScout {
        private final Sources sources;

        public FlightScout(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = SPACE, lease = "2m", pollTimeout = "500ms")
        public FlightOptions scout(FlightRequest request) {
            List<Link> web = sources.search("flights " + request.from() + " to " + request.to()
                    + " " + request.departureDate() + " return " + request.returnDate(), 8);
            FlightOptions found = sources.structured("flights", RESEARCHER + "\n"
                    + "Propose up to three itineraries from " + request.from() + " to "
                    + request.to() + " departing " + request.departureDate() + " returning "
                    + request.returnDate() + ". Preference: " + request.preference() + ".\n"
                    + digest("Search results", web), FlightOptions.class);
            return new FlightOptions(request.tripId(), request.topic(), found.options(),
                    found.note(), "flight-scout");
        }
    }

    /** Finds lodging in one town. */
    @AgentSpec(name = "hotel-scout",
            description = "Finds lodging in a town for a date range under a nightly budget",
            goals = {"find places to sleep"})
    public static final class HotelScout {
        private final Sources sources;

        public HotelScout(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = SPACE, lease = "2m", pollTimeout = "500ms")
        public HotelOptions scout(HotelRequest request) {
            List<Link> web = sources.search("hotels " + request.location() + " " + request.checkIn()
                    + " under $" + (int) request.nightlyBudget() + " "
                    + list(request.needs(), ""), 8);
            HotelOptions found = sources.structured("hotels:" + request.location(), RESEARCHER
                    + "\nFind up to four places to stay in " + request.location() + " from "
                    + request.checkIn() + " to " + request.checkOut() + " at or under $"
                    + request.nightlyBudget() + " a night. The party needs: "
                    + list(request.needs(), "nothing special") + ".\n"
                    + digest("Search results", web), HotelOptions.class);
            return new HotelOptions(request.tripId(), request.topic(), request.location(),
                    found.options(), found.note(), "hotel-scout");
        }
    }

    /** Finds a rental car or ground transport. */
    @AgentSpec(name = "car-scout",
            description = "Finds rental cars and ground transport at the destination",
            goals = {"find ground transport"})
    public static final class CarScout {
        private final Sources sources;

        public CarScout(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = SPACE, lease = "2m", pollTimeout = "500ms")
        public CarOptions scout(CarRequest request) {
            List<Link> web = sources.search("car rental " + request.location() + " "
                    + request.pickupDate() + " " + list(request.needs(), ""), 8);
            CarOptions found = sources.structured("cars", RESEARCHER + "\nFind up to three "
                    + "rental or ground-transport options in " + request.location() + " from "
                    + request.pickupDate() + " to " + request.dropoffDate() + " for "
                    + request.partySize() + " people. Needs: "
                    + list(request.needs(), "none") + ".\n" + digest("Search results", web),
                    CarOptions.class);
            return new CarOptions(request.tripId(), request.topic(), found.options(),
                    found.note(), "car-scout");
        }
    }

    /** Proposes points of interest for the whole trip. */
    @AgentSpec(name = "sightseeing-scout",
            description = "Proposes points of interest suited to the travelers and the brief",
            goals = {"propose points of interest"})
    public static final class SightseeingScout {
        private final Sources sources;

        public SightseeingScout(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = SPACE, lease = "3m", pollTimeout = "500ms")
        public SightseeingOptions scout(SightseeingRequest request) {
            List<Link> web = sources.search("things to do " + request.destination() + " "
                    + request.brief(), 10);
            SightseeingOptions found = sources.structured("sights", RESEARCHER
                    + "\nPropose six to eight points of interest in and around "
                    + request.destination() + " between " + request.departureDate() + " and "
                    + request.returnDate() + " for these travelers:\n" + request.travelers()
                    + "\nBrief: " + request.brief() + "\nOrder them sensibly and give each a "
                    + "date range within the trip.\n" + digest("Search results", web),
                    SightseeingOptions.class);
            return new SightseeingOptions(request.tripId(), request.topic(),
                    found.pointsOfInterest(), "sightseeing-scout");
        }
    }

    /** Finds restaurants near a location. */
    @AgentSpec(name = "restaurant-scout",
            description = "Finds restaurants near a location that suit the party and budget",
            goals = {"find restaurants"})
    public static final class RestaurantScout {
        private final Sources sources;

        public RestaurantScout(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = SPACE, lease = "2m", pollTimeout = "500ms")
        public RestaurantOptions scout(RestaurantRequest request) {
            List<Link> web = sources.search("best restaurants " + request.location() + " "
                    + list(request.needs(), ""), 10);
            RestaurantOptions found = sources.structured("restaurants:" + request.location(),
                    RESEARCHER + "\nPick up to six restaurants in " + request.location()
                    + " around $" + (int) request.perPersonBudget() + " a head, noting which "
                    + "suit these needs: " + list(request.needs(), "none") + ".\n"
                    + digest("Search results", web), RestaurantOptions.class);
            return new RestaurantOptions(request.tripId(), request.topic(), request.location(),
                    found.restaurants(), "restaurant-scout");
        }
    }

    /** Curates the key photographs of a place. */
    @AgentSpec(name = "photo-curator",
            description = "Curates the key photographs of a place for the plan and the photo walk",
            goals = {"curate photographs"})
    public static final class PhotoCurator {
        private final Sources sources;

        public PhotoCurator(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = SPACE, lease = "1m", pollTimeout = "500ms")
        public PhotoSet curate(PhotoRequest request) {
            List<Link> images = sources.images(request.subject() + " " + request.location(), 6);
            return new PhotoSet(request.tripId(), request.topic(), images, "photo-curator");
        }
    }

    /** Digests reviews of a place for this party. */
    @AgentSpec(name = "review-reader",
            description = "Reads traveler reviews of a place and digests praise and complaints",
            goals = {"digest reviews"})
    public static final class ReviewReader {
        private final Sources sources;

        public ReviewReader(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = SPACE, lease = "2m", pollTimeout = "500ms")
        public ReviewDigest read(ReviewRequest request) {
            List<Link> web = sources.search(request.subject() + " " + request.location()
                    + " reviews", 10);
            ReviewDigest found = sources.structured("reviews", RESEARCHER + "\nDigest what "
                    + "travelers say about " + request.subject() + " in " + request.location()
                    + ": an average rating out of five, the recurring praise, and the recurring "
                    + "complaints.\n" + digest("Search results", web), ReviewDigest.class);
            return new ReviewDigest(request.tripId(), request.topic(), found.averageRating(),
                    found.praise(), found.complaints(), web, "review-reader");
        }
    }

    /** Suggests where the party might go next. */
    @AgentSpec(name = "next-destination-advisor",
            description = "Suggests where the party might travel next, given what they enjoyed",
            goals = {"suggest the next destination"})
    public static final class NextDestinationAdvisor {
        private final Sources sources;

        public NextDestinationAdvisor(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = SPACE, lease = "2m", pollTimeout = "500ms")
        public NextDestinationIdeas advise(NextDestinationRequest request) {
            List<Link> web = sources.search("destinations like " + request.after() + " for "
                    + request.brief(), 8);
            NextDestinationIdeas found = sources.structured("next", RESEARCHER
                    + "\nThe party is finishing a trip to " + request.after() + ". Travelers:\n"
                    + request.travelers() + "\nBrief: " + request.brief()
                    + "\nSuggest three next destinations with a reason, a best season, and an "
                    + "estimated daily cost.\n" + digest("Search results", web),
                    NextDestinationIdeas.class);
            return new NextDestinationIdeas(request.tripId(), request.topic(), found.ideas(),
                    "next-destination-advisor");
        }
    }
}
