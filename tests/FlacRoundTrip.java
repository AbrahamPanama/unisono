package app.unisono;

import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;

/** Swift FLAC -> encrypted wire -> Android decoder; exact 24-bit synthetic source samples. */
public final class FlacRoundTrip {
    private static final int RATE=48000, TARGET_FRAMES=49152;
    private static final int[] BOUNDARY={-8388608,-8388607,-65537,-1,0,1,65537,8388606,8388607};
    private interface Checked { void run() throws Exception; }
    private static float source(long frame,int channel) {
        long q=frame%4096<256 ? 0 : frame%4096<512 ? BOUNDARY[(int)((frame*2+channel)%9)] : ((frame*1103515245L+channel*12345)&0xffffff)-0x800000;
        return (float)q/8388608f;
    }
    private static void rejects(String name,Checked action) throws Exception {
        try { action.run(); } catch(Exception expected) { return; }
        throw new AssertionError("Invalid FLAC accepted: "+name);
    }
    public static void run(String link) throws Exception {
        byte[] streamInfo,firstPacket=null;
        final long[] decoded={0},origin={-1};
        long encoded=0,bytes=0;
        try(Wire wire=new Wire(link)) {
            wire.send(5,new JSONObject().put("codec","flac").put("reserveMs",250).put("testPattern","pcm24").toString().getBytes(StandardCharsets.UTF_8));
            byte[] b=wire.read(); if(b.length<2||b[0]!=1) throw new AssertionError("FLAC config missing");
            JSONObject config=new JSONObject(new String(b,1,b.length-1,StandardCharsets.UTF_8));
            if(!"flac".equals(config.getString("format"))||config.getInt("rate")!=RATE||config.getInt("channels")!=2||config.getInt("bitDepth")!=24)
                throw new AssertionError("FLAC 24-bit negotiation failed");
            streamInfo=Base64.decode(config.getString("streamInfo"),Base64.DEFAULT);
            if(streamInfo.length!=34) throw new AssertionError("FLAC STREAMINFO length");
            long packed=ByteBuffer.wrap(streamInfo,10,8).getLong();
            if((packed>>>44)!=RATE||((packed>>>41)&7)+1!=2||((packed>>>36)&31)+1!=24)
                throw new AssertionError("FLAC STREAMINFO precision/rate/channels");
            try(FlacDecoder decoder=new FlacDecoder(RATE,streamInfo,(pts,index,samples)-> {
                if(index!=decoded[0]||samples.length%2!=0||pts!=origin[0]+index*1_000_000_000L/RATE)
                    throw new AssertionError("Decoded FLAC sequence/timestamp");
                for(int n=0;n<samples.length;n++) {
                    float expected=source(index+n/2,n%2);
                    if(Float.floatToRawIntBits(samples[n])!=Float.floatToRawIntBits(expected))
                        throw new AssertionError("FLAC lost 24-bit sample at frame "+(index+n/2)+", channel "+(n%2)+": expected "+expected+", got "+samples[n]);
                }
                decoded[0]+=samples.length/2;
            })) {
                while(encoded<TARGET_FRAMES) {
                    b=wire.read(); if(b.length<=21||b[0]!=11) throw new AssertionError("FLAC packet missing");
                    ByteBuffer header=ByteBuffer.wrap(b); header.get(); long pts=header.getLong(),index=header.getLong(); int frames=header.getInt();
                    if(index!=encoded||frames!=1024) throw new AssertionError("Encoded FLAC sequence/block size");
                    if(origin[0]<0) origin[0]=pts;
                    if(pts!=origin[0]+index*1_000_000_000L/RATE) throw new AssertionError("Encoded FLAC timestamp");
                    byte[] packet=Arrays.copyOfRange(b,21,b.length); if(firstPacket==null) firstPacket=packet.clone();
                    decoder.packet(pts,index,frames,packet); encoded+=frames; bytes+=b.length;
                }
                decoder.finish();
            }
        }
        if(decoded[0]!=encoded||decoded[0]<48000) throw new AssertionError("FLAC sample count: "+decoded[0]+" / "+encoded);
        final byte[] metadata=streamInfo,packet=firstPacket;
        rejects("short metadata",()-> { try(FlacDecoder ignored=new FlacDecoder(RATE,new byte[33],(pts,index,samples)-> {})) {} });
        byte[] wrongPrecision=metadata.clone();
        long format=ByteBuffer.wrap(wrongPrecision,10,8).getLong();
        ByteBuffer.wrap(wrongPrecision,10,8).putLong((format&~(31L<<36))|(15L<<36));
        rejects("16-bit metadata",()-> { try(FlacDecoder ignored=new FlacDecoder(RATE,wrongPrecision,(pts,index,samples)-> {})) {} });
        rejects("rate mismatch",()-> { try(FlacDecoder ignored=new FlacDecoder(44100,metadata,(pts,index,samples)-> {})) {} });
        rejects("truncated frame",()-> {
            try(FlacDecoder decoder=new FlacDecoder(RATE,metadata,(pts,index,samples)-> {})) {
                decoder.packet(0,0,1024,Arrays.copyOf(packet,packet.length/2)); decoder.finish();
            }
        });
        rejects("invalid frame sync",()-> { try(FlacDecoder decoder=new FlacDecoder(RATE,metadata,(pts,index,samples)-> {})) { decoder.packet(0,0,1024,new byte[6]); } });
        rejects("discontinuous frame index",()-> { try(FlacDecoder decoder=new FlacDecoder(RATE,metadata,(pts,index,samples)-> {})) { decoder.packet(0,1024,1024,packet); } });
        android.util.Log.i("UnisonoTest","FLAC exact24 PASS: frames="+decoded[0]+", bytes="+bytes+", silence/boundaries/channel differences/LSBs and malformed input checks");
        Thread.sleep(300);
    }
}
