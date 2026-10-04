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
package ai.badmonkey.agentspaces.partybus.fleet;

import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.console.CommandContext;
import ai.badmonkey.agentspaces.console.ConsoleCommand;
import ai.badmonkey.agentspaces.console.ConsolePanel;
import ai.badmonkey.agentspaces.console.ConsoleView;
import ai.badmonkey.agentspaces.console.FleetCommander;
import ai.badmonkey.agentspaces.console.FleetConsoleServer;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.BookingConfirmation;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.BookingOrder;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.DaySlot;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.EnergyReport;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.PartyDecision;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.ScheduledActivity;
import ai.badmonkey.agentspaces.partybus.domain.Research;
import ai.badmonkey.agentspaces.partybus.domain.Safety;
import ai.badmonkey.agentspaces.partybus.domain.Safety.TravelAdvisory;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.EventKind;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.EventResponse;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.TripDay;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.TripEvent;
import ai.badmonkey.agentspaces.partybus.domain.Trip.Traveler;
import ai.badmonkey.agentspaces.partybus.domain.Trip.TravelBrief;
import ai.badmonkey.agentspaces.partybus.domain.Trip.TravelPlan;
import ai.badmonkey.agentspaces.partybus.domain.Trip.Travelers;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * The vacation command-and-control turret: the fleet console with Party Bus
 * panels and commands. It is one more read-mostly peer. The panels are pure
 * reads of the spaces (the itinerary, the advisories, the bookings, the
 * party's energy, the fleet's responses to events), and the commands are
 * writes into them: start a trip, throw an event at the fleet, reroute the
 * party, pause the scouts. Every command lands in the {@code c2-audit} space
 * signed by the operator's peer, so the trip's audit trail includes the human.
 *
 * <p>The console serves HAL+JSON under {@code /api/v1}, Server-Sent Events at
 * {@code /api/v1/events}, and a web UI at {@code /}, so the turret is usable
 * from a browser, from {@code curl}, or from a Spring HATEOAS client.
 */
public final class Turret implements AutoCloseable {

    /** The operator token for command-and-control. */
    public static final String OPERATOR_TOKEN = "partybus-operator";

    private final PartyBusFleet.Peer peer;
    private final ConsoleView view;
    private final FleetConsoleServer server;

    private Turret(PartyBusFleet.Peer peer, ConsoleView view, FleetConsoleServer server) {
        this.peer = peer;
        this.view = view;
        this.server = server;
    }

    /**
     * Builds the turret over a peer's replicas.
     *
     * @param peer the peer the turret observes and commands through
     * @return the turret, not yet listening
     */
    public static Turret over(PartyBusFleet.Peer peer) {
        Objects.requireNonNull(peer, "peer");
        ConsoleView.Builder view = ConsoleView.builder()
                .space(PartyBusFleet.RESEARCH, peer.space(PartyBusFleet.RESEARCH),
                        Research.FlightRequest.class, Research.HotelRequest.class,
                        Research.CarRequest.class, Research.SightseeingRequest.class,
                        Research.RestaurantRequest.class, Research.DietRequest.class,
                        Research.FoodieRequest.class, Research.PhotoRequest.class,
                        Research.ReviewRequest.class, Research.AccessibilityRequest.class,
                        Research.HospitalRequest.class, Research.NextDestinationRequest.class,
                        Safety.NewsSweep.class, Safety.VideoSweep.class, Safety.SocialSweep.class)
                .space(PartyBusFleet.SLOTS, peer.space(PartyBusFleet.SLOTS), DaySlot.class)
                .results(PartyBusFleet.SLOTS, ScheduledActivity.class,
                        a -> ((ScheduledActivity) a).plannedBy())
                .space(PartyBusFleet.BOOKINGS, peer.space(PartyBusFleet.BOOKINGS),
                        BookingOrder.class)
                .results(PartyBusFleet.BOOKINGS, BookingConfirmation.class,
                        c -> ((BookingConfirmation) c).clerk())
                .members(() -> peer.runtime().membership().allMembers())
                .discovery(peer.discovery());
        for (Class<?> result : RESULT_TYPES) {
            view.results(PartyBusFleet.RESEARCH, result, Turret::authorOf);
        }
        ConsoleView built = view.build();

        FleetCommander commander = FleetCommander.builder(peer.spaces()::get)
                .controlSpace(PartyBusFleet.CONTROL)
                .auditSpace(PartyBusFleet.AUDIT)
                .view(built)
                .command(startTrip())
                .command(throwEvent())
                .command(reroute(peer.space(PartyBusFleet.TRIP)))
                .build();

        FleetConsoleServer server = FleetConsoleServer.builder(built)
                .fleetName("partybus")
                .commandAndControl(commander, OPERATOR_TOKEN)
                .panel(itineraryPanel(peer))
                .panel(safetyPanel(peer))
                .panel(bookingsPanel(peer))
                .panel(energyPanel(peer))
                .panel(responsesPanel(peer))
                .panel(planPanel(peer))
                .build();
        return new Turret(peer, built, server);
    }

