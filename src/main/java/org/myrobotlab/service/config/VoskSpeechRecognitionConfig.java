package org.myrobotlab.service.config;

public class VoskSpeechRecognitionConfig extends SpeechRecognizerConfig {

    public static final String VOSK_SERVER_HOSTNAME_DEFAULT = "localhost";
    public static final int VOSK_SERVER_PORT_DEFAULT = 2700;

    /**
     * Hostname (or IP address) of the Vosk speech recognition server to connect to
     */
    public String host;

    /**
     * Port the Vosk speech recognition server is listening to
     */
    int port;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
