package app.unisono;
import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;

/** Installed only in the test APK; exercises the real Activity, service, socket and AudioTrack. */
public class SmokeTest extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private View find(View v,String text) {
        if(v instanceof Button && ((Button)v).getText().toString().equals(text)) return v;
        if(v instanceof ViewGroup) { ViewGroup g=(ViewGroup)v; for(int i=0;i<g.getChildCount();i++) { View hit=find(g.getChildAt(i),text); if(hit!=null) return hit; } } return null;
    }
    private void save(Activity a,String name) {
        runOnMainSync(()-> { try { View v=a.getWindow().getDecorView(); Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888); v.draw(new Canvas(b)); File f=new File(a.getExternalFilesDir(null),name); try(FileOutputStream o=new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG,100,o); } } catch(Exception e) { throw new RuntimeException(e); } });
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Intent intent=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("unisono://10.0.2.2:45871#11111111111111111111111111111111"),getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity a=startActivitySync(intent); waitForIdleSync(); Thread.sleep(1000); save(a,"android-idle.png");
            runOnMainSync(()-> { View b=find(a.getWindow().getDecorView(),"Conectar"); if(b==null) throw new AssertionError("Connect button missing"); b.performClick(); });
            long deadline=System.nanoTime()+12_000_000_000L;
            while(!AudioService.connected && System.nanoTime()<deadline) Thread.sleep(200);
            if(!AudioService.connected) throw new AssertionError("Connection failed: "+AudioService.status);
            Thread.sleep(6000);
            if(!AudioService.connected) throw new AssertionError("Playback failed: "+AudioService.status);
            save(a,"android-connected.png");
            runOnMainSync(()-> { View b=find(a.getWindow().getDecorView(),"Desconectar"); if(b==null) throw new AssertionError("Disconnect button missing"); b.performClick(); });
            Thread.sleep(1000); if(AudioService.connected||AudioService.connecting) throw new AssertionError("Service did not stop");
            result.putString("stream","PASS: Activity link import, encrypted Mac connection, 6 seconds AudioTrack playback, foreground service, explicit disconnect. "+AudioService.details); finish(Activity.RESULT_OK,result);
        } catch(Throwable e) { result.putString("stream","FAIL: "+e.toString()+"; "+AudioService.status); finish(Activity.RESULT_CANCELED,result); }
    }
}
