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
package ai.badmonkey.agentspaces.partybus.embabel;

import ai.badmonkey.agentspaces.agent.remote.RemoteActions;
import ai.badmonkey.agentspaces.embabel.EmbabelRemoteActions;
import ai.badmonkey.agentspaces.partybus.PartyBus;
import ai.badmonkey.agentspaces.partybus.domain.Research.ReviewDigest;
import ai.badmonkey.agentspaces.partybus.domain.Research.ReviewRequest;
import ai.badmonkey.agentspaces.partybus.domain.Research.SightseeingOptions;
import ai.badmonkey.agentspaces.partybus.domain.Research.SightseeingRequest;
import ai.badmonkey.agentspaces.partybus.domain.Trip.PointOfInterest;
import ai.badmonkey.agentspaces.partybus.domain.Trip.TravelBrief;
import ai.badmonkey.agentspaces.partybus.fleet.PartyBusPlanner;
import ai.badmonkey.agentspaces.partybus.sources.Sources;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.config.annotation.EnableAgents;
import com.embabel.agent.core.AgentPlatform;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Party Bus as a Tripper-style Embabel application. This source set compiles
 * only under the {@code embabel} Maven profile, against the same Embabel
 * starter Tripper uses, and it is the part of the project that cannot be
 * verified in a network-restricted build; treat it as the shape of the
 * integration and run it from a laptop with Maven Central and the API keys.
 *
 * <p>Two things happen here. First, the bus boards inside the Spring Boot
 * application, exactly as the standalone demo boards it. Second, the fleet's
 * AgentCards come back in as planner actions: {@link PartyBusPlanner#bridge}
 * generates an {@code @Agent} with one {@code @Action} per remote card and
 * {@code deployTo(platform)} hands it to Embabel's {@code AgentPlatform}, so
 * the GOAP planner sees the sightseeing scout, the review reader, and every
 * other specialist as actions it can chain. The local {@link PartyBusPlannerAgent}
 * supplies the two actions the fleet cannot: turning a brief into the first
 * request, and turning the top sight into a review request. The goal, a
 * {@code ReviewDigest} from a {@code TravelBrief}, is reachable only through
 * the fleet, which is the whole point.
 */
@SpringBootApplication
@EnableAgents
public class PartyBusEmbabelApplication {

    /**
     * Runs the application.
     *
     * @param args passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(PartyBusEmbabelApplication.class, args);
    }

    /** Boards the bus and bridges its cards into the platform once Spring is up. */
    @Bean
    CommandLineRunner boardTheBus(AgentPlatform platform) {
        return args -> {
            Sources sources = Sources.fromEnvironment();
            PartyBus.Seating bus = PartyBus.board(sources, 7700, 7590);
            RemoteActions remote = PartyBusPlanner.remoteActions(bus.plannerPeer());
            PartyBusPlanner.awaitActions(remote, 12, Duration.ofSeconds(30));
            EmbabelRemoteActions bridge = PartyBusPlanner.bridge(remote);
            if (!bridge.deployTo(platform)) {
                throw new IllegalStateException("the fleet agent did not deploy; are the cards in?");
            }
        };
    }

    /**
     * The planner's own two actions. Everything between them is a remote scout
     * the bridge generated from an AgentCard.
     */
    @Agent(description = "Plans a trip with the Party Bus fleet's specialists")
    public static class PartyBusPlannerAgent {

        @Action
        public SightseeingRequest askForSights(TravelBrief brief) {
            return new SightseeingRequest(brief.tripId(), "planner sights " + brief.to(),
                    brief.to(), brief.brief(), brief.departureDate(), brief.returnDate(),
                    PartyBus.party(brief.tripId()).contribution());
        }

        @Action
        public ReviewRequest askForReviewsOfTheTopSight(SightseeingOptions sights) {
            PointOfInterest top = sights.pointsOfInterest().isEmpty()
                    ? new PointOfInterest("the old town", "", "", "", "")
                    : sights.pointsOfInterest().get(0);
            return new ReviewRequest(sights.tripId(), "planner reviews " + top.name(),
                    top.name(), top.location());
        }

        @AchievesGoal(description = "Know what travelers say about the trip's top sight")
        @Action
        public ReviewDigest done(ReviewDigest digest) {
            return digest;
        }
    }
}
