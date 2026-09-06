package app.unisono;
import android.media.*;
import java.nio.*;
import java.io.*;

/** Raw AAC-LC access units to interleaved PCM. One instance per connection. */
public final class AacDecoder implements AutoCloseable {
    public interface Output { void pcm(long pts,long index,float[] samples) throws Exception; }
    private final MediaCodec codec;
    private final Output output;
    private final int rate;
    private int skip,encoding=AudioFormat.ENCODING_PCM_16BIT;
    private long origin=-1,frames;
    public AacDecoder(int rate,int priming,Output output) throws Exception {
        if(rate!=48000||priming<0||priming>8192) throw new IOException("Configuración AAC no válida");
        this.rate=rate;skip=priming;this.output=output;
        MediaFormat format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,rate,2);
        // MPEG-4 AudioSpecificConfig: AAC-LC, 48 kHz, stereo.
        format.setByteBuffer("csd-0",ByteBuffer.wrap(new byte[]{0x11,(byte)0x90}));
        format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_FLOAT);
        codec=MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        try { codec.configure(format,null,null,0); codec.start(); } catch(Exception e) { codec.release(); throw e; }
    }
    public void packet(long pts,long index,byte[] data) throws Exception {
        if(origin<0) origin=pts;
        long deadline=System.nanoTime()+1_000_000_000L;
        int slot;
        while((slot=codec.dequeueInputBuffer(10000))<0) { drain(); if(System.nanoTime()>deadline) throw new IOException("El decodificador AAC se atrasó"); }
        ByteBuffer in=codec.getInputBuffer(slot); if(in==null||in.capacity()<data.length) throw new IOException("Paquete AAC demasiado grande");
        in.clear(); in.put(data); codec.queueInputBuffer(slot,0,data.length,index*1_000_000/rate,0); drain();
    }
    private void drain() throws Exception {
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        while(true) {
            int slot=codec.dequeueOutputBuffer(info,0);
            if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f=codec.getOutputFormat();
                if(f.getInteger(MediaFormat.KEY_SAMPLE_RATE)!=rate||f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)!=2) throw new IOException("Formato AAC de salida no compatible");
                encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING) ? f.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                if(encoding!=AudioFormat.ENCODING_PCM_FLOAT&&encoding!=AudioFormat.ENCODING_PCM_16BIT) throw new IOException("Salida AAC no compatible");
            } else if(slot<0) return;
            else {
                try {
                    if(info.size==0||(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0) continue;
                    ByteBuffer b=codec.getOutputBuffer(slot); if(b==null) throw new IOException("Salida AAC vacía");
                    b.position(info.offset); b.limit(info.offset+info.size); b=b.slice().order(ByteOrder.LITTLE_ENDIAN);
                    int bytes=encoding==AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                    if(info.size%(bytes*2)!=0) throw new IOException("PCM AAC incompleto");
                    int n=info.size/(bytes*2),drop=Math.min(skip,n);skip-=drop;b.position(drop*2*bytes);n-=drop;
                    if(n>0) {
                        float[] samples=new float[n*2];
                        for(int i=0;i<samples.length;i++) samples[i]=bytes==4 ? b.getFloat() : b.getShort()/32768f;
                        output.pcm(origin+frames*1_000_000_000/rate,frames,samples);frames+=n;
                    }
                } finally { codec.releaseOutputBuffer(slot,false); }
            }
        }
    }
    @Override public void close() { try { codec.stop(); } catch(Exception ignored) {} finally { try { codec.release(); } catch(Exception ignored) {} } }
}
