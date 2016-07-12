package org.myrobotlab.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

import org.myrobotlab.cmdline.CmdLine;
import org.myrobotlab.codec.CodecUtils;
import org.myrobotlab.framework.Instantiator;
import org.myrobotlab.framework.MRLListener;
import org.myrobotlab.framework.Message;
import org.myrobotlab.framework.MessageListener;
import org.myrobotlab.framework.MethodEntry;
import org.myrobotlab.framework.Platform;
import org.myrobotlab.framework.Service;
import org.myrobotlab.framework.ServiceEnvironment;
import org.myrobotlab.framework.ServiceType;
import org.myrobotlab.framework.Status;
import org.myrobotlab.framework.repo.Repo;
import org.myrobotlab.framework.repo.ServiceData;
import org.myrobotlab.io.FileIO;
import org.myrobotlab.logging.Appender;
import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.logging.Logging;
import org.myrobotlab.logging.LoggingFactory;
import org.myrobotlab.net.HTTPRequest;
import org.myrobotlab.service.interfaces.Gateway;
import org.myrobotlab.service.interfaces.RepoInstallListener;
import org.myrobotlab.service.interfaces.ServiceInterface;
import org.myrobotlab.string.StringUtil;
import org.slf4j.Logger;

/**
 * Runtime is responsible for the creation and removal of all Services and the
 * associated static registries It maintains state information regarding
 * possible & running local Services It maintains state information regarding
 * foreign Runtimes It is a singleton and should be the only service of Runtime
 * running in a process The host and registry maps are used in routing
 * communication to the appropriate service (be it local or remote) It will be
 * the first Service created It also wraps the real JVM Runtime object.
 * 
 * TODO - get last args & better restart (with Agent possibly?)
 * 
 * RuntimeMXBean - scares me - but the stackTrace is clever RuntimeMXBean
 * runtimeMxBean = ManagementFactory.getRuntimeMXBean(); List<String> arguments
 * = runtimeMxBean.getInputArguments()
 * 
 * final StackTraceElement[] stackTrace =
 * Thread.currentThread().getStackTrace(); final String mainClassName =
 * stackTrace[stackTrace.length - 1].getClassName();
 * 
 * TODO - add check for 64 bit OS & 32 bit JVM :(
 * 
 */
public class Runtime extends Service implements MessageListener, RepoInstallListener {
  final static private long serialVersionUID = 1L;

  /**
   * instances of MRL - keyed with an instance key URI format is
   * mrl://gateway/(protocol key)
   */

  // gson only serializes - non static & non transient fields
  // has to be HashMap - because null is self
  /**
   * environments of running mrl instances - the null environment is the current
   * local
   */
  static private final HashMap<URI, ServiceEnvironment> environments = new HashMap<URI, ServiceEnvironment>();

  /**
   * a registry of all services regardless of which environment they came from -
   * each must have a unique name
   */
  static private final TreeMap<String, ServiceInterface> registry = new TreeMap<String, ServiceInterface>();

  /**
   * map to hide methods we are not interested in
   */
  // FIXME - REMOVE ALL STATICS !!!
  static private HashSet<String> hideMethods = new HashSet<String>();

  static private boolean needsRestart = false;

  static private String runtimeName;

  static private Date startDate = new Date();

  // DEPRECATED - use Service Timer
  // private boolean checkForUpdatesOnStart = true;

  // private boolean autoRestartAfterUpdate = false;

  static private boolean autoAcceptLicense = true; // at the moment

  /**
   * the local repo of this machine - it should not be static as other foreign
   * repos will come in with other Runtimes from other machines.
   */
  private Repo repo = Repo.getLocalInstance();
  private ServiceData serviceData = ServiceData.getLocalInstance();

  private Platform platform = Platform.getLocalInstance();

  private static long uniqueID = new Random(System.currentTimeMillis()).nextLong();

  public final static Logger log = LoggerFactory.getLogger(Runtime.class);

  /**
   * Object used to synchronize initializing this singleton.
   */
  transient private static final Object instanceLockObject = new Object();

  /**
   * The singleton of this class.
   */
  transient private static Runtime runtime = null;

  private List<String> jvmArgs;

  private List<String> args;

  /**
   * 
   * initially I thought that is would be a good idea to dynamically load
   * Services and append their definitions to the class path. This would
   * "theoretically" be done with ivy to get/download the appropriate dependent
   * jars from the repo. Then use a custom ClassLoader to load the new service.
   * 
   * Ivy works for downloading the appropriate jars & artifacts However, the
   * ClassLoader became very problematic
   * 
   * There is much mis-information around ClassLoaders. The most knowledgeable
   * article I have found has been this one :
   * http://blogs.oracle.com/sundararajan
   * /entry/understanding_java_class_loading
   * 
   * Overall it became a huge PITA with really very little reward. The
   * consequence is all Services' dependencies and categories are defined here
   * rather than the appropriate Service class.
   * 
   * @return
   */

  // private boolean shutdownAfterUpdate = false;

  static transient Cli cli;

  /**
   * global startingArgs - whatever came into main each runtime will have its
   * individual copy
   */
  static private String[] globalArgs;

  static private CmdLine cmdline = null;

  /**
   * Returns the number of processors available to the Java virtual machine.
   * 
   * @return
   */
  public static final int availableProcessors() {
    return java.lang.Runtime.getRuntime().availableProcessors();
  }

  public static ArrayList<String> buildMLRCommandLine(HashMap<String, String> services, String runtimeName) {

    ArrayList<String> ret = new ArrayList<String>();

    Platform platform = Platform.getLocalInstance();

    // library path
    String classpath = String.format("%s.%s.%s", platform.getArch(), platform.getBitness(), platform.getOS());
    String libraryPath = String.format("-Djava.library.path=\"./libraries/native/%s\"", classpath);
    ret.add(libraryPath);

    // class path
    String systemClassPath = System.getProperty("java.class.path");
    ret.add("-classpath");
    String classPath = String.format("./libraries/jar/*%1$s./libraries/jar/%2$s/*%1$s%3$s", platform.getClassPathSeperator(), classpath, systemClassPath);
    ret.add(classPath);

    ret.add("org.myrobotlab.service.Runtime");

    // ----- application level params --------------

    // services
    if (services.size() > 0) {
      ret.add("-service");
      for (Map.Entry<String, String> o : services.entrySet()) {
        ret.add(o.getKey());
        ret.add(o.getValue());
      }
    }

    // runtime name
    if (runtimeName != null) {
      ret.add("-runtimeName");
      ret.add(runtimeName);
    }

    // logLevel

    // logToConsole
    // ret.add("-logToConsole");

    return ret;
  }

  /**
   * 
   * @param name
   * @param type
   * @return
   */
  static public synchronized ServiceInterface create(String name, String type) {
    String fullTypeName;
    if (name.indexOf("/") != -1) {
      throw new IllegalArgumentException(String.format("can not have forward slash / in name %s", name));
    }
    if (type.indexOf(".") == -1) {
      fullTypeName = String.format("org.myrobotlab.service.%s", type);
    } else {
      fullTypeName = type;
    }
    return createService(name, fullTypeName);
  }

  static public ServiceInterface createAndStart(String name, String type) {
    ServiceInterface s = null;
    // framework level catch of all startServices
    // we will catch it here and log it with a stack trace
    // its not a good idea to let exceptions propegate higher - because
    // logging format can get challenging (Python trace-back) or they may
    // be completely lost - this is the last level the error can be handled
    // before
    // going into the unkown - so we catch it !
    try {
      s = create(name, type);
      s.startService();
    } catch (Exception e) {
      String error = e.getMessage();
      if (error == null) {
        error = "error";
      }
      Runtime.getInstance().error(String.format("createAndStart(%s, %s) %s", name, type, error));
      Logging.logError(e);
    }
    return s;
  }

