package eu.su.mas.dedaleEtu.mas.behaviours;
import jade.lang.acl.MessageTemplate;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.ExploreCoopAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import jade.core.AID;
import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class MoveToRallyPointBehaviour extends SimpleBehaviour {

    private static final long serialVersionUID = 1L;

    private final MapRepresentation myMap;
    private final List<String> allAgentNames;
    private boolean finished = false;
    private String coordinatorName = null;
    private boolean listInitSent = false;
    private int failedMoveCount = 0;
    private static final int MAX_FAILED_BEFORE_WAIT = 5;
    private final Random random = new Random();
    private final Map<String, Location> knownPositions;

    public MoveToRallyPointBehaviour(AbstractDedaleAgent agent,
                                     MapRepresentation myMap,
                                     List<String> allAgentNames,
                                     Map<String, Location> knownPositions) {
        super(agent);
        this.myMap = myMap;
        this.allAgentNames = allAgentNames;
        this.knownPositions = knownPositions;
    }

    @Override
    public void action() {
    	System.out.println( "                                            " +myAgent.getLocalName()+ " EST EN ROUTE");
    	
        // 1) Recevoir COORD
        ACLMessage coordMsg = receiveFiltered(
            jade.lang.acl.MessageTemplate.and(
                jade.lang.acl.MessageTemplate.MatchProtocol(WaitAtRallyBehaviour.PROTOCOL_COORD),
                jade.lang.acl.MessageTemplate.MatchPerformative(ACLMessage.INFORM)
            ));
        if (coordMsg != null && coordinatorName == null) {
            coordinatorName = coordMsg.getContent();
            System.out.println("[" + myAgent.getLocalName() + "] COORD reçu, leader = " + coordinatorName);
            ((ExploreCoopAgent) myAgent).getExchangeBehaviour().stopBehaviour();
        }

        // 2) Réception d'une RALLY‑ARRIVED‑LIST → basculer immédiatement
        ACLMessage listMsg = myAgent.receive(
            jade.lang.acl.MessageTemplate.and(
                jade.lang.acl.MessageTemplate.MatchProtocol(WaitAtRallyBehaviour.PROTOCOL_RALLY_ARRIVED_LIST),
                jade.lang.acl.MessageTemplate.MatchPerformative(ACLMessage.INFORM)
            ));
        if (listMsg != null) {
            String content = listMsg.getContent();
            if (content != null) {
                String[] parts = content.split(":", 2);
                String coord = parts[0];
                if (coordinatorName == null) {
                    coordinatorName = coord;
                    System.out.println("[" + myAgent.getLocalName() + "] Reçu liste, coordinateur = " + coord);
                }
                // Basculer en WaitAtRally
                String rallyPoint = myMap.getAllNodes().stream().min(Comparator.naturalOrder()).orElse(null);
                ((ExploreCoopAgent) myAgent).getExchangeBehaviour().stopBehaviour();
                myAgent.addBehaviour(new WaitAtRallyBehaviour(
                	    (AbstractDedaleAgent) myAgent, myMap, allAgentNames,
                	    coordinatorName, rallyPoint, knownPositions));
                finished = true;
                return;
            }
        }

        // 3) Rally point
        String rallyPoint = myMap.getAllNodes().stream().min(Comparator.naturalOrder()).orElse(null);
        if (rallyPoint == null) { block(Constants.stopTimeExplo); return; }

        Location currentPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition();
        if (currentPos == null) { block(Constants.stopTimeExplo); return; }

        // 4) Si on atteint le rally point
        if (currentPos.getLocationId().equals(rallyPoint)) {
            String effectiveCoord = (coordinatorName != null) ? coordinatorName : myAgent.getLocalName();
            if (!listInitSent) {
                listInitSent = true;
                // Envoyer la liste initiale avec le coordinateur
                ACLMessage initMsg = new ACLMessage(ACLMessage.INFORM);
                initMsg.setProtocol(WaitAtRallyBehaviour.PROTOCOL_RALLY_ARRIVED_LIST);
                initMsg.setConversationId(RelayBehaviour.tag(myAgent.getLocalName()));
                initMsg.setSender(myAgent.getAID());
                initMsg.setContent(effectiveCoord + ":" + myAgent.getLocalName());
                for (String name : allAgentNames) {
                    if (!name.equals(myAgent.getLocalName()))
                        initMsg.addReceiver(new AID(name, AID.ISLOCALNAME));
                }
                ((AbstractDedaleAgent) myAgent).sendMessage(initMsg);
                System.out.println("[" + myAgent.getLocalName() + "] Lancement liste (coord=" + effectiveCoord + ")");
            }
            ((ExploreCoopAgent) myAgent).getExchangeBehaviour().stopBehaviour();
            myAgent.addBehaviour(new WaitAtRallyBehaviour(
            	    (AbstractDedaleAgent) myAgent, myMap, allAgentNames,
            	    myAgent.getLocalName(), rallyPoint, knownPositions));
            finished = true;
            return;
        }

        // 5) Déplacement
        List<String> path = myMap.getShortestPath(currentPos.getLocationId(), rallyPoint);
        if (path == null || path.isEmpty()) { block(Constants.stopTimeExplo); return; }

        boolean moved = ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(path.get(0)));
        if (!moved) {
            failedMoveCount++;
            if (failedMoveCount >= MAX_FAILED_BEFORE_WAIT) {
                requestMap();
                launchHunt();
                finished=true;
                return;
            }
        } else {
            failedMoveCount = 0;
            block(Constants.stopTimeExplo);
        }
        
    }
    
    private ACLMessage receiveFiltered(MessageTemplate template) {
        ACLMessage msg = myAgent.receive(template);
        while (msg != null && RelayBehaviour.alreadyRelayedInConv(msg.getConversationId(), myAgent.getLocalName())) {
            msg = myAgent.receive(template);
        }
        return msg;
    }
    private void requestMap() {
        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.setProtocol("RALLY-MAP-REQUEST");
        req.setSender(myAgent.getAID());
        req.setContent(myAgent.getLocalName());
        ((AbstractDedaleAgent) myAgent).sendMessage(req);
    }
    private void launchHunt() {
        System.out.println("[" + myAgent.getLocalName() + "] Lancement de HuntBehaviour !");
        ((ExploreCoopAgent) myAgent).meetingPoint = null;
        myAgent.addBehaviour(new HuntBehaviour( (ExploreCoopAgent)myAgent, allAgentNames,coordinatorName));
    }

    @Override
    public boolean done() { return finished; }
}