    /** The result types the research view attributes to their authors. */
    static final List<Class<?>> RESULT_TYPES = List.of(Research.FlightOptions.class,
            Research.HotelOptions.class, Research.CarOptions.class,
            Research.SightseeingOptions.class, Research.RestaurantOptions.class,
            Research.DietOptions.class, Research.FoodieOptions.class, Research.PhotoSet.class,
            Research.ReviewDigest.class, Research.AccessibilityGuide.class,
            Research.HospitalList.class, Research.NextDestinationIdeas.class);

    private static String authorOf(Object result) {
        try {
            return String.valueOf(result.getClass().getMethod("author").invoke(result));
        } catch (ReflectiveOperationException e) {
            return "unknown";
        }
    }

    /**
     * Starts serving.
     *
     * @param port the HTTP port, or 0 for any free port
     * @return the bound port
     * @throws IOException if the port cannot be bound
     */
    public int start(int port) throws IOException {
        return server.start(port);
    }

    /** The console view, for panels and tests. */
    public ConsoleView view() {
        return view;
    }

    @Override
    public void close() {
        server.close();
        view.close();
    }

    // -------------------------------------------------------------- commands

    /** Writes the travelers and the brief; the dispatcher does the rest. */
    static ConsoleCommand startTrip() {
        return new ConsoleCommand() {
            @Override
            public String id() {
                return "start-trip";
            }

            @Override
            public String title() {
                return "Start a trip";
            }

            @Override
            public String description() {
                return "Writes the travelers and the brief; the fleet researches, plans, and books.";
            }

            @Override
            public List<Field> fields() {
                return List.of(Field.text("tripId", "Trip id"), Field.text("from", "From"),
                        Field.text("to", "To"), Field.text("departureDate", "Departs (ISO)"),
                        Field.text("returnDate", "Returns (ISO)"),
                        Field.number("dailyBudget", "Daily budget (USD)"),
                        Field.text("brief", "Brief (comma-separated interests)"),
                        Field.text("travelers", "Travelers: name|about|need;need ..."));
            }

            @Override
            public Outcome execute(Map<String, String> args, CommandContext ctx) {
                String tripId = args.get("tripId");
                Travelers party = parseTravelers(tripId, args.getOrDefault("travelers", ""));
                ctx.dispatch(PartyBusFleet.TRIP, party, Duration.ofHours(12));
                String entryId = ctx.dispatch(PartyBusFleet.TRIP, new TravelBrief(tripId,
                        args.get("from"), args.get("to"), args.get("departureDate"),
                        args.get("returnDate"), Double.parseDouble(args.get("dailyBudget")),
                        args.get("brief"), args.getOrDefault("transport", "any")),
                        Duration.ofHours(12));
                ctx.audit("start-trip", tripId + " to " + args.get("to"));
                return Outcome.dispatched("trip " + tripId + " started", entryId);
            }
        };
    }

    /** Throws one event at the fleet, exactly as the simulator would. */
    static ConsoleCommand throwEvent() {
        return new ConsoleCommand() {
            @Override
            public String id() {
                return "throw-event";
            }

            @Override
            public String title() {
                return "Throw an event";
            }

            @Override
            public String description() {
                return "Storm, strike, protest, illness, or a lost passport; the fleet reacts.";
            }

            @Override
            public List<Field> fields() {
                return List.of(Field.text("tripId", "Trip id"), Field.text("date", "Date (ISO)"),
                        Field.select("kind", "Kind", Arrays.stream(EventKind.values())
                                .map(Enum::name).toList()),
                        Field.text("location", "Location"), Field.text("detail", "What happened"),
                        Field.number("severity", "Severity 1-5"));
            }

            @Override
            public Outcome execute(Map<String, String> args, CommandContext ctx) {
                String eventId = args.get("tripId") + "-op-" + System.currentTimeMillis();
                String entryId = ctx.dispatch(PartyBusFleet.TRIP, new TripEvent(args.get("tripId"),
                        args.get("date"), EventKind.valueOf(args.get("kind")),
                        args.get("location"), args.get("detail"),
                        (int) Double.parseDouble(args.getOrDefault("severity", "3")), eventId),
                        Duration.ofHours(12));
                ctx.audit("throw-event", args.get("kind") + " at " + args.get("location"));
                return Outcome.dispatched(args.get("kind") + " thrown", entryId);
            }
        };
    }

