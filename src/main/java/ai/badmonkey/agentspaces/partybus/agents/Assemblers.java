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
import ai.badmonkey.agentspaces.agent.annotation.Part;
import ai.badmonkey.agentspaces.agent.annotation.SpaceJoin;
import ai.badmonkey.agentspaces.agent.join.Joined;
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify;
import ai.badmonkey.agentspaces.agent.annotation.SpaceRef;
import ai.badmonkey.agentspaces.agent.annotation.OrderedTake;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.TakenEntry;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.capabilities.orderedlog.OrderedTakes;
import ai.badmonkey.agentspaces.capabilities.orderedlog.RaftLog;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.BookingConfirmation;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.BookingOrder;
import ai.badmonkey.agentspaces.partybus.domain.Research.FlightOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.FoodieOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.HotelOption;
import ai.badmonkey.agentspaces.partybus.domain.Research.HotelOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.RestaurantOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.SightseeingOptions;
import ai.badmonkey.agentspaces.partybus.domain.Safety.TravelAdvisory;
import ai.badmonkey.agentspaces.partybus.domain.Trip.Day;
import ai.badmonkey.agentspaces.partybus.domain.Trip.ProposedTravelPlan;
import ai.badmonkey.agentspaces.partybus.domain.Trip.Stay;
import ai.badmonkey.agentspaces.partybus.domain.Trip.TravelBrief;
import ai.badmonkey.agentspaces.partybus.domain.Trip.TravelPlan;
import ai.badmonkey.agentspaces.partybus.domain.Trip.Travelers;
import ai.badmonkey.agentspaces.partybus.sources.Sources;
import ai.badmonkey.agentspaces.space.replicated.ReplicatedSpace;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;
import static ai.badmonkey.agentspaces.partybus.agents.Prompts.PLANNER;

/**
 * The join, the stays, and the bookings: where the fleet's research becomes a
 * plan, and the plan becomes reservations committed exactly once.
 *
 * <p>The {@link PlanAssembler} is the join of the fan-out: it waits until the
 * research bundle a trip needs is present in the space (the sights, the
 * hotels, the restaurants, the foodie list, the flights) and writes Tripper's
 * {@code ProposedTravelPlan} from it. The {@link StayFinder} reacts to the plan
 * the way Tripper's {@code findPlacesToSleep} follows {@code proposeTravelPlan},
 * groups the days into stays, attaches the hotel scout's options and the safety
 * panel's advisory, and writes one {@link BookingOrder} per stay. The
 * {@link BookingClerk}s take those orders through {@code aspace:cap/ordered-log}
 * so that a reservation (real money) is confirmed by exactly one clerk, even
 * when three are racing for it.
 */
public final class Assemblers {

    /** The space bookings live in. */
    public static final String BOOKINGS = "bookings";

    private Assemblers() {
    }

    /**
     * The join. A loop rather than a notify, because it waits on five different
     * entry types and fires once per trip when the last of them lands.
     *
     * @param research the research space
     * @param trip     the trip space
     * @param sources  the model that writes the plan
     * @return a handle; close it to stop the assembler
     */
    /**
     * The plan assembler as one join (issue #16): a brief in the trip space and
     * five research results in the research space, keyed by the trip id, fire
     * once per trip when all are present; the party roster is optional. This
     * replaced a virtual-thread loop over every brief with an {@code assembled}
     * set, five reads per brief, and a sleep.
     */
    @AgentSpec(name = "plan-assembler",
            description = "Drafts the trip plan once the scouts' research is in",
            goals = {"draft the plan"})
    public static final class PlanAssembler {
        private final Sources sources;

        public PlanAssembler(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceJoin(space = Party.TRIP, key = "tripId", resultLease = "12h",
                parts = {@Part(TravelBrief.class),
                         @Part(value = SightseeingOptions.class, space = Scouts.SPACE),
                         @Part(value = HotelOptions.class, space = Scouts.SPACE),
                         @Part(value = RestaurantOptions.class, space = Scouts.SPACE),
                         @Part(value = FoodieOptions.class, space = Scouts.SPACE),
                         @Part(value = FlightOptions.class, space = Scouts.SPACE),
                         @Part(value = Travelers.class, optional = true)})
        public ProposedTravelPlan assemble(Joined j) {
            TravelBrief brief = j.get(TravelBrief.class);
            Travelers party = j.find(Travelers.class).orElse(new Travelers(j.key(), List.of()));
            ProposedTravelPlan drafted = sources.structured("plan", PLANNER + "\n"
                    + "Write the plan for this brief as HTML starting at <h4>, in 500 "
                    + "words or fewer, with a catchy title, one Day entry per date in "
                    + "'City,+Country' form, and the countries visited.\n<brief>"
                    + brief.contribution() + "</brief>\n" + party.contribution()
                    + "\nPoints of interest: " + j.get(SightseeingOptions.class).pointsOfInterest()
                    + "\nHotels: " + j.get(HotelOptions.class).options()
                    + "\nRestaurants: " + j.get(RestaurantOptions.class).restaurants()
                    + "\nMust eat: " + j.get(FoodieOptions.class).dishes()
                    + "\nFlights: " + j.get(FlightOptions.class).options(), ProposedTravelPlan.class);
            List<Day> days = drafted.days().isEmpty() ? defaultDays(brief) : drafted.days();
            return new ProposedTravelPlan(j.key(), drafted.title(), drafted.plan(), days,
                    drafted.countriesVisited(), "plan-assembler");
        }
    }

