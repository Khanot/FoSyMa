package eu.su.mas.dedaleEtu.mas.behaviours;

import java.io.IOException;
import java.util.List;

import dataStructures.serializableGraph.SerializableSimpleGraph;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedale.env.Location;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import java.util.Map;
import java.util.HashMap;
import jade.lang.acl.UnreadableException;
import jade.lang.acl.MessageTemplate;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;



/**
 * The agent periodically share its map.
 * It blindly tries to send all its graph to its friend(s)  	
 * If it was written properly, this sharing action would NOT be in a ticker behaviour and only a subgraph would be shared.

 * @author hc
 *
 */
public class ExchangeMessageBehaviour extends TickerBehaviour {
    private static final long serialVersionUID = -568867391829327961L;

    private final Map<String, Location> knownPositions;
    private final MapRepresentation myMap;
    private final List<String> receivers;
    private boolean stopped = false;

    private static final MessageTemplate TEMPLATE_POS = MessageTemplate.and(
        MessageTemplate.MatchProtocol("SHARE-POS"),
        MessageTemplate.MatchPerformative(ACLMessage.INFORM)
    );

    private static final MessageTemplate TEMPLATE_TOPO = MessageTemplate.and(
        MessageTemplate.MatchProtocol("SHARE-TOPO"),
        MessageTemplate.MatchPerformative(ACLMessage.INFORM)
    );
    
    private static final int MAP_SHARE_INTERVAL = 5;
    private static final int NODE_SHARE_THRESHOLD = 0;
    private final Map<String, Integer> lastSharedTick = new HashMap<>();
    private final Map<String, Integer> lastSharedMapSize = new HashMap<>();

    public ExchangeMessageBehaviour(Agent a, int period,
            Map<String, Location> knownPositions,
            MapRepresentation myMap,
            List<String> receivers) {
        super(a, period);
        this.knownPositions = knownPositions;
        this.myMap = myMap;
        this.receivers = receivers;
    }
    

    public void stopBehaviour() {
        this.stopped = true;
    }

    @Override
    protected void onTick() {
    	if (this.stopped) return;
    	Location currentPos = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
        if (currentPos == null) return;
        // --- réception positions ---
        ACLMessage received;
        while ((received = this.myAgent.receive(TEMPLATE_POS)) != null) {
            try {
                Object content = received.getContentObject();
                if (content instanceof Location) {
                    knownPositions.put(received.getSender().getLocalName(), (Location) content);
                }
            } catch (UnreadableException e) { e.printStackTrace(); }
        }

        ACLMessage receivedMap;
        while ((receivedMap = this.myAgent.receive(TEMPLATE_TOPO)) != null) {
            try {
                SerializableSimpleGraph<String, MapAttribute> sg =
                    (SerializableSimpleGraph<String, MapAttribute>) receivedMap.getContentObject();
                myMap.mergeMap(sg);
            } catch (UnreadableException e) { e.printStackTrace(); }
        }

        // --- envoi position ---
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol("SHARE-POS");
        msg.setSender(this.myAgent.getAID());
        for (String agentName : receivers) {
            msg.addReceiver(new AID(agentName, AID.ISLOCALNAME));
        }
        try {
            msg.setContentObject(currentPos);
        } catch (IOException e) {
            e.printStackTrace();
        }
        ((AbstractDedaleAgent) this.myAgent).sendMessage(msg);
        
        
        for (String agentName : receivers) {
            int lastTick = lastSharedTick.getOrDefault(agentName, -MAP_SHARE_INTERVAL);
            int lastSize = lastSharedMapSize.getOrDefault(agentName, 0);
            int currentSize = myMap.getNodeCount();
            
            boolean enoughTimeElapsed = getTickCount() - lastTick >= MAP_SHARE_INTERVAL;
            boolean enoughNewNodes = currentSize - lastSize >= NODE_SHARE_THRESHOLD;

            if (!(enoughTimeElapsed && enoughNewNodes)) continue;

            ACLMessage mapMsg = new ACLMessage(ACLMessage.INFORM);
            mapMsg.setProtocol("SHARE-TOPO");
            mapMsg.setSender(this.myAgent.getAID());
            mapMsg.addReceiver(new AID(agentName, AID.ISLOCALNAME));
            try {
                mapMsg.setContentObject(myMap.getSerializableGraph());
                ((AbstractDedaleAgent) this.myAgent).sendMessage(mapMsg);
                lastSharedTick.put(agentName, getTickCount() );
                lastSharedMapSize.put(agentName, currentSize);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}