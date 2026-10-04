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
 * The safety pipeline: raw signals from the news, video, and social watchers,
 * the sentiment analyst's assessment of each, and the travel advisory the
 * safety panel decides by signed quorum vote.
 *
 * <p>The verdicts are the ballot options: {@code GO}, {@code CAUTION}, and
 * {@code AVOID}. The panel is three watchers with different sources, so the
 * advisory is a decision a cross-source majority stands behind, and the ballots
 * in the space are the audit trail of who saw what and how they voted.
 */
public final class Safety {

    private Safety() {
    }

    /** The three verdicts a panelist may cast and the advisory may carry. */
    public static final List<String> VERDICTS = List.of("GO", "CAUTION", "AVOID");

    /**
     * Ask the news watcher to sweep headlines for a location. The {@code wave}
     * names the sweep ({@code baseline}, or the event id that triggered it) and
     * becomes the round the panel votes in.
     */
    public record NewsSweep(String tripId, String topic, String location, String wave) {
    }

    /** Ask the video watcher to sweep YouTube and video search for a location. */
    public record VideoSweep(String tripId, String topic, String location, String wave) {
    }

    /** Ask the social watcher to sweep social and forum chatter for a location. */
    public record SocialSweep(String tripId, String topic, String location, String wave) {
    }

    /**
     * What a watcher found on one channel: headlines and video titles with the
     * source links, ready for sentiment analysis.
     *
     * @param tripId    the trip
     * @param topic     the request topic
     * @param location  the place watched
     * @param channel   {@code news}, {@code video}, or {@code social}
     * @param headlines what was found
     * @param sources   where it was found
     * @param author    the watcher
     */
    public record SignalReport(String tripId, String topic, String location, String channel,
                               String wave, List<String> headlines, List<Research.Link> sources,
                               String author) {
    }

    /**
     * The sentiment analyst's read of one signal report: a danger score and the
     * themes behind it.
     *
     * @param tripId   the trip
     * @param location the place
     * @param channel  the channel assessed
     * @param wave     the sweep this assesses, and so the panel's voting round
     * @param danger   0 (calm) to 100 (active conflict)
     * @param themes   what drives the score
     * @param author   the analyst
     */
    public record DangerAssessment(String tripId, String location, String channel, String wave,
                                   int danger, List<String> themes, String author) {
    }

    /**
     * The panel's decided advisory for a place.
     *
     * @param tripId   the trip
     * @param location the place
     * @param wave     the sweep the panel voted on
     * @param verdict  {@code GO}, {@code CAUTION}, or {@code AVOID}
     * @param goVotes  ballots for GO
     * @param cautionVotes ballots for CAUTION
     * @param avoidVotes ballots for AVOID
     * @param reasons  the assessed themes behind the verdict
     * @param decidedBy the lead that recorded the decision
     */
    public record TravelAdvisory(String tripId, String location, String wave, String verdict,
                                 int goVotes,
                                 int cautionVotes, int avoidVotes, List<String> reasons,
                                 String decidedBy) {
    }

    /** The proposal id the panel votes under for one location, trip, and sweep. */
    public static String proposalId(String tripId, String location, String wave) {
        return "advisory:" + tripId + ":" + location + ":" + wave;
    }

    /** The name of the first, pre-departure sweep. */
    public static final String BASELINE = "baseline";
}
