package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import jade.core.AID;
import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.*;
import java.util.stream.Collectors;

public class WaitAtRallyBehaviour extends SimpleBehaviour {

    private static final long serialVersionUID = 1L;

    public static final String PROTOCOL_COORD        = "RALLY-COORD";
    public static final String PROTOCOL_ARRIVED      = "RALLY-ARRIVED";
    public static final String PROTOCOL_ASSIGN       = "RALLY-ASSIGN";

    private static final MessageTemplate TEMPLATE_ARRIVED = MessageTemplate.and(
        MessageTemplate.MatchProtocol(PROTOCOL_ARRIVED),
        MessageTemplate.MatchPerformative(ACLMessage.INFORM)
    );

    private final MapRepresentation myMap;
    private final List<String> allAgentNames;

    private boolean finished = false;
    private final Set<String> arrivedAgents = new HashSet<>();
    private boolean assignmentDone = false;
    private int tickCount = 0;

    public WaitAtRallyBehaviour(AbstractDedaleAgent agent,
                                 MapRepresentation myMap,
                                 List<String> allAgentNames) {
        super(agent);
        this.myMap = myMap;
        this.allAgentNames = new ArrayList<>(allAgentNames);
        // s'ajouter soi-même immédiatement
        arrivedAgents.add(agent.getLocalName());
    }

    @Override
    public void action() {
        // 1) broadcaster périodiquement "je suis le coordinateur"
        // toutes les ~5 ticks pour ne pas saturer
        if (tickCount % 5 == 0) {
            broadcastCoordAnnounce();
        }
        tickCount++;

        // 2) collecter les ARRIVED
        ACLMessage msg;
        while ((msg = myAgent.receive(TEMPLATE_ARRIVED)) != null) {
            arrivedAgents.add(msg.getSender().getLocalName());
            System.out.println("[" + myAgent.getLocalName() + "] ARRIVED reçu de "
                + msg.getSender().getLocalName()
                + " (" + arrivedAgents.size() + "/" + allAgentNames.size() + ")");
        }

        // 3) tous arrivés ?
        if (!assignmentDone && arrivedAgents.containsAll(allAgentNames)) {
            Map<String, Integer> assignment = computeGroups(new ArrayList<>(allAgentNames),1);
            broadcastAssignment(assignment);
            assignmentDone = true;

            int myGroup = assignment.get(myAgent.getLocalName());
            List<String> myGroupMembers = assignment.entrySet().stream()
                .filter(e -> e.getValue() == myGroup)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

            System.out.println("[" + myAgent.getLocalName() + "] Groupe " + myGroup
                + " — membres : " + myGroupMembers);
            finished = true;
        } else {
            block(300);
        }
    }

    private void broadcastCoordAnnounce() {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol(PROTOCOL_COORD);
        msg.setSender(myAgent.getAID());
        msg.setContent(myAgent.getLocalName()); // nom du coordinateur
        for (String name : allAgentNames) {
            if (!name.equals(myAgent.getLocalName())) {
                msg.addReceiver(new AID(name, AID.ISLOCALNAME));
            }
        }
        ((AbstractDedaleAgent) myAgent).sendMessage(msg);
    }

    private Map<String, Integer> computeGroups(List<String> agents,int wumpus_size) {
        int nbGroups = Math.max(1, (int) Math.round(agents.size()/wumpus_size));
        Collections.sort(agents);
        Map<String, Integer> assignment = new LinkedHashMap<>();
        for (int i = 0; i < agents.size(); i++) {
            assignment.put(agents.get(i), i % nbGroups);
        }
        System.out.println("[" + myAgent.getLocalName() + "] "
            + nbGroups + " groupes pour " + agents.size() + " agents : " + assignment);
        return assignment;
    }

    private void broadcastAssignment(Map<String, Integer> assignment) {
        String content = assignment.entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue())
            .collect(Collectors.joining(","));

        for (String name : allAgentNames) {
            if (name.equals(myAgent.getLocalName())) continue;
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.setProtocol(PROTOCOL_ASSIGN);
            msg.setSender(myAgent.getAID());
            msg.addReceiver(new AID(name, AID.ISLOCALNAME));
            msg.setContent(content);
            ((AbstractDedaleAgent) myAgent).sendMessage(msg);
        }
    }

    @Override
    public boolean done() { return finished; }
}