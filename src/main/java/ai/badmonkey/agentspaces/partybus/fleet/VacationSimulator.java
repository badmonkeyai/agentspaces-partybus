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
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.DaySlot;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.EnergyReport;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.ScheduledActivity;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.EventKind;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.TripDay;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.TripEvent;
import ai.badmonkey.agentspaces.partybus.domain.Trip.TravelBrief;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Consumer;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * The vacation simulator: a clock and a script. It writes one {@link TripDay}
 * per day of the brief, throws the script's {@link TripEvent}s at the fleet,
 * and between days reads the party's energy from the push-sum aggregate to
 * set the next day's pace. When the pace turns to {@code REST} the simulator
 * re-auctions the day, and the rest planner's collapsing bid wins it.
 *
 * <p>The simulator never calls an agent. Everything it does is a write into
 * the {@code trip} or {@code slots} space, and everything the fleet does about
 * it is a reaction, so the same fleet handles the same events when the turret
 * throws them by hand, or when the real world does.
 *
 * <p>Days are short: a "day" is a few seconds of wall clock, long enough for
 * the fleet to converge on the reaction.
 */
public final class VacationSimulator {

    /** One beat of the script. */
    public record Scene(int dayIndex, EventKind kind, String detail, int severity) {
    }

    /** A daily narration line, for the demo's console. */
    public record Narration(String date, String pace, OptionalDouble energy, TripEvent event,
                            List<ScheduledActivity> schedule) {
    }

    /** The bundled script: the week the recorded trip has. */
    public static List<Scene> lisbonWeek() {
        return List.of(
                new Scene(0, EventKind.CALM, "Wheels down. Pastel de nata count: 3 before the hotel.", 1),
                new Scene(1, EventKind.WEATHER, "Atlantic squall parks over the city until mid-afternoon.", 2),
                new Scene(2, EventKind.ILLNESS, "Marco ordered the clams. Marco regrets the clams.", 3),
                new Scene(3, EventKind.PROTEST, "Transport unions march on the Assembleia; Baixa is sealed off.", 3),
                new Scene(4, EventKind.STRIKE, "Metro and ferry strike; the tram to Belém is a rumor.", 3),
                new Scene(5, EventKind.CALM, "Sun, a rooftop, and nothing on fire.", 1),
                new Scene(6, EventKind.LOST_PASSPORT, "Priya's passport is in a taxi. Somewhere.", 4));
    }

    private final Space trip;
    private final Space slots;
    private final PushSumAggregate aggregate;
    private final Duration dayLength;
    private final Consumer<Narration> narrator;

    /**
     * @param trip      the trip space
     * @param slots     the auction space (for re-auctions on a rest day)
     * @param aggregate the push-sum capability the party reports energy into
     * @param dayLength how long a simulated day lasts
     * @param narrator  receives one narration per day
     */
    public VacationSimulator(Space trip, Space slots, PushSumAggregate aggregate,
                             Duration dayLength, Consumer<Narration> narrator) {
        this.trip = Objects.requireNonNull(trip, "trip");
        this.slots = Objects.requireNonNull(slots, "slots");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
        this.dayLength = Objects.requireNonNull(dayLength, "dayLength");
        this.narrator = Objects.requireNonNull(narrator, "narrator");
    }

    /** The pace a party with this average energy should keep tomorrow. */
    public static String paceFor(OptionalDouble energy) {
        if (energy.isEmpty()) {
            return "FULL";
        }
        if (energy.getAsDouble() < 40) {
            return "REST";
        }
        return energy.getAsDouble() < 60 ? "EASY" : "FULL";
    }

    /**
     * Lives the trip, day by day, on the calling thread.
     *
     * @param brief the brief that defines the days
     * @param script the scenes to play
     * @return one narration per day, in order
     * @throws InterruptedException if interrupted between days
     */
    public List<Narration> live(TravelBrief brief, List<Scene> script) throws InterruptedException {
        List<Narration> story = new ArrayList<>();
        List<String> days = brief.days();
        String pace = "EASY";
        for (int i = 0; i < days.size(); i++) {
            String date = days.get(i);
            // The day's event lands first, so the travelers wake up to it.
            TripEvent event = null;
            for (Scene scene : script) {
                if (scene.dayIndex() == i && scene.kind() != EventKind.CALM) {
                    event = new TripEvent(brief.tripId(), date, scene.kind(), brief.to(),
                            scene.detail(), scene.severity(), brief.tripId() + "-d" + i + "-"
                            + scene.kind().name().toLowerCase());
                    trip.write(event, Lease.of(Duration.ofHours(12)));
                }
            }
            Thread.sleep(300);
            trip.write(new TripDay(brief.tripId(), date, i, brief.to(), pace),
                    Lease.of(Duration.ofHours(12)));
            Thread.sleep(dayLength.toMillis());

            OptionalDouble energy = aggregate.estimate(Itinerary.energyEpoch(brief.tripId(), date));
            if (energy.isEmpty()) {
                // No share reached this peer yet: the reports in the space are the fallback.
                energy = trip.readAll(Template.of(EnergyReport.class)
                                .where("tripId", eq(brief.tripId())).where("date", eq(date)), 20)
                        .stream().mapToInt(EnergyReport::energy).average();
            }
            List<ScheduledActivity> schedule = slots.readAll(Template.of(ScheduledActivity.class)
                    .where("tripId", eq(brief.tripId())).where("date", eq(date)), 10);
            story.add(new Narration(date, pace, energy, event, schedule));
            narrator.accept(story.get(story.size() - 1));

            String next = paceFor(energy);
            if (i + 1 < days.size() && next.equals("REST")) {
                reauction(brief, days.get(i + 1), next);
            }
            pace = next;
        }
        return story;
    }

    private void reauction(TravelBrief brief, String date, String pace) {
        int round = slots.readAll(Template.of(ScheduledActivity.class)
                        .where("tripId", eq(brief.tripId())).where("date", eq(date)), 10).stream()
                .mapToInt(ScheduledActivity::round).max().orElse(0) + 1;
        slots.write(new DaySlot(brief.tripId(), date, brief.to(),
                List.of(brief.brief().split("[,;.]\\s*")), pace, round),
                Lease.of(Duration.ofHours(6)));
    }
}
