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
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify;
import ai.badmonkey.agentspaces.agent.annotation.SpaceRef;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.DaySlot;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.ScheduledActivity;
import ai.badmonkey.agentspaces.partybus.domain.Research.AccessibilityRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.CarRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.DietRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.FlightRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.FoodieRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.HospitalRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.HotelRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.NextDestinationRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.PhotoRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.RestaurantRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.ReviewRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.SightseeingRequest;
import ai.badmonkey.agentspaces.partybus.domain.Safety;
import ai.badmonkey.agentspaces.partybus.domain.Safety.NewsSweep;
import ai.badmonkey.agentspaces.partybus.domain.Safety.SocialSweep;
import ai.badmonkey.agentspaces.partybus.domain.Safety.VideoSweep;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.EventResponse;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.TripEvent;
import ai.badmonkey.agentspaces.partybus.domain.Trip.TravelBrief;
import ai.badmonkey.agentspaces.partybus.domain.Trip.Travelers;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * The dispatcher: the one agent that knows what a trip needs researched. It
 * reacts to a {@link TravelBrief} by writing every specialist's request into
 * the research bag, the three safety sweeps, and one {@link DaySlot} per day
 * into the auction; and it reacts to every {@link TripEvent} the simulator (or
 * the turret) throws with the requests that event deserves. It writes requests
 * and never answers them, which keeps the fan-out in one place and the
 * specialists ignorant of each other.
 *
 * <p>This is the service-choreography shape Tripper's planner expresses as a
 * chain of {@code @Action}s. With the fleet in the picture the chain becomes a
 * fan-out into a bag, and the join happens in the {@link Assemblers.PlanAssembler}.
 */
@AgentSpec(name = "dispatcher",
        description = "Turns a travel brief and the events of a trip into the fleet's research requests",
        goals = {"research a trip"})
public final class Dispatcher {

    static final Lease REQUEST_LEASE = Lease.of(Duration.ofHours(6));

    @SpaceRef(Scouts.SPACE)
    Space research;
    @SpaceRef(Party.TRIP)
    Space trip;
    @SpaceRef(Planners.SLOTS)
    Space slots;

    /** Fan a brief out into the bag, the sweeps, and the auction. */
    @SpaceNotify(space = Party.TRIP, lease = "12h")
    public void dispatch(TravelBrief brief) {
        String id = brief.tripId();
        Travelers party = trip.read(Template.of(Travelers.class).where("tripId", eq(id)),
                Duration.ofSeconds(10)).orElseThrow(() -> new IllegalStateException(
                "no travelers written for trip " + id));
        List<String> needs = party.allNeeds();
        String who = party.contribution();
        double nightly = brief.dailyBudget() / 2.0; // Tripper's half-the-budget rule

        research.write(new FlightRequest(id, "flights " + brief.from() + " to " + brief.to(),
                brief.from(), brief.to(), brief.departureDate(), brief.returnDate(),
                brief.transportPreference()), REQUEST_LEASE);
        research.write(new HotelRequest(id, "hotels " + brief.to(), brief.to(),
                brief.departureDate(), brief.returnDate(), nightly, needs), REQUEST_LEASE);
        research.write(new CarRequest(id, "car " + brief.to(), brief.to(), brief.departureDate(),
                brief.returnDate(), party.travelers().size(), needs), REQUEST_LEASE);
        research.write(new SightseeingRequest(id, "sights " + brief.to(), brief.to(),
                brief.brief(), brief.departureDate(), brief.returnDate(), who), REQUEST_LEASE);
        research.write(new RestaurantRequest(id, "restaurants " + brief.to(), brief.to(),
                brief.dailyBudget() / 4.0, needs), REQUEST_LEASE);
        research.write(new DietRequest(id, "diet " + brief.to(), brief.to(), needs),
                REQUEST_LEASE);
        research.write(new FoodieRequest(id, "foodie " + brief.to(), brief.to(), brief.brief()),
                REQUEST_LEASE);
        research.write(new PhotoRequest(id, "photos " + brief.to(), "landmarks", brief.to()),
                REQUEST_LEASE);
        research.write(new ReviewRequest(id, "reviews " + brief.to(), "visiting", brief.to()),
                REQUEST_LEASE);
        research.write(new AccessibilityRequest(id, "accessibility " + brief.to(), brief.to(),
                needs), REQUEST_LEASE);
        research.write(new HospitalRequest(id, "hospitals " + brief.to(), brief.to(),
                "pre-trip: know the nearest emergency room"), REQUEST_LEASE);
        research.write(new NextDestinationRequest(id, "next after " + brief.to(), brief.to(),
                brief.brief(), who), REQUEST_LEASE);
        sweep(id, brief.to(), "travel safety", Safety.BASELINE);

        List<String> interests = List.of(brief.brief().split("[,;.]\\s*"));
        int day = 0;
        for (String date : brief.days()) {
            slots.write(new DaySlot(id, date, brief.to(), interests, day == 0 ? "EASY" : "FULL", 0),
                    REQUEST_LEASE);
            day++;
        }
    }

