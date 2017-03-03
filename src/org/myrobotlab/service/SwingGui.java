/**
 *                    
 * @author greg (at) myrobotlab.org
 *  
 * This file is part of MyRobotLab (http://myrobotlab.org).
 *
 * MyRobotLab is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version (subject to the "Classpath" exception
 * as provided in the LICENSE.txt file that accompanied this code).
 *
 * MyRobotLab is distributed in the hope that it will be useful or fun,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * All libraries in thirdParty bundle are subject to their own license
 * requirements - please refer to http://myrobotlab.org/libraries for 
 * details.
 * 
 * Enjoy !
 * 
 * */

package org.myrobotlab.service;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.swing.Box;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import org.myrobotlab.framework.Instantiator;
import org.myrobotlab.framework.Message;
import org.myrobotlab.framework.Service;
import org.myrobotlab.framework.ServiceType;
import org.myrobotlab.framework.Status;
import org.myrobotlab.image.Util;
import org.myrobotlab.logging.Appender;
import org.myrobotlab.logging.Level;
import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.logging.Logging;
import org.myrobotlab.logging.LoggingFactory;
import org.myrobotlab.service.interfaces.ServiceInterface;
import org.myrobotlab.swing.SwingGuiGui;
import org.myrobotlab.swing.RuntimeGui;
import org.myrobotlab.swing.ServiceGui;
import org.myrobotlab.swing.TabControl2;
import org.myrobotlab.swing.Welcome;
import org.myrobotlab.swing.widget.AboutDialog;
import org.myrobotlab.swing.widget.Console;
import org.slf4j.Logger;

import com.mxgraph.model.mxCell;
import com.mxgraph.view.mxGraph;

/**
 * SwingGui - This is the java swing based GUI for MyRobotLab. This service
 * allows other services control features to be displayed. It is the service
 * which you "see" when you start MyRobotLab. It provides a service tab for
 * other services. With its own tab it provides a map of message routes and
 * icons of currently running services.
 * 
 * SwingGui -> Look at service registry SwingGui -> attempt to create a panel
 * for each registered service SwingGui -> create panel SwingGui ->
 * panel.init(this, serviceName); panel.send(Notify, someoutputfn, GUIName,
 * panel.inputfn, data);
 *
 * serviceName (source) --> SwingGui-> msg Arduino arduino01 -> post message ->
 * outbox -> outbound -> notifyList -> reference of sender? (NO) will not
 * transport across process boundry
 * 
 * serviceGUI needs a Runtime Arduino arduin-> post back (data) --> SwingGui -
 * look up serviceGUI by senders name ServiceGUI->invoke(data)
 * 
 * References :
 * http://www.scribd.com/doc/13122112/Java6-Rules-Adding-Components-To-The-
 * Tabs-On-JTabbedPaneI-Now-A-breeze
 * 
 */
public class SwingGui extends Service implements WindowListener, ActionListener, Serializable {

	private static final long serialVersionUID = 1L;
	transient public final static Logger log = LoggerFactory.getLogger(SwingGui.class);

	String graphXML = "";
	String lastTabVisited;
	// TODO - make MTOD !! from internet
	// TODO - spawn thread callback / subscribe / promise - for new version

	transient JFrame frame;
	transient JTabbedPane tabs;
	transient SwingGuiGui guiServiceGui;
	transient HashMap<String, List<ServiceGui>> serviceGuiMap;
	transient JLabel status = new JLabel("status");

	static public void attachJavaConsole() {
		JFrame j = new JFrame("Java Console");
		j.setSize(500, 550);
		Console c = new Console();
		j.add(c.getScrollPane());
		j.setVisible(true);
		c.startLogging();
	}

	static public void console() {
		attachJavaConsole();
	}

	public static List<Component> getAllComponents(final Container c) {
		Component[] comps = c.getComponents();
		List<Component> compList = new ArrayList<Component>();
		for (Component comp : comps) {
			compList.add(comp);
			if (comp instanceof Container)
				compList.addAll(getAllComponents((Container) comp));
		}
		return compList;
	}

	public static Color getColorFromURI(Object uri) {
		StringBuffer sb = new StringBuffer(String.format("%d", Math.abs(uri.hashCode())));
		Color c = new Color(Color.HSBtoRGB(Float.parseFloat("0." + sb.reverse().toString()), 0.8f, 0.7f));
		return c;
	}