  /**
   * creates and starts service from a cmd line object
   * 
   * @param cmdline
   *          data object from the cmd line
   */
  public final static void createAndStartServices(CmdLine cmdline) {

    System.out.println(String.format("createAndStartServices service count %1$d", cmdline.getArgumentCount("-service") / 2));

    if (cmdline.getArgumentCount("-service") > 0 && cmdline.getArgumentCount("-service") % 2 == 0) {

      for (int i = 0; i < cmdline.getArgumentCount("-service"); i += 2) {

        log.info(String.format("attempting to invoke : org.myrobotlab.service.%1$s named %2$s", cmdline.getSafeArgument("-service", i + 1, ""),
            cmdline.getSafeArgument("-service", i, "")));

        String name = cmdline.getSafeArgument("-service", i, "");
        String type = cmdline.getSafeArgument("-service", i + 1, "");
        ServiceInterface s = Runtime.create(name, type);

        if (s != null) {
          try {
            s.startService();
          } catch (Exception e) {
            runtime.error(e.getMessage());
            Logging.logError(e);
          }
        } else {
          runtime.error(String.format("could not create service %1$s %2$s", name, type));
        }

      }
      return;
    } /*
     * LIST ???
     * 
     * else if (cmdline.hasSwitch("-list")) { Runtime runtime =
     * Runtime.getInstance(); if (runtime == null) {
     * 
     * } else { System.out.println(getServiceTypeNames()); } return; }
     */
    mainHelp();
  }

  /**
   * @param name
   * @param cls
   * @return
   */
  static public synchronized ServiceInterface createService(String name, String fullTypeName) {
    log.info(String.format("Runtime.createService %s", name));
    if (name == null || name.length() == 0 || fullTypeName == null || fullTypeName.length() == 0) {
      log.error("{} not a type or {} not defined ", fullTypeName, name);
      return null;
    }

    ServiceInterface sw = Runtime.getService(name);
    if (sw != null) {
      log.debug("service {} already exists", name);
      return sw;
    }

    try {

      if (log.isDebugEnabled()) {
        // TODO - determine if there have been new classes added from
        // ivy --> Boot Classloader --> Ext ClassLoader --> System
        // ClassLoader
        // http://blog.jamesdbloom.com/JVMInternals.html
        log.debug("ABOUT TO LOAD CLASS");
        log.debug("loader for this class " + Runtime.class.getClassLoader().getClass().getCanonicalName());
        log.debug("parent " + Runtime.class.getClassLoader().getParent().getClass().getCanonicalName());
        log.debug("system class loader " + ClassLoader.getSystemClassLoader());
        log.debug("parent should be null" + ClassLoader.getSystemClassLoader().getParent().getClass().getCanonicalName());
        log.debug("thread context " + Thread.currentThread().getContextClassLoader().getClass().getCanonicalName());
        log.debug("thread context parent " + Thread.currentThread().getContextClassLoader().getParent().getClass().getCanonicalName());
      }

      Repo repo = Runtime.getInstance().getRepo();
      if (!repo.isServiceTypeInstalled(fullTypeName)) {
        log.error(String.format("%s is not installed", fullTypeName));
        if (autoAcceptLicense) {
          repo.install(fullTypeName);
        }
      }

      // create an instance
      Object newService = Instantiator.getNewInstance(fullTypeName, name);
      log.debug("returning {}", fullTypeName);
      return (Service) newService;
    } catch (Exception e) {
      Logging.logError(e);
    }
    return null;
  }

  /**
   * a method which returns a xml representation of all the listeners and routes
   * in the runtime system
   * 
   * @return
   */
  public static String dumpNotifyEntries() {
    ServiceEnvironment se = getLocalServices();

    Map<String, ServiceInterface> sorted = new TreeMap<String, ServiceInterface>(se.serviceDirectory);

    Iterator<String> it = sorted.keySet().iterator();
    String serviceName;
    ServiceInterface sw;
    String n;
    ArrayList<MRLListener> nes;
    MRLListener listener;

    StringBuffer sb = new StringBuffer().append("<NotifyEntries>");
    while (it.hasNext()) {
      serviceName = it.next();
      sw = sorted.get(serviceName);
      sb.append("<service name=\"").append(sw.getName()).append("\" serviceEnironment=\"").append(sw.getInstanceId()).append("\">");
      ArrayList<String> nlks = sw.getNotifyListKeySet();
      if (nlks != null) {
        Iterator<String> nit = nlks.iterator();

        while (nit.hasNext()) {
          n = nit.next();
          sb.append("<addListener map=\"").append(n).append("\">");
          nes = sw.getNotifyList(n);
          for (int i = 0; i < nes.size(); ++i) {
            listener = nes.get(i);
            sb.append("<MRLListener outMethod=\"").append(listener.topicMethod).append("\" name=\"").append(listener.callbackName).append("\" inMethod=\"")
            .append(listener.callbackMethod).append("\" />");
          }
          sb.append("</addListener>");
        }
      }
      sb.append("</service>");

    }
    sb.append("</NotifyEntries>");

    return sb.toString();
  }

  public static String dump() {
    try {

      StringBuffer sb = new StringBuffer().append("\ninstances:\n");
      Map<URI, ServiceEnvironment> sorted = environments;
      Iterator<URI> hkeys = sorted.keySet().iterator();
      URI url;
      ServiceEnvironment se;
      Iterator<String> it2;
      String serviceName;
      ServiceInterface sw;
      while (hkeys.hasNext()) {
        url = hkeys.next();
        se = environments.get(url);
        sb.append("\t").append(url);

        // Service Environment
        Map<String, ServiceInterface> sorted2 = new TreeMap<String, ServiceInterface>(se.serviceDirectory);
        it2 = sorted2.keySet().iterator();
        while (it2.hasNext()) {
          serviceName = it2.next();
          sw = sorted2.get(serviceName);
          sb.append("\t\t").append(serviceName);
          sb.append("\n");
        }
      }

      sb.append("\nregistry:");

      Map<String, ServiceInterface> sorted3 = new TreeMap<String, ServiceInterface>(registry);
      Iterator<String> rkeys = sorted3.keySet().iterator();
      while (rkeys.hasNext()) {
        serviceName = rkeys.next();
        sw = sorted3.get(serviceName);
        sb.append("\n").append(serviceName).append(" ").append(sw.getInstanceId());
      }

      sb.toString();

      FileIO.toFile(String.format("serviceRegistry.%s.txt", runtime.getName()), sb.toString());
      FileIO.toFile(String.format("notifyEntries.%s.xml", runtime.getName()), Runtime.dumpNotifyEntries());

      return sb.toString();
    } catch (Exception e) {
      Logging.logError(e);
    }

    return null;
  }

  /**
   * big hammer - exits no ifs ands or butts
   */
  public static final void exit() {
    exit(-1);
  }

  /**
   * Terminates the currently running Java virtual machine by initiating its
   * shutdown sequence. This method never returns normally. The argument serves
   * as a status code; by convention, a nonzero status code indicates abnormal
   * termination
   * 
   * @param status
   */
  public static final void exit(int status) {
    try {
      releaseAll();
    } catch (Exception e) {
      Logging.logError(e);
    }

    try {
      java.lang.Runtime.getRuntime().exit(status);
    } catch (Exception e) {
      Logging.logError(e);
    }
    //
    // In unusual situations, System.exit(int) might not actually stop the
    // program.
    // Runtime.getRuntime().halt(int) on the other hand, always does.
    java.lang.Runtime.getRuntime().halt(status);
  }

