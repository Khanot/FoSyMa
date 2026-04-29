package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedale.princ.ConfigurationFile;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.ExploreCoopAgent;

import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;

import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Collectors;
import eu.su.mas.dedaleEtu.mas.behaviours.Constants;
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
    
    private String currentTarget = null;
    private int failedMoveCount = 0;
    private int failedFinal = 0;
    private static final int MAX_FAILED_MOVES = 3;
    private Map<String, Integer> blockedNodes = new HashMap<>();
    private static final int BLOCK_DURATION = 5;
    public ExploCoopBehaviour(final AbstractDedaleAgent myagent, MapRepresentation myMap,
            List<String> agentNames, Map<String, Location> knownPositions) {
        super(myagent);
        this.myMap = myMap;
        this.list_agentNames = agentNames;
        this.knownPositions = knownPositions;
    }
    private static final MessageTemplate TEMPLATE_COORD = MessageTemplate.and(
    	    MessageTemplate.MatchProtocol(WaitAtRallyBehaviour.PROTOCOL_COORD),
    	    MessageTemplate.MatchPerformative(ACLMessage.INFORM)
    	);
    @Override
    public void action() {
    	System.out.println( "                                            " +myAgent.getLocalName()+ " EST EN EXPLO");
    	
    	blockedNodes.replaceAll((node, ticks) -> ticks - 1);
    	blockedNodes.entrySet().removeIf(e -> e.getValue() <= 0);
    	
    	ACLMessage coordMsg = myAgent.receive(TEMPLATE_COORD);
        if (coordMsg != null) {
            
            System.out.println("[" + myAgent.getLocalName() + "] RALLY-COORD reçu pendant exploration"
                + " — demande carte" );
            // demander la carte à l'expéditeur (à portée)
            requestMap();
            // passer directement à MoveToRallyPoint
            finished = true;
            myAgent.addBehaviour(new MoveToRallyPointBehaviour(
                (AbstractDedaleAgent) myAgent, myMap, list_agentNames, knownPositions));
            return;
        }
    	
        List<Couple<Location, List<Couple<Observation, String>>>> lobs =
                ((AbstractDedaleAgent) this.myAgent).observe();

       

        // 0) position courante
        Location myPosition = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();

        if (myPosition != null) {
            try { block(Constants.stopTimeExplo); } catch (Exception e) { e.printStackTrace(); }

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
                    this.list_agentNames,
                    this.knownPositions// déjà disponible dans ExploCoopBehaviour
                ));
            }else {
            	// 4) Décider si on doit choisir une nouvelle cible
            	boolean needNewTarget = false;

            	if (currentTarget == null) {
            	    // Première exécution
            	    needNewTarget = true;
            	} else if (myPosition.getLocationId().equals(currentTarget)) {
            	    // Arrivé à destination
            	    System.out.println("[" + myAgent.getLocalName() + "] Cible atteinte : " + currentTarget);
            	    needNewTarget = true;
            	} else if (!this.myMap.getOpenNodes().contains(currentTarget)) {
            	    // La cible n'est plus ouverte (explorée par un autre agent)
            	    System.out.println("[" + myAgent.getLocalName() + "] Cible " + currentTarget + " n'est plus ouverte — nouvelle cible");
            	    needNewTarget = true;
            	}

            	if (needNewTarget) {
            	    currentTarget = chooseBestOpenNodeExcludingPath(myPosition.getLocationId());
            	    failedMoveCount = 0;
            	    System.out.println("[" + myAgent.getLocalName() + "] Nouvelle cible : " + currentTarget);
            	}

            	// 5) Se déplacer vers la cible courante
            	List<String> path = this.myMap.getShortestPath(myPosition.getLocationId(), currentTarget);

            	if (path != null && !path.isEmpty()) {
            	    boolean moved = ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(path.get(0)));
            	    if (!moved) {
            	        failedMoveCount++;
            	        if (failedMoveCount >= MAX_FAILED_MOVES) {
            	            String blockedNode = path.get(0);
            	            blockedNodes.put(blockedNode, BLOCK_DURATION);
            	            System.out.println("[" + myAgent.getLocalName() + "] Bloqué via " + blockedNode + " — nouvelle cible");
            	            currentTarget = chooseBestOpenNodeExcludingPath(myPosition.getLocationId());
            	            failedMoveCount = 0;
            	            failedFinal++;
            	            if (failedFinal > MAX_FAILED_MOVES) {
            	                System.out.println("[" + myAgent.getLocalName() + "] Bloquer, je lance la chasse directement !");
            	                requestMap(); // tente une dernière mise à jour de la carte
            	                String rallyPoint = myMap.getAllNodes().stream().min(Comparator.naturalOrder()).orElse(null);
            	                ((ExploreCoopAgent) myAgent).meetingPoint = rallyPoint;

            	                // Le coordinateur est le premier agent dans la liste triée (ordre alpha)
            	                List<String> sortedAgents = new ArrayList<>(list_agentNames);
            	                sortedAgents.add(myAgent.getLocalName());
            	                Collections.sort(sortedAgents);
            	                String coordinator = sortedAgents.get(0);

            	                finished = true;
            	                myAgent.addBehaviour(new HuntBehaviour((ExploreCoopAgent) myAgent, list_agentNames, coordinator));
            	                return;
            	            }
            	        }
            	        
            	    } else {
            	        failedMoveCount = 0;
            	    }
            	} else {
            	    // Aucun chemin trouvé — choisir une autre cible
            	    currentTarget = chooseBestOpenNodeExcludingPath(myPosition.getLocationId());
            	    failedMoveCount = 0;
            	    block(Constants.stopTimeExplo);
            	}            }
        }
    }
    private void requestMap() {
        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.setProtocol("RALLY-MAP-REQUEST");
        req.setSender(myAgent.getAID());
        req.setContent(myAgent.getLocalName());
        ((AbstractDedaleAgent) myAgent).sendMessage(req);
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
    private String chooseBestOpenNodeExcludingPath(String myPositionId) {
        List<String> openNodes = this.myMap.getOpenNodes();

        List<Couple<String, Integer>> sortedNodes = openNodes.stream()
            .map(node -> {
                List<String> path = this.myMap.getShortestPath(myPositionId, node);
                // exclure les chemins qui passent par le noeud bloqué
                if (path == null ||path.isEmpty() || blockedNodes.containsKey(path.get(0))) {
                    return new Couple<>(node, Integer.MAX_VALUE);
                }
                return new Couple<>(node, path.size());
            })
            .filter(c -> c.getRight() < Integer.MAX_VALUE)
            .sorted(Comparator.comparing(Couple::getRight))
            .collect(Collectors.toList());

        if (sortedNodes.isEmpty()) {
            // tous les chemins passent par le noeud bloqué — attendre
            return chooseBestOpenNode(myPositionId);
        }

        for (Couple<String, Integer> candidate : sortedNodes) {
            String nodeId = candidate.getLeft();
            int myDist = candidate.getRight();

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
        if (sortedNodes.isEmpty()) {
            // fallback SANS contrainte
            return chooseBestOpenNode(myPositionId);
        }
        return sortedNodes.get(0).getLeft();
    }

    @Override
    public boolean done() {
        return finished;
    }
}