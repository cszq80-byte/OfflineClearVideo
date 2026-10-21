package com.offline.clearvideo;

import android.content.Context;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Runtime smoke test: checks an actual local decode -> FSRCNN -> encode -> MP4. */
@RunWith(AndroidJUnit4.class)
public class VideoPipelineInstrumentedTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void modelIsBundledAndRunsOffline() throws Exception {
        byte[] y=new byte[64*64],out=new byte[128*128];
        Arrays.fill(y,(byte)110);
        try(AiEnhancer ai=new AiEnhancer(context)) {
            ai.upscale(y,64,64,out,false,new AtomicBoolean());
        }
        long sum=0;
        for(byte v:out)sum+=v&255;
        assertTrue("Neural network should emit real pixels",sum>0);
    }

    @Test public void decodeAiUpscaleAndExportMp4() throws Exception {
        File input=new File(context.getCacheDir(),"synthetic_input.mp4");
        File output=new File(context.getCacheDir(),"synthetic_upscaled.mp4");
        try {
            encodeSampleVideo(input);
            assertTrue("Sample MP4 not generated",input.length()>500);
            VideoProcessor.Result result=VideoProcessor.process(
                context,Uri.fromFile(input),Uri.fromFile(output),OutputProfile.AI,
                (progress,message)->{},new AtomicBoolean());
            assertEquals(128,result.width);
            assertEquals(128,result.height);
            assertTrue("No frames processed",result.frames>=5);
            assertTrue("Output MP4 empty",output.length()>500);
            MediaExtractor check=new MediaExtractor();
            try {
                check.setDataSource(output.getAbsolutePath());
                assertTrue("No output video track",check.getTrackCount()>0);
                boolean found=false;
                for(int i=0;i<check.getTrackCount();i++){
                    MediaFormat f=check.getTrackFormat(i);
                    if(f.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                        assertEquals(128,f.getInteger(MediaFormat.KEY_WIDTH));
                        assertEquals(128,f.getInteger(MediaFormat.KEY_HEIGHT));
                        found=true;
                    }
                }
                assertTrue(found);
            } finally { check.release(); }
        } finally {
            input.delete();output.delete();
        }
    }

    private static void fillPlane(Image.Plane plane,int width,int height,int value) {
        ByteBuffer b=plane.getBuffer();
        int row=plane.getRowStride(),stride=plane.getPixelStride();
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)
            b.put(y*row+x*stride,(byte)value);
    }

    private static void encodeSampleVideo(File file) throws Exception {
        final int w=64,h=64,frames=6;
        MediaCodec encoder=null;
        MediaMuxer muxer=null;
        boolean muxStarted=false;
        try {
            MediaFormat f=MediaFormat.createVideoFormat("video/avc",w,h);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            f.setInteger(MediaFormat.KEY_BIT_RATE,320_000);
            f.setInteger(MediaFormat.KEY_FRAME_RATE,15);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
            encoder=MediaCodec.createEncoderByType("video/avc");
            encoder.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer=new MediaMuxer(file.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            int submitted=0,track=-1;
            boolean sentEos=false,gotEos=false;
            long deadline=System.currentTimeMillis()+60000;
            while(!gotEos) {
                if(System.currentTimeMillis()>deadline)throw new IllegalStateException("Synthetic video encoder timeout");
                if(!sentEos) {
                    int idx=encoder.dequeueInputBuffer(10000);
                    if(idx>=0) {
                        if(submitted<frames) {
                            Image img=encoder.getInputImage(idx);
                            if(img==null)throw new IllegalStateException("Emulator video encoder has no YUV input image");
                            try{
                                fillPlane(img.getPlanes()[0],w,h,70+submitted*20);
                                fillPlane(img.getPlanes()[1],w/2,h/2,128);
                                fillPlane(img.getPlanes()[2],w/2,h/2,128);
                            }finally{img.close();}
                            encoder.queueInputBuffer(idx,0,w*h*3/2,submitted*1000000L/15,0);
                            submitted++;
                        }else{
                            encoder.queueInputBuffer(idx,0,0,frames*1000000L/15,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sentEos=true;
                        }
                    }
                }
                int idx=encoder.dequeueOutputBuffer(info,10000);
                if(idx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track=muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();muxStarted=true;
                }else if(idx>=0) {
                    if(info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                        assertTrue("No output format reported",muxStarted);
                        ByteBuffer b=encoder.getOutputBuffer(idx);
                        b.position(info.offset);b.limit(info.offset+info.size);
                        muxer.writeSampleData(track,b,info);
                    }
                    gotEos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    encoder.releaseOutputBuffer(idx,false);
                }
            }
        }finally{
            if(encoder!=null){try{encoder.stop();}catch(Exception ignored){}encoder.release();}
            if(muxer!=null){if(muxStarted)try{muxer.stop();}catch(Exception ignored){}muxer.release();}
        }
    }
}
