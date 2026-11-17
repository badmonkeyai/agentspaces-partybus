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

import ai.badmonkey.agentspaces.agent.remote.RemoteAction;
import ai.badmonkey.agentspaces.agent.remote.RemoteActions;
import ai.badmonkey.agentspaces.api.ad.CapabilityAdvertisement;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.embabel.EmbabelRemoteActions;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.BookingConfirmation;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.BookingOrder;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.PartyDecision;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.DayPlan;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.ScheduledActivity;
import ai.badmonkey.agentspaces.partybus.domain.Research.HospitalList;
import ai.badmonkey.agentspaces.partybus.domain.Research.ReviewDigest;
import ai.badmonkey.agentspaces.partybus.domain.Safety;
import ai.badmonkey.agentspaces.partybus.domain.Safety.TravelAdvisory;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.EventKind;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.EventResponse;
import ai.badmonkey.agentspaces.partybus.domain.Trip.TravelBrief;
import ai.badmonkey.agentspaces.partybus.domain.Trip.TravelPlan;
import ai.badmonkey.agentspaces.partybus.fleet.PartyBusFleet;
import ai.badmonkey.agentspaces.partybus.fleet.PartyBusFleet.Peer;
import ai.badmonkey.agentspaces.partybus.fleet.PartyBusPlanner;
import java.time.Instant;
import ai.badmonkey.agentspaces.partybus.fleet.PartyBusPlanner.Step;
import ai.badmonkey.agentspaces.partybus.fleet.VacationSimulator;
import ai.badmonkey.agentspaces.partybus.sources.RecordedSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole bus over real TCP, on the recorded trip: the research bag drains
 * across twelve specialists, the safety panel votes GO, every day is
 * auctioned, the plan is assembled and its stays booked exactly once, the
 * planner reaches a goal through two remote peers, and the simulated week's
 * storm, illness, protest, and strike each get the reaction they deserve.
 */
class PartyBusFleetTest {

    private static final String TRIP = "lisbon-test";

    /**
     * QA3 A3-4 and QA4 A4-3: every capability a peer offers is registered on its
     * capability runtime, so the fleet finds it through discovery (SPEC §8's
     * find-the-provider pattern) rather than by being told. A clerk finds the
     * Raft leader from another clerk's {@code aspace:cap/ordered-log}
     * advertisement, and the lease it reads there is derived from the cadence
     * the log is really driven at: the peer tick, {@code PartyBusFleet.CLERK_TICK}.
     */
    @Test
    @Timeout(180)
    void theFleetDiscoversEveryCapabilityAndTheLeaderLeaseMatchesTheRealCadence()
            throws Exception {
        int base = TestPorts.freeBlock(4);
        List<Peer> clerkPeers = new ArrayList<>();
        try {
            clerkPeers.add(PartyBusFleet.startPeer("clerk-1", base, 0));
            clerkPeers.add(PartyBusFleet.startPeer("clerk-2", base + 1, base));
            clerkPeers.add(PartyBusFleet.startPeer("clerk-3", base + 2, base));

            List<PartyBusFleet.Clerk> clerks = PartyBusFleet.formBookingQuorum(clerkPeers);
            assertThat(clerks).hasSize(3);

            PartyBusFleet.Clerk leader = Waits.await("partybus.desk.leader", () -> clerks.stream()
                    .filter(c -> c.raft().isLeader()).findFirst(), Duration.ofSeconds(60))
                    .orElseThrow(() -> new AssertionError("the quorum elects no leader"));
            Peer observer = clerkPeers.stream()
                    .filter(p -> !p.peerId().equals(leader.peer().peerId())).findFirst().orElseThrow();

            CapabilityAdvertisement ad = Waits.await("partybus.leader.advertised", () -> observer.capabilities()
                    .providersOf("aspace:cap/ordered-log").stream()
                    .filter(c -> c.issuer().equals(leader.peer().peerId()))
                    .filter(c -> "leader".equals(c.parameters().get("role")))
                    .findFirst(), Duration.ofSeconds(30))
                    .orElseThrow(() -> new AssertionError("no leader advertisement discovered"));
            Instant leaseUntil = Instant.parse(ad.parameters().get("leaseUntil"));
            // The election timeout is 5..9 ticks; at the 300 ms peer tick that is
            // 1.5..2.7 s. Under the old 1 s default it read 5..9 s.
            assertThat(leaseUntil)
                    .isAfterOrEqualTo(ad.issued().plusMillis(1500))
                    .isBeforeOrEqualTo(ad.issued().plusMillis(2700));

            // The aggregate and both vote spaces are advertised too, each vote
            // space on its own advertisement.
            assertThat(Waits.await("partybus.aggregate.advertised", () -> observer.capabilities().providersOf("aspace:cap/aggregate")
                    .stream().filter(c -> c.issuer().equals(leader.peer().peerId())).findFirst(),
                    Duration.ofSeconds(30))).isPresent();
            assertThat(Waits.await("partybus.vote.advertised", () -> {
                Set<String> bindings = observer.capabilities().providersOf("aspace:cap/vote")
                        .stream().filter(c -> c.issuer().equals(leader.peer().peerId()))
                        .map(CapabilityAdvertisement::binding).collect(Collectors.toSet());
                return bindings.size() == 2 ? Optional.of(bindings) : Optional.<Set<String>>empty();
            }, Duration.ofSeconds(30))).hasValue(Set.of("space:" + PartyBusFleet.ADVISORIES,
                    "space:" + PartyBusFleet.DECISIONS));
        } finally {
            clerkPeers.forEach(Peer::close);
        }
    }

