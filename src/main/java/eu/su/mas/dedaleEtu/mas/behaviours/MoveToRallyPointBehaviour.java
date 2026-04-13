package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.gs.GsLocation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.ExploreCoopAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.core.behaviours.SimpleBehaviour;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MoveToRallyPointBehaviour extends SimpleBehaviour {

    private static final long serialVersionUID = 1L;
    private final MapRepresentation myMap;
    private final List<String> allAgentNames;
    private boolean finished = false;
    private String coordinatorName = null;
    private boolean arrivedSent = false;
    private int tickCount = 0;
    private static final int RELAY_INTERVAL = 10;

    private static final MessageTemplate TEMPLATE_COORD = MessageTemplate.and(
        MessageTemplate.MatchProtocol(WaitAtRallyBehaviour.PROTOCOL_COORD),
        MessageTemplate.MatchPerformative(ACLMessage.INFORM)
    );

    private static final MessageTemplate TEMPLATE_ASSIGN = MessageTemplate.and(
        MessageTemplate.MatchProtocol(WaitAtRallyBehaviour.PROTOCOL_ASSIGN),
        MessageTemplate.MatchPerformative(ACLMessage.INFORM)
    );

    public MoveToRallyPointBehaviour(AbstractDedaleAgent agent,
                                      MapRepresentation myMap,
                                      List<String> allAgentNames) {
        super(agent);
        this.myMap = myMap;
        this.allAgentNames = allAgentNames;
    }

    @Override
    public void action() {
        tickCount++;

        // 1) relayer RALLY-COORD si reçu, mémoriser le coordinateur
        ACLMessage coordMsg = myAgent.receive(TEMPLATE_COORD);
        if (coordMsg != null && coordinatorName == null) {
            coordinatorName = coordMsg.getContent();
            System.out.println("[" + myAgent.getLocalName() + "] RALLY-COORD reçu de "
                + coordinatorName);
        }

        // 2) si on reçoit ASSIGN — arrêt immédiat
        ACLMessage assignMsg = myAgent.receive(TEMPLATE_ASSIGN);
        if (assignMsg != null) {
            System.out.println("[" + myAgent.getLocalName() + "] ASSIGN reçu — arrêt");
            parseAndFinish(assignMsg.getContent());
            ((ExploreCoopAgent) myAgent).getExchangeBehaviour().stopBehaviour();
            finished = true;
            return;
        }

        // relay périodique pour propager RALLY-COORD aux agents encore loin
        if (coordinatorName != null && tickCount % RELAY_INTERVAL == 0) {
            relayCoordAnnounce();
        }

     // ARRIVED une seule fois dès que coordinateur connu
        if (coordinatorName != null && !arrivedSent) {
            sendArrived();
            arrivedSent = true;
            ((ExploreCoopAgent) myAgent).getExchangeBehaviour().stopBehaviour();
        }

        // 3) calcul rally point
        List<String> openNodes = myMap.getOpenNodes();
        String rallyPoint = myMap.getAllNodes().stream()
            .filter(id -> !openNodes.contains(id))
            .min(Comparator.naturalOrder())
            .orElse(null);

        if (rallyPoint == null) { block(500); return; }

        Location currentPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition();
        if (currentPos == null) { block(500); return; }

        // 4) arrivé au rally sans coordinateur connu — je suis le coordinateur
        if (currentPos.getLocationId().equals(rallyPoint) && coordinatorName == null) {
            System.out.println("[" + myAgent.getLocalName() + "] Arrivé au rally, je suis le coordinateur");
            ((ExploreCoopAgent) myAgent).getExchangeBehaviour().stopBehaviour();
            myAgent.addBehaviour(new WaitAtRallyBehaviour(
                (AbstractDedaleAgent) myAgent, myMap, allAgentNames));
            finished = true;
            return;
        }


        // si ARRIVED déjà envoyé, juste attendre ASSIGN sans bouger
        if (arrivedSent) {
            block(500);
            return;
        }
        
        // 5) se déplacer vers le rally
        List<String> path = myMap.getShortestPath(currentPos.getLocationId(), rallyPoint);
        if (path == null || path.isEmpty()) { block(1000); return; }
        boolean moved = ((AbstractDedaleAgent) myAgent).moveTo(new GsLocation(path.get(0)));
        if (!moved) {
            block(500); // bloqué par un autre agent, attendre
            return;
        }
    }

    private void relayCoordAnnounce() {
        ACLMessage relay = new ACLMessage(ACLMessage.INFORM);
        relay.setProtocol(WaitAtRallyBehaviour.PROTOCOL_COORD);
        relay.setSender(myAgent.getAID());
        relay.setContent(coordinatorName);
        for (String name : allAgentNames) {
            if (!name.equals(myAgent.getLocalName()) && !name.equals(coordinatorName)) {
                relay.addReceiver(new AID(name, AID.ISLOCALNAME));
            }
        }
        ((AbstractDedaleAgent) myAgent).sendMessage(relay);
    }

    private void sendArrived() {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(WaitAtRallyBehaviour.PROTOCOL_ARRIVED);
        msg.setSender(myAgent.getAID());
        msg.addReceiver(new AID(coordinatorName, AID.ISLOCALNAME));
        msg.setContent(myAgent.getLocalName());
        ((AbstractDedaleAgent) myAgent).sendMessage(msg);
    }

    private void parseAndFinish(String content) {
        Map<String, Integer> assignment = new LinkedHashMap<>();
        for (String token : content.split(",")) {
            String[] parts = token.split(":");
            assignment.put(parts[0], Integer.parseInt(parts[1]));
        }
        int myGroup = assignment.getOrDefault(myAgent.getLocalName(), 0);
        List<String> myGroupMembers = assignment.entrySet().stream()
            .filter(e -> e.getValue() == myGroup)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        System.out.println("[" + myAgent.getLocalName() + "] Groupe " + myGroup
            + " — membres : " + myGroupMembers);
    }

    @Override
    public boolean done() {
        return finished;
    }
}