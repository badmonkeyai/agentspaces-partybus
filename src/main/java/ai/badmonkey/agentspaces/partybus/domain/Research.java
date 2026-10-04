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
 * The research bag: one typed request and one typed result per specialist.
 * Every request carries {@code tripId} and {@code topic}, and so does every
 * result, which is the shared-field correlation the remote-actions bridge uses
 * to pair a planner's request with the answer some scout wrote back. The pairs
 * are deliberately distinct types rather than one generic {@code Finding}: the
 * planner conditions on types, so each pair is one planner action, and a
 * template of the request type is one scout's whole work queue.
 *
 * <p>Tripper researches its points of interest in a {@code parallelMap} inside
 * one process. Party Bus writes the same research as entries and lets whoever
 * advertises the skill take them, so a scout that dies mid-search lets its
 * lease lapse and another finishes the job.
 */
public final class Research {

    private Research() {
    }

    /** A source a scout cites: Tripper's {@code InternetResource}. */
    public record Link(String url, String summary) {
    }

    // ------------------------------------------------------------ transport

    /** Find flights or rail for the outbound and return legs. */
    public record FlightRequest(String tripId, String topic, String from, String to,
                                String departureDate, String returnDate, String preference) {
    }

    /** One itinerary option. */
    public record FlightOption(String carrier, String route, String departs, String returns,
                               double priceUsd, String bookingUrl) {
    }

    /** The flight scout's answer. */
    public record FlightOptions(String tripId, String topic, List<FlightOption> options,
                                String note, String author) {
    }

    /** Find a rental car or ground transport at the destination. */
    public record CarRequest(String tripId, String topic, String location, String pickupDate,
                             String dropoffDate, int partySize, List<String> needs) {
    }

    /** One car option. */
    public record CarOption(String vendor, String vehicle, double dailyUsd, boolean accessible,
                            String bookingUrl) {
    }

    /** The car scout's answer. */
    public record CarOptions(String tripId, String topic, List<CarOption> options, String note,
                             String author) {
    }

    // ---------------------------------------------------------------- stays

    /** Find lodging in one town for a date range under a nightly budget. */
    public record HotelRequest(String tripId, String topic, String location, String checkIn,
                               String checkOut, double nightlyBudget, List<String> needs) {
    }

    /** One lodging option. */
    public record HotelOption(String name, String area, double nightlyUsd, double rating,
                              boolean accessible, String bookingUrl) {
    }

    /** The hotel scout's answer. */
    public record HotelOptions(String tripId, String topic, String location,
                               List<HotelOption> options, String note, String author) {
    }

    // -------------------------------------------------------------- the day

    /** Propose points of interest for the whole trip. */
    public record SightseeingRequest(String tripId, String topic, String destination,
                                     String brief, String departureDate, String returnDate,
                                     String travelers) {
    }

    /** The sightseeing scout's answer. */
    public record SightseeingOptions(String tripId, String topic,
                                     List<Trip.PointOfInterest> pointsOfInterest, String author) {
    }

    /** Find restaurants near a location for a party. */
    public record RestaurantRequest(String tripId, String topic, String location,
                                    double perPersonBudget, List<String> needs) {
    }

    /** One restaurant. */
    public record Restaurant(String name, String cuisine, double perPersonUsd, double rating,
                             List<String> suits, String url) {
    }

    /** The restaurant scout's answer. */
    public record RestaurantOptions(String tripId, String topic, String location,
                                    List<Restaurant> restaurants, String author) {
    }

    /** Sensible options for travelers with dietary needs. */
    public record DietRequest(String tripId, String topic, String location, List<String> needs) {
    }

    /** The diet advisor's answer. */
    public record DietOptions(String tripId, String topic, List<Restaurant> restaurants,
                              List<String> tips, String author) {
    }

    /** The best food experiences a place offers. */
    public record FoodieRequest(String tripId, String topic, String location, String brief) {
    }

    /** The foodie advisor's answer. */
    public record FoodieOptions(String tripId, String topic, List<Restaurant> mustEat,
                                List<String> dishes, String author) {
    }

    /** Key photographs of a place, for the plan and for the photo walk. */
    public record PhotoRequest(String tripId, String topic, String subject, String location) {
    }

    /** The photo curator's answer. */
    public record PhotoSet(String tripId, String topic, List<Link> images, String author) {
    }

    /** Reviews of a place, digested for this party. */
    public record ReviewRequest(String tripId, String topic, String subject, String location) {
    }

    /** The review reader's answer. */
    public record ReviewDigest(String tripId, String topic, double averageRating,
                               List<String> praise, List<String> complaints, List<Link> sources,
                               String author) {
    }

    // ------------------------------------------------------------- welfare

    /** How to travel a place with mobility, sight, or hearing restrictions. */
    public record AccessibilityRequest(String tripId, String topic, String location,
                                       List<String> needs) {
    }

    /** The accessibility advisor's answer. */
    public record AccessibilityGuide(String tripId, String topic, List<String> transit,
                                     List<String> lodging, List<String> attractions,
                                     List<String> warnings, String author) {
    }

    /** The nearest hospitals and emergency numbers for a location. */
    public record HospitalRequest(String tripId, String topic, String location, String reason) {
    }

    /** One hospital. */
    public record Hospital(String name, String address, double distanceKm, boolean emergency,
                           String phone) {
    }

    /** The hospital locator's answer. */
    public record HospitalList(String tripId, String topic, String location,
                               List<Hospital> hospitals, String emergencyNumber, String author) {
    }

    // -------------------------------------------------------------- onward

    /** Ideas on where the party might go next after this trip. */
    public record NextDestinationRequest(String tripId, String topic, String after, String brief,
                                         String travelers) {
    }

    /** One candidate next destination. */
    public record Destination(String name, String why, String bestSeason,
                              double estimatedDailyUsd) {
    }

    /** The next-destination advisor's answer. */
    public record NextDestinationIdeas(String tripId, String topic, List<Destination> ideas,
                                       String author) {
    }
}
