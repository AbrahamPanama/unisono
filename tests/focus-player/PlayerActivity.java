package app.unisono.focusfixture;
import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.widget.*;

/** A separate UID is essential: focus/fade enforcement is not a same-process test. */
public class PlayerActivity extends Activity {
    private AudioTrack track;
    private AudioManager manager;
    private AudioFocusRequest focus;
    private boolean granted;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b); TextView text=new TextView(this); text.setText("Independent audio-focus test player"); setContentView(text);
        manager=getSystemService(AudioManager.class);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setOnAudioFocusChangeListener(change->{ if(change<0) stop(); }).build();
        handle(getIntent());
    }
    @Override public void onNewIntent(Intent i) { super.onNewIntent(i); handle(i); }
    private void handle(Intent i) {
        String action=i.getStringExtra("mode");
        if(!"query".equals(action)) {
            stop(); manager.abandonAudioFocusRequest(focus);
            if("play".equals(action)) {
                granted=manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                if(granted) {
                    short[] pcm=new short[48000]; for(int n=0;n<pcm.length;n++) pcm[n]=(short)(Math.sin(n*2*Math.PI*440/48000)*300);
                    track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length*2).build();
                    track.write(pcm,0,pcm.length); track.setLoopPoints(0,pcm.length,-1); track.play();
                }
            }
        }
        sendBroadcast(new Intent("app.unisono.TEST_PLAYER_STATE").setPackage("app.unisono").putExtra("playing",granted && track!=null && track.getPlayState()==AudioTrack.PLAYSTATE_PLAYING));
    }
    private void stop() { granted=false; if(track!=null) { track.stop(); track.release(); track=null; } }
    @Override public void onDestroy() { stop(); manager.abandonAudioFocusRequest(focus); super.onDestroy(); }
}
