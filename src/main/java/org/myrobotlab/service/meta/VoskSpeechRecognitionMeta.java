package org.myrobotlab.service.meta;

import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.service.meta.abstracts.MetaData;
import org.slf4j.Logger;

public class VoskSpeechRecognitionMeta extends MetaData {
  private static final long serialVersionUID = 1L;
  public final static Logger log = LoggerFactory.getLogger(VoskSpeechRecognitionMeta.class);

  /**
   * All the meta data details of the Vosk service, offline voice recognition:
   * its peers, dependencies, and all other meta data related to the service.
   */
  public VoskSpeechRecognitionMeta() {

    addDescription("open source offline speech recognition");
    addCategory("speech recognition");

    // details at https://github.com/TakahikoKawasaki/nv-websocket-client
    addDependency("com.neovisionaries", "nv-websocket-client", "2.14");

  }

}
