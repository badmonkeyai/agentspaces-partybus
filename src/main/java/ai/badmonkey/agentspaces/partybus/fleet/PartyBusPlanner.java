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

import ai.badmonkey.agentspaces.agent.remote.RemoteAction;
import ai.badmonkey.agentspaces.agent.remote.RemoteActions;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.embabel.EmbabelRemoteActions;
import ai.badmonkey.agentspaces.partybus.domain.Research;
import ai.badmonkey.agentspaces.partybus.domain.Research.ReviewRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.SightseeingOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.SightseeingRequest;
import ai.badmonkey.agentspaces.partybus.domain.Safety;
import ai.badmonkey.agentspaces.partybus.domain.Trip.PointOfInterest;
import ai.badmonkey.agentspaces.partybus.domain.Trip.TravelBrief;
import ai.badmonkey.agentspaces.partybus.domain.Trip.Travelers;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The planner's view of the bus: every scout's AgentCard, brought back in as
 * a typed action the Embabel GOAP planner can chain (SPEC §10.6).
 *
 * <p>Tripper's planner sequences {@code @Action}s inside one JVM. Party Bus
 * lets it keep doing that while the actions themselves live on other peers.
 * {@link #remoteActions(PartyBusFleet.Peer)} watches the group's ad-cache and
 * exposes one remote action per foreign card and (consumed, produced) pair, and
 * {@link #bridge} hands those to {@code EmbabelRemoteActions}, which generates
 * a real {@code @Agent} class with one {@code @Action} method per remote action
 * (the planner conditions on method signatures, so they have to be real).
 * Invoking a generated method writes the request entry into the research bag
 * and awaits the correlated result: choreography over the space, not RPC, so
 * whichever scout takes the request answers it, and a scout that dies mid-way
 * hands the request to the next.
 *
 * <p>With the real Embabel framework on the classpath ({@code -Pembabel}),
 * {@code bridge.deployTo(agentPlatform)} puts the generated agent where Tripper's
 * own agent lives and the GOAP planner plans across the fleet. Without it, the
 * same generated agent drives the small breadth-first planner below, which is
 * enough to show the property that matters: a goal the planner provably cannot
 * reach with its local actions becomes reachable, and is reached, the moment
 * the fleet's cards are in the action set.
 */
public final class PartyBusPlanner {

    /** How long a remote action waits for its scout. */
    public static final Duration INVOKE_TIMEOUT = Duration.ofSeconds(60);

    private PartyBusPlanner() {
    }

    /**
     * The registry of remote actions over a peer's discovery and spaces, with
     * every request type routed to the research bag and every result awaited
     * there (sweeps excepted: watchers write their reports into {@code signals}).
     *
     * @param planner the peer the planner runs on
     * @return the registry
     */
    public static RemoteActions remoteActions(PartyBusFleet.Peer planner) {
        Objects.requireNonNull(planner, "planner");
        Map<String, Space> spaces = Map.of(PartyBusFleet.RESEARCH,
                planner.space(PartyBusFleet.RESEARCH), PartyBusFleet.SIGNALS,
                planner.space(PartyBusFleet.SIGNALS));
        RemoteActions remote = new RemoteActions(planner.discovery(), planner.peerId(), spaces)
                .writeLease(Lease.of(Duration.ofHours(1)));
        for (Class<?> request : REQUEST_TYPES) {
            remote.route(request, PartyBusFleet.RESEARCH);
        }
        for (Class<?> result : RESULT_TYPES) {
            remote.resultsIn(result, PartyBusFleet.RESEARCH);
        }
        for (Class<?> sweep : List.of(Safety.NewsSweep.class, Safety.VideoSweep.class,
                Safety.SocialSweep.class)) {
            remote.route(sweep, PartyBusFleet.RESEARCH);
        }
        remote.resultsIn(Safety.SignalReport.class, PartyBusFleet.SIGNALS);
        return remote;
    }

    /** The request types the bag carries. */
    public static final List<Class<?>> REQUEST_TYPES = List.of(Research.FlightRequest.class,
            Research.HotelRequest.class, Research.CarRequest.class,
            Research.SightseeingRequest.class, Research.RestaurantRequest.class,
            Research.DietRequest.class, Research.FoodieRequest.class, Research.PhotoRequest.class,
            Research.ReviewRequest.class, Research.AccessibilityRequest.class,
            Research.HospitalRequest.class, Research.NextDestinationRequest.class);

    /** The result types the bag carries back. */
    public static final List<Class<?>> RESULT_TYPES = List.of(Research.FlightOptions.class,
            Research.HotelOptions.class, Research.CarOptions.class,
            Research.SightseeingOptions.class, Research.RestaurantOptions.class,
            Research.DietOptions.class, Research.FoodieOptions.class, Research.PhotoSet.class,
            Research.ReviewDigest.class, Research.AccessibilityGuide.class,
            Research.HospitalList.class, Research.NextDestinationIdeas.class);

    /**
     * The Embabel bridge: generates the fleet agent over the registry.
     *
     * @param remote the registry
     * @return the bridge; {@code agentInstance()} is the generated agent,
     *         {@code deployTo(platform)} hands it to a real Embabel platform
     */
    public static EmbabelRemoteActions bridge(RemoteActions remote) {
        return new EmbabelRemoteActions(remote, INVOKE_TIMEOUT, "partyBusFleet",
                "The Party Bus fleet's advertised skills: scouts, advisors, watchers");
    }

    /**
     * Waits until at least {@code atLeast} remote actions are available, as the
     * cards gossip in.
     *
     * @return the number available when the wait ended
     */
    public static int awaitActions(RemoteActions remote, int atLeast, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && remote.available().size() < atLeast) {
            Thread.sleep(200);
        }
        return remote.available().size();
    }

    // ------------------------------------------------------------ the planner

    /** One planning step: a typed action, local or remote. */
    public record Step(String name, Class<?> in, Class<?> out, Function<Object, Object> run) {
    }

    /**
     * The planner's local repertoire: what a lone Tripper-style agent can do
     * with a brief before any scout is involved. Deliberately unable to reach a
     * {@code ReviewDigest} on its own.
     */
    public static List<Step> localSteps(Travelers party) {
        return List.of(
                new Step("askForSights", TravelBrief.class, SightseeingRequest.class, in -> {
                    TravelBrief brief = (TravelBrief) in;
                    return new SightseeingRequest(brief.tripId(), "planner sights " + brief.to(),
                            brief.to(), brief.brief(), brief.departureDate(), brief.returnDate(),
                            party.contribution());
                }),
                new Step("askForReviewsOfTheTopSight", SightseeingOptions.class,
                        ReviewRequest.class, in -> {
                    SightseeingOptions sights = (SightseeingOptions) in;
                    PointOfInterest top = sights.pointsOfInterest().isEmpty()
                            ? new PointOfInterest("the old town", "", "", "", "")
                            : sights.pointsOfInterest().get(0);
                    return new ReviewRequest(sights.tripId(), "planner reviews " + top.name(),
                            top.name(), top.location());
                }));
    }

    /**
     * The registry's remote actions as steps, straight from the
     * framework-neutral half of the bridge. This is what a planner without
     * Embabel on its classpath plans over; with Embabel present,
     * {@link #fleetSteps(Object)} exposes the same actions as the generated
     * agent's {@code @Action} methods.
     */
    public static List<Step> remoteSteps(RemoteActions remote) {
        List<Step> steps = new ArrayList<>();
        for (RemoteAction action : remote.available()) {
            steps.add(new Step(action.name(), action.inputType(), action.outputType(),
                    input -> action.invoke(input, INVOKE_TIMEOUT).orElseThrow(() ->
                            new IllegalStateException(action.name() + " produced no result within "
                                    + INVOKE_TIMEOUT))));
        }
        return steps;
    }

    /** The generated agent's {@code @Action} methods as steps. */
    public static List<Step> fleetSteps(Object fleetAgent) {
        List<Step> steps = new ArrayList<>();
        for (Method method : fleetAgent.getClass().getDeclaredMethods()) {
            if (method.getParameterCount() != 1 || !isAction(method)) {
                continue;
            }
            steps.add(new Step(method.getName(), method.getParameterTypes()[0],
                    method.getReturnType(), input -> {
                try {
                    return method.invoke(fleetAgent, input);
                } catch (ReflectiveOperationException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    throw new IllegalStateException(method.getName() + " failed: "
                            + cause.getMessage(), cause);
                }
            }));
        }
        return steps;
    }

    private static boolean isAction(Method method) {
        for (var annotation : method.getAnnotations()) {
            if (annotation.annotationType().getName()
                    .equals("com.embabel.agent.api.annotation.Action")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Breadth-first search for the shortest typed chain from {@code start} to a
     * {@code goal}: the stand-in for GOAP when the real planner is absent.
     */
    public static Optional<List<Step>> plan(Class<?> start, Class<?> goal, List<Step> actions) {
        record Node(Class<?> type, List<Step> path) {
        }
        Deque<Node> frontier = new ArrayDeque<>();
        frontier.add(new Node(start, List.of()));
        Set<Class<?>> visited = new HashSet<>();
        visited.add(start);
        while (!frontier.isEmpty()) {
            Node node = frontier.poll();
            if (goal.isAssignableFrom(node.type())) {
                return Optional.of(node.path());
            }
            for (Step step : actions) {
                if (step.in().isAssignableFrom(node.type()) && visited.add(step.out())) {
                    List<Step> path = new ArrayList<>(node.path());
                    path.add(step);
                    frontier.add(new Node(step.out(), path));
                }
            }
        }
        return Optional.empty();
    }

    /** Runs a plan: each step's output is the next step's input. */
    public static Object execute(List<Step> plan, Object input) {
        Object current = input;
        for (Step step : plan) {
            current = step.run().apply(current);
        }
        return current;
    }
}
