package eu.su.mas.dedaleEtu.mas.behaviours;



import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import jade.core.AID;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RelayBehaviour extends CyclicBehaviour {

    private static final long serialVersionUID = 1L;

    public static final String RELAY_PREFIX = "RELAY|";
    private static final String HOP_SEP = ",";

    private final List<String> allAgentNames;
    private final MessageTemplate watchTemplate;

    public RelayBehaviour(AbstractDedaleAgent agent,
                          List<String> allAgentNames,
                          List<String> protocols) {
        super(agent);
        this.allAgentNames = allAgentNames;

        MessageTemplate protocolFilter = buildProtocolFilter(protocols);
        MessageTemplate relayFilter = new MessageTemplate(msg -> {
            String cid = msg.getConversationId();
            return cid != null && cid.startsWith(RELAY_PREFIX);
        });
        this.watchTemplate = MessageTemplate.and(relayFilter, protocolFilter);
    }

    @Override
    public void action() {
        ACLMessage msg = myAgent.receive(watchTemplate);
        if (msg == null) { block(); return; }

        String myName     = myAgent.getLocalName();
        String senderName = msg.getSender().getLocalName();
        String convId     = msg.getConversationId();

        if (alreadyRelayed(convId, myName)) return;

        String newConvId = convId + HOP_SEP + myName;

        String protocol = msg.getProtocol();
        // Enrichissement pour les deux protocoles de liste
       
        if (WaitAtRallyBehaviour.PROTOCOL_RALLY_ARRIVED_LIST.equals(protocol)) {
            // Ralliement : pas de condition de cible, on garde le comportement existant
            String content = msg.getContent();
            if (content != null) {
                String[] parts = content.split(":", 2);
                if (parts.length == 2) {
                    String coord = parts[0];
                    List<String> names = new ArrayList<>(Arrays.asList(parts[1].split(",")));
                    names.removeIf(String::isEmpty);
                    if (!names.contains(myName)) {
                        names.add(myName);
                    }
                    msg.setContent(coord + ":" + String.join(",", names));
                }
            }
        } 

        ACLMessage relay = (ACLMessage) msg.clone();
        relay.clearAllReceiver();
        relay.setSender(myAgent.getAID());
        relay.setConversationId(newConvId);
        relay.clearAllReplyTo();
        relay.addReplyTo(msg.getSender());

        int count = 0;
        for (String name : allAgentNames) {
            if (!name.equals(myName) && !name.equals(senderName)) {
                relay.addReceiver(new AID(name, AID.ISLOCALNAME));
                count++;
            }
        }

        if (count > 0) {
            System.out.println("[" + myName + "] RELAY " + msg.getProtocol()
                    + " from " + senderName + " → " + count + " agents");
            ((AbstractDedaleAgent) myAgent).sendMessage(relay);
        }
    }

    public static String tag(String senderLocalName) {
        return RELAY_PREFIX + senderLocalName;
    }

    private static boolean alreadyRelayed(String convId, String myName) {
        String hops = convId.substring(RELAY_PREFIX.length());
        for (String hop : hops.split(HOP_SEP)) {
            if (hop.equals(myName)) return true;
        }
        return false;
    }

    private static MessageTemplate buildProtocolFilter(List<String> protocols) {
        if (protocols.isEmpty())
            return MessageTemplate.MatchProtocol("__NONE__");
        MessageTemplate t = MessageTemplate.MatchProtocol(protocols.get(0));
        for (int i = 1; i < protocols.size(); i++)
            t = MessageTemplate.or(t, MessageTemplate.MatchProtocol(protocols.get(i)));
        return t;
    }
    
    /**
     * Vérifie si le nom de l'agent apparaît déjà dans la chaîne des relayeurs
     * contenue dans la conversationId.
     */
    public static boolean alreadyRelayedInConv(String convId, String myName) {
        if (convId == null || !convId.startsWith(RELAY_PREFIX)) return false;
        String hops = convId.substring(RELAY_PREFIX.length());
        for (String hop : hops.split(HOP_SEP)) {
            if (hop.equals(myName)) return true;
        }
        return false;
    }
}