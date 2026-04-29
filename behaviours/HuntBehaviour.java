package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.ExploreCoopAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import dataStructures.serializableGraph.SerializableNode;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class HuntBehaviour extends TickerBehaviour {

    public enum HuntState { MEETING, PATROL, HUNTING, BLOCKING }

    private final List<String> allAgentNames;
    private final String myName;
    private final boolean isLeader;

    private HuntState state = HuntState.PATROL;

    private String golemNode = null;
    private String myTargetNode = null;
    private String patrolTarget = null;

    private Map<String, String> agentPositions = new HashMap<>();
    private Map<String, String> agentTargets = new HashMap<>();
    private Map<String, String> stenchPositions = new HashMap<>();

    private static final MessageTemplate TEMPLATE_MAP_REQUEST = MessageTemplate.and(
    	    MessageTemplate.MatchProtocol("RALLY-MAP-REQUEST"),
    	    MessageTemplate.MatchPerformative(ACLMessage.REQUEST)
    	);
    
    private final Map<String, String> claims = new HashMap<>();
    private String lastKnownStenchNode = null;
    private String myClaimedNode = null;
    private int patrolStuckCount = 0;
    private String lastPatrolPos = null;
    private int failedMoveCount=0;
    private MapRepresentation myMap;
    private String coordinatorName;
    
    private String lastSeenGolemNode = null;
    private int golemSightCount = 0;
    private static final int BLOCK_THRESHOLD = 20;
    private static final int BLOCKED_GOLEM_STENCH_RADIUS = 1; // rayon de l'odeur du golem
    private final Set<String> blockedGolems = new HashSet<>();
    int lostCount=0;
    int lastTimeSeen=0;
    private static final int RELAY_LIMIT=5;
    
    public HuntBehaviour(ExploreCoopAgent agent, List<String> allAgentNames,String coordinatorName) {
        super(agent, Constants.stopTimeHunt);
        this.allAgentNames = allAgentNames;
        this.myName = agent.getLocalName();
        this.myAgent=agent;
        List<String> sorted = new ArrayList<>(allAgentNames);
        sorted.add(agent.getLocalName());
        Collections.sort(sorted);
        this.isLeader = false; /*agent.getLocalName().equals(coordinatorName);*/
        this.coordinatorName=coordinatorName;
        myMap=agent.myMap;
        lostCount=0;
        System.out.println("[" + myName + "] isLeader=" + isLeader);
    }

    @Override
    public void onTick() {
    	
    	
    	
        AbstractDedaleAgent me = (AbstractDedaleAgent) this.myAgent;
        
        if (state==HuntState.PATROL) { System.out.println( "                                            " +me.getLocalName()+ " EST EN PATROUILLE");}
        
        if (state==HuntState.HUNTING) { System.out.println( "                                               " +me.getLocalName()+ " EST EN CHASSE");}
        
        if (state==HuntState.BLOCKING) { System.out.println( "                                           " +me.getLocalName()+ " EST EN BLOQUAGE");}
        
        
        if (((ExploreCoopAgent) me).myMap == null) return;
        
        if (this.myMap.hasOpenNode()) {
        	requestMap();
        }
        processMessagesMap(me);
        
        if (state == HuntState.BLOCKING) {
        	
        	blocking(me,golemNode);
        	return;
        }
        updateGolemPosition(me);
        
        
        
        Location cur = me.getCurrentPosition();
        if (!ensureCurrentNodeInMap(cur)) {
            block(Constants.stopTimeHunt);
            return;
        }
        
        if (cur == null) return;
        
        
        boolean adjacent = myMap.getNeighbors(golemNode).contains(cur.getLocationId());
        if (golemSightCount >= BLOCK_THRESHOLD && state!=HuntState.BLOCKING && adjacent) {
            state = HuntState.BLOCKING;
            addBlockedGolemZone(golemNode);
            return;
        }
        if (golemSightCount > 0 ) {
            // golem vu ce tick → rester figé
            block(Constants.stopTimeHunt);
            
            return;
        }
        

        if (state == HuntState.PATROL) {
            
            followerPatrol(me);
            updateGolemPosition(me);
            processMessages(me);
            return;
        }
        processMessages(me);
        // HUNTING
        
        receiveClaims(me);
        

        

        List<String> stenchNodes = findStenchNodes(me);

        if (stenchNodes.isEmpty()) {
            clearMyClaim(me);
            navigateTowardGolem(me, cur);
            lostCount++;
            if (lostCount>10) {state=HuntState.PATROL;lostCount=0;return;}
            return;
        }
        

        lastKnownStenchNode = stenchNodes.get(0);
        String target = pickTarget(me, cur, stenchNodes);
        if (target == null) target = stenchNodes.get(0);

        myClaimedNode = target;
        broadcastClaim(me, target);

        if (cur.getLocationId().equals(target)) {
            System.out.println("[" + myName + "] On stench node " + target + " ✓");
        } else {
            moveToward(me, target);
        }
    }

    // ==================== PATROL ====================

    

    private void followerPatrol(AbstractDedaleAgent me) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        MapRepresentation map = ((ExploreCoopAgent) me).myMap;
        if (map == null) return;

        // Détecter si bloqué
        if (cur.getLocationId().equals(lastPatrolPos)) {
            patrolStuckCount++;
        } else {
            patrolStuckCount = 0;
            failedMoveCount=0;
            lastPatrolPos = cur.getLocationId();
        }

        int value = ThreadLocalRandom.current().nextInt(3, 11);
        boolean stuck = patrolStuckCount > value  || failedMoveCount >value;

        // Changer de cible seulement si nécessaire
        if (myTargetNode == null || cur.getLocationId().equals(myTargetNode)||stuck) {
        	broadcastGolemBlocked(me);
        	patrolStuckCount = 0;
            failedMoveCount=0;
            String target = null;
            if (!stuck) {
                String leaderPos = agentPositions.get(coordinatorName);
                if (leaderPos != null) {
                    target = pickFarNode(map, cur.getLocationId());
                }
            }
            
            target = pickFarNode(map, cur.getLocationId());
            
            if (target != null) {
                myTargetNode = target;
                System.out.println("[" + myName + "] Nouvelle cible : " + myTargetNode);
            }
        }

        broadcastStatus(me);
        if (myTargetNode != null) {
            boolean moved = moveToward(me, myTargetNode);
            if (!moved) {
                failedMoveCount++;
            } else {
                failedMoveCount = 0;
            }
        }
    }

   



    // ==================== DETECTION ====================

    private void updateGolemPosition(AbstractDedaleAgent me) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;

        

        var observations = me.observe();
        String stenchNode = null;
        boolean wumpusSeenThisTick = false;

        for (var nodeObs : observations) {
            for (var obs : nodeObs.getRight()) {
                if (obs.getLeft() == Observation.AGENTNAME
                        && obs.getRight() != null
                        && obs.getRight().toLowerCase().contains("wumpus")) {
                    String detected = nodeObs.getLeft().getLocationId();
                    if (blockedGolems.contains(detected)) {
                        continue;
                    }
                    wumpusSeenThisTick = true;
                    if (detected.equals(lastSeenGolemNode)) {
                        golemSightCount++;
                    } else {
                        golemSightCount = 1;
                    }
                    lastSeenGolemNode = detected;

                    if (state != HuntState.BLOCKING && state != HuntState.HUNTING) {
                        state = HuntState.HUNTING;
                        golemNode = detected;
                        myTargetNode = null;
                        ((ExploreCoopAgent) me).huntStarted = true;
                        broadcastGolem(me, golemNode);
                        System.out.println("[" + myName + "] Golem VU en " + golemNode);
                    } else if (state != HuntState.BLOCKING && state == HuntState.HUNTING && !detected.equals(golemNode)) {
                        golemNode = detected;
                        myTargetNode = null;
                        broadcastGolem(me, golemNode);
                    }
                    break;
                }
                if (obs.getLeft() == Observation.STENCH) {
                    stenchNode = nodeObs.getLeft().getLocationId();
                }
            }
        }

        if (!wumpusSeenThisTick) {
            golemSightCount = 0;
        }

        if (stenchNode != null) {
            // Vérifier que l'odeur n'est adjacente à aucun golem déjà bloqué
            
            if (!blockedGolems.contains(stenchNode)) {
                broadcastStench(me, stenchNode);
                if (state != HuntState.HUNTING && state != HuntState.BLOCKING && golemSightCount>0) {
                    state = HuntState.HUNTING;
                    golemNode = stenchNode;
                    myTargetNode = null;
                    ((ExploreCoopAgent) me).huntStarted = true;
                } else if (state == HuntState.HUNTING) {
                    broadcastStench(me, stenchNode);
                    if (golemNode == null) {
                        golemNode = stenchNode;
                        myTargetNode = null;
                    }
                }
            }
        }
    }
    
    private void blocking(AbstractDedaleAgent me, String golemNode) {
    	Location cur = me.getCurrentPosition();
    	
        if (cur == null) return;

       

        var observations = me.observe();
        boolean wumpusSeenThisTick = false;

        for (var nodeObs : observations) {
            for (var obs : nodeObs.getRight()) {
                if (obs.getLeft() == Observation.AGENTNAME
                        && obs.getRight() != null
                        && obs.getRight().toLowerCase().contains("wumpus")) {
          
                    
                    wumpusSeenThisTick = true;
                    
                    break;
                }
                
            }
        }

        if (!wumpusSeenThisTick) {
        	state = HuntState.PATROL;
        }
        else{
        	blockedGolems.add(golemNode);
        	if (myMap != null) {
                Set<String> zone = new HashSet<>();
                Queue<String> queue = new LinkedList<>();
                Map<String, Integer> dist = new HashMap<>();
                queue.add(golemNode);
                dist.put(golemNode, 0);
                zone.add(golemNode);
                while (!queue.isEmpty()) {
                    String curr = queue.poll();
                    int d = dist.get(curr);
                    if (d >= BLOCKED_GOLEM_STENCH_RADIUS) continue;
                    for (String neighbor : myMap.getNeighbors(curr)) {
                        if (!dist.containsKey(neighbor)) {
                            dist.put(neighbor, d + 1);
                            zone.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
                }
                blockedGolems.addAll(zone);
            }
        	broadcastGolemBlocked(me);
        }
        block(Constants.stopTimeHunt);
        return;
    }

    // ==================== MESSAGES ====================

    private void processMessages(AbstractDedaleAgent me) {
        MessageTemplate mtGolem = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("GOLEM-POS"));
        ACLMessage msg;
        
        while ((msg = me.receive(mtGolem)) != null) {
            String newGolem = msg.getContent();
            if (newGolem != null && !blockedGolems.contains(newGolem)) {
                golemNode = newGolem;
                myTargetNode = null;
                
                state = HuntState.HUNTING;
                ((ExploreCoopAgent) me).huntStarted = true;
                
                System.out.println("[" + myName + "] Golem signalé en " + golemNode
                    + " par " + msg.getSender().getLocalName());
            }
        }

        MessageTemplate mtStench = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("STENCH-POS"));
        
        while ((msg = me.receive(mtStench)) != null) {
            String[] parts = msg.getContent().split(";");
            if (parts.length < 3) continue;
            int lastTime=Integer.parseInt(parts[2]);
            if(lastTime>RELAY_LIMIT) {continue;}
            else {lastTimeSeen=lastTime+1;}
            String stenchSource = parts[1];
            if (blockedGolems.contains(stenchSource)) continue;
            stenchPositions.put(parts[0], parts[1]);
            if (state != HuntState.HUNTING 
                    ) {
                state = HuntState.HUNTING;
                ((ExploreCoopAgent) me).huntStarted = true;
                golemNode = parts[1];
                myTargetNode = null;
                System.out.println("[" + myName + "] Chasse via stench de "
                    + parts[0] + " en " + parts[1]);
            }
        }

        MessageTemplate mtStatus = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("AGENT-STATUS"));
        while ((msg = me.receive(mtStatus)) != null) {
            String content = msg.getContent();
            if (content == null) continue;
            String[] parts = content.split(";");
            if (parts.length < 2) continue;
            agentPositions.put(msg.getSender().getLocalName(), parts[0]);
            agentTargets.put(msg.getSender().getLocalName(), parts[1]);
        }

        MessageTemplate mtLeader = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("LEADER-STATUS"));
        while ((msg = me.receive(mtLeader)) != null) {
            String content = msg.getContent();
            if (content == null) continue;
            String[] parts = content.split(";");
            if (parts.length < 2) continue;
            agentPositions.put(msg.getSender().getLocalName(), parts[0]);
        }

        MessageTemplate mtBlocked = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                MessageTemplate.MatchOntology("GOLEM-BLOCKED"));

        ACLMessage blockedMsg;
        while ((blockedMsg = me.receive(mtBlocked)) != null) {
            String content = blockedMsg.getContent();

            if (content != null && !content.isEmpty()) {
                String[] golems = content.split(":");

                for (String golem : golems) {
                    addBlockedGolemZone(golem);
                    System.out.println("[" + myName + "] Golem " + golem + " est bloqué (zone ajoutée)");
                }

                if (state != HuntState.BLOCKING && state != HuntState.PATROL) {
                    state = HuntState.PATROL;
                    golemNode = null;
                    myTargetNode = null;
                    myClaimedNode = null;
                    lastKnownStenchNode = null;
                    claims.clear();
                }
            }
        }
     

    }