    /** Reroutes the party: the same travelers and brief, a new destination, a new trip id. */
    static ConsoleCommand reroute(Space trip) {
        return new ConsoleCommand() {
            @Override
            public String id() {
                return "reroute";
            }

            @Override
            public String title() {
                return "Reroute the party";
            }

            @Override
            public String description() {
                return "Copies a trip's travelers and brief to a new destination; the fleet researches it from scratch.";
            }

            @Override
            public List<Field> fields() {
                return List.of(Field.text("tripId", "Trip id"), Field.text("to", "New destination"),
                        Field.text("departureDate", "Departs (ISO)"),
                        Field.text("returnDate", "Returns (ISO)"));
            }

            @Override
            public Outcome execute(Map<String, String> args, CommandContext ctx) {
                String tripId = args.get("tripId");
                TravelBrief old = trip.read(Template.of(TravelBrief.class)
                        .where("tripId", eq(tripId))).orElse(null);
                Travelers party = trip.read(Template.of(Travelers.class)
                        .where("tripId", eq(tripId))).orElse(null);
                if (old == null || party == null) {
                    return Outcome.failed("no brief and travelers for trip " + tripId);
                }
                String newId = tripId + "-to-" + args.get("to").toLowerCase().replaceAll("\\W+", "");
                ctx.dispatch(PartyBusFleet.TRIP, new Travelers(newId, party.travelers()),
                        Duration.ofHours(12));
                String entryId = ctx.dispatch(PartyBusFleet.TRIP, new TravelBrief(newId, old.to(),
                        args.get("to"), args.getOrDefault("departureDate", old.departureDate()),
                        args.getOrDefault("returnDate", old.returnDate()), old.dailyBudget(),
                        old.brief(), old.transportPreference()), Duration.ofHours(12));
                ctx.audit("reroute", tripId + " -> " + args.get("to") + " as " + newId);
                return Outcome.dispatched("rerouted as " + newId, entryId);
            }
        };
    }

    static Travelers parseTravelers(String tripId, String spec) {
        List<Traveler> travelers = new ArrayList<>();
        for (String one : spec.split("\\s*,\\s*")) {
            if (one.isBlank()) {
                continue;
            }
            String[] parts = one.split("\\|", 3);
            List<String> needs = parts.length > 2 && !parts[2].isBlank()
                    ? List.of(parts[2].split(";")) : List.of();
            travelers.add(new Traveler(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "",
                    needs));
        }
        return new Travelers(tripId, travelers);
    }

    // ---------------------------------------------------------------- panels

