package eu.su.mas.dedaleEtu.mas.agents.dummies.explo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedale.mas.agent.behaviours.platformManagment.StartMyBehaviours;
import eu.su.mas.dedale.princ.ConfigurationFile;
import eu.su.mas.dedaleEtu.mas.behaviours.Constants;
import eu.su.mas.dedaleEtu.mas.behaviours.ExchangeMessageBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.ExploCoopBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.RelayBehaviour;
import eu.su.mas.dedaleEtu.mas.behaviours.WaitAtRallyBehaviour;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import jade.core.behaviours.Behaviour;
import jade.util.leap.HashSet;
import jade.util.leap.Set;
import javafx.application.Platform;

import java.util.Collections;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExploreCoopAgent extends AbstractDedaleAgent {

    private static final long serialVersionUID = -7969469610241668140L;

    public MapRepresentation myMap;
    private Map<String, Location> knownPositions = new HashMap<>();
    private ExchangeMessageBehaviour exchangeBehaviour;
    public boolean huntStarted = false;
    private String currentHuntTarget; // mis à jour lors des changements de cible

    public String meetingPoint;
    
    
    protected void setup() {
        super.setup();
        

        List<String> list_agentNames = new ArrayList<>();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(
                ConfigurationFile.INSTANCE_CONFIGURATION_ENTITIES)) {
            if (is != null) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                // Cherche toutes les chaînes du type : "agentName" : "Elsa"
                Pattern pattern = Pattern.compile("\"agentName\"\\s*:\\s*\"([^\"]+)\"");
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    String name = matcher.group(1);
                    if (!name.equals(getLocalName())) {
                        list_agentNames.add(name);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        list_agentNames.remove(this.getLocalName());

        try { Platform.startup(() -> {}); }
        catch (IllegalStateException e) { /* already initialised */ }

        this.myMap = new MapRepresentation(this.getLocalName());
        this.exchangeBehaviour = new ExchangeMessageBehaviour(
            this,Constants.stopTimeExplo*10, this.knownPositions, this.myMap, list_agentNames);

        // ── Protocols that need range extension via relay ──────────────────
        // Include every protocol where sender and receiver may be out of range.
        // TRIGGER-SHARE and FINAL-MAP are NOT included: they're only sent when
        // everyone is already at the rally point (all within range).
        List<String> relayedProtocols = List.of(
            WaitAtRallyBehaviour.PROTOCOL_COORD,
         
            WaitAtRallyBehaviour.PROTOCOL_FINAL_MAP,
        
            WaitAtRallyBehaviour.PROTOCOL_RALLY_ARRIVED_LIST
        );
        

        List<Behaviour> lb = new ArrayList<>();
        lb.add(this.exchangeBehaviour);
        lb.add(new ExploCoopBehaviour(this, this.myMap, list_agentNames, this.knownPositions));

        // RelayBehaviour runs permanently in parallel — add it to the initial
        // behaviour list so it starts together with the others.
        //lb.add(new RelayBehaviour(this, list_agentNames, relayedProtocols));

        addBehaviour(new StartMyBehaviours(this, lb));

        System.out.println("Agent " + this.getLocalName() + " started");
    }

    public ExchangeMessageBehaviour getExchangeBehaviour() {
        return this.exchangeBehaviour;
    }

    protected void takeDown() { super.takeDown(); }

    protected void beforeMove() {
        super.beforeMove();
        if (this.myMap != null) this.myMap.prepareMigration();
    }

    protected void afterMove() {
        super.afterMove();
        if (this.myMap != null) this.myMap.loadSavedData();
    }
    public String getCurrentHuntTarget() {
        return currentHuntTarget;
    }
}
