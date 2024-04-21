package info.saladlam.example.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.PooledByteBufAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class TestcardSource implements VideoSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestcardSource.class);

    private final PooledByteBufAllocator pool = PooledByteBufAllocator.DEFAULT;
    private final BufferedImage background;
    private final Sinks.Many<ByteBuf> sink = Sinks.many().multicast().directBestEffort();

    private volatile ByteBuf current;
    private static final AtomicReferenceFieldUpdater<TestcardSource, ByteBuf> CURRENT = AtomicReferenceFieldUpdater.newUpdater(TestcardSource.class, ByteBuf.class, "current");

    private volatile int wip = 0;
    private static final AtomicIntegerFieldUpdater<TestcardSource> WIP = AtomicIntegerFieldUpdater.newUpdater(TestcardSource.class, "wip");

    public TestcardSource() {
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream("plate.bmp")) {
            background = ImageIO.read(stream);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BufferedImage cloneBackground() {
        ColorModel colorModel = background.getColorModel();
        boolean isAlphaPremultiplied = colorModel.isAlphaPremultiplied();
        WritableRaster writableRaster = background.copyData(background.getRaster().createCompatibleWritableRaster());
        return new BufferedImage(colorModel, writableRaster, isAlphaPremultiplied, null);
    }

    private void updateFrame() {
        ByteBuf old = current;
        BufferedImage newImage = cloneBackground();
        ByteBuf newByteBuf = pool.directBuffer(newImage.getHeight() * newImage.getWidth() * 3);

        Graphics g = newImage.getGraphics();
        g.setFont(new Font("SansSerif", Font.BOLD, 70));
        g.setColor(Color.WHITE);
        g.drawString(LocalTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_TIME), 819, 882);

        ByteBufOutputStream stream = new ByteBufOutputStream(newByteBuf);
        try {
            ImageIO.write(newImage, "jpg", stream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        CURRENT.set(this, newByteBuf);
        sink.tryEmitNext(newByteBuf);
        if (Objects.nonNull(old)) {
            old.release();
        }
    }

    @Override
    public void run() {
        if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
            updateFrame();
            WIP.decrementAndGet(this);
        } else {
            LOGGER.warn("Frame creation is no finished");
        }
    }

    @Override
    public ByteBuf getFrame() {
        return current.duplicate();
    }

    @Override
    public Flux<ByteBuf> getFrameFlux() {
        return sink.asFlux();
    }

}