private void processMessagesMap(AbstractDedaleAgent me) {
 // Répondre aux demandes de carte
    ACLMessage mapReq = me.receive(TEMPLATE_MAP_REQUEST);
    if (mapReq != null) {
        String requester = mapReq.getSender().getLocalName();
        System.out.println("[" + myName + "] Demande de carte de " + requester + " (reçue pendant la chasse)");
        ACLMessage response = new ACLMessage(ACLMessage.INFORM);
        response.setProtocol("RALLY-MAP-RESPONSE");
        response.setSender(me.getAID());
        response.addReceiver(new AID(requester, AID.ISLOCALNAME));
        try {
            response.setContentObject(myMap.getSerializableGraph());
            ((AbstractDedaleAgent) me).sendMessage(response);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

 

    private void broadcastStatus(AbstractDedaleAgent me) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        String target = myTargetNode != null ? myTargetNode : cur.getLocationId();
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology("AGENT-STATUS");
        acl.setContent(cur.getLocationId() + ";" + target);
        acl.setSender(me.getAID());
        for (String name : allAgentNames) {
            if (!name.equals(myName))
                acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        ((AbstractDedaleAgent) me).sendMessage(acl);
    }

    private void broadcastGolem(AbstractDedaleAgent me, String golemPos) {
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology("GOLEM-POS");
        acl.setContent(golemPos);
        acl.setSender(me.getAID());
        for (String name : allAgentNames) {
            if (!name.equals(myName))
                acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        ((AbstractDedaleAgent) me).sendMessage(acl);
    }

    private void broadcastStench(AbstractDedaleAgent me, String stenchNode) {
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology("STENCH-POS");
        acl.setContent(myName + ";" + stenchNode+";"+String.valueOf(lastTimeSeen));
        acl.setSender(me.getAID());
        for (String name : allAgentNames) {
            if (!name.equals(myName))
                acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        ((AbstractDedaleAgent) me).sendMessage(acl);
    }

    // ==================== UTILITAIRES ====================

    private String pickFarNode(MapRepresentation map, String currentId) {
    	if (!map.containsNode(currentId)) {
            // le nœud courant n'est pas encore dans notre carte, impossible de planifier
    		requestMap();
            return null;
        }
        var allNodes = map.getSerializableGraph().getAllNodes();
        if (allNodes.isEmpty()) return null;

        List<String> nodeIds = new ArrayList<>();
        for (var n : allNodes) {
            if (!n.getNodeId().equals(currentId)) nodeIds.add(n.getNodeId());
        }
        if (nodeIds.isEmpty()) return null;

        String bestNode = null;
        int bestDist = 0;
        Random rand = new Random();
        int samples = Math.min(10, nodeIds.size());
        for (int i = 0; i < samples; i++) {
            String candidate = nodeIds.get(rand.nextInt(nodeIds.size()));
            List<String> path = map.getShortestPath(currentId, candidate);
            int dist = (path == null) ? Integer.MAX_VALUE : path.size();
            if (dist > bestDist) {
                bestDist = dist;
                bestNode = candidate;
            }
        }
        return bestNode;
    }

    private String pickTarget(AbstractDedaleAgent me, Location cur, List<String> stenchNodes) {
    	if (!ensureCurrentNodeInMap(cur)) return null;   // <-- ajout
        Set<String> takenByOthers = new HashSet<>();
        for (Map.Entry<String, String> e : claims.entrySet()) {
            if (!e.getKey().equals(myName)) {
                takenByOthers.add(e.getValue());
            }
        }

        MapRepresentation map = ((ExploreCoopAgent) myAgent).myMap;

        List<String> candidates = stenchNodes.stream()
        	    .filter(n -> !blockedGolems.contains(n))
        	    .collect(Collectors.toList());
        if (map != null) {
            candidates.sort(Comparator.comparingInt(n -> {
                List<String> path = map.getShortestPath(cur.getLocationId(), n);
                return path == null ? Integer.MAX_VALUE : path.size();
            }));
        }

        if (myClaimedNode != null && candidates.contains(myClaimedNode)
                && !takenByOthers.contains(myClaimedNode)) {
            return myClaimedNode;
        }

        for (String candidate : candidates) {
            if (!takenByOthers.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void navigateTowardGolem(AbstractDedaleAgent me, Location cur) {
    	if (!ensureCurrentNodeInMap(cur)) return;
        if (lastKnownStenchNode != null && blockedGolems.contains(lastKnownStenchNode)) {
            lastKnownStenchNode = null;
        }
        if (lastKnownStenchNode == null) {
            var obs = me.observe();
            if (obs == null || obs.isEmpty()) return;

            Set<String> occupiedByAgents = new HashSet<>();
            for (var nodeObs : obs) {
                for (var o : nodeObs.getRight()) {
                    if (o.getLeft() == Observation.AGENTNAME) {
                        occupiedByAgents.add(nodeObs.getLeft().getLocationId());
                    }
                }
            }

            List<String> freeNeighbors = new ArrayList<>();
            for (var nodeObs : obs) {
                String nodeId = nodeObs.getLeft().getLocationId();
                if (!nodeId.equals(cur.getLocationId()) 
                        && !occupiedByAgents.contains(nodeId)) {
                    freeNeighbors.add(nodeId);
                }
            }

            if (freeNeighbors.isEmpty()) return;
            String next = freeNeighbors.get(new Random().nextInt(freeNeighbors.size()));
            for (var nodeObs : obs) {
                if (nodeObs.getLeft().getLocationId().equals(next)) {
                    me.moveTo(nodeObs.getLeft());
                    return;
                }
            }
            return;
        }
        if (lastKnownStenchNode != null) {
            List<String> golemNeighbors = myMap.getNeighbors(lastKnownStenchNode);
            if (golemNeighbors.contains(cur.getLocationId())) {
                // déjà en position de blocage, ne pas bouger
                return;
            }
        }

        MapRepresentation map = ((ExploreCoopAgent) myAgent).myMap;
        List<String> golemNeighbors = map.getNeighbors(lastKnownStenchNode);
        
        Set<String> takenByOthers = new HashSet<>();
        for (Map.Entry<String, String> e : claims.entrySet()) {
            if (!e.getKey().equals(myName)) {
                takenByOthers.add(e.getValue());
            }
        }

        String bestTarget = null;
        int bestDist = Integer.MAX_VALUE;

        for (String neighbor : golemNeighbors) {
            if (takenByOthers.contains(neighbor)) continue;
            if (map == null) continue;
            
            boolean exists = map.getSerializableGraph().getAllNodes().stream()
                .anyMatch(n -> n.getNodeId().equals(neighbor));
            if (!exists) continue;

            List<String> path = map.getShortestPath(cur.getLocationId(), neighbor);
            int dist = (path == null) ? Integer.MAX_VALUE : path.size();
            if (dist < bestDist) {
                bestDist = dist;
                bestTarget = neighbor;
            }
        }

        if (bestTarget != null) {
            System.out.println("[" + myName + "] → voisin libre du golem : " + bestTarget);
            myClaimedNode = bestTarget;
            broadcastClaim(me, bestTarget);
            moveToward(me, bestTarget);
        } else {
            System.out.println("[" + myName + "] Tous voisins pris → rapprochement du golem");
            moveToward(me, lastKnownStenchNode);
        }
    }

    private List<String> findStenchNodes(AbstractDedaleAgent me) {
        List<String> result = new ArrayList<>();
        Location cur = me.getCurrentPosition();
        var observations = me.observe();
        if (observations == null) return result;

        for (var nodeObs : observations) {
            String nodeId = nodeObs.getLeft().getLocationId();
            if (blockedGolems.contains(nodeId)) continue;
            if (cur != null && nodeId.equals(cur.getLocationId())) continue;

            boolean hasStench = false;
            boolean hasWumpus = false;

            for (var obs : nodeObs.getRight()) {
                if (obs.getLeft() == Observation.STENCH) hasStench = true;
                if (obs.getLeft() == Observation.AGENTNAME
                    && "Wumpus".equals(obs.getRight())) hasWumpus = true;
            }
            if (hasStench && !hasWumpus) {
                // Ne pas ajouter si un autre agent est déjà sur ce nœud
                if (!agentPositions.containsValue(nodeId)) {
                    result.add(nodeId);
                }
            }
        }
        return result;
    }

    private boolean moveToward(AbstractDedaleAgent me, String targetId) {
        Location cur = me.getCurrentPosition();
        boolean res=false;
        if (cur == null || cur.getLocationId().equals(targetId)) return res;
        if (!ensureCurrentNodeInMap(cur)) return false;
        MapRepresentation map = ((ExploreCoopAgent) me).myMap;
        if (map == null) return res;

        boolean targetExists = map.getSerializableGraph().getAllNodes().stream()
            .anyMatch(n -> n.getNodeId().equals(targetId));
        if (!targetExists) return res;

        List<String> path = map.getShortestPath(cur.getLocationId(), targetId);
        if (path == null || path.isEmpty()) return res;
        String nextNode = path.get(0);

        var obs = me.observe();
        if (obs == null) return res;
        for (var nodeObs : obs) {
            if (nodeObs.getLeft().getLocationId().equals(nextNode)) {
                res=me.moveTo(nodeObs.getLeft());
                return res;
            }
        }
        return false;
    }

    private void broadcastClaim(AbstractDedaleAgent me, String nodeId) {
    	String golemPart = lastKnownStenchNode != null ? lastKnownStenchNode : "";
        String content = "CLAIM:" + myName + ":" + nodeId + ":" + golemPart;
        for (String name : allAgentNames) {
            ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
            acl.setSender(me.getAID());
            acl.setOntology("HUNT2");
            acl.setContent(content);
            acl.addReceiver(new AID(name, AID.ISLOCALNAME));
            me.sendMessage(acl);
        }
    }

    private void receiveClaims(AbstractDedaleAgent me) {
        claims.clear();
        claims.put(myName, myClaimedNode != null ? myClaimedNode : "");

        MessageTemplate mt = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("HUNT2"));

        ACLMessage msg;
        while ((msg = me.receive(mt)) != null) {
            String content = msg.getContent();
            if (content == null || !content.startsWith("CLAIM:")) continue;
            
            String[] parts = content.substring("CLAIM:".length()).split(":", 3);
            if (parts.length >= 2) {
                claims.put(parts[0], parts[1]);
            }
            if (parts.length >= 3 && !parts[2].isEmpty()) {
                lastKnownStenchNode = parts[2];
                System.out.println("[" + myName + "] Golem signalé en " 
                    + lastKnownStenchNode + " par " + parts[0]);
            }
        }
    }

    private void clearMyClaim(AbstractDedaleAgent me) {
        myClaimedNode = null;
        String content = "CLAIM:" + myName + ":";
        for (String name : allAgentNames) {
            ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
            acl.setSender(me.getAID());
            acl.setOntology("HUNT2");
            acl.setContent(content);
            acl.addReceiver(new AID(name, AID.ISLOCALNAME));
            me.sendMessage(acl);
        }
    }
    
    private void addBlockedGolemZone(String golemNode) {
        blockedGolems.add(golemNode);
        
    }

    private void broadcastGolemBlocked(AbstractDedaleAgent me) {
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology("GOLEM-BLOCKED");

        String content = String.join(":", blockedGolems);
        acl.setContent(content);

        acl.setSender(me.getAID());
        for (String name : allAgentNames) {
            if (!name.equals(myName))
                acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        ((AbstractDedaleAgent) me).sendMessage(acl);
    }
    private void requestMap() {
        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.setProtocol("RALLY-MAP-REQUEST");
        req.setSender(myAgent.getAID());
        req.setContent(myAgent.getLocalName());
        ((AbstractDedaleAgent) myAgent).sendMessage(req);
    }
    private boolean ensureCurrentNodeInMap(Location cur) {
        if (cur == null) return false;
        String nodeId = cur.getLocationId();
        if (!myMap.containsNode(nodeId)) {
            System.out.println("[" + myName + "] Node " + nodeId + " absent de ma carte → demande carte");
            requestMap();
            return false;
        }
        return true;
    }

}