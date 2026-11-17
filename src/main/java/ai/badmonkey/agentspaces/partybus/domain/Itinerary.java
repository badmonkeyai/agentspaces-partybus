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

import ai.badmonkey.agentspaces.agent.reduce.Reductions;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * The itinerary, bookings, and the party's own decisions.
 *
 * <p>Three coordination types meet here. {@link DaySlot}s live in an
 * {@code AUCTION} space: the activity planners bid for each day, and the space
 * routes the slot to the cheapest fit. {@link BookingOrder}s live in a space
 * whose takes serialize through the ordered log, because a reservation is paid
 * for and must be confirmed exactly once; the day's {@link DayPlan} is folded
 * through the same log, because a schedule the party shares must hold one plan
 * per day, which an auction alone promises only within its settle window.
 * {@link NextDestinationProposal}s are decided by the travelers' own quorum vote, and each traveler's
 * {@link EnergyReport} feeds the push-sum aggregate the simulator paces the
 * trip by.
 */
public final class Itinerary {

    private Itinerary() {
    }

    /**
     * One day of the trip, up for auction among the activity planners.
     *
     * @param tripId    the trip
     * @param date      the ISO date
     * @param location  where the party is that day
     * @param interests the party's interests, which bidders price against
     * @param pace      {@code FULL}, {@code EASY}, or {@code REST}
     * @param round     re-auction round; a strike or storm bumps it
     */
    public record DaySlot(String tripId, String date, String location, List<String> interests,
                          String pace, int round) {
    }

    /**
     * A plan a planner made for a slot it won: a proposal for the day, not yet the day's plan. The planner writes
     * it into the bookings space tagged {@link #DAY_TAG}, and the itinerary keepers fold it into the day's one
     * {@link DayPlan} through the ordered log.
     *
     * @param tripId   the trip
     * @param date     the day
     * @param location the place
     * @param kind     {@code DINING}, {@code SIGHTS}, {@code PHOTO_WALK}, or {@code REST}
     * @param title    what the party does
     * @param costUsd  the planner's winning bid, its estimate of the day's cost
     * @param plannedBy the planner that won
     * @param round    the auction round this filled
     */
    public record ScheduledActivity(String tripId, String date, String location, String kind,
                                    String title, double costUsd, String plannedBy, int round) {

        /** The order a day's proposals are judged in: a later round first, then the lower bid, then the name. */
        static final Comparator<ScheduledActivity> BETTER = Comparator
                .comparingInt(ScheduledActivity::round).reversed()
                .thenComparingDouble(ScheduledActivity::costUsd)
                .thenComparing(ScheduledActivity::plannedBy);
    }

    /** The tag a proposal carries naming its day, the itinerary reduce's key. */
    public static final String DAY_TAG = "day";

    /** The itinerary reduce's name: its accumulators carry it as their {@code reduce} tag. */
    public static final String ITINERARY = "itinerary";

    /** The key of one day of one trip, the value of a proposal's {@link #DAY_TAG}. */
    public static String dayKey(String tripId, String date) {
        return tripId + "|" + date;
    }

    /**
     * The round a re-auction of a day runs: one past the highest round the day has, counting the committed plan
     * and any proposal still waiting for its fold, so a re-auction never reuses a round a planner already filled.
     *
     * @param bookings the bookings space
     * @param tripId   the trip
     * @param date     the day
     * @return the next round
     */
    public static int nextRound(Space bookings, String tripId, String date) {
        int committed = DayPlan.current(bookings, tripId, date).map(d -> d.plan().round()).orElse(0);
        int waiting = bookings.readAll(Template.of(ScheduledActivity.class)
                        .whereTag(DAY_TAG, eq(dayKey(tripId, date))), 20).stream()
                .mapToInt(ScheduledActivity::round).max().orElse(0);
        return Math.max(committed, waiting) + 1;
    }

