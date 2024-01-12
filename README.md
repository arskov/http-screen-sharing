# Share a computer desktop via HTTP

A sample app in Java that allows to shares a computer's desktop over HTTP using *Motion JPEG over HTTP* (`multipart/x-mixed-replace` MIME type). The program is only ~300 lines of code and doesn't use third-party libraries. It just uses pure JDK, `com.sun.net.httpserver.HttpServer` for HTTP serving and `java.awt` classes for screen capturing.

In order to compile it you need just clone the repo and use `javac` command
```
javac -cp . org/arsenyko/share/ShareDesktop.java
```
Then use `java` command to run it
```
java -cp . org.arsenyko.share.ShareDesktop
```
Access the stream using your browser `http://localhost:8080/stream`
