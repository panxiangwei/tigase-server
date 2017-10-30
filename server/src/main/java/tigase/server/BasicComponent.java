/*
 * BasicComponent.java
 *
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2017 "Tigase, Inc." <office@tigase.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */

package tigase.server;

//~--- non-JDK imports --------------------------------------------------------

import tigase.cluster.api.ClusterControllerIfc;
import tigase.cluster.api.ClusteredComponentIfc;
import tigase.conf.Configurable;
import tigase.conf.ConfigurationException;
import tigase.disco.ServiceEntity;
import tigase.disco.ServiceIdentity;
import tigase.disco.XMPPService;
import tigase.eventbus.EventBusFactory;
import tigase.kernel.beans.Initializable;
import tigase.kernel.beans.Inject;
import tigase.kernel.beans.config.ConfigField;
import tigase.kernel.beans.config.ConfigurationChangedAware;
import tigase.osgi.ModulesManagerImpl;
import tigase.osgi.OSGiScriptEngineManager;
import tigase.server.script.AddScriptCommand;
import tigase.server.script.CommandIfc;
import tigase.server.script.RemoveScriptCommand;
import tigase.stats.ComponentStatisticsProvider;
import tigase.stats.StatisticsList;
import tigase.util.common.DependencyChecker;
import tigase.util.dns.DNSResolverFactory;
import tigase.util.stringprep.TigaseStringprepException;
import tigase.vhosts.VHostItem;
import tigase.vhosts.VHostListener;
import tigase.vhosts.VHostManagerIfc;
import tigase.xml.Element;
import tigase.xmpp.Authorization;
import tigase.xmpp.jid.BareJID;
import tigase.xmpp.jid.JID;

