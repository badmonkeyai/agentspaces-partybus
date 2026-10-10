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
import ai.badmonkey.agentspaces.agent.annotation.Ballot;
import ai.badmonkey.agentspaces.agent.annotation.OnDecision;
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify;
import ai.badmonkey.agentspaces.agent.annotation.SpaceRef;
import ai.badmonkey.agentspaces.agent.capability.Motion;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.EnergyReport;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.NextDestinationProposal;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.PartyDecision;
import ai.badmonkey.agentspaces.partybus.domain.Research.Destination;
import ai.badmonkey.agentspaces.partybus.domain.Research.NextDestinationIdeas;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.EventKind;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.TripDay;
import ai.badmonkey.agentspaces.partybus.domain.Simulation.TripEvent;
import ai.badmonkey.agentspaces.partybus.domain.Trip.Traveler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * The party: the travelers themselves as agents, plus the party lead.
 *
 * <p>Tripper models travelers as data the planner writes for. Party Bus gives
 * each traveler a seat on the bus. A {@link TravelerAgent} reacts to every
 * {@link TripDay} with an {@link EnergyReport}, and feeds the same number into
 * the {@code aspace:cap/aggregate} push-sum epoch for that day, so the fleet
 * learns the party's average energy in O(log N) gossip rounds with nobody
 * collecting anything; the simulator reads the estimate and sets the next
 * day's pace, and the pace is what flips the activity auction toward the rest
 * planner. Each traveler also votes on where to go next when the party lead
 * puts the next-destination advisor's ideas to a quorum vote, preferring the
 * idea that matches what they said they were about.
 *
 * <p>Two collective decisions, two mechanisms, chosen by what each needs. The
 * pace is quorum sensing (an approximate average is plenty, and it must be
 * cheap every day); the destination is a ballot (it must be exact and
 * auditable, and it happens once).
 */
public final class Party {

    /** The space the party's proposals and ballots live in. */
    public static final String DECISIONS = "decisions";
    /** The space trip days, events, and energy reports live in. */
    public static final String TRIP = "trip";

    private Party() {
    }

    /** One traveler on the bus. */
    @AgentSpec(name = "traveler",
            description = "A traveler on the trip: reports energy each day and votes on the party's choices",
            goals = {"have a good trip"})
    public static final class TravelerAgent {
        private final Traveler traveler;
        private final int stamina;
        private final PushSumAggregate aggregate;

        @SpaceRef(TRIP)
        Space trip;

        /**
         * @param traveler  who
         * @param stamina   baseline energy, 0 to 100
         * @param aggregate the push-sum capability this traveler reports into
         * @param vote      the vote capability over the decisions space
         */
        public TravelerAgent(Traveler traveler, int stamina, PushSumAggregate aggregate) {
            this.traveler = Objects.requireNonNull(traveler, "traveler");
            this.stamina = stamina;
            this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
        }

        /** The traveler's name. */
        public String name() {
            return traveler.name();
        }

        /**
         * How this traveler feels on a day: stamina minus wear, minus a lot when
         * the day's events hit them, and the same number goes into the aggregate.
         */
        @SpaceNotify(space = TRIP, lease = "12h")
        public EnergyReport feel(TripDay day) {
            int energy = energyOn(day, trip.readAll(Template.of(TripEvent.class)
                    .where("tripId", eq(day.tripId())).where("date", eq(day.date())), 20));
            aggregate.start(Itinerary.energyEpoch(day.tripId(), day.date()), energy);
            double spent = 40 + (traveler.about().toLowerCase().contains("food") ? 45 : 20)
                    + day.dayIndex() * 3;
            aggregate.start(Itinerary.spendEpoch(day.tripId(), day.date()), spent);
            return new EnergyReport(day.tripId(), day.date(), traveler.name(), energy, spent);
        }

        /** Pure and deterministic, so the simulator's story replays the same way. */
        int energyOn(TripDay day, List<TripEvent> events) {
            int energy = stamina - day.dayIndex() * 9;
            for (TripEvent event : events) {
                if (event.kind() == EventKind.ILLNESS
                        && event.detail().toLowerCase().contains(traveler.name().toLowerCase())) {
                    energy -= 60;
                } else if (event.kind() == EventKind.WEATHER || event.kind() == EventKind.STRIKE) {
                    energy -= 10;
                } else if (event.kind() == EventKind.PROTEST) {
                    energy -= 15;
                }
            }
            return Math.max(0, Math.min(100, energy));
        }

        /**
         * One signed ballot per proposal, for the option that sounds most like them.
         * The cue is the vote's own proposal, so the ballot is never refused as
         * unknown and no wait is needed.
         */
        @Ballot(space = DECISIONS, prefix = "next:", lease = "12h")
        public String decide(VoteCapability.Proposal proposal) {
            return preference(proposal.options());
        }

        String preference(List<String> options) {
            String about = traveler.about().toLowerCase();
            for (String option : options) {
                for (String word : option.toLowerCase().split("[^a-z]+")) {
                    if (word.length() > 3 && about.contains(word)) {
                        return option;
                    }
                }
            }
            // Fall back on a stable, name-derived choice so ties break differently per traveler.
            return options.get(Math.abs(traveler.name().hashCode()) % options.size());
        }
    }

    /**
     * The party lead: when the next-destination advisor answers, puts the ideas
     * to the travelers as a quorum vote (and announces it on the record), and
     * records the decision on the trip when the vote closes.
     */
    @AgentSpec(name = "party-lead", description = "Puts the next destination to a vote",
            goals = {"decide together"})
    public static final class Lead {
        private final int partySize;
        @SpaceRef(DECISIONS)
        Space decisions;

        public Lead(int partySize) {
            this.partySize = partySize;
        }

        /**
         * The options come from the cue (the advisor's ideas), so this is the
         * dynamic form: the method returns the {@link Motion} and the binder opens
         * it once per proposal id (ISSUE-Motion). The human-readable announcement
         * is a second entry, written through the handle, since one return
         * carries one value.
         */
        @SpaceNotify(space = Scouts.SPACE, lease = "12h")
        public Motion open(NextDestinationIdeas ideas) {
            String proposalId = "next:" + ideas.tripId();
            List<String> options = new ArrayList<>();
            for (Destination idea : ideas.ideas()) {
                options.add(idea.name());
            }
            if (decisions.read(Template.of(NextDestinationProposal.class)
                    .where("proposalId", eq(proposalId))).isEmpty()) {
                decisions.write(new NextDestinationProposal(ideas.tripId(), proposalId,
                        "Where next after this trip?", options, "party-lead"), Lease.of(Duration.ofHours(12)));
            }
            return Motion.in(DECISIONS, proposalId, "Where next after this trip?", options, partySize,
                    Lease.of(Duration.ofHours(12)));
        }

        @OnDecision(space = DECISIONS, prefix = "next:", resultSpace = TRIP, resultLease = "12h",
                lease = "12h")
        public PartyDecision record(VoteCapability.Decision decision) {
            List<String> tally = new ArrayList<>();
            for (Map.Entry<String, Integer> e : decision.tally().entrySet()) {
                tally.add(e.getKey() + "=" + e.getValue());
            }
            return new PartyDecision(decision.proposalId().substring("next:".length()),
                    decision.proposalId(), decision.winner(), tally);
        }
    }
}