    static ConsolePanel panel(String id, String title, java.util.function.Supplier<Object> data) {
        return new ConsolePanel() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String title() {
                return title;
            }

            @Override
            public Object data() {
                return data.get();
            }
        };
    }

    /** The itinerary: the winning activity per day, latest round first. */
    static ConsolePanel itineraryPanel(PartyBusFleet.Peer peer) {
        Space slots = peer.space(PartyBusFleet.SLOTS);
        return panel("itinerary", "Itinerary (auction winners)", () -> {
            Map<String, ScheduledActivity> byDay = new java.util.TreeMap<>();
            for (ScheduledActivity a : slots.readAll(Template.of(ScheduledActivity.class), 200)) {
                ScheduledActivity current = byDay.get(a.date());
                if (current == null || a.round() > current.round()) {
                    byDay.put(a.date(), a);
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ScheduledActivity a : byDay.values()) {
                rows.add(row("date", a.date(), "kind", a.kind(), "title", a.title(),
                        "plannedBy", a.plannedBy(), "bid", a.costUsd(), "round", a.round()));
            }
            return rows;
        });
    }

    /** The advisories the panel decided, with the ballot split. */
    static ConsolePanel safetyPanel(PartyBusFleet.Peer peer) {
        Space trip = peer.space(PartyBusFleet.TRIP);
        return panel("safety", "Travel advisories (signed quorum votes)", () -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (TravelAdvisory a : trip.readAll(Template.of(TravelAdvisory.class), 100)) {
                rows.add(row("location", a.location(), "wave", a.wave(), "verdict", a.verdict(),
                        "GO", a.goVotes(), "CAUTION", a.cautionVotes(), "AVOID", a.avoidVotes(),
                        "reasons", String.join("; ", a.reasons())));
            }
            return rows;
        });
    }

    /** Orders and their confirmations, with the clerk and the committed log index. */
    static ConsolePanel bookingsPanel(PartyBusFleet.Peer peer) {
        Space bookings = peer.space(PartyBusFleet.BOOKINGS);
        return panel("bookings", "Bookings (ordered log, exactly once)", () -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (BookingOrder o : bookings.readAll(Template.of(BookingOrder.class), 100)) {
                rows.add(row("orderId", o.orderId(), "what", o.what(), "amount", o.amountUsd(),
                        "status", "pending"));
            }
            for (BookingConfirmation c : bookings.readAll(Template.of(BookingConfirmation.class), 100)) {
                rows.add(row("orderId", c.orderId(), "confirmation", c.confirmation(), "amount",
                        c.amountUsd(), "clerk", c.clerk(), "logIndex", c.logIndex(),
                        "status", "confirmed"));
            }
            return rows;
        });
    }

    /** The party's energy per day: individual reports and the push-sum estimate. */
    static ConsolePanel energyPanel(PartyBusFleet.Peer peer) {
        Space trip = peer.space(PartyBusFleet.TRIP);
        return panel("energy", "Party energy (push-sum aggregate)", () -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (TripDay day : trip.readAll(Template.of(TripDay.class), 60)) {
                OptionalDouble estimate = peer.aggregate().estimate(
                        Itinerary.energyEpoch(day.tripId(), day.date()));
                List<EnergyReport> reports = trip.readAll(Template.of(EnergyReport.class)
                        .where("tripId", eq(day.tripId())).where("date", eq(day.date())), 20);
                StringBuilder who = new StringBuilder();
                for (EnergyReport r : reports) {
                    who.append(r.traveler()).append('=').append(r.energy()).append(' ');
                }
                rows.add(row("date", day.date(), "pace", day.pace(), "estimate",
                        estimate.isPresent() ? Math.round(estimate.getAsDouble()) : "pending",
                        "reports", who.toString().trim()));
            }
            return rows;
        });
    }

    /** What the fleet did about each event. */
    static ConsolePanel responsesPanel(PartyBusFleet.Peer peer) {
        Space trip = peer.space(PartyBusFleet.TRIP);
        return panel("responses", "Events and the fleet's responses", () -> {
            Map<String, TripEvent> events = new LinkedHashMap<>();
            for (TripEvent e : trip.readAll(Template.of(TripEvent.class), 100)) {
                events.put(e.eventId(), e);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (TripEvent e : events.values()) {
                StringBuilder actions = new StringBuilder();
                for (EventResponse r : trip.readAll(Template.of(EventResponse.class)
                        .where("eventId", eq(e.eventId())), 20)) {
                    actions.append(r.agent()).append(": ").append(r.action()).append(". ");
                }
                rows.add(row("date", e.date(), "kind", e.kind().name(), "detail", e.detail(),
                        "severity", e.severity(), "response", actions.toString().trim()));
            }
            return rows;
        });
    }

    /** The finished plan and the party's decision, when they exist. */
    static ConsolePanel planPanel(PartyBusFleet.Peer peer) {
        Space trip = peer.space(PartyBusFleet.TRIP);
        return panel("plan", "Travel plan and party decisions", () -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (TravelPlan p : trip.readAll(Template.of(TravelPlan.class), 10)) {
                rows.add(row("tripId", p.tripId(), "title", p.title(), "advisory", p.advisory(),
                        "stays", p.stays().size(), "map", p.mapUrl()));
            }
            for (PartyDecision d : trip.readAll(Template.of(PartyDecision.class), 10)) {
                rows.add(row("tripId", d.tripId(), "decision", d.proposalId(), "winner",
                        d.winner(), "tally", String.join(" ", d.tally())));
            }
            return rows;
        });
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            row.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return row;
    }
}
