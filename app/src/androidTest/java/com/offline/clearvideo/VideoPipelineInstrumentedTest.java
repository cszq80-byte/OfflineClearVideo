package com.offline.clearvideo;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.content.Intent;
import android.provider.MediaStore;
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

    @Test public void galleryPickerFiltersVideos() {
        Intent modern=MainActivity.galleryIntent(true);
        assertEquals(MediaStore.ACTION_PICK_IMAGES, modern.getAction());
        assertEquals("video/*", modern.getType());
        assertTrue((modern.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        Intent fallback=MainActivity.galleryIntent(false);
        assertEquals(Intent.ACTION_GET_CONTENT, fallback.getAction());
        assertEquals("video/*", fallback.getType());
        assertTrue(fallback.hasCategory(Intent.CATEGORY_OPENABLE));
    }

    @Test public void detailStrengthChangesOutputAndCanPreserveFullAi(){
        byte[] baseline={(byte)100,(byte)150,(byte)220};
        byte[] neural={(byte)110,(byte)170,(byte)245};
        byte[] conservative=neural.clone();
        DetailMixer.mix(baseline,conservative,3,DetailMixer.gain(DetailMixer.NATURAL),80);
        assertTrue((conservative[0]&255)<(neural[0]&255));
        byte[] full=neural.clone();
        DetailMixer.mix(baseline,full,3,DetailMixer.gain(DetailMixer.BALANCED),255);
        assertArrayEquals(neural,full);
    }
    @Test public void realEsrganProducesDifferentDetailThanOrdinaryInterpolation() throws Exception {
        YuvEnhancer.Scratch scratch=new YuvEnhancer.Scratch(64,64,128,128);
        for(int y=0;y<64;y++)for(int x=0;x<64;x++)
            scratch.y[y*64+x]=(byte)(40+((x*13+y*7)%145));
        for(int i=0;i<scratch.u.length;i++) {
            scratch.u[i]=(byte)(80+i%80);
            scratch.v[i]=(byte)(120+i%70);
        }
        try(RealEsrganEnhancer model=new RealEsrganEnhancer(context)) {
            model.upscale(scratch,false,true,new AtomicBoolean());
        }
        byte[] baseline=new byte[128*128];
        YuvEnhancer.resize(scratch.y,64,64,baseline,128,128);
        long difference=0;
        for(int i=0;i<baseline.length;i++)
            difference+=Math.abs((scratch.oy[i]&255)-(baseline[i]&255));
        assertTrue("AI should not equal bilinear interpolation",difference>10000);
    }
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

    /** Tests actual Real-ESRGAN full-color frame processing, not just isolated inference. */
    @Test public void realEsrganVideoExportEndToEnd() throws Exception {
        File inputFile=new File(context.getCacheDir(),"strong_input.mp4");
        File outputFile=new File(context.getCacheDir(),"strong_output.mp4");
        try {
            encodeSampleVideo(inputFile);
            VideoProcessor.Result result=VideoProcessor.process(
                context,Uri.fromFile(inputFile),Uri.fromFile(outputFile),
                OutputProfile.QUALITY_AI,(progress,message)->{},new AtomicBoolean());
            assertEquals(128,result.width);
            assertEquals(128,result.height);
            assertTrue("No Real-ESRGAN video frames processed",result.frames>=5);
            assertTrue("Real-ESRGAN video output missing",outputFile.length()>500);
            MediaExtractor verifier=new MediaExtractor();
            try {
                verifier.setDataSource(outputFile.getAbsolutePath());
                boolean valid=false;
                for(int i=0;i<verifier.getTrackCount();i++) {
                    MediaFormat fmt=verifier.getTrackFormat(i);
                    if(fmt.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                        assertEquals(128,fmt.getInteger(MediaFormat.KEY_WIDTH));
                        assertEquals(128,fmt.getInteger(MediaFormat.KEY_HEIGHT));
                        verifier.selectTrack(i);
                        ByteBuffer sample=ByteBuffer.allocate(1024*1024);
                        assertTrue("No decodable video sample",verifier.readSampleData(sample,0)>0);
                        valid=true;
                    }
                }
                assertTrue("No valid video track",valid);
            } finally { verifier.release(); }
        } finally { inputFile.delete();outputFile.delete(); }
    }

    /** Verifies the service outlives Activity finish and still writes a valid MP4. */
    @Test public void foregroundServiceSurvivesActivityDestruction() throws Exception {
        File in=new File(context.getCacheDir(),"service_input.mp4");
        File out=new File(context.getCacheDir(),"service_output.mp4");
        try {
            assertFalse("Previous video job still active",ProcessingService.isWorking());
            encodeSampleVideo(in);
            Intent open=new Intent(context,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity=InstrumentationRegistry.getInstrumentation().startActivitySync(open);
            ProcessingService.start(context,Uri.fromFile(in),Uri.fromFile(out),OutputProfile.AI);
            // Destroy the Activity while the separate foreground service runs.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
            long deadline=SystemClock.elapsedRealtime()+120000;
            while(ProcessingService.isWorking()&&SystemClock.elapsedRealtime()<deadline)
                Thread.sleep(100);
            assertFalse("Foreground job timed out",ProcessingService.isWorking());
            assertTrue("Service job did not generate MP4: "+ProcessingService.snapshot().message,out.length()>500);
            MediaExtractor check=new MediaExtractor();
            try {
                check.setDataSource(out.getAbsolutePath());
                assertTrue(check.getTrackCount()>0);
            }finally{check.release();}
        }finally {
            if(ProcessingService.isWorking())ProcessingService.requestCancel(context);
            in.delete();out.delete();
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
