package app.unisono;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;

/** Local-only probe of this app on an authorized physical device. Never logs pairing data. */
public class DeviceTest extends Instrumentation {
    private int seconds;
    private boolean flacRoundTrip;
    @Override public void onCreate(Bundle b) { super.onCreate(b); seconds=Integer.parseInt(b.getString("seconds","20")); flacRoundTrip="true".equals(b.getString("flacRoundTrip")); start(); }
    private View find(View v) {
        if(v instanceof Button && ((Button)v).getText().toString().equals("Conectar")) return v;
        if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) { View b=find(((ViewGroup)v).getChildAt(i));if(b!=null)return b; }return null;
    }
    private Object field(AudioService s,String name) throws Exception { java.lang.reflect.Field f=AudioService.class.getDeclaredField(name);f.setAccessible(true);return f.get(s); }
    @Override public void onStart() {
        Bundle result=new Bundle(); int connected=0,transitions=0;boolean last=false;
        try {
            if(flacRoundTrip) {
                // adb reverse maps this synthetic-only fixture; saved pairing and playback settings stay untouched.
                FlacRoundTrip.run("unisono://127.0.0.1:45871#11111111111111111111111111111111");
                result.putString("result","PASS: FLAC exact24 native round-trip on physical device");
                finish(Activity.RESULT_OK,result); return;
            }
            Activity a=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync();Thread.sleep(500);
            runOnMainSync(()-> { View b=find(a.getWindow().getDecorView());if(b!=null)b.performClick(); });
            for(int i=0;i<seconds;i++) {
                Thread.sleep(1000); AudioService s=AudioService.instance;boolean active=AudioService.connected;
                if(active)connected++;if(last&&!active)transitions++;last=active;
                String info="t="+i+" connected="+active+" status="+AudioService.status+" detail="+AudioService.details+" route="+AudioService.route;
                if(s!=null) info+=" sync="+field(s,"syncMs")+" underruns="+field(s,"underruns")+" failure="+field(s,"playbackFailure");
                android.util.Log.i("UnisonoDeviceTest",info.replaceAll("unisono://\\S+","[pairing link]"));
            }
            result.putString("result","Observed connected="+connected+"/"+seconds+" seconds; disconnect transitions="+transitions);finish(Activity.RESULT_OK,result);
        } catch(Throwable e) { result.putString("result",flacRoundTrip ? ("FLAC exact24 probe failed: "+e.toString()).replaceAll("unisono://\\S+","[pairing link]") : "Probe failed: "+e.getClass().getSimpleName());finish(Activity.RESULT_CANCELED,result); }
    }
}
