package org.myrobotlab.service;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.myrobotlab.framework.Service;
import org.myrobotlab.framework.interfaces.Attachable;
import org.myrobotlab.logging.Level;
import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.logging.Logging;
import org.myrobotlab.logging.LoggingFactory;
import org.myrobotlab.service.config.SerialRelayConfig;
import org.myrobotlab.service.data.SerialRelayData;
import org.myrobotlab.service.interfaces.SerialDataListener;
import org.myrobotlab.service.interfaces.SerialDevice;
import org.myrobotlab.service.interfaces.SerialRelayListener;
import org.slf4j.Logger;

public class SerialRelay extends Service<SerialRelayConfig> implements SerialDevice, Attachable {

  private static final long serialVersionUID = 1L;

  public final static Logger log = LoggerFactory.getLogger(SerialRelay.class);

  transient Arduino controller = null;

  private transient SerialRelayListener listener;

  private int controllerAttachAs;

  public SerialRelay(String n, String id) {
    super(n, id);
  }

  public static void main(String[] args) {
    try {

      LoggingFactory.init(Level.INFO);

      Runtime.start("template", "SerialRelay");
      Runtime.start("gui", "SwingGui");
      Runtime.start("python", "Python");

      Arduino arduino = (Arduino) Runtime.start("arduino", "Arduino");
      arduino.connect("COM15");
      Arduino arduino1 = (Arduino) Runtime.start("arduino1", "Arduino");
      // arduino1.connect(arduino, "Serial1"); HOW DO YOU EVEN attach to the
      // different pins
      // FIXME - should be arduino1.attach(arduino2.getSerial("s1"));
      // or - arduino1.attach(arduino2, "s3");
    } catch (Exception e) {
      Logging.logError(e);
    }
  }

  @Override
  public void open(String name) throws IOException {
    // TODO Auto-generated method stub

  }

  @Override
  public int read() throws Exception {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public void write(byte[] data) throws Exception {
    int[] cdata = new int[data.length];
    for (int i = 0; i < data.length; i++) {
      cdata[i] = data[i];
    }
    controller.msg.serialRelay(controller.getDeviceId(this), cdata);
  }

  @Override
  public void write(int data) throws Exception {
    // controller.msg.serialRelay(controller.getDeviceId(this), new byte[]
    // {(byte)data});
    controller.msg.serialRelay(controller.getDeviceId(this), new int[] { data });
  }

  public void attach(Arduino controller, SerialRelayListener listener, int controllerAttachAs) {
    this.controller = controller;
    this.listener = listener;
    this.controllerAttachAs = controllerAttachAs;
    if (controller != null) {
      subscribe(controller.getName(), "publishSerialData");
      controller.serialAttach(this, controllerAttachAs);
    }
  }

  // @Override
  public Attachable getController() {
    return controller;
  }

  @Override
  public boolean isAttached(String name) {
    return (controller != null && controller.getName().equals(name));
  }

  @Override
  public Set<String> getAttached() {
    HashSet<String> ret = new HashSet<String>();
    if (controller != null) {
      ret.add(controller.getName());
    }
    return ret;
  }

  public int[] onSerialData(SerialRelayData data) {
    if (data.deviceId == controller.getDeviceId(this)) {
      byte[] byteData = new byte[data.data.length];
      // TODO: convert serial relay data to pass around a byte array..
      for (int i = 0; i < data.data.length; i++) {
        byteData[i] = (byte) (data.data[i] & 0xFF);
      }
      if (listener instanceof SerialDataListener) {
        try {
          ((SerialDataListener) listener).onBytes(byteData);
        } catch (Exception e) {
          log.warn("Exception relaying serial data to listener. {}", byteData, e);
        }
      }
      return data.data;
    }
    return new int[0];// new byte[] {0};
  }

  // TODO - this could be Java 8 default interface implementation
  @Override
  public void detach(String controllerName) {
    if (controller == null || !controllerName.equals(controller.getName())) {
      return;
    }
    controller.detach(this);
    controller = null;
  }

  @Override
  public void clear() {
    // TODO Auto-generated method stub

  }

  @Override
  public void setTimeout(int timeoutMs) {
    // TODO Auto-generated method stub

  }

  @Override
  public void flush() {
    // TODO Auto-generated method stub

  }

  @Override
  public int available() {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public String publishConnect(String portName) {
    return portName;
  }

  @Override
  public String publishDisconnect(String portName) {
    return portName;
  }

  @Override
  public boolean isConnected() {
    return controller != null;
  }

  @Override
  public String getPortName() {
    return controller.getSerial().getPortName();
  }

  @Override
  public List<String> getPortNames() {
    return controller.getSerial().getPortNames();
  }

  @Override
  public void write(String data) throws Exception {
    write(data.getBytes());
  }

  @Override
  public void open(String portname, int rate, int dataBits, int stopBits, int parity) throws IOException {
    // TODO Auto-generated method stub

  }

  @Override
  public void close() throws IOException {
    // TODO Auto-generated method stub

  }

  @Override
  public int getRate() {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public int getDataBits() {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public int getStopBits() {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public int parity() {
    // TODO Auto-generated method stub
    return 0;
  }

}
