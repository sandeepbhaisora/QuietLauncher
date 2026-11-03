package dev.quiet.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One quiet, original synthesized acknowledgement hum, not TTS.
 * Perception cue only: does not accelerate LLM inference, repeat, or claim
 * live data access. Delayed until a request is demonstrably still pending.
 */
final class ThinkingHum {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicInteger token=new AtomicInteger();
    private final Context app;
    private volatile AudioTrack playing;
    private volatile boolean closed;

    ThinkingHum(Context context){app=context.getApplicationContext();}
    void begin(){
        final int request=token.incrementAndGet();
        if(closed || !app.getSharedPreferences("quiet_local_v1",Context.MODE_PRIVATE)
                .getBoolean("londe_thinking_hum",true))return;
        ui.postDelayed(()->{
            if(request!=token.get() || closed)return;
            worker.execute(()->play(request));
        },1000L);
    }
    void stop(){
        token.incrementAndGet();
        AudioTrack p=playing;
        if(p!=null){
            try{p.pause();p.flush();}catch(Exception ignored){}
        }
    }
    void close(){
        closed=true;
        stop();
        ui.removeCallbacksAndMessages(null);
        worker.shutdown();
    }
    private void play(int request){
        if(closed || request!=token.get())return;
        final int rate=16000, count=rate*32/100;
        short[] pcm=new short[count];
        for(int i=0;i<count;i++){
            double t=i/(double)rate;
            double fade=Math.min(1.0,Math.min(i/(double)(rate/25),
                    (count-1-i)/(double)(rate/10)));
            double low=Math.sin(2*Math.PI*185*t);
            double harmonic=.19*Math.sin(2*Math.PI*370*t);
            pcm[i]=(short)(Math.max(0,fade)*(low+harmonic)*3100);
        }
        AudioTrack track=null;
        try{
            track=new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(count*2).build();
            if(closed || request!=token.get())return;
            track.write(pcm,0,count);
            track.setVolume(0.23f);
            playing=track;
            track.play();
            Thread.sleep(370L);
        }catch(Exception ignored) {
            // Audio hints are optional. No error must block the real response.
        }finally{
            if(playing==track)playing=null;
            if(track!=null)try{track.stop();track.release();}catch(Exception ignored){}
        }
    }
}
