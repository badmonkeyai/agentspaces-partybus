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

import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import ai.badmonkey.agentspaces.partybus.domain.Safety;
import ai.badmonkey.agentspaces.partybus.domain.Safety.DangerAssessment;
import ai.badmonkey.agentspaces.partybus.domain.Safety.TravelAdvisory;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.Ballot;
import ai.badmonkey.agentspaces.agent.annotation.OnDecision;
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify;
import ai.badmonkey.agentspaces.agent.annotation.SpaceRef;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * The safety panel: three panelists and a lead deciding one travel advisory
 * per place per sweep by signed quorum vote ({@code aspace:cap/vote} in QUORUM
 * mode).
 *
 * <p>Each panelist is seated for one channel (news, video, social) and trusts
 * the other channels a little less than its own, so a lurid video score alone
 * cannot carry an AVOID that the news and the forums do not support. The lead
 * opens the proposal when the first assessment of a sweep arrives, records the
 * {@link TravelAdvisory} when the vote reaches quorum, and every ballot stays
 * in the {@code advisories} space signed by its panelist, which is what makes
 * the advisory an auditable decision rather than a number that fell out of a
 * model.
 *
 * <p>The panel votes again for every new sweep. A protest in the simulator
 * triggers a sweep named by the event, the analyst scores the fresh reports
 * under that wave, and the panel decides the advisory for that wave, so the
 * turret shows the fleet's opinion moving with the news.
 */
public final class SafetyPanel {

    /** The channels with a seat on the panel; also the quorum. */
    public static final List<String> SEATS = List.of("news", "video", "social");

    /** A trust-weighted danger at or above this is AVOID. */
    public static final int AVOID_AT = 70;
    /** At or above this (and below {@link #AVOID_AT}) is CAUTION. */
    public static final int CAUTION_AT = 40;

    private SafetyPanel() {
    }

    /**
     * How far a panelist of one channel trusts an assessment from another. Video
     * is the most vivid channel and the least trusted by the others; the news
     * seat is the most trusted.
     */
    public static double trust(String seat, String channel) {
        if (seat.equals(channel)) {
            return 1.0;
        }
        return switch (channel) {
            case "news" -> 0.9;
            case "social" -> 0.7;
            default -> 0.6; // video
        };
    }

    /** The verdict a weighted danger score deserves. */
    public static String verdictFor(double weightedDanger) {
        if (weightedDanger >= AVOID_AT) {
            return "AVOID";
        }
        return weightedDanger >= CAUTION_AT ? "CAUTION" : "GO";
    }

    /** The vote space the panel's advisories are decided in. */
    public static final String ADVISORIES = "advisories";
    /** Proposal ids the panel votes on all start with this. */
    public static final String PROPOSAL_PREFIX = "advisory:";

    /**
     * A panelist: its cue is the vote's own proposal for a wave of danger
     * assessments; it weighs the wave's assessments by how far it trusts each
     * channel and returns its verdict. The binder casts the ballot as this peer's
     * agent, once per proposal; no voted set, no proposal wait.
     */
    @AgentSpec(name = "panelist", description = "Casts one signed ballot per safety proposal",
            goals = {"advise"})
    public static final class Panelist {
        private final String seat;
        @SpaceRef(Watchers.SIGNALS)
        Space signals;

        public Panelist(String seat) {
            this.seat = Objects.requireNonNull(seat, "seat");
        }

        @Ballot(space = ADVISORIES, prefix = PROPOSAL_PREFIX, lease = "12h")
        public String judge(VoteCapability.Proposal proposal) {
            // The proposal names the wave; read its assessments, waiting briefly
            // for our own channel's when it is still in flight.
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                List<DangerAssessment> wave = waveOf(proposal.proposalId());
                boolean ownSeen = wave.stream().anyMatch(a -> a.channel().equals(seat));
                if (ownSeen || wave.size() >= SEATS.size()) {
                    double weighted = wave.stream()
                            .mapToDouble(a -> a.danger() * trust(seat, a.channel()))
                            .max().orElse(0.0);
                    return verdictFor(weighted);
                }
                sleep(100);
            }
            return null; // the wave never arrived here: abstain
        }

        private List<DangerAssessment> waveOf(String proposalId) {
            return signals.readAll(Template.of(DangerAssessment.class), 500).stream()
                    .filter(a -> Safety.proposalId(a.tripId(), a.location(), a.wave()).equals(proposalId))
                    .toList();
        }
    }

    /**
     * The safety lead: opens a vote for every wave of assessments it sees, and
     * publishes the {@link TravelAdvisory} when the panel's vote closes.
     */
    @AgentSpec(name = "safety-lead", description = "Opens panel votes and publishes advisories",
            goals = {"advise"})
    public static final class Lead {
        private final VoteCapability vote;
        private final String leadName;
        private final Set<String> proposed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Map<String, DangerAssessment> waves = new java.util.concurrent.ConcurrentHashMap<>();
        @SpaceRef(Watchers.SIGNALS)
        Space signals;

        public Lead(VoteCapability vote, String leadName) {
            this.vote = Objects.requireNonNull(vote, "vote");
            this.leadName = Objects.requireNonNull(leadName, "leadName");
        }

        @SpaceNotify(space = Watchers.SIGNALS, lease = "12h")
        public void open(DangerAssessment assessment) {
            String proposalId = Safety.proposalId(assessment.tripId(), assessment.location(),
                    assessment.wave());
            waves.putIfAbsent(proposalId, assessment);
            if (proposed.add(proposalId) && vote.proposal(proposalId).isEmpty()) {
                vote.propose(proposalId, "Travel advisory for " + assessment.location()
                        + " (" + assessment.wave() + ")", Safety.VERDICTS, SEATS.size(),
                        Lease.of(Duration.ofHours(12)));
            }
        }

        @OnDecision(space = ADVISORIES, prefix = PROPOSAL_PREFIX, resultSpace = Party.TRIP,
                resultLease = "12h", lease = "12h")
        public TravelAdvisory publish(VoteCapability.Decision decision) {
            DangerAssessment wave = waves.get(decision.proposalId());
            if (wave == null) {
                return null; // a vote opened elsewhere on a wave this lead never saw
            }
            Map<String, Integer> tally = decision.tally();
            Set<String> reasons = new TreeSet<>();
            signals.readAll(Template.of(DangerAssessment.class)
                    .where("tripId", eq(wave.tripId()))
                    .where("location", eq(wave.location()))
                    .where("wave", eq(wave.wave())), 50)
                    .forEach(a -> reasons.addAll(a.themes()));
            return new TravelAdvisory(wave.tripId(), wave.location(), wave.wave(), decision.winner(),
                    tally.getOrDefault("GO", 0), tally.getOrDefault("CAUTION", 0),
                    tally.getOrDefault("AVOID", 0), List.copyOf(reasons), leadName);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
