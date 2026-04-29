package eu.su.mas.dedaleEtu.mas.behaviours;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.ExploreCoopAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import jade.core.AID;
import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

import eu.su.mas.dedaleEtu.mas.behaviours.Constants;

import java.io.IOException;
import java.util.*;

public class WaitAtRallyBehaviour extends SimpleBehaviour {

    private static final long serialVersionUID = 1L;

    public static final String PROTOCOL_COORD               = "RALLY-COORD";
    public static final String PROTOCOL_RALLY_ARRIVED_LIST  = "RALLY-ARRIVED-LIST";
    public static final String PROTOCOL_FINAL_MAP           = "FINAL-MAP";

    private static final MessageTemplate TEMPLATE_RALLY_ARRIVED_LIST = MessageTemplate.and(
        MessageTemplate.MatchProtocol(PROTOCOL_RALLY_ARRIVED_LIST),
        MessageTemplate.MatchPerformative(ACLMessage.INFORM)
    );
    private static final MessageTemplate TEMPLATE_FINAL_MAP = MessageTemplate.and(
        MessageTemplate.MatchProtocol(PROTOCOL_FINAL_MAP),
        MessageTemplate.MatchPerformative(ACLMessage.INFORM)
    );

    private final MapRepresentation myMap;
    private final List<String> allAgentNames;
    private final String coordinatorName;
    private final boolean isLeader;
    private final Map<String, Location> knownPositions;

    private boolean finished = false;
    private int tickCount = 0;
    private String rallyPoint;
    private boolean mapExchangeDone = false;
    private boolean hasSharedOwnMap = false;
    private int waitTicksAfterMap = 0;
    private boolean huntLaunched = false;
    private static int MAXTICKCOUNT=25;
    // Phase de ralliement
    private final Set<String> knownRallyArrived = new HashSet<>();
    private long lastRallyListTime = 0;

    public WaitAtRallyBehaviour(AbstractDedaleAgent agent,
                                MapRepresentation myMap,
                                List<String> allAgentNames,
                                String coordinatorName,
                                String rallyPoint,
                                Map<String, Location> knownPositions) {
        super(agent);
        this.myMap = myMap;
        this.allAgentNames = new ArrayList<>(allAgentNames);
        this.coordinatorName = coordinatorName;
        this.isLeader = agent.getLocalName().equals(coordinatorName);
        this.rallyPoint = rallyPoint;
        this.knownPositions = knownPositions;

        
        knownRallyArrived.add(agent.getLocalName());
        
    }

    @Override
    public void action() {
    	
        tickCount++;
       
        System.out.println( "                                            " +myAgent.getLocalName()+ " EST EN ATTENTE");
        
        moveTowardsRallyPoint();
            
        
        if (isLeader) {
            leaderAction();
        } else {
            followerAction();
        }
        block(Constants.stopTimeHunt);
    }

    // =========================== LEADER =============================
    private void leaderAction() {
    	
        if (tickCount %3 == 0) broadcastCoord();

        // 1) Traiter les messages RALLY-ARRIVED-LIST
        ACLMessage listMsg = receiveFiltered(TEMPLATE_RALLY_ARRIVED_LIST);
        if (listMsg != null) {
            String content = listMsg.getContent();
            if (content != null) {
                String[] parts = content.split(":", 2);
                List<String> names = new ArrayList<>();
                if (parts.length == 2) {
                    names.addAll(Arrays.asList(parts[1].split(",")));
                }
                int oldSize = knownRallyArrived.size();
                knownRallyArrived.addAll(names);
                if (knownRallyArrived.size() > oldSize) {
                    sendPartialRallyList();
                }
                if (knownRallyArrived.containsAll(allAgentNames) && !mapExchangeDone) {
                    System.out.println("[" + myAgent.getLocalName() + "] Rally terminé, échange des cartes...");
                    sendFinalMapOnce();
                    mapExchangeDone = true;
                    waitTicksAfterMap = 0;
                }
            }
        }

        // 2) Envoi périodique (toutes les 2s)
        long now = System.currentTimeMillis();
        if (now - lastRallyListTime > 500) {
            sendPartialRallyList();
        }

        // 3) Transition vers la chasse
        if ((mapExchangeDone && !huntLaunched) || tickCount>MAXTICKCOUNT) {
            waitTicksAfterMap++;
            if (waitTicksAfterMap >= 5) {
                launchHunt();
                huntLaunched = true;
                finished = true;
                return;
            }
        }
    }

