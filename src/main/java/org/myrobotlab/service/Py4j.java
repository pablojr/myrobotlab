package org.myrobotlab.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bytedeco.javacpp.Loader;
import org.myrobotlab.codec.CodecUtils;
import org.myrobotlab.framework.Message;
import org.myrobotlab.framework.Platform;
import org.myrobotlab.framework.Service;
import org.myrobotlab.generics.SlidingWindowList;
import org.myrobotlab.io.FileIO;
import org.myrobotlab.io.StreamGobbler;
import org.myrobotlab.logging.Level;
import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.logging.LoggingFactory;
import org.myrobotlab.net.Connection;
import org.myrobotlab.service.config.Py4jConfig;
import org.myrobotlab.service.data.Script;
import org.myrobotlab.service.interfaces.Executor;
import org.myrobotlab.service.interfaces.Gateway;
import org.myrobotlab.service.interfaces.Processor;
import org.slf4j.Logger;

import py4j.GatewayServer;
import py4j.GatewayServerListener;
import py4j.Py4JServerConnection;

/**
 * 
 * 
 * A bridge between a native proces of Python running and MRL. Should support
 * any version of Python.
 * 
 * <pre>
 *  requirements: 
 * 
 *  1.  some version of python is installed and the python
 *      executable is available in the PATH.  Py4j service will start the
 *      default python and run a small script resource/Py4j/Py4j.py
 *      
 *  2.  pip install py4j.
 *  
 *  TODO:
 *  1.  Support multiple instances of Py4j running - this requires more management 
 *  of the service ports
 *  2. Perhaps asynchronous calling of handler ?
 *
 * </pre>
 * 
 * @author GroG
 */
public class Py4j extends Service<Py4jConfig> implements GatewayServerListener, Gateway, Processor {

  /**
   * POJO class to tie all the data elements of a external python process
   * together. Including the process handler, the std out, std err streams and
   * termination signal thread.
   * 
   * @author GroG
   *
   */
  class Py4jClient {
    // py4j connection
    public transient Py4JServerConnection connection;
    public transient StreamGobbler gobbler;
    public transient Process process;
    public transient Py4j py4j;
    public transient Thread waitFor;

    /* TODO figure out a way to connect the process to the connection */
    public Py4jClient() {
    }

    public Py4jClient(Py4j py4j, Process process) {
      this.process = process;
      this.py4j = py4j;
      this.gobbler = new StreamGobbler(String.format("%s-gobbler", getName()), process.getInputStream());
      this.gobbler.start();
      this.waitFor = new WaitForProcess(py4j, process);
      this.waitFor.start();

      log.info("process started {}", process);
    }
  }

  /**
   * A class to wait on a process signal and notify client is disconnected
   * 
   * @author GroG
   *
   */
  public class WaitForProcess extends Thread {
    public Integer exitCode;
    public Process process;
    public Py4j py4j;

    public WaitForProcess(Py4j py4j, Process process) {
      super(String.format("%s-process-signal", py4j.getName()));
      this.process = process;
      this.py4j = py4j;
    }

    @Override
    public void run() {
      try {
        exitCode = process.waitFor();
      } catch (InterruptedException e) {
      }
      warn("process %s terminated with exit code %d", process.toString(), exitCode);
    }
  }

  public final static Logger log = LoggerFactory.getLogger(Py4j.class);

  private static final long serialVersionUID = 1L;

  /**
   * py4j clients currently attached to this service
   */
  protected Map<String, Py4jClient> clients = new HashMap<>();

  /**
   * Java server side gateway for the python process to attach default port
   * 25333
   */
  private transient GatewayServer gateway = null;

  /**
   * the all important interface to the Python MessageHandler This defines what
   * Java can call that will rpc'd into Python's MessageHandler
   */
  private transient Executor handler = null;

  /**
   * a sliding window of logs
   */
  protected List<String> logs = new SlidingWindowList<>(300);

  /**
   * Opened scripts are scripts opened in memory, from there they can be
   * executed or saved to the file system, or updatd in memory which the js
   * client does
   */
  protected HashMap<String, Script> openedScripts = new HashMap<String, Script>();

  /**
   * client process and connectivity reference
   */
  protected Py4jClient pythonProcess = null;

  /**
   * The base command to launch the Python interpreter without any arguments.
   */
  protected transient String pythonCommand = "python";

  public Py4j(String n, String id) {
    super(n, id);
    // ready when connected
    ready = false;
  }

