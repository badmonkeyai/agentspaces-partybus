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
package ai.badmonkey.agentspaces.partybus;

import ai.badmonkey.agentspaces.agent.remote.RemoteActions;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.embabel.EmbabelRemoteActions;
import ai.badmonkey.agentspaces.partybus.agents.Advisors;
import ai.badmonkey.agentspaces.partybus.agents.Assemblers;
import ai.badmonkey.agentspaces.partybus.agents.Dispatcher;
import ai.badmonkey.agentspaces.partybus.agents.Party;
import ai.badmonkey.agentspaces.partybus.agents.Planners;
import ai.badmonkey.agentspaces.partybus.agents.SafetyPanel;
import ai.badmonkey.agentspaces.partybus.agents.Scouts;
import ai.badmonkey.agentspaces.partybus.agents.Watchers;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.BookingConfirmation;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.PartyDecision;
import ai.badmonkey.agentspaces.partybus.domain.Research.ReviewDigest;
import ai.badmonkey.agentspaces.partybus.domain.Safety.TravelAdvisory;
import ai.badmonkey.agentspaces.partybus.domain.Trip.Traveler;
import ai.badmonkey.agentspaces.partybus.domain.Trip.TravelBrief;
import ai.badmonkey.agentspaces.partybus.domain.Trip.TravelPlan;
import ai.badmonkey.agentspaces.partybus.domain.Trip.Travelers;
import ai.badmonkey.agentspaces.partybus.fleet.PartyBusFleet;
import ai.badmonkey.agentspaces.partybus.fleet.PartyBusFleet.Peer;
import ai.badmonkey.agentspaces.partybus.fleet.PartyBusPlanner;
import ai.badmonkey.agentspaces.partybus.fleet.Turret;
import ai.badmonkey.agentspaces.partybus.fleet.VacationSimulator;
import ai.badmonkey.agentspaces.partybus.sources.Sources;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * The whole bus, on one machine, fifteen peers deep: thirty-four agents
 * seated a few to a peer, the turret on port 7590, and the simulator driving a
 * week in Lisbon that refuses to go to plan.
 *
 * <p>Run it with {@code mvn -q exec:java}. A live run needs the keys Tripper
 * needs ({@code OPENAI_API_KEY}, {@code BRAVE_API_KEY}, optionally
 * {@code YOUTUBE_API_KEY}); {@code PARTYBUS_SOURCES=recorded} replays the
 * recorded trip instead. Open {@code http://localhost:7590/} while it runs.
 */
public final class PartyBus {

    /** The demo party: three friends who agree on nothing except Lisbon. */
    public static Travelers party(String tripId) {
        return new Travelers(tripId, List.of(
                new Traveler("Marco", "eats for a living; food writer; wine; will try anything once",
                        List.of("shellfish allergy, ignores it")),
                new Traveler("Priya", "architecture historian; museums; azulejos; long walks; photography",
                        List.of("vegetarian")),
                new Traveler("Dee", "just wants a pool and a rooftop; travels with a folding cane on hills",
                        List.of("limited mobility on steep hills", "step-free lodging"))));
    }

    /** The demo brief: Boston to Lisbon, a week, two hundred dollars a day. */
    public static TravelBrief brief(String tripId) {
        return new TravelBrief(tripId, "Boston, USA", "Lisbon, Portugal", "2026-10-03",
                "2026-10-09", 200.0,
                "food and wine, architecture and history, photography, one lazy pool day, "
                        + "no steep hills for Dee", "fly, then trams and feet");
    }

    /** The full seating plan: one agent per peer, in boarding order. */
    public record Seating(List<Peer> peers, Peer turretPeer, Peer plannerPeer, Peer simulatorPeer) {
    }

    /**
     * Boards the bus: starts every peer, binds every agent, forms the booking
     * quorum, and starts the turret.
     *
     * @param sources     the specialists' sources
     * @param basePort    the first TCP port; peers take consecutive ports
     * @param consolePort the turret's HTTP port (0 for any)
     * @return the seating
     * @throws Exception on startup failure
     */
    public static Seating board(Sources sources, int basePort, int consolePort) throws Exception {
        List<Peer> peers = new ArrayList<>();
        int[] port = {basePort};
        Function<String, Peer> seat = name -> {
            try {
                Peer peer = PartyBusFleet.startPeer(name, port[0]++,
                        peers.isEmpty() ? 0 : basePort);
                peers.add(peer);
                return peer;
            } catch (Exception e) {
                throw new IllegalStateException("cannot seat " + name, e);
            }
        };

        Peer turretPeer = seat.apply("turret");

        // The concierge desk: the agents that watch the whole trip rather than one task.
        Peer concierge = seat.apply("concierge");
        concierge.bind(new Dispatcher());
        concierge.bind(new Assemblers.StayFinder());
        concierge.run(Assemblers.runPlanAssembler(concierge.space(PartyBusFleet.RESEARCH),
                concierge.space(PartyBusFleet.TRIP), sources));
        concierge.bind(new SafetyPanel.Lead(concierge.vote(PartyBusFleet.ADVISORIES), "safety-lead"));

        // The scouts and advisors: the research bag's workers, four to a peer.
        Peer scoutsA = seat.apply("scouts-a");
        scoutsA.bind(new Scouts.FlightScout(sources));
        scoutsA.bind(new Scouts.HotelScout(sources));
        scoutsA.bind(new Scouts.CarScout(sources));
        scoutsA.bind(new Scouts.SightseeingScout(sources));
        Peer scoutsB = seat.apply("scouts-b");
        scoutsB.bind(new Scouts.RestaurantScout(sources));
        scoutsB.bind(new Scouts.PhotoCurator(sources));
        scoutsB.bind(new Scouts.ReviewReader(sources));
        scoutsB.bind(new Scouts.NextDestinationAdvisor(sources));
        Peer advisors = seat.apply("advisors");
        advisors.bind(new Advisors.DietAdvisor(sources));
        advisors.bind(new Advisors.FoodieAdvisor(sources));
        advisors.bind(new Advisors.AccessibilityAdvisor(sources));
        advisors.bind(new Advisors.HospitalLocator(sources));

        // The safety pipeline: watchers, the analyst, and the three-seat panel.
        Peer watchers = seat.apply("watchers");
        watchers.bind(new Watchers.NewsWatcher(sources));
        watchers.bind(new Watchers.VideoWatcher(sources));
        watchers.bind(new Watchers.SocialWatcher(sources));
        watchers.bind(new Watchers.SentimentAnalyst(sources));
        // One peer per panelist: a ballot's voter is the peer's own agent, so
        // three seats sharing a peer would be one voter and the three-seat quorum
        // could never close (QA4 A4-4).
        for (String seatName : SafetyPanel.SEATS) {
            Peer panelist = seat.apply("panelist-" + seatName);
            panelist.bind(new SafetyPanel.Panelist(seatName));
        }

        // The activity auction: one bid function per peer, so one planner per peer.
        seat.apply("dining-planner").bind(new Planners.DiningPlanner());
        seat.apply("sights-planner").bind(new Planners.SightsPlanner());
        seat.apply("photo-walk-planner").bind(new Planners.PhotoWalkPlanner());
        seat.apply("rest-planner").bind(new Planners.RestPlanner());

        // The party itself: one peer per traveler, so each carries its own ballot
        // and the party quorum is a quorum of members (QA4 A4-4).
        Travelers party = party("lisbon-2026");
        int[] stamina = {85, 70, 55};
        for (int i = 0; i < party.travelers().size(); i++) {
            Traveler traveler = party.travelers().get(i);
            Peer seatOf = seat.apply("traveler-" + traveler.name().toLowerCase());
            seatOf.bind(new Party.TravelerAgent(traveler, stamina[i], seatOf.aggregate()));
        }
        Peer bus = seat.apply("the-bus");
        bus.bind(new Party.Lead(bus.vote(PartyBusFleet.DECISIONS), party.travelers().size()));

        // The booking desk: three clerks, one Raft quorum.
        List<Peer> clerks = List.of(seat.apply("clerk-a"), seat.apply("clerk-b"),
                seat.apply("clerk-c"));
        PartyBusFleet.formBookingQuorum(clerks);

        // The planner is where Tripper's own agent would run; the simulator rides with the turret.
        Peer plannerPeer = seat.apply("planner");
        Peer simulatorPeer = turretPeer;

        Turret turret = Turret.over(turretPeer);
        turretPeer.run(turret);
        int bound = turret.start(consolePort);
        System.out.println("turret: http://localhost:" + bound + "/  (operator token: "
                + Turret.OPERATOR_TOKEN + ")");
        return new Seating(peers, turretPeer, plannerPeer, simulatorPeer);
    }

    /**
     * Runs the demo.
     *
     * @param args unused
     * @throws Exception on failure
     */
    public static void main(String[] args) throws Exception {
        Sources sources = Sources.fromEnvironment();
        System.out.println("partybus: " + sources.name() + " sources; boarding the bus\n");
        Seating bus = board(sources, 7700, 7590);
        try {
            Thread.sleep(3000); // let membership and the cards settle
            String tripId = "lisbon-2026";
            Peer turretPeer = bus.turretPeer();
            turretPeer.space(PartyBusFleet.TRIP).write(party(tripId), Lease.of(Duration.ofHours(12)));
            turretPeer.space(PartyBusFleet.TRIP).write(brief(tripId), Lease.of(Duration.ofHours(12)));
            System.out.println("brief written; the dispatcher fans it out to the fleet\n");

            // The planner: a goal it cannot reach alone, reached through two peers.
            RemoteActions remote = PartyBusPlanner.remoteActions(bus.plannerPeer());
            PartyBusPlanner.awaitActions(remote, 12, Duration.ofSeconds(30));
            EmbabelRemoteActions bridge = PartyBusPlanner.bridge(remote);
            List<PartyBusPlanner.Step> local = PartyBusPlanner.localSteps(party(tripId));
            System.out.println("planner alone reaches ReviewDigest: "
                    + PartyBusPlanner.plan(TravelBrief.class, ReviewDigest.class, local).isPresent());
            List<PartyBusPlanner.Step> withFleet = new ArrayList<>(local);
            Optional<Object> fleetAgent = bridge.agentInstance();
            if (fleetAgent.isPresent()) {
                System.out.println("Embabel is on the classpath: planning over the generated @Agent");
                withFleet.addAll(PartyBusPlanner.fleetSteps(fleetAgent.get()));
            } else {
                System.out.println("no Embabel on the classpath (-Pembabel adds it): planning over "
                        + remote.available().size() + " remote actions directly");
                withFleet.addAll(PartyBusPlanner.remoteSteps(remote));
            }
            Optional<List<PartyBusPlanner.Step>> plan = PartyBusPlanner.plan(TravelBrief.class,
                    ReviewDigest.class, withFleet);
            System.out.println("planner with the fleet: " + plan.map(steps -> steps.stream()
                    .map(PartyBusPlanner.Step::name).toList()).orElse(List.of()));
            plan.ifPresent(steps -> {
                ReviewDigest digest = (ReviewDigest) PartyBusPlanner.execute(steps, brief(tripId));
                System.out.println("  -> reviews of the top sight: " + digest.averageRating()
                        + "/5, praise: " + digest.praise() + "\n");
            });

            // The simulator: a week, narrated.
            Peer sim = bus.simulatorPeer();
            VacationSimulator simulator = new VacationSimulator(sim.space(PartyBusFleet.TRIP),
                    sim.space(PartyBusFleet.SLOTS), sim.aggregate(), Duration.ofSeconds(8),
                    PartyBus::narrate);
            simulator.live(brief(tripId), VacationSimulator.lisbonWeek());

            Thread.sleep(4000);
            summarize(turretPeer, tripId);
            System.out.println("\nthe turret stays up; Ctrl-C to park the bus");
            Thread.currentThread().join();
        } finally {
            for (Peer peer : bus.peers()) {
                peer.close();
            }
        }
    }

    static void narrate(VacationSimulator.Narration day) {
        StringBuilder line = new StringBuilder(day.date()).append("  pace ").append(day.pace());
        day.energy().ifPresent(e -> line.append("  energy ").append(Math.round(e)));
        if (day.event() != null) {
            line.append("\n   !! ").append(day.event().kind()).append(": ")
                    .append(day.event().detail());
        }
        day.schedule().stream().max((a, b) -> Integer.compare(a.round(), b.round()))
                .ifPresent(a -> line.append("\n   -> ").append(a.kind()).append(" by ")
                        .append(a.plannedBy()).append(": ").append(a.title()));
        System.out.println(line);
    }

    static void summarize(Peer peer, String tripId) {
        System.out.println("\n== the week, as the space remembers it ==");
        for (TravelAdvisory a : peer.space(PartyBusFleet.TRIP).readAll(
                Template.of(TravelAdvisory.class).where("tripId", eq(tripId)), 20)) {
            System.out.println("advisory [" + a.wave() + "] " + a.verdict() + "  GO=" + a.goVotes()
                    + " CAUTION=" + a.cautionVotes() + " AVOID=" + a.avoidVotes());
        }
        for (BookingConfirmation c : peer.space(PartyBusFleet.BOOKINGS).readAll(
                Template.of(BookingConfirmation.class), 20)) {
            System.out.println("booked " + c.orderId() + " " + c.confirmation() + " by " + c.clerk()
                    + " at log index " + c.logIndex());
        }
        peer.space(PartyBusFleet.TRIP).read(Template.of(PartyDecision.class)
                .where("tripId", eq(tripId))).ifPresent(d -> System.out.println("next time: "
                + d.winner() + "  " + d.tally()));
        peer.space(PartyBusFleet.TRIP).read(Template.of(TravelPlan.class)
                .where("tripId", eq(tripId))).ifPresent(p -> System.out.println("plan: \""
                + p.title() + "\"  advisory " + p.advisory() + "  " + p.stays().size()
                + " stays  " + p.mapUrl()));
    }
}
