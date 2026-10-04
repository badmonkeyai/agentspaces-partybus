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

import java.util.List;

/**
 * The itinerary, bookings, and the party's own decisions.
 *
 * <p>Three coordination types meet here. {@link DaySlot}s live in an
 * {@code AUCTION} space: the activity planners bid for each day, and the space
 * awards the slot to the cheapest fit. {@link BookingOrder}s live in a space
 * whose takes serialize through the ordered log, because a reservation is paid
 * for and must be confirmed exactly once. {@link NextDestinationProposal}s are
 * decided by the travelers' own quorum vote, and each traveler's
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
     * The activity the winning planner scheduled into a slot.
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