  static public boolean fromAgent() {
    return cmdline.containsKey("-fromAgent");
  }

  /**
   * Runs the garbage collector.
   */
  public static final void gc() {
    java.lang.Runtime.getRuntime().gc();
  }

  public static Cli getCli() {
    if (cli == null) {
      cli = startCli();
    }
    return cli;
  }

  /**
   * tricky way of getting static data "static" assumes you talking about "this"
   * Runtime and no other transported/networked/serialized Runtime .. and this
   * way Runtime == this instance's runtime !
   * 
   * @return
   */
  static public CmdLine getCMDLine() {
    return cmdline;
  }

  /**
   * although "fragile" since it relies on a external source - its useful to
   * find the external ip address of NAT'd systems
   * 
   * @return external or routers ip
   * @throws Exception
   */
  public static String getExternalIp() throws Exception {
    URL whatismyip = new URL("http://checkip.amazonaws.com");
    BufferedReader in = null;
    try {
      in = new BufferedReader(new InputStreamReader(whatismyip.openStream()));
      String ip = in.readLine();
      return ip;
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  /**
   * Returns the amount of free memory in the Java Virtual Machine. Calling the
   * gc method may result in increasing the value returned by freeMemory.
   * 
   * @return
   */
  public static final long getFreeMemory() {
    return java.lang.Runtime.getRuntime().freeMemory();
  }

  static public String[] getGlobalArgs() {
    return globalArgs;
  }

  /**
   * Get a handle to the Runtime singleton.
   * 
   * @return the Runtime
   */
  public static Runtime getInstance() {
    if (runtime == null) {
      synchronized (instanceLockObject) {
        if (runtime == null) {
          /*
           * Well that didn't work the way I wanted it to... :P
           * Thread.setDefaultUncaughtExceptionHandler(new
           * Thread.UncaughtExceptionHandler() {
           * 
           * @Override public void uncaughtException(Thread t, Throwable e) {
           * //System.out.println(t.getName() + ": " + e);
           * log.error(String.format(
           * "============ WHOOP WHOOP WHOOP WHOOP WHOOP WHOOP Thread %s threw %s ============"
           * , t.getName(), e.getMessage())); // MyWorker worker = new
           * MyWorker(); // worker.start(); } });
           */

          if (runtimeName == null) {
            runtimeName = "runtime";
          }
          runtime = new Runtime(runtimeName);
          Repo.getLocalInstance().addStatusListener(runtime);
        }
      }
    }
    return runtime;
  }

  static public List<String> getJVMArgs() {
    RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
    return runtimeMxBean.getInputArguments();
  }

  /**
   * gets all non-loopback, active, non-virtual ip addresses
   * 
   * @return list of local ipv4 IP addresses
   * @throws SocketException
   * @throws UnknownHostException
   */
  static public ArrayList<String> getLocalAddresses() {
    log.info("getLocalAddresses");
    ArrayList<String> ret = new ArrayList<String>();

    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface current = interfaces.nextElement();
        // log.info(current);
        if (!current.isUp() || current.isLoopback() || current.isVirtual()) {
          log.info("skipping interface is down, a loopback or virtual");
          continue;
        }
        Enumeration<InetAddress> addresses = current.getInetAddresses();
        while (addresses.hasMoreElements()) {
          InetAddress currentAddress = addresses.nextElement();

          if (!(currentAddress instanceof Inet4Address)) {
            log.info("not ipv4 skipping");
            continue;
          }

          if (currentAddress.isLoopbackAddress()) {
            log.info("skipping loopback address");
            continue;
          }
          log.info(currentAddress.getHostAddress());
          ret.add(currentAddress.getHostAddress());
        }
      }
    } catch (Exception e) {
      Logging.logError(e);
    }
    return ret;
  }

