package info.saladlam.example.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.handler.codec.http.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Scanner;
import java.util.UUID;

public class MjpegDemoServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MjpegDemoServer.class);
    static final short CRLF_SHORT = (HttpConstants.CR << 8) | HttpConstants.LF;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        Scheduler clockScheduler = Schedulers.newSingle("clock");
        Scheduler s = Schedulers.parallel();
        // 5Hz
        Flux<Long> clockFlux = Flux.interval(Duration.ofMillis(200L), clockScheduler);
        VideoSource videoSource = new TestcardSource();
        clockFlux.subscribe(v -> s.schedule(videoSource));

        DisposableServer server =
                HttpServer.create()
                        .host("0.0.0.0").port(8080)
                        .doOnConnection(c -> c.addHandlerFirst(new LoggingHandler("info.saladlam.example.netty.MjpegDemoServer", LogLevel.DEBUG)))
                        .route(routes ->
                                routes
                                        .get("/mjpg/frame.jpg", (request, response) -> {
                                            response.addHeader(HttpHeaderNames.CONTENT_TYPE, "image/jpeg");
                                            return response.send(Mono.defer(
                                                    () -> Mono.just(videoSource.getFrame()).map(ByteBuf::retain)
                                            ));
                                        })
                                        .get("/mjpg/video.mjpg", (request, response) -> {
                                            Flux<ByteBuf> flux = videoSource.getFrameFlux();
                                            UUID uuid = UUID.randomUUID();

                                            response.addHeader(HttpHeaderNames.CONTENT_TYPE, String.format("multipart/x-mixed-replace; boundary=%s", uuid));
                                            return response.sendHeaders().send(
                                                    flux.map(v -> {
                                                        v.retain();
                                                        CompositeByteBuf ret = response.alloc().compositeBuffer();
                                                        ByteBuf subHeader = response.alloc().buffer(1024);
                                                        ByteBuf tail = response.alloc().buffer(2);
                                                        subHeader.writeCharSequence(String.format("--%s", uuid), StandardCharsets.US_ASCII);
                                                        subHeader.writeShort(CRLF_SHORT);
                                                        subHeader.writeCharSequence(String.format("%s: %s", HttpHeaderNames.CONTENT_TYPE, "image/jpeg"), StandardCharsets.US_ASCII);
                                                        subHeader.writeShort(CRLF_SHORT);
                                                        subHeader.writeCharSequence(String.format("%s: %s", HttpHeaderNames.CONTENT_LENGTH, v.readableBytes()), StandardCharsets.US_ASCII);
                                                        subHeader.writeShort(CRLF_SHORT);
                                                        subHeader.writeShort(CRLF_SHORT);
                                                        tail.writeShort(CRLF_SHORT);

                                                        ret.addComponents(true, subHeader, v, tail);
                                                        return ret;
                                                    })
                                            );
                                        })
                        )
                        .bindNow();

        server.onDispose().subscribe(v -> {},
                e -> LOGGER.warn("Exception throw", e),
                () -> LOGGER.info("Shutdown successfully")
        );

        LOGGER.info("Press double enter to shutdown");
        scanner.nextLine();
        clockScheduler.dispose();
        server.dispose();
    }

}