    /**
     * The day's plan: the one accumulator per day the itinerary keepers fold every proposal into, through the
     * ordered log, so that a day has one plan at all times, the same on every replica.
     *
     * <p>The auction alone cannot promise that. An {@code AUCTION} take picks one planner per day only while
     * every claim reaches every rival planner within the settle window (SPEC §7.4), and the bus measured claims
     * 0.3 to 2.4 s late, so two planners can each win and plan the same round of a day. A day plan is a shared
     * schedule, which needs strong coordination: the ordered reduce folds each proposal once, one keeper at a
     * time, and the fold keeps the auction's own rule, the latest round and in it the lowest bid. The fold does
     * not depend on the order proposals arrive in, so the plan a day settles on is the one the auction meant,
     * and a second plan for a round is counted, not shown.
     *
     * @param tripId     the trip
     * @param date       the day
     * @param plan       the day's plan
     * @param considered every proposal folded into this day, in all rounds, in the order the log folded them; a
     *                   day sees a handful, so the list stays small, and it is the record of every plan made
     * @param duplicates proposals for a round that already had one: two planners that both won one slot, or two
     *                   re-auctions of the day (an event's and the party's energy) that chose the same round
     * @param stale      proposals for a round older than the plan's, which arrived after the newer round
     */
    public record DayPlan(String tripId, String date, ScheduledActivity plan,
                          List<ScheduledActivity> considered, int duplicates, int stale) {

        public DayPlan {
            considered = List.copyOf(considered);
        }

        /** How many proposals were folded into this day. */
        public int proposals() {
            return considered.size();
        }

        /**
         * One step of the itinerary reduce: the day's plan after one more proposal.
         *
         * @param current  the day's plan so far, or null before the first proposal
         * @param proposal the proposal to fold
         * @return the day's plan with the proposal folded in
         */
        public static DayPlan fold(DayPlan current, ScheduledActivity proposal) {
            if (current == null) {
                return new DayPlan(proposal.tripId(), proposal.date(), proposal, List.of(proposal), 0, 0);
            }
            ScheduledActivity plan = current.plan();
            List<ScheduledActivity> considered = new java.util.ArrayList<>(current.considered());
            considered.add(proposal);
            if (proposal.round() < plan.round()) {
                // A plan for a round a re-auction already replaced: it arrived late and changes nothing.
                return new DayPlan(current.tripId(), current.date(), plan, considered,
                        current.duplicates(), current.stale() + 1);
            }
            if (proposal.round() > plan.round()) {
                return new DayPlan(current.tripId(), current.date(), proposal, considered,
                        current.duplicates(), current.stale());
            }
            // A second plan for this round: the lower bid keeps the day, as it does in the auction's lattice.
            ScheduledActivity better = ScheduledActivity.BETTER.compare(proposal, plan) < 0 ? proposal : plan;
            return new DayPlan(current.tripId(), current.date(), better, considered,
                    current.duplicates() + 1, current.stale());
        }

        /** The template of the itinerary accumulators of one trip. */
        public static Template<DayPlan> template(String tripId) {
            return Template.of(DayPlan.class).where("tripId", eq(tripId))
                    .whereTag(Reductions.REDUCE_TAG, eq(ITINERARY));
        }

        /**
         * The day's plan as the space holds it now: the newest accumulator of the day's key, the one a reader
         * acts on (a fold writes the new accumulator, then retires the old one).
         *
         * @param bookings the bookings space
         * @param tripId   the trip
         * @param date     the day
         * @return the plan, or empty before the day's first proposal is folded
         */
        public static Optional<DayPlan> current(Space bookings, String tripId, String date) {
            return Reductions.current(bookings, DayPlan.class, ITINERARY, dayKey(tripId, date))
                    .map(Space.Entry::value);
        }

        /**
         * Every day's plan of a trip, by date, each the newest accumulator of its day.
         *
         * @param bookings the bookings space
         * @param tripId   the trip
         * @return date to plan, in date order
         */
        public static SortedMap<String, DayPlan> ofTrip(Space bookings, String tripId) {
            Map<String, Space.Entry<DayPlan>> newest = new HashMap<>();
            for (Space.Entry<DayPlan> entry : bookings.readAllEntries(template(tripId), 200)) {
                newest.merge(entry.value().date(), entry,
                        (a, b) -> Reductions.steps(b) > Reductions.steps(a) ? b : a);
            }
            SortedMap<String, DayPlan> byDate = new TreeMap<>();
            newest.forEach((date, entry) -> byDate.put(date, entry.value()));
            return byDate;
        }
    }

    /**
     * A reservation to commit: one hotel night, one table, one ticket.
     *
     * @param tripId    the trip
     * @param orderId   the order
     * @param kind      {@code HOTEL}, {@code CAR}, {@code FLIGHT}, {@code TABLE}, or {@code TICKET}
     * @param what      what is being booked
     * @param date      the ISO date
     * @param amountUsd the charge
     */
    public record BookingOrder(String tripId, String orderId, String kind, String what,
                               String date, double amountUsd) {
    }

    /**
     * A confirmation, written exactly once per order by the clerk whose ordered
     * take won.
     *
     * @param tripId       the trip
     * @param orderId      the order
     * @param confirmation the confirmation code
     * @param amountUsd    the charge
     * @param clerk        the clerk that committed it
     * @param logIndex     the ordered-log index of the committing take
     */
    public record BookingConfirmation(String tripId, String orderId, String confirmation,
                                      double amountUsd, String clerk, long logIndex) {
    }

    /**
     * A question put to the travelers: where next, or whether to change plans.
     *
     * @param tripId     the trip
     * @param proposalId the vote id
     * @param question   what is asked
     * @param options    the choices
     * @param askedBy    who asked
     */
    public record NextDestinationProposal(String tripId, String proposalId, String question,
                                         List<String> options, String askedBy) {
    }

    /**
     * The travelers' decision, recorded once the vote reaches quorum.
     *
     * @param tripId     the trip
     * @param proposalId the vote
     * @param winner     the chosen option
     * @param tally      option to votes, rendered {@code option=count}
     */
    public record PartyDecision(String tripId, String proposalId, String winner,
                                List<String> tally) {
    }

    /**
     * How one traveler feels today; the aggregate over the party sets the pace.
     *
     * @param tripId   the trip
     * @param date     the day
     * @param traveler who
     * @param energy   0 (spent) to 100 (raring to go)
     * @param spentUsd what they spent today
     */
    public record EnergyReport(String tripId, String date, String traveler, int energy,
                               double spentUsd) {
    }

    /** The push-sum epoch id for the party's energy on one day. */
    public static String energyEpoch(String tripId, String date) {
        return "energy:" + tripId + ":" + date;
    }

    /** The push-sum epoch id for the party's spend on one day. */
    public static String spendEpoch(String tripId, String date) {
        return "spend:" + tripId + ":" + date;
    }
}