  // @TargetApi(9)
  static public ArrayList<String> getLocalHardwareAddresses() {
    log.info("getLocalHardwareAddresses");
    ArrayList<String> ret = new ArrayList<String>();
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface current = interfaces.nextElement();
        byte[] mac = current.getHardwareAddress();

        if (mac == null || mac.length == 0) {
          continue;
        }

        String m = StringUtil.bytesToHex(mac);
        log.info(String.format("mac address : %s", m));

        /*
         * StringBuilder sb = new StringBuilder(); for (int i = 0; i <
         * mac.length; i++) { sb.append(String.format("%02X%s", mac[i], (i <
         * mac.length - 1) ? "-" : "")); }
         */

        ret.add(m);
        log.info("added mac");
      }
    } catch (Exception e) {
      Logging.logError(e);
    }

    log.info("done");
    return ret;
  }

  /**
   * 
   * @return
   */
  public static ServiceEnvironment getLocalServices() {
    if (!environments.containsKey(null)) {
      runtime.error("local (null) ServiceEnvironment does not exist");
      return null;
    }

    return environments.get(null);
  }

  /**
   * getLocalServicesForExport returns a filtered map of Service references to
   * export to another instance of MRL. The objective of filtering may help
   * resolve functionality, security, or technical issues. For example, the
   * Dalvik JVM can only run certain Services. It would be error prone to export
   * a GUIService to a jvm which does not support swing.
   * 
   * Since the map of Services is made for export - it is NOT a copy but
   * references
   * 
   * The filtering is done by Service Type.. although in the future it could be
   * extended to Service.getName()
   * 
   * @return
   * 
   */
  public static ServiceEnvironment getLocalServicesForExport() {
    if (!environments.containsKey(null)) {
      runtime.error("local (null) ServiceEnvironment does not exist");
      return null;
    }

    ServiceEnvironment local = environments.get(null);

    // URI is null but the "acceptor" will fill in the correct URI/ID
    ServiceEnvironment export = new ServiceEnvironment(null);

    Iterator<String> it = local.serviceDirectory.keySet().iterator();
    String name;
    ServiceInterface sw;
    while (it.hasNext()) {
      name = it.next();
      sw = local.serviceDirectory.get(name);
      if (security == null || security.allowExport(name)) {
        // log.info(String.format("exporting service: %s of type %s",
        // name, sw.getServiceType()));
        export.serviceDirectory.put(name, sw); // FIXME !! make note
        // when XMPP or Remote
        // Adapter pull it -
        // they have to reset
        // this !!
      } else {
        log.info(String.format("security prevents export of %s", name));
        continue;
      }
    }

    return export;
  }

  /**
   * FIXME - DEPRECATE - THIS IS NOT "instance" specific info - its Class
   * definition info - Runtime should return based on ClassName
   * 
   * @param serviceName
   * @return
   */
  public static Map<String, MethodEntry> getMethodMap(String serviceName) {
    if (!registry.containsKey(serviceName)) {
      runtime.error(String.format("%1$s not in registry - can not return method map", serviceName));
      return null;
    }

    Map<String, MethodEntry> ret = new TreeMap<String, MethodEntry>();
    ServiceInterface sw = registry.get(serviceName);

    Class<?> c = sw.getClass();
    Method[] methods = c.getDeclaredMethods();

    Method m;
    MethodEntry me;
    String s;
    for (int i = 0; i < methods.length; ++i) {
      m = methods[i];

      if (hideMethods.contains(m.getName())) {
        continue;
      }
      me = new MethodEntry(m);
      me.parameterTypes = m.getParameterTypes();
      me.returnType = m.getReturnType();
      s = me.getSignature();
      ret.put(s, me);
    }

    return ret;
  }

  public static String getPid() {

    SimpleDateFormat TSFormatter = new SimpleDateFormat("yyyyMMddHHmmssSSS");
    final String fallback = TSFormatter.format(new Date());

    try {

      // something like '<pid>@<hostname>', at least in SUN / Oracle JVMs
      final String jvmName = ManagementFactory.getRuntimeMXBean().getName();
      final int index = jvmName.indexOf('@');

      if (index < 1) {
        // part before '@' empty (index = 0) / '@' not found (index =
        // -1)
        return fallback;
      }

      return Long.toString(Long.parseLong(jvmName.substring(0, index)));
    } catch (Exception e) {

    }

    return fallback;
  }

  /**
   * 
   * @return
   */
  public static Map<String, ServiceInterface> getRegistry() {
    return registry;// FIXME should return copy
  }

  /**
   * Return the named service - - if name is not null, but service is not found
   * - return null (for re-entrant Service creation) - if the name IS null,
   * return Runtime - to support api/getServiceNames - if the is not null, and
   * service is found - return the Service
   * 
   * @param name
   * @return
   */
  public static ServiceInterface getService(String name) {

    if (name == null || name.length() == 0) {
      return Runtime.getInstance();
    }
    if (!registry.containsKey(name)) {
      return null;
    } else {
      return registry.get(name);
    }
  }

  /**
   * 
   * @param url
   * @return
   */
  public static ServiceEnvironment getEnvironment(URI url) {
    if (environments.containsKey(url)) {
      return environments.get(url); // FIXME should return copy
    }
    return null;
  }

  /**
   * 
   * @return
   */
  public static HashMap<URI, ServiceEnvironment> getEnvironments() {
    // return copy
    return new HashMap<URI, ServiceEnvironment>(environments);
  }

  // Reference - cpu utilization
  // http://www.javaworld.com/javaworld/javaqa/2002-11/01-qa-1108-cpu.html

  /**
   * list of currently created services
   * 
   * @return
   */
  static public String[] getServiceNames() {
    List<ServiceInterface> si = getServices();
    String[] ret = new String[si.size()];
    for (int i = 0; i < ret.length; ++i) {
      ret[i] = si.get(i).getName();
    }
    return ret;
  }

  public static ArrayList<String> getServiceNamesFromInterface(String interfaze) throws ClassNotFoundException {
    return getServiceNamesFromInterface(Class.forName(interfaze));
  }

  /**
   * @param interfaceName
   * @return service names which match
   */
  public static ArrayList<String> getServiceNamesFromInterface(Class<?> interfaze) {
    ArrayList<String> ret = new ArrayList<String>();
    ArrayList<ServiceInterface> services = getServicesFromInterface(interfaze);
    for (int i = 0; i < services.size(); ++i) {
      ret.add(services.get(i).getName());
    }
    return ret;
  }

  public static List<ServiceInterface> getServices() {
    List<ServiceInterface> list = new ArrayList<ServiceInterface>(registry.values());
    return list;
  }

  /**
   * @param interfaze
   * @return services which match
   */
  public static ArrayList<ServiceInterface> getServicesFromInterface(Class<?> interfaze) {
    ArrayList<ServiceInterface> ret = new ArrayList<ServiceInterface>();

    Iterator<String> it = registry.keySet().iterator();
    String serviceName;
    ServiceInterface sw;
    Class<?> c;
    Class<?>[] interfaces;
    Class<?> m;
    while (it.hasNext()) {
      serviceName = it.next();
      sw = registry.get(serviceName);
      c = sw.getClass();
      interfaces = c.getInterfaces();
      for (int i = 0; i < interfaces.length; ++i) {
        m = interfaces[i];

        if (m.equals(interfaze)) {
          ret.add(sw);
        }
      }
    }

    return ret;
  }

  static public Set<Thread> getThreads() {
    return Thread.getAllStackTraces().keySet();
  }

  /**
   * dorky pass-throughs to the real JVM Runtime
   * 
   * @return
   */
  public static final long getTotalMemory() {

    return java.lang.Runtime.getRuntime().totalMemory();
  }

  /**
   * attempt to get physical memory from the jvm not supported in all jvms..
   * 
   * @return
   */
  static public long getTotalPhysicalMemory() {
    try {

      com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
      long physicalMemorySize = os.getTotalPhysicalMemorySize();
      return physicalMemorySize;
    } catch (Exception e) {
      log.error("getTotalPhysicalMemory - threw");
    }
    return 0;
  }

  /**
   * unique id's are need for sendBlocking - to uniquely identify the message
   * this is a method to support that - it is unique within a process, but not
   * across processes
   * 
   * @return a unique id
   */
  public static final synchronized long getUniqueID() {
    ++uniqueID;
    return uniqueID;
  }

  public static String getUptime() {
    Date now = new Date();
    long diff = now.getTime() - startDate.getTime();

    long diffSeconds = diff / 1000 % 60;
    long diffMinutes = diff / (60 * 1000) % 60;
    long diffHours = diff / (60 * 60 * 1000) % 24;
    long diffDays = diff / (24 * 60 * 60 * 1000);

    StringBuffer sb = new StringBuffer();
    sb.append(diffDays).append(" days ").append(diffHours).append(" hours ").append(diffMinutes).append(" minutes ").append(diffSeconds).append(" seconds");
    return sb.toString();
  }

  public static String getVersion() {
    return Platform.getLocalInstance().getVersion();// FileIO.resourceToString("version.txt");
  }

  // FIXME - shouldn't this be in platform ???
  public static String getBranch() {
    return Platform.getLocalInstance().getBranch();// FileIO.resourceToString("branch.txt");
  }

  static public void install() throws ParseException, IOException {
    // if a runtime exits we'll broadcast we are starting to install
    ServiceData sd = ServiceData.getLocalInstance();
    if (runtime != null) {
      runtime.onInstallProgress(Repo.createStartStatus("starting installation of %s services", sd.getServiceTypeNames().length));
    }

    Repo.getLocalInstance().install();

    // if a runtime exits we'll broadcast we are done installing
    if (runtime != null) {
      runtime.onInstallProgress(Repo.createFinishedStatus("finished installing %s", sd.getServiceTypeNames().length));
    }
  }

  /**
   * Installs a single Service type. This "should" work even if there is no
   * Runtime. It can be invoked on the command line without starting a MRL
   * instance. If a runtime exits it will broadcast events of installation
   * progress
   * 
   * @param serviceType
   * @throws ParseException
   * @throws IOException
   */
  static public void install(String serviceType) throws ParseException, IOException {

    // if a runtime exits we'll broadcast we are starting to install
    if (runtime != null) {
      runtime.onInstallProgress(Repo.createStartStatus("starting installation of %s", serviceType));
    }

    Repo.getLocalInstance().install(serviceType);

    // if a runtime exits we'll broadcast we are done installing
    if (runtime != null) {
      runtime.onInstallProgress(Repo.createFinishedStatus("finished installing %s", serviceType));
    }
  }

  /**
   * broadcast of Service install progress
   * 
   * @param status
   * @return
   */
  public Status publishInstallProgress(Status status) {
    return status;
  }

  /**
   * direct callback from the Repo on installation progress we re-broadcast this
   * on a topic
   */
  public void onInstallProgress(final Status status) {
    invoke("publishInstallProgress", status);
  }

  static public void invokeCommands(CmdLine cmdline) {
    int argCount = cmdline.getArgumentCount("-invoke");
    if (argCount > 1) {

      StringBuffer params = new StringBuffer();

      ArrayList<String> invokeList = cmdline.getArgumentList("-invoke");
      Object[] data = new Object[argCount - 2];
      for (int i = 2; i < argCount; ++i) {
        data[i - 2] = invokeList.get(i);
        params.append(String.format("%s ", invokeList.get(i)));
      }

      String name = cmdline.getArgument("-invoke", 0);
      String method = cmdline.getArgument("-invoke", 1);

      log.info(String.format("attempting to invoke : %s.%s(%s)\n", name, method, params.toString()));
      getInstance().send(name, method, data);

    }

  }

  /**
   * invoked to confirm with the user it is appropriate to restart now
   * 
   * @return - the current autoRestartAfterUpdate to put in dialog to (never ask
   *         again)
   */
  /*
   * public boolean confirmRestart() { needsRestart = true; return
   * autoRestartAfterUpdate; }
   */

  static public boolean isAgent() {
    if (cmdline == null) {
      return false;
    }
    return cmdline.containsKey("-isAgent");
  }

  static public boolean isHeadless() {
    // String awt = "java.awt.GraphicsEnvironment";
    // java.awt.GraphicsEnvironment.isHeadless()
    // String nm = System.getProperty("java.awt.headless");
    // should return true if Linux != display
    String b = System.getProperty("java.awt.headless");
    return Boolean.parseBoolean(b);
  }

  public static boolean isLocal(String serviceName) {
    ServiceInterface sw = getService(serviceName);
    return sw.isLocal();
  }

  /**
   * check if class is a Runtime class
   * 
   * @param newService
   * @return true if class == Runtime.class
   */
  public static boolean isRuntime(Service newService) {
    return newService.getClass().equals(Runtime.class);
  }

  /**
   * load all configuration from all local services
   * 
   * @return
   */
  static public boolean loadAll() {
    boolean ret = true;
    ServiceEnvironment se = getLocalServices();
    Iterator<String> it = se.serviceDirectory.keySet().iterator();
    String serviceName;
    while (it.hasNext()) {
      serviceName = it.next();
      ServiceInterface sw = se.serviceDirectory.get(serviceName);
      ret &= sw.load();
    }

    return ret;
  }

  /**
   * 
   * @param filename
   */
  public static final void loadLibrary(String filename) {
    java.lang.Runtime.getRuntime().loadLibrary(filename);
  }

  /**
   * Main starting method of MyRobotLab Parses command line options
   * 
   * -h help -v version -list jvm args -Dhttp.proxyHost=webproxy
   * -Dhttp.proxyPort=80 -Dhttps.proxyHost=webproxy -Dhttps.proxyPort=80
   * 
   * @param args
   */
  public static void main(String[] args) {
    System.out.println(Arrays.toString(args));
    // global for this process
    globalArgs = args;

    // sub-process if there is one

    cmdline = new CmdLine(args);

    Logging logging = LoggingFactory.getInstance();

    try {

      logging.setLevel(cmdline.getSafeArgument("-logLevel", 0, "INFO"));

      if (cmdline.containsKey("-v") || cmdline.containsKey("--version")) {
        System.out.print(Runtime.getVersion());
        return;
      }
      if (cmdline.containsKey("-runtimeName")) {
        runtimeName = cmdline.getSafeArgument("-runtimeName", 0, "MRL");
      }

      /*
       * Previously the Agent remained running - and would siphon std:in & std:out
      if (cmdline.containsKey("-isAgent")) {
        logging.addAppender(Appender.IS_AGENT);
      } else if (cmdline.containsKey("-fromAgent")) {
        logging.addAppender(Appender.FROM_AGENT);
      } else 
      */
    	  
      if (cmdline.containsKey("-logToConsole")) {
        logging.addAppender(Appender.CONSOLE);
      } else {
        logging.addAppender(Appender.FILE, String.format("%s.log", runtimeName));
      }

      log.info(cmdline.toString());

      if (cmdline.containsKey("-h") || cmdline.containsKey("--help")) {
        mainHelp();
        return;
      }

      // logging.addAppender(Appender.CONSOLE); hopefully it still worky
      // after removing this ! :)

      if (!cmdline.containsKey("-noCLI")) {
        Runtime.getInstance();
        startCli();
      }

      // LINUX LD_LIBRARY_PATH MUST BE EXPORTED - NO OTHER SOLUTION FOUND
      // hack to reconcile the different ways os handle and expect
      // "PATH & LD_LIBRARY_PATH" to be handled
      // found here -
      // http://blog.cedarsoft.com/2010/11/setting-java-library-path-programmatically/
      // but does not work

      if (cmdline.containsKey("-install")) {
        // force all updates
        ArrayList<String> services = cmdline.getArgumentList("-install");
        Repo repo = Repo.getLocalInstance();
        if (services.size() == 0) {
          repo.install();
        } else {
          for (int i = 0; i < services.size(); ++i) {
            repo.install(services.get(i));
          }
        }
        shutdown();
        return;
      }

      if (cmdline.containsKey("-service")) {
        createAndStartServices(cmdline);
      }

      if (cmdline.containsKey("-invoke")) {
        invokeCommands(cmdline);
      }

    } catch (Exception e) {
      Logging.logError(e);
      System.out.print(Logging.stackToString(e));
      Service.sleep(2000);
    }
  }

  /**
   * prints help to the console
   */
  static void mainHelp() {
    System.out.println(String.format("Runtime %s", Runtime.getVersion()));
    System.out.println("-h --help			                       # help ");
    System.out.println("-v --version		                       # print version");
    System.out.println("-update   			                       # update myrobotlab");
    System.out.println("-invoke name method [param1 param2 ...]    # invoke a method of a service");
    System.out.println("-install [ServiceType1 ServiceType2 ...]   # install services - if no ServiceTypes are specified install all");
    System.out.println("-runtimeName <runtime name>                # rename the Runtime service - prevents multiple instance name collisions");
    System.out.println("-logToConsole                              # redirects logging to console");
    System.out.println("-logLevel <DEBUG | INFO | WARNING | ERROR> # log level");
    System.out.println("-service <name1 Type1 name2 Type2 ...>     # create and start list of services, e.g. -service gui GUIService");
    System.out.println("example:");
    String helpString = "java -Djava.library.path=./libraries/native/x86.32.windows org.myrobotlab.service.Runtime -service gui GUIService -logLevel INFO -logToConsole";
    System.out.println(helpString);
  }

  public static String message(String msg) {
    getInstance().invoke("publishMessage", msg);
    log.info(msg);
    return msg;
  }

  /**
   * needsRestart is set by the update process - or some othe method when a
   * restart is needed. Used by other Services to prepare for restart
   * 
   * @return needsRestart
   */
  public static boolean needsRestart() {
    return needsRestart;
  }

  public void onState(ServiceInterface updatedService) {
    log.info("runtime updating registry info for remote service {}", updatedService.getName());
    registry.put(updatedService.getName(), updatedService);
    ServiceEnvironment se = environments.get(updatedService.getInstanceId());
    if (se != null) {
      se.serviceDirectory.put(updatedService.getName(), updatedService);
    } else {
      error("onState ServiceEnvironment null");
    }
  }

  // ---------- Java Runtime wrapper functions end --------
  /**
   * 
   * FIXME - need Platform information (reference from within service ???
   * 
   * @param url
   * @param s
   * @return
   */
  public final static synchronized ServiceInterface register(ServiceInterface s, URI url) {
    ServiceEnvironment se = null;
    if (!environments.containsKey(url)) {
      se = new ServiceEnvironment(url);
      environments.put(url, se);
    } else {
      se = environments.get(url);
    }

    if (s == null) {
      // register null is how
      // initial communication starts
      // between two instances
      return null;
    }

    String name = s.getName();

    if (se.serviceDirectory.containsKey(name)) {
      log.info("attempting to register {} which is already registered in {}", name, url);
      if (runtime != null) {
        runtime.invoke("collision", name);// necessary ? collisions are
        // 'expected' in mrl's
        // evolution
        log.info("collision registering {}", name);
        // runtime.error(String.format(" name collision or already
        // registered with %s", name));
      }
      return s;// <--- BUG ?!?!? WHAT ABOUT THE REMOTE GATEWAYS !!!
    }

    // REMOTE BROADCAST to all foreign environments
    // FIXME - Security determines what to export
    // for each gateway

    ArrayList<String> remoteGateways = getServiceNamesFromInterface(Gateway.class);
    for (int ri = 0; ri < remoteGateways.size(); ++ri) {
      String n = remoteGateways.get(ri);
      // Communicator gateway = (Communicator)registry.get(n);
      ServiceInterface gateway = registry.get(n);

      // for each JVM this gateway is attached too
      for (Map.Entry<URI, ServiceEnvironment> o : environments.entrySet()) {
        URI uri = o.getKey();
        // if its a foreign JVM & the gateway responsible for the
        // remote
        // connection and
        // the foreign JVM is not the host which this service
        // originated
        // from - send it....
        if (uri != null && gateway.getName().equals(uri.getHost()) && !uri.equals(s.getInstanceId())) {
          log.info(String.format("gateway %s sending registration of %s remote to %s", gateway.getName(), name, uri));
          // FIXME - Security determines what to export
          Message msg = runtime.createMessage("", "register", s);
          // ((Communicator) gateway).sendRemote(uri, msg);
          // //mrl://remote2/tcp://127.0.0.1:50488 <-- wrong
          // sendingRemote is wrong
          // FIXME - optimize gateway.send(msg) && URI TO URI MAP
          // IN
          // RUNTIME !!!
          gateway.in(msg);
        }
      }
    }

    // ServiceInterface sw = new ServiceInterface(s, se.accessURL);
    se.serviceDirectory.put(name, s);
    // WARNING - SHOULDN'T THIS BE DONE FIRST AVOID DEADLOCK / RACE
    // CONDITION ????
    registry.put(name, s); // FIXME FIXME FIXME FIXME !!!!!!
    // pre-pend
    // URI if not NULL !!!
    if (runtime != null) {
      runtime.invoke("registered", s);
    }

    // new --------
    // we want to subscribe to state changes
    if (!s.isLocal()) {
      runtime.subscribe(name, "publishState");
    }
    // end new ----

    return s;

  }

  /**
   * releases a service - stops the service, its threads, releases its
   * resources, and removes registry entries
   * 
   * @param name
   *          of the service to be released
   * @return whether or not it successfully released the service
   */
  public synchronized static boolean release(String name) {
    log.info("releasing service {}", name);
    Runtime rt = getInstance();
    if (!registry.containsKey(name)) {
      rt.error("release could not find %s", name);
      return false;
    }
    ServiceInterface sw = registry.remove(name);
    if (sw.isLocal()) {
      sw.stopService();
    }
    ServiceEnvironment se = environments.get(sw.getInstanceId());
    se.serviceDirectory.remove(name);
    rt.invoke("released", sw);
    log.info("released {}", name);
    return true;
  }

  /**
   * 
   * @param url
   * @return
   */
  public static boolean release(URI url) /* release process environment */
  {
    boolean ret = true;
    ServiceEnvironment se = environments.get(url);
    if (se == null) {
      log.warn("attempt to release {} not successful - it does not exist", url);
      return false;
    }
    log.info(String.format("releasing url %1$s", url));
    String[] services = se.serviceDirectory.keySet().toArray(new String[se.serviceDirectory.keySet().size()]);
    String runtimeName = null;
    ServiceInterface service;
    for (int i = 0; i < services.length; ++i) {
      service = registry.get(services[i]);
      if (service != null && "Runtime".equals(service.getSimpleName())) {
        runtimeName = service.getName();
        log.info(String.format("delaying release of Runtime %1$s", runtimeName));
        continue;
      }
      ret &= release(services[i]);
    }

    if (runtimeName != null) {
      ret &= release(runtimeName);
    }

    return ret;
  }

  /**
   * This does not EXIT(1) !!! releasing just releases all services
   * 
   * FIXME FIXME FIXME - just call release on each - possibly saving runtime for
   * last .. send prepareForRelease before releasing
   * 
   * release all local services
   * 
   * FIXME - there "should" be an order to releasing the correct way would be to
   * save the Runtime for last and broadcast all the services being released
   * 
   * FIXME - send SHUTDOWN event to all running services with a timeout period -
   * end with System.exit() FIXME normalize with releaseAllLocal and
   * releaseAllExcept
   */
  public static void releaseAll() /* local only? YES !!! LOCAL ONLY !! */
  {
    log.debug("releaseAll");

    ServiceEnvironment se = environments.get(null); // local services only
    if (se == null) {
      log.info("releaseAll called when everything is released, all done here");
      return;
    }
    Iterator<String> seit = se.serviceDirectory.keySet().iterator();
    String serviceName;
    ServiceInterface sw;

    seit = se.serviceDirectory.keySet().iterator();
    while (seit.hasNext()) {
      serviceName = seit.next();
      sw = se.serviceDirectory.get(serviceName);

      if (sw == Runtime.getInstance()) {
        // skipping runtime
        continue;
      }

      log.info(String.format("stopping service %s", serviceName));

      if (sw == null) {
        log.warn("unknown type and/or remote service");
        continue;
      }
      // runtime.invoke("released", se.serviceDirectory.get(serviceName));
      // FIXME DO THIS
      try {
        sw.stopService();
        // sw.releaseService(); // FIXED ! - releaseService will mod the
        // maps :P
        runtime.invoke("released", sw);
      } catch (Exception e) {
        runtime.error(String.format("%s threw while stopping",e));
        Logging.logError(e);
      }
    }

    runtime.stopService();

    log.info("clearing hosts environments");
    environments.clear();

    log.info("clearing registry");
    registry.clear();

    // exit () ?
  }

  public static void shutdown() {
    releaseAll();
    System.exit(-1);
  }

  // ---------------- callback events end -------------

  // ---------------- Runtime begin --------------

  public static void releaseAllServicesExcept(HashSet<String> saveMe) {
    log.info("releaseAllServicesExcept");
    List<ServiceInterface> list = Runtime.getServices();
    for (int i = 0; i < list.size(); ++i) {
      ServiceInterface si = list.get(i);
      if (saveMe != null && saveMe.contains(si.getName())) {
        log.info("leaving {}", si.getName());
        continue;
      } else {
        si.releaseService();
      }
    }

  }

  /**
   * @param name
   *          - name of Service to be removed and whos resources will be
   *          released
   */
  static public void releaseService(String name) {
    Runtime.release(name);
  }

  // ============== update events begin ==============
  /**
   * 
   * should probably be deprecated - currently not used
   * 
   * @param runBeforeRestart
   * 
   *          will this work on a file lock update?
   */
  static public void restart(Runnable runBeforeRestart) {
    final java.lang.Runtime r = java.lang.Runtime.getRuntime();
    log.info("restart - restart?");
    Runtime.releaseAll();
    try {
      // java binary
      String java = System.getProperty("java.home") + "/bin/java";

      // init the command to execute, add the vm args
      final StringBuffer cmd = new StringBuffer("\"" + java + "\" ");

      // program main and program arguments
      String[] mainCommand = globalArgs;
      // program main is a jar
      if (mainCommand[0].endsWith(".jar")) {
        // if it's a jar, add -jar mainJar
        cmd.append("-jar " + new File(mainCommand[0]).getPath());
      } else {
        // else it's a .class, add the classpath and mainClass
        cmd.append("-cp \"" + System.getProperty("java.class.path") + "\" " + mainCommand[0]);
      }
      // finally add program arguments
      for (int i = 1; i < mainCommand.length; i++) {
        cmd.append(" ");
        cmd.append(mainCommand[i]);
      }
      // execute the command in a shutdown hook, to be sure that all the
      // resources have been disposed before restarting the application

      r.addShutdownHook(new Thread() {
        @Override
        public void run() {
          try {
            r.exec(cmd.toString());
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      });
      // execute some custom code before restarting
      if (runBeforeRestart != null) {
        runBeforeRestart.run();
      }
      // exit
    } catch (Exception ex) {
      Logging.logError(ex);
    }
    System.exit(0);

  }

  /**
   * save all configuration from all local services
   * 
   * @return
   */
  static public boolean saveAll() {
    boolean ret = true;
    ServiceEnvironment se = getLocalServices();
    Iterator<String> it = se.serviceDirectory.keySet().iterator();
    String serviceName;
    while (it.hasNext()) {
      serviceName = it.next();
      ServiceInterface sw = se.serviceDirectory.get(serviceName);
      ret &= sw.save();
    }

    return ret;
  }

  public static boolean setJSONPrettyPrinting(boolean b) {
    return CodecUtils.setJSONPrettyPrinting(b);
  }

  public static void setRuntimeName(String inName) {
    runtimeName = inName;
  }

  static public ServiceInterface start(String name, String type) {
    return createAndStart(name, type);
  }

  static public Cli startCli() {
    cli = (Cli) start("cli", "Cli");
    return cli;
  }

  static public void stopCli() {
    if (cli != null) {
      release(cli.getName());
    }
  }

  public Runtime(String n) {
    super(n);

    synchronized (instanceLockObject) {
      if (runtime == null) {
        runtime = this;
      }
    }

    String libararyPath = System.getProperty("java.library.path");
    String userDir = System.getProperty("user.dir");
    String userHome = System.getProperty("user.home");

    String vmName = System.getProperty("java.vm.name");
    // TODO this should be a single log statement
    // http://developer.android.com/reference/java/lang/System.html

    Date now = new Date();
    String format = "yyyy/MM/dd HH:mm:ss";
    SimpleDateFormat sdf = new SimpleDateFormat(format);
    SimpleDateFormat gmtf = new SimpleDateFormat(format);
    gmtf.setTimeZone(TimeZone.getTimeZone("UTC"));
    log.info("============== args begin ==============");
    StringBuffer sb = new StringBuffer();

    jvmArgs = getJVMArgs();
    args = new ArrayList<String>();
    if (globalArgs != null) {
      for (int i = 0; i < globalArgs.length; ++i) {
        sb.append(globalArgs[i]);
        args.add(globalArgs[i]);
      }
    }
    if (jvmArgs != null) {
      log.info(String.format("jvmArgs %s", Arrays.toString(jvmArgs.toArray())));
    }
    log.info(String.format("args %s", Arrays.toString(args.toArray())));

    log.info("============== args end ==============");
    if (cmdline != null && !cmdline.containsKey("-noEnv")) {
      log.info("============== env begin ==============");
      Map<String, String> env = System.getenv();
      for (Map.Entry<String, String> entry : env.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        log.info(String.format("%s=%s", key, value));
      }
      log.info("============== env end ==============");
    }

    // Platform platform = Platform.getLocalInstance();
    log.info("============== normalized ==============");
    log.info("{} - GMT - {}", sdf.format(now), gmtf.format(now));
    log.info("Pid {}", getPid());
    log.info("ivy [runtime,{}.{}.{}]", platform.getArch(), platform.getBitness(), platform.getOS());
    log.info("os.name [{}] getOS [{}]", System.getProperty("os.name"), platform.getOS());
    log.info("os.arch [{}] getArch [{}]", System.getProperty("os.arch"), platform.getArch());
    log.info("getBitness [{}]", platform.getBitness());
    log.info("java.vm.name [{}] getVMName [{}]", vmName, platform.getVMName());
    log.info("version [{}]", Runtime.getVersion());
    log.info("root [{}]", FileIO.getRoot());
    log.info("cfg dir [{}]", FileIO.getCfgDir());
    log.info("sun.arch.data.model [{}]", System.getProperty("sun.arch.data.model"));

    log.info("============== non-normalized ==============");
    log.info("java.vm.name [{}]", vmName);
    log.info("java.vm.version [{}]", System.getProperty("java.vm.version"));
    log.info("java.vm.vendor [{}]", System.getProperty("java.vm.vendor"));
    log.info("java.vm.version [{}]", System.getProperty("java.vm.version"));
    log.info("java.vm.vendor [{}]", System.getProperty("java.runtime.version"));

    // System.getProperty("pi4j.armhf")
    log.info("os.version [{}]", System.getProperty("os.version"));
    log.info("os.version [{}]", System.getProperty("os.version"));

    log.info("java.home [{}]", System.getProperty("java.home"));
    log.info("java.class.path [{}]", System.getProperty("java.class.path"));
    log.info("java.library.path [{}]", libararyPath);
    log.info("user.dir [{}]", userDir);

    log.info("user.home [{}]", userHome);
    log.info("total mem [{}] Mb", Runtime.getTotalMemory() / 1048576);
    log.info("total free [{}] Mb", Runtime.getFreeMemory() / 1048576);
    log.info("total physical mem [{}] Mb", Runtime.getTotalPhysicalMemory() / 1048576);

    log.info("getting local repo");

    Repo.getLocalInstance().addStatusListener(this);

    hideMethods.add("main");
    hideMethods.add("loadDefaultConfiguration");
    hideMethods.add("getDescription");
    hideMethods.add("run");
    hideMethods.add("access$0");

    // TODO - check for updates on startup ???

    // starting this
    try {
      startService();
    } catch (Exception e) {
      error("OMG Runtime won't start GAME OVER ! :( %s", e.getMessage());
      Logging.logError(e);
    }
  }

  /**
   * publishing event - since checkForUpdates may take a while
   */
  public void checkingForUpdates() {
    log.info("checking for updates");
  }

  /**
   * collision event - when a registration is attempted but there is a name
   * collision
   * 
   * @param name
   *          - the name of the two Services with the same name
   * @return
   */
  public String collision(String name) {
    return name;
  }

  // ---------- Java Runtime wrapper functions begin --------
  /**
   * Executes the specified command and arguments in a separate process. Returns
   * the exit value for the subprocess.
   * 
   * @param params
   * @return
   */
  public int exec(String[] params) {
    java.lang.Runtime r = java.lang.Runtime.getRuntime();
    try {
      Process p = r.exec(params);
      return p.exitValue();
    } catch (IOException e) {
      logException(e);
    }

    return 0;
  }

  /**
   * publishing point of Ivy sub system - sends event failedDependency when the
   * retrieve report for a Service fails
   * 
   * @param dep
   * @return
   */
  public String failedDependency(String dep) {
    return dep;
  }

  /**
   * returns version string of MyRobotLab
   * 
   * @return
   */
  public String getLocalVersion() {
    return getVersion(null);
  }

  // FIXME - you don't need that many "typed" messages - resolve,
  // resolveError, ... etc
  // just use & parse "message"

  public Platform getPlatform() {
    return platform;
  }

  /**
   * returns the platform type of a remote system
   * 
   * @param uri
   *          - the access uri of the remote system
   * @return Platform description
   */
  public Platform getPlatform(URI uri) {
    ServiceEnvironment local = environments.get(uri);
    if (local != null) {
      return local.platform;
    }

    error("can't get local platform in service environment");

    return null;
  }

  public Repo getRepo() {
    return repo;
  }

  /**
   * Gets the current total number of services registered services. This is the
   * number of services in all Service Environments
   * 
   * @return total number of services
   */
  public int getServiceCount() {
    int cnt = 0;
    Iterator<URI> it = environments.keySet().iterator();
    ServiceEnvironment se;
    Iterator<String> it2;
    while (it.hasNext()) {
      se = environments.get(it.next());
      it2 = se.serviceDirectory.keySet().iterator();
      while (it2.hasNext()) {
        ++cnt;
        it2.next();
      }
    }
    return cnt;
  }

  // ============== configuration begin ==============

  /**
   * 
   * @return
   */
  public int getEnvironmentCount() {
    return environments.size();
  }

  /**
   * Returns an array of all the simple type names of all the possible services.
   * The data originates from the repo's serviceData.xml file https:/
   * /code.google.com/p/myrobotlab/source/browse/trunk/myrobotlab/thirdParty
   * /repo/serviceData.xml
   * 
   * There is a local one distributed with the install zip When a "update" is
   * forced, MRL will try to download the latest copy from the repo.
   * 
   * The serviceData.xml lists all service types, dependencies, categories and
   * other relevant information regarding service creation
   * 
   * @return
   */
  public String[] getServiceTypeNames() {
    return getServiceTypeNames("all");
  }

  // ============== configuration end ==============

  /**
   * publishing event to get the possible services currently available
   * 
   * @param filter
   * @return
   */
  public String[] getServiceTypeNames(String filter) {
    return serviceData.getServiceTypeNames(filter);
  }

  /**
   * returns version string of MyRobotLab instance based on uri e.g : uri
   * mrl://10.5.3.1:7777 may be a remote instance null uri is local
   * 
   * @param uri
   *          - key of ServiceEnvironment
   * @return version string
   */
  public String getVersion(URI uri) {
    ServiceEnvironment local = environments.get(uri);
    if (local != null) {
      return local.platform.getVersion();
    }

    error("can't get local version in service environment");

    return null;
  }

  /**
   * event fired when a new artifact is download
   * 
   * @param module
   * @return
   */
  public String newArtifactsDownloaded(String module) {
    return module;
  }

  // FIXME THIS IS NOT NORMALIZED !!!
  static public Status noWorky(String userId) {
    Status status = null;
    try {
      String retStr = HTTPRequest.postFile("http://myrobotlab.org/myrobotlab_log/postLogFile.php", userId, "file", new File("../agent.log"));
      if (retStr.contains("Upload:")) {
        log.info("noWorky successfully sent - our crack team of experts will check it out !");
        status = Status.info("no worky sent");
      } else {
        status = Status.error("could not send");
      }
    } catch (Exception e) {
      log.error("the noWorky didn't worky !");
      status = Status.error(e);
    }

    // this makes the 'static' of this method pointless
    // perhaps the webgui should invoke rather than call directly .. :P
    Runtime.getInstance().invoke("publishNoWorky", status);
    return status;
  }

  /**
   * published results of sending a noWorky
   * 
   * @param status
   * @return
   */
  static public Status publishNoWorky(Status status) {
    return status;
  }

  // -------- network begin ------------------------

  /**
   * this method is an event notifier that there were updates found
   */
  public ServiceData proposedUpdates(ServiceData si) {
    return si;
  }

  public String publishMessage(String msg) {
    return msg;
  }

  // -------- network end ------------------------

  // http://stackoverflow.com/questions/16610525/how-to-determine-if-graphicsenvironment-exists

  // FIXME - this is important in the future
  @Override
  public void receive(Message msg) {
    // TODO Auto-generated method stub

  }

  // ---------------- callback events begin -------------
  /**
   * registration event
   * 
   * @param path
   *          - the name of the Service which was successfully registered
   * @return
   */
  public ServiceInterface registered(ServiceInterface sw) {
    return sw;
  }

  /**
   * release event
   * 
   * @param path
   *          - the name of the Service which was successfully released
   * @return
   */
  public ServiceInterface released(ServiceInterface sw) {
    return sw;
  }

  /**
   * published events
   * 
   * @param className
   * @return
   */
  public String resolveBegin(String className) {
    return className;
  }

  public void resolveEnd() {
  }

  /**
   * 
   * @param errors
   * @return
   */
  public List<String> resolveError(List<String> errors) {
    return errors;
  }

  /**
   * 
   * @param className
   * @return
   */
  public String resolveSuccess(String className) {
    return className;
  }

  /**
   * FIXME - need to extend - communication to Agent ??? process request restart
   * ???
   * 
   * restart occurs after applying updates - user or config data needs to be
   * examined and see if its an appropriate time to restart - if it is the
   * spawnBootstrap method will be called and bootstrap.jar will go through its
   * sequence to update myrobotlab.jar
   */
  public void restart() {
    try {
      info("restarting");
      // TODO - timeout release .releaseAll nice ? - check or re-implement
      Runtime.releaseAll();
      // Bootstrap.spawn(args.toArray(new String[args.size()]));
      System.exit(0);

      // shutdown / exit
    } catch (Exception e) {
      Logging.logError(e);
    }
  }

  /**
   * Runtime's setLogLevel will set the root log level if its called from a
   * service - it will only set that Service type's log level
   * 
   */
  @Override
  public String setLogLevel(String level) {
    Logging logging = LoggingFactory.getInstance();
    logging.setLevel(level);
    return level;
  }

  /**
   * Stops all service-related running items. This releases the singleton
   * referenced by this class, but it does not guarantee that the old service
   * will be GC'd. FYI - if stopServices does not remove INSTANCE - it is not
   * re-entrant in junit tests
   */
  @Override
  public void stopService() {
    super.stopService();
    runtime = null;
  }

  public static void clearErrors() {
    ServiceEnvironment se = getLocalServices();
    for (String name : se.serviceDirectory.keySet()) {
      se.serviceDirectory.get(name).clearLastError();
    }
  }

  public static boolean hasErrors() {
    ServiceEnvironment se = getLocalServices();

    for (String name : se.serviceDirectory.keySet()) {
      if (se.serviceDirectory.get(name).hasError()) {
        return true;
      }
    }
    return false;
  }

  /**
   * remove all subscriptions from all local Services
   */
  static public void removeAllSubscriptions() {
    ServiceEnvironment se = getEnvironment(null);
    Set<String> keys = se.serviceDirectory.keySet();
    for (String name : keys) {
      ServiceInterface si = getService(name);
      ArrayList<String> nlks = si.getNotifyListKeySet();
      for (int i = 0; i < nlks.size(); ++i) {
        si.getOutbox().notifyList.clear();
      }
    }
  }

  public static ArrayList<Status> getErrors() {
    ArrayList<Status> stati = new ArrayList<Status>();
    ServiceEnvironment se = getLocalServices();
    for (String name : se.serviceDirectory.keySet()) {
      Status status = se.serviceDirectory.get(name).getLastError();
      if (status != null && status.isError()) {
        log.info(status.toString());
        stati.add(status);
      }
    }
    return stati;
  }

  public static void broadcastStates() {
    ServiceEnvironment se = getLocalServices();

    for (String name : se.serviceDirectory.keySet()) {
      se.serviceDirectory.get(name).broadcastState();
    }
  }

  public static Runtime get() {
    return Runtime.getInstance();
  }

  public static String getRuntimeName() {
    return Runtime.getInstance().getName();
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

    ServiceType meta = new ServiceType(Runtime.class.getCanonicalName());
    meta.addDescription("Runtime singleton service responsible for the creation and registry of all other services");
    meta.addCategory("framework");
    meta.addPeer("cli", "Cli", "command line interpreter for the runtime");

    return meta;
  }

  public ServiceData getServiceData() {
    return serviceData;
  }

}
