package org.arsenyko.share;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * 
 * @author arseny.kovalchuk
 *
 */
@SuppressWarnings("restriction")
public class ShareScreen {
    
    private static final Logger logger = Logger.getLogger(ShareScreen.class.getName());

    private static final String ALL_IF = "0.0.0.0";
    private static final int PORT = 8080;
    
    private static final String GET = "GET";
    private static final String OPTIONS = "OPTIONS";
    
    static class ScreenCaptureListener {
        
        private static final String DASH = "--";
        private static final char LF = '\n';
        private static final String CONTENT_TYPE_IMAGE_JPEG = "Content-Type: image/jpeg";
        private static final String CONTENT_LENGTH = "Content-Length: ";
        private String boundary;
        private byte[] contentTypeHeaderBytes;
        private OutputStream stream;
        private HttpExchange exch;
        
        public ScreenCaptureListener(String boundary, HttpExchange exch) {
            this.boundary = boundary;
            this.exch = exch;
            this.stream = exch.getResponseBody();
            StringBuilder buffer = new StringBuilder().append(DASH).append(this.boundary).append(LF).append(CONTENT_TYPE_IMAGE_JPEG).append(LF);
            contentTypeHeaderBytes = buffer.toString().getBytes(StandardCharsets.US_ASCII);
        }
        
        public void onFrame(byte[] frameBytes) throws IOException {
            stream.write(contentTypeHeaderBytes);
            stream.write(getContentLengthHeaderBytes(frameBytes));
            stream.write(LF);
            stream.write(frameBytes);
            stream.write(LF);
            stream.write(LF);
            stream.flush();
        }
        
        private byte[] getContentLengthHeaderBytes(byte[] frameBytes) {
            StringBuilder buffer = new StringBuilder().append(CONTENT_LENGTH).append(String.valueOf(frameBytes.length)).append(LF);
            return buffer.toString().getBytes(StandardCharsets.US_ASCII);
        }

        public String getId() {
            return boundary;
        }
        
        public void close() {
            try {
                stream.flush();
                stream.close();
                exch.close();
            } catch (IOException e) {
                // I know, I know )
            }
        }
        
    }
    
    static class ScreenCaptureWorker extends Thread {

        private Map<String, ScreenCaptureListener> listeners = new ConcurrentHashMap<>();
        private Robot robot;
        private Rectangle screenRect;
        
        private AtomicBoolean running = new AtomicBoolean(true);
        
        public ScreenCaptureWorker() throws AWTException {
            this.robot = new Robot();
            this.screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        }
        
        @Override
        public void run() {
            // capture frame
            while (running.get()) {
                
                if (isInterrupted()) {
                    if (running.getAndSet(false) == true) {
                        closeListeners();
                        listeners.clear();
                    }
                    return;
                }

                // do not consume CPU if there are no listeners
                if (listeners.size() == 0) {
                    try {
                        Thread.sleep(1000);
                        continue;
                    } catch (InterruptedException e) {
                        logger.log(Level.WARNING, "Thread was interrupted");
                        closeListeners();
                        return;
                    }
                }
                
                long startTs = System.currentTimeMillis();
                BufferedImage screenFullImage = robot.createScreenCapture(screenRect);
                ByteArrayOutputStream baos = new ByteArrayOutputStream(1024 * 512); // 500Kb
                // probably we can write directly to the response's output stream, but we won't set content length than
                try {
                    ImageIO.write(screenFullImage, "jpeg", baos);
                } catch (IOException e) {
                    closeListeners();
                    logger.log(Level.SEVERE, "Cannot produce the frame", e);
                    return;
                }
                
                byte[] frame = baos.toByteArray();
                // send frame to listeners
                List<String> removeList = new LinkedList<>();
                // bad performance, we need async write
                for(ScreenCaptureListener listener : listeners.values()) {
                    try {
                        listener.onFrame(frame);
                    } catch (Exception e) {
                        logger.log(Level.FINE, "Error while writing to {0}, so removing the listener", listener.getId());
                        removeList.add(listener.getId());
                    }
                }
                // remove broken
                for(String id : removeList) {
                    listeners.remove(id);
                }
                // 15 FPS
                long endTs = System.currentTimeMillis();
                long timeLeft = 1000 / 15 - (endTs - startTs);
                if (timeLeft > 0) {
                    try {
                        Thread.sleep(timeLeft);
                    } catch (InterruptedException e) {
                        logger.log(Level.WARNING, "Thread was interrupted");
                        closeListeners();
                        return;
                    }
                }
            }
        }
        
