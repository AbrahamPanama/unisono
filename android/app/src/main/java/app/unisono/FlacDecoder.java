package app.unisono;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/** One raw FLAC frame per packet, decoded without reducing the negotiated 24-bit precision. */
public final class FlacDecoder implements AutoCloseable {
    public interface Output { void pcm(long pts,long index,float[] samples) throws Exception; }

    /** The caller must reconnect using PCM rather than silently play FLAC at 16-bit precision. */
    public static final class UnsupportedPrecisionException extends IOException {
        UnsupportedPrecisionException() { super("El decodificador FLAC no conserva los 24 bits; usa PCM"); }
    }

    private static final int MAX_BLOCK_FRAMES=8192, MAX_FRAME_BYTES=65536;
    private final MediaCodec codec;
    private final Output output;
    private final int rate,maxBlock,maxFrame;
    private final long totalFrames;
    private final ArrayDeque<Packet> pending=new ArrayDeque<>();
    private int encoding;
    private long nextIndex,origin;
    private boolean haveOrigin,outputFormatKnown,finishing,ended,closed;

    private static final class Packet {
        final long pts,index;
        final int frames;
        Packet(long pts,long index,int frames) { this.pts=pts; this.index=index; this.frames=frames; }
    }

    /** streamInfo contains exactly the 34-byte STREAMINFO payload, without a metadata header. */
    public FlacDecoder(int rate,byte[] streamInfo,Output output) throws Exception {
        if(rate<8000||rate>192000||streamInfo==null||streamInfo.length!=34||output==null)
            throw new IOException("Configuración FLAC no válida");
        byte[] info=streamInfo.clone();
        int minBlock=u16(info,0);
        maxBlock=u16(info,2);
        int minFrame=u24(info,4);
        maxFrame=u24(info,7);
        long packed=ByteBuffer.wrap(info,10,8).getLong();
        int metadataRate=(int)(packed>>>44),channels=(int)((packed>>>41)&7)+1,bits=(int)((packed>>>36)&31)+1;
        totalFrames=packed&0xfffffffffL;
        if(metadataRate!=rate||channels!=2||bits!=24||minBlock<16||maxBlock<minBlock||maxBlock>MAX_BLOCK_FRAMES
                ||maxFrame>MAX_FRAME_BYTES||(minFrame!=0&&maxFrame!=0&&minFrame>maxFrame))
            throw new IOException("STREAMINFO FLAC no compatible: se requieren estéreo y 24 bits");
        this.rate=rate; this.output=output;

        // Android's csd-0 is fLaC + metadata header + STREAMINFO. The high bit ends metadata.
        ByteBuffer csd=ByteBuffer.allocate(42);
        csd.put(new byte[]{'f','L','a','C',(byte)0x80,0,0,34}).put(info).flip();
        MediaFormat format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC,rate,2);
        format.setByteBuffer("csd-0",csd);
        format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_FLOAT);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,maxFrame>0 ? maxFrame : maxBlock*6+64);
        codec=MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC);
        try { codec.configure(format,null,null,0); codec.start(); }
        catch(Exception e) { try { codec.release(); } catch(Exception ignored) {} throw e; }
    }

    public void packet(long pts,long index,int frames,byte[] data) throws Exception {
        if(closed||finishing||ended) throw new IOException("El decodificador FLAC está cerrado");
        if(index!=nextIndex||frames<=0||frames>maxBlock||data==null||data.length<6||data.length>MAX_FRAME_BYTES
                ||(maxFrame!=0&&data.length>maxFrame)||(data[0]&255)!=255||(data[1]&0xfe)!=0xf8
                ||index>Long.MAX_VALUE-frames||(totalFrames!=0&&index+frames>totalFrames))
            throw new IOException("Secuencia FLAC no válida");
        if(!haveOrigin) { origin=pts; haveOrigin=true; }
        long expectedPts=Math.addExact(origin,toNanos(index));
        // Timestamps originate from the same integer sample clock; tolerate one rounded sample.
        if(pts<expectedPts-1_000_000_000L/rate||pts>expectedPts+1_000_000_000L/rate)
            throw new IOException("Reloj FLAC no continuo");
        int slot=inputSlot();
        ByteBuffer input=codec.getInputBuffer(slot);
        if(input==null||input.capacity()<data.length) throw new IOException("Paquete FLAC demasiado grande para el decodificador");
        input.clear(); input.put(data);
        if(pending.size()>=64) throw new IOException("El decodificador FLAC se atrasó");
        pending.addLast(new Packet(expectedPts,index,frames));
        nextIndex+=frames;
        codec.queueInputBuffer(slot,0,data.length,toMicros(index),0);
        drain(0);
    }

    /** Drain a finite test/file stream; live connections can simply close when stopped. */
    public void finish() throws Exception {
        if(closed) throw new IOException("El decodificador FLAC está cerrado");
        if(ended) return;
        if(!finishing) {
            if(totalFrames!=0&&nextIndex!=totalFrames) throw new IOException("FLAC terminó antes de completar sus muestras");
            int slot=inputSlot();
            finishing=true;
            codec.queueInputBuffer(slot,0,0,toMicros(nextIndex),MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
        long deadline=System.nanoTime()+1_000_000_000L;
        while(!ended) {
            drain(10000);
            if(System.nanoTime()>deadline) throw new IOException("El decodificador FLAC no terminó");
        }
        if(!pending.isEmpty()) throw new IOException("El decodificador FLAC perdió muestras");
    }

    private int inputSlot() throws Exception {
        long deadline=System.nanoTime()+1_000_000_000L;
        int slot;
        while((slot=codec.dequeueInputBuffer(10000))<0) {
            drain(0);
            if(System.nanoTime()>deadline) throw new IOException("El decodificador FLAC se atrasó");
        }
        return slot;
    }

    private void checkOutputFormat(MediaFormat format) throws IOException {
        if(format.getInteger(MediaFormat.KEY_SAMPLE_RATE)!=rate||format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)!=2)
            throw new IOException("El formato FLAC cambió durante la reproducción");
        encoding=format.containsKey(MediaFormat.KEY_PCM_ENCODING) ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
        if(encoding!=AudioFormat.ENCODING_PCM_FLOAT&&encoding!=AudioFormat.ENCODING_PCM_24BIT_PACKED&&encoding!=AudioFormat.ENCODING_PCM_32BIT)
            throw new UnsupportedPrecisionException();
        outputFormatKnown=true;
    }

    private void drain(long timeoutUs) throws Exception {
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        while(true) {
            int slot=codec.dequeueOutputBuffer(info,timeoutUs);
            timeoutUs=0;
            if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { checkOutputFormat(codec.getOutputFormat()); continue; }
            if(slot==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue;
            if(slot<0) return;
            try {
                if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0) {
                    if(!finishing) throw new IOException("FLAC terminó inesperadamente");
                    ended=true;
                }
                if(info.size==0||(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0) continue;
                if(!outputFormatKnown) checkOutputFormat(codec.getOutputFormat(slot));
                int bytes=encoding==AudioFormat.ENCODING_PCM_24BIT_PACKED ? 3 : 4;
                Packet expected=pending.peekFirst();
                // A complete raw FLAC frame yields the same number of samples, without priming.
                if(expected==null||info.size!=expected.frames*2*bytes||Math.abs(info.presentationTimeUs-toMicros(expected.index))>1)
                    throw new IOException("El decodificador FLAC cambió la secuencia de muestras");
                ByteBuffer buffer=codec.getOutputBuffer(slot);
                if(buffer==null||info.offset<0||info.size>buffer.capacity()-info.offset) throw new IOException("Salida FLAC incompleta");
                buffer.limit(info.offset+info.size); buffer.position(info.offset);
                buffer=buffer.slice().order(ByteOrder.nativeOrder());
                float[] samples=new float[expected.frames*2];
                for(int i=0;i<samples.length;i++) {
                    if(encoding==AudioFormat.ENCODING_PCM_FLOAT) {
                        float value=buffer.getFloat(),scaled=value*8388608f;
                        if(!Float.isFinite(value)||value< -1f||value>=1f||scaled!=(int)scaled)
                            throw new UnsupportedPrecisionException();
                        samples[i]=value;
                    } else if(encoding==AudioFormat.ENCODING_PCM_24BIT_PACKED) {
                        int a=buffer.get()&255,b=buffer.get()&255,c=buffer.get()&255;
                        int value=ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN ? a|(b<<8)|(c<<16) : (a<<16)|(b<<8)|c;
                        samples[i]=((value<<8)>>8)/8388608f;
                    } else {
                        int value=buffer.getInt();
                        // Q31 output for a 24-bit source has eight zero low bits and converts exactly.
                        if((value&255)!=0) throw new UnsupportedPrecisionException();
                        samples[i]=value/2147483648f;
                    }
                }
                pending.removeFirst();
                output.pcm(expected.pts,expected.index,samples);
            } finally { codec.releaseOutputBuffer(slot,false); }
        }
    }

    private long toNanos(long frames) { return Math.addExact(Math.multiplyExact(frames/rate,1_000_000_000L),(frames%rate)*1_000_000_000L/rate); }
    private long toMicros(long frames) { return Math.addExact(Math.multiplyExact(frames/rate,1_000_000L),(frames%rate)*1_000_000L/rate); }
    private static int u16(byte[] bytes,int offset) { return ((bytes[offset]&255)<<8)|(bytes[offset+1]&255); }
    private static int u24(byte[] bytes,int offset) { return ((bytes[offset]&255)<<16)|((bytes[offset+1]&255)<<8)|(bytes[offset+2]&255); }

    @Override public void close() {
        if(closed) return;
        closed=true; pending.clear();
        try { codec.stop(); } catch(Exception ignored) {}
        finally { try { codec.release(); } catch(Exception ignored) {} }
    }
}