    private static List<Day> defaultDays(TravelBrief brief) {
        List<Day> days = new ArrayList<>();
        for (String date : brief.days()) {
            days.add(new Day(date, brief.to(), "in " + brief.to()));
        }
        return days;
    }

    /** Groups the plan's days into stays, attaches lodging and the advisory, orders the rooms. */
    @AgentSpec(name = "stay-finder",
            description = "Turns a proposed plan into stays with lodging, the safety advisory, and booking orders",
            goals = {"find places to sleep", "finish the travel plan"})
    public static final class StayFinder {
        @SpaceRef(Scouts.SPACE)
        Space research;
        @SpaceRef(BOOKINGS)
        Space bookings;
        @SpaceRef(Party.TRIP)
        Space trip;

        @SpaceNotify(space = Party.TRIP, lease = "12h")
        public TravelPlan finish(ProposedTravelPlan plan) {
            Map<String, List<String>> byTown = new LinkedHashMap<>();
            for (Day day : plan.days()) {
                byTown.computeIfAbsent(day.stayingAt(), k -> new ArrayList<>()).add(day.date());
            }
            List<HotelOptions> hotels = research.readAll(Template.of(HotelOptions.class)
                    .where("tripId", eq(plan.tripId())), 20);
            List<Stay> stays = new ArrayList<>();
            int order = 0;
            for (Map.Entry<String, List<String>> e : byTown.entrySet()) {
                Optional<HotelOption> pick = hotels.stream()
                        .filter(h -> h.location().toLowerCase().startsWith(e.getKey().toLowerCase())
                                || e.getKey().toLowerCase().startsWith(h.location().toLowerCase()))
                        .flatMap(h -> h.options().stream()).findFirst()
                        .or(() -> hotels.stream().flatMap(h -> h.options().stream()).findFirst());
                Stay stay = new Stay(e.getKey(), e.getValue(),
                        pick.map(HotelOption::name).orElse("lodging still being scouted"),
                        pick.map(HotelOption::bookingUrl).orElse(""),
                        pick.map(HotelOption::nightlyUsd).orElse(0.0));
                stays.add(stay);
                if (pick.isPresent()) {
                    bookings.write(new BookingOrder(plan.tripId(),
                            plan.tripId() + "-stay-" + (++order), "HOTEL", stay.lodging(),
                            e.getValue().get(0), stay.nightly() * e.getValue().size()),
                            Lease.of(Duration.ofHours(12)));
                }
            }
            // The panel's baseline vote usually lands before the plan; give it a
            // moment when it has not, and ship PENDING rather than wait forever.
            String advisory = trip.read(Template.of(TravelAdvisory.class)
                            .where("tripId", eq(plan.tripId())), Duration.ofSeconds(45))
                    .map(TravelAdvisory::verdict).orElse("PENDING");
            return new TravelPlan(plan.tripId(), plan.title(), plan.plan(), plan.days(), stays,
                    advisory, mapUrl(plan.days()));
        }

        /** Tripper computes the map link in code because models get it wrong; so do we. */
        static String mapUrl(List<Day> days) {
            List<String> places = days.stream().map(Day::locationAndCountry).distinct()
                    .map(p -> URLEncoder.encode(p, StandardCharsets.UTF_8)).toList();
            if (places.size() == 1) {
                return "https://www.google.com/maps/search/?api=1&query=" + places.get(0);
            }
            return "https://www.google.com/maps/dir/" + String.join("/", places);
        }
    }

    /**
     * A booking clerk: takes each order through the log and confirms it exactly
     * once. {@code @OrderedTake} is {@code @SpaceTake} routed through the space's
     * ordered-log coordinator, so the binder owns the loop and the resubmit after
     * a leader election; the returned confirmation completes the take.
     */
    @AgentSpec(name = "booking-clerk", description = "Confirms bookings exactly once",
            goals = {"book"})
    public static final class BookingClerk {
        private final RaftLog raft;
        private final String clerk;

        public BookingClerk(RaftLog raft, String clerk) {
            this.raft = Objects.requireNonNull(raft, "raft");
            this.clerk = Objects.requireNonNull(clerk, "clerk");
        }

        @OrderedTake(space = BOOKINGS, lease = "30s", pollTimeout = "2s", resultLease = "12h")
        public BookingConfirmation confirm(BookingOrder order) {
            return new BookingConfirmation(order.tripId(), order.orderId(),
                    confirmationCode(order, clerk), order.amountUsd(), clerk, raft.commitIndex());
        }
    }

    static String confirmationCode(BookingOrder order, String clerk) {
        return ("PB-" + Integer.toHexString((order.orderId() + clerk).hashCode())).toUpperCase();
    }
}