    // ========================== FOLLOWER ============================
    private void followerAction() {
        // Cartes
        ACLMessage finalMapMsg = receiveFiltered(TEMPLATE_FINAL_MAP);
        if (finalMapMsg != null) {
            try {
                @SuppressWarnings("unchecked")
                SerializableSimpleGraph<String, MapAttribute> sg =
                    (SerializableSimpleGraph<String, MapAttribute>) finalMapMsg.getContentObject();
                myMap.mergeMap(sg);
                if (!hasSharedOwnMap) {
                    sendFinalMapOnce();
                    hasSharedOwnMap = true;
                }
            } catch (UnreadableException e) { e.printStackTrace(); }
        }
        ACLMessage listMsg = receiveFiltered(TEMPLATE_RALLY_ARRIVED_LIST);
        if (listMsg != null) {
            String content = listMsg.getContent();
            if (content != null) {
                String[] parts = content.split(":", 2);
                List<String> names = new ArrayList<>();
                if (parts.length == 2) {
                    names.addAll(Arrays.asList(parts[1].split(",")));
                }
                int oldSize = knownRallyArrived.size();
                knownRallyArrived.addAll(names);
                if (knownRallyArrived.size() > oldSize) {
                    sendPartialRallyList();
                }
                if (knownRallyArrived.containsAll(allAgentNames) && !mapExchangeDone) {
                    System.out.println("[" + myAgent.getLocalName() + "] Rally terminé, échange des cartes...");
                    sendFinalMapOnce();
                    mapExchangeDone = true;
                    waitTicksAfterMap = 0;
                }
            }
        }


        if (tickCount % 20 == 0) broadcastCoord();
        if (tickCount % 20 == 0) sendPartialRallyList();

        // Quand le follower a sa carte ET a partagé la sienne, il lance la chasse
        if ((mapExchangeDone && !huntLaunched) || tickCount>MAXTICKCOUNT) {
            launchHunt();
            huntLaunched = true;
            finished = true;
            return;
        }
    }

    private void launchHunt() {
        System.out.println("[" + myAgent.getLocalName() + "] Lancement de HuntBehaviour !");
        ((ExploreCoopAgent) myAgent).meetingPoint = rallyPoint;
        myAgent.addBehaviour(new HuntBehaviour( (ExploreCoopAgent)myAgent, allAgentNames,coordinatorName));
    }

    // ===================== MÉTHODES D'ENVOI ==========================
    private void sendFinalMapOnce() {
        try {
            ACLMessage mapMsg = new ACLMessage(ACLMessage.INFORM);
            mapMsg.setProtocol(PROTOCOL_FINAL_MAP);
            mapMsg.setConversationId(RelayBehaviour.tag(myAgent.getLocalName()));
            mapMsg.setSender(myAgent.getAID());
            for (String name : allAgentNames) {
                if (!name.equals(myAgent.getLocalName()))
                    mapMsg.addReceiver(new AID(name, AID.ISLOCALNAME));
            }
            mapMsg.setContentObject(myMap.getSerializableGraph());
            ((AbstractDedaleAgent) myAgent).sendMessage(mapMsg);
            System.out.println("[" + myAgent.getLocalName() + "] Envoi carte (FINAL-MAP)");
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void broadcastCoord() {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_COORD);
        msg.setSender(myAgent.getAID());
        msg.setContent(coordinatorName);
        for (String name : allAgentNames) {
            if (!name.equals(myAgent.getLocalName()))
                msg.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        ((AbstractDedaleAgent) myAgent).sendMessage(msg);
    }

    private void sendPartialRallyList() {
        if (knownRallyArrived.isEmpty()) return;
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_RALLY_ARRIVED_LIST);
        msg.setConversationId(RelayBehaviour.tag(myAgent.getLocalName()) + "-" + System.currentTimeMillis());
        msg.setSender(myAgent.getAID());
        msg.setContent(coordinatorName + ":" + String.join(",", knownRallyArrived));
        for (String name : allAgentNames) {
            if (!name.equals(myAgent.getLocalName()))
                msg.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        ((AbstractDedaleAgent) myAgent).sendMessage(msg);
        lastRallyListTime = System.currentTimeMillis();
        System.out.println("[" + myAgent.getLocalName() + "] Envoi liste ralliement partielle : " + knownRallyArrived);
    }

    // ======================= UTILITAIRE ==============================
    private ACLMessage receiveFiltered(MessageTemplate template) {
        ACLMessage msg = myAgent.receive(template);
        while (msg != null
                && !msg.getSender().getLocalName().equals(myAgent.getLocalName())
                && RelayBehaviour.alreadyRelayedInConv(msg.getConversationId(), myAgent.getLocalName())) {
            msg = myAgent.receive(template);
        }
        return msg;
    }
    private void moveTowardsRallyPoint() {
        AbstractDedaleAgent me = (AbstractDedaleAgent) myAgent;
        Location cur = me.getCurrentPosition();
        if (cur == null) { block(Constants.stopTimeHunt); return; }

        

        // Se déplacer vers le rally point
        List<String> path = myMap.getShortestPath(cur.getLocationId(), rallyPoint);
        if (path != null && !path.isEmpty()) {
            boolean moved = me.moveTo(new GsLocation(path.get(0)));
            if (!moved) {
                // gestion d’échec simple
                block(Constants.stopTimeHunt * 2);
            } else {
                block(Constants.stopTimeHunt);
            }
        } else {
            block(Constants.stopTimeHunt);
        }
    }

    @Override
    public boolean done() { return finished; }
}