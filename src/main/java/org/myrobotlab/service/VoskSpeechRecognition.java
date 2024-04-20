package org.myrobotlab.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

import org.myrobotlab.framework.Message;
import org.myrobotlab.framework.Service;
import org.myrobotlab.logging.Level;
import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.logging.LoggingFactory;
import org.myrobotlab.net.WsClient;
import org.myrobotlab.service.abstracts.AbstractSpeechRecognizer;
import org.myrobotlab.service.config.VoskSpeechRecognitionConfig;
import org.myrobotlab.service.data.Locale;
import org.myrobotlab.service.interfaces.ConnectionEventListener;
import org.myrobotlab.service.interfaces.TextListener;
import org.slf4j.Logger;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * @author pablojr (at) gmail.com
 */

public class VoskSpeechRecognition extends AbstractSpeechRecognizer<VoskSpeechRecognitionConfig> {

    private static final long serialVersionUID = 1L;

    public final static Logger log = LoggerFactory.getLogger(VoskSpeechRecognition.class);

    final transient VoskWsListener poller;

    private TargetDataLine line = null;
    private boolean isLineAcquired = false;

    private ArrayList<String> results = new ArrayList<String>();
    private CountDownLatch recieveLatch;

    HashSet<String> lockPhrases = new HashSet<String>();
    HashMap<String, Message> confirmations = null;
    HashMap<String, Message> negations = null;
    HashMap<String, Message> bypass = null;

    Message currentCommand = null;

    public VoskSpeechRecognition(String n, String id) {
        super(n, id);
        acquireInputLine();
        poller = new VoskWsListener(this);
    }

    @Override
    public void releaseService() {
        poller.stop();
        super.releaseService();
    }

    @Override
    public void startService() {
        super.startService();
        VoskSpeechRecognitionConfig c = (VoskSpeechRecognitionConfig) config;
        if (c.listening) {
            poller.start();
        }
    }

    public void addResults(String result) {
        this.results.add(result);
    }

    /*
     * public void addBypass(String... txt) {
     * if (bypass == null) {
     * bypass = new HashMap<String, Message>();
     * }
     * Message bypassCommand = Message.createMessage(getName(), getName(), "bypass",
     * null);
     * 
     * for (int i = 0; i < txt.length; ++i) {
     * bypass.put(txt[i], bypassCommand);
     * }
     * }
     */

    public void addComfirmations(String... txt) {
        if (confirmations == null) {
            confirmations = new HashMap<String, Message>();
        }
        Message confirmCommand = Message.createMessage(getName(), getName(), "confirmation", null);
        for (int i = 0; i < txt.length; ++i) {
            confirmations.put(txt[i], confirmCommand);
        }
    }

    public void addNegations(String... txt) {
        if (negations == null) {
            negations = new HashMap<String, Message>();
        }
        Message negationCommand = Message.createMessage(getName(), getName(), "negation", null);

        for (int i = 0; i < txt.length; ++i) {
            negations.put(txt[i], negationCommand);
        }
    }

    @Override
    public void addTextListener(TextListener service) {
        addListener("publishText", service.getName(), "onText");
    }

    public void addVoiceRecognitionListener(Service s) {
        // TODO: reflect on a public heard method - if doesn't exist error ?
        this.addListener("recognized", s.getName(), "heard");
    }

    @Override
    public Map<String, Locale> getLocales() {
        return Locale.getLocaleMap("en-US");
    }

    @Override
    public String publishText(String recognizedText) {
        return recognizedText;
    }

    /**
     * The main output for this service.
     * 
     * @return the word that has been recognized
     */
    @Override
    public String recognized(String word) {
        return word;
    }

    @Override
    public void startListening() {
        poller.start();
    }

    @Override
    public void stopListening() {
        poller.stop();
    }

