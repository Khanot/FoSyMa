package eu.su.mas.dedaleEtu.mas.behaviours;

import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import jade.core.AID;
import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Behaviour for hunting wumpus/golems after the rally phase.
 *
 * Three phases:
 *   MOVE_TO_START  — each group moves to its assigned sector entry point
 *   SWEEP          — the group sweeps the map in formation, observing for wumpus traces
 *   CHASE          — a trace has been spotted; the whole group converges on it
 *
 * Group coordination:
 *   - The "leader" is the alphabetically-first agent in the group.
 *   - The leader picks the next target and broadcasts HUNT-MOVE to followers.
 *   - Followers execute the received move order; if no order arrives in time they wait.
 *   - All agents broadcast their observed wumpus traces (HUNT-TRACE).
 *
 * Starting points:
 *   All closed nodes are sorted and split evenly among groups.
 *   Group i receives the node at index  (i * totalNodes / nbGroups).
 */
public class HuntBehaviour extends SimpleBehaviour {

    private static final long serialVersionUID = 42L;

    // ── protocols ────────────────────────────────────────────────────────────
    public static final String PROTOCOL_MOVE  = "HUNT-MOVE";   // leader → followers
    public static final String PROTOCOL_TRACE = "HUNT-TRACE";  // any   → any
    public static final String PROTOCOL_POS   = "HUNT-POS";    // any   → leader (position update)

    private static final MessageTemplate TPL_MOVE = MessageTemplate.and(
            MessageTemplate.MatchProtocol(PROTOCOL_MOVE),
            MessageTemplate.MatchPerformative(ACLMessage.INFORM));

    private static final MessageTemplate TPL_TRACE = MessageTemplate.and(
            MessageTemplate.MatchProtocol(PROTOCOL_TRACE),
            MessageTemplate.MatchPerformative(ACLMessage.INFORM));

    private static final MessageTemplate TPL_POS = MessageTemplate.and(
            MessageTemplate.MatchProtocol(PROTOCOL_POS),
            MessageTemplate.MatchPerformative(ACLMessage.INFORM));

    // ── state ─────────────────────────────────────────────────────────────────
    private enum Phase { MOVE_TO_START, SWEEP, CHASE }

    private Phase phase = Phase.MOVE_TO_START;
    private boolean finished = false;

    private final MapRepresentation myMap;
    private final int myGroup;
    private final int nbGroups;
    private final List<String> myGroupMembers;   // sorted, leader = index 0
    private final List<String> allAgentNames;

    /** Current sweep target (open or unvisited node). */
    private String sweepTarget = null;

    /** Node where a wumpus trace was detected (CHASE phase). */
    private String traceLocation = null;

    /** Last known positions of group members (nodeId). */
    private final Map<String, String> memberPositions = new HashMap<>();

    /** Ticks between leader broadcasts and position reports. */
    private int tickCount = 0;
    private static final int BROADCAST_INTERVAL = 3;

    /** Ticks the follower waits for a HUNT-MOVE before acting on its own. */
    private static final int FOLLOWER_TIMEOUT = 6;
    private int followerWaitTicks = 0;

    // ── constructor ───────────────────────────────────────────────────────────