    /**
     * QA4 A4-5, open: the ordered-log booking step confirms each order exactly
     * once and then never drains it. The clerks write one BookingConfirmation per
     * BookingOrder, as asserted just above, but the BookingOrder entries are still
     * readable at another replica two minutes later — measured, not guessed:
     * {@code ordersLeft=2 confirmations=2 expected=2} after a 120 s window. So a
     * completion taken through {@code OrderedTakes.adoptClaim} is not tombstoning
     * the entry for other replicas the way a LEASE_RACE completion does.
     *
     * <p>This was invisible until 2026-09-24: the safety quorum stalled first
     * (QA4 A4-4) and the test never reached this line. Fixed the same day in the
     * library (the ordered log now installs the holder's own attested claim, so
     * every replica can authenticate the completion); this test is the flagship
     * proof and {@code SafetyQuorumTest} the four-second one for the front half.
     */
    @Test
    @Timeout(420)
    void theBusResearchesPlansBooksAndSurvivesTheWeek() throws Exception {
        RecordedSources sources = RecordedSources.lisbon();
        PartyBus.Seating bus = TestPorts.board(sources);
        try {
            Peer turret = bus.turretPeer();
            Space trip = turret.space(PartyBusFleet.TRIP);
            Space research = turret.space(PartyBusFleet.RESEARCH);
            Waits.formed(turret, bus.peers());

            trip.write(PartyBus.party(TRIP), Lease.of(Duration.ofHours(1)));
            TravelBrief brief = PartyBus.brief(TRIP);
            trip.write(brief, Lease.of(Duration.ofHours(1)));

            // 1. The bag drains: every specialist answered its request type.
            for (Class<?> result : PartyBusPlanner.RESULT_TYPES) {
                assertThat(Waits.await("partybus.research.answered", () -> research.read(Template.of(result)
                        .where("tripId", eq(TRIP))), Duration.ofSeconds(90)))
                        .as(result.getSimpleName()).isPresent();
            }
            assertThat(sources.lookups("flights")).isGreaterThanOrEqualTo(1);

            // 2. The safety panel voted the baseline advisory with a full quorum.
            TravelAdvisory baseline = Waits.await("partybus.advisory.baseline", () -> advisory(trip, Safety.BASELINE),
                    Duration.ofSeconds(60)).orElseThrow();
            assertThat(baseline.verdict()).isEqualTo("GO");
            assertThat(baseline.goVotes() + baseline.cautionVotes() + baseline.avoidVotes())
                    .isEqualTo(3);

            // 3. Every day was auctioned to a planner, more than one planner won, and each day's
            // plan was committed through the booking desk's ordered log.
            Space bookings = turret.space(PartyBusFleet.BOOKINGS);
            Map<String, DayPlan> committed = Waits.await("partybus.dayplan.committed", () -> {
                Map<String, DayPlan> days = DayPlan.ofTrip(bookings, TRIP);
                return days.size() >= brief.days().size() ? Optional.of(days) : Optional.<Map<String, DayPlan>>empty();
            }, Duration.ofSeconds(90)).orElseThrow();
            assertThat(committed.values().stream().map(d -> d.plan().plannedBy()).collect(Collectors.toSet()))
                    .hasSizeGreaterThanOrEqualTo(2);
            assertThat(committed.values()).allSatisfy(d -> assertThat(d.plan().costUsd()).isLessThan(150));

            // 4. The join fired, the stays were found, the advisory rode along.
            TravelPlan plan = Waits.await("partybus.plan.assembled", () -> trip.read(Template.of(TravelPlan.class)
                    .where("tripId", eq(TRIP))), Duration.ofSeconds(60)).orElseThrow();
            assertThat(plan.title()).isNotBlank();
            assertThat(plan.stays()).isNotEmpty();
            assertThat(plan.advisory()).isEqualTo("GO");
            assertThat(plan.mapUrl()).startsWith("https://www.google.com/maps/");

            // 5. Each booking order was confirmed by exactly one clerk, through the log.
            int orders = plan.stays().size();
            List<BookingConfirmation> confirmations = Waits.await("partybus.booking.confirmed", () -> {
                List<BookingConfirmation> all = bookings.readAll(
                        Template.of(BookingConfirmation.class), 50);
                return all.size() >= orders ? Optional.of(all) : Optional.<List<BookingConfirmation>>empty();
            }, Duration.ofSeconds(90)).orElseThrow();
            Map<String, Long> perOrder = confirmations.stream()
                    .collect(Collectors.groupingBy(BookingConfirmation::orderId, Collectors.counting()));
            assertThat(perOrder.values()).allSatisfy(n -> assertThat(n).isEqualTo(1L));
            // The completions replicate as their own deltas; the orders drain within moments.
            assertThat(Waits.await("partybus.booking.drained", () -> bookings.readAll(Template.of(BookingOrder.class), 50).isEmpty()
                    ? Optional.of(Boolean.TRUE) : Optional.<Boolean>empty(), Duration.ofSeconds(30)))
                    .isPresent();

            // 6. The planner: unreachable alone, reachable and executed through the fleet.
            // The bridge snapshots the fleet's cards when it is built, so wait for the
            // two actions this plan needs, not merely for a dozen cards: with the front
            // half of the trip now settling in seconds, "twelve of thirty" no longer
            // implies the sightseeing scout's and review reader's cards are among them.
            RemoteActions remote = PartyBusPlanner.remoteActions(bus.plannerPeer());
            assertThat(PartyBusPlanner.awaitActions(remote, 12, Duration.ofSeconds(30)))
                    .isGreaterThanOrEqualTo(12);
            List<String> needed = List.of("sightseeing-scout_SightseeingRequest",
                    "review-reader_ReviewRequest");
            assertThat(Waits.await("partybus.planner.actions", () -> {
                Set<String> names = remote.available().stream().map(RemoteAction::name)
                        .collect(Collectors.toSet());
                return names.containsAll(needed) ? Optional.of(names) : Optional.<Set<String>>empty();
            }, Duration.ofSeconds(30)))
                    .as("the scout and reader actions the plan needs are advertised; seen: "
                            + remote.available().stream().map(RemoteAction::name).sorted().toList())
                    .isPresent();
            List<Step> local = PartyBusPlanner.localSteps(PartyBus.party(TRIP));
            assertThat(PartyBusPlanner.plan(TravelBrief.class, ReviewDigest.class, local)).isEmpty();
            EmbabelRemoteActions bridge = PartyBusPlanner.bridge(remote);
            List<Step> withFleet = new ArrayList<>(local);
            withFleet.addAll(PartyBusPlanner.fleetSteps(bridge.agentInstance().orElseThrow()));
            List<Step> found = PartyBusPlanner.plan(TravelBrief.class, ReviewDigest.class, withFleet)
                    .orElseThrow();
            assertThat(found).extracting(Step::name).containsExactly("askForSights",
                    "sightseeing_scout_SightseeingRequest", "askForReviewsOfTheTopSight",
                    "review_reader_ReviewRequest");
            ReviewDigest digest = (ReviewDigest) PartyBusPlanner.execute(found,
                    new TravelBrief(TRIP + "-plan", brief.from(), brief.to(), brief.departureDate(),
                            brief.returnDate(), brief.dailyBudget(), brief.brief(),
                            brief.transportPreference()));
            assertThat(digest.author()).isEqualTo("review-reader");
            assertThat(digest.averageRating()).isEqualTo(4.6);

            // 7. The week: the simulator throws the script and the fleet reacts.
            Peer sim = bus.simulatorPeer();
            VacationSimulator simulator = new VacationSimulator(sim.space(PartyBusFleet.TRIP),
                    sim.space(PartyBusFleet.SLOTS), sim.space(PartyBusFleet.BOOKINGS), sim.aggregate(),
                    Duration.ofSeconds(4),
                    day -> { });
            List<VacationSimulator.Narration> week = simulator.live(brief,
                    VacationSimulator.lisbonWeek());
            assertThat(week).hasSize(brief.days().size());

            // The illness sent the hospital locator to the map.
            assertThat(Waits.await("partybus.week.hospital", () -> research.readAll(Template.of(HospitalList.class)
                            .where("tripId", eq(TRIP)), 10).stream()
                    .filter(h -> h.topic().contains("illness")).findFirst(), Duration.ofSeconds(30)))
                    .isPresent();
            // The protest re-ran the sweep and the panel downgraded the advisory.
            TravelAdvisory protest = Waits.await("partybus.advisory.protest", () -> advisory(trip, TRIP + "-d3-protest"),
                    Duration.ofSeconds(60)).orElseThrow();
            assertThat(protest.verdict()).isEqualTo("CAUTION");
            assertThat(protest.avoidVotes()).isEqualTo(1);
            // Every non-calm event got a response on the record.
            Set<String> responded = Waits.await("partybus.week.responses", () -> {
                Set<String> ids = trip.readAll(Template.of(EventResponse.class)
                                .where("tripId", eq(TRIP)), 50).stream()
                        .map(EventResponse::eventId).collect(Collectors.toSet());
                return ids.size() >= 5 ? Optional.of(ids) : Optional.<Set<String>>empty();
            }, Duration.ofSeconds(30)).orElseThrow();
            assertThat(responded).anyMatch(id -> id.endsWith(EventKind.STRIKE.name().toLowerCase()));
            // The storm day changed hands to an indoor planner; the party's energy bought a rest day.
            // The photo walk waited for the curator's set: no walk was planned with an empty shot list. Every plan
            // made is in its day's record, including one a re-auction replaced.
            assertThat(DayPlan.ofTrip(bookings, TRIP).values().stream().flatMap(d -> d.considered().stream())
                    .filter(a -> a.kind().equals("PHOTO_WALK")).toList())
                    .isNotEmpty().noneMatch(a -> a.title().contains(" 0-shot"));
            assertThat(Waits.await("partybus.storm.replanned", () -> DayPlan.current(bookings, TRIP, brief.days().get(1))
                    .filter(d -> d.plan().round() >= 1), Duration.ofSeconds(30)))
                    .hasValueSatisfying(d -> assertThat(d.plan().kind()).isEqualTo("DINING"));
            assertThat(Waits.await("partybus.rest.planned", () -> DayPlan.ofTrip(bookings, TRIP).values().stream()
                    .filter(d -> d.plan().kind().equals("REST")).findFirst(), Duration.ofSeconds(30)))
                    .isPresent();
            // One plan per day (owner rule 3: a shared schedule needs strong coordination). The ordered
            // fold leaves no proposal unfolded, one accumulator per day once each fold has retired its
            // predecessor, and the same plans at every replica. Liveness ceilings are 30 s; the time
            // each took is printed.
            long settleStart = System.nanoTime();
            assertThat(Waits.await("partybus.dayplan.folded", () -> bookings.readAll(Template.of(ScheduledActivity.class)
                    .where("tripId", eq(TRIP)), 50).isEmpty() ? Optional.of(true) : Optional.<Boolean>empty(),
                    Duration.ofSeconds(30))).as("every proposal folded into its day").isPresent();
            assertThat(Waits.await("partybus.dayplan.one-per-day", () -> {
                Map<String, Long> perDay = bookings.readAllEntries(DayPlan.template(TRIP), 200).stream()
                        .collect(Collectors.groupingBy(e -> e.value().date(), Collectors.counting()));
                return perDay.size() == brief.days().size() && perDay.values().stream().allMatch(n -> n == 1)
                        ? Optional.of(perDay) : Optional.<Map<String, Long>>empty();
            }, Duration.ofSeconds(30))).as("one day plan per day").isPresent();
            Map<String, DayPlan> atTurret = DayPlan.ofTrip(bookings, TRIP);
            for (Peer peer : bus.peers()) {
                assertThat(Waits.await("partybus.dayplan.replicated", () -> DayPlan.ofTrip(peer.space(PartyBusFleet.BOOKINGS), TRIP).equals(atTurret)
                        ? Optional.of(true) : Optional.<Boolean>empty(), Duration.ofSeconds(30)))
                        .as("the day plans at " + peer.name()).isPresent();
            }
            long settledMillis = (System.nanoTime() - settleStart) / 1_000_000;
            int secondPlans = atTurret.values().stream().mapToInt(DayPlan::duplicates).sum();
            long counted = bus.peers().stream().mapToLong(p -> p.space(PartyBusFleet.SLOTS).duplicateCompletions())
                    .sum();
            System.out.println("day plans: " + atTurret.values().stream().map(d -> d.date() + "=" + d.plan().kind()
                    + "/r" + d.plan().round() + "/p" + d.proposals() + "/d" + d.duplicates() + "/s" + d.stale())
                    .toList() + "; second plans for a round: " + secondPlans
                    + "; duplicate slot completions counted: " + counted + "; settled in " + settledMillis + " ms");
            // The travelers voted on where to go next.
            PartyDecision next = Waits.await("partybus.vote.decided", () -> trip.read(Template.of(PartyDecision.class)
                    .where("tripId", eq(TRIP))), Duration.ofSeconds(60)).orElseThrow();
            assertThat(next.winner()).isNotBlank();
            assertThat(next.tally().stream().mapToInt(t -> Integer.parseInt(t.split("=")[1])).sum())
                    .isEqualTo(3);
            // A week later (well past the 30 s take lease) the bookings are still exactly once:
            // the holder's completion authenticated on every clerk's replica, so no clerk
            // ever saw the order reappear.
            Map<String, Long> stillOnce = bookings.readAll(Template.of(BookingConfirmation.class), 50)
                    .stream().collect(Collectors.groupingBy(BookingConfirmation::orderId,
                            Collectors.counting()));
            assertThat(stillOnce).hasSize(orders);
            assertThat(stillOnce.values()).allSatisfy(n -> assertThat(n).isEqualTo(1L));
        } finally {
            for (Peer peer : bus.peers()) {
                peer.close();
            }
        }
    }

    private static Optional<TravelAdvisory> advisory(Space trip, String wave) {
        return trip.readAll(Template.of(TravelAdvisory.class).where("tripId", eq(TRIP))
                .where("wave", eq(wave)), 10).stream().findFirst();
    }

}
