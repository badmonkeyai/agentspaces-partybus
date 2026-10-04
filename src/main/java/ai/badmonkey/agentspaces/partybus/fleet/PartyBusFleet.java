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

import ai.badmonkey.agentspaces.agent.AgentBinder;
import ai.badmonkey.agentspaces.api.ad.GroupAdvertisement;
import ai.badmonkey.agentspaces.api.ad.PeerAdvertisement;
import ai.badmonkey.agentspaces.api.space.ConflictStrategyType;
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.capabilities.orderedlog.OrderedTakes;
import ai.badmonkey.agentspaces.capabilities.orderedlog.RaftLog;
import ai.badmonkey.agentspaces.capabilities.runtime.CapabilityPipes;
import ai.badmonkey.agentspaces.capabilities.runtime.CapabilityRuntime;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import ai.badmonkey.agentspaces.common.codec.CborCodec;
import ai.badmonkey.agentspaces.common.id.GroupId;
import ai.badmonkey.agentspaces.common.id.PeerId;
import ai.badmonkey.agentspaces.discovery.AdCache;
import ai.badmonkey.agentspaces.discovery.DiscoveryService;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.partybus.agents.Assemblers;
import ai.badmonkey.agentspaces.partybus.agents.SafetyPanel;
import ai.badmonkey.agentspaces.partybus.agents.Party;
import ai.badmonkey.agentspaces.partybus.agents.Planners;
import ai.badmonkey.agentspaces.partybus.agents.Scouts;
import ai.badmonkey.agentspaces.partybus.agents.Watchers;
import ai.badmonkey.agentspaces.peering.membership.GroupMembership;
import ai.badmonkey.agentspaces.peering.node.GroupRuntime;
import ai.badmonkey.agentspaces.peering.node.PeerNode;
import ai.badmonkey.agentspaces.peering.transport.TlsTcpTransport;
import ai.badmonkey.agentspaces.space.replicated.ReplicatedSpace;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The bus: fifteen peers in the {@code partybus} group, each holding a replica
 * of every space the trip uses and hosting a few agents. There is no server in
 * this picture. The first peer listens on a TLS port; every other peer is
 * handed that address as a seed, and SWIM membership plus gossip do the rest.
 *
 * <p>The spaces, and the coordination type each one runs:
 * <ul>
 *   <li>{@code trip}: briefs, travelers, days, events, plans, advisories, decisions.
 *       LEASE_RACE; mostly written and watched.</li>
 *   <li>{@code research}: the typed request bag the scouts and advisors drain.
 *       LEASE_RACE takes: kill-tolerant replicated workers.</li>
 *   <li>{@code signals}: watcher reports and the analyst's assessments.
 *       Choreography by {@code notify}.</li>
 *   <li>{@code advisories}: the safety panel's proposals and signed ballots
 *       ({@code aspace:cap/vote}, QUORUM).</li>
 *   <li>{@code decisions}: the travelers' proposals and signed ballots (the same
 *       capability, a different electorate).</li>
 *   <li>{@code slots}: the day slots the activity planners bid for. AUCTION.</li>
 *   <li>{@code bookings}: reservation orders committed exactly once through
 *       {@code aspace:cap/ordered-log}.</li>
 *   <li>{@code fleet-control} and {@code c2-audit}: the turret's directives and
 *       its signed audit trail.</li>
 * </ul>
 * The party's energy and spend travel on no space at all: they are push-sum
 * epochs ({@code aspace:cap/aggregate}) over the capability pipes.
 *
 * <p>One clock drives it all. Every capability a peer offers is registered on
 * the peer's {@link CapabilityRuntime}, which rides the peer tick, so the
 * aggregate, the votes, and the clerks' Raft logs advance on the same
 * {@link #CLERK_TICK} that drives membership and anti-entropy, and every one of
 * them is advertised for the fleet to discover.
 */
public final class PartyBusFleet {

    /** The group name. */
    public static final String GROUP = "partybus";
    /** The trip space. */
    public static final String TRIP = Party.TRIP;
    /** The research bag. */
    public static final String RESEARCH = Scouts.SPACE;
    /** The signals space. */
    public static final String SIGNALS = Watchers.SIGNALS;
    /** The safety panel's vote space. */
    public static final String ADVISORIES = SafetyPanel.ADVISORIES;
    /** The travelers' vote space. */
    public static final String DECISIONS = Party.DECISIONS;
    /** The auction space. */
    public static final String SLOTS = Planners.SLOTS;
    /** The ordered bookings space. */
    public static final String BOOKINGS = Assemblers.BOOKINGS;
    /** The turret's directive space. */
    public static final String CONTROL = "fleet-control";
    /** The turret's audit space. */
    public static final String AUDIT = "c2-audit";

    /** Every space a peer replicates, in creation order. */
    public static final List<String> SPACES = List.of(TRIP, RESEARCH, SIGNALS, ADVISORIES,
            DECISIONS, SLOTS, BOOKINGS, CONTROL, AUDIT);

    private static final String HOST = "127.0.0.1";

    private PartyBusFleet() {
    }

    /** The group advertisement every member derives the same self-certifying id from. */
    public static GroupAdvertisement group() {
        GroupId groupId = GroupId.fromFounding("partybus-v1".getBytes(StandardCharsets.UTF_8));
        return new GroupAdvertisement("aspace://" + groupId.value(),
                PeerIdentity.generate().peerId(), groupId, Instant.EPOCH, Duration.ofDays(365),
                GROUP, GroupAdvertisement.MembershipPolicy.OPEN, ConflictStrategyType.LEASE_RACE,
                GroupAdvertisement.GossipParameters.defaults());
    }

    /** One seat on the bus: a peer with its replicas, binder, and capabilities. */
    public static final class Peer implements AutoCloseable {
        private final String name;
        private final PeerIdentity identity;
        private final PeerNode node;
        private final GroupRuntime runtime;
        private final DiscoveryService discovery;
        private final Map<String, ReplicatedSpace> spaces;
        private final AgentBinder binder;
        private final CborCodec codec;
        private final CapabilityPipes pipes;
        private final CapabilityRuntime capabilities;
        private final PushSumAggregate aggregate;
        private final VoteCapability advisoryVote;
        private final VoteCapability partyVote;
        private final List<AutoCloseable> agents = new CopyOnWriteArrayList<>();
        private final int port;

        Peer(String name, PeerIdentity identity, PeerNode node, GroupRuntime runtime,
             DiscoveryService discovery, Map<String, ReplicatedSpace> spaces, AgentBinder binder,
             CborCodec codec, CapabilityPipes pipes, CapabilityRuntime capabilities,
             PushSumAggregate aggregate, VoteCapability advisoryVote, VoteCapability partyVote,
             int port) {
            this.name = name;
            this.identity = identity;
            this.node = node;
            this.runtime = runtime;
            this.discovery = discovery;
            this.spaces = spaces;
            this.binder = binder;
            this.codec = codec;
            this.pipes = pipes;
            this.capabilities = capabilities;
            this.aggregate = aggregate;
            this.advisoryVote = advisoryVote;
            this.partyVote = partyVote;
            this.port = port;
        }

        public String name() {
            return name;
        }

        public PeerIdentity identity() {
            return identity;
        }

        public PeerId peerId() {
            return identity.peerId();
        }

        public PeerNode node() {
            return node;
        }

        public GroupRuntime runtime() {
            return runtime;
        }

        public DiscoveryService discovery() {
            return discovery;
        }

        public int port() {
            return port;
        }

        /** A replica by space name. */
        public ReplicatedSpace space(String spaceName) {
            ReplicatedSpace space = spaces.get(spaceName);
            if (space == null) {
                throw new IllegalArgumentException("no space '" + spaceName + "'; spaces: "
                        + spaces.keySet());
            }
            return space;
        }

        /** All replicas, by name. */
        public Map<String, ReplicatedSpace> spaces() {
            return spaces;
        }

        public AgentBinder binder() {
            return binder;
        }

        public CborCodec codec() {
            return codec;
        }

        public CapabilityPipes pipes() {
            return pipes;
        }

        /** The capability runtime: drives and advertises every capability this peer offers. */
        public CapabilityRuntime capabilities() {
            return capabilities;
        }

        /** The push-sum capability, ticked on the peer tick. */
        public PushSumAggregate aggregate() {
            return aggregate;
        }

        /** The vote capability over the {@code advisories} space. */
        public VoteCapability advisoryVote() {
            return advisoryVote;
        }

        /** The vote capability over the {@code decisions} space. */
        public VoteCapability partyVote() {
            return partyVote;
        }

        /**
         * This peer's vote capability over a vote space. The voter is the peer's
         * own agent, the only identity the space will attribute its writes to; a
         * ballot naming anyone else is dropped as forged at every replica, so
         * distinct voters are distinct peers (QA4 A4-4). One peer therefore has
         * one vote per space, registered and advertised when the peer started.
         *
         * @param spaceName {@link #ADVISORIES} or {@link #DECISIONS}
         * @return the capability
         */
        public VoteCapability vote(String spaceName) {
            return switch (spaceName) {
                case ADVISORIES -> advisoryVote;
                case DECISIONS -> partyVote;
                default -> throw new IllegalArgumentException("no vote over '" + spaceName
                        + "'; vote spaces: " + List.of(ADVISORIES, DECISIONS));
            };
        }

        /** Binds an annotated agent to this peer's spaces and publishes its card. */
        public AgentBinder.Bound bind(Object agent) {
            AgentBinder.Bound bound = binder.bind(agent);
            agents.add(bound);
            return bound;
        }

        /** Registers a loop agent to be closed with the peer. */
        public <T extends AutoCloseable> T run(T agent) {
            agents.add(agent);
            return agent;
        }

        @Override
        public void close() {
            capabilities.close();
            for (AutoCloseable agent : agents) {
                try {
                    agent.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
            binder.close();
            spaces.values().forEach(ReplicatedSpace::close);
            node.close();
        }
    }

    /**
     * Starts one peer: joins the group over TCP, builds every space, and wires
     * the capabilities.
     *
     * @param name     the peer's name (its agents' host)
     * @param port     the TCP port to listen on
     * @param seedPort an existing member's port, or 0 for the first member
     * @return the running peer
     * @throws Exception if the port cannot be bound
     */
    public static Peer startPeer(String name, int port, int seedPort) throws Exception {
        Objects.requireNonNull(name, "name");
        PeerIdentity identity = PeerIdentity.generate();
        // Channel authentication (SPEC §5.6): TLS 1.3 with identity-endorsed
        // certificates, so the channel vouches for the sender and frames travel
        // unsigned. Fifteen peers times nine spaces is thousands of gossip frames
        // a second; signing each one would spend the machine on Ed25519.
        PeerNode node = PeerNode.builder(identity)
                .channelAuth(PeerNode.ChannelAuth.ATTESTED).build();
        node.listen(new TlsTcpTransport(identity), HOST + ":" + port);
        List<PeerAdvertisement.Endpoint> seeds = seedPort == 0 ? List.of()
                : List.of(new PeerAdvertisement.Endpoint("tls", HOST + ":" + seedPort, 0));
        GroupRuntime runtime = node.joinGroup(group(),
                new GroupMembership.Config(Duration.ofSeconds(30), Duration.ofSeconds(2), 2),
                seeds);
        CborCodec codec = CborCodec.defaultCodec();
        InstantSource clock = InstantSource.system();
        DiscoveryService discovery = new DiscoveryService(runtime, new AdCache(codec, clock),
                codec, identity.peerId());

        Map<String, ReplicatedSpace> spaces = new LinkedHashMap<>();
        for (String spaceName : SPACES) {
            ReplicatedSpace.Builder builder = ReplicatedSpace.builder(runtime, spaceName,
                    identity, name).settleWindow(Duration.ofMillis(200));
            if (spaceName.equals(SLOTS)) {
                // The bid comes from the planner's @BidFunction at bind time; a bid
                // function is single-assignment per space per peer (QA4 A4-8), so a
                // placeholder here would be the first bidder and the planner refused.
                builder.strategy(ConflictStrategyType.AUCTION);
            }
            spaces.put(spaceName, builder.build());
        }

        AgentBinder binder = new AgentBinder(identity, runtime.id(), discovery, clock);
        spaces.forEach(binder::space);

        CapabilityPipes pipes = new CapabilityPipes(runtime, codec);
        PushSumAggregate aggregate = new PushSumAggregate(pipes, runtime.sampler(),
                identity.peerId(), codec, clock);
        VoteCapability advisoryVote = new VoteCapability(spaces.get(ADVISORIES),
                identity.agent(name), identity.peerId(), clock);
        VoteCapability partyVote = new VoteCapability(spaces.get(DECISIONS),
                identity.agent(name), identity.peerId(), clock);
        // What the annotations cast and watch with: @Ballot/@OnDecision on the two
        // vote spaces, and a Contribution return on the aggregate.
        binder.vote(ADVISORIES, advisoryVote).vote(DECISIONS, partyVote).aggregate(aggregate);

        // One clock: the runtime rides the peer tick, so registering is all it
        // takes for the aggregate to gossip and for every capability to be found.
        CapabilityRuntime capabilities = new CapabilityRuntime(runtime, discovery, identity);
        capabilities.register(aggregate);
        capabilities.register(advisoryVote);
        capabilities.register(partyVote);
        node.startTicking(CLERK_TICK);
        return new Peer(name, identity, node, runtime, discovery, spaces, binder, codec, pipes,
                capabilities, aggregate, advisoryVote, partyVote, port);
    }

    /** One clerk's seat at the booking desk: its peer, its Raft member, its take coordinator. */
    public record Clerk(Peer peer, RaftLog raft, OrderedTakes ordered) {
    }

    /**
     * The peer tick, and so the cadence every capability is driven at, the
     * clerks' Raft logs included. Raft's election timer counts tick rounds, so this is
     * also what sets its election timeout; the runtime tells each log the
     * cadence it really runs at, so the leader lease it advertises
     * ({@code electionTimeout × cadence}) describes the timeout the log really
     * has (QA3 A3-4).
     */
    static final Duration CLERK_TICK = Duration.ofMillis(300);

    /**
     * Forms the booking quorum: a Raft group among the clerk peers, an
     * {@link OrderedTakes} coordinator per clerk over its {@code bookings}
     * replica, and a running clerk on each.
     *
     * @param clerks the clerk peers (three is the right number)
     * @return the seated clerks, in the same order
     */
    public static List<Clerk> formBookingQuorum(List<Peer> clerks) {
        List<PeerId> members = clerks.stream().map(Peer::peerId).toList();
        List<Clerk> seated = new ArrayList<>();
        int i = 0;
        for (Peer clerk : clerks) {
            // The log and its coordinator, wired together. Registering the log
            // drives it on the peer tick and advertises it, role and leader lease
            // included; the runtime declares the cadence, so the lease is truthful.
            OrderedTakes ordered = OrderedTakes.over(clerk.pipes(), clerk.codec(), clerk.identity(),
                    clerk.name(), members, InstantSource.system(), 7000L + i++, clerk.space(BOOKINGS));
            RaftLog raft = ordered.raft();
            clerk.capabilities().register(raft);
            // The clerk is one @OrderedTake method; the binder owns the take loop.
            clerk.binder().ordered(BOOKINGS, ordered);
            clerk.bind(new Assemblers.BookingClerk(raft, clerk.name()));
            seated.add(new Clerk(clerk, raft, ordered));
        }
        return seated;
    }
}
