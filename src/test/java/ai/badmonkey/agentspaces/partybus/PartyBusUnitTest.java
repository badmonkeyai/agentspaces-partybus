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

import ai.badmonkey.agentspaces.partybus.agents.Planners;
import ai.badmonkey.agentspaces.partybus.agents.SafetyPanel;
import ai.badmonkey.agentspaces.partybus.domain.Itinerary.DaySlot;
import ai.badmonkey.agentspaces.partybus.domain.Research.HotelOptions;
import ai.badmonkey.agentspaces.partybus.domain.Safety.DangerAssessment;
import ai.badmonkey.agentspaces.partybus.domain.Trip.TravelBrief;
import ai.badmonkey.agentspaces.partybus.fleet.VacationSimulator;
import ai.badmonkey.agentspaces.partybus.sources.LiveSources;
import ai.badmonkey.agentspaces.partybus.sources.RecordedSources;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pure parts: pricing, verdicts, pacing, sources, and the brief's calendar. */
class PartyBusUnitTest {

    @Test
    void liveSourcesInsistOnTheirKeysLikeTripperDoes() {
        assertThatThrownBy(() -> LiveSources.fromEnvironment(Map.of()))
                .isInstanceOf(LiveSources.MissingApiKeyException.class)
                .hasMessageContaining("OPENAI_API_KEY")
                .hasMessageContaining("BRAVE_API_KEY")
                .hasMessageContaining("PARTYBUS_SOURCES=recorded");
        assertThatThrownBy(() -> LiveSources.fromEnvironment(Map.of("OPENAI_API_KEY", "sk-x")))
                .hasMessageContaining("BRAVE_API_KEY")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Missing: OPENAI"));
        assertThat(LiveSources.fromEnvironment(Map.of("OPENAI_API_KEY", "sk-x",
                "BRAVE_API_KEY", "b-x")).name()).isEqualTo("live");
    }

    @Test
    void theRecordingAnswersByTagThenByBaseTagAndCountsLookups() {
        RecordedSources sources = RecordedSources.lisbon();
        HotelOptions lisbon = sources.structured("hotels:Lisbon, Portugal", "", HotelOptions.class);
        HotelOptions anywhere = sources.structured("hotels:Porto", "", HotelOptions.class);
        assertThat(lisbon.options()).hasSize(4);
        assertThat(anywhere.options()).isEqualTo(lisbon.options());
        DangerAssessment calm = sources.structured("danger:news:travel safety", "",
                DangerAssessment.class);
        DangerAssessment unrest = sources.structured("danger:video:protest unrest", "",
                DangerAssessment.class);
        assertThat(calm.danger()).isLessThan(SafetyPanel.CAUTION_AT);
        assertThat(unrest.danger()).isGreaterThanOrEqualTo(SafetyPanel.AVOID_AT);
        assertThat(sources.lookups("hotels:Lisbon, Portugal")).isEqualTo(1);
        assertThat(sources.search("flights Boston to Lisbon", 2)).hasSize(2)
                .allSatisfy(link -> assertThat(link.url()).startsWith("https://"));
        assertThat(sources.places("Lisbon, Portugal", "hospital", 2)).hasSize(2);
    }

    @Test
    void theBriefKnowsItsDays() {
        TravelBrief brief = PartyBus.brief("t");
        assertThat(brief.days()).hasSize(7).startsWith("2026-10-03").endsWith("2026-10-09");
        assertThat(brief.contribution()).contains("Boston").contains("Lisbon").contains("200.0");
    }

    @Test
    void thePanelWeighsTheChannelsItDidNotWatchLessAndVerdictsFollowTheThresholds() {
        assertThat(SafetyPanel.trust("news", "news")).isEqualTo(1.0);
        assertThat(SafetyPanel.trust("news", "video")).isLessThan(SafetyPanel.trust("news", "social"));
        assertThat(SafetyPanel.verdictFor(SafetyPanel.AVOID_AT)).isEqualTo("AVOID");
        assertThat(SafetyPanel.verdictFor(SafetyPanel.CAUTION_AT)).isEqualTo("CAUTION");
        assertThat(SafetyPanel.verdictFor(SafetyPanel.CAUTION_AT - 1)).isEqualTo("GO");
        // The recorded protest wave: the video seat alone reaches AVOID; the panel lands on CAUTION.
        double newsSeat = Math.max(55, Math.max(78 * SafetyPanel.trust("news", "video"),
                48 * SafetyPanel.trust("news", "social")));
        double videoSeat = Math.max(78, Math.max(55 * SafetyPanel.trust("video", "news"),
                48 * SafetyPanel.trust("video", "social")));
        assertThat(SafetyPanel.verdictFor(newsSeat)).isEqualTo("CAUTION");
        assertThat(SafetyPanel.verdictFor(videoSeat)).isEqualTo("AVOID");
    }

    @Test
    void theAuctionPricesTheDayFromEachPlannersPointOfView() {
        List<String> interests = List.of("food and wine", "architecture and history", "photography");
        DaySlot full = new DaySlot("t", "2026-10-05", "Lisbon, Portugal", interests, "FULL", 0);
        DaySlot easy = new DaySlot("t", "2026-10-03", "Lisbon, Portugal", interests, "EASY", 0);
        DaySlot rest = new DaySlot("t", "2026-10-06", "Lisbon, Portugal", interests, "REST", 1);
        DaySlot stormy = new DaySlot("t", "2026-10-04", "Lisbon, Portugal", interests, "FULL", 1);
        Planners.DiningPlanner dining = new Planners.DiningPlanner();
        Planners.SightsPlanner sights = new Planners.SightsPlanner();
        Planners.PhotoWalkPlanner photo = new Planners.PhotoWalkPlanner();
        Planners.RestPlanner restPlanner = new Planners.RestPlanner();

        assertThat(winner(full, dining, sights, photo, restPlanner)).isEqualTo("sights");
        assertThat(winner(easy, dining, sights, photo, restPlanner)).isEqualTo("photo");
        assertThat(winner(rest, dining, sights, photo, restPlanner)).isEqualTo("rest");
        assertThat(winner(stormy, dining, sights, photo, restPlanner)).isEqualTo("dining");
    }

    private static String winner(DaySlot slot, Planners.DiningPlanner dining,
                                 Planners.SightsPlanner sights, Planners.PhotoWalkPlanner photo,
                                 Planners.RestPlanner rest) {
        Map<String, Double> bids = Map.of("dining", dining.bid(slot), "sights", sights.bid(slot),
                "photo", photo.bid(slot), "rest", rest.bid(slot));
        return bids.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    @Test
    void theSimulatorPacesTheNextDayFromThePartysEnergy() {
        assertThat(VacationSimulator.paceFor(OptionalDouble.empty())).isEqualTo("FULL");
        assertThat(VacationSimulator.paceFor(OptionalDouble.of(72))).isEqualTo("FULL");
        assertThat(VacationSimulator.paceFor(OptionalDouble.of(55))).isEqualTo("EASY");
        assertThat(VacationSimulator.paceFor(OptionalDouble.of(31))).isEqualTo("REST");
        assertThat(VacationSimulator.lisbonWeek()).hasSize(7)
                .anySatisfy(scene -> assertThat(scene.detail()).contains("clams"));
    }
}