  /**
   * Add a new script to Py4j default location will be in
   * data/Py4j/{serviceName}
   * 
   * @param scriptName
   *          - name of the script
   * @param code
   *          - code block
   */
  public void addScript(String scriptName, String code) {
    Py4jConfig c = (Py4jConfig) config;
    File script = new File(c.scriptRootDir + fs + scriptName);

    if (script.exists()) {
      error("script %s already exists", scriptName);
      return;
    }

    openedScripts.put(scriptName, new Script(scriptName, code));
    broadcastState();
  }

  /**
   * If autostartPython is true, Py4j will start a process on starting and
   * connect the stdout/stdin streams to be redirected to the UI
   * 
   * @param b
   * @return
   */
  public boolean autostartPython(boolean b) {
    config.autostartPython = b;
    if (config.autostartPython && pythonProcess == null) {
      startPythonProcess();
    }
    return b;
  }

  /**
   * removes script from memory of openScripts
   * 
   * @param scriptName
   *          The name of the script to close.
   */
  public void closeScript(String scriptName) {
    openedScripts.remove(scriptName);
    broadcastState();
  }

  @Override
  public void connectionError(Exception e) {
    error(e);
  }

  @Override /* TODO add a one shot addTask to call handler.setName(name) */
  public void connectionStarted(Py4JServerConnection gatewayConnection) {
    try {
      log.info("connectionStarted {}", gatewayConnection.toString());
      clients.put(getClientKey(gatewayConnection), new Py4jClient());
      ready = true;
      info("connection started");
      invoke("getClients");
    } catch (Exception e) {
      error(e);
    }
  }

  @Override
  public void connectionStopped(Py4JServerConnection gatewayConnection) {
    info("connection stopped");
    clients.remove(getClientKey(gatewayConnection));
    invoke("getClients");
  }

  /**
   * One of 3 methods supported on the MessageHandler() callbacks
   * 
   * @param code
   *          The Python code to execute in the interpreter.
   */
  @Override
  public boolean exec(String code) {
    log.info(String.format("exec %s", code));
    try {
      if (handler != null) {
        handler.exec(code);
        return true;
      } else {
        error("handler is null");
      }
    } catch (Exception e) {
      error(e);
    }
    return false;
  }

  private String getClientKey(Py4JServerConnection gatewayConnection) {
    return String.format("%s:%d", gatewayConnection.getSocket().getInetAddress(), gatewayConnection.getSocket().getPort());
  }

  /**
   * get listing of filesystem files location will be data/Py4j/{serviceName}
   * 
   * @return
   * @throws IOException
   */
  public List<String> getScriptList() throws IOException {
    List<String> sorted = new ArrayList<>();
    System.out.println(CodecUtils.toJson(config));
    Py4jConfig c = (Py4jConfig) config;
    List<File> files = FileIO.getFileList(c.scriptRootDir, true);
    for (File file : files) {
      if (file.toString().endsWith(".py")) {
        sorted.add(file.toString().substring(c.scriptRootDir.length() + 1));
      }
    }
    Collections.sort(sorted);
    return sorted;
  }

  /**
   * Sink for standard output from Py4j-related subprocesses. This method
   * immediately publishes the output on {@link #publishStdOut(String)}.
   *
   * @param msg
   *          The output from a py4j related subprocess.
   */
  public void handleStdOut(String msg) {
    if (!"\n".equals(msg)) {
      logs.add(msg);
      invoke("publishStdOut", msg);
    }
  }

  /**
   * Potential entry point for python message
   * 
   * @param code
   */
  public void onPython(String code) {
    log.info("onPython {}", code);
    exec(code);
  }

  public String onPythonMessage(Message msg) {
    // create wrapper to tunnel incoming message - include original sender?
    Message tunnelMsg = Message.createMessage(msg.sender, getName(), "onPythonMessage", msg);
    String json = CodecUtils.toJson(tunnelMsg);
    handler.send(json);
    return json;
  }

  /**
   * Opens an example "service" script maintained in myrobotlab
   * 
   * @param serviceType
   *          the type of service
   * @throws IOException
   */
  public void openExampleScript(String serviceType) throws IOException {
    String filename = getResourceRoot() + fs + serviceType + fs + String.format("%s.py", serviceType);
    String serviceScript = null;
    try {
      serviceScript = FileIO.toString(filename);
    } catch (Exception e) {
      error("%s.py not  found", serviceType);
      log.error("getting service file script example threw", e);
    }
    addScript(serviceType + ".py", serviceScript);
  }

  /**
   * Opens an existing script. All file operations will be relative to the
   * data/Py4j/{serviceName} directory.
   * 
   * @param scriptName
   *          - name of the script file relatie to scriptRootDir
   *          data/Py4j/{serviceName}/
   * @throws IOException
   */
  public void openScript(String scriptName) throws IOException {
    Py4jConfig c = (Py4jConfig) config;
    File script = new File(c.scriptRootDir + fs + scriptName);

    if (!script.exists()) {
      error("file %s not found", script.getAbsolutePath());
      return;
    }

    openedScripts.put(scriptName, new Script(scriptName, FileIO.toString(script.getAbsoluteFile())));
    broadcastState();
  }

