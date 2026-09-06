package app.unisono;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Shared wire codec: TCP framing + AES-256-GCM, with per-connection challenge-derived keys. */
public final class Wire implements Closeable {
    public final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private byte[] key;
    private final SecureRandom random=new SecureRandom();
    public static byte[] hmac(byte[] key,byte[] data) throws Exception { Mac m=Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key,"HmacSHA256")); return m.doFinal(data); }
    public static byte[] unhex(String s) {
        if(s==null||s.length()!=32) throw new IllegalArgumentException("La clave debe tener 32 caracteres hexadecimales");
        byte[] b=new byte[16]; for(int i=0;i<16;i++) { int a=Character.digit(s.charAt(i*2),16),c=Character.digit(s.charAt(i*2+1),16); if(a<0||c<0) throw new IllegalArgumentException("Clave no válida"); b[i]=(byte)((a<<4)|c); } return b;
    }
    public Wire(String link) throws Exception {
        URI uri=new URI(link.trim());
        if(!"unisono".equals(uri.getScheme())||uri.getHost()==null||uri.getPort()<1||uri.getPort()>65535) throw new IllegalArgumentException("Pega el enlace unisono:// de la Mac");
        byte[] secret=unhex(uri.getFragment()); socket=new Socket();
        try { socket.setTcpNoDelay(true); socket.connect(new InetSocketAddress(uri.getHost(),uri.getPort()),5000); socket.setSoTimeout(8000);
            in=new DataInputStream(new BufferedInputStream(socket.getInputStream(),65536)); out=new DataOutputStream(socket.getOutputStream());
            byte[] challenge=new byte[32]; in.readFully(challenge); out.write(hmac(secret,challenge)); out.flush();
            byte[] label="audio-v1".getBytes(StandardCharsets.UTF_8), material=new byte[challenge.length+label.length]; System.arraycopy(challenge,0,material,0,challenge.length); System.arraycopy(label,0,material,challenge.length,label.length); key=hmac(secret,material);
            byte[] welcome=read(); if(welcome[0]!=9 || !new String(welcome,1,welcome.length-1,StandardCharsets.UTF_8).equals("Unisono/1")) throw new IOException("Versión incompatible");
        } catch(Exception e) { socket.close(); throw e; }
    }
    public synchronized void send(int type,byte[] payload) throws Exception {
        byte[] nonce=new byte[12]; random.nextBytes(nonce); Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        byte[] plain=new byte[payload.length+1]; plain[0]=(byte)type; System.arraycopy(payload,0,plain,1,payload.length); byte[] encrypted=c.doFinal(plain);
        out.writeInt(nonce.length+encrypted.length); out.write(nonce); out.write(encrypted); out.flush();
    }
    public byte[] read() throws Exception {
        int n=in.readInt(); if(n<29||n>65536) throw new IOException("Longitud de paquete no válida"); byte[] nonce=new byte[12]; in.readFully(nonce); byte[] encrypted=new byte[n-12]; in.readFully(encrypted);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce)); return c.doFinal(encrypted);
    }
    public void close() { try { socket.close(); } catch(IOException ignored) {} }
    public static byte[] longBytes(long x) { return ByteBuffer.allocate(8).putLong(x).array(); }
}