import javax.script.Bindings;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created: Oct 17, 2009 7:49:05 PM
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class BasicComponent
		implements Configurable,
				   XMPPService,
				   VHostListener,
				   ClusteredComponentIfc,
				   Initializable,
				   ConfigurationChangedAware {

	/** Field description */
	public static final String ALL_PROP_KEY = "ALL";

	/** Field description */
	public static final String COMMAND_PROP_NODE = "command";

	/** Field description */
	public static final String SCRIPTS_DIR_PROP_DEF = "scripts/admin";

	/** Field description */
	public static final String SCRIPTS_DIR_PROP_KEY = "scripts-dir";

	private static final Logger log = Logger.getLogger(BasicComponent.class.getName());

	//~--- fields ---------------------------------------------------------------
	private final CopyOnWriteArrayList<JID> connectedNodes = new CopyOnWriteArrayList<JID>();
	private final CopyOnWriteArrayList<JID> connectedNodesWithLocal = new CopyOnWriteArrayList<JID>();
	private final List<JID> connectedNodesWithLocal_ro = Collections.unmodifiableList(connectedNodesWithLocal);
	private final List<JID> connectedNodes_ro = Collections.unmodifiableList(connectedNodes);
	@ConfigField(desc = "List of admins JIDs", alias = "admins")
	protected ConcurrentSkipListSet<BareJID> admins = new ConcurrentSkipListSet<BareJID>();
	/** Field description */
	protected Map<String, CommandIfc> scriptCommands = new ConcurrentHashMap<String, CommandIfc>(20);
	protected ConcurrentSkipListSet<String> trusted = new ConcurrentSkipListSet<String>();
	/** Field description */
	@Inject(nullAllowed = true)
	protected VHostManagerIfc vHostManager = null;
	private String DEF_HOSTNAME_PROP_VAL = null;
	private ComponentInfo cmpInfo = null;
	@ConfigField(alias = "commands", desc = "Commands ACL")
	private ConcurrentHashMap<String, CopyOnWriteArraySet<CmdAcl>> commandsACL = new ConcurrentHashMap<>(20);
	@ConfigField(desc = "Component JID")
	private JID compId = null;
	@ConfigField(desc = "Default hostname")
	private BareJID defHostname = null;
	private boolean initializationCompleted = false;
	@ConfigField(desc = "Component name")
	private String name = null;
	private boolean nonAdminCommands = false;
	private ScriptEngineManager scriptEngineManager = null;
	@ConfigField(desc = "Base directory for scripts", alias = SCRIPTS_DIR_PROP_KEY)
	private String scriptsBaseDir = SCRIPTS_DIR_PROP_DEF;
	private String scriptsCompDir = null;
	private ServiceEntity serviceEntity = null;
	@Inject(nullAllowed = true)
	private List<ComponentStatisticsProvider> statisticsProviders;
	@ConfigField(alias = "trusted", desc = "List of trusted JIDs")
	private String[] trustedProp = null;

	public BasicComponent() {
		DependencyChecker.checkDependencies(getClass());

		DEF_HOSTNAME_PROP_VAL = DNSResolverFactory.getInstance().getDefaultHost();
		defHostname = BareJID.bareJIDInstanceNS(DEF_HOSTNAME_PROP_VAL);
	}

	//~--- methods --------------------------------------------------------------

	public void addComponentDomain(String domain) {
		vHostManager.addComponentDomain(domain);
	}

	@Override
	public void beanConfigurationChanged(Collection<String> changedFields) {
		if (changedFields.contains("trusted")) {
			refreshTrustedJids();
		}
	}

	/**
	 * Method checks if following adhoc command can execute from this JID
	 *
	 * @param jid - JID of entity which wants to execute command
	 * @param commandId - ID of an adhoc command
	 *
	 * @return a value of <code>boolean</code>
	 */
	public boolean canCallCommand(JID jid, String commandId) {
		boolean result = isAdmin(jid) || isTrusted(jid);

		if (result) {
			return true;
		}

		Set<CmdAcl> acl = commandsACL.get(ALL_PROP_KEY);

		if (acl != null) {
			result = checkCommandAcl(jid, acl);
		}
		if (!result) {
			acl = commandsACL.get(commandId);
			if (acl != null) {
				result = checkCommandAcl(jid, acl);
			}
		}

		return result;
	}

	/**
	 * Check if entity with JID is allowed ot execute command with passed access control list.
	 *
	 * @param jid - entity JID
	 * @param acl - access control list
	 *
	 * @return a value of <code>boolean</code>
	 */
	public boolean checkCommandAcl(JID jid, Set<CmdAcl> acl) {
		for (CmdAcl cmdAcl : acl) {
			switch (cmdAcl.getType()) {
				case ALL:
					return true;

				case ADMIN:
					if (isAdmin(jid)) {
						return true;
					}

					break;

				case LOCAL:
					if (isLocalDomain(jid.getDomain())) {
						return true;
					}

					break;

				case NONE:
					return false;

				case DOMAIN:
					if (cmdAcl.isDomainAllowed(jid.getDomain())) {
						return true;
					}

					break;

				case JID:
				default:
					if (cmdAcl.isJIDAllowed(jid.getBareJID())) {
						return true;
					}
			}
		}

		return false;
	}

	public void everyHour() {
		for (CommandIfc comm : scriptCommands.values()) {
			comm.everyHour();
		}
		if (statisticsProviders != null) {
			statisticsProviders.forEach(ComponentStatisticsProvider::everyHour);
		}
	}

	public void everyMinute() {
		for (CommandIfc comm : scriptCommands.values()) {
			comm.everyMinute();
		}
		if (statisticsProviders != null) {
			statisticsProviders.forEach(ComponentStatisticsProvider::everyMinute);
		}
	}

	public void everySecond() {
		for (CommandIfc comm : scriptCommands.values()) {
			comm.everySecond();
		}
		if (statisticsProviders != null) {
			statisticsProviders.forEach(ComponentStatisticsProvider::everySecond);
		}
	}

	@Override
	public boolean handlesLocalDomains() {
		return false;
	}

	@Override
	public boolean handlesNameSubdomains() {
		return true;
	}

	@Override
	public boolean handlesNonLocalDomains() {
		return false;
	}

	/**
	 * Initialize a mapping of key/value pairs which can be used in scripts loaded by the server
	 *
	 * @param binds A mapping of key/value pairs, all of whose keys are Strings.
	 */
	public void initBindings(Bindings binds) {
		binds.put(CommandIfc.VHOST_MANAGER, vHostManager);
		binds.put(CommandIfc.ADMINS_SET, admins);
		binds.put(CommandIfc.COMMANDS_ACL, commandsACL);
		binds.put(CommandIfc.SCRI_MANA, scriptEngineManager);
		binds.put(CommandIfc.ADMN_CMDS, scriptCommands);
		binds.put(CommandIfc.ADMN_DISC, serviceEntity);
		binds.put(CommandIfc.SCRIPT_BASE_DIR, scriptsBaseDir);
		binds.put(CommandIfc.SCRIPT_COMP_DIR, scriptsCompDir);
		binds.put(CommandIfc.CONNECTED_NODES, connectedNodes);
		binds.put(CommandIfc.CONNECTED_NODES_WITH_LOCAL, connectedNodesWithLocal);
		binds.put(CommandIfc.COMPONENT_NAME, getName());
		binds.put(CommandIfc.COMPONENT, this);
		binds.put(CommandIfc.EVENTBUS, EventBusFactory.getInstance());
	}

	@Override
	public void initializationCompleted() {
		initializationCompleted = true;
	}

	@Override
	public void nodeConnected(String node) {
		JID jid = JID.jidInstanceNS(getName(), node, null);
		boolean added = false;

		synchronized (connectedNodesWithLocal) {
			if (!connectedNodesWithLocal.contains(jid)) {
				JID[] tmp = connectedNodesWithLocal.toArray(new JID[connectedNodesWithLocal.size() + 1]);
				tmp[tmp.length - 1] = jid;
				Arrays.sort(tmp);
				int pos = Arrays.binarySearch(tmp, jid);
				connectedNodesWithLocal.add(pos, jid);
				added = true;
			}
		}

		synchronized (connectedNodes) {
			if (!connectedNodes.contains(jid) && !getComponentId().equals(jid)) {
				JID[] tmp = connectedNodes.toArray(new JID[connectedNodes.size() + 1]);
				tmp[tmp.length - 1] = jid;
				Arrays.sort(tmp);
				int pos = Arrays.binarySearch(tmp, jid);
				connectedNodes.add(pos, jid);
				added = true;
			}
		}

		if (added) {
			log.log(Level.FINE, "Node connected: {0}", node);
			onNodeConnected(jid);
			refreshTrustedJids();
		}
	}

	@Override
	public void nodeDisconnected(String node) {
		JID jid = JID.jidInstanceNS(getName(), node, null);
		boolean removed = false;

		synchronized (connectedNodesWithLocal) {
			removed |= connectedNodesWithLocal.remove(jid);
		}

		synchronized (connectedNodes) {
			removed |= connectedNodes.remove(jid);
		}

		if (removed) {
			log.log(Level.FINE, "Node disonnected: {0}", node);
			onNodeDisconnected(jid);
			refreshTrustedJids();
		}
	}

	@Override
	public void processPacket(Packet packet, Queue<Packet> results) {
		if (packet.isCommand() && getName().equals(packet.getStanzaTo().getLocalpart()) &&
				isLocalDomain(packet.getStanzaTo().getDomain())) {
			processScriptCommand(packet, results);
		}
	}

	@Override
	public void release() {
	}

	public void removeComponentDomain(String domain) {
		vHostManager.removeComponentDomain(domain);
	}

	public void removeServiceDiscoveryItem(String jid, String node, String description) {
		ServiceEntity item = new ServiceEntity(jid, node, description);

		// item.addIdentities(new ServiceIdentity("component", identity_type,
		// name));
		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "Modifying service-discovery info, removing: {0}", item);
		}
		serviceEntity.removeItems(item);
	}

	@Override
	public void setClusterController(ClusterControllerIfc cl_controller) {

	}

	public void updateServiceDiscoveryItem(String jid, String node, String description, boolean admin) {
		updateServiceDiscoveryItem(jid, node, description, admin, (String[]) null);
	}

	public void updateServiceDiscoveryItem(String jid, String node, String description, boolean admin,
										   String... features) {
		updateServiceDiscoveryItem(jid, node, description, null, null, admin, features);
	}

	public void updateServiceDiscoveryItem(String jid, String node, String description, String category, String type,
										   boolean admin, String... features) {
		if (serviceEntity.getJID().equals(jid) && (serviceEntity.getNode() == node)) {
			serviceEntity.setAdminOnly(admin);
			serviceEntity.setDescription(description);
			if ((category != null) || (type != null)) {
				serviceEntity.addIdentities(new ServiceIdentity(category, type, description));
			}
			if (features != null) {
				serviceEntity.setFeatures("http://jabber.org/protocol/commands");
				serviceEntity.addFeatures(features);
			}
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Modifying service-discovery info: {0}", serviceEntity);
			}
		} else {
			ServiceEntity item = new ServiceEntity(jid, node, description, admin);

			if ((category != null) || (type != null)) {
				item.addIdentities(new ServiceIdentity(category, type, description));
			}
			if (features != null) {
				item.addFeatures(features);
			}
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Adding new item: {0}", item);
			}
			serviceEntity.addItems(item);
		}
	}

	public void updateServiceEntity() {
		serviceEntity = new ServiceEntity(name, null, getDiscoDescription(), true);
		serviceEntity.addIdentities(
				new ServiceIdentity(getDiscoCategory(), getDiscoCategoryType(), getDiscoDescription()));
		serviceEntity.addFeatures("http://jabber.org/protocol/commands");
	}

	//~--- get methods ----------------------------------------------------------

	@Override
	public JID getComponentId() {
		return compId;
	}

	@Override
	public ComponentInfo getComponentInfo() {
		if (cmpInfo == null) {
			cmpInfo = new ComponentInfo(getName(), this.getClass());
		}

		return cmpInfo;
	}

	@Override
	@Deprecated
	public Map<String, Object> getDefaults(Map<String, Object> params) {
		Map<String, Object> defs = new LinkedHashMap<String, Object>(50);

		defs.put(COMPONENT_ID_PROP_KEY, compId.toString());
		DEF_HOSTNAME_PROP_VAL = DNSResolverFactory.getInstance().getDefaultHost();
		defs.put(DEF_HOSTNAME_PROP_KEY, DEF_HOSTNAME_PROP_VAL);

		return defs;
	}

	public BareJID getDefHostName() {
		return defHostname;
	}

	public BareJID getDefVHostItem() {
		return (vHostManager != null) ? vHostManager.getDefVHostItem() : getDefHostName();
	}

	/**
	 * Method returns category of a component used for service discovery responses.
	 * 
	 * @return category of a component
	 */
	public String getDiscoCategory() {
		return "component";
	}

	/**
	 * Method returns component category type used for service discovery responses.
	 *
	 * @return category type of a component
	 */
	public String getDiscoCategoryType() {
		return "generic";
	}

	/**
	 * Method returns description used for service discovery responses.
	 *
	 * @return description of a component
	 */
	public String getDiscoDescription() {
		return "Undefined description";
	}

	/**
	 * Method returns list of features provided by this component.
	 *
	 * @return list of features
	 */
	public List<Element> getDiscoFeatures() {
		return null;
	}

	/**
	 * Method returns list of features provided by this component for provided JID.
	 *
	 * @return list of features
	 */
	@Override
	public List<Element> getDiscoFeatures(JID from) {
		return getDiscoFeatures();
	}

	@Override
	public Element getDiscoInfo(String node, JID jid, JID from) {
		if (getName().equals(jid.getLocalpart()) || jid.toString().startsWith(getName() + ".")) {
			return serviceEntity.getDiscoInfo(node, isAdmin(from) || nonAdminCommands);
		}

		return null;
	}

	@Override
	public List<Element> getDiscoItems(String node, JID jid, JID from) {
		List<Element> result = null;
		boolean isAdminFrom = isAdmin(from);

		if (getName().equals(jid.getLocalpart()) || jid.toString().startsWith(getName() + ".")) {
			if (node != null) {
				if (node.equals("http://jabber.org/protocol/commands") && (isAdminFrom || nonAdminCommands)) {
					result = new LinkedList<Element>();
					for (CommandIfc comm : scriptCommands.values()) {
						if (!comm.isAdminOnly() || isAdminFrom) {
							Element item = new Element("item", new String[]{"node", "name", "jid"},
													   new String[]{comm.getCommandId(), comm.getDescription(),
																	jid.toString()});
							if (comm.getGroup() != null) {
								item.setAttribute("group", comm.getGroup());
							}
							result.add(item);
						}
					}
				} else {
					result = serviceEntity.getDiscoItems(node, jid.toString(), (isAdminFrom || nonAdminCommands));
				}
			} else {
				result = serviceEntity.getDiscoItems(null, jid.toString(), (isAdminFrom || nonAdminCommands));
				if (result != null) {
					for (Iterator<Element> it = result.iterator(); it.hasNext(); ) {
						Element element = it.next();

						if (element.getAttributeStaticStr("node") == null) {
							it.remove();
						}
					}
				}
			}

			// Element result = serviceEntity.getDiscoItem(null, getName() + "." + jid);
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Found disco items: {0}", ((result != null) ? result.toString() : null));
			}

			return result;
		} else {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "{0} General disco items request, node: {1}", new Object[]{getName(), node});
			}
			if (node == null) {
				if (log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST, "{0} Disco items request for null node", new Object[]{getName()});
				}

				Element res = null;

				if (!serviceEntity.isAdminOnly() || isAdmin(from) || nonAdminCommands) {
					res = serviceEntity.getDiscoItem(null, isSubdomain()
														   ? (getName() + "." + jid)
														   : getName() + "@" + jid.toString());
				}
				result = serviceEntity.getDiscoItems(null, null, (isAdminFrom || nonAdminCommands));
				if (res != null) {
					if (result != null) {
						for (Iterator<Element> it = result.iterator(); it.hasNext(); ) {
							Element element = it.next();

							if (element.getAttributeStaticStr("node") != null) {
								it.remove();
							}
						}
						result.add(0, res);
					} else {
						result = Arrays.asList(res);
					}
				}
			}

			return result;
		}
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void setName(String name) {
		this.name = name;

		try {
			compId = JID.jidInstance(name, defHostname.getDomain(), null);
		} catch (TigaseStringprepException ex) {
			log.log(Level.WARNING, "Problem setting component ID: ", ex);
		}
	}

	public void getStatistics(StatisticsList list) {
		String compName = getName();
		for (CommandIfc comm : scriptCommands.values()) {
			comm.getStatistics(compName, list);
		}
		if (connectedNodes.size() > 0) {
			list.add(getName(), "Known cluster nodes", connectedNodes.size(), Level.FINEST);
		}

		if (statisticsProviders != null) {
			statisticsProviders.stream()
					.filter(provider -> provider.belongsTo(this.getClass()))
					.forEach(provider -> provider.getStatistics(compName, list));
		}
	}

	public List<Element> getScriptItems(String node, JID jid, JID from) {
		LinkedList<Element> result = null;
		boolean isAdminFrom = isAdmin(from);

		if (node.equals("http://jabber.org/protocol/commands") && (isAdminFrom || nonAdminCommands)) {
			result = new LinkedList<Element>();
			for (CommandIfc comm : scriptCommands.values()) {
				if (!comm.isAdminOnly() || isAdminFrom) {
					result.add(new Element("item", new String[]{"node", "name", "jid"},
										   new String[]{comm.getCommandId(), comm.getDescription(), jid.toString()}));
				}
			}
		}

		return result;
	}

	public VHostItem getVHostItem(String domain) {
		return (vHostManager != null) ? vHostManager.getVHostItem(domain) : null;
	}

	public boolean isAdmin(JID jid) {
		return admins.contains(jid.getBareJID());
	}

	@Override
	public boolean isInitializationComplete() {
		return initializationCompleted;
	}

	public boolean isLocalDomain(String domain) {
		return (vHostManager != null) ? vHostManager.isLocalDomain(domain) : false;
	}

	public boolean isLocalDomainOrComponent(String domain) {
		return (vHostManager != null) ? vHostManager.isLocalDomainOrComponent(domain) : false;
	}

	public boolean isSubdomain() {
		return false;
	}

	public boolean isTrusted(JID jid) {
		if (trusted.contains(jid.getBareJID().toString())) {
			return true;
		}

		return isAdmin(jid);
	}

	public boolean isTrusted(String jid) {
		return trusted.contains(jid);
	}

	//~--- set methods ----------------------------------------------------------

	public void setAdmins(Set<BareJID> admins) {
		this.admins.addAll(admins);
		this.admins.retainAll(admins);
	}

	@Override
	@Deprecated
	public void setProperties(Map<String, Object> props) throws ConfigurationException {
	}

	public void setCommandsACL(ConcurrentHashMap<String, CopyOnWriteArraySet<CmdAcl>> commandsACL) {
		this.commandsACL = commandsACL;
		this.nonAdminCommands = commandsACL.entrySet()
				.stream()
				.filter(e -> (!e.getValue().contains(CmdAcl.ADMIN)) || e.getValue().size() > 1)
				.findAny()
				.isPresent();
	}

	public void setScriptsBaseDir(String scriptsBaseDir) {
		this.scriptsBaseDir = scriptsBaseDir;
		this.scriptsCompDir = scriptsBaseDir + "/" + getName();
		if (scriptEngineManager != null) {
			reloadScripts();
		}
	}

	@Override
	public void setVHostManager(VHostManagerIfc manager) {
		this.vHostManager = manager;
	}

	public List<JID> getNodesConnected() {
		return connectedNodes_ro;
	}

	//~--- methods --------------------------------------------------------------

	public List<JID> getNodesConnectedWithLocal() {
		return connectedNodesWithLocal_ro;
	}

	public boolean processScriptCommand(Packet pc, Queue<Packet> results) {

		// TODO: test if this is right
		// It is not, the packet should actually have packetFrom set at all times
		// to ensure the error can be sent back to the original sender.
		// if ((pc.getStanzaFrom() == null) || (pc.getPacketFrom() != null)) {
		//
		//// The packet has not gone through session manager yet
		// return false;
		// }
		// This test is more correct as it says whether the packet went through
		// session manager checking.
		// TODO: test if commands still work for users from different XMPP servers
		// with the right permission set.
		if (pc.getPermissions() == Permissions.NONE) {
			return false;
		}

		Iq iqc = (Iq) pc;
		Command.Action action = Command.getAction(iqc);

		if (action == Command.Action.cancel) {
			Packet result = iqc.commandResult(Command.DataType.result);

			Command.addTextField(result, "Note", "Command canceled.");
			results.offer(result);

			return true;
		}

		String strCommand = iqc.getStrCommand();
		CommandIfc com = scriptCommands.get(strCommand);

		if ((strCommand != null) && (com != null)) {
			boolean admin = false;

			try {
				admin = canCallCommand(iqc.getStanzaFrom(), strCommand);
				if (admin) {
					if (log.isLoggable(Level.FINER)) {
						log.log(Level.FINER, "Processing admin command: {0}", pc);
					}

					Bindings binds = com.getBindings();

					if (binds == null) {
						binds = scriptEngineManager.getBindings();
					}

					// Bindings binds = scriptEngineManager.getBindings();
					initBindings(binds);
					com.runCommand(iqc, binds, results);
				} else {
					if (log.isLoggable(Level.FINER)) {
						log.log(Level.FINER, "Command rejected non-admin detected: {0}", pc.getStanzaFrom());
					}
					results.offer(
							Authorization.FORBIDDEN.getResponseMessage(pc, "Only Administrator can call the command.",
																	   true));
				}
			} catch (Exception e) {
				log.log(Level.WARNING, "Unknown admin command processing exception: " + pc, e);
			}

			return true;
		}

		return false;
	}

	@Override
	public void initialize() {
		nodeConnected(defHostname.getDomain());
		if (scriptEngineManager == null) {
			scriptEngineManager = createScriptEngineManager();
		}

		updateServiceEntity();

		setScriptsBaseDir(scriptsBaseDir);
		reloadScripts();
		cmpInfo = new ComponentInfo(getName(), this.getClass());

		System.out.println("Loading component: " + cmpInfo);

		initializationCompleted();
	}

	protected ScriptEngineManager createScriptEngineManager() {
		if (XMPPServer.isOSGi()) {
			return new OSGiScriptEngineManager();
		} else {
			return new ScriptEngineManager();
		}
	}

	protected void onNodeConnected(JID jid) {

	}

	//~--- get methods ----------------------------------------------------------

	protected void onNodeDisconnected(JID jid) {

	}

	protected Map<String, CommandIfc> getScriptCommands() {
		return scriptCommands;
	}

	protected ServiceEntity getServiceEntity() {
		return serviceEntity;
	}

	//~--- methods --------------------------------------------------------------

	protected boolean isNonAdminCommands() {
		return nonAdminCommands;
	}

	private void reloadScripts() {
		log.log(Level.CONFIG, "Reloading admin scripts for component: {0}.", new Object[]{getName()});
		scriptCommands.clear();
		CommandIfc command = new AddScriptCommand();

		command.init(CommandIfc.ADD_SCRIPT_CMD, "New command script", "Scripts");
		scriptCommands.put(command.getCommandId(), command);
		command = new RemoveScriptCommand();
		command.init(CommandIfc.DEL_SCRIPT_CMD, "Remove command script", "Scripts");
		scriptCommands.put(command.getCommandId(), command);

		loadScripts();
	}

	private void loadScripts() {
		log.log(Level.CONFIG, "Loading admin scripts for component: {0}.", new Object[]{getName()});

		File file = null;
		AddScriptCommand addCommand = new AddScriptCommand();
		Bindings binds = scriptEngineManager.getBindings();
		List<String> extensions = new ArrayList<>();
		for (ScriptEngineFactory engineFactory : scriptEngineManager.getEngineFactories()) {
			extensions.addAll(engineFactory.getExtensions());
		}

		initBindings(binds);

		String[] dirs = new String[]{scriptsBaseDir, scriptsCompDir};

		// check class only from main directory

		for (String scriptsPath : dirs) {
			log.log(Level.CONFIG, "{0}: Loading scripts from directory: {1}", new Object[]{getName(), scriptsPath});
			try {
				File adminDir = new File(scriptsPath);

				if ((adminDir != null) && adminDir.exists()) {
					for (File f : adminDir.listFiles(new ExtFilter(extensions))) {

						// Just regular files here....
						if (f.isFile() && !f.toString().endsWith("~") && !f.isHidden()) {
							String cmdId = null;
							String cmdDescr = null;
							String cmdGroup = null;
							String comp = null;
							String compClass = null;

							file = f;

							StringBuilder sb = new StringBuilder();
							BufferedReader buffr = new BufferedReader(new FileReader(file));
							String line = null;

							while ((line = buffr.readLine()) != null) {
								sb.append(line).append("\n");

								int idx = line.indexOf(CommandIfc.SCRIPT_DESCRIPTION);

								if (idx >= 0) {
									cmdDescr = line.substring(idx + CommandIfc.SCRIPT_DESCRIPTION.length()).trim();
								}
								idx = line.indexOf(CommandIfc.SCRIPT_ID);
								if (idx >= 0) {
									cmdId = line.substring(idx + CommandIfc.SCRIPT_ID.length()).trim();
								}
								idx = line.indexOf(CommandIfc.SCRIPT_COMPONENT);
								if (idx >= 0) {
									comp = line.substring(idx + CommandIfc.SCRIPT_COMPONENT.length()).trim();
								}
								idx = line.indexOf(CommandIfc.SCRIPT_CLASS);
								if (idx >= 0) {
									compClass = line.substring(idx + CommandIfc.SCRIPT_CLASS.length()).trim();
								}
								idx = line.indexOf(CommandIfc.SCRIPT_GROUP);
								if (idx >= 0) {
									cmdGroup = line.substring(idx + CommandIfc.SCRIPT_GROUP.length()).trim();
								}
							}
							buffr.close();
							if ((cmdId == null) || (cmdDescr == null)) {
								log.log(Level.WARNING,
										"Admin script found but it has no command ID or command" + "description: " +
												"{0}", file);

								continue;
							}

							boolean found = false;

							if (comp != null) {
								// Which components should load the script
								String[] comp_names = comp.split(",");

								// check component names
								for (String cmp : comp_names) {
									cmp = cmp.trim();
									found |= getName().equals(cmp);
								}
							}

							// check component classes
							if (null != compClass) {
								// do we need this check? it blocks us from loading adhoc
								// commands from component named directory if it is marked
								// as for a specific component class - this should be allowed
//								if ( scriptsPath.endsWith( getName() ) ){
//									// ok, this is script for component of particular name, skip
//									// loading based on class
//									continue;
//								}
								String[] comp_classes = compClass.split(",");
								for (String cmp : comp_classes) {
									try {
										// we also check whether script is loaded for particular class or it's subclasses
										Class<?> loadClass = ModulesManagerImpl.getInstance().forName(cmp);
										found |= loadClass.isAssignableFrom(this.getClass());

									} catch (NoClassDefFoundError ex) {
										log.log(Level.WARNING,
												"Tried loading script with class defined as: {0} for class: {1}",
												new String[]{cmp, this.getClass().getCanonicalName()});
									} catch (ClassNotFoundException ex) {
										// just ignore
									}
								}
							}
							if (!found) {
								log.log(Level.FINEST,
										"{0}: skipping admin script {1}, id: {2}, descr: {3}, group: {4} for component: {5} or class: {6}",
										new Object[]{getName(), file, cmdId, cmdDescr, cmdGroup, comp, compClass});

								continue;
							}

							int idx = file.toString().lastIndexOf('.');
							String ext = file.toString().substring(idx + 1);

							if (cmdGroup != null && cmdGroup.contains("${componentName}")) {
								cmdGroup = cmdGroup.replace("${componentName}", this.getDiscoDescription());
							}

							addCommand.addAdminScript(cmdId, cmdDescr, cmdGroup, sb.toString(), null, ext, binds);
							log.log(Level.CONFIG,
									"{0}: Loaded admin command from file: {1}, id: {2}, ext: {3}, descr: {4}",
									new Object[]{getName(), file, cmdId, ext, cmdDescr});
						}
					}
				}
			} catch (IOException | ScriptException e) {
				log.log(Level.WARNING, "Can't load the admin script file: " + file, e);
			}
		}
	}

	private void refreshTrustedJids() {
		synchronized (connectedNodesWithLocal) {
			trusted.clear();
			if (trustedProp != null) {
				for (String trustedStr : trustedProp) {
					if (trustedStr.contains("{clusterNode}")) {
						for (JID nodeJid : connectedNodesWithLocal) {
							String node = nodeJid.getDomain();
							String jid = trustedStr.replace("{clusterNode}", node);
							trusted.add(jid);
						}
					} else {
						trusted.add(trustedStr);
					}
				}
			}
		}
		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "component {0} got trusted jids set as {1}", new Object[]{getName(), trusted});
		}
	}

	private class ExtFilter
			implements FileFilter {

		List<String> extensions;

		public ExtFilter(List<String> extensions) {
			this.extensions = extensions;
		}

		@Override
		public boolean accept(File file) {

			boolean matched = false;
			for (String extension : extensions) {
				matched |= file.isFile() && file.getName().toLowerCase().endsWith(extension);
			}
			return matched;
		}
	}
}

//~ Formatted in Tigase Code Convention on 13/12/09