  @Override
  public boolean preProcessHook(Message msg) {
    // let the messages for this service
    // get processed normally
    if (methodSet.contains(msg.method)) {
      return true;
    }

    // TODO - determine clients are connected .. how many clients etc..
    try {
      if (handler != null) {
        // afaik - Py4j does some kind of magical encoding to get a JavaObject
        // back to the Python process, but:
        // 1. its useless for users - no way to access the content ?
        // 2. you can't do anything with it
        // So, I've chosen to json encode it here, and the Py4j.py
        // MessageHandler will
        // decode it into a Python dictionary \o/
        // we do single encoding including the parameter array - there is no
        // header needed
        // with method and other details, as the invoke here is invoking
        // directly in the
        // Py4j.py script

        String json = CodecUtils.toJson(msg);
        // handler.invoke(msg.method, json);
        log.info(String.format("handler %s", json));
        handler.send(json);
      } else {
        error("preProcessHook handler is null");
      }
    } catch (Exception e) {
      error(e);
    }
    return false;
  }

  public String publishStdOut(String data) {
    return data;
  }

  /**
   * Saves a script to the file system default will be in
   * data/Py4j/{serviceName}/{scriptName}
   * 
   * @param scriptName
   * @param code
   * @throws IOException
   */
  public void saveScript(String scriptName, String code) throws IOException {
    if (scriptName != null && !scriptName.toLowerCase().endsWith(".py")) {
      scriptName = scriptName + ".py";
    }
    FileIO.toFile(config.scriptRootDir + fs + scriptName, code);
    info("saved file %s", scriptName);
  }

  @Override
  public void serverError(Exception e) {
    error("server error");
    error(e);
  }

  @Override
  public void serverPostShutdown() {
    info("%s post shutdown", getName());
  }

  @Override
  public void serverPreShutdown() {
    info("%s pre shutdown", getName());
  }

  @Override
  public void serverStarted() {
    info("%s started", getName());
  }

  @Override
  public void serverStopped() {
    info("%s stopped", getName());
  }

  /**
   * start the gateway service listening on port
   */
  public void start() {
    try {
      if (gateway == null) {
        gateway = new GatewayServer(this);
        gateway.addListener(this);
        gateway.start();
        info("server started listening on %s:%d", gateway.getAddress(), gateway.getListeningPort());
        handler = (Executor) gateway.getPythonServerEntryPoint(new Class[] { Executor.class });

        // sleep(100);
        // String[] services = Runtime.getServiceNames();
        // sendRemote(Message.createMessage(getName(), "runtime",
        // "onServiceNames", services));

      } else {
        log.info("Py4j gateway server already started");
      }
    } catch (Exception e) {
      error(e);
    }
  }

  /**
   * function which start the python process and begins the client
   * MessageHandler and setup for runtime references to work
   */
  public void startPythonProcess() {
    try {

      // Specify the Python script path and arguments
      String pythonScript = new File(getResourceDir() + fs + "Py4j.py").getAbsolutePath();

      // Script requires full name as first command line argument
      String[] pythonArgs = { getFullName() };

      // Build the command to start the Python process
      ProcessBuilder processBuilder;
      if (((Py4jConfig) config).useBundledPython) {
        String venv = getDataDir() + fs + "venv";
        pythonCommand = (Platform.getLocalInstance().isWindows()) ? venv + fs + "Scripts" + fs + "python.exe" : venv + fs + "bin" + fs + "python";
        if (!FileIO.checkDir(venv)) {
          // We don't have an initialized virtual environment, so lets make one
          // and install our required packages
          String python = Loader.load(org.bytedeco.cpython.python.class);
          String venvLib = new File(python).getParent() + fs + "lib" + fs + "venv" + fs + "scripts" + fs + "nt";
          if (Platform.getLocalInstance().isWindows()) {
            // Super hacky workaround, venv works differently on Windows and
            // requires these two
            // files, but they are not distributed in bare-bones Python or in
            // any pip packages.
            // So we copy them where it expects, and it seems to work now
            FileIO.copy(getResourceDir() + fs + "python.exe", venvLib + fs + "python.exe");
            FileIO.copy(getResourceDir() + fs + "pythonw.exe", venvLib + fs + "pythonw.exe");
          }
          ProcessBuilder installProcess = new ProcessBuilder(python, "-m", "venv", venv);
          int ret = installProcess.inheritIO().start().waitFor();
          if (ret != 0) {
            error("Could not create virtual environment, subprocess returned {}. If on Windows, make sure there is a python.exe file in {}", ret, venvLib);
            return;
          }

          installProcess = new ProcessBuilder(pythonCommand, "-m", "pip", "install", "py4j");
          ret = installProcess.inheritIO().start().waitFor();
          if (ret != 0) {
            error("Could not install package, subprocess returned " + ret);
            return;
          }

        }

        // Virtual environment should exist, so lets use that python
      } else {
        // Just use the system python
        pythonCommand = "python";
      }
      processBuilder = new ProcessBuilder(pythonCommand, pythonScript);
      processBuilder.redirectErrorStream(true);
      processBuilder.command().addAll(List.of(pythonArgs));

      // Start the Python process
      pythonProcess = new Py4jClient(this, processBuilder.start());

    } catch (Exception e) {
      error(e);
    }
  }