	static public void restart() {
		JFrame frame = new JFrame();
		int ret = JOptionPane.showConfirmDialog(frame,
				"<html>New components have been added,<br>"
						+ " it is necessary to restart in order to use them.</html>",
				"restart", JOptionPane.YES_NO_OPTION);
		if (ret == JOptionPane.OK_OPTION) {
			log.info("restarting");
			// Runtime.restart(restartScript);
			Runtime.getInstance().restart(); // <-- FIXME WRONG need to send
			// message - may be remote !!
		} else {
			log.info("chose not to restart");
			return;
		}
	}

	public SwingGui(String n) {
		super(n);
		// subscribe to services being added and removed
		subscribe(Runtime.getRuntimeName(), "released");
		subscribe(Runtime.getRuntimeName(), "registered");
	}

	public void about() {
		new AboutDialog(this);
	}

	@Override
	public void actionPerformed(ActionEvent ae) {
		String cmd = ae.getActionCommand();
		// Object source = ae.getSource();

		if ("unhide all".equals(cmd)) {
			unhideAll();
		} else if ("hide all".equals(cmd)) {
			hideAll();
		} else if (cmd.equals(Appender.FILE)) {
			Logging logging = LoggingFactory.getInstance();
			logging.addAppender(Appender.FILE);
		} else if (cmd.equals(Appender.NONE)) {
			Logging logging = LoggingFactory.getInstance();
			logging.addAppender(Appender.NONE);
		} else if ("explode".equals(cmd)) {
		} else if ("about".equals(cmd)) {
			new AboutDialog(this);
			// display();
		} else {
			log.info(String.format("unknown command %s", cmd));
		}
	}

