package org.myrobotlab.service.config;

import java.util.ArrayList;
import java.util.HashMap;

import org.myrobotlab.service.I2cMux.I2CDeviceMap;

public class I2cMuxConfig extends ServiceConfig {

  public String bus = "1";
  public String address = "0x70";
  public ArrayList<String> controllers = new ArrayList<String>();
  public HashMap<String, I2CDeviceMap> i2cDevices = new HashMap<String, I2CDeviceMap>();

}