        public void registerListener(ScreenCaptureListener listener) {
            if (running.get()) {
                listeners.put(listener.getId(), listener);
            }
        }
        
        public void unRegisterListener(ScreenCaptureListener listener) {
            if (running.get()) {
                listeners.remove(listener.getId());
            }
        }
        
        private void closeListeners() {
            if (listeners.size() > 0) {
                for (ScreenCaptureListener listener : listeners.values()) {
                    listener.close();
                }
                listeners.clear();
            }
        }
    }
    
    static class StreamHandler implements HttpHandler {
        
        private int activeConnections = 0;
        private Object workerMonitor = new Object();
        private ScreenCaptureWorker screenCaptureWorker;
        

        @Override
        public void handle(HttpExchange exch) throws IOException {
            final String method = exch.getRequestMethod();
            logger.log(Level.INFO, "Method {0}", method);
            
            // we allow only
            if (OPTIONS.equals(method) == false && GET.equals(method) == false) {
                exch.sendResponseHeaders(405, 0);
                exch.close();
                return;
            }
            final Headers responseHeaders = exch.getResponseHeaders();
            responseHeaders.add("Access-Control-Allow-Origin", "*");
            responseHeaders.add("Access-Control-Allow-Methods", "GET, OPTIONS");
            responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Content-Length, Content-Disposition, Content-Type, Content-Transfer-Encoding, Accept, Accept-Language, Accept-Encoding, User-Agent");
            
            // do not response body to OPTIONS
            if (OPTIONS.equalsIgnoreCase(method) == true) {
                exch.sendResponseHeaders(200, 0);
                exch.getResponseBody().flush();
                exch.close();
                return;
            }
            
            // proceed with GET
            final String boundary = getNewBoundary();
            responseHeaders.add("Content-Type", "multipart/x-mixed-replace;boundary=" + boundary);
            exch.sendResponseHeaders(200, 0);
            
            try {
                synchronized (workerMonitor) {
                    if (activeConnections == 0) {
                        // Start screen capturing
                        screenCaptureWorker = new ScreenCaptureWorker();
                        screenCaptureWorker.setDaemon(false);
                        screenCaptureWorker.start();
                    }
                    activeConnections++;
                }
                screenCaptureWorker.registerListener(new ScreenCaptureListener(boundary, exch));
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Something went wrong", e);
            }
            // do not close exch.close() since we are streaming
        }
        
        private String getNewBoundary() {
            return UUID.randomUUID().toString().replaceAll("-", "");
        }

        public void terminate() {
            screenCaptureWorker.interrupt();
        }
    }
    
    public static void main(String[] args) throws IOException {
        
        String addressStr = ALL_IF;
        int port = PORT;
        InetAddress address = null;
        // a little bit of CLI parameters
        if (args.length == 1) {
            addressStr = args[0];
        } else if (args.length == 2) {
            addressStr = args[0];
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                logger.log(Level.WARNING, "Using defult port 8080", e);
            }
        }
        try {
            address = InetAddress.getByName(addressStr);
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
        
        // create and start the server
        HttpServer server = HttpServer.create(new InetSocketAddress(address, port), 5); // allows 5 connections
        StreamHandler handler = new StreamHandler();
        server.createContext("/stream", handler);
        server.setExecutor(null);
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.log(Level.INFO, "Shutting down worker...");
            handler.terminate();
        }));
        
        server.start();
        
    }
    
}