    /** React to what the trip throws at the party. */
    @SpaceNotify(space = Party.TRIP, lease = "12h")
    public EventResponse react(TripEvent event) {
        String id = event.tripId();
        switch (event.kind()) {
            case ILLNESS -> {
                research.write(new HospitalRequest(id, "hospitals " + event.eventId(),
                        event.location(), event.detail()), REQUEST_LEASE);
                research.write(new DietRequest(id, "diet " + event.eventId(), event.location(),
                        List.of("recovering from food poisoning", "bland food")), REQUEST_LEASE);
                return response(event, "asked the hospital locator for the nearest ER and the "
                        + "diet advisor for a gentle menu");
            }
            case PROTEST -> {
                sweep(id, event.location(), "protest unrest", event.eventId());
                return response(event, "opened a fresh safety sweep on all three channels; "
                        + "the panel re-votes the advisory");
            }
            case STRIKE -> {
                reauction(id, event.date());
                research.write(new CarRequest(id, "car " + event.eventId(), event.location(),
                        event.date(), event.date(), 3, List.of()), REQUEST_LEASE);
                return response(event, "re-auctioned the day and re-scouted ground transport");
            }
            case WEATHER -> {
                reauction(id, event.date());
                return response(event, "re-auctioned the day; outdoor bids rise, indoor bids win");
            }
            case LOST_PASSPORT -> {
                research.write(new AccessibilityRequest(id, "consulate " + event.eventId(),
                        event.location(), List.of("emergency travel document", "nearest consulate")),
                        REQUEST_LEASE);
                return response(event, "asked the accessibility advisor for the consulate route");
            }
            default -> {
                return response(event, "a calm day; nothing to do");
            }
        }
    }

    private void sweep(String tripId, String location, String topic, String wave) {
        research.write(new NewsSweep(tripId, topic, location, wave), REQUEST_LEASE);
        research.write(new VideoSweep(tripId, topic, location, wave), REQUEST_LEASE);
        research.write(new SocialSweep(tripId, topic, location, wave), REQUEST_LEASE);
    }

    private void reauction(String tripId, String date) {
        Optional<TravelBrief> brief = trip.read(Template.of(TravelBrief.class)
                .where("tripId", eq(tripId)));
        // Filled slots become ScheduledActivity results in the same space; the
        // next round is one past the highest round already scheduled for the day.
        int round = slots.readAll(Template.of(ScheduledActivity.class).where("tripId", eq(tripId))
                .where("date", eq(date)), 10).stream().mapToInt(ScheduledActivity::round).max()
                .orElse(0) + 1;
        List<String> interests = brief.map(b -> List.of(b.brief().split("[,;.]\\s*")))
                .orElse(List.of());
        String location = brief.map(TravelBrief::to).orElse("unknown");
        slots.write(new DaySlot(tripId, date, location, interests, "FULL", round), REQUEST_LEASE);
    }

    private static EventResponse response(TripEvent event, String action) {
        return new EventResponse(event.tripId(), event.eventId(), "dispatcher", action);
    }
}
