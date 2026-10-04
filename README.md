# AgentSpaces Party Bus

*Tripper planned your trip. Party Bus brings the friends.*

Party Bus takes the [Embabel travel planner](https://github.com/embabel/tripper)
and puts it on a bus. Tripper is one agent in one JVM: it finds points of
interest, researches them in parallel, writes a plan, and books the stays.
Party Bus keeps Tripper's domain model and its planner, and hands the work to a
fleet of thirty-four agents who coordinate through shared spaces with no
broker, no queue, and nobody in charge. Scouts drain a bag of typed research
tasks. Activity planners bid for every day of the trip in an auction. A safety
panel decides travel advisories by signed quorum vote after watching the news,
YouTube, and the forums. The travelers themselves have seats: they vote on where
to go next and report how tired they are, and the fleet paces the trip from
their gossip. Booking clerks confirm reservations exactly once through an
ordered log. An operator steers the whole thing from a command-and-control
turret, and a vacation simulator drives the fleet through a week in Lisbon
that goes wrong on purpose.

It is a standalone Maven project on the published AgentSpaces libraries. Every
coordination type AgentSpaces offers appears once, on the job it fits best, and
the whole thing runs on a laptop in about ninety seconds.

## The week

Three friends fly Boston to Lisbon for a week on two hundred dollars a day.
Marco eats for a living and has a shellfish allergy he ignores. Priya is an
architecture historian with a camera. Dee wants a pool, a rooftop, and no steep
hills. The bus researches, plans, and books the trip, then the week happens:

```
2026-10-03  pace EASY  energy 70
   -> PHOTO_WALK by photo-walk-planner: Golden-hour walk with a 4-shot list
2026-10-04  pace FULL  energy 51
   !! WEATHER: Atlantic squall parks over the city until mid-afternoon.
   -> DINING by dining-planner: Market morning, long lunch, dinner at Taberna da Rua das Flores
2026-10-05  pace EASY  energy 32
   !! ILLNESS: Marco ordered the clams. Marco regrets the clams.
   -> SIGHTS by sights-planner: Sights day: Sintra and Pena Palace and the streets around it
2026-10-06  pace REST  energy 28
   !! PROTEST: Transport unions march on the Assembleia; Baixa is sealed off.
   -> REST by rest-planner: Rest day: pool, siesta, one gentle dinner
2026-10-07  pace REST  energy 24
   !! STRIKE: Metro and ferry strike; the tram to Belém is a rumor.
   -> DINING by dining-planner: Market morning, long lunch, dinner at Taberna da Rua das Flores

== the week, as the space remembers it ==
advisory [baseline] GO  GO=3 CAUTION=0 AVOID=0
advisory [lisbon-2026-d3-protest] CAUTION  GO=0 CAUTION=2 AVOID=1
booked lisbon-2026-stay-1 PB-FC5A3CA5 by clerk-c at log index 1
booked lisbon-2026-stay-2 PB-643B6984 by clerk-c at log index 3
next time: Porto and the Douro  [Porto and the Douro=2, Seville=1, The Azores=0]
plan: "Seven Hills, Three Friends, One Pool"  advisory GO  2 stays
```

The storm re-auctioned the day and the dining planner won it from the sights
planner. The clams knocked the party's energy under forty, so the simulator
called a rest day and the rest planner's bid collapsed to almost nothing. The
protest sent three watchers back to their sources; the video seat voted AVOID
after watching the livestreams, the news and forum seats voted CAUTION, and the
advisory moved to CAUTION with the ballots on record. Nobody dispatched any of
it.

## Running it

```
./run-demo.sh                 # replays the recorded trip; no keys needed
mvn test                      # the flow test over real TLS peers, about 90 s
```

A live run wants the same keys Tripper wants, and one more for the model:

```
export OPENAI_API_KEY=...     # the language model (gpt-4.1-mini by default)
export BRAVE_API_KEY=...      # web, news, image, and video search
export YOUTUBE_API_KEY=...    # optional; the video watcher prefers YouTube when present
./run-demo.sh
```

Without the keys the demo refuses to pretend and tells you which variable is
missing, exactly as Tripper does; `PARTYBUS_SOURCES=recorded` replays the
bundled Lisbon week instead. The turret serves at `http://localhost:7590/`
while the demo runs (operator token `partybus-operator`): HAL+JSON under
`/api/v1`, Server-Sent Events at `/api/v1/events`, and the web UI with the
Party Bus panels (itinerary, advisories, bookings, energy, event responses,
the plan) and commands (start a trip, throw an event, reroute the party).

The project depends on the AgentSpaces libraries at `0.2.0`. From a
clone of the main repository:

```
mvn -N install
mvn install -DskipTests -pl agentspaces-common,agentspaces-api,agentspaces-identity,\
agentspaces-peering,agentspaces-discovery,agentspaces-space,agentspaces-capabilities,\
agentspaces-agent,agentspaces-a2a,agentspaces-connect-core,agentspaces-console,\
agentspaces-spring-stubs,agentspaces-spring-boot-autoconfigure,\
agentspaces-spring-boot-starter,embabel-agentspaces
```

## Who is on the bus

| Seat | Agents | Coordination type |
| --- | --- | --- |
| concierge | dispatcher, plan-assembler, stay-finder, safety-lead | `notify` choreography; the fan-out and the join |
| scouts-a, scouts-b | flight, hotel, car, sightseeing, restaurant, photo, review, next-destination | `@SpaceTake` on the `research` bag (LEASE_RACE), kill-tolerant |
| advisors | diet, foodie, accessibility, hospital-locator | `@SpaceTake` on the same bag |
| watchers | news, video, social watchers; sentiment analyst; three panelists | `@SpaceTake` sweeps, `@SpaceNotify` scoring, `aspace:cap/vote` QUORUM |
| dining-, sights-, photo-walk-, rest-planner | one activity planner each | `@BidFunction` + `@SpaceTake` on `slots` (AUCTION) |
| the-bus | Marco, Priya, Dee, and the party lead | `aspace:cap/aggregate` push-sum (energy, spend) and `aspace:cap/vote` (where next) |
| clerk-a, clerk-b, clerk-c | booking clerks | `aspace:cap/ordered-log`: exactly-once takes over a Raft quorum |
| planner | the Embabel planner's seat | remote-actions bridge (SPEC §10.6): every card is a typed action |
| turret | the console, the simulator | HAL+JSON, SSE, C2 directives with a signed audit trail |

Fifteen peers, nine spaces each, one group, TLS with channel authentication
(SPEC §5.6) so the gossip travels unsigned and a two-core laptop keeps up.

## The code, feature by feature

```
src/main/java/ai/badmonkey/agentspaces/partybus/
├── PartyBus.java              the demo: builds the peers, binds the agents, runs the week
├── domain/                    Tripper's model as entry records (Trip, Research, Safety,
│                              Itinerary, Simulation); every record carries a tripId, and
│                              requests and results share a topic for correlation
├── sources/                   the outside world behind one interface (Sources): LiveSources
│                              speaks REST/JSON to OpenAI, Brave, YouTube, OSM; RecordedSources
│                              replays a captured trip
├── agents/                    the fleet: Scouts, Advisors, Watchers, SafetyPanel, Planners,
│                              Party, Assemblers, Dispatcher
└── fleet/                     the wiring: PartyBusFleet (peers, spaces, capabilities),
                               Turret (console), VacationSimulator (the week),
                               PartyBusPlanner (the planner bridge)
```

Each coordination type appears once, on the job it fits. Here is where to look
for each, and what it saved us writing.

**Replicated workers that survive being killed.** Look at `agents/Scouts.java`,
any of the eight inner classes: one `@SpaceTake(space = "research", lease = "2m")`
method whose parameter type is its queue. The binder takes a matching request
under a lease, runs the method, and writes the return value back as the result.
A scout that dies mid-search lets its lease lapse and another scout of the same
kind picks the request up. Normally this is a broker, a visibility timeout, a
dead-letter queue, and a consumer framework to tie them together. Here one
annotation gets competing consumers, at-least-once delivery, and crash recovery,
with no broker in the picture at all. Running two flight scouts drains the
flight queue twice as fast, and that is the only scaling knob.

**Fan-out and event-driven choreography.** Look at `agents/Dispatcher.java`: a
single `@SpaceNotify(space = "trip")` method that fires when a `TravelBrief`
lands and writes twelve typed requests into the research bag, one per specialist.
Its second method, `react`, does the same for a `TripEvent`, sending the watchers
back to their sources and the advisors after hospitals or a car. Nothing subscribes
to the dispatcher and the dispatcher knows none of the scouts. Look also at
`agents/Watchers.java`, where `@SpaceTake(space = "research", resultSpace = "signals")`
routes a worker's output into a different space, which is a pipeline stage
without a pipeline engine.

**Auction scheduling instead of a scheduler.** Look at `agents/Planners.java`:
each planner pairs a `@BidFunction(space = "slots")` with a
`@SpaceTake(space = "slots")`. The space runs the CBBA rounds and awards each
day to the lowest bid. The alternative is a central scheduler and an assignment
algorithm you write and tune yourself. Here the policy lives in four small
pricing functions, and the interesting behaviour falls out: when the travelers'
energy drops, the rest planner's bid collapses and it takes the day; when the
simulator's storm re-auctions a day, the outdoor planners price themselves out
and the day changes hands. Nobody dispatches any of it.

**Signed quorum voting with a recomputable tally.** Look at
`agents/SafetyPanel.java` for `vote.propose(...)` and `vote.castBallot(...)`,
and at `agents/Party.java#decide` for the travelers' seats on a second
electorate. Every ballot is an entry signed by its writer, so the tally is
recomputable by any member from the space alone. Building this yourself means
either a consensus service or a database plus application-level voting plus an
audit trail you have to trust.

**Fleet-wide numbers with no collector.** Look at `agents/Party.java#feel`:
each traveler calls `aggregate.start(epoch, energy)` and the party's energy and
spend converge through push-sum gossip over the capability pipes. These numbers
live in no space and no database. The usual answer is a metrics pipeline or a
coordinator process that every agent reports to, and then you own that
coordinator's availability.

**Exactly-once work across competing workers.** Look at
`agents/Assemblers.java#runBookingClerk`: three clerks race for every booking
order through `OrderedTakes` over a Raft quorum, and only the committed claim's
clerk completes the order. This is the one place in the fleet where "at least
once" is not good enough, and it is the one place that pays for a quorum. The
alternatives are embedding Raft yourself, a database row lock, or a log with
carefully managed offsets.

**Space handles by injection.** Look at the `@SpaceRef("research")` fields in
`agents/Planners.java` and `agents/Assemblers.java`. An agent that needs to read
another space declares the field and the binder fills it, so no agent takes a
fleet object in its constructor and every agent stays a plain testable class.

**Foreign agents as typed local actions.** Look at
`fleet/PartyBusPlanner.java#remoteActions` and `#bridge`. Every scout publishes
an AgentCard naming the type it consumes and the type it produces; the bridge
turns each card into a generated `@Action` method the GOAP planner can chain,
and invoking one writes the request and awaits the correlated result. The
planner keeps sequencing typed actions exactly as Tripper's does while the
actions run on other peers. The usual version of this is service discovery plus
generated client stubs plus an RPC contract to keep in sync, and it gives you
RPC semantics: a call that fails when the far side dies. This keeps
choreography, so a scout that dies mid-request hands it to the next one.

**A console, an API, and an audit trail you did not build.** Look at
`fleet/Turret.java`: `FleetConsoleServer` with Party Bus `ConsolePanel`s and
`FleetCommander` commands gives HAL+JSON under `/api/v1`, Server-Sent Events,
and a web UI, and every operator command lands in the `c2-audit` space signed
by the operator's peer. The panels are pure reads of the spaces, so the console
is one more read-mostly peer rather than a privileged service.

**Mutual TLS without a service mesh.** Look at `fleet/PartyBusFleet.java` where
the peer is built with `ChannelAuth.ATTESTED` over a `TlsTcpTransport`. The
channel authenticates both ends, which lets the fabric drop the per-frame
signature on attested links and keeps a two-core laptop up with fifteen peers.
The content signatures that make gossip trustworthy across hops stay on.

**A deterministic demo and test with no network.** Look at `sources/Sources.java`
and its two implementations. The agents hold the interface and never learn
which one they have, so the same fleet runs live against OpenAI and Brave or
replays a recorded Lisbon week. That is what lets `run-demo.sh` work with no
keys and the flow test run in CI.

## How the pieces sit together

`domain/` is Tripper's domain model as entry records, plus the requests and
results each specialist exchanges, the safety pipeline's signals and
advisories, the itinerary and booking types, and the simulator's clock and
events. Every record carries a `tripId`; requests and results also share a
`topic`, which is the shared-field correlation the planner bridge pairs them by.

`sources/` is the outside world behind one provider interface. `LiveSources`
speaks REST/JSON to OpenAI, Brave Search, the YouTube Data API, and
OpenStreetMap's Nominatim and Overpass services with `java.net.http` and no
SDK. `RecordedSources` replays a recorded trip so the simulator and the tests
run with no network.

`agents/` is the fleet: `Scouts` and `Advisors` (twelve replicated workers),
`Watchers` and `SafetyPanel` (the signals pipeline and the panel vote),
`Planners` (the four bidders), `Party` (the travelers and their lead),
`Assemblers` (the join, the stays, the clerks), and `Dispatcher` (the fan-out
and the event reactions).

`fleet/` is the wiring: `PartyBusFleet` starts a peer with every space and
capability, `Turret` is the console, `VacationSimulator` is the clock and the
script, and `PartyBusPlanner` is the remote-actions bridge with a small
breadth-first planner that stands in for GOAP when Embabel is absent.

`src/embabel/java` compiles only under `-Pembabel` against the Embabel starter
Tripper uses. It boards the bus inside a Spring Boot application and deploys
the generated fleet agent onto Embabel's `AgentPlatform`, so the real GOAP
planner plans across the fleet. That profile needs Maven Central and is the one
part of the project the default build does not verify.

`PartyBusUnitTest` covers the pure pieces with no fleet: the auction's pricing
from each planner's point of view, the simulator pacing the next day from the
party's energy, the safety panel's channel weighting and verdict thresholds, the
recording's lookup order, and the live sources insisting on their keys.
`PartyBusFleetTest` runs the whole week over real TLS peers.

## The guide

`site/partybus-guide.html` in the AgentSpaces repository walks through building
all of this, coordination type by coordination type, with the code from these
sources.

## A note the bus left for the library

Running the bookings through the ordered log at fleet scale surfaced a gap in
`SignedClaim.merge`: the coordinator applies each committed claim on every
member under that member's own key, and the holder's own proof, arriving with
the completion, lost the merge tie to that placeholder, so no other member
could authenticate the completion and the order reappeared for the next clerk
when the lease lapsed. The merge now prefers the holder-attested proof for the
same claim; `SignedClaimMergeTest` in `agentspaces-space` pins it, and the flow
test here checks that the bookings are still exactly once a simulated week
later.

## License

Apache License 2.0. An open source project from Bad Monkey.
