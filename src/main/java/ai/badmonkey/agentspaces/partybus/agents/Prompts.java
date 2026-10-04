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

import ai.badmonkey.agentspaces.partybus.domain.Research.Link;

import java.util.List;

/**
 * The prompt-building helpers every specialist shares: a digest of search
 * results the model can cite, and the persona lines Tripper gives its
 * researcher and planner.
 */
final class Prompts {

    /** Tripper's researcher persona, condensed. */
    static final String RESEARCHER = "You are a meticulous travel researcher. You cite only the "
            + "material you were given and you say plainly when it is thin.";

    /** Tripper's planner persona, condensed. */
    static final String PLANNER = "You are a warm, witty travel planner who writes for the "
            + "travelers named in the brief, keeps to the budget, and never pads.";

    private Prompts() {
    }

    /** Search results as numbered lines the model can cite by URL. */
    static String digest(String heading, List<Link> links) {
        if (links.isEmpty()) {
            return heading + ": no results\n";
        }
        StringBuilder text = new StringBuilder(heading).append(":\n");
        int i = 1;
        for (Link link : links) {
            text.append(i++).append(". ").append(link.summary()).append(" <").append(link.url())
                    .append(">\n");
        }
        return text.toString();
    }

    /** Joins a list for a prompt, or a placeholder when empty. */
    static String list(List<String> items, String whenEmpty) {
        return items.isEmpty() ? whenEmpty : String.join(", ", items);
    }
}
