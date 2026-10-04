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

import java.time.LocalDate;
import java.util.List;

/**
 * The trip itself: the entry types Tripper's domain model becomes once the
 * blackboard is a shared space. Every record carries {@code tripId}, the field
 * the whole fleet correlates on, and dates travel as ISO-8601 strings so that
 * templates match them with plain equality.
 *
 * <p>These types map one for one onto Tripper's: {@link TravelBrief} is its
 * {@code JourneyTravelBrief}, {@link Travelers} its {@code Travelers},
 * {@link ProposedTravelPlan} and {@link TravelPlan} its plan types. Party Bus
 * adds {@code needs} to a traveler (dietary and accessibility requirements the
 * advisor agents consume) and a {@code tripId} everywhere.
 */
public final class Trip {

    private Trip() {
    }

    /**
     * The travel brief the turret writes to start a trip. The shape of Tripper's
     * {@code JourneyTravelBrief} with a trip id.
     *
     * @param tripId              the trip identifier every entry correlates on
     * @param from                the origin
     * @param to                  the destination
     * @param departureDate       ISO-8601 departure date
     * @param returnDate          ISO-8601 return date
     * @param dailyBudget         the daily budget in USD
     * @param brief               the free-text brief
     * @param transportPreference how the travelers prefer to move
     */
    public record TravelBrief(String tripId, String from, String to, String departureDate,
                              String returnDate, double dailyBudget, String brief,
                              String transportPreference) {

        /** The trip's days as ISO-8601 strings, departure to return inclusive. */
        public List<String> days() {
            LocalDate start = LocalDate.parse(departureDate);
            LocalDate end = LocalDate.parse(returnDate);
            return start.datesUntil(end.plusDays(1)).map(LocalDate::toString).toList();
        }

        /** The brief as prompt text, the way Tripper's {@code contribution()} renders it. */
        public String contribution() {
            return "Journey from " + from + " to " + to + "\nDates: " + departureDate + " to "
                    + returnDate + "\nBrief: " + brief + "\nTransport preference: "
                    + transportPreference + "\nDaily budget (USD): " + dailyBudget;
        }
    }

    /**
     * One traveler.
     *
     * @param name  the traveler's name
     * @param about interests and background, as Tripper has it
     * @param needs dietary, mobility, and medical needs the advisors honor
     */
    public record Traveler(String name, String about, List<String> needs) {
    }

    /**
     * The travelers on a trip.
     *
     * @param tripId    the trip
     * @param travelers the party
     */
    public record Travelers(String tripId, List<Traveler> travelers) {

        /** The party as prompt text. */
        public String contribution() {
            StringBuilder text = new StringBuilder(travelers.size() + " travelers:\n");
            for (Traveler t : travelers) {
                text.append(t.name()).append(": ").append(t.about());
                if (!t.needs().isEmpty()) {
                    text.append(" (needs: ").append(String.join(", ", t.needs())).append(')');
                }
                text.append('\n');
            }
            return text.toString();
        }

        /** Every distinct need across the party. */
        public List<String> allNeeds() {
            return travelers.stream().flatMap(t -> t.needs().stream()).distinct().toList();
        }
    }

    /**
     * A point of interest the sightseeing scout proposes, Tripper's
     * {@code PointOfInterest}.
     *
     * @param name        the place
     * @param description why it suits the party
     * @param location    a Google Maps friendly {@code City,+Country}
     * @param fromDate    first suggested ISO date
     * @param toDate      last suggested ISO date
     */
    public record PointOfInterest(String name, String description, String location,
                                  String fromDate, String toDate) {
    }

    /**
     * A day of the plan, Tripper's {@code Day}.
     *
     * @param date               ISO date
     * @param locationAndCountry {@code City,+Country}
     * @param summary            what the day holds
     */
    public record Day(String date, String locationAndCountry, String summary) {

        /** The readable town name, e.g. {@code Ghent} for {@code Ghent,+Belgium}. */
        public String stayingAt() {
            int comma = locationAndCountry.indexOf(',');
            return (comma < 0 ? locationAndCountry : locationAndCountry.substring(0, comma))
                    .trim();
        }
    }

    /**
     * The plan the assembler writes once the research bundle is complete,
     * Tripper's {@code ProposedTravelPlan}.
     *
     * @param tripId           the trip
     * @param title            a catchy title
     * @param plan             the HTML plan
     * @param days             the day list
     * @param countriesVisited the countries
     * @param assembledBy      the agent that wrote it
     */
    public record ProposedTravelPlan(String tripId, String title, String plan, List<Day> days,
                                     List<String> countriesVisited, String assembledBy) {
    }

    /**
     * A stay, Tripper's {@code Stay}: consecutive days in one place with the
     * accommodation the hotel scout found.
     *
     * @param location   the town
     * @param dates      the ISO dates
     * @param lodging    the chosen lodging name
     * @param bookingUrl where to book it
     * @param nightly    nightly rate in USD
     */
    public record Stay(String location, List<String> dates, String lodging, String bookingUrl,
                       double nightly) {
    }

    /**
     * The finished plan, Tripper's {@code TravelPlan}: the proposal plus stays
     * and the advisory the safety panel voted.
     *
     * @param tripId   the trip
     * @param title    the plan title
     * @param plan     the HTML plan
     * @param days     the day list
     * @param stays    the stays
     * @param advisory the safety panel's verdict for the destination
     * @param mapUrl   a Google Maps directions link over the days
     */
    public record TravelPlan(String tripId, String title, String plan, List<Day> days,
                             List<Stay> stays, String advisory, String mapUrl) {
    }
}
