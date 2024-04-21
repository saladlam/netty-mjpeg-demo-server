package info.saladlam.example.netty;

import io.netty.buffer.ByteBuf;
import reactor.core.publisher.Flux;

public interface VideoSource extends Runnable {

    ByteBuf getFrame();

    Flux<ByteBuf> getFrameFlux();

}
