import app.unisono.Wire;
import java.nio.*;
import java.util.*;

public class WireTest {
    public static void main(String[] args) throws Exception {
        String link="unisono://127.0.0.1:45871#11111111111111111111111111111111";
        for(int run=0;run<2;run++) {
            try(Wire w=new Wire(link)) {
                for(int i=0;i<8;i++) { long t=System.nanoTime(); w.send(3,Wire.longBytes(t)); byte[] b=w.read(); if(b.length!=17||b[0]!=4||ByteBuffer.wrap(b,1,8).getLong()!=t) throw new AssertionError("Clock response"); }
                w.send(5,new byte[0]); if(w.read()[0]!=1) throw new AssertionError("Config");
                long index=0;
                for(int n=0;n<16;n++) {
                    byte[] b=w.read(); ByteBuffer p=ByteBuffer.wrap(b); if(p.get()!=2) throw new AssertionError("PCM type"); long pts=p.getLong(), ix=p.getLong(); int frames=p.getInt();
                    if(ix!=index||frames!=256||pts==0) throw new AssertionError("PCM header");
                    p.order(ByteOrder.LITTLE_ENDIAN);
                    for(int f=0;f<256;f++) for(int c=0;c<2;c++) { float expected=(float)(((index+f)*2+c)/8192.0-0.5); if(p.getInt()!=Float.floatToRawIntBits(expected)) throw new AssertionError("PCM changed at "+index); }
                    index+=256;
                }
                byte[] volume=ByteBuffer.allocate(4).putFloat(.625f).array(); w.send(6,volume); byte[] echo=w.read(); if(echo[0]!=6||!Arrays.equals(volume,Arrays.copyOfRange(echo,1,5))) throw new AssertionError("Control channel");
                w.send(7,new byte[0]); if(w.read()[0]!=7) throw new AssertionError("Explicit stop");
            }
            Thread.sleep(200);
        }
        boolean rejected=false;
        try(Wire bad=new Wire(link.replace("11111111111111111111111111111111","22222222222222222222222222222222"))) {} catch(Exception expected) { rejected=true; }
        if(!rejected) throw new AssertionError("Wrong key accepted");
        System.out.println("PASS: Swift ↔ Java AES-GCM, exact PCM samples (8192 stereo frames), clock sync messages, controls, reconnect, wrong-key rejection.");
    }
}
