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
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import ai.badmonkey.agentspaces.partybus.domain.Safety;
import ai.badmonkey.agentspaces.partybus.fleet.PartyBusFleet;
import ai.badmonkey.agentspaces.partybus.fleet.PartyBusFleet.Peer;
import ai.badmonkey.agentspaces.partybus.sources.RecordedSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA4 A4-4, the safety chain end to end: watchers sweep, the analyst scores,
 * the lead proposes, three panelists each cast a ballot, and the quorum closes
 * into a signed advisory.
 *
 * <p>This is the regression test for a silent stall. The three panelists used to
 * share one peer, so all three ballots declared a voter the space could never
 * attribute a write to; every one was dropped as forged, the tally stayed empty,
 * and the quorum never closed — with nothing logged anywhere. The library now
 * refuses that construction outright, and the panel seats one peer per voter.
 * Cheaper and far more diagnostic than the full week-long flow test.
 */
class SafetyQuorumTest {

    private static final String TRIP = "lisbon-2026";

    @Test
    @Timeout(240)
    void theSafetyPanelReachesAQuorumDecisionAndPublishesAnAdvisory() throws Exception {
        RecordedSources sources = RecordedSources.lisbon();
        int base;
        try (ServerSocket probe = new ServerSocket(0)) {
            base = probe.getLocalPort();
        }
        PartyBus.Seating bus = PartyBus.board(sources, base, 0);
        try {
            Peer turret = bus.turretPeer();
            Space trip = turret.space(PartyBusFleet.TRIP);
            Space signals = turret.space(PartyBusFleet.SIGNALS);
            Space advisories = turret.space(PartyBusFleet.ADVISORIES);
            Thread.sleep(2500);

            trip.write(PartyBus.party(TRIP), Lease.of(Duration.ofHours(1)));
            trip.write(PartyBus.brief(TRIP), Lease.of(Duration.ofHours(1)));

            // The chain, step by step, so a future stall names its own stage.
            assertThat(await(() -> first(signals, Safety.SignalReport.class)))
                    .as("the watchers swept and reported").isPresent();
            assertThat(await(() -> first(signals, Safety.DangerAssessment.class)))
                    .as("the analyst scored a report").isPresent();
            assertThat(await(() -> first(advisories, VoteCapability.Proposal.class)))
                    .as("the safety lead opened a proposal").isPresent();
            assertThat(await(() -> first(advisories, VoteCapability.Ballot.class)))
                    .as("a panelist cast a ballot").isPresent();

            // Three distinct voters, so the three-seat quorum can close.
            assertThat(await(() -> {
                List<VoteCapability.Ballot> all = advisories.readAll(
                        Template.of(VoteCapability.Ballot.class), 100);
                return all.size() >= 3 ? Optional.of(all) : Optional.empty();
            })).as("all three panelists voted").isPresent();
            assertThat(advisories.readAll(Template.of(VoteCapability.Ballot.class), 100).stream()
                    .map(VoteCapability.Ballot::voter).distinct().count())
                    .as("three distinct voter identities, not one repeated").isEqualTo(3L);

            // A Decision is computed from the tally, never written as an entry;
            // the advisory the lead publishes is the observable proof it closed.
            Optional<Safety.TravelAdvisory> advisory = await(() ->
                    trip.readAll(Template.of(Safety.TravelAdvisory.class)
                            .where("tripId", eq(TRIP))
                            .where("wave", eq(Safety.BASELINE)), 10).stream().findFirst());
            assertThat(advisory).as("the baseline advisory is published").isPresent();
            assertThat(advisory.orElseThrow().verdict()).isEqualTo("GO");
            assertThat(advisory.orElseThrow().goVotes()
                    + advisory.orElseThrow().cautionVotes()
                    + advisory.orElseThrow().avoidVotes())
                    .as("every seat is counted exactly once").isEqualTo(3);
        } finally {
            bus.peers().forEach(Peer::close);
        }
    }

    private static <T> Optional<T> first(Space space, Class<T> type) {
        return space.readAll(Template.of(type), 200).stream().findFirst();
    }

    private static <T> Optional<T> await(Supplier<Optional<T>> probe) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<T> value = probe.get();
            if (value.isPresent()) {
                return value;
            }
            Thread.sleep(250);
        }
        return probe.get();
    }
}