    /**
     * @param agent           the owning agent
     * @param myMap           shared map representation
     * @param myGroup         this agent's group id
     * @param nbGroups        total number of groups
     * @param myGroupMembers  all members of this group (unsorted is fine)
     * @param allAgentNames   all agent names (for broadcast if needed)
     */
    public HuntBehaviour(AbstractDedaleAgent agent,
                         MapRepresentation myMap,
                         int myGroup,
                         int nbGroups,
                         List<String> myGroupMembers,
                         List<String> allAgentNames) {
        super(agent);
        this.myMap = myMap;
        this.myGroup = myGroup;
        this.nbGroups = nbGroups;
        this.myGroupMembers = new ArrayList<>(myGroupMembers);
        Collections.sort(this.myGroupMembers);          // leader = index 0
        this.allAgentNames = new ArrayList<>(allAgentNames);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean isLeader() {
        return myAgent.getLocalName().equals(myGroupMembers.get(0));
    }

    private String leaderName() {
        return myGroupMembers.get(0);
    }

    /**
     * Computes the starting node for this group.
     * Closed nodes are sorted; group i takes index (i * size / nbGroups).
     */
    private String computeStartingNode() {
        List<String> closed = myMap.getAllNodes().stream()
                .filter(id -> !myMap.getOpenNodes().contains(id))
                .sorted()
                .collect(Collectors.toList());

        if (closed.isEmpty()) return null;
        int idx = (myGroup * closed.size()) / Math.max(nbGroups, 1);
        idx = Math.min(idx, closed.size() - 1);
        return closed.get(idx);
    }

    /** Send a string message to all group members except self. */
    private void sendToGroup(String protocol, int performative, String content) {
        for (String name : myGroupMembers) {
            if (name.equals(myAgent.getLocalName())) continue;
            ACLMessage msg = new ACLMessage(performative);
            msg.setProtocol(protocol);
            msg.setSender(myAgent.getAID());
            msg.addReceiver(new AID(name, AID.ISLOCALNAME));
            msg.setContent(content);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
        }
    }

    /** Drain all pending messages for a template, return list of contents. */
    private List<String> drainMessages(MessageTemplate tpl) {
        List<String> result = new ArrayList<>();
        ACLMessage msg;
        while ((msg = myAgent.receive(tpl)) != null) {
            result.add(msg.getSender().getLocalName() + "|" + msg.getContent());
        }
        return result;
    }

    /**
     * Observes the environment and looks for wumpus/golem traces.
     * Returns the node id of the first trace found, or null.
     */
    private String detectTrace(
            List<Couple<Location, List<Couple<Observation, String>>>> observations) {

        for (Couple<Location, List<Couple<Observation, String>>> cell : observations) {
            for (Couple<Observation, String> obs : cell.getRight()) {
                Observation type = obs.getLeft();
                // Wumpus-related observation types in Dedale
                if (type == Observation.STENCH) {
                    String val = obs.getRight();
                    if (val != null && !val.equals("0") && !val.isBlank()) {
                        return cell.getLeft().getLocationId();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Pick the next sweep node for the leader.
     * Prefers unvisited (open) nodes; falls back to any reachable node.
     */
    private String pickNextSweepTarget(String currentId) {
        List<String> candidates = new ArrayList<>(myMap.getOpenNodes());

        if (candidates.isEmpty()) {
            // map fully explored — iterate over all nodes
            candidates = new ArrayList<>(myMap.getAllNodes());
        }

        return candidates.stream()
                .map(n -> {
                    List<String> p = myMap.getShortestPath(currentId, n);
                    int d = (p == null) ? Integer.MAX_VALUE : p.size();
                    return new Couple<>(n, d);
                })
                .filter(c -> c.getRight() < Integer.MAX_VALUE && c.getRight() > 0)
                .min(Comparator.comparing(Couple::getRight))
                .map(Couple::getLeft)
                .orElse(null);
    }

    // ── main action ───────────────────────────────────────────────────────────

    @Override
    public void action() {
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        tickCount++;

        Location currentLoc = ((AbstractDedaleAgent) myAgent).getCurrentPosition();
        if (currentLoc == null) { block(300); return; }
        String currentId = currentLoc.getLocationId();

        // ── always observe ───────────────────────────────────────────────────
        List<Couple<Location, List<Couple<Observation, String>>>> obs =
                ((AbstractDedaleAgent) myAgent).observe();

        // ── broadcast own position to leader ────────────────────────────────
        if (!isLeader() && tickCount % BROADCAST_INTERVAL == 0) {
            ACLMessage posMsg = new ACLMessage(ACLMessage.INFORM);
            posMsg.setProtocol(PROTOCOL_POS);
            posMsg.setSender(myAgent.getAID());
            posMsg.addReceiver(new AID(leaderName(), AID.ISLOCALNAME));
            posMsg.setContent(currentId);
            ((AbstractDedaleAgent) myAgent).sendMessage(posMsg);
        }

        // ── leader: collect positions ────────────────────────────────────────
        if (isLeader()) {
            for (String raw : drainMessages(TPL_POS)) {
                String[] parts = raw.split("\\|", 2);
                memberPositions.put(parts[0], parts[1]);
            }
            memberPositions.put(myAgent.getLocalName(), currentId);
        }

        // ── scan for traces (all agents) ─────────────────────────────────────
        String detectedTrace = detectTrace(obs);
        if (detectedTrace != null && phase != Phase.CHASE) {
            System.out.println("[" + myAgent.getLocalName() + "] Trace détectée en " + detectedTrace);
            sendToGroup(PROTOCOL_TRACE, ACLMessage.INFORM, detectedTrace);
            traceLocation = detectedTrace;
            phase = Phase.CHASE;
        }

        // ── receive trace from group ─────────────────────────────────────────
        for (String raw : drainMessages(TPL_TRACE)) {
            String traceNode = raw.split("\\|", 2)[1];
            if (phase != Phase.CHASE) {
                System.out.println("[" + myAgent.getLocalName() + "] Trace signalée par groupe en " + traceNode);
                traceLocation = traceNode;
                phase = Phase.CHASE;
            }
        }

        // ── phase dispatch ───────────────────────────────────────────────────
        if (phase == phase.MOVE_TO_START) {doMoveToStart(currentId);}
        if (phase == phase.SWEEP) {doSweep(currentId);}
        if (phase ==phase.CHASE) {doChase(currentId);}
    }

    // ── PHASE 1 : MOVE_TO_START ───────────────────────────────────────────────

    private void doMoveToStart(String currentId) {
        String startNode = computeStartingNode();

        if (startNode == null) {
            System.out.println("[" + myAgent.getLocalName() + "] Aucun nœud de départ — passage en SWEEP");
            phase = Phase.SWEEP;
            return;
        }

        if (currentId.equals(startNode)) {
            System.out.println("[" + myAgent.getLocalName() + "] Arrivé au point de départ " + startNode);
            phase = Phase.SWEEP;
            return;
        }

        List<String> path = myMap.getShortestPath(currentId, startNode);
        if (path == null || path.isEmpty()) {
            phase = Phase.SWEEP;
            return;
        }

        ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(path.get(0)));
    }

    // ── PHASE 2 : SWEEP ───────────────────────────────────────────────────────

    private void doSweep(String currentId) {
        if (isLeader()) {
            leaderSweep(currentId);
        } else {
            followerSweep(currentId);
        }
    }

    private void leaderSweep(String currentId) {
        // Recalculate target if needed
        if (sweepTarget == null
                || currentId.equals(sweepTarget)
                || !myMap.getAllNodes().contains(sweepTarget)) {
            sweepTarget = pickNextSweepTarget(currentId);
            if (sweepTarget == null) {
                System.out.println("[" + myAgent.getLocalName() + "] Leader: plus de cibles — fin de chasse");
                finished = true;
                return;
            }
            System.out.println("[" + myAgent.getLocalName() + "] Leader sweep → " + sweepTarget);
        }

        // Broadcast target to followers
        if (tickCount % BROADCAST_INTERVAL == 0) {
            sendToGroup(PROTOCOL_MOVE, ACLMessage.INFORM, sweepTarget);
        }

        // Check all members are not too far behind before moving
        boolean allClose = memberPositions.isEmpty() || memberPositions.values().stream()
                .allMatch(pos -> {
                    List<String> p = myMap.getShortestPath(pos, currentId);
                    return p == null || p.size() <= 3;   // max 3 hops behind leader
                });

        if (!allClose) {
            // Wait for followers to catch up
            block(300);
            return;
        }

        List<String> path = myMap.getShortestPath(currentId, sweepTarget);
        if (path != null && !path.isEmpty()) {
            ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(path.get(0)));
        }
    }

    private void followerSweep(String currentId) {
        // Check for a HUNT-MOVE order from leader
        ACLMessage moveMsg = myAgent.receive(TPL_MOVE);
        if (moveMsg != null) {
            sweepTarget = moveMsg.getContent();
            followerWaitTicks = 0;
        }

        if (sweepTarget == null) {
            followerWaitTicks++;
            block(300);
            return;
        }

        if (currentId.equals(sweepTarget)) {
            // Arrived — wait for next order
            followerWaitTicks++;
            if (followerWaitTicks > FOLLOWER_TIMEOUT) {
                // Leader may be unreachable — act autonomously
                sweepTarget = pickNextSweepTarget(currentId);
            }
            block(300);
            return;
        }

        List<String> path = myMap.getShortestPath(currentId, sweepTarget);
        if (path != null && !path.isEmpty()) {
            boolean moved = ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(path.get(0)));
            if (!moved) {
                followerWaitTicks++;
                block(300);
            } else {
                followerWaitTicks = 0;
            }
        } else {
            block(300);
        }
    }

    // ── PHASE 3 : CHASE ───────────────────────────────────────────────────────

    private void doChase(String currentId) {
        if (traceLocation == null) {
            // Lost the trace — resume sweep
            phase = Phase.SWEEP;
            return;
        }

        if (currentId.equals(traceLocation)) {
            System.out.println("[" + myAgent.getLocalName() + "] Sur la trace du golem en " + traceLocation);
            // Re-observe: if trace gone, sweep nearby; else stay/attack
            traceLocation = null;
            phase = Phase.SWEEP;
            return;
        }

        // Leader broadcasts chase target
        if (isLeader() && tickCount % BROADCAST_INTERVAL == 0) {
            sendToGroup(PROTOCOL_MOVE, ACLMessage.INFORM, traceLocation);
        }

        // Follower: consume any HUNT-MOVE
        if (!isLeader()) {
            ACLMessage moveMsg = myAgent.receive(TPL_MOVE);
            if (moveMsg != null) {
                traceLocation = moveMsg.getContent();
            }
        }

        List<String> path = myMap.getShortestPath(currentId, traceLocation);
        if (path != null && !path.isEmpty()) {
            ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(path.get(0)));
        } else {
            block(300);
        }
    }

    @Override
    public boolean done() { return finished; }
}
