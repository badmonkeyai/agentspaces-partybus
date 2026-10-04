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
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import ai.badmonkey.agentspaces.partybus.domain.Research.Link;
import ai.badmonkey.agentspaces.partybus.domain.Safety.DangerAssessment;
import ai.badmonkey.agentspaces.partybus.domain.Safety.NewsSweep;
import ai.badmonkey.agentspaces.partybus.domain.Safety.SignalReport;
import ai.badmonkey.agentspaces.partybus.domain.Safety.SocialSweep;
import ai.badmonkey.agentspaces.partybus.domain.Safety.VideoSweep;
import ai.badmonkey.agentspaces.partybus.sources.Sources;

import java.util.List;
import java.util.Objects;

import static ai.badmonkey.agentspaces.partybus.agents.Prompts.digest;

/**
 * The watchers and the analyst: the signals half of the safety pipeline.
 *
 * <p>Three watchers take sweep requests from the research bag and write
 * {@link SignalReport}s into the {@code signals} space, one per channel. The
 * news watcher reads headlines; the video watcher reads YouTube and video
 * search, which is where the front-line footage, the protest livestreams, and
 * the "is it safe to visit" vlogs live; the social watcher reads forums and
 * social chatter. The sentiment analyst reacts to every report with a
 * {@code @SpaceNotify} and writes a {@link DangerAssessment}, the entry the
 * safety panel votes on.
 *
 * <p>Nothing here decides anything. A watcher reports what it saw and the
 * analyst scores it; the verdict belongs to the panel, because a single
 * channel is exactly how a fleet ends up cancelling a trip over one viral clip.
 */
public final class Watchers {

    /** The space signal reports and assessments live in. */
    public static final String SIGNALS = "signals";

    private Watchers() {
    }

    /** Sweeps headlines. */
    @AgentSpec(name = "news-watcher",
            description = "Sweeps the latest news for a destination and reports the headlines",
            goals = {"watch the news"})
    public static final class NewsWatcher {
        private final Sources sources;

        public NewsWatcher(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = Scouts.SPACE, resultSpace = SIGNALS, lease = "1m",
                pollTimeout = "500ms")
        public SignalReport sweep(NewsSweep sweep) {
            List<Link> news = sources.news(sweep.location() + " " + sweep.topic(), 10);
            return new SignalReport(sweep.tripId(), sweep.topic(), sweep.location(), "news",
                    sweep.wave(), news.stream().map(Link::summary).toList(), news,
                    "news-watcher");
        }
    }

    /** Sweeps YouTube and video search. */
    @AgentSpec(name = "video-watcher",
            description = "Sweeps YouTube and video search for footage and vlogs about a destination",
            goals = {"watch the footage"})
    public static final class VideoWatcher {
        private final Sources sources;

        public VideoWatcher(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = Scouts.SPACE, resultSpace = SIGNALS, lease = "1m",
                pollTimeout = "500ms")
        public SignalReport sweep(VideoSweep sweep) {
            List<Link> videos = sources.videos(sweep.location() + " " + sweep.topic()
                    + " this week", 10);
            return new SignalReport(sweep.tripId(), sweep.topic(), sweep.location(), "video",
                    sweep.wave(), videos.stream().map(Link::summary).toList(), videos,
                    "video-watcher");
        }
    }

    /** Sweeps social and forum chatter. */
    @AgentSpec(name = "social-watcher",
            description = "Sweeps social media and travel forums for what visitors are saying now",
            goals = {"watch the chatter"})
    public static final class SocialWatcher {
        private final Sources sources;

        public SocialWatcher(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceTake(space = Scouts.SPACE, resultSpace = SIGNALS, lease = "1m",
                pollTimeout = "500ms")
        public SignalReport sweep(SocialSweep sweep) {
            List<Link> posts = sources.search(sweep.location() + " " + sweep.topic()
                    + " site:reddit.com OR site:x.com OR site:tripadvisor.com", 10);
            return new SignalReport(sweep.tripId(), sweep.topic(), sweep.location(), "social",
                    sweep.wave(), posts.stream().map(Link::summary).toList(), posts,
                    "social-watcher");
        }
    }

    /** Scores every report; the choreography step between watching and voting. */
    @AgentSpec(name = "sentiment-analyst",
            description = "Scores each signal report for danger to travelers and names the themes",
            goals = {"assess danger"})
    public static final class SentimentAnalyst {
        private final Sources sources;

        public SentimentAnalyst(Sources sources) {
            this.sources = Objects.requireNonNull(sources, "sources");
        }

        @SpaceNotify(space = SIGNALS, lease = "12h")
        public DangerAssessment assess(SignalReport report) {
            DangerAssessment scored = sources.structured(
                    "danger:" + report.channel() + ":" + report.topic(),
                    "You assess danger to ordinary tourists, not to journalists or soldiers. "
                    + "Score 0 (calm) to 100 (active conflict) and name the themes. Location: "
                    + report.location() + ". Channel: " + report.channel() + ".\n"
                    + digest("What the watcher found", report.sources()), DangerAssessment.class);
            return new DangerAssessment(report.tripId(), report.location(), report.channel(),
                    report.wave(), scored.danger(), scored.themes(), "sentiment-analyst");
        }
    }
}
