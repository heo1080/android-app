package com.dolphin.launcher.v1;

import android.content.Context;
import android.media.AudioTrack;

/** Short user-initiated PCM sweep for AUD-EQ-001. No vehicle DSP write. */
public final class EqualizerAudibleTest {
    private EqualizerAudibleTest(){}

    public static void play(Context context, OwnedAudioEqualizer eq){
        final Context app=context.getApplicationContext();
        new Thread(()->{
            AudioTrack track=null;
            try{
                final int rate=48000;
                track=eq.createOwnedPcmTrack(rate);
                if(track==null) return;
                int frames=rate*2;
                short[] pcm=new short[frames*2];
                double[] hz={100.0,1000.0,8000.0};
                for(int i=0;i<frames;i++){
                    int section=Math.min(2,(i*3)/frames);
                    double sample=Math.sin(2.0*Math.PI*hz[section]*i/rate)*0.16;
                    short s=(short)(sample*Short.MAX_VALUE);
                    pcm[i*2]=s; pcm[i*2+1]=s;
                }
                track.play();
                int written=track.write(pcm,0,pcm.length);
                VerificationEvidenceRuntime.recordPassiveEvent(app,"EQ_AUDIBLE_TEST",
                        "written_samples="+written+";duration_s=2;tones_hz=100,1000,8000;target=app-owned;byd_dsp_write=false");
                track.stop();
            }catch(Throwable t){
                VerificationEvidenceRuntime.recordPassiveEvent(app,"EQ_AUDIBLE_TEST_FAILED","error="+t.getClass().getSimpleName());
            }finally{
                if(track!=null) track.release();
                eq.releaseEffect();
            }
        },"v1-eq-audible-test").start();
    }
}
