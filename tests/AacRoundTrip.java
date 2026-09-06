package app.unisono;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Real Swift AAC encoder -> encrypted wire -> Android MediaCodec decoder. Synthetic tone only. */
public final class AacRoundTrip {
    public static void run(String link) throws Exception {
        for(int bitrate:new int[]{256000,160000}) {
            try(Wire wire=new Wire(link)) {
                wire.send(5,new JSONObject().put("codec","aac-lc").put("bitrate",bitrate).put("reserveMs",500).toString().getBytes(StandardCharsets.UTF_8));
                byte[] b=wire.read(); if(b[0]!=1) throw new AssertionError("AAC config missing");
                JSONObject config=new JSONObject(new String(b,1,b.length-1,StandardCharsets.UTF_8));
                if(!"aac-lc".equals(config.getString("format"))||config.getInt("bitrate")!=bitrate) throw new AssertionError("AAC negotiation");
                final long[] frames={0}; final double[] signal={0},error={0}; long bytes=0,expected=0;
                try(AacDecoder decoder=new AacDecoder(48000,config.getInt("primingFrames"),(pts,index,samples)-> {
                    if(index!=frames[0]) throw new AssertionError("Decoded sequence");
                    for(int n=0;n<samples.length;n++) {
                        if(!Float.isFinite(samples[n])) throw new AssertionError("Nonfinite AAC output");
                        if(index+n/2>=4096) { double value=Math.sin((index+n/2)*0.0576)*0.01; signal[0]+=value*value; error[0]+=Math.pow(samples[n]-value,2); }
                    }
                    frames[0]+=samples.length/2;
                })) {
                    while(frames[0]<48000) {
                        b=wire.read();if(b[0]!=10) throw new AssertionError("AAC packet missing");bytes+=b.length;
                        ByteBuffer h=ByteBuffer.wrap(b);h.get();long pts=h.getLong(),index=h.getLong();int count=h.getInt();
                        if(index!=expected||count!=1024) throw new AssertionError("Encoded sequence");expected+=count;
                        decoder.packet(pts,index,java.util.Arrays.copyOfRange(b,21,b.length));
                    }
                }
                double snr=10*Math.log10(signal[0]/error[0]);
                if(snr<30) throw new AssertionError("AAC alignment/quality SNR too low: "+snr+" dB");
                if(bytes>=48000*8/3) throw new AssertionError("AAC failed to reduce transport size");
                android.util.Log.i("UnisonoTest","AAC "+bitrate+" SNR="+snr+" dB, bytes="+bytes+", frames="+frames[0]);
            }
            Thread.sleep(300);
        }
    }
}
