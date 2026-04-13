package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.Iterator;
import java.util.List;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.ExploreCoopAgent;

import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;

import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

import java.util.Map;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * <pre>
 * This behaviour allows an agent to explore the environment and learn the associated topological map.
 * The algorithm is a pseudo - DFS computationally consuming because its not optimised at all.
 * 
 * When all the nodes around him are visited, the agent randomly select an open node and go there to restart its dfs. 
 * This (non optimal) behaviour is done until all nodes are explored. 
 * 
 * Warning, this behaviour does not save the content of visited nodes, only the topology.
 * Warning, the sub-behaviour ShareMap periodically share the whole map
 * </pre>
 * @author hc
 *
 */
public class ExploCoopBehaviour extends SimpleBehaviour {

    private static final long serialVersionUID = 8567689731496787661L;

    private boolean finished = false;
    private Location myPos;
    private MapRepresentation myMap;
    private List<String> list_agentNames;
    private final Map<String, Location> knownPositions;

    public ExploCoopBehaviour(final AbstractDedaleAgent myagent, MapRepresentation myMap,
            List<String> agentNames, Map<String, Location> knownPositions) {
        super(myagent);
        this.myMap = myMap;
        this.list_agentNames = agentNames;
        this.knownPositions = knownPositions;
    }

    @Override
    public void action() {
        List<Couple<Location, List<Couple<Observation, String>>>> lobs =
                ((AbstractDedaleAgent) this.myAgent).observe();

       

        // 0) position courante
        Location myPosition = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();

        if (myPosition != null) {
            try { this.myAgent.doWait(1000); } catch (Exception e) { e.printStackTrace(); }

            // 1) marquer le noeud courant comme closed
            this.myMap.addNode(myPosition.getLocationId(), MapAttribute.closed);

            // 2) ajouter les noeuds voisins
            String nextNodeId = null;
            Iterator<Couple<Location, List<Couple<Observation, String>>>> iter = lobs.iterator();
            while (iter.hasNext()) {
                Location accessibleNode = iter.next().getLeft();
                boolean isNewNode = this.myMap.addNewNode(accessibleNode.getLocationId());
                if (myPosition.getLocationId() != accessibleNode.getLocationId()) {
                    this.myMap.addEdge(myPosition.getLocationId(), accessibleNode.getLocationId());
                    if (nextNodeId == null && isNewNode) nextNodeId = accessibleNode.getLocationId();
                }
            }

            // 3) vérifier si l'exploration est terminée
            if (!this.myMap.hasOpenNode()) {
                finished = true;
                myAgent.addBehaviour(new MoveToRallyPointBehaviour(
                    (AbstractDedaleAgent) myAgent,
                    this.myMap,
                    this.list_agentNames  // déjà disponible dans ExploCoopBehaviour
                ));
            }else {
                // 4) choisir le prochain noeud en tenant compte des positions a priori
                nextNodeId = chooseBestOpenNode(myPosition.getLocationId());

                // 5) se déplacer
             // 5) se déplacer
                String targetNodeId = chooseBestOpenNode(myPosition.getLocationId());

                List<String> path = this.myMap.getShortestPath(myPosition.getLocationId(), targetNodeId);
                if (path != null && !path.isEmpty()) {
                    ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(path.get(0)));
                } else {
                    // noeud inaccessible, on prend le plus proche direct
                    ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(targetNodeId));
                }
            }
        }
    }

    /**
     * Parcourt les noeuds ouverts du plus proche au plus loin.
     * Pour chacun, vérifie si un autre agent est a priori plus proche.
     * Retourne le premier noeud pour lequel on est le plus proche.
     */
    private String chooseBestOpenNode(String myPositionId) {
        List<String> openNodes = this.myMap.getOpenNodes();

        // trier les noeuds ouverts par distance croissante depuis ma position
        List<Couple<String, Integer>> sortedNodes = openNodes.stream()
            .map(node -> {
                List<String> path = this.myMap.getShortestPath(myPositionId, node);
                int dist = (path != null) ? path.size() : Integer.MAX_VALUE;
                return new Couple<>(node, dist);
            })
            .sorted(Comparator.comparing(Couple::getRight))
            .collect(Collectors.toList());

        for (Couple<String, Integer> candidate : sortedNodes) {
            String nodeId = candidate.getLeft();
            int myDist = candidate.getRight();
            if (myDist == Integer.MAX_VALUE) continue;

            // vérifier si un autre agent est plus proche de ce noeud
            boolean anotherAgentCloser = knownPositions.entrySet().stream()
            	    .filter(e -> !e.getKey().equals(this.myAgent.getLocalName()))
            	    .filter(e -> this.myMap.containsNode(e.getValue().getLocationId())) 
            	    .anyMatch(e -> {
            	        List<String> agentPath = this.myMap.getShortestPath(
            	            e.getValue().getLocationId(), nodeId);
            	        int agentDist = (agentPath != null) ? agentPath.size() : Integer.MAX_VALUE;
            	        return agentDist < myDist;
            	    });

            if (!anotherAgentCloser) {
                return nodeId;
            }
        }

        // tous les noeuds ont un agent plus proche — prendre le plus proche quand même
        return sortedNodes.get(0).getLeft();
    }

    @Override
    public boolean done() {
        return finished;
    }
}