    public static void main(String[] args) {
        LoggingFactory.init(Level.DEBUG);

        /*
         * VoskSpeechRecognition client = new VoskSpeechRecognition("ear", "Vosk");
         * ArrayList<String> asr = client.asr(line);
         * for (String res : asr) {
         * log.info("Recognized speech: {}", res);
         * }
         */
    }

    // *******************************************************************

    private void acquireInputLine() {
        // It must be a:
        // 16 kHz (or 8 kHz, depending on the training data)
        // 16 bit Mono (i.e. single channel) Big-Endian
        AudioFormat.Encoding encoding = AudioFormat.Encoding.PCM_SIGNED;
        float rate = 16000.0f;
        int channels = 1;
        int sampleSize = 16;
        boolean bigEndian = false;
        AudioFormat format = new AudioFormat(encoding, rate, sampleSize, channels, (sampleSize / 8) * channels, rate,
                bigEndian);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        boolean isLineSupported = AudioSystem.isLineSupported(info);
        log.info("Line matching {} {} supported.", info, (isLineSupported ? "is" : "not"));
        if (isLineSupported) {
            try {
                line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();
                log.info("Line open");
                isLineAcquired = true;
            } catch (Throwable e) {
                log.error("Issue with line {}", info, e);
            }
        }
    }

    // *******************************************************************

    public class VoskWsListener extends WebSocketListener implements ConnectionEventListener {

        private transient VoskSpeechRecognition service;
        private WsClient websocket;

        public VoskWsListener(VoskSpeechRecognition service) {
            this.service = service;
        }

        public void stop() {
            disconnect();
        }

        public void start() {
            try {
                websocket = new WsClient();
                websocket.connect(this, getWebsocketUrl());
            } catch (Exception e) {
                service.error(e);
            }
        }

        private String getWebsocketUrl() {
            VoskSpeechRecognitionConfig c = (VoskSpeechRecognitionConfig) config;
            return String.format("ws://%s:%d", c.getHost(), c.getPort());
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            AudioInputStream audioInputStream = new AudioInputStream(line);
            AudioFormat format = audioInputStream.getFormat();
            int bytesPerFrame = format.getFrameSize();
            if (bytesPerFrame == AudioSystem.NOT_SPECIFIED) {
                bytesPerFrame = 1;
            }

            // Let Vosk server now the sample rate of sound stream (from microphone)
            String configMessage = "{ \"config\" : { \"sample_rate\" : " + (int) format.getSampleRate() + " } }";
            websocket.send(configMessage);

            // Set an arbitrary buffer size of 1024 frames.
            int numBytes = 1024 * bytesPerFrame;
            byte[] audioBytes = new byte[numBytes];
            try {
                // Try to read numBytes bytes from the microphone.
                while ((audioInputStream.read(audioBytes)) != -1) {
                    recieveLatch = new CountDownLatch(1);
                    websocket.send(ByteString.of(audioBytes));
                    recieveLatch.await();
                }
                disconnect();
            } catch (InterruptedException e) {
                log.error("Thread interrupted.", e);
            } catch (IOException ex) {
                log.error("Cannot connect to offline speech server at {}", getWebsocketUrl(), ex);
            }
            // Recognition has finished, let the world know about the results!
            // invoke()
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            // TODO Auto-generated method stub
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            if (service.isRunning()) {
                info("could not connect to ... %s", getWebsocketUrl());
                sleep(5000);
                websocket.connect(this, getWebsocketUrl());
            }
        }

        public void onMessage(WebSocket webSocket, String text) {
            if (!text.contains("partial")) {
                service.addResults(text);
                log.info("Recognized {}", text);
            }
            recieveLatch.countDown();
        }

        private void disconnect() {
            recieveLatch = new CountDownLatch(1);
            websocket.send("{\"eof\" : 1}");
            try {
                recieveLatch.await();
            } catch (InterruptedException e) {
                log.error("Thread interrupted.", e);
            }
            websocket.close();
        }
    }
}