	/**
	 * add a service tab to the SwingGui
	 * 
	 * @param serviceName
	 *            - name of service to add
	 * 
	 *            FIXME - full parameter of addTab(final String serviceName,
	 *            final String serviceType, final String lable) then overload
	 */
	synchronized public void addTab(final String serviceName) {

		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				ServiceInterface sw = Runtime.getService(serviceName);

				if (sw == null) {
					log.error(String.format("addTab {} can not proceed it does not exist in registry (yet?)",
							serviceName));
					return;
				}

				String guiClass = String.format("org.myrobotlab.swing.%sGui", sw.getClass().getSimpleName());

				if (serviceGuiMap.containsKey(sw.getName())) {
					log.debug("not creating {} gui - it already exists", sw.getName());
					return;
				}

				ServiceGui newGui = createTabbedPanel(serviceName, guiClass);
				// woot - got index !
				int index = tabs.indexOfTab(serviceName) - 1;

				if (newGui != null) {
					return;
				} else {
					++index;
				}

				guiServiceGui.rebuildGraph();

				Component c = tabs.getTabComponentAt(index);
				if (c instanceof TabControl2) {
					// TabControl2 tc = (TabControl2) c;
					if (!sw.isLocal()) {
						Color hsv = SwingGui.getColorFromURI(sw.getInstanceId());
						tabs.setBackgroundAt(index, hsv);
					}
				}

				frame.pack();
				frame.repaint();

			}
		});
	}

	/**
	 * Build the menu for display.
	 * 
	 * @return
	 */
	public JMenuBar buildMenu() {
		JMenuBar menuBar = new JMenuBar();

		JMenu help = new JMenu("help");
		JMenuItem about = new JMenuItem("about");
		about.addActionListener(this);
		help.add(about);
		menuBar.add(Box.createHorizontalGlue());
		menuBar.add(help);

		return menuBar;
	}

	/**
	 * puts unique service gui into map
	 * 
	 * @param tabText
	 * @param sg
	 */
	public void putTab(String tabText, ServiceGui sg) {
		List<ServiceGui> list = null;
		if (serviceGuiMap.containsKey(tabText)) {
			list = serviceGuiMap.get(tabText);
		} else {
			list = new ArrayList<ServiceGui>();
			serviceGuiMap.put(tabText, list);
		}

		boolean found = false;
		for (int i = 0; i < list.size(); ++i) {
			ServiceGui existingSg = list.get(i);
			if (existingSg == sg) {
				found = true;
			}
		}
		if (!found) {
			list.add(sg); // that was easy ;)
		}
	}

	// return tabs;
	// }

	/**
	 * attempts to create a new ServiceGui and add it to the map
	 * 
	 * @param serviceName
	 * @param guiClass
	 * @param sw
	 * @return
	 */

	public ServiceGui createTabbedPanel(String serviceName, String guiClass) {
		ServiceGui gui = null;

		gui = (ServiceGui) Instantiator.getNewInstance(guiClass, serviceName, this, tabs);

		if (gui == null) {
			log.info(String.format("could not construct a %s object - creating generic template", guiClass));
			gui = (ServiceGui) Instantiator.getNewInstance("org.myrobotlab.swing.NoGui", serviceName, this, tabs);
		}

		// serviceGuiMap.put(serviceName, gui);
		putTab(serviceName, gui);
		gui.subscribeGui();

		// state and status are both subscribed for the service here
		subscribe(serviceName, "publishStatus", getName(), "onStatus");
		subscribe(serviceName, "publishState", getName(), "onState");
		send(serviceName, "publishState");

		// processing of all 'helper' component/layout parts
		// gui.display();
		return gui;
	}

	/**
	 * closes window and puts the panel back into the tabbed pane
	 */
	public void dockPanel(final String label) {
		// there is a bug here - there can be multiple ServiceGui's per
		// 'boundServiceName' (widgets) some may be dockable - some not
		// should probably fix with unique component names ...

		if (serviceGuiMap.containsKey(label)) {
			List<ServiceGui> sgs = serviceGuiMap.get(label);
			for (int i = 0; i < sgs.size(); ++i) {
				ServiceGui sg = sgs.get(i);
				sg.dockPanel();
			}
		}

		/*
		 * if (serviceGuiMap.containsKey(label)) { ServiceGui sg =
		 * serviceGuiMap.get(label); sg.dockPanel(); } else { log.error(
		 * "dockPanel - {} not in serviceGuiMap", label); }
		 */
	}

	public HashMap<String, mxCell> getCells() {
		return guiServiceGui.serviceCells;
	}

	public String getDstMethodName() {
		return guiServiceGui.dstMethodName.getText();
	}

	public String getDstServiceName() {
		return guiServiceGui.dstServiceName.getText();
	}

	public JFrame getFrame() {
		return frame;
	}

	public mxGraph getGraph() {
		return guiServiceGui.graph;
	}

	public String getGraphXML() {
		return graphXML;
	}

	public String getSrcMethodName() {
		return guiServiceGui.srcMethodName.getText();
	}

	public String getSrcServiceName() {
		return guiServiceGui.srcServiceName.getText();
	}

	public void onStatus(Status inStatus) {

		if (inStatus.isError()) {
			status.setOpaque(true);
			status.setForeground(Color.white);
			status.setBackground(Color.red);
		} else if (inStatus.isWarn()) {
			status.setOpaque(true);
			status.setForeground(Color.black);
			status.setBackground(Color.yellow);
		} else {
			status.setForeground(Color.black);
			status.setOpaque(false);
		}

		status.setText(inStatus.detail);
	}

	public void hideAll() {
		log.info("hideAll");
		for (String key : serviceGuiMap.keySet()) {
			hidePanel(key);
		}
	}

	public void hidePanel(final String label) {
		if (serviceGuiMap.containsKey(label)) {
			List<ServiceGui> sgs = serviceGuiMap.get(label);
			for (int i = 0; i < sgs.size(); ++i) {
				sgs.get(i).hidePanel();
			}
		} else {
			log.error("hidePanel - {} not in serviceGuiMap", label);
		}
	}

	public void noWorky() {
		// String img =
		// SwingGui.class.getResource("/resource/expert.jpg").toString();
		String logon = (String) JOptionPane.showInputDialog(getFrame(),
				"<html>This will send your myrobotlab.log file<br><p align=center>to our crack team of experts,<br> please type your myrobotlab.org user</p></html>",
				"No Worky!", JOptionPane.WARNING_MESSAGE, Util.getResourceIcon("expert.jpg"), null, null);
		if (logon == null || logon.length() == 0) {
			return;
		}

		try {
			if (Runtime.noWorky(logon).isInfo()) {
				JOptionPane.showMessageDialog(getFrame(), "log file sent, Thank you", "Sent !",
						JOptionPane.INFORMATION_MESSAGE);
			} else {
				JOptionPane.showMessageDialog(getFrame(), "could not send log file :(", "DOH !",
						JOptionPane.ERROR_MESSAGE);
			}
		} catch (Exception e1) {
			JOptionPane.showMessageDialog(getFrame(), Service.stackToString(e1), "DOH !", JOptionPane.ERROR_MESSAGE);
		}

	}

	public void pack() {
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				frame.pack();
				frame.repaint();
			}
		});
	}

	@Override
	public boolean preProcessHook(Message m) {
		// FIXME - problem with collisions of this service's methods
		// and dialog methods ?!?!?

		// if the method name is == to a method in the SwingGui
		if (methodSet.contains(m.method)) {
			// process the message like a regular service
			return true;
		}

		// otherwise send the message to the dialog with the senders name
		List<ServiceGui> sgs = serviceGuiMap.get(m.sender);
		if (sgs == null) {
			log.error("attempting to update sub-gui - sender " + m.sender + " not available in map " + getName());
		} else {
			// FIXME - NORMALIZE - Instantiator or Service - not both !!!
			// Instantiator.invokeMethod(serviceGuiMap.get(m.sender), m.method,
			// m.data);
			for (int i = 0; i < sgs.size(); ++i) {
				ServiceGui sg = sgs.get(i);
				invokeOn(sg, m.method, m.data);
			}

		}

		return false;
	}

	public Service onRegistered(final Service s) {
		addTab(s.getName());
		// kind of kludgy but got to keep them in sync
		// WTF ? is this still applicable ?
		List<ServiceGui> sgs = serviceGuiMap.get(Runtime.getInstance().getName());
		if (sgs != null) {
			for (int i = 0; i < sgs.size(); ++i) {
				RuntimeGui rg = (RuntimeGui) (sgs.get(i));
				if (rg != null) {
					rg.registered(s);
				}
			}
		}
		return s;
	}

	public void onReleased(final Service s) {
		log.info("releasing");
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				removeTab(s.getName());
				RuntimeGui rg = (RuntimeGui) serviceGuiMap.get(Runtime.getInstance().getName());
				if (rg != null) {
					rg.released(s);
				}
			}
		});
	}

	public void removeTab(final String name) {
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {

				log.info("removeTab");

				// detaching & removing the ServiceGui
				List<ServiceGui> sgs = serviceGuiMap.get(name);
				if (sgs != null) {
					for (int i = 0; i < sgs.size(); ++i) {
						ServiceGui sg = sgs.get(i);
						sg.remove();
					}
					serviceGuiMap.remove(name);
				} else {
					log.warn(String.format("{} was not in the serviceGuiMap - unable to remove", name));
				}

				unsubscribe(name, "publishStatus");
				unsubscribe(name, "publishState");

				guiServiceGui.rebuildGraph();

				frame.pack();
				frame.repaint();
			}
		});
	}

	public void setArrow(String s) {
		guiServiceGui.arrow0.setText(s);
	}

	public void setDstMethodName(String d) {
		guiServiceGui.dstMethodName.setText(d);
	}

	public void setDstServiceName(String d) {
		guiServiceGui.dstServiceName.setText(d);
	}

	public void setGraphXML(String xml) {
		graphXML = xml;
	}

	public void setPeriod0(String s) {
		guiServiceGui.period0.setText(s);
	}

	public void setPeriod1(String s) {
		guiServiceGui.period1.setText(s);
	}

	public void setSrcMethodName(String d) {
		guiServiceGui.srcMethodName.setText(d);
	}

	public void setSrcServiceName(String d) {
		guiServiceGui.srcServiceName.setText(d);
	}

	@Override
	public void startService() {
		super.startService();

		// create gui parts
		frame = new JFrame();
		tabs = new JTabbedPane();
		serviceGuiMap = new HashMap<String, List<ServiceGui>>();

		frame.addWindowListener(this);
		frame.setTitle("myrobotlab - " + getName() + " " + Runtime.getVersion().trim());

		// ===== build tab panels begin ======
		// builds all the service tabs for the first time called when SwingGui
		// starts

		// add welcome table
		putTab("Welcome", new Welcome("welcome", this, tabs));
		/**
		 * pack() repaint() works on current selected (non-hidden) tab welcome
		 * is the first panel when the UI starts - therefore this controls the
		 * initial size .. however the largest panel typically at this point is
		 * the RuntimeGui - so we set it to the rough size of that panel
		 */

		Map<String, ServiceInterface> services = Runtime.getRegistry();
		log.info("buildTabPanels service count " + Runtime.getRegistry().size());

		TreeMap<String, ServiceInterface> sortedMap = new TreeMap<String, ServiceInterface>(services);
		Iterator<String> it = sortedMap.keySet().iterator();

		JPanel tabPanel = new JPanel(new BorderLayout());
		tabPanel.add(tabs, BorderLayout.CENTER);
		tabPanel.add(status, BorderLayout.SOUTH);
		status.setOpaque(true);

		frame.add(tabPanel);

		URL url = getClass().getResource("/resource/mrl_logo_36_36.png");
		Toolkit kit = Toolkit.getDefaultToolkit();
		Image img = kit.createImage(url);
		frame.setIconImage(img);

		// menu
		frame.setJMenuBar(buildMenu());
		frame.setVisible(true);

		while (it.hasNext()) {
			String serviceName = it.next();
			addTab(serviceName);
		}

		frame.pack();
		frame.repaint();

		// pick out a reference to our own gui
		List<ServiceGui> sgs = serviceGuiMap.get(getName());
		if (sgs != null) {
			for (int i = 0; i < sgs.size(); ++i) {
				// another potential bug :(
				guiServiceGui = (SwingGuiGui) sgs.get(i);
			}
		}
	}

	@Override
	public void stopService() {
		if (frame != null) {
			frame.dispose();
		}
		super.stopService();
	}

	public void undockPanel(final String label) {
		if (serviceGuiMap.containsKey(label)) {
			List<ServiceGui> sgs = serviceGuiMap.get(label);
			for (int i = 0; i < sgs.size(); ++i) {
				sgs.get(i).undockPanel();
			}
		}
	}

	public void unhideAll() {
		log.info("unhideAll");
		for (String key : serviceGuiMap.keySet()) {
			unhidePanel(key);
		}
	}

	// must handle docked or undocked & re-entrant for unhidden
	public void unhidePanel(final String label) {
		if (serviceGuiMap.containsKey(label)) {
			List<ServiceGui> sgs = serviceGuiMap.get(label);
			for (int i = 0; i < sgs.size(); ++i) {
				sgs.get(i).unhidePanel();
			}
		}
	}

	// @Override - only in Java 1.6
	@Override
	public void windowActivated(WindowEvent e) {
		// log.info("windowActivated");
	}

	// @Override - only in Java 1.6
	@Override
	public void windowClosed(WindowEvent e) {
		// log.info("windowClosed");
	}

	// @Override - only in Java 1.6
	@Override
	public void windowClosing(WindowEvent e) {
		// check for all service guis and see if its
		// ok to shutdown now

		Iterator<Map.Entry<String, List<ServiceGui>>> it = serviceGuiMap.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, List<ServiceGui>> pairs = it.next();
			// String serviceName = pairs.getKey();
			/*
			 * if (undockedPanels.containsKey(serviceName)) { UndockedPanel up =
			 * undockedPanels.get(serviceName); if (!up.isDocked()) {
			 * up.savePosition(); } }
			 */
			List<ServiceGui> sgs = pairs.getValue();
			for (int i = 0; i < sgs.size(); ++i) {
				ServiceGui sg = sgs.get(i);
				sg.savePosition();
				sg.isReadyForRelease();
				sg.makeReadyForRelease();
			}
		}

		save();
		Runtime.releaseAll();
		System.exit(1); // the Big Hamm'r
	}

	// @Override - only in Java 1.6
	@Override
	public void windowDeactivated(WindowEvent e) {
		// log.info("windowDeactivated");
	}

	// @Override - only in Java 1.6
	@Override
	public void windowDeiconified(WindowEvent e) {
		// log.info("windowDeiconified");
	}

	// @Override - only in Java 1.6
	@Override
	public void windowIconified(WindowEvent e) {
		// log.info("windowActivated");
	}

	// @Override - only in Java 1.6
	@Override
	public void windowOpened(WindowEvent e) {
		// log.info("windowOpened");

	}

	public static void main(String[] args) throws ClassNotFoundException, URISyntaxException {
		LoggingFactory.getInstance().configure();
		Logging logging = LoggingFactory.getInstance();
		try {
			logging.setLevel(Level.INFO);

			// Runtime.start("i01", "InMoov");
			// Runtime.start("mac", "Runtime");
			Runtime.start("python", "Python");
			// RemoteAdapter remote = (RemoteAdapter)Runtime.start("remote",
			// "RemoteAdapter");
			// remote.setDefaultPrefix("raspi");
			// remote.connect("tcp://127.0.0.1:6767");
			SwingGui gui = (SwingGui) Runtime.start("gui", "SwingGui");
			Runtime.start("python", "Python");
			gui.startService();

		} catch (Exception e) {
			Logging.logError(e);
		}
	}

	/**
	 * This static method returns all the details of the class without it having
	 * to be constructed. It has description, categories, dependencies, and peer
	 * definitions.
	 * 
	 * @return ServiceType - returns all the data
	 * 
	 */
	static public ServiceType getMetaData() {

		ServiceType meta = new ServiceType(SwingGui.class.getCanonicalName());
		meta.addDescription("Service used to graphically display and control other services");
		meta.addCategory("display");
		return meta;
	}

	public JTabbedPane getTabs() {
		return tabs;
	}

	public void setLastTabVisited(String tabName) {
		lastTabVisited = tabName;

	}

}
