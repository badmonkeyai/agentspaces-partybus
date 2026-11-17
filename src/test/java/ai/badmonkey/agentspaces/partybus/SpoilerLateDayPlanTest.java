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

import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.measure.Measure;
import ai.badmonkey.agentspaces.measure.Measurement;
import ai.badmonkey.agentspaces.measure.Params;
import ai.badmonkey.agentspaces.measure.Wait;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.DayPlan;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.ScheduledActivity;
import ai.badmonkey.agentspaces.partybus.fleet.PartyBusFleet;
import ai.badmonkey.agentspaces.partybus.fleet.PartyBusFleet.Peer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The late claim after the settle window, at the day plan (srad/spoiler-peers.md SC-A1, the Party Bus form).
 *
 * <p>The bus auctions each day among the planners in an {@code AUCTION} space. One planner per day is promised
 * only while every claim reaches every rival planner within the settle window (SPEC §7.4), and the bus measured
 * claims 0.3 to 2.4 s late, so two planners can each win a round of a day and each plan it. The fix of
 * 2026-11-15 made the day's plan one plan whatever the auction does: every proposal is folded into one
 * {@link DayPlan} per day by the itinerary keepers' {@code ORDERED} reduce through the booking desk's log,
 * keeping the auction's rule (the latest round, then the lowest bid, then the name) and counting a second
 * plan for a round instead of showing it.
 *
 * <p>Here a scripted planner, a full member of the bus with its own key, plays the planner whose rival's claim
 * reached it after the window: once the day's plan is committed at every replica, it writes the plan it made
 * for the same round, first with the lower bid (the auction's true winner, which won late), then with a higher
 * one. After a re-auction to the next round is committed, it writes a plan for the round the re-auction
 * replaced: a late slot. Each step fires on what the honest members show, never on a sleep, and each is judged
 * at every replica: one accumulator for the day, the plan the auction's rule names, every proposal folded and
 * counted. The first committed plan, before the spoiler acts, is the negative control: one proposal, no
 * duplicate. The link-level form, which holds the claim frame itself over TCP, needs the spoiler transport of
 * the spoiler program's phase 4 (after 0.7.0).
 */
class SpoilerLateDayPlanTest {

    private static final String TRIP = "lisbon-spoiler";
    private static final String DATE = "2026-06-02";
    private static final Duration CEILING = Duration.ofSeconds(60);

    @Test
    @Timeout(300)
    void lateProposalsForADecidedDayFoldIntoOnePlanByTheAuctionsRuleAtEveryReplica() throws Exception {
        int base = TestPorts.freeBlock(5);
        List<Peer> peers = new ArrayList<>();
        try {
            List<Peer> clerks = new ArrayList<>();
            clerks.add(PartyBusFleet.startPeer("clerk-1", base, 0));
            clerks.add(PartyBusFleet.startPeer("clerk-2", base + 1, base));
            clerks.add(PartyBusFleet.startPeer("clerk-3", base + 2, base));
            peers.addAll(clerks);
            Peer planner = PartyBusFleet.startPeer("sights-planner", base + 3, base);
            Peer spoiler = PartyBusFleet.startPeer("late-planner", base + 4, base);
            peers.add(planner);
            peers.add(spoiler);
            List<PartyBusFleet.Clerk> desk = PartyBusFleet.formBookingQuorum(clerks);
            await("partybus.desk.leader", Params.none(), () -> desk.stream().allMatch(c -> c.raft().leader().isPresent()));

            // The honest winner of round 1 plans the day, and the keepers commit it: the negative control.
            ScheduledActivity won = activity("SIGHTS", "Alfama walk", 80, "sights-planner", 1);
            propose(planner, won);
            awaitDay(peers, "honest", won, 1, 0, 0);

            // The late winner: the cheaper planner's claim reached the incumbent after the window, so both won
            // round 1 and both planned it. Its plan arrives after the day was committed and takes the day.
            ScheduledActivity cheaper = activity("PHOTO_WALK", "Miradouro photo walk", 60, "photo-planner", 1);
            propose(spoiler, cheaper);
            awaitDay(peers, "late-cheaper", cheaper, 2, 1, 0);

            // A second late winner with the higher bid changes nothing but the count.
            propose(spoiler, activity("DINING", "Tasca dinner", 95, "dining-planner", 1));
            awaitDay(peers, "late-dearer", cheaper, 3, 2, 0);

            // A re-auction (a storm) moves the day to round 2, and the honest planner's plan is committed.
            ScheduledActivity indoor = activity("SIGHTS", "Tile museum", 70, "sights-planner", 2);
            propose(planner, indoor);
            awaitDay(peers, "reauction", indoor, 4, 2, 0);

            // A late slot: a plan for round 1, the round the re-auction replaced, arrives now. It is folded and
            // counted as stale, and the day keeps its round-2 plan.
            propose(spoiler, activity("REST", "Beach afternoon", 10, "rest-planner", 1));
            awaitDay(peers, "late-slot", indoor, 5, 2, 1);
        } finally {
            for (Peer peer : peers) {
                peer.close();
            }
        }
    }

    private static ScheduledActivity activity(String kind, String title, double cost, String planner, int round) {
        return new ScheduledActivity(TRIP, DATE, "Lisbon", kind, title, cost, planner, round);
    }

    /** Writes a proposal as a planner does: into the bookings space, tagged with its day for the keepers' fold. */
    private static void propose(Peer peer, ScheduledActivity proposal) {
        peer.space(PartyBusFleet.BOOKINGS).write(proposal, Lease.of(Duration.ofHours(1)),
                Map.of(Itinerary.DAY_TAG, Itinerary.dayKey(TRIP, DATE)));
    }

    /**
     * Waits until every replica holds exactly one accumulator for the day, carrying the expected plan and counts,
     * with no proposal for the day left unfolded; the time it took is recorded under the step's name.
     */
    private static void awaitDay(List<Peer> peers, String step, ScheduledActivity plan, int proposals,
                                 int duplicates, int stale) {
        for (Peer peer : peers) {
            Space bookings = peer.space(PartyBusFleet.BOOKINGS);
            await("partybus.dayplan.settled", Params.of("step", step, "peer", peer.name()), () -> {
                List<Space.Entry<DayPlan>> day = bookings.readAllEntries(DayPlan.template(TRIP), 50).stream()
                        .filter(e -> e.value().date().equals(DATE)).toList();
                if (day.size() != 1) {
                    return false; // a fold writes the new plan, then retires the old one
                }
                DayPlan d = day.get(0).value();
                return d.plan().equals(plan) && d.proposals() == proposals && d.duplicates() == duplicates
                        && d.stale() == stale && unfolded(bookings).isEmpty();
            }, () -> "at " + peer.name() + ": " + bookings.readAllEntries(DayPlan.template(TRIP), 50).stream()
                    .map(e -> e.value().plan().plannedBy() + "/r" + e.value().plan().round() + "/p"
                            + e.value().proposals() + "/d" + e.value().duplicates() + "/s" + e.value().stale())
                    .toList() + ", unfolded " + unfolded(bookings).size());
        }
        DayPlan settled = DayPlan.current(peers.get(0).space(PartyBusFleet.BOOKINGS), TRIP, DATE).orElseThrow();
        assertThat(settled.considered()).as("every proposal is on the day's record").hasSize(proposals);
        System.out.println("PARTYBUS-SPOILER " + step + ": plan " + settled.plan().plannedBy() + "/r"
                + settled.plan().round() + " $" + settled.plan().costUsd() + ", proposals " + settled.proposals()
                + ", duplicates " + settled.duplicates() + ", stale " + settled.stale() + ", same at "
                + peers.size() + " replicas");
    }

    private static List<ScheduledActivity> unfolded(Space bookings) {
        return bookings.readAll(Template.of(ScheduledActivity.class).where("tripId", eq(TRIP))
                .whereTag(Itinerary.DAY_TAG, eq(Itinerary.dayKey(TRIP, DATE))), 20);
    }

    private static void await(String metric, Params params, java.util.function.BooleanSupplier condition) {
        await(metric, params, condition, () -> "");
    }

    /** A measured wait: polls every 250 ms, as the fleet test's waits do, and fails at the ceiling. */
    private static void await(String metric, Params params, java.util.function.BooleanSupplier condition,
                              java.util.function.Supplier<String> diagnostics) {
        // fm: (a) ceiling 60 s (policy; a fold that lost its keeper waits out the 30 s take lease)
        Measurement m = Measure.await(metric, CEILING, condition,
                Wait.with().params(params).poll(Duration.ofMillis(250)).diagnostics(diagnostics));
        System.out.println("PARTYBUS-MEASURE " + metric + " " + params + " " + Math.round(m.value()) + " ms");
    }
}