  /**
   * Install a list of packages into the environment Py4j is running in. Py4j
   * does not need to be running/connected to call this method as it spawns a
   * new subprocess to invoke Pip. Output from pip is echoed via
   * {@link #handleStdOut(String)}.
   * 
   * @param packages
   *          The list of packages to install. Must be findable by Pip
   * @throws IOException
   *           If an I/O error occurs running Pip.
   */
  public void installPipPackages(List<String> packages) throws IOException {
    List<String> commandArgs = new ArrayList<>(List.of("-m", "pip", "install"));
    commandArgs.addAll(packages);
    ProcessBuilder pipProcess = new ProcessBuilder(pythonCommand);
    pipProcess.command().addAll(commandArgs);
    Process proc = pipProcess.redirectErrorStream(true).start();
    new Thread(() -> {
      BufferedReader stdOutput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
      String s;
      try {
        while ((s = stdOutput.readLine()) != null) {
          handleStdOut(s);
        }
      } catch (IOException e) {
        error(e);
      }
    }).start();
    int ret = 0;
    try {
      ret = proc.waitFor();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    if (ret != 0) {
      error("Could not install packages, subprocess returned " + ret);
    }
  }

  @Override
  public void startService() {
    super.startService();
    if (config.scriptRootDir == null) {
      config.scriptRootDir = new File(getDataInstanceDir()).getAbsolutePath();
    }
    File dataDir = new File(config.scriptRootDir);
    dataDir.mkdirs();
    // start the py4j socket server
    start();
    sleep(300);
    // start the python process which starts the Py4j.py MessageHandler
    if (config.autostartPython) {
      startPythonProcess();
    }
  }

  /**
   * stop the gateway service and teardown of the python process
   */
  public void stop() {
    if (gateway != null) {
      log.info("stopping py4j gateway");
      gateway.shutdown();
      gateway = null;
    } else {
      log.info("Py4j gateway server already stopped");
    }

    handler = null;

    if (pythonProcess != null) {
      log.info("shutting down python process");
      pythonProcess.process.destroy();
      pythonProcess.gobbler.interrupt();
      pythonProcess = null;
    }
  }

  /**
   * shutdown cleanly
   */
  @Override
  public void stopService() {
    super.stopService();
    stop();
  }

  /**
   * updates a script in memory
   * 
   * @param scriptName
   * @param code
   */
  public void updateScript(String scriptName, String code) {
    if (openedScripts.containsKey(scriptName)) {
      Script script = openedScripts.get(scriptName);
      script.code = code;
    } else {
      error("cannot find script %s to update", scriptName);
    }
  }

  public static void main(String[] args) {
    try {

      LoggingFactory.init(Level.INFO);

      WebGui webgui = (WebGui) Runtime.create("webgui", "WebGui");
      webgui.autoStartBrowser(false);
      webgui.startService();
      // Runtime.start("servo", "Servo");
      Runtime.start("py4j", "Py4j");
      // Runtime.start("python", "Python");

    } catch (Exception e) {
      log.error("main threw", e);
    }
  }

  @Override
  public void connect(String uri) throws Exception {
    // host:port of python process running py4j ???

  }

  /**
   * Remote in this context is the remote python process
   */
  @Override
  public void sendRemote(Message msg) throws Exception {
    log.info("sendRemote");
    String jsonMsg = CodecUtils.toJson(msg);
    log.info(String.format("sendRemote %s"));
    handler.send(jsonMsg);
  }

  @Override
  public boolean isLocal(Message msg) {
    return Runtime.getInstance().isLocal(msg);
  }

  @Override
  public List<String> getClientIds() {
    return Runtime.getInstance().getConnectionUuids(getName());
  }

  @Override
  public Map<String, Connection> getClients() {
    return Runtime.getInstance().getConnections(getName());
  }

  public void clear() {
    logs = new SlidingWindowList<>(300);
  }

}
