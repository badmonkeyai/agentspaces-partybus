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

import ai.badmonkey.agentspaces.common.id.PeerId;
import ai.badmonkey.agentspaces.measure.Measure;
import ai.badmonkey.agentspaces.measure.Measurement;
import ai.badmonkey.agentspaces.measure.Wait;
import ai.badmonkey.agentspaces.partybus.fleet.PartyBusFleet.Peer;
import ai.badmonkey.agentspaces.peering.membership.GroupMembership;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The fleet tests' measured waits (srad/feature-measurements.md): each polls every 250 ms, as the
 * hand-written waits did, records how long it took under its metric id, and fails at its ceiling.
 */
final class Waits {

    /** The ceiling policy's floor: a liveness ceiling is at least 30 s. */
    static final Duration FLOOR = Duration.ofSeconds(30);

    private Waits() {
    }

    /**
     * Waits for a probe to return a value.
     *
     * @return the value, present; past the ceiling the test fails with the metric named
     */
    static <T> Optional<T> await(String metric, Supplier<Optional<T>> probe, Duration ceiling) {
        // fm: (a) ceiling as given, at least 30 s (policy)
        Duration cap = ceiling.compareTo(FLOOR) < 0 ? FLOOR : ceiling;
        T value = Measure.awaitValue(metric, cap, probe, Wait.with().poll(Duration.ofMillis(250)));
        return Optional.of(value);
    }

    /**
     * Waits until the turret's membership knows every other seat: the formation a fixed 2.5 s sleep used to
     * stand for. A write made before then still arrives by anti-entropy; this keeps the first stage of a
     * run from paying for it.
     */
    static void formed(Peer turret, java.util.List<Peer> seats) {
        Set<PeerId> others = seats.stream().map(Peer::peerId).filter(id -> !id.equals(turret.peerId()))
                .collect(Collectors.toSet());
        GroupMembership membership = turret.runtime().membership();
        Measurement m = Measure.await("partybus.fleet.formed", Duration.ofSeconds(60),
                () -> membership.allMembers().stream().map(GroupMembership.Member::id).collect(Collectors.toSet())
                        .containsAll(others),
                Wait.with().poll(Duration.ofMillis(250)).diagnostics(() -> "the turret knows "
                        + membership.allMembers().size() + " of " + others.size() + " other seats"));
        System.out.println("PARTYBUS-MEASURE partybus.fleet.formed " + Math.round(m.value()) + " ms ("
                + others.size() + " seats)");
    }
}
